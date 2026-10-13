package dev.pocket.opencode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pocket.opencode.core.Doctor
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.ui.components.copyToClipboard
import dev.pocket.opencode.ui.theme.PocketColors
import kotlinx.coroutines.launch

/**
 * 一键体检：跑 [Doctor] 的检查项并把结果列出来，可以复制整份报告。
 * 检查在打开时自动开始，也可以手动重跑。
 */
@Composable
fun DoctorDialog(engine: PocketEngine, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<Doctor.Check>() }
    var running by remember { mutableStateOf(false) }

    fun start() {
        if (running) return
        running = true
        results.clear()
        scope.launch {
            engine.doctor.run { check -> results.add(check) }
            running = false
        }
    }

    LaunchedEffect(Unit) { start() }

    val failures = results.count { it.status == Doctor.Status.FAIL }
    val warnings = results.count { it.status == Doctor.Status.WARN }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("一键体检")
                Text(
                    when {
                        running -> "正在检查…"
                        failures > 0 -> "$failures 项需要处理"
                        warnings > 0 -> "$warnings 项建议处理"
                        else -> "一切正常"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        failures > 0 -> MaterialTheme.colorScheme.error
                        warnings > 0 -> PocketColors.Amber
                        else -> PocketColors.Emerald
                    },
                )
            }
        },
        text = {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(results, key = { it.title + it.detail }) { check ->
                    DoctorRow(check)
                }
                if (running) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "正在执行下一项检查…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    val report = results.joinToString("\n") { "${it.status}  ${it.title}：${it.detail}" }
                    copyToClipboard(context, report, "体检报告已复制")
                }) { Text("复制报告") }
                TextButton(onClick = { start() }) { Text("重新检查") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DoctorRow(check: Doctor.Check) {
    val (tone, icon) = when (check.status) {
        Doctor.Status.OK -> PocketColors.Emerald to Icons.Filled.Check
        Doctor.Status.WARN -> PocketColors.Amber to Icons.Filled.Warning
        Doctor.Status.FAIL -> PocketColors.Rose to Icons.Filled.Warning
        Doctor.Status.INFO -> PocketColors.Cyan to Icons.Filled.Info
        Doctor.Status.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Filled.Info
    }
    Row(Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(16.dp), tint = tone)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(check.title, style = MaterialTheme.typography.bodyMedium)
            if (check.detail.isNotBlank()) {
                Text(
                    check.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
