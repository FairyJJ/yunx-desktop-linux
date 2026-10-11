package com.yunx.app.data.storage

import com.yunx.app.AppContext
import com.yunx.app.util.Log
import java.io.File
import java.sql.DriverManager

/**
 * 储存目录搬迁（设置 → 储存目录）。
 *
 * 两类目标：
 * - **数据库目录**：`yunx.db`（含 `-wal` / `-shm`）+ `credential.key`。密钥必须跟着库走 ——
 *   库里所有凭证都是用它加密的，留着旧密钥在新目录会**新建一把随机密钥**，密文解不开会被自愈逻辑清空。
 * - **缓存目录**：整棵缓存树（下载分片 / 合并临时文件 / JCEF 浏览器数据）。
 *
 * ★ 三条准则：
 * 1. **只拷贝、不删除**：搬迁当下旧库与 JCEF 目录仍被本进程占用（Windows 上删不掉，硬删会失败），
 *    所以只把「待清理」记进偏好，交给**下次启动**的 `AppContext.cleanupMigratedStorage()` 删；
 * 2. **目标必须干净**：数据库目标已存在 `yunx.db` 一律拒绝（否则两套数据混在一起，无法判断归谁）；
 * 3. **失败不切配置**：任何一步失败都原样保留旧配置，宁可“没搬成”，绝不能“搬一半就切过去”。
 */
object StorageMigrator {

    /** 搬迁结果：拷贝的文件数 / 字节数，以及被跳过（占用、读不到）的文件 */
    data class Report(
        val copiedFiles: Int,
        val copiedBytes: Long,
        val skipped: List<String>,
        /** 未拷贝、将在新位置自动重建的条目数（目前只有 JCEF 解包产物） */
        val selfHealingEntries: Int = 0
    ) {
        val skippedCount: Int get() = skipped.size
    }

    /** 数据库相关文件名（credential.key 与库必须同目录） */
    private val DB_FILES = listOf("yunx.db", "yunx.db-wal", "yunx.db-shm")
    private const val KEY_FILE = "credential.key"

    /**
     * 目标目录是否可写（真正写一个探针文件，避免只看 `canWrite()` 被 ACL 骗过）。
     * 不可写时返回原因文本，可直接展示给用户。
     */
    fun checkWritable(target: File): String? {
        if (!target.exists() && !target.mkdirs()) return "无法创建目录：${target.absolutePath}"
        if (!target.isDirectory) return "目标不是目录：${target.absolutePath}"
        val probe = File(target, ".yunx-write-test")
        return runCatching {
            probe.writeText("ok")
            probe.delete()
            null
        }.getOrElse { "该目录不可写（${it.javaClass.simpleName}）：${target.absolutePath}" }
    }

    /**
     * 数据库目标是否可用：已存在 `yunx.db` 时返回错误文本（拒绝覆盖另一套数据）。
     * 返回 null 表示可以搬。
     */
    fun checkDatabaseTarget(target: File): String? {
        if (File(target, "yunx.db").isFile) {
            return "目标目录已存在数据库文件（yunx.db），请换一个空目录，或先在资源管理器里把它移走"
        }
        return checkWritable(target)
    }

    /** 把数据库目录从 [source] 搬到 [target]（拷贝；旧文件由下次启动清理） */
    fun migrateDatabase(source: File, target: File): Report {
        // 先 checkpoint：把 WAL 里的内容并回主库文件，拷贝出的单文件即完整一致
        checkpointWal(source)
        val report = copyDatabaseFiles(source, target)
        require(File(target, "yunx.db").isFile) { "拷贝后未在目标目录找到 yunx.db" }
        return report
    }

    /** 把缓存目录从 [source] 整棵搬到 [target]（拷贝；旧目录由下次启动删除） */
    fun migrateCache(source: File, target: File): Report =
        copyTree(source, target, skipTopLevel = SELF_HEALING_NAMES)

    /**
     * 不需要拷贝、会在新位置自愈的缓存子项：
     * - `jcef-bundle`：内嵌 Chromium 的解包产物（~200MB），jcefmaven 在找不到时会重新解包 ——
     *   拷贝这棵正被 CEF 占用的树最容易残缺（残缺的 bundle 比没有更糟），交给它自己重建更可靠。
     *   而**登录态在 `jcef/`**（root_cache_path），仍会照常搬过去，所以网页登录不会掉。
     */
    private val SELF_HEALING_NAMES = setOf("jcef-bundle")

    /**
     * 把 WAL 落盘（`wal_checkpoint(TRUNCATE)`）：避免拷贝时主库文件落后于 WAL。
     * 失败不抛（可能连不上/被占用），后面仍会把 `-wal` 一起拷过去兜底。
     */
    private fun checkpointWal(dbDir: File) {
        val dbFile = File(dbDir, "yunx.db")
        if (!dbFile.isFile) return
        runCatching {
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
                conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            }
        }.onFailure { Log.w(TAG, "WAL checkpoint 失败（继续按文件拷贝）：${it.message}") }
    }

    private fun copyDatabaseFiles(source: File, target: File): Report {
        target.mkdirs()
        var files = 0
        var bytes = 0L
        val skipped = mutableListOf<String>()
        val pending = DB_FILES + listOf(KEY_FILE)
        pending.forEach { name ->
            val src = File(source, name)
            if (!src.isFile) return@forEach
            val dst = File(target, name)
            runCatching {
                src.copyTo(dst, overwrite = true)
                files++
                bytes += dst.length()
            }.onFailure {
                skipped.add(name)
                Log.w(TAG, "拷贝失败（跳过）：$name ${it.message}")
            }
        }
        return Report(files, bytes, skipped)
    }

    /** 递归拷贝目录树；单个文件失败只记跳过，不中断整体搬迁 */
    private fun copyTree(source: File, target: File, skipTopLevel: Set<String> = emptySet()): Report {
        target.mkdirs()
        var files = 0
        var bytes = 0L
        var selfHealing = 0
        val skipped = mutableListOf<String>()
        val sourcePath = source.canonicalPath
        source.walkTopDown().forEach { src ->
            val relative = src.absolutePath.removePrefix(sourcePath).trimStart(File.separatorChar)
            if (relative.isEmpty()) return@forEach
            // 自愈目录（JCEF 解包产物）：整棵跳过，不在新位置留半份
            if (skipTopLevel.any { relative == it || relative.startsWith("$it${File.separator}") }) {
                selfHealing++
                return@forEach
            }
            val dst = File(target, relative)
            if (src.isDirectory) {
                runCatching { dst.mkdirs() }
                return@forEach
            }
            runCatching {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
                files++
                bytes += dst.length()
            }.onFailure {
                // 被占用的文件（JCEF 正在用）会走到这里：跳过即可，重启后 JCEF 会自行补齐/重建
                skipped.add(relative)
            }
        }
        return Report(files, bytes, skipped, selfHealing)
    }

    /** 迁移成功后记录「旧位置待清理」，由下次启动删除（本进程还占着旧文件，删不掉） */
    fun rememberCleanupForDatabase(oldDir: File) {
        AppContext.miscPrefs.put(AppContext.KEY_DB_MIGRATED_FROM, oldDir.absolutePath)
    }

    fun rememberCleanupForCache(oldDir: File) {
        AppContext.miscPrefs.put(AppContext.KEY_CACHE_MIGRATED_FROM, oldDir.absolutePath)
    }

    private const val TAG = "YunX-Storage"
}

/**
 * 「储存目录」三个预设：[DEFAULT] 用户目录（数据根目录）、[APP_DIR] 应用所在目录（便携）、[CUSTOM] 自定义。
 */
enum class StoragePreset { DEFAULT, APP_DIR, CUSTOM }
