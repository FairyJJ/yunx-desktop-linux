package com.yunx.app

import com.yunx.app.util.Log
import java.io.File
import java.util.prefs.Preferences

/**
 * 桌面应用上下文门面：替代 Android Context。
 * 数据/缓存/临时目录、以及非设置类的杂项偏好（onboarding、忽略版本、迅雷设备指纹）。
 *
 * 目录分三层（自 2026-10-11 起后两层可在「设置 → 储存目录」里改，改完需重启生效）：
 * 1. [dataDir] 数据根目录：默认 `<用户目录>/.yunx-desktop`，可用环境变量 `YUNX_DESKTOP_DATA_DIR` 覆盖；
 *    日志（[filesDir]）与诊断日志目录跟随它，**不可在应用内更改**。
 * 2. [dbDir] 数据库目录：默认＝[dataDir]，放 `yunx.db` 与 `credential.key`（两者必须同处一地）。
 * 3. [cacheDir] 缓存根目录：默认＝`<dataDir>/cache`，放下载分片、合并临时文件、JCEF 浏览器数据。
 *
 * ★ 目录配置存在 [miscPrefs]（java.util.prefs，与数据目录无关）——否则「把数据目录搬走」之后
 *   就再也读不到「该去哪找数据」了。启动时只读一次，因此改动天然是「重启后生效」。
 */
object AppContext {

    /** 应用数据根目录：默认 <用户目录>/.yunx-desktop；可用环境变量 YUNX_DESKTOP_DATA_DIR 覆盖（测试/沙箱环境用） */
    val dataDir: File = System.getenv("YUNX_DESKTOP_DATA_DIR")?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".yunx-desktop")

    /** 旧版数据根目录（.yunx-pc）：首次启动时整体迁移到新目录 */
    private val legacyDataDir: File = File(System.getProperty("user.home"), ".yunx-pc")

    // ---------- 杂项偏好（必须在下面解析目录之前声明：Kotlin 单例按声明顺序初始化）----------

    /** 杂项偏好（原 "yunx_prefs" SharedPreferences：onboarding_shown / ignored_version） */
    val miscPrefs: Preferences = Preferences.userRoot().node("yunx/misc")

    /** 迅雷设备指纹偏好（原 "xunlei_device_fp"） */
    val xunleiFpPrefs: Preferences = Preferences.userRoot().node("yunx/xunlei_fp")

    // ---------- 可在应用内更改的目录 ----------

    /** 应用根目录（便携版 = exe 所在目录；开发运行 = 工程目录），供「储存目录」的「应用所在目录」预设使用 */
    val appRootDir: File = resolveAppRoot()

    /** 数据库目录（`yunx.db` + `credential.key`）：默认 [dataDir]，可改为 <应用根>/data 或自定义 */
    val dbDir: File = resolveConfiguredDir(KEY_DB_DIR, dataDir)

    /** 缓存根目录（下载分片 / 合并临时文件 / JCEF 浏览器数据）：默认 `<dataDir>/cache` */
    val cacheDir: File = resolveConfiguredDir(KEY_CACHE_DIR, File(dataDir, "cache"))

    /** 下载分片目录（原 externalCacheDir/download_tmp） */
    val downloadTmpDir: File = File(cacheDir, "download_tmp")

    /** 合并暂存目录（原 cacheDir/merged_*） */
    val mergeDir: File = File(cacheDir, "merge")

    /** 杂项文件目录（日志等）；跟随数据根目录，不随缓存/数据库设置变化 */
    val filesDir: File = File(dataDir, "files")

    // 偏好键（与 SettingsRepository 的 storage* 属性一一对应；这里直接读是为了在 SettingsRepository 可用之前就定好目录）
    internal const val KEY_DB_DIR = "storage_db_dir"
    internal const val KEY_CACHE_DIR = "storage_cache_dir"

    /** 迁移完成后待清理的旧目录（下次启动时删掉旧库文件 / 旧缓存目录） */
    internal const val KEY_DB_MIGRATED_FROM = "storage_db_migrated_from"
    internal const val KEY_CACHE_MIGRATED_FROM = "storage_cache_migrated_from"

    fun init() {
        migrateLegacyDataDir()
        listOf(dataDir, dbDir, cacheDir, downloadTmpDir, mergeDir, filesDir).forEach { it.mkdirs() }
        cleanupMigratedStorage()
    }

    /** 「应用所在目录」预设对应的目标目录：<应用根>/data（不直接写在程序根目录里，避免和 app/ runtime/ 混在一起） */
    fun appDataDirPreset(): File = File(appRootDir, "data")

    /** 「应用所在目录」预设对应的缓存目录：<应用根>/data/cache */
    fun appCacheDirPreset(): File = File(appRootDir, "data/cache")

    /**
     * 旧数据目录迁移：<用户目录>/.yunx-pc → .yunx-desktop。
     * 仅在新目录不存在、且未用环境变量覆盖时执行；整体改名（不复制，下载分片可能很大），
     * 改名失败则退回使用新建的空目录（登录态与任务需重新登录/重新添加）。
     */
    private fun migrateLegacyDataDir() {
        if (System.getenv("YUNX_DESKTOP_DATA_DIR") != null) return
        if (dataDir.exists() || !legacyDataDir.exists()) return
        if (!legacyDataDir.renameTo(dataDir)) {
            Log.w("YunX-DataDir", "旧数据目录迁移失败：${legacyDataDir.absolutePath}")
            return
        }
        // 日志文件一并改名，避免迁移后旧日志成为游离文件
        File(filesDir, "yunx-pc.log").takeIf { it.exists() }
            ?.renameTo(File(filesDir, "yunx-desktop.log"))
        Log.i("YunX-DataDir", "已迁移数据目录：${legacyDataDir.name} -> ${dataDir.name}")
    }

    /**
     * 清理「储存目录」迁移后留下的旧文件（只在**下一次启动**做，因为搬迁当下旧库/旧 JCEF 目录
     * 仍被本进程占用，Windows 上删不掉）。仅当新位置确实可用时才删，避免把唯一一份数据删掉。
     */
    private fun cleanupMigratedStorage() {
        runCatching {
            val oldDb = miscPrefs.get(KEY_DB_MIGRATED_FROM, null)?.trim().orEmpty()
            if (oldDb.isNotEmpty()) {
                val oldDir = File(oldDb)
                if (oldDir.canonicalPath != dbDir.canonicalPath && File(dbDir, "yunx.db").isFile) {
                    // 只删库文件（含 WAL/SHM）；credential.key 留着，便于用户切回默认目录
                    listOf("yunx.db", "yunx.db-wal", "yunx.db-shm").forEach {
                        runCatching { File(oldDir, it).delete() }
                    }
                    Log.i("YunX-DataDir", "已清理迁移后的旧数据库文件：${oldDir.absolutePath}")
                }
                miscPrefs.remove(KEY_DB_MIGRATED_FROM)
            }
            val oldCache = miscPrefs.get(KEY_CACHE_MIGRATED_FROM, null)?.trim().orEmpty()
            if (oldCache.isNotEmpty()) {
                val oldDir = File(oldCache)
                if (oldDir.canonicalPath != cacheDir.canonicalPath && oldDir.isDirectory &&
                    cacheDir.isDirectory && cacheDir.listFiles()?.isNotEmpty() == true
                ) {
                    oldDir.deleteRecursively()
                    Log.i("YunX-DataDir", "已清理迁移后的旧缓存目录：${oldDir.absolutePath}")
                }
                miscPrefs.remove(KEY_CACHE_MIGRATED_FROM)
            }
        }.onFailure { Log.w("YunX-DataDir", "清理迁移残留失败：${it.message}") }
    }

    /** 读取配置的目录；未配置或目录不可用（被删/无权限）时回退默认值，绝不让应用起不来 */
    private fun resolveConfiguredDir(key: String, defaultDir: File): File {
        val raw = miscPrefs.get(key, null)?.trim().orEmpty()
        if (raw.isEmpty()) return defaultDir
        val configured = File(raw)
        if (configured.isDirectory) return configured
        if (configured.mkdirs()) return configured
        Log.w("YunX-DataDir", "配置的目录不可用，回退默认：$raw")
        return defaultDir
    }

    /**
     * 应用根目录：优先取 jpackage 注入的 `jpackage.app-path`（指向 `<root>/YunX-Desktop.exe`），
     * 便携版启动器会把工作目录设为程序根目录（开发运行时为工程目录）。
     */
    private fun resolveAppRoot(): File {
        System.getProperty("jpackage.app-path")?.let { p ->
            File(p).parentFile?.takeIf { it.isDirectory }?.let { return it }
        }
        return File(System.getProperty("user.dir") ?: ".")
    }
}
