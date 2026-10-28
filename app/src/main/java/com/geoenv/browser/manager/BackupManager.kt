package com.geoenv.browser.manager

import android.content.Context
import com.geoenv.browser.data.repo.AuditRepository
import com.geoenv.browser.data.repo.AuditRepository.Companion.Action
import com.geoenv.browser.data.repo.EnvironmentRepository
import com.geoenv.browser.db.AppDatabase
import com.geoenv.browser.db.BackupRecord
import com.geoenv.browser.db.Environment
import com.geoenv.browser.db.HostsRule
import com.geoenv.browser.db.ProxyConfig
import com.geoenv.browser.db.AuditLog
import com.geoenv.browser.di.ServiceLocator
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 加密环境包导入导出 + 本地自动备份（需求 3.6）。
 *
 * 包格式（`.geoenv`，实为 ZIP → AES-256-GCM 密文流）：
 * ```
 * magic "GEOPKG1\n"            // 8 字节明文头，便于识别文件类型
 * salt (16) || iv (12)          // 明文前缀，用于 PBKDF2 / GCM
 * ciphertext+tag                // ZIP 内容整体加密
 * ```
 * - 密钥：`PBKDF2WithHmacSHA256(passphrase, salt, 210_000)` → AES-256；
 * - AAD = magic+salt，防止密文被拼接到别的包；
 * - ZIP 内容：`manifest.json` + `environments.json` + `accounts.json` +
 *   `snapshots.json` + `proxies.json` + `hosts.json` + `theme.json` + `profiles.zip`（可选）。
 *
 * 导入时**必须提供同一口令**（需求 3.6.2"导入需要密钥"）。
 * 字段级密文（`passwordEnc`）原样搬移——它们由 Keystore 派生密钥加密，跨设备由
 * manifest 中的 `reencrypt` 提示交由调用方用新口令重新包裹（见 [ExportOptions]）。
 */
@Singleton
class BackupManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val locator: ServiceLocator,
) {

    private val db: AppDatabase get() = locator.database
    private val audit: AuditRepository get() = locator.auditRepository
    private val gson = GsonBuilder().setPrettyPrinting().create()

    /* ----------------------------- 数据模型 ----------------------------- */

    data class ExportOptions(
        /** 只导出指定环境；空 = 全部 */
        val envIds: List<Long> = emptyList(),
        /** 是否携带 profile 目录（登录态/缓存）。体积较大，默认关闭 */
        val includeProfiles: Boolean = false,
        /** 是否带出审计日志 */
        val includeAudit: Boolean = false,
    )

    data class PackageStats(
        val path: String,
        val sizeBytes: Long,
        val envCount: Int,
    )

    data class ImportPreview(
        val appName: String,
        val appVersion: String,
        val geckoVersion: String,
        val createdAt: Long,
        val envCount: Int,
        val accountCount: Int,
        val snapshotCount: Int,
        val proxyCount: Int,
        val hasProfiles: Boolean,
        val names: List<String>,
    )

    /* ----------------------------- 导出 ----------------------------- */

    suspend fun export(
        passphrase: String,
        outFile: File,
        options: ExportOptions = ExportOptions(),
    ): PackageStats = withContext(Dispatchers.IO) {
        require(passphrase.length >= 8) { "口令至少 8 位" }

        val envs = if (options.envIds.isEmpty()) db.envDao().getAll()
        else options.envIds.mapNotNull { db.envDao().getById(it) }
        require(envs.isNotEmpty()) { "没有可导出的环境" }
        val ids = envs.map { it.id }

        // 关联数据
        val accounts = ids.mapNotNull { db.accountDao().getByEnv(it) }
        val snapshots = ids.flatMap { db.snapshotDao().getByEnvOnce(it) }
        val hosts = ids.flatMap { db.hostsDao().getEnabledForEnv(it) }
        val proxies = db.proxyDao().getAll()
        val theme = db.themeDao().get()
        val groups = db.groupDao().getAll().filter { g -> envs.any { it.groupId == g.id } }
        val auditLogs = if (options.includeAudit) db.auditDao().recent(5000) else emptyList()

        val manifest = PackageManifest(
            appName = "GeoEnv",
            appVersion = BuildVersion.NAME,
            geckoVersion = BuildVersion.GECKO,
            createdAt = System.currentTimeMillis(),
            envCount = envs.size,
            accountCount = accounts.size,
            snapshotCount = snapshots.size,
            proxyCount = proxies.size,
            hostsCount = hosts.size,
            includeProfiles = options.includeProfiles,
            includeAudit = options.includeAudit,
            format = FORMAT,
        )

        // 逐层打进内存中的 ZIP（避免中间文件泄露明文）
        val zipBytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                putJson(z, "manifest.json", manifest)
                putJson(z, "environments.json", envs)
                putJson(z, "accounts.json", accounts)
                putJson(z, "snapshots.json", snapshots)
                putJson(z, "proxies.json", proxies)
                putJson(z, "hosts.json", hosts)
                putJson(z, "groups.json", groups)
                putJson(z, "theme.json", theme)
                if (auditLogs.isNotEmpty()) putJson(z, "audit.json", auditLogs)
                if (options.includeProfiles) {
                    putJson(z, "profile_index.json", profileIndex(envs))
                    envs.forEach { env ->
                        val dir = locator.environmentRepository.profileDir(env.id)
                        if (dir.isDirectory) putDirectory(z, "profiles/env_${env.id}", dir)
                    }
                }
            }
        }.toByteArray()

        val sealed = seal(zipBytes, passphrase)
        outFile.parentFile?.mkdirs()
        outFile.writeBytes(sealed)

        db.backupDao().insert(
            BackupRecord(
                filePath = outFile.absolutePath,
                sizeBytes = sealed.size.toLong(),
                envCount = envs.size,
                encrypted = true,
            ),
        )
        audit.log(
            action = Action.EXPORT,
            note = "导出加密环境包 ${envs.size} 个环境 → ${outFile.name}",
            detail = mapOf(
                "envCount" to envs.size,
                "size" to sealed.size,
                "includeProfiles" to options.includeProfiles,
            ),
        )
        PackageStats(outFile.absolutePath, sealed.size.toLong(), envs.size)
    }

    /* ----------------------------- 导入 ----------------------------- */

    /** 解包预览（不落库），用于导入前确认内容 */
    suspend fun preview(input: File, passphrase: String): ImportPreview =
        withContext(Dispatchers.IO) {
            val zip = open(input.readBytes(), passphrase)
            val manifest = readJson<PackageManifest>(zip, "manifest.json")
                ?: error("包内缺少 manifest.json")
            val envs = readJson<List<Environment>>(zip, "environments.json") ?: emptyList()
            ImportPreview(
                appName = manifest.appName,
                appVersion = manifest.appVersion,
                geckoVersion = manifest.geckoVersion,
                createdAt = manifest.createdAt,
                envCount = manifest.envCount,
                accountCount = manifest.accountCount,
                snapshotCount = manifest.snapshotCount,
                proxyCount = manifest.proxyCount,
                hasProfiles = manifest.includeProfiles,
                names = envs.map { it.name },
            )
        }

    data class ImportResult(
        val importedEnvs: Int,
        val skippedNames: List<String>,
        val failedNames: List<String>,
    )

    /**
     * 导入加密包。
     *
     * @param onConflict [ConflictStrategy] 同名环境处理策略
     * @param renamePrefix 冲突时改名的前缀（默认 `导入_`）
     */
    enum class ConflictStrategy { SKIP, RENAME }

    suspend fun import(
        input: File,
        passphrase: String,
        onConflict: ConflictStrategy = ConflictStrategy.RENAME,
        withProfiles: Boolean = true,
    ): ImportResult = withContext(Dispatchers.IO) {
        val zip = open(input.readBytes(), passphrase)
        val manifest = readJson<PackageManifest>(zip, "manifest.json")
            ?: error("包内缺少 manifest.json")
        require(manifest.format == FORMAT) { "不支持的包格式：${manifest.format}" }

        val envs = readJson<List<Environment>>(zip, "environments.json").orEmpty()
        val accounts = readJson<List<com.geoenv.browser.db.Account>>(zip, "accounts.json").orEmpty()
        val snapshots = readJson<List<com.geoenv.browser.db.FingerprintSnapshot>>(zip, "snapshots.json").orEmpty()
        val proxies = readJson<List<ProxyConfig>>(zip, "proxies.json").orEmpty()
        val hosts = readJson<List<HostsRule>>(zip, "hosts.json").orEmpty()
        val groups = readJson<List<com.geoenv.browser.db.EnvGroup>>(zip, "groups.json").orEmpty()
        val auditLogs = readJson<List<AuditLog>>(zip, "audit.json").orEmpty()

        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var imported = 0

        // 1) 分组（按名称合并）
        val groupIdMap = HashMap<Long, Long>()
        groups.forEach { g ->
            val existing = db.groupDao().getAll().firstOrNull { it.name == g.name }
            groupIdMap[g.id] = existing?.id ?: db.groupDao().insert(g.copy(id = 0))
        }

        // 2) 代理（按 host:port:type 合并，密码字段跨设备不可解 → 清空并要求重填）
        val proxyIdMap = HashMap<Long, Long>()
        proxies.forEach { p ->
            val existing = db.proxyDao().getAll().firstOrNull {
                it.host == p.host && it.port == p.port && it.type == p.type
            }
            proxyIdMap[p.id] = existing?.id ?: run {
                db.proxyDao().insert(
                    p.copy(
                        id = 0,
                        passwordEnc = null,
                        authRequired = p.username != null,
                        status = ProxyConfig.STATUS_UNKNOWN,
                    ),
                )
            }
        }

        // 3) 环境
        envs.forEach { env ->
            try {
                var name = env.name
                if (db.envDao().getByName(name) != null) {
                    when (onConflict) {
                        ConflictStrategy.SKIP -> { skipped += name; return@forEach }
                        ConflictStrategy.RENAME -> {
                            name = uniqueName("导入_$name")
                        }
                    }
                }
                val newId = db.envDao().insert(
                    env.copy(
                        id = 0,
                        name = name,
                        groupId = env.groupId?.let { groupIdMap[it] },
                        proxyId = env.proxyId?.let { proxyIdMap[it] },
                        profilePath = "",
                        status = Environment.STATUS_STOPPED,
                        marionettePort = 0, // 端口必须在本机重新分配
                        lastStartTime = null,
                        fingerprintSnapshotId = null,
                        createTime = System.currentTimeMillis(),
                        updateTime = System.currentTimeMillis(),
                    ),
                )
                val profilePath = locator.environmentRepository.profileDir(newId).absolutePath
                db.envDao().update(db.envDao().getById(newId)!!.copy(profilePath = profilePath))
                locator.environmentRepository.ensureProfileDir(newId)

                // 关联数据：旧 envId → 新 id
                accounts.filter { it.envId == env.id }.forEach { a ->
                    db.accountDao().insert(a.copy(id = 0, envId = newId))
                }
                snapshots.filter { it.envId == env.id }.forEach { s ->
                    db.snapshotDao().insert(s.copy(id = 0, envId = newId))
                }
                hosts.filter { it.envId == env.id }.forEach { h ->
                    db.hostsDao().insert(h.copy(id = 0, envId = newId))
                }
                imported++
            } catch (t: Throwable) {
                Timber.w(t, "import env %s failed", env.name)
                failed += env.name
            }
        }

        // 4) profile 数据（可选）
        if (withProfiles && manifest.includeProfiles) {
            extractProfiles(zip)
        }

        // 5) 主题：仅当本机尚无自定义时导入
        if (db.themeDao().get() == null) {
            readJson<com.geoenv.browser.db.ThemeConfig>(zip, "theme.json")?.let {
                db.themeDao().upsert(it)
            }
        }

        // 6) 审计日志（只追加，天然兼容导入）
        if (auditLogs.isNotEmpty()) {
            db.auditDao().insertAll(auditLogs.filter { it.id == 0L }.map { it.copy(id = 0) })
        }

        audit.log(
            action = Action.IMPORT,
            note = "导入环境包：成功 $imported，跳过 ${skipped.size}，失败 ${failed.size}",
            detail = mapOf(
                "file" to input.name,
                "imported" to imported,
                "skipped" to skipped,
                "failed" to failed,
            ),
        )
        ImportResult(imported, skipped, failed)
    }

    /* ----------------------------- 自动备份 ----------------------------- */

    /** 每日自动备份（本地全量环境配置，口令由设备密钥派生，不需用户输入） */
    suspend fun autoBackup(): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "backups").apply { mkdirs() }
            val name = "auto_${System.currentTimeMillis()}.geoenv"
            val out = File(dir, name)
            // 自动备份口令：设备本地派生，跨设备不可解（本地恢复可用）
            val passphrase = locator.keyStoreManager.getOrCreateBackupPassphrase()
            export(passphrase, out, ExportOptions(includeProfiles = false))
            pruneAutoBackups(dir, keep = AUTO_BACKUP_KEEP)
            out
        }.onFailure { Timber.w(it, "auto backup failed") }.getOrNull()
    }

    private fun pruneAutoBackups(dir: File, keep: Int) {
        dir.listFiles { f -> f.name.startsWith("auto_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keep)
            ?.forEach { runCatching { it.delete() } }
    }

    suspend fun recentBackups(): List<BackupRecord> = db.backupDao().observeRecentOnce(50)

    /* ----------------------------- 加密原语 ----------------------------- */

    private fun seal(plain: ByteArray, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD_PREFIX + salt)
        val ct = cipher.doFinal(plain)
        return MAGIC + salt + iv + ct
    }

    private fun open(sealed: ByteArray, passphrase: String): Map<String, ByteArray> {
        require(sealed.size > MAGIC.size + SALT_LEN + IV_LEN + TAG_BITS / 8) { "包文件损坏或过小" }
        require(sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "不是 GeoEnv 环境包（文件头不匹配）" }
        val salt = sealed.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = sealed.copyOfRange(MAGIC.size + SALT_LEN, MAGIC.size + SALT_LEN + IV_LEN)
        val ct = sealed.copyOfRange(MAGIC.size + SALT_LEN + IV_LEN, sealed.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD_PREFIX + salt)
        val plain = try {
            cipher.doFinal(ct)
        } catch (t: Throwable) {
            throw IllegalArgumentException("解密失败：口令错误或包已损坏", t)
        }
        return readZip(plain)
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val encoded = try {
            factory.generateSecret(spec).encoded
        } finally {
            (spec as PBEKeySpec).clearPassword()
        }
        return SecretKeySpec(encoded, "AES")
    }

    /* ----------------------------- ZIP 读写 ----------------------------- */

    private fun putJson(z: ZipOutputStream, name: String, value: Any?) {
        z.putNextEntry(ZipEntry(name))
        z.write(gson.toJson(value).toByteArray(Charsets.UTF_8))
        z.closeEntry()
    }

    private fun putDirectory(z: ZipOutputStream, prefix: String, dir: File) {
        dir.walkTopDown().forEach { f ->
            val rel = f.relativeTo(dir).path
            if (f.isFile) {
                z.putNextEntry(ZipEntry("$prefix/$rel"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            } else {
                z.putNextEntry(ZipEntry("$prefix/$rel/"))
                z.closeEntry()
            }
        }
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                if (entry.isDirectory) continue
                // 解压炸弹防护：单条 256MB、总数 4096
                require(out.size < 4096) { "包内条目过多" }
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val n = z.read(chunk)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_ENTRY_BYTES) { "包内条目过大：${entry.name}" }
                    buf.write(chunk, 0, n)
                }
                out[entry.name] = buf.toByteArray()
            }
        }
        return out
    }

    private inline fun <reified T> readJson(zip: Map<String, ByteArray>, name: String): T? =
        zip[name]?.let { runCatching { gson.fromJson(String(it, Charsets.UTF_8), T::class.java) }.getOrNull() }

    private fun profileIndex(envs: List<Environment>): List<Map<String, Any>> =
        envs.map { mapOf("id" to it.id, "name" to it.name) }

    private fun extractProfiles(zip: Map<String, ByteArray>) {
        val root = locator.environmentRepository.profileRoot()
        zip.forEach { (name, bytes) ->
            if (!name.startsWith("profiles/env_")) return@forEach
            val parts = name.removePrefix("profiles/").split("/")
            if (parts.size < 2) return@forEach
            val envId = parts[0].removePrefix("env_").toLongOrNull() ?: return@forEach
            val target = File(File(root, "env_$envId"), parts.drop(1).joinToString("/"))
            // 防目录穿越
            if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) return@forEach
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }

    private suspend fun uniqueName(base: String): String {
        var name = base
        var i = 2
        while (db.envDao().getByName(name) != null) {
            name = "${base}_${i++}"
        }
        return name
    }

    /* ----------------------------- 模型 ----------------------------- */

    data class PackageManifest(
        val appName: String,
        val appVersion: String,
        val geckoVersion: String,
        val createdAt: Long,
        val envCount: Int,
        val accountCount: Int,
        val snapshotCount: Int,
        val proxyCount: Int,
        val hostsCount: Int,
        val includeProfiles: Boolean,
        val includeAudit: Boolean,
        val format: String,
    )

    object BuildVersion {
        val NAME: String = runCatching {
            com.geoenv.browser.BuildConfig.VERSION_NAME
        }.getOrDefault("1.0.0")

        /** 与 gradle 中 GeckoView 锁定版本保持一致（需求 4.4 版本锁定） */
        val GECKO: String = "158.0.20261021001945"
    }

    companion object {
        private const val FORMAT = "geoenv-package-v1"
        private val MAGIC = "GEOPKG1\n".toByteArray(Charsets.US_ASCII)
        private val AAD_PREFIX = "geoenv-pkg:".toByteArray(Charsets.US_ASCII)
        private const val SALT_LEN = 16
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
        private const val ITERATIONS = 210_000
        private const val KEY_BITS = 256
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_ENTRY_BYTES = 256L * 1024 * 1024
        private const val AUTO_BACKUP_KEEP = 7
    }
}
