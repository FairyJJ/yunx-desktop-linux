package com.yunx.app.util

import com.yunx.app.AppContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 替代 android.util.Log 的日志门面：输出 stdout + 追加数据目录日志文件。
 * 与 android.util.Log 同签名（i/d/w/e），仅需替换 import。
 *
 * 轮转：每满 [ROTATE_INTERVAL_DAYS] 天（按日期，默认一周）把当前文件滚为
 * `yunx-desktop.1.log`（覆盖上一份），**只保留两轮**（当前 + 上一轮），磁盘占用有上限。
 */
object Log {

    private val logFile: File = File(AppContext.filesDir, "yunx-desktop.log")

    /** 上一轮日志（只有一份，滚动时覆盖） */
    private val backupFile: File = File(AppContext.filesDir, "yunx-desktop.1.log")

    /** 轮转周期（天） */
    private const val ROTATE_INTERVAL_DAYS = 7L

    /**
     * 当前日志段起始日期。首次写入时从已有文件推断一次（惰性），之后只比对内存值，
     * 避免每次写日志都去碰磁盘。
     */
    private var segmentStart: LocalDate? = null

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String) = write("E", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable) = write("E", tag, "$msg\n${tr.stackTraceToString()}")

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val now = LocalDateTime.now()
        val line = "$now $level/$tag: $msg"
        println(line)
        runCatching {
            logFile.parentFile?.mkdirs()
            rotateIfNeeded(now.toLocalDate())
            logFile.appendText(line + "\n")
        }
    }

    /** 到点就滚：把当前文件移到备份位（覆盖旧的），任何时刻最多两个文件 */
    private fun rotateIfNeeded(today: LocalDate) {
        val start = segmentStart ?: detectSegmentStart(today).also { segmentStart = it }
        if (start.plusDays(ROTATE_INTERVAL_DAYS).isAfter(today)) return
        if (!logFile.isFile) {
            // 文件已被外部清掉：直接开新的一轮，别去动备份
            segmentStart = today
            return
        }
        val moved = runCatching {
            Files.move(logFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        }.getOrDefault(false)
        // 移动失败就先不推进起点，下次写入再试
        if (moved) segmentStart = today
    }

    /**
     * 推断当前日志段的起始日期：优先取首行时间戳（与写入格式一致，兼容旧日志）；
     * 空文件/解析失败时退回文件修改时间，都没有就用今天。
     */
    private fun detectSegmentStart(today: LocalDate): LocalDate {
        if (!logFile.isFile) return today
        val firstLine = runCatching { logFile.bufferedReader().use { it.readLine() } }.getOrNull()
        firstLine?.take(10)?.let { head ->
            runCatching { LocalDate.parse(head) }.getOrNull()?.let { return it }
        }
        return runCatching {
            Instant.ofEpochMilli(logFile.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
        }.getOrDefault(today)
    }
}
