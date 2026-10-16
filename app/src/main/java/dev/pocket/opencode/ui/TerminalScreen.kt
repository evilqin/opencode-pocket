package dev.pocket.opencode.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.core.StepState
import dev.pocket.opencode.ui.components.LogConsole
import dev.pocket.opencode.ui.components.copyToClipboard
import dev.pocket.opencode.ui.theme.CodeTextStyle
import dev.pocket.opencode.ui.theme.PocketColors
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 终端页：一个「类 shell」的命令执行器 + 两份日志。
 * 每条命令都在容器里以登录 shell 执行，执行完回传当前目录，体验上接近真终端。
 */
@Composable
fun TerminalScreen(engine: PocketEngine) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val install by engine.installer.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("命令", "安装日志", "运行日志").forEachIndexed { index, title ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(title, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
        when (tab) {
            0 -> CommandRunner(engine)
            1 -> LogPanel(
                engine = engine,
                isInstallLog = true,
                emptyText = if (install.steps.any { it.state != StepState.PENDING }) {
                    "还没有安装日志"
                } else {
                    "开始安装后，这里会实时显示每一步的输出"
                },
            )
            else -> LogPanel(engine = engine, isInstallLog = false, emptyText = "启动服务后，这里会显示 opencode 的输出")
        }
    }
}

private sealed interface TermLine {
    data class Prompt(val cwd: String, val command: String) : TermLine
    data class Output(val text: String) : TermLine
    data class Note(val text: String, val error: Boolean = false) : TermLine
}

private const val MAX_TERMINAL_LINES = 800
private const val MAX_LOG_LINES = 1200
private const val MAX_SAVED_TERMINAL_LINES = 50

/** 把终端行序列化成 JSON 字符串，用于进程重建时恢复；只存最近 50 条防止 Bundle 超限。 */
private fun TermLine.serialize(): String = when (this) {
    is TermLine.Prompt -> """{"t":"P","c":${jsonQuote(cwd)},"d":${jsonQuote(command)}}"""
    is TermLine.Output -> """{"t":"O","d":${jsonQuote(text)}}"""
    is TermLine.Note -> """{"t":"N","d":${jsonQuote(text)},"e":$error}"""
}

private fun String.toTermLine(): TermLine? = runCatching {
    val json = JSONObject(this)
    when (json.getString("t")) {
        "P" -> TermLine.Prompt(json.getString("c"), json.getString("d"))
        "O" -> TermLine.Output(json.getString("d"))
        "N" -> TermLine.Note(json.getString("d"), json.getBoolean("e"))
        else -> null
    }
}.getOrNull()

private fun jsonQuote(s: String): String {
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
    }
    return sb.append('"').toString()
}

private val TermLineListSaver = listSaver<SnapshotStateList<TermLine>, String>(
    save = { it.takeLast(MAX_SAVED_TERMINAL_LINES).map { line -> line.serialize() } },
    restore = { saved ->
        mutableStateListOf<TermLine>().apply {
            addAll(saved.mapNotNull { it.toTermLine() })
        }
    },
)

@Composable
private fun CommandRunner(engine: PocketEngine) {
    val scope = rememberCoroutineScope()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val lines = rememberSaveable(saver = TermLineListSaver) { mutableStateListOf<TermLine>() }
    var input by rememberSaveable { mutableStateOf("") }
    var cwd by rememberSaveable { mutableStateOf("/root/workspace") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }

    fun run(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || busy) return
        if (trimmed == "clear") {
            lines.clear()
            return
        }
        lines.add(TermLine.Prompt(cwd, trimmed))
        input = ""
        busy = true
        scope.launch {
            try {
                // cwd 用单引号包住并转义（目录名里有 ' 时也不会串命令）；
                // PWD 标记带随机串，避免命令输出伪造标记来欺骗界面
                val quotedCwd = cwd.replace("'", "'\\''")
                val marker = "__POCKET_PWD_${java.util.UUID.randomUUID().toString().take(8)}__"
                val wrapped = "cd -- '$quotedCwd' 2>/dev/null || cd /root; $trimmed\nprintf '\\n$marker%s\\n' \"\$PWD\""
                engine.shell.run(wrapped, settings, timeoutMs = 600_000) { line ->
                    if (line.startsWith(marker)) {
                        cwd = line.removePrefix(marker).trim()
                    } else if (line.isNotEmpty()) {
                        lines.add(TermLine.Output(line))
                        // 长输出命令（cat/find 之类）运行期间就要裁剪，否则内存线性增长
                        while (lines.size > MAX_TERMINAL_LINES) lines.removeAt(0)
                    }
                }
            } catch (error: Exception) {
                lines.add(TermLine.Note("执行失败：${error.message}", error = true))
            } finally {
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF060B14))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            if (engine.paths.rootfsLooksInstalled().not()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "容器还没安装，先到「安装」页一键安装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    items(lines.size) { index ->
                        when (val line = lines[index]) {
                            is TermLine.Prompt -> Text(
                                "root@android:${line.cwd}\$ ${line.command}",
                                style = CodeTextStyle,
                                color = PocketColors.Cyan,
                            )
                            is TermLine.Output -> Text(line.text, style = CodeTextStyle, color = Color(0xFFD7E3F4))
                            is TermLine.Note -> Text(
                                line.text,
                                style = CodeTextStyle,
                                color = if (line.error) PocketColors.Rose else PocketColors.Amber,
                            )
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "ls -al",
                "pwd",
                "opencode --version",
                "node -v",
                "git status",
                "df -h /",
                "free -m",
                "uname -a",
            ).forEach { command ->
                AssistChip(
                    onClick = { run(command) },
                    label = { Text(command, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("在容器里执行命令（$cwd）", style = MaterialTheme.typography.bodySmall) },
                textStyle = CodeTextStyle,
                maxLines = 4,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    capitalization = KeyboardCapitalization.None,
                ),
                keyboardActions = KeyboardActions(onSend = { run(input) }),
            )
            IconButton(
                onClick = { run(input) },
                enabled = !busy && input.isNotBlank(),
            ) {
                if (busy) {
                    Icon(Icons.Filled.Add, "执行中", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Icon(Icons.Filled.Send, "执行")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LogPanel(engine: PocketEngine, isInstallLog: Boolean, emptyText: String) {
    val context = LocalContext.current
    val lines = remember { mutableStateListOf<String>() }
    // 历史来自各层自己的环形缓冲（先灌一遍），之后只消费新日志；
    // 这样「清屏」不会因为 SharedFlow 的 replay 被重新灌回来。
    LaunchedEffect(isInstallLog) {
        val history = if (isInstallLog) engine.installer.recentLogs.value else engine.server.recentLogs.value
        lines.clear()
        lines.addAll(history.takeLast(MAX_LOG_LINES))
        val flow = if (isInstallLog) engine.installer.logs else engine.server.logs
        flow.collect { line ->
            lines.add(line)
            while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isInstallLog) engine.paths.installLog.absolutePath else engine.paths.serverLog.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = { copyToClipboard(context, lines.joinToString("\n"), "日志已复制") }) {
                Text("复制")
            }
            TextButton(onClick = {
                lines.clear()
                // 同时清掉源缓冲，否则切走再回来历史又回来了
                if (isInstallLog) engine.installer.clearLogs() else engine.server.clearRecentLogs()
            }) { Text("清屏") }
        }
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LogConsole(
                lines = lines,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}
