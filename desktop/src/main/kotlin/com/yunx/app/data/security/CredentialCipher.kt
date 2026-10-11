package com.yunx.app.data.security

import com.yunx.app.AppContext
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.DiagnosticLog
import com.yunx.app.util.Log
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
 * 主密钥为首次启动生成的随机 32 字节，保存在 `<数据库目录>/credential.key`；
 * 密钥本身按「设置 → 储存目录 → 数据库加密等级」的档位包裹（明文 / 本机派生密钥 / DPAPI，见 [DbKeyStore]）。
 * 输出格式与原 Android 版完全相同："yunx:v1:<iv Base64>:<ct Base64>"，AAD = purpose。
 */
internal class FileCredentialCipher : CredentialCipher {

    private val key: SecretKeySpec by lazy {
        val keyFile = DbKeyStore.keyFile(AppContext.dbDir, AppContext.dataDir)
        val level = DbEncryptionLevel.fromId(SettingsRepository().dbEncryptionLevel)
        val bytes = runCatching { DbKeyStore.loadOrCreate(keyFile, level) }.getOrElse { e ->
            // 密钥解不开（最典型：在「普通 / 较为严格」档下把数据拷到了别的机器/别的 Windows 用户）：
            // 这把密钥已经不可恢复。保留原文件备查，另生成一把新密钥 —— 否则连「重新登录」都保存不了，
            // 应用会直接变成不可用；库里那些解不开的密文由 DAO 的自愈逻辑清掉，用户重新登录即可。
            Log.e(TAG, "凭证密钥解不开（${e.message}），改用新密钥：旧文件保留为 ${keyFile.name}.unreadable")
            runCatching {
                keyFile.renameTo(File(keyFile.parentFile, "${keyFile.name}.unreadable"))
            }
            DbKeyStore.loadOrCreate(keyFile, level)
        }
        require(bytes.size == 32) { "invalid credential key file" }
        SecretKeySpec(bytes, "AES")
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
        const val TAG = "YunX-Crypto"
    }
}
