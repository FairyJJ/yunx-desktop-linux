package com.yunx.app.data.gopeed

/**
 * [Linux 移植] Gopeed 官方内核包的平台坐标。
 *
 * 上游写死 `gopeed-web-<ver>-windows-amd64.zip` + `gopeed.exe`，Linux 上必然取不到内核。
 * 官方 Release 对每个平台都发了同名规则的无界面包：
 * `gopeed-web-v1.9.3-linux-amd64.zip`（内含无后缀的 `gopeed` 二进制）。
 *
 * 来源：mu-zi-lee/YunX-Docker（同项目体系的 Linux 移植）已验证该映射。
 */
data class GopeedPlatform(val system: String, val architecture: String) {

    /** 包内可执行文件名：Windows 带 .exe，类 Unix 无后缀 */
    val executableName: String
        get() = if (system == "windows") "gopeed.exe" else "gopeed"

    /** 官方内核包名，如 gopeed-web-v1.9.3-linux-amd64.zip */
    fun archiveName(version: String): String = "gopeed-web-$version-$system-$architecture.zip"

    companion object {
        fun current(): GopeedPlatform = detect(System.getProperty("os.name"), System.getProperty("os.arch"))

        fun detect(os: String, arch: String): GopeedPlatform {
            val system = when {
                os.lowercase().startsWith("windows") -> "windows"
                os.lowercase().startsWith("mac") || os.lowercase() == "darwin" -> "macos"
                os.lowercase().startsWith("linux") -> "linux"
                else -> error("不支持此系统的 Gopeed 内核：$os")
            }
            val architecture = when (arch.lowercase()) {
                "amd64", "x86_64" -> "amd64"
                "aarch64", "arm64" -> "arm64"
                "x86", "i386", "i686" -> "386"
                else -> error("不支持此架构的 Gopeed 内核：$arch")
            }
            return GopeedPlatform(system, architecture)
        }
    }
}
