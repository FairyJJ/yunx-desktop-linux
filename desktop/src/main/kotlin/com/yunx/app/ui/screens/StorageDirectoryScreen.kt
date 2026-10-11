package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.AppContext
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.storage.StorageMigrator
import com.yunx.app.data.storage.StoragePreset
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 要搬迁的是哪一类目录 */
private enum class StorageKind { CACHE, DB }

/** 一次待确认的搬迁 */
private data class MigrateRequest(val kind: StorageKind, val source: File, val target: File)

/**
 * 储存目录（二级页，设置 → 储存目录）。
 *
 * 三块内容：
 * 1. **下载保存目录**：下载完成后的落盘位置（从设置主页移到这里）；
 * 2. **下载缓存目录**：分片 / 合并临时文件 / JCEF 浏览器数据（含网页版登录态、~200MB Chromium 解包）；
 * 3. **数据库位置**：`yunx.db` 与 `credential.key`（库内凭证用它加密，必须跟着库一起走）。
 *
 * ★ 目录改动**一律需要重启应用生效**：`AppContext` 在启动时只解析一次目录，运行期不再变。
 *   切换时会把旧数据**自动搬到新位置**（拷贝；旧文件留到下次启动再删 —— 本进程还占用着它们，Windows 删不掉）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDirectoryScreen(
    onBack: () -> Unit,
    /** 迁移完成后「立即重启」：由 MainScreen 提供（拉起新实例 + 退出本进程） */
    onRestartApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = rememberGlobalSnackbarHostState()
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository() }

    // 下载保存目录（沿用原有设置项）
    var downloadDir by remember { mutableStateOf(settingsRepo.downloadDirUri) }

    // 默认位置（不受配置影响，用于判定预设）
    val defaultCacheDir = remember { File(AppContext.dataDir, "cache") }
    val defaultDbDir = remember { AppContext.dataDir }
    val appCacheDir = remember { AppContext.appCacheDirPreset() }
    val appDbDir = remember { AppContext.appDataDirPreset() }

    // 当前生效位置（重启后才变）与已配置位置（立刻持久化）
    val effectiveCacheDir = AppContext.cacheDir
    val effectiveDbDir = AppContext.dbDir
    var configuredCache by remember { mutableStateOf(settingsRepo.storageCacheDir) }
    var configuredDb by remember { mutableStateOf(settingsRepo.storageDbDir) }
    val configuredCacheDir = configuredCache?.let { File(it) } ?: defaultCacheDir
    val configuredDbDir = configuredDb?.let { File(it) } ?: defaultDbDir

    var busy by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<MigrateRequest?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    /** 写配置：默认位置存 null（跟随数据根目录），其余存绝对路径 */
    fun persist(kind: StorageKind, target: File) {
        val isDefault = samePath(target, if (kind == StorageKind.CACHE) defaultCacheDir else defaultDbDir)
        val value = if (isDefault) null else target.absolutePath
        if (kind == StorageKind.CACHE) {
            settingsRepo.storageCacheDir = value
            configuredCache = value
        } else {
            settingsRepo.storageDbDir = value
            configuredDb = value
        }
    }

    /** 点击预设 / 选定自定义目录后的统一入口 */
    fun choose(kind: StorageKind, target: File) {
        val configured = if (kind == StorageKind.CACHE) configuredCacheDir else configuredDbDir
        val effective = if (kind == StorageKind.CACHE) effectiveCacheDir else effectiveDbDir
        if (samePath(target, configured)) {
            SnackbarController.show("已经是该设置")
            return
        }
        // 目标就是当前正在用的目录：不需要搬数据，只把配置写回去（也顺带修掉「搬迁后又切回来」的残留配置）
        if (samePath(target, effective)) {
            persist(kind, target)
            SnackbarController.show("已切回当前生效目录（无需搬迁）")
            return
        }
        val reason = if (kind == StorageKind.DB) {
            StorageMigrator.checkDatabaseTarget(target)
        } else {
            StorageMigrator.checkWritable(target)
        }
        if (reason != null) {
            errorText = reason
            return
        }
        request = MigrateRequest(kind, effective, target)
    }

    /** 执行搬迁（先拷贝、成功后写配置；失败一律不写配置） */
    fun migrate(req: MigrateRequest) {
        request = null
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = if (req.kind == StorageKind.CACHE) {
                        StorageMigrator.migrateCache(req.source, req.target)
                    } else {
                        StorageMigrator.migrateDatabase(req.source, req.target)
                    }
                    if (req.kind == StorageKind.CACHE) {
                        StorageMigrator.rememberCleanupForCache(req.source)
                    } else {
                        StorageMigrator.rememberCleanupForDatabase(req.source)
                    }
                    report
                }
            }
            busy = false
            result
                .onSuccess { report ->
                    persist(req.kind, req.target)
                    val notes = buildList {
                        if (report.selfHealingEntries > 0) add("内嵌浏览器数据将在启动时重建")
                        if (report.skippedCount > 0) add("${report.skippedCount} 个文件被占用已跳过")
                    }
                    val noteText = if (notes.isEmpty()) "" else "（${notes.joinToString("；")}）"
                    resultText = "已搬迁 ${report.copiedFiles} 个文件（${formatBytes(report.copiedBytes)}）$noteText\n" +
                        req.target.absolutePath
                }
                .onFailure { e ->
                    errorText = "搬迁失败，目录设置未改动：${e.message ?: e.javaClass.simpleName}"
                }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("储存目录", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    text = "目录改动需要**重启应用**生效；切换时会把旧位置的数据自动搬到新位置（旧文件留到下" +
                        "次启动再清理）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))

                // ===== 下载保存目录 =====
                SectionCard(
                    icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                    title = "下载保存目录",
                    subtitle = "下载完成后文件的落盘位置（默认：系统「下载」文件夹）"
                ) {
                    PathRow(
                        path = downloadDir ?: DesktopActions.defaultDownloadDir.absolutePath,
                        label = if (downloadDir != null) "已自定义" else "系统默认 Download"
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                // 原生目录选择器在独立线程打开，避免阻塞 UI 线程
                                val dir = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
                                if (dir != null) {
                                    settingsRepo.downloadDirUri = dir
                                    downloadDir = dir
                                    SnackbarController.show("下载保存目录已更新")
                                }
                            }
                        }) { Text("选择目录") }
                        if (downloadDir != null) {
                            TextButton(onClick = {
                                settingsRepo.downloadDirUri = null
                                downloadDir = null
                                SnackbarController.show("已恢复默认下载目录")
                            }) { Text("恢复默认") }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ===== 下载缓存目录 =====
                SectionCard(
                    icon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    title = "下载缓存目录",
                    subtitle = "下载分片、合并临时文件与内嵌浏览器数据（含网页版登录态，约 200MB Chromium 解包）"
                ) {
                    PresetRows(
                        configured = configuredCacheDir,
                        defaultDir = defaultCacheDir,
                        appDir = appCacheDir,
                        defaultLabel = "默认（用户目录）",
                        onPick = { choose(StorageKind.CACHE, it) },
                        onPickCustom = { pickCustom(scope, StorageKind.CACHE, ::choose) }
                    )
                    Spacer(Modifier.height(10.dp))
                    PathRow(path = configuredCacheDir.absolutePath, label = "重启后生效")
                    if (!samePath(configuredCacheDir, effectiveCacheDir)) {
                        Spacer(Modifier.height(4.dp))
                        HintRow("当前生效（重启前）：${effectiveCacheDir.absolutePath}")
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        if (!DesktopActions.openDirectory(configuredCacheDir.absolutePath)) {
                            SnackbarController.show("无法打开该目录")
                        }
                    }) { Text("打开所在文件夹") }
                }

                Spacer(Modifier.height(16.dp))

                // ===== 数据库位置 =====
                SectionCard(
                    icon = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                    title = "数据库位置",
                    subtitle = "任务、收藏、历史与**网盘账号凭证**（yunx.db + credential.key，两者必须同处一地）"
                ) {
                    PresetRows(
                        configured = configuredDbDir,
                        defaultDir = defaultDbDir,
                        appDir = appDbDir,
                        defaultLabel = "默认（用户目录）",
                        onPick = { choose(StorageKind.DB, it) },
                        onPickCustom = { pickCustom(scope, StorageKind.DB, ::choose) }
                    )
                    Spacer(Modifier.height(10.dp))
                    PathRow(path = configuredDbDir.absolutePath, label = "重启后生效")
                    if (!samePath(configuredDbDir, effectiveDbDir)) {
                        Spacer(Modifier.height(4.dp))
                        HintRow("当前生效（重启前）：${effectiveDbDir.absolutePath}")
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = {
                        if (!DesktopActions.openDirectory(configuredDbDir.absolutePath)) {
                            SnackbarController.show("无法打开该目录")
                        }
                    }) { Text("打开所在文件夹") }
                    Spacer(Modifier.height(4.dp))
                    HintRow("搬迁时会连同 credential.key 一起搬走 —— 库里的凭证是用它加密的，分开会全部解不开。")
                }

                Spacer(Modifier.height(24.dp))
            }

            // 搬迁进行中：就地遮罩 + 卡片（不用原生弹窗，避免挡住 UI 线程）
            if (busy) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Card(shape = MaterialTheme.shapes.large) {
                            Row(
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(14.dp))
                                Text("正在搬迁数据，请勿关闭应用…")
                            }
                        }
                    }
                }
            }
        }
    }

    // 搬迁确认
    request?.let { req ->
        val isDb = req.kind == StorageKind.DB
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { request = null },
            title = { Text(if (isDb) "搬迁数据库？" else "搬迁下载缓存？") },
            text = {
                Text(
                    buildString {
                        append(if (isDb) "数据库（含凭证密钥）" else "缓存数据")
                        append("将从\n${req.source.absolutePath}\n搬迁到\n${req.target.absolutePath}\n\n")
                        if (isDb) {
                            append("搬迁完成后需要重启应用才会生效。旧位置的数据库文件会在下次启动时删除。")
                        } else {
                            append(
                                "搬迁完成后需要重启应用才会生效。内嵌浏览器的解包文件（约 200MB）不搬、" +
                                    "会由它在启动时自动重建；网页版登录态会保留。旧目录会在下次启动时删除。"
                            )
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { migrate(req) }) { Text("开始搬迁") }
            },
            dismissButton = {
                TextButton(onClick = { request = null }) { Text("取消") }
            }
        )
    }

    // 搬迁成功：提示重启
    resultText?.let { text ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { resultText = null },
            title = { Text("搬迁完成，需重启生效") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    val started = DesktopActions.restartApp()
                    resultText = null
                    if (started) {
                        onRestartApp()
                    } else {
                        errorText = "无法自动启动新实例（开发运行模式不支持），请手动重启应用"
                    }
                }) { Text("立即重启") }
            },
            dismissButton = {
                TextButton(onClick = { resultText = null }) { Text("稍后手动重启") }
            }
        )
    }

    // 失败 / 校验不通过
    errorText?.let { text ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { errorText = null },
            title = { Text("无法切换目录") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { errorText = null }) { Text("知道了") }
            }
        )
    }
}

// ---------- 局部 UI 组件 ----------

@Composable
private fun SectionCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(contentAlignment = Alignment.Center) { icon() }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** 三个预设的单选行（默认 / 应用所在目录 / 自定义） */
@Composable
private fun PresetRows(
    configured: File,
    defaultDir: File,
    appDir: File,
    defaultLabel: String,
    onPick: (File) -> Unit,
    onPickCustom: () -> Unit
) {
    val current = when {
        samePath(configured, appDir) -> StoragePreset.APP_DIR
        samePath(configured, defaultDir) -> StoragePreset.DEFAULT
        else -> StoragePreset.CUSTOM
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        PresetRow(
            title = defaultLabel,
            subtitle = defaultDir.absolutePath,
            selected = current == StoragePreset.DEFAULT
        ) { onPick(defaultDir) }
        PresetRow(
            title = "应用所在目录（便携）",
            subtitle = appDir.absolutePath,
            selected = current == StoragePreset.APP_DIR
        ) { onPick(appDir) }
        PresetRow(
            title = "自定义",
            subtitle = if (current == StoragePreset.CUSTOM) {
                configured.absolutePath
            } else {
                "点击选择其它文件夹"
            },
            selected = current == StoragePreset.CUSTOM,
            onClick = onPickCustom
        )
    }
}

@Composable
private fun PresetRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 当前路径展示（等宽、单行省略、可选中复制） */
@Composable
private fun PathRow(path: String, label: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---------- 工具 ----------

/** 自定义目录：原生目录选择器必须在 IO 线程打开（阻塞式模态对话框，直接跑会卡住 UI） */
private fun pickCustom(scope: CoroutineScope, kind: StorageKind, onChosen: (StorageKind, File) -> Unit) {
    scope.launch {
        val picked = withContext(Dispatchers.IO) { DesktopActions.pickDirectory() }
        if (picked != null) onChosen(kind, File(picked))
    }
}

private fun samePath(a: File, b: File): Boolean =
    runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(a.absolutePath == b.absolutePath)

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return String.format("%.1f %s", value, units[i])
}
