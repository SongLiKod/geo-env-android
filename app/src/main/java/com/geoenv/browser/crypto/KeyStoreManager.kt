package com.geoenv.browser.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 数据库主密钥管理。
 *
 * - 使用 Android Keystore（AES256-GCM）加密随机生成的 32 字节 SQLCipher passphrase；
 * - 密文与 IV 存放在应用私有 SharedPreferences（`geoenv_secure_prefs`，MODE_PRIVATE）；
 * - Keystore 密钥标记为不可导出（`setUserAuthenticationRequired(false)`，避免锁屏导致无法解密）。
 *
 * 这样即使 APK 被反编译或私有目录被 root 导出，没有设备 Keystore 也无法解密数据库。
 */
class KeyStoreManager(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateDbPassphrase(): ByteArray {
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (existing != null) {
            val parts = existing.split(":")
            if (parts.size == 2) {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val ct = Base64.decode(parts[1], Base64.NO_WRAP)
                runCatching { return decrypt(iv, ct) }
                    .onFailure {
                        // Keystore 密钥丢失/损坏：不能静默返回错误密钥，交由上层决定
                        throw IllegalStateException("无法解密数据库主密钥", it)
                    }
            }
        }
        // 首次生成：32 字节密码，等价 AES-256
        val passphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val (iv, ct) = encrypt(passphrase)
        prefs.edit()
            .putString(KEY_DB_PASSPHRASE, "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ct, Base64.NO_WRAP)}")
            .apply()
        return passphrase
    }

    /** 生成一个仅存于内存的字段级 AES-GCM 密钥（用于账号密码等敏感字段）。 */
    fun getFieldKey(): SecretKey = getOrCreateFieldKey()

    /**
     * 自动备份口令：随机 32 字节，由 Keystore 密钥加密后存本地 SharedPreferences。
     * 仅用于"本机自动备份 → 本机恢复"，跨设备导入需用户手输导出口令。
     */
    fun getOrCreateBackupPassphrase(): String {
        val existing = prefs.getString(KEY_BACKUP_PASSPHRASE, null)
        if (existing != null) {
            val parts = existing.split(":")
            if (parts.size == 2) {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val ct = Base64.decode(parts[1], Base64.NO_WRAP)
                runCatching { return String(decrypt(iv, ct), Charsets.ISO_8859_1) }
            }
        }
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val (iv, ct) = encrypt(bytes)
        prefs.edit()
            .putString(
                KEY_BACKUP_PASSPHRASE,
                "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ct, Base64.NO_WRAP)}",
            )
            .apply()
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun getOrCreateFieldKey(): SecretKey {
        val ks = loadKeyStore()
        (ks.getEntry(KEY_FIELD_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_FIELD_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    // ------------------------------------------------------------------

    private fun encrypt(plain: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateDbKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        return iv to cipher.doFinal(plain)
    }

    private fun decrypt(iv: ByteArray, ct: ByteArray): ByteArray {
        val key = getOrCreateDbKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    private fun getOrCreateDbKey(): SecretKey {
        val ks = loadKeyStore()
        (ks.getEntry(KEY_DB_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        kg.init(
            KeyGenParameterSpec.Builder(
                KEY_DB_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(PROVIDER).apply { load(null) }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFS_NAME = "geoenv_secure_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase_v1"
        private const val KEY_BACKUP_PASSPHRASE = "backup_passphrase_v1"
        private const val KEY_DB_ALIAS = "geoenv_db_master"
        private const val KEY_FIELD_ALIAS = "geoenv_field_key"
    }
}
