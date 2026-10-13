package dev.pocket.opencode.service

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 设备 Shell 桥：只监听 127.0.0.1 的极简 HTTP 服务，把容器里的请求翻译成 Shizuku 命令。
 *
 * 约定（给容器里的 `dexec` 用）：
 *   GET  /health                  → {"ok":true}
 *   POST /exec                    → 直接在手机本体上执行命令
 *        Header: Authorization: Bearer <token>
 *        Body:   纯文本命令（默认），或 JSON {"cmd":"...","timeoutMs":30000}
 *        Query:  ?format=json       → 返回结构化结果；默认返回纯文本输出
 *
 * 这个服务只在 App 进程活着时运行，且只在用户显式开启「设备 Shell 桥」后启动；
 * token 每次开启时随机生成，卸载/清数据即失效。
 */
class DeviceShellServer(
    private val port: Int,
    private val token: String,
    private val execute: suspend (String, Long) -> dev.pocket.opencode.core.ShizukuBridge.Result,
) {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "device-shell-bridge").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (running.getAndSet(true)) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("127.0.0.1", port), 8)
            serverSocket = socket
        } catch (error: Exception) {
            running.set(false)
            throw error
        }
        Thread({
            while (running.get()) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    continue
                }
                workers.execute { handle(client) }
            }
        }, "device-shell-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respond(client, 400, "text/plain", "bad request")
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }

            when {
                method == "GET" && path == "/health" ->
                    respond(client, 200, "application/json", """{"ok":true,"bridge":"opencode-pocket"}""")

                method == "POST" && path == "/exec" -> {
                    val provided = headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty()
                    if (!tokenEquals(provided, token)) {
                        respond(client, 401, "application/json", """{"error":"unauthorized"}""")
                        return@use
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length > MAX_BODY) return respond(client, 413, "text/plain", "body too large")
                    val body = CharArray(length)
                    var read = 0
                    while (read < length) {
                        val count = reader.read(body, read, length - read)
                        if (count < 0) break
                        read += count
                    }
                    val payload = String(body, 0, read)
                    val json = runCatching { JSONObject(payload) }.getOrNull()
                    val command = (json?.optString("cmd") ?: payload).trim()
                    val timeout = (json?.optLong("timeoutMs") ?: DEFAULT_TIMEOUT_MS)
                        .coerceIn(1_000, MAX_TIMEOUT_MS)
                    if (command.isEmpty()) {
                        respond(client, 400, "application/json", """{"error":"empty command"}""")
                        return@use
                    }
                    val result = runBlocking { execute(command, timeout) }
                    val asJson = query.contains("format=json") ||
                        headers["accept"]?.contains("application/json") == true
                    if (asJson) {
                        val json = JSONObject()
                            .put("exitCode", result.exitCode)
                            .put("timedOut", result.timedOut)
                            .put("stdout", result.stdout.take(MAX_OUTPUT))
                            .put("stderr", result.stderr.take(MAX_OUTPUT))
                        respond(client, 200, "application/json", json.toString())
                    } else {
                        val text = buildString {
                            append(result.stdout.take(MAX_OUTPUT))
                            if (result.stderr.isNotBlank()) {
                                if (isNotEmpty() && !endsWith("\n")) append('\n')
                                append(result.stderr.take(MAX_OUTPUT))
                            }
                            if (result.timedOut) append("\n[命令超时，已被终止]\n")
                        }
                        respond(client, if (result.exitCode == 0) 200 else 500, "text/plain; charset=utf-8", text)
                    }
                }

                else -> respond(client, 404, "application/json", """{"error":"not found"}""")
            }
        }
    }

    private fun respond(socket: Socket, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        runCatching {
            val output: OutputStream = socket.getOutputStream()
            output.write(header)
            output.write(bytes)
            output.flush()
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun tokenEquals(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }

    private companion object {
        const val MAX_BODY = 1 shl 20          // 1 MiB
        const val MAX_OUTPUT = 256 shl 10      // 单次输出最多 256 KiB
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 10 * 60_000L
    }
}
