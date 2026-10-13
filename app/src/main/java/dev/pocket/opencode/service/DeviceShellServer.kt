package dev.pocket.opencode.service

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 设备 Shell 桥：只监听 127.0.0.1 的极简 HTTP/1.1 服务，把容器里的请求翻译成 Shizuku 命令。
 *
 * 约定（给容器里的 `dexec` 用，所有接口都要 Bearer token）：
 *   GET  /health                  → {"ok":true}
 *   POST /exec                    → 直接在手机本体上执行命令
 *        Header: Authorization: Bearer <token>
 *        Body:   纯文本命令（默认），或 JSON {"cmd":"...","timeoutMs":30000}
 *        Query:  ?format=json       → 返回结构化结果；默认返回纯文本输出 + 退出码
 *
 * 实现细节（踩过的坑）：
 *   - 请求行/头逐字节读，body 也按 **字节** 读 Content-Length 再整体 UTF-8 解码；
 *     用 BufferedReader 按字符读会在含中文的命令上卡到 socket 超时；
 *   - `Expect: 100-continue` 直接回 100，避免客户端等一秒才发 body；
 *   - stop() 会关掉 socket、shutdown 线程池，避免反复开关时留下两个 accept 循环。
 */
class DeviceShellServer(
    private val port: Int,
    private val token: String,
    private val execute: suspend (String, Long) -> dev.pocket.opencode.core.ShizukuBridge.Result,
) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "device-shell-bridge").apply { isDaemon = true }
    }

    val isRunning: Boolean get() = running.get()

    /** 真正在监听的端口（启动失败时仍返回配置值）。 */
    val listeningPort: Int get() = serverSocket?.localPort ?: port

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
                workers.execute { runCatching { handle(client) } }
            }
        }, "device-shell-accept").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        workers.shutdownNow()
    }

    // ------------------------------------------------------------------ HTTP

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = client.getInputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respond(client, 400, "text/plain", "bad request")
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }

            // curl 对 >1KB 的 body 会先等 100-continue
            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                runCatching {
                    client.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    client.getOutputStream().flush()
                }
            }

            val authorized = tokenEquals(
                headers["authorization"]?.removePrefix("Bearer ")?.trim().orEmpty(),
                token,
            )
            if (!authorized) {
                return respond(client, 401, "application/json", """{"error":"unauthorized"}""")
            }

            when {
                method == "GET" && path == "/health" ->
                    respond(client, 200, "application/json", """{"ok":true,"bridge":"opencode-pocket"}""")

                method == "POST" && path == "/exec" -> {
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    if (length > MAX_BODY) return respond(client, 413, "text/plain", "body too large")
                    val bytes = readExactly(input, length)
                    val payload = String(bytes, Charsets.UTF_8)
                    val json = runCatching { JSONObject(payload) }.getOrNull()
                    val command = (json?.optString("cmd") ?: payload).trim()
                    // 超时可来自 JSON body，也可来自 X-Dexec-Timeout-Ms 头（容器里的 dexec 用的是后者）
                    val headerTimeout = headers["x-dexec-timeout-ms"]?.toLongOrNull()
                    val timeout = (json?.optLong("timeoutMs") ?: headerTimeout ?: DEFAULT_TIMEOUT_MS)
                        .coerceIn(1_000, MAX_TIMEOUT_MS)
                    if (command.isEmpty()) {
                        return respond(client, 400, "application/json", """{"error":"empty command"}""")
                    }
                    val result = runBlocking { execute(command, timeout) }
                    val asJson = query.contains("format=json") ||
                        headers["accept"]?.contains("application/json") == true
                    if (asJson) {
                        val body = JSONObject()
                            .put("exitCode", result.exitCode)
                            .put("timedOut", result.timedOut)
                            .put("stdout", result.stdout.take(MAX_OUTPUT))
                            .put("stderr", result.stderr.take(MAX_OUTPUT))
                        respond(client, 200, "application/json", body.toString())
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

    /** 逐字节读到 CRLF，返回不含 CRLF 的字符串（HTTP 头是 ASCII/Latin-1）。 */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(128)
        var previous = -1
        while (true) {
            val current = input.read()
            if (current < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = buffer.toByteArray()
                return String(bytes, 0, (bytes.size - 1).coerceAtLeast(0), Charsets.ISO_8859_1)
            }
            buffer.write(current)
            previous = current
            if (buffer.size() > MAX_HEADER_LINE) return null
        }
    }

    /** 按字节读满 length（或遇到 EOF）。 */
    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(data, read, length - read)
            if (count < 0) break
            read += count
        }
        return if (read == length) data else data.copyOf(read)
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
            val output = socket.getOutputStream()
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
        const val MAX_HEADER_LINE = 8 shl 10   // 单行请求头最长 8 KiB
        const val MAX_OUTPUT = 256 shl 10      // 单次输出最多 256 KiB
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 30 * 60_000L
    }
}
