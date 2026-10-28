package com.geoenv.browser.gecko

import android.content.Context
import com.geoenv.browser.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 按环境生成指纹 WebExtension 包（.xpi）。
 *
 * 为什么动态生成：指纹 Hook 必须在 `document_start` **同步**拿到本环境的配置
 * （见 [HookScript.build]），而 APK assets 是只读静态资源。因此每次环境启动时
 * 把 Hook 源码与配置一起打进 `filesDir/extensions/geoenv_<envId>.xpi`，
 * 再通过 `WebExtensionController.install(fileUri, INSTALLATION_METHOD_FROM_FILE)`
 * 安装（配合 DebugConfig 中 `xpinstall.signatures.required=false` 与
 * 自动批准的 PromptDelegate，无人值守可安装）。
 *
 * 扩展职责（background 常驻页脚本）：
 *  - HAR 抓包：`webRequest` 生命周期组装条目，应用侧分页拉取（binder 单事务 ≤1MB）；
 *  - Cookie 读写：`browser.cookies` 代理应用侧 READ_COOKIE / WRITE_COOKIE 命令；
 *  - JS 执行：`tabs.executeScript({code})` 代理 EXEC_JS（GeckoView 无公开 evaluateJs API）；
 *  - 验证码/弹窗启发式上报（内容脚本 DOM 扫描 → native port 事件）。
 *
 * 指纹 Hook 本身只含页面侧代码（[HookScript.build]），与配置一同静态固化在 content.js 中。
 */
object ExtensionFactory {

    const val EXTENSION_ID = "geoenv-helper@geoenv.local"
    const val NATIVE_APP = "geoenv"

    /** 生成/覆盖 xpi，返回文件 */
    fun buildXpi(context: Context, envId: Long, fp: FingerprintConfig): File {
        val dir = File(context.filesDir, "extensions").apply { mkdirs() }
        val out = File(dir, "geoenv_$envId.xpi")
        val version = versionFor(fp)
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            put(z, "manifest.json", manifestJson(version))
            put(z, "background.js", BACKGROUND_JS)
            put(z, "content.js", HookScript.build(fp) + "\n" + CAPTCHA_SCAN_JS)
        }
        return out
    }

    private fun put(z: ZipOutputStream, name: String, content: String) {
        z.putNextEntry(ZipEntry(name))
        z.write(content.toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    private fun versionFor(fp: FingerprintConfig): String {
        val h = (FingerprintConfig.toJson(fp).hashCode() and 0x7fffffff) % 100000
        return "1.0.$h"
    }

    private fun manifestJson(version: String): String = JSONObject()
        .put("manifest_version", 2)
        .put("name", "GeoEnv Helper")
        .put("version", version)
        .put("description", "GeoEnv fingerprint / HAR / cookie bridge (internal)")
        .put("browser_specific_settings", JSONObject()
            .put("gecko", JSONObject()
                .put("id", EXTENSION_ID)
                .put("strict_min_version", "108.0")))
        .put("permissions", JSONArray(listOf(
            "webRequest", "cookies", "tabs", "<all_urls>", "storage"
        )))
        .put("background", JSONObject().put("scripts", JSONArray(listOf("background.js"))))
        .put("content_scripts", JSONArray(listOf(JSONObject()
            .put("matches", JSONArray(listOf("<all_urls>")))
            .put("js", JSONArray(listOf("content.js")))
            .put("run_at", "document_start")
            .put("all_frames", true)
            .put("match_about_blank", true)
        )))
        .put("web_accessible_resources", JSONArray(listOf("content.js")))
        .toString(2)

    /* =====================================================================================
     * background.js
     * ===================================================================================*/
    private val BACKGROUND_JS = """
'use strict';
/* GeoEnv helper background — native port bridge (HAR / cookies / eval) */
const NATIVE = ${jsonStr(NATIVE_APP)};
let port = null;
let seq = 1;
const har = { recording: false, entries: [], inFlight: {}, dropped: 0 };
const HAR_CAP = 50000;

function connect() {
  if (port) return;
  try {
    port = browser.runtime.connectNative(NATIVE);
    port.onMessage.addListener(onAppMessage);
    port.onDisconnect.addListener(() => { port = null; setTimeout(connect, 1500); });
  } catch (e) { port = null; setTimeout(connect, 2000); }
}
function post(obj) {
  if (!port) { connect(); setTimeout(() => { try { if (port) port.postMessage(obj); } catch (e) {} }, 600); return; }
  try { port.postMessage(obj); } catch (e) { port = null; connect(); }
}
function reply(id, payload) { payload.id = id; post(payload); }

connect();

async function onAppMessage(msg) {
  if (!msg || !msg.id || !msg.cmd) return;
  try {
    switch (msg.cmd) {
      case 'startHar':
        har.recording = true; har.entries = []; har.inFlight = {};
        reply(msg.id, { ok: true }); break;
      case 'stopHar':
        har.recording = false; har.inFlight = {};
        reply(msg.id, { ok: true }); break;
      case 'getHar': {
        const off = msg.offset | 0, lim = msg.limit | 0 || 100;
        reply(msg.id, { ok: true, entries: har.entries.slice(off, off + lim), total: har.entries.length });
        break;
      }
      case 'clearHar':
        har.entries = []; har.inFlight = {};
        reply(msg.id, { ok: true }); break;
      case 'eval': {
        let tabId = msg.tabId;
        if (!tabId) {
          const tabs = await browser.tabs.query({ active: true, currentWindow: true });
          tabId = tabs[0] && tabs[0].id;
        }
        if (!tabId) { reply(msg.id, { ok: false, error: 'no_tab' }); break; }
        const r = await browser.tabs.executeScript(tabId, { code: String(msg.code || '') });
        reply(msg.id, { ok: true, result: (r && r.length ? r[0] : null) });
        break;
      }
      case 'getCookies': {
        const cs = await browser.cookies.getAll({ url: msg.url });
        reply(msg.id, {
          ok: true,
          cookies: cs.map(c => ({
            name: c.name, value: c.value, domain: c.domain, path: c.path,
            expires: c.expirationDate, httpOnly: c.httpOnly, secure: c.secure,
            sameSite: c.sameSite, storeId: c.storeId, hostOnly: c.hostOnly
          }))
        });
        break;
      }
      case 'setCookie': {
        const r = await browser.cookies.set(msg.cookie || {});
        reply(msg.id, { ok: !!r });
        break;
      }
      case 'ping':
        reply(msg.id, { ok: true, recording: har.recording, buffered: har.entries.length });
        break;
      default:
        reply(msg.id, { ok: false, error: 'unknown_cmd' });
    }
  } catch (e) {
    reply(msg.id, { ok: false, error: String((e && e.message) || e) });
  }
}

/* ----------------------------- HAR (webRequest) ----------------------------- */
function hdrs(list) { return (list || []).map(h => ({ name: h.name, value: h.value })); }
function formDataToParams(fd) {
  const params = [];
  for (const k in fd) {
    const v = fd[k];
    (Array.isArray(v) ? v : [v]).forEach(x => params.push({ name: k, value: String(x) }));
  }
  return params;
}
browser.webRequest.onBeforeRequest.addListener(d => {
  if (!har.recording) return;
  if (har.entries.length >= HAR_CAP) { har.dropped++; return; }
  const e = {
    startedDateTime: new Date(d.timeStamp).toISOString(),
    time: 0,
    request: { method: d.method, url: d.url, headers: [], queryString: [], cookies: [], postData: null, httpVersion: "HTTP/1.1" },
    response: { status: 0, statusText: "", headers: [], cookies: [], content: { size: 0, mimeType: "" }, httpVersion: "HTTP/1.1", redirectURL: "" },
    cache: {}, timings: { blocked: 0, dns: -1, connect: -1, send: 0, wait: 0, receive: 0, ssl: -1 },
    _type: d.type, _st: d.timeStamp
  };
  if (d.requestBody) {
    if (d.requestBody.formData) {
      e.request.postData = { mimeType: "application/x-www-form-urlencoded", params: formDataToParams(d.requestBody.formData) };
    } else if (d.requestBody.raw && d.requestBody.raw.length) {
      try {
        const bytes = new Uint8Array(d.requestBody.raw[0].bytes);
        let s = "";
        for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
        e.request.postData = { mimeType: (d.requestBody.raw[0].contentType || ""), text: btoa(s) };
      } catch (err) {}
    }
  }
  har.inFlight[d.requestId] = e;
}, { urls: ["<all_urls>"] }, ["requestBody"]);

browser.webRequest.onBeforeSendHeaders.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  e.request.headers = hdrs(d.requestHeaders);
}, { urls: ["<all_urls>"] }, ["requestHeaders"]);

browser.webRequest.onHeadersReceived.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  e.response.status = d.statusCode;
  e.response.headers = hdrs(d.responseHeaders);
  const ct = (d.responseHeaders || []).find(h => h.name.toLowerCase() === 'content-type');
  if (ct) e.response.content.mimeType = ct.value;
}, { urls: ["<all_urls>"] }, ["responseHeaders"]);

browser.webRequest.onCompleted.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  delete har.inFlight[d.requestId];
  e.time = Math.max(0, d.timeStamp - e._st);
  e.timings.receive = e.time;
  const cl = (e.response.headers || []).find(h => h.name.toLowerCase() === 'content-length');
  if (cl) { const n = parseInt(cl.value, 10); if (!isNaN(n)) e.response.content.size = n; }
  delete e._st;
  har.entries.push(e);
}, { urls: ["<all_urls>"] });

browser.webRequest.onErrorOccurred.addListener(d => {
  const e = har.inFlight[d.requestId]; if (!e) return;
  delete har.inFlight[d.requestId];
  e.response.status = 0;
  e.response.statusText = String(d.error || "Error");
  e.time = Math.max(0, d.timeStamp - e._st);
  delete e._st;
  har.entries.push(e);
}, { urls: ["<all_urls>"] });

/* --------------------- 弹窗 / 验证码 DOM 启发式上报 --------------------- */
browser.runtime.onMessage.addListener(msg => {
  if (msg && msg.event) post(msg);
});
""".trimIndent()

    /* =====================================================================================
     * content.js 追加段：验证码/弹窗 DOM 启发式扫描
     * ===================================================================================*/
    private val CAPTCHA_SCAN_JS = """
;(function(){
  try {
    if (window.__geoEnvScan) return; window.__geoEnvScan = true;
    var RE = /(captcha|recaptcha|geetest|hcaptcha|verify|validate|antispick|safecaptcha)/i;
    var seen = {};
    function report(kind, title, message) {
      try {
        var key = kind + '|' + location.href;
        if (seen[key]) return; seen[key] = 1;
        browser.runtime.sendMessage({ event: 'popup', type: kind, title: title || '', message: message || '', url: location.href });
      } catch (e) {}
    }
    function scan() {
      try {
        var nodes = document.querySelectorAll('iframe,img[src],canvas,div[id],div[class]');
        var n = Math.min(nodes.length, 400);
        for (var i = 0; i < n; i++) {
          var el = nodes[i];
          var hay = (el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute && (el.getAttribute('src') || el.getAttribute('alt') || '')) || '';
          if (RE.test(hay)) { report('CAPTCHA', 'captcha-widget', String(hay).slice(0, 200)); break; }
        }
      } catch (e) {}
    }
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', function(){ scan(); setTimeout(scan, 1500); }, true);
    } else { scan(); setTimeout(scan, 1500); }
    // window.open 事件侧记录（弹窗主体由 Gecko PromptDelegate 统一收录）
    var origOpen = window.open;
    window.open = function(u, n, f) {
      try { report('POPUP', String(n || '_blank'), String(u || '')); } catch (e) {}
      return origOpen.apply(window, arguments);
    };
  } catch (e) {}
})();
""".trimIndent()

    private fun jsonStr(s: String): String = JSONObject.quote(s)

    /** 供 SlotEngine 解析后台事件用（popup 事件结构） */
    fun parsePopupEvent(json: JSONObject): PopupMessage = PopupMessage(
        type = json.optString("type", "POPUP"),
        title = json.optString("title"),
        message = json.optString("message"),
        url = json.optString("url"),
    )

    data class PopupMessage(val type: String, val title: String, val message: String, val url: String)

    /** 是否为 Debug 包（决定是否输出诊断日志） */
    val DEBUG: Boolean get() = BuildConfig.DEBUG
}
