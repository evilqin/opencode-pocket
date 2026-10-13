package dev.pocket.opencode.data

import dev.pocket.opencode.core.Downloader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 opencode 服务的最小 REST + SSE 客户端（原生对话页用）。
 *
 * 与服务端约定见 https://opencode.ai/docs/server/：
 *   - 会话：GET/POST /session，GET /session/:id/message
 *   - 发消息：POST /session/:id/prompt_async（不等回复），结果通过 /event 流式回来
 *   - 事件：GET /event（SSE，`data:` 一行一个 JSON）
 */
class OpencodeApi(
    downloads: Downloader,
    private val portProvider: () -> Int,
) {

    private val client: OkHttpClient = downloads.http.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    class ApiException(val status: Int, message: String) : Exception(message)

    private fun url(path: String) = "http://127.0.0.1:${portProvider()}$path"

    // ---------------------------------------------------------------- 基础请求

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val body = getText(path)
        JSONObject(body.ifBlank { "{}" })
    }

    private suspend fun getArray(path: String): JSONArray = withContext(Dispatchers.IO) {
        val body = getText(path)
        if (body.isBlank()) return@withContext JSONArray()
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return@withContext JSONArray(trimmed)
        // 个别版本会包一层：{ data: [...] } / { items: [...] }
        val json = JSONObject(trimmed)
        json.optJSONArray("data") ?: json.optJSONArray("items") ?: JSONArray()
    }

    private suspend fun getText(path: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url(path)).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, body.take(300))
            body
        }
    }

    private suspend fun post(path: String, body: JSONObject?): JSONObject? = withContext(Dispatchers.IO) {
        val payload = (body ?: JSONObject()).toString()
        val request = Request.Builder().url(url(path)).post(payload.toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, text.take(300))
            if (text.isBlank()) null else JSONObject(text)
        }
    }

    private suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url(path)).delete().build()).execute().use { response ->
            response.isSuccessful
        }
    }

    private suspend fun patch(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(path)).patch(body.toString().toRequestBody(jsonType)).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, text.take(300))
            JSONObject(text.ifBlank { "{}" })
        }
    }

    // ---------------------------------------------------------------- 具体接口

    suspend fun health(): JSONObject? = try {
        getJson("/global/health")
    } catch (_: Exception) {
        null
    }

    suspend fun sessions(): List<SessionInfo> = withContext(Dispatchers.IO) {
        parseSessionArray(getArray("/session"))
    }

    private fun parseSessionArray(array: JSONArray): List<SessionInfo> {
        val result = ArrayList<SessionInfo>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { result += Parsers.session(it) }
        }
        return result.sortedByDescending { it.updated }
    }

    suspend fun createSession(title: String? = null): SessionInfo {
        val body = JSONObject()
        if (!title.isNullOrBlank()) body.put("title", title)
        val json = post("/session", body) ?: JSONObject()
        return Parsers.session(json)
    }

    suspend fun renameSession(id: String, title: String): SessionInfo =
        Parsers.session(patch("/session/$id", JSONObject().put("title", title)))

    suspend fun deleteSession(id: String): Boolean = delete("/session/$id")

    suspend fun abort(sessionID: String) {
        post("/session/$sessionID/abort", JSONObject())
    }

    suspend fun messages(sessionID: String, limit: Int? = null): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            val query = limit?.let { "?limit=$it" } ?: ""
            val array = getArray("/session/$sessionID/message$query")
            val result = ArrayList<ChatMessage>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                result += Parsers.chatMessage(item.optJSONObject("info") ?: JSONObject(), item.optJSONArray("parts"))
            }
            result
        }

    /** 异步发消息：立刻返回，回复通过事件流推送。 */
    suspend fun promptAsync(
        sessionID: String,
        text: String,
        model: Pair<String, String>? = null,
        agent: String? = null,
    ) {
        val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        val body = JSONObject().put("parts", parts)
        model?.let {
            body.put("model", JSONObject().put("providerID", it.first).put("modelID", it.second))
        }
        agent?.takeIf { it.isNotBlank() }?.let { body.put("agent", it) }
        post("/session/$sessionID/prompt_async", body)
    }

    suspend fun replyPermission(sessionID: String, permissionID: String, response: String) {
        post(
            "/session/$sessionID/permissions/$permissionID",
            JSONObject().put("response", response),
        )
    }

    suspend fun providers(): Pair<List<ProviderInfo>, Map<String, String>> =
        Parsers.providers(getJson("/config/providers"))

    suspend fun agents(): List<AgentInfo> = withContext(Dispatchers.IO) {
        Parsers.agents(getArray("/agent"))
    }

    suspend fun config(): JSONObject = getJson("/config")

    // ---------------------------------------------------------------- 事件流

    /**
     * SSE 事件流：断线自动重连，直到收集方取消。
     * 返回的是原始 JSON（`{type, properties}`），由上层解释。
     */
    fun events(): Flow<JSONObject> = flow {
        while (true) {
            try {
                val request = Request.Builder()
                    .url(url("/event"))
                    .header("Accept", "text/event-stream")
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()
                    .newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw ApiException(response.code, "event stream")
                        val source = response.body?.source() ?: return@use
                        val data = StringBuilder()
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) {
                                        val payload = data.toString()
                                        data.clear()
                                        runCatching { emit(JSONObject(payload)) }
                                    }
                                }
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            }
                        }
                    }
            } catch (_: Exception) {
                // 服务没起来或断线：稍后重连
            }
            delay(1200)
        }
    }.flowOn(Dispatchers.IO)
}
