package dev.pocket.opencode.service

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 *        Query:  ?format=json       → 返回结构化结果；默认返回纯文本输出
 *        状态码: 200 成功 / 500 命令非 0 退出 / 504 命令超时 / 403 被危险命令防护拦下 /
 *                401 未认证 / 400 请求不合法 / 501 不支持的传输方式 / 431 头部过大
 *
 * 实现要点（都踩过坑）：
 *   - 请求行/头逐字节读、body 按 **字节** 读 Content-Length 再整体 UTF-8 解码
 *     （用字符读会在含中文的命令上卡到 socket 超时）；
 *   - **先认证再谈别的**：没 token 就不发 100-continue、也不读 body；
 *   - 头部有条数/总长度上限，拒绝 Transfer-Encoding（不做 chunked）；
 *   - 线程池有界：慢速连接打满后就拒绝新连接，不会无限吃内存；
 *   - 危险命令防护 + 每次执行写审计（含被拦的）。
 */
class DeviceShellServer(
    private val port: Int,
    /**
     * 令牌提供者：每个请求实时读取（用户「重新生成令牌」后立即生效，
     * 不需要重建监听；旧实现把 token 钉死在构造参数里，换令牌后一直 401）。
     */
    private val tokenProvider: () -> String,
    /** 危险命令防护：返回非 null 表示拒绝执行（原因给 agent 看）。 */
    private val guard: (String) -> String? = { null },
    /** 每次执行（含被拦）都回调一行审计文本。 */
    private val onAudit: (String) -> Unit = {},
    private val execute: suspend (String, Long) -> dev.pocket.opencode.core.ShizukuBridge.Result,
) {

    @Volatile
    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)

    /** 有界线程池：队列满了直接拒绝（close socket），避免慢速攻击把内存吃干。start() 里重建。 */
    private var workers = newWorkerPool()

    private fun newWorkerPool() = ThreadPoolExecutor(
        4,
        16,
        30,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "device-shell-bridge").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    val isRunning: Boolean get() = running.get()

    /** 真正在监听的端口（启动失败时仍返回配置值）。 */
    val listeningPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        if (running.getAndSet(true)) return
        // stop() 会 shutdownNow，所以每次启动都新建一个有界线程池
        workers = newWorkerPool()
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
                    // 偶发错误不要忙等：让出一点 CPU 再 accept，避免失败风暴打满一个核
                    runCatching { Thread.sleep(50) }
                    continue
                }
                try {
                    workers.execute { runCatching { handle(client) } }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    // 队列满：宁可拒绝这个连接，也不能无限排队
                    runCatching { client.close() }
                }
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
            // 头部阶段用较短超时：慢速连接不能长时间占着池线程；读 body 时再放宽
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = client.getInputStream()

            val requestLine = try {
                readLine(input) ?: return
            } catch (_: HeaderTooLongException) {
                return respond(client, 431, "text/plain", "request line too long")
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respond(client, 400, "text/plain", "bad request")
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            val headers = LinkedHashMap<String, String>()
            var headerBytes = 0
            while (true) {
                val line = try {
                    readLine(input) ?: break
                } catch (_: HeaderTooLongException) {
                    return respond(client, 431, "text/plain", "header line too long")
                }
                if (line.isEmpty()) break
                headerBytes += line.length + 2
                if (headers.size > MAX_HEADER_COUNT || headerBytes > MAX_HEADER_TOTAL) {
                    return respond(client, 431, "text/plain", "headers too large")
                }
                val index = line.indexOf(':')
                if (index > 0) headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }

            // 1) 认证永远排在前面：没通过就不处理 body、不发 100-continue
            val authHeader = headers["authorization"].orEmpty()
            // 严格 Bearer 前缀，别接受裸 token
            val provided = if (authHeader.length > 7 && authHeader.substring(0, 7).equals("Bearer ", true)) {
                authHeader.substring(7).trim()
            } else {
                ""
            }
            // 令牌每请求实时读取：用户「重新生成令牌」后立即生效，不需要重建监听
            val token = runCatching { tokenProvider() }.getOrDefault("")
            if (!tokenEquals(provided, token)) {
                return respond(client, 401, "application/json", """{"error":"unauthorized"}""")
            }

            // 2) 不做 chunked：直接拒绝，避免解析歧义
            if (headers["transfer-encoding"] != null) {
                return respond(client, 501, "application/json", """{"error":"transfer-encoding not supported"}""")
            }

            // 3) curl 对 >1KB 的 body 会先等 100-continue（此时已经通过认证）
            if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
                runCatching {
                    val output = client.getOutputStream()
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    output.flush()
                }
            }

            when {
                method == "GET" && path == "/health" ->
                    respond(client, 200, "application/json", """{"ok":true,"bridge":"opencode-pocket"}""")

                method == "POST" && path == "/exec" -> {
                    val length = headers["content-length"]?.toIntOrNull()
                    if (length == null || length < 0) {
                        return respond(client, 411, "application/json", """{"error":"content-length required"}""")
                    }
                    if (length > MAX_BODY) {
                        return respond(client, 413, "application/json", """{"error":"body too large"}""")
                    }
                    // 读 body 放宽超时：大 body / 网络抖动会分多次到达
                    client.soTimeout = BODY_TIMEOUT_MS
                    val bytes = readExactly(input, length)
                    val payload = String(bytes, Charsets.UTF_8)
                    val json = runCatching { JSONObject(payload) }.getOrNull()
                    val command = (json?.optString("cmd") ?: payload).trim()
                    val headerTimeout = headers["x-dexec-timeout-ms"]?.toLongOrNull()
                    // 注意：不能写 json?.optLong("timeoutMs") ?: header —— 合法 JSON 但没有该字段时
                    // optLong 返回 0，会被夹成 1 秒，长命令莫名超时
                    val bodyTimeout = json?.takeIf { it.has("timeoutMs") }?.optLong("timeoutMs")
                    val timeout = (bodyTimeout ?: headerTimeout ?: DEFAULT_TIMEOUT_MS)
                        .coerceIn(1_000, MAX_TIMEOUT_MS)
                    if (command.isEmpty()) {
                        return respond(client, 400, "application/json", """{"error":"empty command"}""")
                    }

                    // 4) 危险命令防护：先拦再执行（原因给 agent，也写审计）
                    // 防护本身抛异常时按「拦住」处理（fail-closed）：宁可拒绝执行，也不能放行没检查过的命令
                    val blocked = try {
                        guard(command)
                    } catch (error: Exception) {
                        "危险命令防护内部错误（已拦截）：${error.message ?: error::class.java.simpleName}"
                    }
                    if (blocked != null) {
                        onAudit("BLOCKED  ${sanitize(command)}   （${sanitize(blocked)}）")
                        return respond(
                            client,
                            403,
                            "application/json",
                            JSONObject()
                                .put("error", "blocked-by-guard")
                                .put("reason", blocked)
                                .put("hint", "这条命令被危险命令防护拦下；请让用户在 App 内确认，或到设置里关闭防护")
                                .toString(),
                        )
                    }

                    val result = try {
                        runBlocking { execute(command, timeout) }
                    } catch (error: Exception) {
                        onAudit("ERROR  ${sanitize(command)}   （${error.message ?: error::class.java.simpleName}）")
                        return respond(
                            client,
                            500,
                            "application/json",
                            JSONObject()
                                .put("error", "execute-failed")
                                .put("detail", error.message ?: "unknown")
                                .toString(),
                        )
                    }
                    onAudit(
                        buildString {
                            append(if (result.timedOut) "TIMEOUT" else "exit=${result.exitCode}")
                            append("  ")
                            append(sanitize(command))
                        },
                    )
                    val asJson = query.split('&').any { it.equals("format=json", true) } ||
                        headers["accept"]?.contains("application/json") == true
                    if (asJson) {
                        val body = JSONObject()
                            .put("exitCode", result.exitCode)
                            .put("timedOut", result.timedOut)
                            .put("stdout", result.stdout.take(MAX_OUTPUT))
                            .put("stderr", result.stderr.take(MAX_OUTPUT))
                        respond(client, if (result.exitCode == 0) 200 else 500, "application/json", body.toString())
                    } else {
                        val text = buildString {
                            append(result.stdout.take(MAX_OUTPUT))
                            if (result.stderr.isNotBlank()) {
                                if (isNotEmpty() && !endsWith("\n")) append('\n')
                                append(result.stderr.take(MAX_OUTPUT))
                            }
                            if (result.timedOut) append("\n[命令超时，已被终止]\n")
                        }
                        val status = when {
                            result.timedOut -> 504
                            result.exitCode != 0 -> 500
                            else -> 200
                        }
                        respond(client, status, "text/plain; charset=utf-8", text)
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
            // 规范要求 CRLF，但有些客户端只发 LF：两种都接受
            if (current == '\n'.code) {
                val bytes = buffer.toByteArray()
                val length = if (previous == '\r'.code) (bytes.size - 1).coerceAtLeast(0) else bytes.size
                return String(bytes, 0, length, Charsets.ISO_8859_1)
            }
            buffer.write(current)
            previous = current
            if (buffer.size() > MAX_HEADER_LINE) throw HeaderTooLongException()
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
        403 -> "Forbidden"
        404 -> "Not Found"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        504 -> "Gateway Timeout"
        else -> "OK"
    }

    private fun tokenEquals(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        return java.security.MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }

    /** 审计日志里不能出现 CR/LF/Tab，否则可以伪造出一行假的审计记录；长度统一截到 200。 */
    private fun sanitize(text: String): String =
        text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').take(200)

    /** 请求行/头行超过上限：不再当作「头结束」返回 null，而是明确回 431。 */
    private class HeaderTooLongException : Exception()

    private companion object {
        /** 头部读取阶段：慢速连接不能长时间占着池线程。 */
        const val HEADER_TIMEOUT_MS = 5_000
        /** 读 body 时放宽（大 body 会分多次到达）。 */
        const val BODY_TIMEOUT_MS = 20_000
        const val MAX_BODY = 1 shl 20          // 1 MiB
        const val MAX_HEADER_LINE = 8 shl 10   // 单行请求头最长 8 KiB
        const val MAX_HEADER_COUNT = 32        // 请求头最多 32 行
        const val MAX_HEADER_TOTAL = 32 shl 10 // 请求头总量最多 32 KiB
        const val MAX_OUTPUT = 256 shl 10      // 单次输出最多 256 KiB
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_MS = 10 * 60_000L // 与 Shizuku 用户服务的上限保持一致
    }
}
