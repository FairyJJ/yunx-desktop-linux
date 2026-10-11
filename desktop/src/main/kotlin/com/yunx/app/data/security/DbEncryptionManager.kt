package com.yunx.app.data.security

import com.yunx.app.AppContext
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.util.Log
import java.io.File

/**
 * 数据库加密等级的读写入口（设置 → 储存目录 → 数据库加密等级）。
 *
 * 三档都只保护**密钥**（库内凭证列本来就是 AES 密文），所以切档 = 解包主密钥 → 按新档位重新包裹 → 原子替换，
 * **不碰库里任何数据**：秒级完成、没有重加密风险。切档立即生效，不需要重启。
 */
object DbEncryptionManager {

    private const val TAG = "YunX-Storage"

    /** 当前档位（读偏好；缺失/非法值一律回落到「普通」） */
    fun currentLevel(): DbEncryptionLevel =
        DbEncryptionLevel.fromId(SettingsRepository().dbEncryptionLevel)

    /**
     * **实际**生效的档位（UI 与提示都应该看这个）：
     * - 密钥文件已存在：以文件里实际的封装格式为准（老版本装的是明文密钥 ⇒ 实际就是「宽松」，
     *   即使用户偏好里是默认的「普通」—— 不能对用户谎报「已绑定本机」）；
     * - 密钥文件还不存在（全新安装）：用偏好里的档位，首次用到凭证时会按它包裹。
     */
    fun effectiveLevel(): DbEncryptionLevel {
        val file = keyFile()
        if (!file.isFile) return currentLevel()
        return wrappedLevel() ?: DbEncryptionLevel.LOOSE
    }

    /** 密钥文件位置（与 [FileCredentialCipher] 用的是同一处） */
    fun keyFile(): File = DbKeyStore.keyFile(AppContext.dbDir, AppContext.dataDir)

    /** 密钥文件里的封装格式对应的档位（文件不存在时返回 null） */
    fun wrappedLevel(): DbEncryptionLevel? {
        val file = keyFile()
        if (!file.isFile || !DbKeyStore.isWrapped(file)) return null
        val text = runCatching { String(file.readBytes(), Charsets.UTF_8) }.getOrNull() ?: return null
        return when {
            text.contains(":mk:v1:") -> DbEncryptionLevel.NORMAL
            text.contains(":dpapi:v1:") -> DbEncryptionLevel.STRICT
            else -> null
        }
    }

    /**
     * 切换档位。失败时保持原档位与原密钥文件不变（先解包成功才写新文件）。
     * @return 实际的旧档位（新装/尚无密钥文件时为 null），供 UI 提示
     */
    fun switchTo(target: DbEncryptionLevel): Result<DbEncryptionLevel?> = runCatching {
        require(target.isSupported) { "「${target.label}」档需要 SQLCipher 原生库，暂未支持" }
        val settings = SettingsRepository()
        val previous = DbEncryptionLevel.fromId(settings.dbEncryptionLevel)
        val file = keyFile()
        // 注意：以「密钥文件里实际的封装格式」为准再包一次 —— 偏好可能是手动改过的，不能只信偏好
        val actual = wrappedLevel() ?: previous
        DbKeyStore.switchLevel(file, actual, target)
        settings.dbEncryptionLevel = target.id
        Log.i(TAG, "数据库加密等级已切换为「${target.label}」")
        if (actual != previous) actual else previous
    }.onFailure { Log.e(TAG, "切换数据库加密等级失败：${it.message}", it) }
}
