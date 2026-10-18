package com.yunx.app.util

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [Linux 移植] 平台判定 + 轻量系统集成。
 *
 * 原项目面向 Windows，系统集成散落在各工具类里用 `os.name.contains("win")` 判定，
 * 非 Windows 一律退化为 no-op。移植到 Linux 时这些能力需要真实现：
 * 桌面通知、文件管理器定位、防休眠、默认应用打开。
 *
 * 这里集中提供，避免各处重复拼命令行。
 */
object Platform {

    private val osName: String = System.getProperty("os.name", "").orEmpty().lowercase()

    val isWindows: Boolean = osName.contains("win")
    val isLinux: Boolean = osName.contains("linux")
    val isMac: Boolean = osName.contains("mac") || osName.contains("darwin")

    /** 可执行文件后缀：Windows 有 .exe，类 Unix 为空 */
    val exeSuffix: String = if (isWindows) ".exe" else ""

    /**
     * 系统托盘是否可用。
     *
     * Linux 上这取决于桌面环境：KDE / XFCE / Cinnamon 自带 legacy tray；GNOME 需要
     * AppIndicator 扩展（Ubuntu 默认带，原版 GNOME 不带）。不可用时调用方必须
     * 退化到「最小化到任务栏」，否则窗口隐藏后用户没有任何入口把它叫回来。
     */
    fun isTraySupported(): Boolean = runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)

    /** 命令是否存在于 PATH（用 execvp 语义探测，不依赖 shell 内建） */
    fun commandExists(name: String): Boolean = runCatching {
        val p = ProcessBuilder(name, "--help")
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        p.waitFor(3, TimeUnit.SECONDS)
        // 能拉起就算存在（--help 退出码不统一，不看返回值）
        true
    }.getOrElse {
        // 上面失败通常是 IOException(ENOENT)：再用 sh -c 'command -v' 兜一层
        runCatching {
            val p = ProcessBuilder("sh", "-c", "command -v $name")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }

    /** 分离式执行（不阻塞、不继承 IO），失败返回 false */
    fun execDetached(vararg cmd: String): Boolean = runCatching {
        ProcessBuilder(*cmd)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        true
    }.getOrDefault(false)

    /**
     * 发送桌面通知（仅 Linux 实现，其他平台返回 false 由调用方回退）。
     *
     * 走 `notify-send`：Ubuntu/GNOME/KDE 默认都装了 libnotify-bin。
     * @param progress 0..100，带进度提示（GNOME Shell / KDE 会渲染成进度条）
     * @param tag 同步标签：同 tag 的通知会原地替换，用于进度刷新不刷屏
     */
    fun notify(
        title: String,
        body: String,
        iconPath: File? = null,
        progress: Int? = null,
        tag: String? = null
    ): Boolean {
        if (!isLinux) return false
        val cmd = mutableListOf("notify-send", "-a", "云析", "-t", "5000")
        iconPath?.takeIf { it.isFile }?.let { cmd += listOf("-i", it.absolutePath) }
        tag?.let { cmd += listOf("-h", "string:x-canonical-private-synchronous:$it") }
        progress?.let { cmd += listOf("-h", "int:value:${it.coerceIn(0, 100)}") }
        cmd += listOf(title, body)
        return execDetached(*cmd.toTypedArray())
    }

    /**
     * 在文件管理器中显示文件 / 打开目录。
     * - [select]=true：调用 org.freedesktop.FileManager1.ShowItems 选中文件（Nautilus/Dolphin/Thunar 均支持）
     * - [select]=false：直接 xdg-open 目录
     * ShowItems 失败（无该接口）时回退为打开父目录。
     */
    fun openInFileManager(target: File, select: Boolean): Boolean {
        if (!isLinux) return false
        if (select) {
            val uri = target.toURI().toString()
            val ok = execDetached(
                "dbus-send", "--session",
                "--dest=org.freedesktop.FileManager1",
                "--type=method_call",
                "/org/freedesktop/FileManager1",
                "org.freedesktop.FileManager1.ShowItems",
                "array:string:$uri", "string:"
            )
            if (ok) return true
        }
        val dir = if (target.isDirectory) target else target.parentFile ?: return false
        return execDetached("xdg-open", dir.absolutePath)
    }

    /** 用系统默认应用打开（Linux 走 xdg-open，比 Desktop.getDesktop 更稳） */
    fun openWithDefaultApp(target: File): Boolean {
        if (!isLinux) return false
        return execDetached("xdg-open", target.absolutePath)
    }
}
