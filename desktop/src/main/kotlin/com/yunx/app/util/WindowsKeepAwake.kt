package com.yunx.app.util

import com.sun.jna.Native
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载期间阻止系统睡眠。
 *
 * - **Windows**：kernel32.SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)。
 *   该状态是线程本地属性，所有调用固定投递到同一线程执行，引用计数归零时解除。
 * - **Linux**（[Linux 移植]）：起一个 `systemd-inhibit --what=idle:sleep --mode=block sleep infinity`
 *   子进程，进程存活期间系统不进入 idle 睡眠；release 时销毁该进程即解除。
 *   systemd-inhibit 不可用（非 systemd 发行版 / 容器）时静默降级为 no-op。
 */
object WindowsKeepAwake {
    private interface Kernel32 : StdCallLibrary {
        fun SetThreadExecutionState(esFlags: Int): Int

        companion object {
            val INSTANCE: Kernel32 = Native.load("kernel32", Kernel32::class.java)
        }
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001

    /** 所有 SetThreadExecutionState 调用固定在此线程（状态线程本地，跨线程会失调） */
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "yunx-keep-awake") }
    private val refs = AtomicInteger(0)

    /** Linux：持有 inhibit 的子进程（null 表示未持锁或平台不支持） */
    @Volatile
    private var linuxInhibitor: Process? = null

    fun acquire() {
        if (refs.getAndIncrement() == 0) {
            if (Platform.isLinux) {
                startLinuxInhibitor()
            } else {
                executor.execute {
                    runCatching { Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED) }
                }
            }
        }
    }

    fun release() {
        if (refs.decrementAndGet() <= 0) {
            refs.set(0)
            if (Platform.isLinux) {
                stopLinuxInhibitor()
            } else {
                executor.execute {
                    runCatching { Kernel32.INSTANCE.SetThreadExecutionState(ES_CONTINUOUS) }
                }
            }
        }
    }

    private fun startLinuxInhibitor() {
        if (linuxInhibitor?.isAlive == true) return
        linuxInhibitor = runCatching {
            ProcessBuilder(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=云析 YunX-Desktop",
                "--why=正在下载文件，请勿休眠",
                "--mode=block",
                "sleep", "infinity"
            )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure {
            Log.w("YunX-KeepAwake", "systemd-inhibit 启动失败，放弃防休眠: ${it.message}")
        }.getOrNull()
    }

    private fun stopLinuxInhibitor() {
        val p = linuxInhibitor ?: return
        linuxInhibitor = null
        runCatching { p.destroy() }
        runCatching {
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }
}
