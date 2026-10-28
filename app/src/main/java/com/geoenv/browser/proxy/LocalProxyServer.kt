package com.geoenv.browser.proxy

import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocketFactory

/**
 * 单环境本地代理服务器（需求 3.6「单环境 Hosts 劫持」）。
 *
 * - 监听 127.0.0.1 随机端口，Gecko 侧通过 `network.proxy.*` prefs 指向本服务器；
 * - 支持 HTTP 正向代理语义（`CONNECT` 隧道 + 绝对 URI 请求）；
 * - **Hosts 劫持**：域名（含 `*.example.com` 通配）优先解析到指定 IP，可按环境独立配置；
 * - **代理链**：可选上游代理（HTTP / HTTPS / SOCKS5，支持 Basic 认证），
 *   劫持目标 IP 会透传给 SOCKS5 上游（ATYP=IPv4/IPv6），HTTP 上游则走 CONNECT；
 * - 纯本机回环监听，不对外暴露。
 *
 * 设计为"每环境一个实例"，Hosts 规则可热更新（SET_HOSTS 命令），上游与端口在
 * 环境启动时确定（端口需写入 DebugConfig YAML，故上游变更需重启环境）。
 */
class LocalProxyServer(
    @Volatile private var hostsRules: Map<String, String> = emptyMap(),
    private val upstream: Upstream? = null,
) {

    data class Upstream(
        val type: String,      // HTTP / HTTPS / SOCKS5
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    )

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    var port: Int = 0
        private set

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "geoenv-proxy").apply { isDaemon = true }
    }
    private val active = AtomicInteger(0)

    /** 启动并返回监听端口；失败返回 0 */
    fun start(): Int {
        if (running) return port
        return try {
            val s = ServerSocket()
            s.bind(InetSocketAddress("127.0.0.1", 0), 64)
            s.soTimeout = 0
            server = s
            port = s.localPort
            running = true
            pool.execute { acceptLoop(s) }
            Timber.i("LocalProxyServer started on 127.0.0.1:%d upstream=%s hosts=%d",
                port, upstream?.let { "${it.type}@${it.host}:${it.port}" } ?: "direct", hostsRules.size)
            port
        } catch (t: Throwable) {
            Timber.w(t, "LocalProxyServer start failed")
            0
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
    }

    /** Hosts 规则热更新（域名小写 → IP） */
    fun updateHosts(rules: Map<String, String>) {
        hostsRules = rules.entries.associate { it.key.lowercase() to it.value }
        Timber.i("LocalProxyServer hosts updated: %d rules", rules.size)
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val client = try {
                s.accept()
            } catch (_: Throwable) {
                break
            }
            if (active.get() > MAX_CONNECTIONS) {
                runCatching { client.close() }
                continue
            }
            pool.execute {
                active.incrementAndGet()
                try {
                    handleClient(client)
                } catch (t: Throwable) {
                    Timber.d("proxy conn end: %s", t.message)
                } finally {
                    active.decrementAndGet()
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())

        val head = readHead(input) ?: return
        val requestLine = head.first
        val headers = head.second

        val tokens = requestLine.split(" ")
        if (tokens.size < 3) {
            writeSimple(output, 400, "Bad Request")
            return
        }
        val method = tokens[0]
        val target = tokens[1]

        if (method.equals("CONNECT", ignoreCase = true)) {
            handleTunnel(target, input, output)
        } else {
            handleForward(method, target, tokens[2], input, output, headers)
        }
    }

    /* ------------------------- CONNECT 隧道 ------------------------- */

    private fun handleTunnel(target: String, input: InputStream, output: OutputStream) {
        val (host, port) = splitHostPort(target) ?: run {
            writeSimple(output, 400, "Bad Request")
            return
        }
        val remote = connectUpstreamTunnel(host, port) ?: run {
            writeSimple(output, 502, "Bad Gateway")
            return
        }
        try {
            output.write(
                "HTTP/1.1 200 Connection Established\r\nProxy-Agent: GeoEnv\r\n\r\n"
                    .toByteArray(StandardCharsets.UTF_8)
            )
            output.flush()
            pipe(input, output, remote)
        } finally {
            runCatching { remote.close() }
        }
    }

    /* ------------------------- 普通 HTTP 转发 ------------------------- */

    private fun handleForward(
        method: String,
        target: String,
        version: String,
        input: InputStream,
        output: OutputStream,
        headers: List<String>,
    ) {
        val uri = parseAbsoluteUri(target)
        val host = uri?.host ?: headers.firstOrNull { it.startsWith("Host:", true) }
            ?.substringAfter(":")?.trim()?.substringBefore(":")
            ?: run { writeSimple(output, 400, "Bad Request"); return }
        val port = uri?.port ?: headers.firstOrNull { it.startsWith("Host:", true) }
            ?.trim()?.substringAfterLast(":")?.toIntOrNull() ?: 80
        val path = if (uri != null && uri.path.isNotEmpty()) uri.path + (uri.query?.let { "?$it" } ?: "") else target

        val remote = connectUpstream(host, port) ?: run {
            writeSimple(output, 502, "Bad Gateway")
            return
        }
        try {
            val remoteOut = BufferedOutputStream(remote.getOutputStream())
            val remoteIn = BufferedInputStream(remote.getInputStream())

            if (upstream != null && !upstream.type.equals("SOCKS5", true)) {
                // 上游是 HTTP/HTTPS 代理：保持绝对 URI 形式原样转发
                remoteOut.write("$method $target $version\r\n".toByteArray(StandardCharsets.UTF_8))
            } else {
                // 直连或 SOCKS 上游：改写为源形式并补 Host 头
                remoteOut.write("$method $path $version\r\n".toByteArray(StandardCharsets.UTF_8))
                if (headers.none { it.startsWith("Host:", true) }) {
                    remoteOut.write("Host: $host:$port\r\n".toByteArray(StandardCharsets.UTF_8))
                }
            }
            // 去掉逐跳代理头；若上游为需认证的 HTTP 代理则补上认证头
            headers.filterNot {
                it.startsWith("Proxy-Authorization", true) ||
                    it.startsWith("Proxy-Connection", true)
            }.forEach { h ->
                remoteOut.write((h + "\r\n").toByteArray(StandardCharsets.UTF_8))
            }
            if (upstream != null && !upstream.type.equals("SOCKS5", true) && !upstream.username.isNullOrBlank()) {
                val token = Base64.getEncoder().encodeToString(
                    "${upstream.username}:${upstream.password ?: ""}".toByteArray(StandardCharsets.UTF_8)
                )
                remoteOut.write("Proxy-Authorization: Basic $token\r\n".toByteArray(StandardCharsets.UTF_8))
            }
            remoteOut.write("\r\n".toByteArray(StandardCharsets.UTF_8))
            remoteOut.flush()

            val body = readBodyIfAny(input, headers)
            if (body != null && body.isNotEmpty()) remoteOut.write(body)
            remoteOut.flush()

            // 双向管道（后续请求/响应直接透传）
            pipe(input, output, remote, alreadyReadRequestBody = false)
        } finally {
            runCatching { remote.close() }
        }
    }

    /* ------------------------- 上游连接 ------------------------- */

    private fun connectUpstream(host: String, port: Int): Socket? {
        val targetIp = resolveWithHosts(host) ?: host
        val up = upstream ?: return directConnect(targetIp, host, port)
        return try {
            when (up.type.uppercase()) {
                "SOCKS5" -> socks5Connect(up, host, targetIp, port)
                "HTTP", "HTTPS" -> httpConnect(up, host, port)
                else -> directConnect(targetIp, host, port)
            }
        } catch (t: Throwable) {
            Timber.w(t, "upstream connect failed %s:%d via %s", host, port, up.type)
            null
        }
    }

    private fun directConnect(ip: String, host: String, port: Int): Socket? = try {
        val s = Socket()
        s.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        s
    } catch (t: Throwable) {
        Timber.d("direct connect fail %s(%s):%d - %s", host, ip, port, t.message)
        null
    }

    private fun httpConnect(up: Upstream, host: String, port: Int): Socket {
        val raw = Socket()
        raw.connect(InetSocketAddress(up.host, up.port), CONNECT_TIMEOUT_MS)
        raw.tcpNoDelay = true
        val s: Socket = if (up.type.equals("HTTPS", true)) {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, up.host, up.port, true) as javax.net.ssl.SSLSocket
            ssl.useClientMode = true
            ssl.startHandshake()
            ssl
        } else raw
        // 作为 HTTP 上游使用时，请求行会在 handleForward/handleTunnel 中按上游语义处理，
        // 这里仅对隧道场景补 CONNECT 握手
        return s
    }

    /** HTTP 上游的隧道握手（connectUpstream 里针对 CONNECT 场景单独调用） */
    private fun httpTunnelHandshake(up: Upstream, host: String, port: Int, socket: Socket) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val inp = BufferedInputStream(socket.getInputStream())
        val auth = up.username?.let {
            val token = Base64.getEncoder()
                .encodeToString("$it:${up.password ?: ""}".toByteArray(StandardCharsets.UTF_8))
            "Proxy-Authorization: Basic $token\r\n"
        } ?: ""
        out.write(
            ("CONNECT $host:$port HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                auth +
                "\r\n").toByteArray(StandardCharsets.UTF_8)
        )
        out.flush()
        val status = readLine(inp) ?: throw IOException("upstream closed")
        if (!status.contains(" 200")) throw IOException("upstream CONNECT refused: $status")
        // 读掉响应头
        while (true) {
            val line = readLine(inp) ?: break
            if (line.isEmpty()) break
        }
    }

    private fun socks5Connect(up: Upstream, host: String, targetIp: String, port: Int): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(up.host, up.port), CONNECT_TIMEOUT_MS)
        s.tcpNoDelay = true
        s.soTimeout = CONNECT_TIMEOUT_MS
        val out = BufferedOutputStream(s.getOutputStream())
        val inp = BufferedInputStream(s.getInputStream())

        if (up.username.isNullOrBlank()) {
            out.write(byteArrayOf(0x05, 0x01, 0x00))
        } else {
            out.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
        }
        out.flush()
        val methodBuf = ByteArray(2)
        readFully(inp, methodBuf)
        if (methodBuf[0].toInt() != 0x05) throw IOException("bad socks version")
        when (methodBuf[1].toInt()) {
            0x02 -> {
                val u = up.username!!.toByteArray(StandardCharsets.UTF_8)
                val p = (up.password ?: "").toByteArray(StandardCharsets.UTF_8)
                val req = ByteArrayOutputStream()
                req.write(0x01)
                req.write(u.size); req.write(u)
                req.write(p.size); req.write(p)
                out.write(req.toByteArray())
                out.flush()
                val resp = ByteArray(2)
                readFully(inp, resp)
                if (resp[1].toInt() != 0x00) throw IOException("socks auth failed")
            }
            0x00 -> Unit
            else -> throw IOException("socks method rejected: ${methodBuf[1]}")
        }

        // 绑定地址：hosts 劫持命中的用 IP，否则用域名让远程解析
        val req = ByteArrayOutputStream()
        req.write(0x05); req.write(0x01); req.write(0x00)
        val ip = runCatching { java.net.InetAddress.getByName(targetIp) }.getOrNull()
        if (targetIp != host && ip != null) {
            val bytes = ip.address
            req.write(if (bytes.size == 4) 0x01 else 0x04)
            req.write(bytes)
        } else {
            val hb = host.toByteArray(StandardCharsets.UTF_8)
            req.write(0x03); req.write(hb.size); req.write(hb)
        }
        req.write((port shr 8) and 0xff)
        req.write(port and 0xff)
        out.write(req.toByteArray())
        out.flush()

        val head = ByteArray(4)
        readFully(inp, head)
        if (head[1].toInt() != 0x00) throw IOException("socks connect refused: ${head[1]}")
        val skip = when (head[3].toInt()) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> {
                val l = ByteArray(1); readFully(inp, l); l[0].toInt()
            }
            else -> 0
        }
        if (skip > 0) readFully(inp, ByteArray(skip + 2))
        s.soTimeout = 0
        return s
    }

    private fun connectUpstreamTunnel(host: String, port: Int): Socket? {
        val up = upstream ?: return directConnect(resolveWithHosts(host) ?: host, host, port)
        return try {
            when (up.type.uppercase()) {
                "SOCKS5" -> socks5Connect(up, host, resolveWithHosts(host) ?: host, port)
                "HTTP", "HTTPS" -> {
                    val s = httpConnect(up, host, port)
                    httpTunnelHandshake(up, host, port, s)
                    s
                }
                else -> directConnect(resolveWithHosts(host) ?: host, host, port)
            }
        } catch (t: Throwable) {
            Timber.w(t, "tunnel upstream failed %s:%d", host, port)
            null
        }
    }

    // handleTunnel 需要先做上游握手，包一层
    private fun connectTunnelTarget(host: String, port: Int): Socket? = connectUpstreamTunnel(host, port)

    /* ------------------------- 解析与 IO 工具 ------------------------- */

    private fun resolveWithHosts(host: String): String? {
        val h = host.lowercase().removeSuffix(".")
        hostsRules[h]?.let { return it }
        hostsRules.entries.firstOrNull { (domain, _) ->
            domain.startsWith("*.") && h.endsWith(domain.substring(1))
        }?.value?.let { return it }
        return null
    }

    private fun readHead(input: InputStream): Pair<String, List<String>>? {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) return null
        val headers = mutableListOf<String>()
        var total = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            total += h.length
            if (total > MAX_HEADER_BYTES) throw IOException("header too large")
            headers += h
        }
        return line to headers
    }

    private fun readBodyIfAny(input: InputStream, headers: List<String>): ByteArray? {
        val cl = headers.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(":")?.trim()?.toIntOrNull() ?: return null
        if (cl <= 0 || cl > MAX_BODY_BYTES) return null
        val buf = ByteArray(cl)
        readFully(input, buf)
        return buf
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                if (sb.length > MAX_LINE_BYTES) throw IOException("line too long")
                sb.append(b.toChar())
            }
        }
        return sb.toString()
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    /** 把 client<->remote 的流量双向桥接，直到任一端关闭 */
    private fun pipe(
        clientIn: InputStream,
        clientOut: OutputStream,
        remote: Socket,
        alreadyReadRequestBody: Boolean = false,
    ) {
        val remoteIn = BufferedInputStream(remote.getInputStream())
        val remoteOut = BufferedOutputStream(remote.getOutputStream())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)

        val up = Thread {
            try {
                pump(clientIn, remoteOut)
            } catch (_: Throwable) {
            } finally {
                done.set(true)
                runCatching { remote.shutdownOutput() }
            }
        }.apply { isDaemon = true; name = "geoenv-pipe-up" }

        val down = Thread {
            try {
                pump(remoteIn, clientOut)
            } catch (_: Throwable) {
            } finally {
                done.set(true)
                runCatching { (clientOut as? Socket)?.let { } }
            }
        }.apply { isDaemon = true; name = "geoenv-pipe-down" }

        up.start(); down.start()
        up.join()
        down.join()
    }

    private fun pump(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = src.read(buf)
            if (n < 0) break
            dst.write(buf, 0, n)
            dst.flush()
        }
    }

    private fun writeSimple(out: OutputStream, code: Int, reason: String) {
        val text = "$code $reason"
        runCatching {
            out.write(
                ("HTTP/1.1 $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.UTF_8)
            )
            out.flush()
        }
    }

    private data class UriParts(val host: String, val port: Int, val path: String, val query: String?)

    private fun parseAbsoluteUri(target: String): UriParts? {
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) return null
        return runCatching {
            val u = java.net.URI(target)
            val path = u.rawPath ?: "/"
            UriParts(u.host ?: return null, if (u.port > 0) u.port else if (u.scheme.equals("https", true)) 443 else 80, path, u.rawQuery)
        }.getOrNull()
    }

    private fun splitHostPort(target: String): Pair<String, Int>? {
        val idx = target.lastIndexOf(':')
        if (idx <= 0) return null
        val p = target.substring(idx + 1).toIntOrNull() ?: return null
        return target.substring(0, idx) to p
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
        private const val MAX_CONNECTIONS = 128
    }
}
