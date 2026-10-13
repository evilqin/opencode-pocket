package dev.pocket.opencode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.InstallStep
import dev.pocket.opencode.core.Mirrors
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.core.Repos
import dev.pocket.opencode.core.StepState
import dev.pocket.opencode.ui.components.HeroCard
import dev.pocket.opencode.ui.components.InlineBanner
import dev.pocket.opencode.ui.components.LabeledProgress
import dev.pocket.opencode.ui.components.LogConsole
import dev.pocket.opencode.ui.components.PocketCard
import dev.pocket.opencode.ui.components.SectionHeader
import dev.pocket.opencode.ui.components.StatusPill
import dev.pocket.opencode.ui.theme.PocketColors
import dev.pocket.opencode.util.Format
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(engine: PocketEngine, onDone: () -> Unit) {
    val install by engine.installer.state.collectAsStateWithLifecycle()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val logs = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        engine.installer.logs.collect { line ->
            logs.add(line)
            if (logs.size > 600) logs.removeAt(0)
        }
    }

    var showLogs by remember { mutableStateOf(false) }
    var speedResults by remember { mutableStateOf<Map<String, Repos.Speed>>(emptyMap()) }
    var testing by remember { mutableStateOf(false) }

    val doneCount = install.steps.count { it.state == StepState.DONE }
    val totalCount = install.steps.size
    val running = install.running != null

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HeroCard(accent = if (install.installed) PocketColors.Emerald else PocketColors.Cyan) {
                Text("环境安装", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (install.installed) "全部就绪，可以开始使用了"
                    else "四步装好一台口袋里的 Linux：rootfs → 基础工具 → Node（可选）→ OpenCode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                if (running) {
                    LabeledProgress(
                        label = install.running?.title ?: "",
                        fraction = install.steps.firstOrNull { it.step == install.running }?.progress ?: 0f,
                        detail = install.message,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = { engine.installer.cancel() }) { Text("取消") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { scope.launch { engine.installer.installAll() } },
                            enabled = doneCount < totalCount,
                        ) {
                            Icon(PocketIcons.Bolt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (doneCount == 0) "一键安装" else "继续安装（$doneCount/$totalCount）")
                        }
                        if (install.installed) {
                            FilledTonalButton(onClick = onDone) { Text("开始使用") }
                        }
                        if (doneCount > 0) {
                            TextButton(onClick = { scope.launch { engine.installer.refresh() } }) {
                                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("重新检测")
                            }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("镜像源", "国内直连，测速用真实文件", Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            testing = true
                            scope.launch {
                                val ubuntu = engine.repos.speedTestUbuntu(Mirrors.ubuntu)
                                val node = engine.repos.speedTestNode(Mirrors.node)
                                val npm = engine.repos.speedTestNpm(Mirrors.npm)
                                // 用「类型:id」做 key：同一镜像名会同时出现在多个分组里（例如清华同时提供 ubuntu/apt/node/npm）
                                speedResults = buildMap {
                                    ubuntu.forEach { put("ubuntu:${it.mirrorId}", it) }
                                    node.forEach { put("node:${it.mirrorId}", it) }
                                    npm.forEach { put("npm:${it.mirrorId}", it) }
                                }
                                testing = false
                            }
                        },
                        enabled = !testing,
                    ) {
                        if (testing) {
                            Text("测速中…")
                        } else {
                            Icon(PocketIcons.Gauge, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("全线测速")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                PocketCard {
                    MirrorGroup(
                        title = "Ubuntu rootfs",
                        kind = "ubuntu",
                        mirrors = Mirrors.ubuntu,
                        selected = settings.ubuntuMirror,
                        speeds = speedResults,
                        testing = testing,
                        onSelect = { id -> scope.launch { engine.settings.update { it.copy(ubuntuMirror = id) } } },
                    )
                    Spacer(Modifier.height(14.dp))
                    MirrorGroup(
                        title = "apt 软件源",
                        kind = "apt",
                        mirrors = Mirrors.apt,
                        selected = settings.aptMirror,
                        speeds = speedResults,
                        testing = testing,
                        onSelect = { id -> scope.launch { engine.settings.update { it.copy(aptMirror = id) } } },
                    )
                    Spacer(Modifier.height(14.dp))
                    MirrorGroup(
                        title = "Node.js",
                        kind = "node",
                        mirrors = Mirrors.node,
                        selected = settings.nodeMirror,
                        speeds = speedResults,
                        testing = testing,
                        onSelect = { id -> scope.launch { engine.settings.update { it.copy(nodeMirror = id) } } },
                    )
                    Spacer(Modifier.height(14.dp))
                    MirrorGroup(
                        title = "npm registry（OpenCode 下载）",
                        kind = "npm",
                        mirrors = Mirrors.npm,
                        selected = settings.npmMirror,
                        speeds = speedResults,
                        testing = testing,
                        onSelect = { id -> scope.launch { engine.settings.update { it.copy(npmMirror = id) } } },
                    )
                }
            }
        }

        item {
            SectionHeader("安装步骤", "每一步都可以单独重试")
        }

        items(install.steps, key = { it.step.id }) { snapshot ->
            StepCard(
                snapshot = snapshot,
                running = running,
                onStart = { scope.launch { engine.installer.reinstall(snapshot.step) } },
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showLogs = !showLogs }) {
                    Icon(
                        PocketIcons.Terminal,
                        null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (showLogs) "收起安装日志" else "查看安装日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (showLogs) {
                Spacer(Modifier.height(8.dp))
                LogConsole(lines = logs, modifier = Modifier.height(240.dp))
            }
        }

        item {
            InlineBanner(
                icon = Icons.Filled.Warning,
                title = "安装期间请保持 App 在前台",
                body = "Android 可能会在后台清理下载进程；下载支持断点续传，中断后重新点一次即可继续。",
                tone = PocketColors.Amber,
            )
        }

        if (!install.installed && !engine.installer.hasEnoughSpace()) {
            item {
                InlineBanner(
                    icon = Icons.Filled.Warning,
                    title = "存储空间可能不够",
                    body = "完整环境（Ubuntu + OpenCode）大约需要 1.5 GB，当前可用 ${Format.bytes(engine.installer.freeSpaceBytes())}",
                    tone = PocketColors.Rose,
                )
            }
        }
    }
}

@Composable
private fun MirrorGroup(
    title: String,
    kind: String,
    mirrors: List<Mirrors.Mirror>,
    selected: String,
    speeds: Map<String, Repos.Speed>,
    testing: Boolean,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mirrors.forEach { mirror ->
                val speed = speeds["$kind:${mirror.id}"]
                val label = buildString {
                    append(mirror.name)
                    when {
                        speed == null -> {}
                        speed.bytesPerSecond != null -> append(" ${Format.speed(speed.bytesPerSecond)}")
                        else -> append(" ✗")
                    }
                }
                FilterChip(
                    selected = selected == mirror.id,
                    onClick = { onSelect(mirror.id) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun StepCard(
    snapshot: dev.pocket.opencode.core.StepSnapshot,
    running: Boolean,
    onStart: () -> Unit,
) {
    val (tone, label) = when (snapshot.state) {
        StepState.DONE -> PocketColors.Emerald to "已完成"
        StepState.RUNNING -> PocketColors.Amber to "进行中"
        StepState.FAILED -> PocketColors.Rose to "失败"
        StepState.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant to "已跳过"
        StepState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant to "待安装"
    }
    PocketCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when (snapshot.state) {
                    StepState.DONE -> Icons.Filled.Check
                    StepState.FAILED -> Icons.Filled.Warning
                    StepState.RUNNING -> PocketIcons.Bolt
                    else -> PocketIcons.Download
                },
                null,
                Modifier.size(20.dp),
                tint = tone,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(snapshot.step.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    snapshot.detail.ifBlank { snapshot.step.subtitle },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusPill(label, tone)
        }

        if (snapshot.state == StepState.RUNNING) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { snapshot.progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape),
            )
        }

        if (snapshot.error != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                snapshot.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
        }

        if (snapshot.state != StepState.RUNNING && !running) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (snapshot.state != StepState.DONE) {
                    FilledTonalButton(onClick = onStart) {
                        Text(if (snapshot.state == StepState.FAILED) "重试" else "开始这一步")
                    }
                } else {
                    OutlinedButton(onClick = onStart) { Text("重装") }
                }
            }
        }
    }
}
