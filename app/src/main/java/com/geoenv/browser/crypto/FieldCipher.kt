package com.geoenv.browser.crypto

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 字段级 AES-256-GCM 加密。
 *
 * 存储格式（字符串，便于 Room 直接存 TEXT）：
 * ```
 * v1:<base64(iv)>:<base64(ciphertext)>
 * ```
 * - 96-bit 随机 IV，每条记录独立；
 * - AAD（附加认证数据）可传入字段名，防止密文在列之间被调换；
 * - 解密失败（篡改/换列）会抛异常，由上层转换为"密钥不可用"错误。
 */
class FieldCipher(private val keyStoreManager: KeyStoreManager) {

    private val key: SecretKey get() = keyStoreManager.getFieldKey()

    fun encrypt(plain: String?, aad: String? = null): String? {
        if (plain == null) return null
        if (plain.isEmpty()) return ""
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return "v1:${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(ct, Base64.NO_WRAP)}"
    }

    fun decrypt(stored: String?, aad: String? = null): String? {
        if (stored == null) return null
        if (stored.isEmpty()) return ""
        // 兼容历史明文（迁移期）：无前缀视为明文
        if (!stored.startsWith("v1:")) return stored
        val parts = stored.split(":")
        require(parts.size == 3) { "密文格式非法" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ct = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** 用于导出包：把字段密文连同 IV 一起还原为原始字节（跨设备不可用，仅进程内）。 */
    fun encryptBytes(plain: ByteArray, aad: String? = null): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        if (aad != null) cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return iv + cipher.doFinal(plain)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}
