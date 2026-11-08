package dev.pocket.opencode.ui

import dev.pocket.opencode.i18n.t

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import dev.pocket.opencode.ui.theme.LocalPocketAccents
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
    data object Language : SettingsDialog
    data object BridgePort : SettingsDialog
}

@Composable
fun SettingsScreen(engine: PocketEngine) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accents = LocalPocketAccents.current
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val versions by engine.installer.versions.collectAsStateWithLifecycle()
    val install by engine.installer.state.collectAsStateWithLifecycle()
    val server by engine.server.status.collectAsStateWithLifecycle()
    val bridge by engine.deviceShell.status.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }
    var showRegeneratePassword by remember { mutableStateOf(false) }
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
                SectionHeader(t("模型与密钥", "Models & keys"), t("写进容器里的 opencode.json，只存在手机上", "Written to opencode.json inside the container; stored only on this phone"))
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    OpencodeConfig.providers.forEach { provider ->
                        val key = settings.providerKeys[provider.id].orEmpty()
                        SettingRow(
                            icon = PocketIcons.Chip,
                            title = provider.labelText,
                            subtitle = when {
                                key.isNotBlank() -> t("已配置 · ${maskKey(key)}", "Configured · ${maskKey(key)}")
                                provider.hint.isNotBlank() -> provider.hintText
                                else -> t("未配置", "Not configured")
                            },
                            onClick = { dialog = SettingsDialog.ProviderKey(provider.id) },
                            trailing = {
                                if (key.isNotBlank()) {
                                    Icon(
                                        Icons.Filled.Check,
                                        null,
                                        Modifier.size(16.dp),
                                        tint = accents.emerald,
                                    )
                                }
                            },
                        )
                    }
                    if (settings.providerKeys.keys.any { it == "openai-compatible" }) {
                        SettingRow(
                            icon = PocketIcons.Globe,
                            title = t("自定义 baseURL", "Custom baseURL"),
                            subtitle = settings.providerBaseUrls["openai-compatible"].orEmpty().ifBlank { t("例如 https://api.xxx.com/v1", "e.g. https://api.xxx.com/v1") },
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
                    title = t("默认模型", "Default model"),
                    subtitle = t("provider/model，例如 deepseek/deepseek-chat", "provider/model, e.g. deepseek/deepseek-chat"),
                    value = settings.defaultModel.ifBlank { t("自动", "Auto") },
                    onClick = { dialog = SettingsDialog.DefaultModel },
                )
                SettingRow(
                    icon = PocketIcons.Chip,
                    title = t("小模型（可选）", "Small model (optional)"),
                    subtitle = t("用于生成标题等轻量任务", "Used for lightweight tasks such as titles"),
                    value = settings.smallModel.ifBlank { t("自动", "Auto") },
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
                        Text(t("正在写入配置…", "Writing config…"))
                    } else {
                        Text(if (server.running) t("保存并重启服务", "Save & restart server") else t("保存配置", "Save config"))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (server.running) {
                        t("密钥、模型与权限会立刻生效（服务会重启一次）。", "Keys, models and permissions take effect immediately (the server restarts once).")
                    } else {
                        t("服务当前没在运行：配置已保存，下次启动服务时生效。", "The server is not running. Config is saved and will apply on the next start.")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Column {
                SectionHeader(t("服务", "Server"), t("端口、省电与权限策略", "Port, battery and permission policy"))
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Globe,
                        title = t("端口", "Port"),
                        subtitle = t("服务监听 127.0.0.1，只有本机能访问", "Listens on 127.0.0.1; only this device can reach it"),
                        value = settings.port.toString(),
                        onClick = { dialog = SettingsDialog.Port },
                    )
                    SettingRow(
                        icon = PocketIcons.Power,
                        title = t("进入 App 时自动启动", "Start when the app opens"),
                        subtitle = t("打开应用就顺手把服务拉起来", "Bring the server up when you open the app"),
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
                        title = t("开机自动启动", "Start on boot"),
                        subtitle = t("开机后按设置恢复服务（部分 ROM 限制后台启动，失败不影响手动启动）", "Restore the server after boot (some ROMs block background starts; manual start still works)"),
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
                        title = t("服务口令", "Server password"),
                        subtitle = t("127.0.0.1 对同机所有 App 开放，口令是唯一的防线（启动服务时自动带上）", "127.0.0.1 is reachable by every app on this device; the password is the only guard and is sent automatically"),
                        value = if (settings.serverPassword.isBlank()) t("未生成", "Not generated") else maskKey(settings.serverPassword),
                        onClick = {
                            if (settings.serverPassword.isNotBlank()) {
                                copyToClipboard(context, settings.serverPassword, t("口令已复制", "Password copied"))
                            }
                        },
                    )
                    SettingRow(
                        icon = Icons.Filled.Refresh,
                        title = t("重新生成服务口令", "Regenerate server password"),
                        subtitle = t("生成后需要重启服务才生效", "Restart the server for it to take effect"),
                        onClick = { showRegeneratePassword = true },
                    )
                    SettingRow(
                        icon = PocketIcons.Chip,
                        title = t("低内存模式", "Low-memory mode"),
                        subtitle = t("关掉文件监视与 LSP 自动下载，4GB 以下机型更稳", "Disable file watching and LSP auto-download; more stable on devices under 4 GB"),
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
                        title = t("禁用文件监视", "Disable file watching"),
                        subtitle = t("工作区文件很多时关闭，省电", "Turn off for large workspaces to save power"),
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
                        title = t("git 快照（可回滚）", "Git snapshots (rollback)"),
                        subtitle = t("opencode 每次修改都留档；关闭可省磁盘", "Keep a snapshot of every edit; turn off to save disk"),
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
                        title = t("文件修改权限", "File edit permission"),
                        subtitle = t("allow 直接改 / ask 每次询问 / deny 禁止", "allow: edit directly / ask: prompt each time / deny: block"),
                        value = permissionLabel(settings.permissionEdit),
                        onClick = {
                            scope.launch {
                                engine.settings.update { it.copy(permissionEdit = nextPermission(it.permissionEdit)) }
                            }
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Terminal,
                        title = t("执行命令权限", "Command permission"),
                        subtitle = t("容器内 bash 工具的策略", "Policy for the bash tool inside the container"),
                        value = permissionLabel(settings.permissionBash),
                        onClick = {
                            scope.launch {
                                engine.settings.update { it.copy(permissionBash = nextPermission(it.permissionBash)) }
                            }
                        },
                    )
                    SettingRow(
                        icon = PocketIcons.Sliders,
                        title = t("兼容模式", "Compatibility mode"),
                        subtitle = t("个别内核上 proot 需要关掉 seccomp 加速", "Some kernels need proot's seccomp acceleration disabled"),
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
                SectionHeader(t("工作区", "Workspace"), t("手机上的目录会直接挂进容器", "A folder on your phone is mounted into the container"))
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Folder,
                        title = t("使用手机目录", "Use a phone folder"),
                        subtitle = t("关闭则用 App 私有的 /root/workspace", "When off, use the app-private /root/workspace"),
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
                        title = t("把整个共享存储挂进容器", "Mount all shared storage"),
                        subtitle = t("默认关闭：容器里只能看到你选的工作区，看不到相册/下载/其它 App 的导出目录", "Off by default: the container only sees your workspace, not Photos, Downloads or other apps' exports"),
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
                        title = t("工作区路径", "Workspace path"),
                        subtitle = t("容器内固定为 /root/workspace", "Always /root/workspace inside the container"),
                        value = settings.workspace,
                        onClick = { dialog = SettingsDialog.Workspace },
                    )
                    SettingRow(
                        icon = PocketIcons.Shield,
                        title = t("所有文件访问权限", "All files access"),
                        subtitle = if (DevicePermissions.hasAllFilesAccess()) t("已授权", "Granted") else t("未授权，容器只能读写 App 自己的目录", "Not granted; the container can only use app-private folders"),
                        onClick = { DevicePermissions.requestAllFilesAccess(context) },
                        trailing = {
                            if (DevicePermissions.hasAllFilesAccess()) {
                                Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = accents.emerald)
                            } else {
                                Icon(Icons.Filled.Warning, null, Modifier.size(16.dp), tint = accents.amber)
                            }
                        },
                    )
                }
            }
        }

        item {
            Column {
                SectionHeader(t("设备 Shell（Shizuku）", "Device shell (Shizuku)"), t("让 agent 能在手机本体上执行命令", "Let the agent run commands on the phone itself"))
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Terminal,
                        title = t("启用设备 Shell 桥", "Enable device shell bridge"),
                        subtitle = t("容器里的 agent 可以用 dexec 操作手机（读设备信息、剪贴板、系统目录）", "The agent can use dexec on the phone (device info, clipboard, system folders)"),
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
                        title = t("状态", "Status"),
                        subtitle = bridge.message,
                        value = if (bridge.running) t("运行中", "Running") else t("未运行", "Not running"),
                        onClick = { engine.deviceShell.sync() },
                    )
                    when {
                        !settings.deviceShell -> Unit

                        !bridge.available -> SettingRow(
                            icon = PocketIcons.External,
                            title = t("Shizuku 没有运行", "Shizuku is not running"),
                            subtitle = t("先安装并启动 Shizuku（Android 11+ 可用无线调试直接启动），然后回来刷新", "Install and start Shizuku first (Android 11+ can start it over wireless debugging), then come back and refresh"),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, "https://shizuku.rikka.app/download/".toUri()),
                                    )
                                }
                            },
                        )

                        !bridge.granted -> SettingRow(
                            icon = PocketIcons.Shield,
                            title = t("请求 Shizuku 授权", "Request Shizuku permission"),
                            subtitle = if (engine.deviceShell.shizuku.needsManualGrant()) {
                                t("系统可能不再弹授权框，请到 Shizuku 应用里手动允许本应用", "The system may no longer prompt; allow this app manually in Shizuku")
                            } else {
                                t("点一下弹出授权框", "Tap to show the permission prompt")
                            },
                            onClick = { engine.deviceShell.shizuku.requestPermission(SHIZUKU_REQUEST_CODE) },
                        )

                        else -> {
                            SettingRow(
                                icon = PocketIcons.Globe,
                                title = t("桥接地址", "Bridge address"),
                                subtitle = t("容器里固定用 http://127.0.0.1:${settings.deviceShellPort}/exec", "Inside the container use http://127.0.0.1:${settings.deviceShellPort}/exec"),
                                value = "127.0.0.1:${settings.deviceShellPort}",
                                onClick = { dialog = SettingsDialog.BridgePort },
                            )
                            SettingRow(
                                icon = PocketIcons.Code,
                                title = t("访问令牌", "Access token"),
                                subtitle = t("只在本机使用；开启时会重新生成", "Local only; regenerated when enabled"),
                                value = maskKey(settings.deviceShellToken),
                                onClick = {
                                    copyToClipboard(context, settings.deviceShellToken, t("令牌已复制", "Token copied"))
                                },
                            )
                            SettingRow(
                                icon = PocketIcons.Gauge,
                                title = t("跑一条测试命令", "Run a test command"),
                                subtitle = "id; getprop ro.product.model; uname -a",
                                onClick = {
                                    scope.launch { bridgeTest = t("运行中…\n", "Running…\n") + engine.deviceShell.testCommand() }
                                },
                            )
                            SettingRow(
                                icon = Icons.Filled.Refresh,
                                title = t("刷新容器里的 dexec / 技能文件", "Refresh dexec / skill files in the container"),
                                subtitle = t("改了端口或令牌之后点一下", "Tap after changing the port or token"),
                                onClick = { engine.deviceShell.sync() },
                            )
                            SettingRow(
                                icon = PocketIcons.Shield,
                                title = t("危险命令防护", "Dangerous command guard"),
                                subtitle = t("拦截 rm -rf /、pm uninstall、dd 写块设备等 ${dev.pocket.opencode.core.CommandGuard.ruleCount} 类命令", "Blocks rm -rf /, pm uninstall, dd to block devices and ${dev.pocket.opencode.core.CommandGuard.ruleCount} categories in total"),
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
                                title = t("最近执行记录", "Recent executions"),
                                subtitle = t("桥的每次调用（含被拦下的）都会记一笔", "Every bridge call (including blocked ones) is logged"),
                                onClick = {
                                    scope.launch { auditTail = engine.deviceShell.auditTail(40) }
                                },
                            )
                            SettingRow(
                                icon = Icons.Filled.Refresh,
                                title = t("更换访问令牌", "Rotate access token"),
                                subtitle = t("旧令牌立即失效，容器里的 dexec 配置会同步刷新", "The old token stops working immediately; dexec config in the container is refreshed"),
                                onClick = { engine.deviceShell.rotateToken() },
                            )
                        }
                    }
                }
            }
        }

        item {
            Column {
                SectionHeader(t("外观", "Appearance"))
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Translate,
                        title = t("界面语言", "Language"),
                        subtitle = t("切换后立即生效，无需重启应用", "Applies immediately, no restart needed"),
                        value = when (settings.language) {
                            "zh" -> t("简体中文", "Chinese (Simplified)")
                            "en" -> "English"
                            else -> t("跟随系统", "System default")
                        },
                        onClick = { dialog = SettingsDialog.Language },
                    )
                    SettingRow(
                        icon = PocketIcons.Sliders,
                        title = t("主题", "Theme"),
                        value = when (settings.themeMode) {
                            "light" -> t("浅色", "Light")
                            "dark" -> t("深色", "Dark")
                            else -> t("跟随系统", "System default")
                        },
                        onClick = { dialog = SettingsDialog.Theme },
                    )
                    SettingRow(
                        icon = PocketIcons.Globe,
                        title = t("动态取色", "Dynamic color"),
                        subtitle = t("跟随系统壁纸（Android 12+）", "Follow the system wallpaper (Android 12+)"),
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
                SectionHeader(t("维护", "Maintenance"), t("更新、备份与清理", "Updates, backup and cleanup"))
                Spacer(Modifier.height(8.dp))
                PocketCard(contentPadding = 6.dp) {
                    SettingRow(
                        icon = PocketIcons.Download,
                        title = t("更新 OpenCode", "Update OpenCode"),
                        subtitle = when {
                            install.running == dev.pocket.opencode.core.InstallStep.OPENCODE -> t("正在下载并替换…", "Downloading and replacing…")
                            versions["opencode"] != null ->
                                t(
                                    "当前 v${versions["opencode"]} · 点一下拉取最新版（${Mirrors.find(Mirrors.npm, settings.npmMirror).displayName}）",
                                    "Current v${versions["opencode"]} · Tap to fetch the latest (${Mirrors.find(Mirrors.npm, settings.npmMirror).displayName})",
                                )
                            else -> t("从 ${Mirrors.find(Mirrors.npm, settings.npmMirror).displayName} 拉取最新版", "Fetch the latest from ${Mirrors.find(Mirrors.npm, settings.npmMirror).displayName}")
                        },
                        onClick = { scope.launch { engine.installer.updateOpencode() } },
                    )
                    SettingRow(
                        icon = Icons.Filled.Refresh,
                        title = t("重装基础工具", "Reinstall base tools"),
                        subtitle = t("git / curl / ripgrep 出问题时跑一次", "Run this if git / curl / ripgrep misbehave"),
                        onClick = { scope.launch { engine.installer.reinstall(dev.pocket.opencode.core.InstallStep.TOOLS) } },
                    )
                    SettingRow(
                        icon = PocketIcons.Terminal,
                        title = t("导出运行日志", "Export logs"),
                        subtitle = t("会自动打码密钥/口令；日志里仍可能包含你的代码片段与对话内容", "Keys and passwords are redacted; logs may still contain code snippets and chat content"),
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
                        title = t("删除容器环境", "Delete container environment"),
                        subtitle = t("清掉 rootfs 与运行库（配置保留），下次重新安装", "Remove the rootfs and runtime libraries (config is kept); reinstall next time"),
                        onClick = { dialog = SettingsDialog.ResetEnvironment },
                    )
                }
            }
        }

        item {
            InlineBanner(
                icon = Icons.Filled.Warning,
                title = t("幻影进程限制", "Phantom process limit"),
                body = t("Android 12+ 会限制应用的后台进程数量。如果服务被系统杀掉，可用 adb/Shizuku 执行：settings put global settings_enable_monitor_phantom_procs false", "Android 12+ limits background processes. If the system kills the server, run this via adb/Shizuku: settings put global settings_enable_monitor_phantom_procs false"),
                tone = accents.amber,
            )
        }

        item {
            Column {
                SectionHeader(t("关于", "About"))
                Spacer(Modifier.height(8.dp))
                PocketCard {
                    Text(
                        t("OpenCode 口袋 ${dev.pocket.opencode.BuildConfig.VERSION_NAME}", "OpenCode Pocket ${dev.pocket.opencode.BuildConfig.VERSION_NAME}"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t("把官方 opencode 跑在手机里的 Ubuntu（proot，无需 root）。界面、容器与安装脚本都在本仓库里，密钥只写入应用私有目录。", "Run the official opencode in Ubuntu on your phone (proot, no root). The UI, container and installer live in this repository; keys are written only to app-private storage."),
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
                                            "https://opencode.ai/docs/".toUri(),
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
                                            "https://github.com/anomalyco/opencode".toUri(),
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
            title = t("自定义 baseURL", "Custom baseURL"),
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
            title = t("服务端口", "Server port"),
            hint = "1024 - 65535",
            initial = settings.port.toString(),
            keyboardNumber = true,
            validate = { value ->
                val port = value.toIntOrNull()
                if (port == null || port !in 1024..65535) t("端口必须在 1024–65535 之间", "Port must be between 1024 and 65535") else null
            },
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
            title = t("默认模型", "Default model"),
            hint = "deepseek/deepseek-chat",
            initial = settings.defaultModel,
            onSave = { value ->
                scope.launch { engine.settings.update { it.copy(defaultModel = value.trim()) } }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        SettingsDialog.SmallModel -> TextValueDialog(
            title = t("小模型", "Small model"),
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
                title = { Text(t("工作区路径", "Workspace path")) },
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
                                    problem ?: t("容器里始终显示为 /root/workspace", "Always shown as /root/workspace in the container"),
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
                            t("只支持共享存储里的具体目录（/sdcard/…）；整个 /sdcard 或 /data 这种目录不允许当工作区。", "Only a concrete folder under shared storage (/sdcard/…) is allowed; /sdcard itself or /data cannot be the workspace."),
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
                    ) { Text(t("保存", "Save")) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(t("取消", "Cancel")) } },
            )
        }

        SettingsDialog.ResetEnvironment -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(t("删除容器环境？", "Delete the container environment?")) },
            text = { Text(t("会删除 Ubuntu rootfs 与运行库（约 1GB），工作区文件不受影响。下次进入安装页可以重装。", "This removes the Ubuntu rootfs and runtime libraries (~1 GB). Workspace files are untouched. You can reinstall from Setup.")) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    scope.launch {
                        if (server.running) engine.stopServer()
                        engine.installer.uninstallEnvironment()
                    }
                }) { Text(t("删除", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(t("取消", "Cancel")) } },
        )

        SettingsDialog.BridgePort -> TextValueDialog(
            title = t("设备 Shell 桥端口", "Device shell bridge port"),
            hint = t("1024 - 65535，默认 3090", "1024–65535, default 3090"),
            initial = settings.deviceShellPort.toString(),
            keyboardNumber = true,
            validate = { value ->
                val port = value.toIntOrNull()
                if (port == null || port !in 1024..65535) t("端口必须在 1024–65535 之间", "Port must be between 1024 and 65535") else null
            },
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
            title = { Text(t("主题", "Theme")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to t("跟随系统", "System default"), "dark" to t("深色", "Dark"), "light" to t("浅色", "Light")).forEach { (value, label) ->
                        SettingRow(
                            icon = PocketIcons.Sliders,
                            title = label,
                            onClick = {
                                scope.launch { engine.settings.update { it.copy(themeMode = value) } }
                                dialog = null
                            },
                            trailing = {
                                if (settings.themeMode == value) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = accents.cyan)
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(t("关闭", "Close")) } },
        )

        SettingsDialog.Language -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("界面语言") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "system" to t("跟随系统", "System default"),
                        "zh" to t("简体中文", "Chinese (Simplified)"),
                        "en" to "English",
                    ).forEach { (value, label) ->
                        SettingRow(
                            icon = PocketIcons.Translate,
                            title = label,
                            onClick = {
                                dialog = null
                                // 先立刻切 I18n：用户不用等 DataStore 写完就能看到新语言
                                dev.pocket.opencode.i18n.I18n.mode = value
                                scope.launch {
                                    engine.settings.update { it.copy(language = value) }
                                    dev.pocket.opencode.service.Notifications.ensureChannels(context)
                                }
                            },
                            trailing = {
                                if (settings.language == value) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = accents.cyan)
                                }
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text(t("关闭", "Close")) } },
        )

        null -> Unit
    }

    if (showRegeneratePassword) {
        AlertDialog(
            onDismissRequest = { showRegeneratePassword = false },
            title = { Text(t("重新生成服务口令？", "Regenerate the server password?")) },
            text = { Text(t("旧口令会立即失效，服务将重启一次；已经打开的网页和终端都需要重新连接。", "The old password stops working immediately and the server restarts. Open web and terminal sessions must reconnect.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRegeneratePassword = false
                        scope.launch {
                            engine.settings.update { it.copy(serverPassword = "") }
                            engine.settings.ensureServerPassword()
                            if (server.running) engine.startServer(restart = true)
                        }
                    },
                ) { Text(t("重新生成", "Regenerate")) }
            },
            dismissButton = { TextButton(onClick = { showRegeneratePassword = false }) { Text(t("取消", "Cancel")) } },
        )
    }

    bridgeTest?.let { result ->
        AlertDialog(
            onDismissRequest = { bridgeTest = null },
            title = { Text(t("设备 Shell 测试", "Device shell test")) },
            text = {
                Text(
                    result,
                    style = dev.pocket.opencode.ui.theme.CodeTextStyle,
                    modifier = Modifier.heightIn(max = 320.dp),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    copyToClipboard(context, result, t("结果已复制", "Result copied"))
                }) { Text(t("复制", "Copy")) }
            },
            dismissButton = { TextButton(onClick = { bridgeTest = null }) { Text(t("关闭", "Close")) } },
        )
    }

    auditTail?.let { lines ->
        AlertDialog(
            onDismissRequest = { auditTail = null },
            title = { Text(t("设备 Shell 最近执行记录", "Recent device shell executions")) },
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
                TextButton(onClick = { copyToClipboard(context, lines.joinToString("\n"), t("记录已复制", "Records copied")) }) {
                    Text(t("复制", "Copy"))
                }
            },
            dismissButton = { TextButton(onClick = { auditTail = null }) { Text(t("关闭", "Close")) } },
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
        title = { Text(provider?.labelText ?: "API Key") },
        text = {
            Column {
                Text(
                    provider?.hintText?.takeIf { it.isNotBlank() } ?: t("粘贴 provider 的 API Key", "Paste the provider API key"),
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
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(t("保存", "Save")) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text(t("清除", "Clear"), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(t("取消", "Cancel")) }
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
    validate: ((String) -> String?)? = null,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                    problem = null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = problem != null,
                placeholder = { Text(hint, style = MaterialTheme.typography.bodySmall) },
                supportingText = problem?.let {
                    { Text(it, style = MaterialTheme.typography.labelSmall) }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (keyboardNumber) {
                        androidx.compose.ui.text.input.KeyboardType.Number
                    } else {
                        androidx.compose.ui.text.input.KeyboardType.Text
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val checked = validate?.invoke(value)
                if (checked != null) problem = checked else onSave(value)
            }) { Text(t("保存", "Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("取消", "Cancel")) } },
    )
}

private fun maskKey(key: String): String =
    if (key.length <= 8) "••••" else key.take(4) + "••••" + key.takeLast(4)

private fun permissionLabel(value: String): String = when (value) {
    "ask" -> t("每次询问", "Ask each time")
    "deny" -> t("禁止", "Deny")
    else -> t("允许", "Allow")
}

private fun nextPermission(value: String): String = when (value) {
    "allow" -> "ask"
    "ask" -> "deny"
    else -> "allow"
}
