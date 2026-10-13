package dev.pocket.opencode.data

import org.json.JSONArray
import org.json.JSONObject

/** 会话（opencode 里的 session）。 */
data class SessionInfo(
    val id: String,
    val title: String,
    val directory: String,
    val created: Long,
    val updated: Long,
    val parentID: String?,
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: Int = 0,
)

data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    val total: Long get() = input + output + reasoning
}

data class MessageInfo(
    val id: String,
    val sessionID: String,
    val role: String,
    val created: Long,
    val completed: Long?,
    val error: String?,
    val modelID: String,
    val providerID: String,
    val agent: String,
    val cost: Double,
    val tokens: TokenUsage,
) {
    val isUser: Boolean get() = role == "user"
    val pending: Boolean get() = !isUser && completed == null && error == null
}

sealed interface Part {
    val id: String
    val messageID: String

    data class Text(override val id: String, override val messageID: String, val text: String, val synthetic: Boolean) : Part
    data class Reasoning(override val id: String, override val messageID: String, val text: String) : Part
    data class ToolCall(
        override val id: String,
        override val messageID: String,
        val tool: String,
        val status: String,
        val title: String,
        val input: String,
        val output: String,
        val error: String?,
    ) : Part
    data class FileRef(
        override val id: String,
        override val messageID: String,
        val filename: String,
        val mime: String,
        val url: String,
    ) : Part
    data class StepFinish(
        override val id: String,
        override val messageID: String,
        val cost: Double,
        val tokens: TokenUsage,
    ) : Part
    data class AgentRef(override val id: String, override val messageID: String, val name: String) : Part
    data class Unknown(override val id: String, override val messageID: String, val type: String) : Part
}

data class ChatMessage(val info: MessageInfo, val parts: List<Part>)

data class PermissionRequest(
    val id: String,
    val sessionID: String,
    val title: String,
    val type: String,
    val pattern: String,
) {
    companion object {
        fun from(json: JSONObject): PermissionRequest? {
            val id = json.optString("id").ifBlank { return null }
            return PermissionRequest(
                id = id,
                sessionID = json.optString("sessionID"),
                title = json.optString("title").ifBlank { json.optString("type", "需要授权") },
                type = json.optString("type"),
                pattern = when (val pattern = json.opt("pattern")) {
                    is String -> pattern
                    is JSONArray -> (0 until pattern.length()).joinToString(", ") { pattern.optString(it) }
                    else -> ""
                },
            )
        }
    }
}

data class ModelInfo(val id: String, val name: String, val reasoning: Boolean = false)

data class ProviderInfo(
    val id: String,
    val name: String,
    val models: List<ModelInfo>,
)

data class AgentInfo(val name: String, val description: String, val mode: String, val isPrimary: Boolean)

/** JSON -> 模型（全部字段容错，服务端版本变化不会崩）。 */
object Parsers {

    fun session(json: JSONObject): SessionInfo = SessionInfo(
        id = json.optString("id"),
        title = json.optString("title").ifBlank { "未命名会话" },
        directory = json.optString("directory"),
        created = json.optJSONObject("time")?.optLong("created") ?: 0L,
        updated = json.optJSONObject("time")?.optLong("updated") ?: 0L,
        parentID = json.optString("parentID").ifBlank { null },
        additions = json.optJSONObject("summary")?.optInt("additions") ?: 0,
        deletions = json.optJSONObject("summary")?.optInt("deletions") ?: 0,
        files = json.optJSONObject("summary")?.optInt("files") ?: 0,
    )

    fun message(json: JSONObject): MessageInfo {
        val time = json.optJSONObject("time")
        return MessageInfo(
            id = json.optString("id"),
            sessionID = json.optString("sessionID"),
            role = json.optString("role", "assistant"),
            created = time?.optLong("created") ?: 0L,
            completed = time?.let { if (it.has("completed")) it.optLong("completed") else null },
            error = formatError(json.optJSONObject("error")),
            modelID = json.optString("modelID"),
            providerID = json.optString("providerID"),
            agent = json.optString("agent").ifBlank { json.optString("mode") },
            cost = json.optDouble("cost", 0.0),
            tokens = tokens(json.optJSONObject("tokens")),
        )
    }

    private fun tokens(json: JSONObject?): TokenUsage {
        if (json == null) return TokenUsage()
        val cache = json.optJSONObject("cache")
        return TokenUsage(
            input = json.optLong("input"),
            output = json.optLong("output"),
            reasoning = json.optLong("reasoning"),
            cacheRead = cache?.optLong("read") ?: 0,
            cacheWrite = cache?.optLong("write") ?: 0,
        )
    }

    fun formatError(json: JSONObject?): String? {
        if (json == null) return null
        val name = json.optString("name")
        val data = json.optJSONObject("data")
        val message = data?.optString("message").orEmpty()
        return when (name) {
            "ProviderAuthError" -> "模型认证失败（${data?.optString("providerID").orEmpty()}）：请到设置里检查 API Key"
            "MessageAbortedError" -> "已中断"
            "MessageOutputLengthError" -> "输出长度超限"
            "APIError" -> "接口错误${data?.optInt("statusCode")?.takeIf { it > 0 }?.let { "（HTTP $it）" } ?: ""}：$message"
            "" -> null
            else -> if (message.isNotBlank()) "$name：$message" else name
        }
    }

    fun part(json: JSONObject): Part? {
        val id = json.optString("id")
        val messageID = json.optString("messageID")
        if (id.isBlank()) return null
        return when (val type = json.optString("type")) {
            "text" -> Part.Text(id, messageID, json.optString("text"), json.optBoolean("synthetic", false))
            "reasoning" -> Part.Reasoning(id, messageID, json.optString("text"))
            "tool" -> {
                val state = json.optJSONObject("state")
                Part.ToolCall(
                    id = id,
                    messageID = messageID,
                    tool = json.optString("tool"),
                    status = state?.optString("status") ?: "pending",
                    title = state?.optString("title").orEmpty(),
                    input = state?.optJSONObject("input")?.toString(2).orEmpty(),
                    output = state?.optString("output").orEmpty(),
                    error = state?.optString("error")?.takeIf { it.isNotBlank() },
                )
            }
            "file" -> Part.FileRef(
                id = id,
                messageID = messageID,
                filename = json.optString("filename").ifBlank { json.optString("url").substringAfterLast('/') },
                mime = json.optString("mime"),
                url = json.optString("url"),
            )
            "step-finish" -> Part.StepFinish(
                id = id,
                messageID = messageID,
                cost = json.optDouble("cost", 0.0),
                tokens = tokens(json.optJSONObject("tokens")),
            )
            "agent" -> Part.AgentRef(id, messageID, json.optString("name"))
            else -> Part.Unknown(id, messageID, type)
        }
    }

    fun chatMessage(infoJson: JSONObject, partsJson: JSONArray?): ChatMessage {
        val parts = ArrayList<Part>()
        if (partsJson != null) {
            for (i in 0 until partsJson.length()) {
                partsJson.optJSONObject(i)?.let { part(it)?.let(parts::add) }
            }
        }
        return ChatMessage(message(infoJson), parts)
    }

    fun providers(json: JSONObject): Pair<List<ProviderInfo>, Map<String, String>> {
        val list = ArrayList<ProviderInfo>()
        val providers = json.optJSONArray("providers") ?: JSONArray()
        for (i in 0 until providers.length()) {
            val item = providers.optJSONObject(i) ?: continue
            val modelsJson = item.optJSONObject("models") ?: JSONObject()
            val models = ArrayList<ModelInfo>()
            for (key in modelsJson.keys()) {
                val model = modelsJson.optJSONObject(key) ?: continue
                models += ModelInfo(
                    id = model.optString("id", key),
                    name = model.optString("name", key),
                    reasoning = model.optBoolean("reasoning", false),
                )
            }
            list += ProviderInfo(
                id = item.optString("id"),
                name = item.optString("name").ifBlank { item.optString("id") },
                models = models.sortedBy { it.name },
            )
        }
        val defaults = LinkedHashMap<String, String>()
        json.optJSONObject("default")?.let { map ->
            for (key in map.keys()) defaults[key] = map.optString(key)
        }
        return list to defaults
    }

    fun agents(json: JSONArray): List<AgentInfo> {
        val result = ArrayList<AgentInfo>()
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            val mode = item.optString("mode", "primary")
            result += AgentInfo(
                name = item.optString("name"),
                description = item.optString("description"),
                mode = mode,
                isPrimary = mode != "subagent",
            )
        }
        return result
    }
}
