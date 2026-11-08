package dev.pocket.opencode.ui

import dev.pocket.opencode.i18n.t

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.InstallStep
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.core.ServerPhase
import dev.pocket.opencode.core.StepState
import dev.pocket.opencode.ui.components.HeroCard
import dev.pocket.opencode.ui.components.InlineBanner
import dev.pocket.opencode.ui.components.KeyValueRow
import dev.pocket.opencode.ui.components.LogConsole
import dev.pocket.opencode.ui.components.PocketCard
import dev.pocket.opencode.ui.components.SectionHeader
import dev.pocket.opencode.ui.components.StatusOrb
import dev.pocket.opencode.ui.components.StatusPill
import dev.pocket.opencode.ui.theme.LocalPocketAccents
import dev.pocket.opencode.util.DevicePermissions
import dev.pocket.opencode.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    engine: PocketEngine,
    onOpenSetup: () -> Unit,
    onOpenWeb: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accents = LocalPocketAccents.current
    val install by engine.installer.state.collectAsStateWithLifecycle()
    val versions by engine.installer.versions.collectAsStateWithLifecycle()
    val server by engine.server.status.collectAsStateWithLifecycle()
    val logs by engine.server.recentLogs.collectAsStateWithLifecycle()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    var showDoctor by remember { mutableStateOf(false) }

    var showStopConfirm by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 只在服务运行时每秒刷新「已运行时长」；其他状态下不要白白重组整个首页
    LaunchedEffect(server.phase) {
        while (server.phase == ServerPhase.RUNNING) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val diskUsage by produceState(0L, install.installed, install.running) {
        value = withContext(Dispatchers.IO) { engine.shell.hostSize(engine.paths.home) }
    }

    // 工作区解析涉及磁盘探测（mkdirs/canWrite），放到 IO 线程，避免组合期卡主线程
    val workspace by produceState<dev.pocket.opencode.core.Proot.Workspace?>(
        initialValue = null,
        settings.useExternalWorkspace,
        settings.workspace,
    ) {
        value = withContext(Dispatchers.IO) { engine.proot.resolveWorkspace(settings) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HeroCard(accent = when (server.phase) {
                ServerPhase.RUNNING -> accents.emerald
                ServerPhase.ERROR -> accents.rose
                ServerPhase.STARTING, ServerPhase.STOPPING -> accents.amber
                else -> accents.cyan
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusOrb(server.phase, 72.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when (server.phase) {
                                ServerPhase.RUNNING -> t("服务运行中", "Server running")
                                ServerPhase.STARTING -> t("正在启动…", "Starting…")
                                ServerPhase.STOPPING -> t("正在停止…", "Stopping…")
                                ServerPhase.ERROR -> t("启动失败", "Failed to start")
                                ServerPhase.STOPPED -> if (install.installed) t("已停止", "Stopped") else t("环境未安装", "Environment not installed")
                            },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                server.phase == ServerPhase.RUNNING ->
                                    t(
                                        "端口 ${server.port} · v${server.version.ifBlank { "?" }} · 已运行 ${Format.duration(now - server.startedAt)}",
                                        "Port ${server.port} · v${server.version.ifBlank { "?" }} · up ${Format.duration(now - server.startedAt)}",
                                    )
                                server.error != null -> server.error.orEmpty()
                                install.running != null -> install.message
                                install.installed -> t("点一下就能开始写代码", "Tap to start coding")
                                else -> t("先完成一次安装（约 5 分钟）", "Run the setup first (~5 minutes)")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (server.phase == ServerPhase.RUNNING) {
                        Button(onClick = { showStopConfirm = true }) {
                            Icon(PocketIcons.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(t("停止", "Stop"))
                        }
                        FilledTonalButton(onClick = onOpenWeb) {
                            Icon(PocketIcons.Globe, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("打开网页")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (install.installed) engine.startServer() else onOpenSetup()
                            },
                            enabled = install.running == null,
                        ) {
                            Icon(
                                if (install.installed) Icons.Filled.PlayArrow else PocketIcons.Bolt,
                                null,
                                Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (install.installed) t("启动服务", "Start server") else t("开始安装", "Start setup"))
                        }
                        if (install.installed) {
                            OutlinedButton(onClick = { engine.startServer(restart = true) }) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("重启")
                            }
                        }
                    }
                }
            }
        }

        if (!install.installed) {
            item {
                PocketCard(onClick = onOpenSetup) {                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(PocketIcons.Bolt, null, Modifier.size(22.dp), tint = accents.cyan)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("安装 Linux 运行环境", style = MaterialTheme.typography.titleSmall)
                            Text(
                                t("自动下载 Ubuntu rootfs、基础工具与 OpenCode 本体", "Downloads the Ubuntu rootfs, base tools and OpenCode automatically"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val doneCount = install.steps.count { it.state == StepState.DONE }
                        StatusPill("$doneCount/${install.steps.size}", accents.amber)
                    }
                }
            }
        }

        item {
            Column {
                SectionHeader(t("环境", "Environment"), t("容器与工具链", "Container and toolchain"))
                Spacer(Modifier.height(8.dp))
                PocketCard {
                    KeyValueRow(t("系统", "System"), versions["ubuntu"]?.let { "Ubuntu $it" } ?: t("未安装", "Not installed"))
                    KeyValueRow("OpenCode", versions["opencode"] ?: t("未安装", "Not installed"))
                    KeyValueRow("Node.js", versions["node"]?.let { "v${it.trimStart('v')}" } ?: t("未安装（可选）", "Not installed (optional)"))
                    KeyValueRow(
                        t("工作区", "Workspace"),
                        workspace?.let { it.host.absolutePath + if (it.external) "" else t("（内部）", " (internal)") }
                            ?: settings.workspace,
                    )
                    KeyValueRow(t("占用空间", "Storage used"), Format.bytes(diskUsage))
                    KeyValueRow(t("模型", "Model"), settings.defaultModel.ifBlank { t("跟随默认", "Follow default") })
                }
            }
        }

        item {
            Column {
                SectionHeader(t("快捷操作", "Quick actions"))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val updatingOpencode = install.running == dev.pocket.opencode.core.InstallStep.OPENCODE
                    FilledTonalButton(
                        onClick = { scope.launch { engine.installer.updateOpencode() } },
                        enabled = install.installed && install.running == null,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (updatingOpencode) {
                            androidx.compose.material3.CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("更新中…", style = MaterialTheme.typography.labelMedium)
                        } else {
                            Icon(PocketIcons.Download, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("更新 OpenCode", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    FilledTonalButton(onClick = { showDoctor = true }, modifier = Modifier.weight(1f)) {
                        Icon(PocketIcons.Shield, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("一键体检", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = onOpenTerminal, modifier = Modifier.weight(1f)) {
                        Icon(PocketIcons.Terminal, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("终端与日志", style = MaterialTheme.typography.labelMedium)
                    }
                    FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) {
                        Icon(PocketIcons.Sliders, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("设置", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        if (install.installed && !DevicePermissions.hasNotificationPermission(context)) {
            item {
                InlineBanner(
                    icon = Icons.Filled.Info,
                    title = t("开启通知权限", "Enable notifications"),
                    body = t("服务运行状态会显示在通知栏，方便随时停止", "Server status appears in the notification shade so you can stop it anytime"),
                    actionLabel = t("去开启", "Enable"),
                    onAction = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
            }
        }
        if (install.installed && !DevicePermissions.hasAllFilesAccess()) {
            item {
                InlineBanner(
                    icon = PocketIcons.Folder,
                    title = t("允许访问手机文件", "Allow access to phone files"),
                    body = t("授权后容器里的 ${PocketPathsGuestWorkspace} 会直接挂到手机的 ${settings.workspace}", "After granting, ${PocketPathsGuestWorkspace} in the container maps to ${settings.workspace} on your phone"),
                    tone = accents.violet,
                    actionLabel = t("去授权", "Grant"),
                    onAction = { DevicePermissions.requestAllFilesAccess(context) },
                )
            }
        }
        if (install.installed && !DevicePermissions.isIgnoringBatteryOptimizations(context)) {
            item {
                InlineBanner(
                    icon = Icons.Filled.Warning,
                    title = t("建议加入电池白名单", "Add to battery allowlist"),
                    body = t("否则长时间跑任务时系统可能把服务杀掉", "Otherwise the system may kill the server during long tasks"),
                    tone = accents.amber,
                    actionLabel = t("去设置", "Open settings"),
                    onAction = { DevicePermissions.requestIgnoreBatteryOptimizations(context) },
                )
            }
        }

        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader(t("最近日志", "Recent logs"), modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenTerminal) { Text(t("查看全部", "View all")) }
                }
                Spacer(Modifier.height(6.dp))
                if (logs.isEmpty()) {
                    PocketCard {
                        Text(
                            t("还没有日志。安装或启动服务后，这里会实时显示输出。", "No logs yet. Output will appear here after installing or starting the server."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LogConsole(
                        lines = logs.takeLast(8),
                        modifier = Modifier.height(150.dp),
                    )
                }
            }
        }

        item {
            val failed = install.steps.filter { it.state == StepState.FAILED }
            if (failed.isNotEmpty()) {
                InlineBanner(
                    icon = Icons.Filled.Warning,
                    title = t("${failed.first().step.title} 失败", "${failed.first().step.titleEn} failed"),
                    body = failed.first().error ?: t("可以到安装页单独重试这一步", "Retry this step on the Setup tab"),
                    tone = accents.rose,
                    actionLabel = t("去处理", "Fix"),
                    onAction = onOpenSetup,
                )
            } else if (install.installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = accents.emerald)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        t("环境自检通过 · 全部 ${install.steps.size} 项就绪", "Environment check passed · all ${install.steps.size} steps ready"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showDoctor) {
        DoctorDialog(engine) { showDoctor = false }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(t("停止服务？", "Stop the server?")) },
            text = { Text(t("这会中断正在运行的会话与任务，网页版和终端都需要重新连接。", "This interrupts running sessions and tasks. The web UI and terminal must reconnect.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showStopConfirm = false
                        engine.stopServer()
                    },
                ) { Text(t("停止", "Stop")) }
            },
            dismissButton = { TextButton(onClick = { showStopConfirm = false }) { Text(t("取消", "Cancel")) } },
        )
    }
}

private const val PocketPathsGuestWorkspace = "/root/workspace"
