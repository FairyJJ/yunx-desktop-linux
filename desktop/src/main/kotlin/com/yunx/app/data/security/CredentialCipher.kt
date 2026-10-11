package com.yunx.app.data.security

import com.yunx.app.AppContext
import com.yunx.app.util.DiagnosticLog
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 凭证加解密接口（与原 Android 版一致）。
 */
internal interface CredentialCipher {
    fun encrypt(plaintext: String, purpose: String): String
    fun decrypt(stored: String, purpose: String): String
    fun isEncrypted(stored: String): Boolean
}

/**
 * 桌面版凭证加密：AES-256-GCM。
 * 密钥为首次启动生成的随机 32 字节，保存在 `<数据库目录>/credential.key`
 * （数据库目录默认＝数据根目录，可在「设置 → 储存目录」改；密钥必须跟着库走，否则库里的密文全部解不开）。
 * 输出格式与原 Android 版完全相同："yunx:v1:<iv Base64>:<ct Base64>"，AAD = purpose。
 */
internal class FileCredentialCipher : CredentialCipher {

    private val key: SecretKeySpec by lazy {
        val keyFile = resolveKeyFile()
        val bytes = if (keyFile.exists()) {
            keyFile.readBytes()
        } else {
            ByteArray(32).also { SecureRandom().nextBytes(it) }.also { newKey ->
                keyFile.parentFile?.mkdirs()
                keyFile.writeBytes(newKey)
            }
        }
        require(bytes.size == 32) { "invalid credential key file" }
        SecretKeySpec(bytes, "AES")
    }

    /**
     * 密钥文件位置：**先看数据库目录，再看数据根目录**（只读回退，不在数据根目录新建）。
     *
     * 回退这一条是为兼容两种情况：① 老版本把密钥放在数据根目录（数据库目录＝数据根目录，同一个文件）；
     * ② 用户把数据库/缓存切到别处、但没跟着搬密钥 —— 只要数据根目录里还有原密钥，密文就仍然解得开，
     * 绝不会因为「找不到密钥」而新建一把随机密钥（那会让所有已存凭证解不开、进而被自愈逻辑清空）。
     */
    private fun resolveKeyFile(): File {
        val primary = File(AppContext.dbDir, KEY_FILE_NAME)
        if (primary.exists()) return primary
        val legacy = File(AppContext.dataDir, KEY_FILE_NAME)
        if (legacy.exists() && legacy.canonicalPath != primary.canonicalPath) return legacy
        return primary
    }

    override fun encrypt(plaintext: String, purpose: String): String {
        // 诊断日志只记「用途 + 明文长度」，绝不打明文/密文本身
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "encrypt", size = plaintext.toByteArray(Charsets.UTF_8).size.toLong(),
            summary = "purpose=$purpose"
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val ctB64 = Base64.getEncoder().encodeToString(ct)
        return "$PREFIX$ivB64:$ctB64"
    }

    override fun decrypt(stored: String, purpose: String): String {
        // 诊断日志：解密是「密钥失效」类问题的第一现场（只记用途与长度，不记内容）
        DiagnosticLog.log(
            DiagnosticLog.CRYPTO, "decrypt", size = stored.length.toLong(),
            summary = "purpose=$purpose"
        )
        require(stored.startsWith(PREFIX)) { "stored value is not encrypted" }
        val parts = stored.removePrefix(PREFIX).split(":")
        require(parts.size == 2) { "malformed encrypted value" }
        val iv = Base64.getDecoder().decode(parts[0])
        val ct = Base64.getDecoder().decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    override fun isEncrypted(stored: String): Boolean = stored.startsWith(PREFIX)

    private companion object {
        const val PREFIX = "yunx:v1:"
        const val KEY_FILE_NAME = "credential.key"
    }
}
