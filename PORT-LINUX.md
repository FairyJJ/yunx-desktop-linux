# YunX-Desktop Linux 移植记录

- **日期**：2026-10-18
- **上游**：`tidain/YunX-Desktop` v1.2.9（Windows 桌面移植版，AGPL-3.0）
- **发布仓库**：`FairyJJ/yunx-desktop-linux`（<https://github.com/FairyJJ/yunx-desktop-linux>，tag `v1.2.9-linux.1`）
- **目标机**：EndeavourOS / Arch 系，GNOME Wayland，x86_64
- **工作副本**：`/home/jay/WorkBuddy/2026-10-17-08-56-35/yunx-linux`
- **安装包**：`~/.cache/wb-pkgs/yunx-desktop-1.2.9-1-x86_64.pkg.tar.zst`（258 M）

## 一、为什么不是「从安卓移植」

上游 `CYQawa/YunX` 是 Android 应用（Kotlin + Jetpack Compose + Room + WebView）。桌面版已经把这些替换过一轮
（Room→`sqlite-jdbc`、SAF→AWT 文件对话框、WebView→JCEF、`SharedPreferences`→`java.util.prefs`），
224 个 Kotlin 文件里 22 个网盘解析器全部是纯 JVM 代码。真正与 Windows 绑定的只有 **约 10 个 util 文件 + 1 处下载内核平台判断**，
所以移植基座选桌面版，工作量为改写这些点，而不是把 Android 那一层再拆一遍。

另有一个 `mu-zi-lee/YunX-Docker` fork：它不是桌面移植，而是新增 `server/` 模块做成**网页版**（浏览器访问 :8080）。
其 `GopeedPlatform.kt` 已解决 Linux 内核包命名问题，本次直接沿用（见下）。

## 二、改动清单（共 12 个文件，+1 新增）

### 1. 构建层 `desktop/build.gradle.kts`

| 原状 | 移植后 |
|---|---|
| `jcef-natives-windows-amd64` 写死 | `jcef-natives-${platform}`，按 `os.name`/`os.arch` 选 `linux-amd64` |
| `compileDarkMode` 用 `cmd`/`cl` 编译 `darkmode.dll` | 非 Windows 注册为空任务（Linux 不需要 uxtheme 桥接） |
| `java.library.path` 用 `;` 拼接 | `File.pathSeparator`（Linux 为 `:`） |
| `targetFormats(TargetFormat.Exe)` | 按平台选 Exe / Deb / Dmg，并补 `linux { packageName … }` |

### 2. 平台集成层

| 能力 | Windows 原实现 | Linux 实现 |
|---|---|---|
| 托盘右键菜单 | JNA `TrackPopupMenuEx` | AWT `PopupMenu`（`TrayManager` 内按平台分派） |
| 托盘不可用 | — | `SystemTray.isSupported()=false` 时「最小化到托盘」退化为最小化到任务栏，避免窗口消失无法恢复 |
| 桌面通知 | PowerShell + WinRT toast | `notify-send`（`x-canonical-private-synchronous` 实现原地刷新 + `int:value` 进度） |
| 下载防休眠 | `SetThreadExecutionState` | `systemd-inhibit --what=idle:sleep --mode=block sleep infinity` |
| 定位文件 | `explorer.exe /select,` | `org.freedesktop.FileManager1.ShowItems`，失败回退 `xdg-open` 父目录 |
| 打开目录/文件 | `explorer.exe` / `Desktop.open` | `xdg-open` |
| 重启应用 | `cmd /c ping & start` | `sh -c 'sleep 2; exec <jpackage launcher>'` |
| 系统暗色 | 注册表 `AppsUseLightTheme` | `gsettings color-scheme=prefer-dark` → `gtk-theme` 含 dark → `GTK_THEME` |
| 系统代理 | 注册表 `Internet Settings` | 环境变量 `http(s)_proxy`/`all_proxy` → `gsettings org.gnome.system.proxy` |
| 数据库密钥绑定 | `MachineGuid`（注册表） | `/etc/machine-id` |
| 「较为严格」加密档 | DPAPI | 标记不可用（无等价实现，避免切档后解不开凭证） |

### 3. Gopeed 下载内核

新增 `GopeedPlatform.kt`（源自 YunX-Docker）：

- 内核包名 `gopeed-web-<ver>-<os>-<arch>.zip`，Linux 取 `gopeed-web-v1.9.3-linux-amd64.zip`
- 包内可执行文件 Windows 叫 `gopeed.exe`，Linux 叫 `gopeed`
- **解包后必须 `setExecutable(true)`** —— zip 不带执行位，否则 `ProcessBuilder` 直接 `EACCES`

### 4. 未移植（缺功能，不影响主流程）

- **一键导入本机浏览器 Cookie**：原实现读 `LOCALAPPDATA` + DPAPI 解密 Chromium `encrypted_value`。
  Linux 侧要做需接 libsecret + AES-256-GCM（`v10` 前缀，key 来自 gnome-keyring），本次未做 —— 目前返回「未检测到浏览器」。
- **内嵌浏览器登录**：JCEF 已可用（日志 `JCEF initialized`），Linux 原生库 325 MB 首次运行解包到
  `~/.yunx-desktop/cache/jcef-bundle`。

## 三、构建与安装

```bash
cd /home/jay/WorkBuddy/2026-10-17-08-56-35/yunx-linux

# 环境（本机已装 jdk17-openjdk；Gradle 由 wrapper 自动下载 8.13）
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export PATH="$JAVA_HOME/bin:$PATH"
export GRADLE_USER_HOME=/home/jay/.cache/wb-yunx/gradle-home

# 1) 编译 + 生成自带运行时的 app-image
java -classpath gradle/wrapper/gradle-wrapper.jar \
  org.gradle.wrapper.GradleWrapperMain :desktop:createDistributable --console=plain

# 2) 打 pacman 包
cd packaging && makepkg -f

# 3) 安装
sudo pacman -U yunx-desktop-1.2.9-1-x86_64.pkg.tar.zst

# 4) 启动
yunx-desktop          # 或应用列表里搜「云析」
```

> 开发调试：`:desktop:run`（需要 `DISPLAY=:0` 与 `XAUTHORITY`，GNOME Wayland 下走 XWayland）。

## 四、安装内容

| 路径 | 内容 |
|---|---|
| `/opt/yunx-desktop/` | app-image（含 jlink 精简 JDK，不依赖系统 JDK） |
| `/usr/bin/yunx-desktop` | 启动脚本 |
| `/usr/share/applications/yunx-desktop.desktop` | 桌面项（`StartupWMClass=com-yunx-app-MainKt`） |
| `/usr/share/icons/hicolor/256x256/apps/yunx-desktop.png` | 图标 |

用户数据：`~/.yunx-desktop/`（`yunx.db`、`credential.key`、日志、JCEF 缓存）。

## 五、卸载 / 回滚

```bash
sudo pacman -Rns yunx-desktop
rm -rf ~/.yunx-desktop          # 连账号与任务一起清
```

## 六、移植中踩到的坑

1. **`pkill -f "<路径含 YunX-Desktop>"` 会杀掉自己** —— 命令行本身就含该字符串。用 `ps` 拿 PID 再 `kill`。
2. **xdotool 点击在缩放下要传绝对坐标**：`--window` 相对坐标在 HiDPI XWayland 上会偏移（实测 (2073,1395) 落到别处），
   改成 `mousemove 2156 1614`（窗口 96,228 + 逻辑 1030,693 × 2）才命中。
3. **Gradle 的 `TaskProvider<out Task>`**：Windows/非 Windows 两个分支注册不同类型的任务时，
   `by tasks.registering(...)` 推断不出共同类型，要显式 `TaskProvider<out Task>`。
4. **jcef 原生库版本号必须与 jcefmaven 严格一致**，否则运行期 JCEF 初始化报 API 不匹配。
5. **app-image 里 lib 目录 331 M**：绝大部分是 JCEF 原生库，打包后 zst 压缩到 258 M，属正常。
