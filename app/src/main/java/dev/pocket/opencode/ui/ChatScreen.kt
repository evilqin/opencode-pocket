package dev.pocket.opencode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.core.ServerPhase
import dev.pocket.opencode.data.AgentInfo
import dev.pocket.opencode.data.ChatMessage
import dev.pocket.opencode.data.Parsers
import dev.pocket.opencode.data.Part
import dev.pocket.opencode.data.PermissionRequest
import dev.pocket.opencode.data.ProviderInfo
import dev.pocket.opencode.data.SessionInfo
import dev.pocket.opencode.ui.components.CodeBlock
import dev.pocket.opencode.ui.components.EmptyState
import dev.pocket.opencode.ui.components.MarkdownText
import dev.pocket.opencode.ui.components.PocketCard
import dev.pocket.opencode.ui.components.SectionHeader
import dev.pocket.opencode.ui.components.StatusPill
import dev.pocket.opencode.ui.components.copyToClipboard
import dev.pocket.opencode.ui.theme.CodeTextStyle
import dev.pocket.opencode.ui.theme.PocketColors
import dev.pocket.opencode.util.Format
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ChatScreen(engine: PocketEngine, onOpenSettings: () -> Unit) {
    val server by engine.server.status.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SessionInfo?>(null) }

    when {
        server.phase != ServerPhase.RUNNING -> EmptyState(
            icon = PocketIcons.Forum,
            title = "服务未在运行",
            body = server.error ?: "对话页直连手机上的 opencode 服务；先在首页把服务启动起来。",
            action = { Button(onClick = { engine.startServer() }) { Text("启动服务") } },
        )

        selected == null -> SessionList(
            engine = engine,
            onOpen = { selected = it },
            onOpenSettings = onOpenSettings,
        )

        else -> SessionDetail(
            engine = engine,
            session = selected!!,
            onBack = { selected = null },
        )
    }
}

// ------------------------------------------------------------------ 会话列表

@Composable
private fun SessionList(
    engine: PocketEngine,
    onOpen: (SessionInfo) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val settings by engine.settings.settings.collectAsStateWithLifecycle()
    val sessions = remember { mutableStateListOf<SessionInfo>() }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SessionInfo?>(null) }
    var refreshToken by remember { mutableStateOf(0) }

    LaunchedEffect(refreshToken) {
        loading = true
        error = null
        try {
            val loaded = engine.api.sessions()
            sessions.clear()
            sessions.addAll(loaded.distinctBy { it.id })
        } catch (exception: Exception) {
            error = exception.message
        }
        loading = false
    }

    val hasKey = settings.providerKeys.values.any { it.isNotBlank() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!hasKey) {
                item {
                    PocketCard(onClick = onOpenSettings) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = PocketColors.Amber)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("还没配置模型密钥", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "去设置里填一个 provider 的 API Key（DeepSeek / Anthropic / OpenAI…）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("去设置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("会话", "${sessions.size} 个", Modifier.weight(1f))
                    IconButton(onClick = { refreshToken++ }) {
                        Icon(Icons.Filled.Refresh, "刷新")
                    }
                }
            }

            when {
                loading && sessions.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                }

                error != null && sessions.isEmpty() -> item {
                    PocketCard {
                        Text("加载失败", style = MaterialTheme.typography.titleSmall)
                        Text(
                            error.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { refreshToken++ }) { Text("重试") }
                    }
                }

                sessions.isEmpty() -> item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有会话", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "点右下角的按钮新建一个，或直接说出你想做的事",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        onClick = { onOpen(session) },
                        onLongClick = { pendingDelete = session },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = {
                scope.launch {
                    try {
                        val created = engine.api.createSession()
                        sessions.removeAll { it.id == created.id }
                        sessions.add(0, created)
                        onOpen(created)
                    } catch (exception: Exception) {
                        error = exception.message
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(Icons.Filled.Add, "新建会话")
        }
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除会话？") },
            text = { Text("「${session.title}」及其消息记录会被删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { engine.api.deleteSession(session.id) }
                        sessions.removeAll { it.id == session.id }
                        pendingDelete = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRow(session: SessionInfo, onClick: () -> Unit, onLongClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(Modifier),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(PocketIcons.Forum, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(Format.relativeTime(session.updated))
                        if (session.files > 0) append(" · ${session.files} 个文件")
                        if (session.additions > 0 || session.deletions > 0) {
                            append(" · +${session.additions} -${session.deletions}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onLongClick) {
                Icon(
                    Icons.Filled.Delete,
                    "删除",
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 单个会话

private class ChatState(val engine: PocketEngine, val session: SessionInfo) {
    val messages = mutableStateListOf<ChatMessage>()
    val permissions = mutableStateListOf<PermissionRequest>()
    var loading by mutableStateOf(true)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var title by mutableStateOf(session.title)
    var providers by mutableStateOf<List<ProviderInfo>>(emptyList())
    var agents by mutableStateOf<List<AgentInfo>>(emptyList())
    var selectedModel by mutableStateOf<Pair<String, String>?>(null)
    var selectedAgent by mutableStateOf<String?>(null)

    /** 事件流重连过，需要补拉一次历史。 */
    var pendingResync by mutableStateOf(false)

    /** 只重拉消息（不动 loading 状态，供 resync 使用）。 */
    suspend fun reloadMessages() {
        runCatching { engine.api.messages(session.id) }.onSuccess { history ->
            val merged = history.sortedWith(compareBy({ it.info.created }, { it.info.id }))
            messages.clear()
            messages.addAll(merged)
        }
    }

    suspend fun load() {
        loading = true
        error = null
        try {
            val history = engine.api.messages(session.id)
            messages.clear()
            messages.addAll(history.sortedWith(compareBy({ it.info.created }, { it.info.id })))
            runCatching {
                val (list, defaults) = engine.api.providers()
                providers = list.filter { it.models.isNotEmpty() }
                val models = mutableListOf<String>()
                defaults.forEach { (provider, model) -> models.add("$provider/$model") }
                val preferred = models.firstOrNull { it.startsWith("deepseek/") } ?: models.firstOrNull()
                preferred?.split('/')?.let { (provider, model) ->
                    if (provider.isNotBlank() && model.isNotBlank()) selectedModel = provider to model
                }
            }
            runCatching { agents = engine.api.agents().filter { it.isPrimary } }
        } catch (exception: Exception) {
            error = exception.message
        }
        loading = false
    }

    fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        if (type == "resync") {
            // 事件流重连过：补拉一次历史，避免漏事件
            pendingResync = true
            return
        }
        val props = event.optJSONObject("properties") ?: return
        when (type) {
            "message.updated" -> {
                val infoJson = props.optJSONObject("info") ?: return
                val info = Parsers.message(infoJson)
                if (info.sessionID != session.id) return
                val index = messages.indexOfFirst { it.info.id == info.id }
                if (index >= 0) {
                    val existing = messages[index]
                    messages[index] = existing.copy(info = info)
                } else {
                    messages.add(ChatMessage(info, emptyList()))
                    sortMessages()
                    // 只有“新出现的用户消息”才意味着刚提交了一轮请求；
                    // 历史回灌（事件流重连等）不该把界面标成“正在思考”
                    if (info.isUser) busy = true
                }
                if (!info.isUser && info.completed != null) busy = false
            }

            "message.part.updated" -> {
                val partJson = props.optJSONObject("part") ?: return
                // 某些版本的 Part 不带 sessionID：为空时也接受（消息 id 会兜底）
                val partSession = partJson.optString("sessionID")
                if (partSession.isNotBlank() && partSession != session.id) return
                val part = Parsers.part(partJson) ?: return
                upsertPart(part)
            }

            "message.removed" -> {
                val id = props.optString("messageID")
                messages.removeAll { it.info.id == id }
            }

            "message.part.removed" -> {
                val messageID = props.optString("messageID")
                val partID = props.optString("partID")
                val index = messages.indexOfFirst { it.info.id == messageID }
                if (index >= 0) {
                    messages[index] = messages[index].copy(parts = messages[index].parts.filter { it.id != partID })
                }
            }

            "session.idle" -> if (props.optString("sessionID") == session.id) busy = false

            "session.status" -> {
                val status = props.optJSONObject("status")?.optString("type") ?: return
                if (props.optString("sessionID") == session.id) {
                    busy = status == "busy" || status == "retry"
                }
            }

            "session.updated" -> {
                val info = props.optJSONObject("info") ?: return
                if (info.optString("id") == session.id) title = info.optString("title").ifBlank { title }
            }

            "session.error" -> {
                if (props.optString("sessionID") == session.id) {
                    error = Parsers.formatError(props.optJSONObject("error"))
                        ?: props.optJSONObject("error")?.optString("name")
                }
            }

            "permission.updated" -> {
                PermissionRequest.from(props)?.takeIf { it.sessionID == session.id }?.let { request ->
                    if (permissions.none { it.id == request.id }) permissions.add(request)
                }
            }

            "permission.replied" -> {
                permissions.removeAll { it.id == props.optString("permissionID") }
            }
        }
    }

    private fun sortMessages() {
        val sorted = messages.sortedWith(compareBy({ it.info.created }, { it.info.id }))
        messages.clear()
        messages.addAll(sorted)
    }

    private fun upsertPart(part: Part) {
        val index = messages.indexOfFirst { it.info.id == part.messageID }
        if (index < 0) {
            // 消息还没到，先把 parts 存进一个占位（等 message.updated 补上 info）
            val placeholder = ChatMessage(
                info = Parsers.message(JSONObject().put("id", part.messageID).put("sessionID", session.id).put("role", "assistant")),
                parts = listOf(part),
            )
            messages.add(placeholder)
            return
        }
        val message = messages[index]
        val parts = message.parts.toMutableList()
        val partIndex = parts.indexOfFirst { it.id == part.id }
        if (partIndex >= 0) parts[partIndex] = part else parts.add(part)
        messages[index] = message.copy(parts = parts)
    }

    suspend fun send(text: String) {
        if (text.isBlank()) {
            busy = false
            return
        }
        error = null
        try {
            engine.api.promptAsync(session.id, text, selectedModel, selectedAgent)
        } catch (exception: Exception) {
            error = exception.message
            busy = false
        }
    }

    /**
     * 同步抢锁：返回 false 表示上一条还在跑，调用方**不要**清空输入框。
     * （放进协程里判断会有竞态：连点两下会静默吞掉第二段草稿。）
     */
    fun tryStartSend(): Boolean {
        if (busy) return false
        busy = true
        return true
    }

    suspend fun abort() {
        runCatching { engine.api.abort(session.id) }
        busy = false
    }

    suspend fun replyPermission(request: PermissionRequest, response: String) {
        val removed = permissions.filter { it.id == request.id }
        permissions.removeAll { it.id == request.id }
        val result = runCatching { engine.api.replyPermission(session.id, request.id, response) }
        if (result.isFailure) {
            // 失败就把卡片放回去，别让用户以为已经答复了
            if (removed.isNotEmpty() && permissions.none { it.id == request.id }) {
                permissions.addAll(removed)
            }
            error = "授权回复失败：${result.exceptionOrNull()?.message ?: "请重试"}"
        }
    }

    suspend fun rename(newTitle: String) {
        runCatching {
            val updated = engine.api.renameSession(session.id, newTitle)
            title = updated.title
        }
    }
}

@Composable
private fun SessionDetail(engine: PocketEngine, session: SessionInfo, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chat = remember(session.id) { ChatState(engine, session) }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var showModelPicker by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }

    LaunchedEffect(session.id) { chat.load() }

    LaunchedEffect(session.id) {
        engine.api.events().collect { event -> chat.handleEvent(event) }
    }

    // 事件流重连过：补拉一次历史
    LaunchedEffect(chat.pendingResync) {
        if (chat.pendingResync) {
            chat.pendingResync = false
            chat.reloadMessages()
        }
    }

    LaunchedEffect(chat.messages.size, chat.permissions.size, chat.busy) {
        val total = chat.messages.size + chat.permissions.size + if (chat.busy) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { showRename = true },
                ) {
                    Text(
                        chat.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (chat.busy) "正在思考…" else "opencode · ${Format.clockTime(session.created)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { scope.launch { chat.load() } }) { Icon(Icons.Filled.Refresh, "重新加载") }
            }
        }

        Box(Modifier.weight(1f)) {
            if (chat.loading && chat.messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(chat.messages, key = { it.info.id }) { message ->
                        MessageBubble(message)
                    }
                    items(chat.permissions, key = { it.id }) { request ->
                        PermissionCard(
                            request = request,
                            onReply = { response -> scope.launch { chat.replyPermission(request, response) } },
                        )
                    }
                    if (chat.busy) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "正在生成回复…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        chat.error?.let { message ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { chat.error = null }) { Text("知道了") }
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp).imePadding()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val modelLabel = chat.selectedModel?.let { (provider, model) ->
                        model.substringAfterLast('/').ifBlank { model } + " · " + provider
                    } ?: "默认模型"
                    AssistChip(
                        onClick = { showModelPicker = true },
                        label = { Text(modelLabel, style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(PocketIcons.Chip, null, Modifier.size(14.dp)) },
                    )
                    val agents = chat.agents
                    if (agents.isNotEmpty()) {
                        agents.take(4).forEach { agent ->
                            AssistChip(
                                onClick = {
                                    chat.selectedAgent =
                                        if (chat.selectedAgent == agent.name) null else agent.name
                                },
                                label = {
                                    Text(
                                        agent.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (chat.selectedAgent == agent.name) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                },
                            )
                        }
                    }
                    AssistChip(
                        onClick = { copyToClipboard(context, chat.messages.joinToString("\n\n") { m -> m.info.role + ": " + m.parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text } }, "对话已复制") },
                        label = { Text("复制对话", style = MaterialTheme.typography.labelSmall) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("让 opencode 做点什么…", style = MaterialTheme.typography.bodySmall) },
                        maxLines = 6,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Send,
                            capitalization = KeyboardCapitalization.Sentences,
                        ),
                        keyboardActions = KeyboardActions(onSend = {
                            if (chat.tryStartSend()) {
                                val text = input
                                input = ""
                                scope.launch { chat.send(text) }
                            }
                        }),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (chat.busy) {
                        FilledTonalButton(onClick = { scope.launch { chat.abort() } }) {
                            Icon(PocketIcons.Stop, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("停止")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (chat.tryStartSend()) {
                                    val text = input
                                    input = ""
                                    scope.launch { chat.send(text) }
                                }
                            },
                            enabled = input.isNotBlank(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "发送", Modifier.size(17.dp))
                        }
                    }
                }
            }
        }
    }

    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("选择模型") },
            text = {
                LazyColumn(Modifier.height(400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item {
                        TextButton(onClick = {
                            chat.selectedModel = null
                            showModelPicker = false
                        }) { Text("跟随 opencode 默认") }
                    }
                    chat.providers.forEach { provider ->
                        item {
                            Text(
                                provider.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                            )
                        }
                        items(provider.models.size) { index ->
                            val model = provider.models[index]
                            Text(
                                model.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        chat.selectedModel = provider.id to model.id
                                        showModelPicker = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelPicker = false }) { Text("关闭") } },
        )
    }

    if (showRename) {
        var text by remember { mutableStateOf(chat.title) }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRename = false
                    scope.launch { chat.rename(text) }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("取消") } },
        )
    }
}

// ------------------------------------------------------------------ 消息渲染

@Composable
private fun MessageBubble(message: ChatMessage) {
    val context = LocalContext.current
    if (message.info.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                modifier = Modifier.fillMaxWidth(0.88f),
            ) {
                Text(
                    message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                buildString {
                    append(message.info.modelID.ifBlank { "opencode" })
                    if (message.info.providerID.isNotBlank()) append(" · ${message.info.providerID}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            message.info.error?.let {
                StatusPill("出错", PocketColors.Rose)
            }
        }
        Spacer(Modifier.height(8.dp))

        message.info.error?.let { error ->
            PocketCard {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            message.parts.forEach { part ->
                when (part) {
                    is Part.Text -> if (part.text.isNotBlank() && !part.synthetic) {
                        MarkdownText(part.text)
                    }

                    is Part.Reasoning -> if (part.text.isNotBlank()) {
                        ReasoningCard(part.text)
                    }

                    is Part.ToolCall -> ToolCallCard(part, context)

                    is Part.StepFinish -> {
                        val info = buildString {
                            if (part.tokens.total > 0) append("${Format.tokens(part.tokens.total)} tokens")
                            if (part.cost > 0) append("  ·  ${Format.cost(part.cost)}")
                        }
                        if (info.isNotBlank()) {
                            Text(
                                info,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is Part.AgentRef -> StatusPill("子代理 ${part.name}", PocketColors.Violet)

                    is Part.FileRef -> Text(
                        "附件：${part.filename}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ReasoningCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    PocketIcons.Chip,
                    null,
                    Modifier.size(15.dp),
                    tint = PocketColors.Violet,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "思考过程",
                    style = MaterialTheme.typography.labelMedium,
                    color = PocketColors.Violet,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text,
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolCallCard(part: Part.ToolCall, context: android.content.Context) {
    var expanded by remember { mutableStateOf(false) }
    val (tone, label) = when (part.status) {
        "completed" -> PocketColors.Emerald to "完成"
        "running" -> PocketColors.Amber to "执行中"
        "error" -> PocketColors.Rose to "失败"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to "准备"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded },
            ) {
                Icon(PocketIcons.Code, null, Modifier.size(16.dp), tint = tone)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        part.title.ifBlank { part.tool },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        part.tool,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(label, tone)
            }

            if (part.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    part.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (part.input.isNotBlank() && part.input != "{}") {
                        CodeBlock("输入", part.input)
                    }
                    if (part.output.isNotBlank()) {
                        CodeBlock("输出", part.output.take(6000))
                    }
                    TextButton(onClick = {
                        copyToClipboard(context, part.output.ifBlank { part.input }, "已复制")
                    }) { Text("复制内容") }
                }
            }

            if (!expanded && part.output.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    part.output.take(220),
                    style = CodeTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(request: PermissionRequest, onReply: (String) -> Unit) {
    Surface(
        color = PocketColors.Amber.copy(alpha = 0.12f),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PocketColors.Amber.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(PocketIcons.Shield, null, Modifier.size(18.dp), tint = PocketColors.Amber)
                Spacer(Modifier.width(10.dp))
                Text("需要你确认", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(request.type.ifBlank { "permission" }, PocketColors.Amber)
            }
            Spacer(Modifier.height(8.dp))
            Text(request.title, style = MaterialTheme.typography.bodyMedium)
            if (request.pattern.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(request.pattern, style = CodeTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onReply("once") }) { Text("允许一次") }
                FilledTonalButton(onClick = { onReply("always") }) { Text("始终允许") }
                OutlinedButton(onClick = { onReply("reject") }) { Text("拒绝") }
            }
        }
    }
}
