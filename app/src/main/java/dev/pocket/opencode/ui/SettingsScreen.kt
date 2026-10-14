package dev.pocket.opencode.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.Mirrors
import dev.pocket.opencode.core.OpencodeConfig
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.ui.components.InlineBanner
import dev.pocket.opencode.ui.components.PocketCard
import dev.pocket.opencode.ui.components.SectionHeader
import dev.pocket.opencode.ui.components.SettingRow
import dev.pocket.opencode.ui.components.copyToClipboard
import dev.pocket.opencode.ui.theme.PocketColors
import dev.pocket.opencode.util.DevicePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val SHIZUKU_REQUEST_CODE = 4210

private sealed interface SettingsDialog {
    data class ProviderKey(val providerId: String) : SettingsDialog
    data class BaseUrl(val providerId: String) : SettingsDialog
    data object Port : SettingsDialog
    data object DefaultModel : SettingsDialog
    data object SmallModel : SettingsDialog
    data object Workspace : SettingsDialog
    data object ResetEnvironment : SettingsDialog
    data object Theme : SettingsDialog
    data object BridgePort : SettingsDialog
}

@Composable
fun SettingsScreen(engine: PocketEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val versions by engine.installer.versions.collectAsStateWithLifecycle()
    val install by engine.installer.state.collectAsStateWithLifecycle()
    val server by engine.server.status.collectAsStateWithLifecycle()
    val bridge by engine.deviceShell.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var bridgeTest by remember { mutableStateOf<String?>(null) }
    var auditTail by remember { mutableStateOf<List<String>?>(null) }

    // Shizuku 的授权结果回调：授权完成后立刻把桥拉起来
    DisposableEffect(Unit) {
        val listener = Shizuku.OnRequestPermissionResultListener { _, _ ->
            engine.deviceShell.sync()
        }
        engine.deviceShell.shizuku.addPermissionListener(listener)
        onDispose { engine.deviceShell.shizuku.removePermissionListener(listener) }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                SectionHeader("模型与密钥", "写进容器里的 opencode.json，只存在手机上")
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    OpencodeConfig.providers.forEach { provider ->
                        val key = settings.providerKeys[provider.id].orEmpty()
                        SettingRow(
                            icon = PocketIcons.Chip,
                            title = provider.label,
                            subtitle = when {
                                key.isNotBlank() -> "已配置 · ${maskKey(key)}"
                                provider.hint.isNotBlank() -> provider.hint
                                else -> "未配置"
                            },
                            onClick = { dialog = SettingsDialog.ProviderKey(provider.id) },
                            trailing = {
                                if (key.isNotBlank()) {
                                    Icon(
                                        Icons.Filled.Check,
                                        null,
                                        Modifier.size(16.dp),
                                        tint = PocketColors.Emerald,
                                    )
                                }
                            },
                        )
                    }
                    if (settings.providerKeys.keys.any { it == "openai-compatible" }) {
                        SettingRow(
                            icon = PocketIcons.Globe,
                            title = "自定义 baseURL",
                            subtitle = settings.providerBaseUrls["openai-compatible"].orEmpty().ifBlank { "例如 https://api.xxx.com/v1" },
                            onClick = { dialog = SettingsDialog.BaseUrl("openai-compatible") },
                        )
                    }
                }
            }
        }

        item {
            PocketCard(contentPadding = 6.dp) {
                SettingRow(
                    icon = Icons.Filled.Check,
                    title = "默认模型",
                    subtitle = "provider/model，例如 deepseek/deepseek-chat",
                    value = settings.defaultModel.ifBlank { "自动" },
                    onClick = { dialog = SettingsDialog.DefaultModel },
                )
                SettingRow(
                    icon = PocketIcons.Chip,
                    title = "小模型（可选）",
                    subtitle = "用于生成标题等轻量任务",
                    value = settings.smallModel.ifBlank { "自动" },
                    onClick = { dialog = SettingsDialog.SmallModel },
                )
            }
        }

        item {
            Column {
                Button(
                    onClick = {
                        scope.launch {
                            // 把最新设置重新写进容器；服务在跑就顺带重启让它生效
                            engine.installer.reinstall(dev.pocket.opencode.core.InstallStep.CONFIG)
                            if (engine.server.isAlive()) engine.startServer(restart = true)
                        }
                    },
                    enabled = install.running == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (install.running == dev.pocket.opencode.core.InstallStep.CONFIG) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在写入配置…")
                    } else {
                        Text(if (server.running) "保存并重启服务" else "保存配置")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (server.running) {
                        "密钥、模型与权限会立刻生效（服务会重启一次）。"
                    } else {
                        "服务当前没在运行：配置已保存，下次启动服务时生效。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column {
                SectionHeader("服务", "端口、省电与权限策略")
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Globe,
                        title = "端口",
                        subtitle = "服务监听 127.0.0.1，只有本机能访问",
                        value = settings.port.toString(),
                        onClick = { dialog = SettingsDialog.Port },
                    )
                    SettingRow(
                        icon = PocketIcons.Power,
                        title = "进入 App 时自动启动",
                        subtitle = "打开应用就顺手把服务拉起来",
                        trailing = {
                            Switch(
                                checked = settings.autoStart,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(autoStart = value) } }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Bolt,
                        title = "开机自动启动",
                        subtitle = "开机后按设置恢复服务（部分 ROM 限制后台启动，失败不影响手动启动）",
                        trailing = {
                            Switch(
                                checked = settings.autoStartOnBoot,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(autoStartOnBoot = value) } }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Shield,
                        title = "服务口令",
                        subtitle = "127.0.0.1 对同机所有 App 开放，口令是唯一的防线（启动服务时自动带上）",
                        value = if (settings.serverPassword.isBlank()) "未生成" else maskKey(settings.serverPassword),
                        onClick = {
                            if (settings.serverPassword.isNotBlank()) {
                                copyToClipboard(context, settings.serverPassword, "口令已复制")
                            }
                        },
                    )
                    SettingRow(
                        icon = Icons.Filled.Refresh,
                        title = "重新生成服务口令",
                        subtitle = "生成后需要重启服务才生效",
                        onClick = {
                            scope.launch {
                                engine.settings.update { it.copy(serverPassword = "") }
                                engine.settings.ensureServerPassword()
                                if (server.running) engine.startServer(restart = true)
                            }
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Chip,
                        title = "低内存模式",
                        subtitle = "关掉文件监视与 LSP 自动下载，4GB 以下机型更稳",
                        trailing = {
                            Switch(
                                checked = settings.lowMemory,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(lowMemory = value) } }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Folder,
                        title = "禁用文件监视",
                        subtitle = "工作区文件很多时关闭，省电",
                        trailing = {
                            Switch(
                                checked = settings.disableWatcher,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(disableWatcher = value) } }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Shield,
                        title = "git 快照（可回滚）",
                        subtitle = "opencode 每次修改都留档；关闭可省磁盘",
                        trailing = {
                            Switch(
                                checked = settings.snapshot,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(snapshot = value) } }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Shield,
                        title = "文件修改权限",
                        subtitle = "allow 直接改 / ask 每次询问 / deny 禁止",
                        value = permissionLabel(settings.permissionEdit),
                        onClick = {
                            scope.launch {
                                engine.settings.update { it.copy(permissionEdit = nextPermission(it.permissionEdit)) }
                            }
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Terminal,
                        title = "执行命令权限",
                        subtitle = "容器内 bash 工具的策略",
                        value = permissionLabel(settings.permissionBash),
                        onClick = {
                            scope.launch {
                                engine.settings.update { it.copy(permissionBash = nextPermission(it.permissionBash)) }
                            }
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Sliders,
                        title = "兼容模式",
                        subtitle = "个别内核上 proot 需要关掉 seccomp 加速",
                        trailing = {
                            Switch(
                                checked = settings.compatMode,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(compatMode = value) } }
                                },
                            )
                        },
                    )
                }
            }
        }

        item {
            Column {
                SectionHeader("工作区", "手机上的目录会直接挂进容器")
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Folder,
                        title = "使用手机目录",
                        subtitle = "关闭则用 App 私有的 /root/workspace",
                        trailing = {
                            Switch(
                                checked = settings.useExternalWorkspace,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(useExternalWorkspace = value) } }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Storage,
                        title = "把整个共享存储挂进容器",
                        subtitle = "默认关闭：容器里只能看到你选的工作区，看不到相册/下载/其它 App 的导出目录",
                        trailing = {
                            Switch(
                                checked = settings.exposeSdcard,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(exposeSdcard = value) } }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Storage,
                        title = "工作区路径",
                        subtitle = "容器内固定为 /root/workspace",
                        value = settings.workspace,
                        onClick = { dialog = SettingsDialog.Workspace },
                    )
                    SettingRow(
                        icon = PocketIcons.Shield,
                        title = "所有文件访问权限",
                        subtitle = if (DevicePermissions.hasAllFilesAccess()) "已授权" else "未授权，容器只能读写 App 自己的目录",
                        onClick = { DevicePermissions.requestAllFilesAccess(context) },
                        trailing = {
                            if (DevicePermissions.hasAllFilesAccess()) {
                                Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = PocketColors.Emerald)
                            } else {
                                Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = PocketColors.Amber)
                            }
                        },
                    )
                }
            }
        }

        item {
            Column {
                SectionHeader("设备 Shell（Shizuku）", "让 agent 能在手机本体上执行命令")
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Terminal,
                        title = "启用设备 Shell 桥",
                        subtitle = "容器里的 agent 可以用 dexec 操作手机（读设备信息、剪贴板、系统目录）",
                        trailing = {
                            Switch(
                                checked = settings.deviceShell,
                                onCheckedChange = { value ->
                                    scope.launch {
                                        // 每次开启都换一把新令牌，关掉即失效
                                        engine.settings.update { current ->
                                            current.copy(
                                                deviceShell = value,
                                                deviceShellToken = if (value) "" else current.deviceShellToken,
                                            )
                                        }
                                        engine.deviceShell.syncNow()
                                    }
                                },
                            )
                        },
                    )
                    SettingRow(
                        icon = if (bridge.running) PocketIcons.Shield else Icons.Filled.Warning,
                        title = "状态",
                        subtitle = bridge.message,
                        value = if (bridge.running) "运行中" else "未运行",
                        onClick = { engine.deviceShell.sync() },
                    )
                    when {
                        !settings.deviceShell -> Unit

                        !bridge.available -> SettingRow(
                            icon = PocketIcons.External,
                            title = "Shizuku 没有运行",
                            subtitle = "先安装并启动 Shizuku（Android 11+ 可用无线调试直接启动），然后回来刷新",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/")),
                                    )
                                }
                            },
                        )

                        !bridge.granted -> SettingRow(
                            icon = PocketIcons.Shield,
                            title = "请求 Shizuku 授权",
                            subtitle = if (engine.deviceShell.shizuku.deniedForever()) {
                                "已被拒绝，需要到 Shizuku 应用里手动允许本应用"
                            } else {
                                "点一下弹出授权框"
                            },
                            onClick = { engine.deviceShell.shizuku.requestPermission(SHIZUKU_REQUEST_CODE) },
                        )

                        else -> {
                            SettingRow(
                                icon = PocketIcons.Globe,
                                title = "桥接地址",
                                subtitle = "容器里固定用 http://127.0.0.1:${settings.deviceShellPort}/exec",
                                value = "127.0.0.1:${settings.deviceShellPort}",
                                onClick = { dialog = SettingsDialog.BridgePort },
                            )
                            SettingRow(
                                icon = PocketIcons.Code,
                                title = "访问令牌",
                                subtitle = "只在本机使用；开启时会重新生成",
                                value = maskKey(settings.deviceShellToken),
                                onClick = {
                                    copyToClipboard(context, settings.deviceShellToken, "令牌已复制")
                                },
                            )
                            SettingRow(
                                icon = PocketIcons.Gauge,
                                title = "跑一条测试命令",
                                subtitle = "id; getprop ro.product.model; uname -a",
                                onClick = {
                                    scope.launch { bridgeTest = "运行中…\n" + engine.deviceShell.testCommand() }
                                },
                            )
                            SettingRow(
                                icon = Icons.Filled.Refresh,
                                title = "刷新容器里的 dexec / 技能文件",
                                subtitle = "改了端口或令牌之后点一下",
                                onClick = { engine.deviceShell.sync() },
                            )
                            SettingRow(
                                icon = PocketIcons.Shield,
                                title = "危险命令防护",
                                subtitle = "拦截 rm -rf /、pm uninstall、dd 写块设备等 ${dev.pocket.opencode.core.CommandGuard.ruleCount} 类命令",
                                trailing = {
                                    Switch(
                                        checked = settings.deviceShellGuard,
                                        onCheckedChange = { value ->
                                            scope.launch {
                                                engine.settings.update { it.copy(deviceShellGuard = value) }
                                                engine.deviceShell.syncNow()
                                            }
                                        },
                                    )
                                },
                            )
                            SettingRow(
                                icon = PocketIcons.Terminal,
                                title = "最近执行记录",
                                subtitle = "桥的每次调用（含被拦下的）都会记一笔",
                                onClick = {
                                    scope.launch { auditTail = engine.deviceShell.auditTail(40) }
                                },
                            )
                            SettingRow(
                                icon = Icons.Filled.Refresh,
                                title = "更换访问令牌",
                                subtitle = "旧令牌立即失效，容器里的 dexec 配置会同步刷新",
                                onClick = { engine.deviceShell.rotateToken() },
                            )
                        }
                    }
                }
            }
        }

        item {
            Column {
                SectionHeader("外观")
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Sliders,
                        title = "主题",
                        value = when (settings.themeMode) {
                            "light" -> "浅色"
                            "dark" -> "深色"
                            else -> "跟随系统"
                        },
                        onClick = { dialog = SettingsDialog.Theme },
                    )
                    SettingRow(
                        icon = PocketIcons.Globe,
                        title = "动态取色",
                        subtitle = "跟随系统壁纸（Android 12+）",
                        trailing = {
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = { value ->
                                    scope.launch { engine.settings.update { it.copy(dynamicColor = value) } }
                                },
                            )
                        },
                    )
                }
            }
        }

        item {
            Column {
                SectionHeader("维护", "更新、备份与清理")
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Download,
                        title = "更新 OpenCode",
                        subtitle = when {
                            install.running == dev.pocket.opencode.core.InstallStep.OPENCODE -> "正在下载并替换…"
                            versions["opencode"] != null ->
                                "当前 v${versions["opencode"]} · 点一下拉取最新版（${Mirrors.find(Mirrors.npm, settings.npmMirror).name}）"
                            else -> "从 ${Mirrors.find(Mirrors.npm, settings.npmMirror).name} 拉取最新版"
                        },
                        onClick = { scope.launch { engine.installer.updateOpencode() } },
                    )
                    SettingRow(
                        icon = Icons.Filled.Refresh,
                        title = "重装基础工具",
                        subtitle = "git / curl / ripgrep 出问题时跑一次",
                        onClick = { scope.launch { engine.installer.reinstall(dev.pocket.opencode.core.InstallStep.TOOLS) } },
                    )
                    SettingRow(
                        icon = PocketIcons.Terminal,
                        title = "导出运行日志",
                        subtitle = "会自动打码密钥/口令；日志里仍可能包含你的代码片段与对话内容",
                        onClick = {
                            scope.launch {
                                // 日志可能几十 MB，读写在 IO 线程做；同时做脱敏（密钥/令牌/口令）
                                val file = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val raw = buildString {
                                            appendLine("== install.log ==")
                                            appendLine(engine.paths.installLog.takeIf { it.isFile }?.readText().orEmpty())
                                            appendLine("== server.log ==")
                                            appendLine(engine.paths.serverLog.takeIf { it.isFile }?.readText().orEmpty())
                                        }
                                        val secrets = listOfNotNull(
                                            settings.serverPassword.takeIf { it.isNotBlank() },
                                            settings.deviceShellToken.takeIf { it.isNotBlank() },
                                        ) + settings.providerKeys.values
                                        val target = engine.paths.exportDir.resolve("opencode-pocket-logs.txt")
                                        target.writeText(dev.pocket.opencode.util.LogRedactor.redact(raw, secrets))
                                        target
                                    }.getOrNull()
                                }
                                if (file == null) {
                                    return@launch
                                }
                                runCatching {
                                    val uri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.files",
                                        file,
                                    )
                                    context.startActivity(
                                        Intent(Intent.ACTION_SEND)
                                            .setType("text/plain")
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    )
                                }
                            }
                        },
                    )
                    SettingRow(
                        icon = Icons.Filled.Delete,
                        title = "删除容器环境",
                        subtitle = "清掉 rootfs 与运行库（配置保留），下次重新安装",
                        onClick = { dialog = SettingsDialog.ResetEnvironment },
                    )
                }
            }
        }

        item {
            InlineBanner(
                icon = Icons.Filled.Warning,
                title = "幻影进程限制",
                body = "Android 12+ 会限制应用的后台进程数量。如果服务被系统杀掉，可用 adb/Shizuku 执行：settings put global settings_enable_monitor_phantom_procs false",
                tone = PocketColors.Amber,
            )
        }

        item {
            Column {
                SectionHeader("关于")
                Spacer(Modifier.height(8.dp))
                PocketCard {
                    Text(
                        "OpenCode 口袋 ${dev.pocket.opencode.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "把官方 opencode 跑在手机里的 Ubuntu（proot，无需 root）。界面、容器与安装脚本都在本仓库里，密钥只写入应用私有目录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://opencode.ai/docs/"),
                                        ),
                                    )
                                }
                            },
                            label = { Text("opencode 文档", style = MaterialTheme.typography.labelSmall) },
                        )
                        AssistChip(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://github.com/anomalyco/opencode"),
                                        ),
                                    )
                                }
                            },
                            label = { Text("上游仓库", style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        is SettingsDialog.ProviderKey -> KeyDialog(
            provider = OpencodeConfig.findProvider(current.providerId),
            initial = settings.providerKeys[current.providerId].orEmpty(),
            onSave = { key ->
                scope.launch {
                    engine.settings.update { current0 ->
                        val map = current0.providerKeys.toMutableMap()
                        if (key.isBlank()) map.remove(current.providerId) else map[current.providerId] = key.trim()
                        current0.copy(providerKeys = map)
                    }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        is SettingsDialog.BaseUrl -> TextValueDialog(
            title = "自定义 baseURL",
            hint = "https://api.example.com/v1",
            initial = settings.providerBaseUrls[current.providerId].orEmpty(),
            onSave = { value ->
                scope.launch {
                    engine.settings.update { current0 ->
                        val map = current0.providerBaseUrls.toMutableMap()
                        if (value.isBlank()) map.remove(current.providerId) else map[current.providerId] = value.trim()
                        current0.copy(providerBaseUrls = map)
                    }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.Port -> TextValueDialog(
            title = "服务端口",
            hint = "1024 - 65535",
            initial = settings.port.toString(),
            keyboardNumber = true,
            onSave = { value ->
                value.toIntOrNull()?.let { port ->
                    scope.launch {
                        engine.settings.update { it.copy(port = port.coerceIn(1024, 65535)) }
                    }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.DefaultModel -> TextValueDialog(
            title = "默认模型",
            hint = "deepseek/deepseek-chat",
            initial = settings.defaultModel,
            onSave = { value ->
                scope.launch { engine.settings.update { it.copy(defaultModel = value.trim()) } }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.SmallModel -> TextValueDialog(
            title = "小模型",
            hint = "deepseek/deepseek-chat",
            initial = settings.smallModel,
            onSave = { value ->
                scope.launch { engine.settings.update { it.copy(smallModel = value.trim()) } }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.Workspace -> {
            var path by remember { mutableStateOf(settings.workspace) }
            var problem by remember { mutableStateOf<String?>(null) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("工作区路径") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = path,
                            onValueChange = {
                                path = it
                                problem = dev.pocket.opencode.core.WorkspacePolicy.validate(it)
                            },
                            singleLine = true,
                            isError = problem != null,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(
                                    problem ?: "容器里始终显示为 /root/workspace",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf("/sdcard/OpenCode", "/sdcard/Download", "/sdcard/Documents").forEach { preset ->
                                FilterChip(
                                    selected = path == preset,
                                    onClick = {
                                        path = preset
                                        problem = null
                                    },
                                    label = { Text(preset.replace("/sdcard/", ""), style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "只支持共享存储里的具体目录（/sdcard/…）；整个 /sdcard 或 /data 这种目录不允许当工作区。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val checked = dev.pocket.opencode.core.WorkspacePolicy.validate(path)
                            if (checked != null) {
                                problem = checked
                            } else {
                                scope.launch { engine.settings.update { it.copy(workspace = path.trim().trimEnd('/')) } }
                                dialog = null
                            }
                        },
                    ) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
            )
        }

        SettingsDialog.ResetEnvironment -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("删除容器环境？") },
            text = { Text("会删除 Ubuntu rootfs 与运行库（约 1GB），工作区文件不受影响。下次进入安装页可以重装。") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        if (server.running) engine.stopServer()
                        engine.installer.uninstallEnvironment()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )

        SettingsDialog.BridgePort -> TextValueDialog(
            title = "设备 Shell 桥端口",
            hint = "1024 - 65535，默认 3090",
            initial = settings.deviceShellPort.toString(),
            keyboardNumber = true,
            onSave = { value ->
                value.toIntOrNull()?.let { port ->
                    scope.launch {
                        engine.settings.update { it.copy(deviceShellPort = port.coerceIn(1024, 65535)) }
                        engine.deviceShell.syncNow()
                    }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.Theme -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("主题") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to "跟随系统", "dark" to "深色", "light" to "浅色").forEach { (value, label) ->
                        SettingRow(
                            icon = PocketIcons.Sliders,
                            title = label,
                            onClick = {
                                scope.launch { engine.settings.update { it.copy(themeMode = value) } }
                                dialog = null
                            },
                            trailing = {
                                if (settings.themeMode == value) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = PocketColors.Cyan)
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } },
        )

        null -> Unit
    }

    bridgeTest?.let { result ->
        AlertDialog(
            onDismissRequest = { bridgeTest = null },
            title = { Text("设备 Shell 测试") },
            text = {
                Text(
                    result,
                    style = dev.pocket.opencode.ui.theme.CodeTextStyle,
                    modifier = Modifier.heightIn(max = 320.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, result, "结果已复制")
                }) { Text("复制") }
            },
            dismissButton = { TextButton(onClick = { bridgeTest = null }) { Text("关闭") } },
        )
    }

    auditTail?.let { lines ->
        AlertDialog(
            onDismissRequest = { auditTail = null },
            title = { Text("设备 Shell 最近执行记录") },
            text = {
                if (lines.isEmpty()) {
                    Text("还没有记录", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        lines.joinToString("\n"),
                        style = dev.pocket.opencode.ui.theme.CodeTextStyle,
                        modifier = Modifier.heightIn(max = 360.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { copyToClipboard(context, lines.joinToString("\n"), "记录已复制") }) {
                    Text("复制")
                }
            },
            dismissButton = { TextButton(onClick = { auditTail = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun KeyDialog(
    provider: OpencodeConfig.Provider?,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(provider?.label ?: "API Key") },
        text = {
            Column {
                Text(
                    provider?.hint?.takeIf { it.isNotBlank() } ?: "粘贴 provider 的 API Key",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) PocketIcons.EyeOff else PocketIcons.Eye,
                                null,
                                Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text("清除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun TextValueDialog(
    title: String,
    hint: String,
    initial: String,
    keyboardNumber: Boolean = false,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (keyboardNumber) {
                        androidx.compose.ui.text.input.KeyboardType.Number
                    } else {
                        androidx.compose.ui.text.input.KeyboardType.Text
                    },
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun maskKey(key: String): String =
    if (key.length <= 8) "••••" else key.take(4) + "••••" + key.takeLast(4)

private fun permissionLabel(value: String): String = when (value) {
    "ask" -> "每次询问"
    "deny" -> "禁止"
    else -> "允许"
}

private fun nextPermission(value: String): String = when (value) {
    "allow" -> "ask"
    "ask" -> "deny"
    else -> "allow"
}
