package com.yunx.app.util

import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘管理器。
 *
 * 右键菜单两条实现路径：
 * - **Windows**：JNA 调 Win32 TrackPopupMenu（[Win32PopupMenu]），中文正常、暗色跟随系统，
 *   不用 Swing、不自绘窗口。TrayIcon 必须挂一个空 PopupMenu 才能收到右键事件。
 * - **Linux**：AWT 原生 PopupMenu（交给桌面环境的 StatusNotifier/XEmbed 宿主渲染），
 *   菜单项点击回调同一个 command id。GNOME 无托盘宿主时 [install] 返回 false，
 *   调用方需退化为「最小化到任务栏」，否则窗口隐藏后无法恢复。
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null

    /** 是否已安装托盘图标（Linux 上为 false 时禁用「最小化到托盘」） */
    val isInstalled: Boolean
        get() = trayIcon != null

    /**
     * 安装托盘图标。
     * @param image 图标
     * @param onLeftClick 左键单击回调
     * @param menuItems 右键菜单项（label to commandId；label 为 "-" 表示分隔线）
     * @param onMenuCommand 菜单项被选中后的回调（参数为 commandId）
     * @return 是否安装成功
     */
    fun install(
        image: BufferedImage,
        onLeftClick: () -> Unit,
        menuItems: List<Pair<String, Int>>,
        onMenuCommand: (Int) -> Unit
    ): Boolean {
        if (!SystemTray.isSupported()) {
            Log.w("YunX-Tray", "当前桌面环境没有系统托盘宿主，跳过托盘安装")
            return false
        }
        if (trayIcon != null) uninstall()

        val icon = TrayIcon(image.getScaledInstance(22, 22, Image.SCALE_SMOOTH), "云析 YunX-Desktop").apply {
            isImageAutoSize = true
            popupMenu = buildPopupMenu(menuItems, onMenuCommand)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onLeftClick()
                }

                override fun mousePressed(e: MouseEvent) {
                    // Windows 的右键菜单由 Win32PopupMenu 自己弹，AWT 只负责把坐标交出来。
                    // Linux 由系统托盘宿主弹 popupMenu，这里不再重复处理。
                    if (e.button == MouseEvent.BUTTON3 && Platform.isWindows) {
                        e.consume()
                        val cmd = Win32PopupMenu.show(menuItems, e.xOnScreen, e.yOnScreen)
                        if (cmd != 0) onMenuCommand(cmd)
                    }
                }
            })
        }
        return runCatching {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            Log.i("YunX-Tray", "托盘已安装（platform=${if (Platform.isWindows) "windows" else "linux"}）")
            true
        }.getOrElse {
            Log.w("YunX-Tray", "托盘安装失败: ${it.message}")
            false
        }
    }

    /**
     * 构造 AWT PopupMenu。
     * Windows 下也建（但只用它的「存在」触发右键事件），菜单项在 Linux 上才真正可见可点。
     */
    private fun buildPopupMenu(
        items: List<Pair<String, Int>>,
        onCommand: (Int) -> Unit
    ): PopupMenu = PopupMenu().apply {
        items.forEach { (label, id) ->
            if (label == "-") {
                addSeparator()
            } else {
                add(MenuItem(label).apply {
                    addActionListener { onCommand(id) }
                })
            }
        }
    }

    /** 移除托盘图标 */
    fun uninstall() {
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
            trayIcon = null
        }
    }

    /**
     * 显示系统通知。
     *
     * - Linux：走 notify-send（AWT 托盘的 displayMessage 在多数 Linux 桌面不显示）
     * - Windows：AWT 托盘气泡，Win10/11 会归入通知中心
     */
    fun showNotification(title: String, message: String) {
        if (Platform.isLinux) {
            if (Platform.notify(title, message, loadIconFile())) return
        }
        val icon = trayIcon ?: return
        runCatching { icon.displayMessage(title, message, TrayIcon.MessageType.NONE) }
            .onFailure { Log.w("YunX-Tray", "showNotification failed: ${it.message}") }
    }

    /** 从 classpath 加载 icon.png */
    fun loadTrayIcon(): BufferedImage? = runCatching {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        ImageIO.read(stream)
    }.getOrNull()

    /** 从 classpath 把 icon.png 落到临时文件（notify-send 需要真实路径，不能用 jar 内资源） */
    fun notificationIcon(): java.io.File? = loadIconFile()

    private fun loadIconFile(): java.io.File? = runCatching {
        val cached = java.io.File(System.getProperty("java.io.tmpdir"), "yunx-notify-icon.png")
        if (cached.isFile && cached.length() > 0) return@runCatching cached
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        stream.use { input -> cached.outputStream().use { input.copyTo(it) } }
        cached
    }.getOrNull()
}
