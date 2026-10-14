package dev.pocket.opencode.core

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

enum class ServerPhase { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class ServerStatus(
    val phase: ServerPhase = ServerPhase.STOPPED,
    val port: Int = 4096,
    val version: String = "",
    val startedAt: Long = 0L,
    val error: String? = null,
    val hint: String = "",
) {
    val running: Boolean get() = phase == ServerPhase.RUNNING
}

/**
 * opencode 服务（容器内）的生命周期管理。
 *
 * 启动的是 `opencode web`：同一个端口既提供 HTTP API（原生对话页在用），
 * 也提供官方 Web UI（网页标签页在用）。
 */
class ServerController(
    context: Context,
    private val paths: PocketPaths,
    private val proot: Proot,
    private val shell: GuestShell,
    private val downloads: Downloader,
    private val net: NetworkBridge,
) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(ServerStatus())
    val status: StateFlow<ServerStatus> = _status.asStateFlow()

    private val _logs = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 512)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val recent = ArrayDeque<String>()
    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    /** 最近一次启动用的口令（健康检查与状态轮询复用）。 */
    @Volatile
    private var _lastPassword: String = ""

    private var process: Process? = null
    private var readerJob: Job? = null
    private var startJob: Job? = null

    fun baseUrl(port: Int = _status.value.port): String = "http://127.0.0.1:$port"

    fun isAlive(): Boolean = process?.isAlive == true

    // ------------------------------------------------------------------ 启动

    suspend fun start(settings: AppSettings): Boolean {
        if (isAlive()) return true
        if (startJob?.isActive == true) return false

        if (settings.serverPassword.isBlank()) {
            _status.value = _status.value.copy(
                phase = ServerPhase.ERROR,
                error = "服务口令还没生成",
                hint = "重开一次 App（或点一次启动）会自动生成",
            )
            return false
        }
        _lastPassword = settings.serverPassword
        if (!paths.rootfsLooksInstalled() ||
            !File(paths.rootfs, PocketPaths.Guest.OPENCODE_BIN.trimStart('/')).isFile
        ) {
            _status.value = _status.value.copy(
                phase = ServerPhase.ERROR,
                error = "容器环境还没装好",
                hint = "先到「安装」页完成一键安装",
            )
            return false
        }

        val job = scope.launch {
            try {
                _status.value = ServerStatus(ServerPhase.STARTING, port = settings.port, hint = "准备容器…")
                appendLog("▶ 启动 OpenCode 服务（端口 ${settings.port}）")

                proot.stageBootRuntime()
                net.writeResolvConf()

                // 清掉上次残留的进程：按 guest 里记录的 pid 精确杀（见 CLEANUP_COMMAND 的注释）
                runCatching {
                    shell.run(
                        CLEANUP_COMMAND,
                        settings,
                        timeoutMs = 20_000,
                    )
                }

                _status.value = _status.value.copy(hint = "拉起源码运行时…")
                val env = OpencodeConfig.environment(settings)
                val command =
                    "cd ${PocketPaths.Guest.WORKSPACE} 2>/dev/null || cd /root; " +
                        // 记录 guest 侧的 pid：停止/清理时按 pid 精确杀。不要用 pkill -f opencode ——
                        // App 自己的进程名里就带 opencode，而 /proc 被原样 bind 进容器，会误杀 App。
                        "printf '%s' \$\$ > /tmp/opencode.pid; " +
                        "exec opencode web --hostname 127.0.0.1 --port ${settings.port}"
                val started = withContext(Dispatchers.IO) {
                    proot.start(
                        command = command,
                        config = settings,
                        extraEnv = env,
                        cwd = PocketPaths.Guest.ROOT,
                    )
                }
                process = started
                readerJob = scope.launch { pump(started) }

                _status.value = _status.value.copy(hint = "等待服务就绪…")
                val healthy = awaitHealthy(settings.port, settings.serverPassword, timeoutMs = 240_000)
                if (!healthy) {
                    // 起不来就别让它占着端口：先杀掉再报错
                    withContext(Dispatchers.IO) {
                        runCatching { started.destroy() }
                        delay(1_200)
                        runCatching { started.destroyForcibly() }
                    }
                    runCatching {
                        shell.run("pkill -9 -f '[o]pencode' >/dev/null 2>&1; true", settings, timeoutMs = 20_000)
                    }
                    process = null
                    _status.value = _status.value.copy(
                        phase = ServerPhase.ERROR,
                        error = "服务启动超时（已清理进程）",
                        hint = "看一下运行日志：常见原因是没配 API Key、内存不足，或者端口被占用",
                    )
                    return@launch
                }

                val health = health() ?: JSONObject()
                _status.value = ServerStatus(
                    phase = ServerPhase.RUNNING,
                    port = settings.port,
                    version = health.optString("version", ""),
                    startedAt = System.currentTimeMillis(),
                    hint = "",
                )
                appendLog("✔ 服务已就绪：${baseUrl(settings.port)}（v${_status.value.version}）")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                appendLog("✘ 启动失败：${error.message}")
                _status.value = _status.value.copy(
                    phase = ServerPhase.ERROR,
                    error = error.message ?: "启动失败",
                )
            }
        }
        startJob = job
        job.join()
        return _status.value.phase == ServerPhase.RUNNING
    }

    // ------------------------------------------------------------------ 停止

    suspend fun stop(settings: AppSettings) {
        _status.value = _status.value.copy(phase = ServerPhase.STOPPING, hint = "正在停止…")
        appendLog("■ 正在停止服务…")
        val current = process
        process = null
        if (current != null) {
            withContext(Dispatchers.IO) {
                current.destroy()
                var waited = 0
                while (current.isAlive && waited < 60) {
                    delay(100)
                    waited++
                }
                if (current.isAlive) current.destroyForcibly()
            }
        }
        runCatching {
            shell.run(
                CLEANUP_COMMAND,
                settings,
                timeoutMs = 20_000,
            )
        }
        _status.value = ServerStatus(phase = ServerPhase.STOPPED, port = settings.port)
        appendLog("■ 服务已停止")
    }

    suspend fun restart(settings: AppSettings) {
        stop(settings)
        start(settings)
    }

    private suspend fun awaitHealthy(port: Int, password: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            if (!isAlive()) return false
            if (attempts % 8 == 0) {
                val seconds = ((System.currentTimeMillis() - (deadline - timeoutMs)) / 1000)
                appendLog("… 仍在等待服务就绪（${seconds}s）")
            }
            val body = health(port, password)
            if (body != null && body.optBoolean("healthy", false)) return true
            delay(700)
        }
        return false
    }

    /** 本机服务带口令（OPENCODE_SERVER_PASSWORD），健康检查也要带上。 */
    private fun health(port: Int = _status.value.port, password: String = _lastPassword): JSONObject? = try {
        val builder = Request.Builder().url("http://127.0.0.1:$port/global/health")
        if (password.isNotBlank()) {
            val token = android.util.Base64.encodeToString(
                "opencode:$password".toByteArray(),
                android.util.Base64.NO_WRAP,
            )
            builder.header("Authorization", "Basic $token")
        }
        val request = builder.build()
        healthClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let { JSONObject(it) }
        }
    } catch (_: Exception) {
        null
    }

    /** 把容器输出搬到日志里。 */
    private suspend fun pump(target: Process) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(target.inputStream), 64 * 1024)
            while (isActive) {
                val line = reader.readLine() ?: break
                appendLog(line)
            }
        } catch (_: Exception) {
        } finally {
            val exit = runCatching { target.exitValue() }.getOrDefault(-1)
            if (_status.value.phase == ServerPhase.RUNNING || _status.value.phase == ServerPhase.STARTING) {
                appendLog("⚠ 服务进程结束（exit=$exit）")
                _status.value = _status.value.copy(
                    phase = ServerPhase.ERROR,
                    error = "服务进程退出（exit=$exit）",
                    hint = "看运行日志定位原因，然后点「启动」重试",
                )
            }
        }
    }

    private fun appendLog(line: String) {
        val stamped = "[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $line"
        _logs.tryEmit(stamped)
        synchronized(recent) {
            recent.addLast(stamped)
            while (recent.size > 400) recent.removeFirst()
            _recentLogs.value = recent.toList()
        }
        runCatching {
            rotateLogIfNeeded()
            paths.serverLog.appendText(stamped + "\n")
        }
    }

    /** 超过 1 MB 就把 server.log 转成 server.log.1，避免长期运行无限增长。 */
    private fun rotateLogIfNeeded() {
        val log = paths.serverLog
        if (!log.isFile || log.length() < MAX_LOG_BYTES) return
        val backup = File(log.parentFile, log.name + ".1")
        backup.delete()
        if (!log.renameTo(backup)) {
            // 某些文件系统上 renameTo 会失败：退化成复制 + 截断，别让日志无限增长
            runCatching {
                log.copyTo(backup, overwrite = true)
                java.io.RandomAccessFile(log, "rw").use { it.setLength(0) }
            }
        }
    }

    fun clearRecentLogs() {
        synchronized(recent) {
            recent.clear()
            _recentLogs.value = emptyList()
        }
    }

    private companion object {
        const val MAX_LOG_BYTES = 1L shl 20 // 1 MiB

        /**
         * 停止/清理容器里的服务：先按 /tmp/opencode.pid 精确杀，再用收窄后的模式兜底。
         *
         * 注意 **不要** 用 `pkill -f opencode`：App 自己的进程名里带 opencode，
         * 而容器的 /proc 是宿主原样 bind 的，pkill 会按宿主 pid 把 App 一起杀掉。
         */
        val CLEANUP_COMMAND: String = buildString {
            append("if [ -f /tmp/opencode.pid ]; then ")
            append("kill -9 \"$(cat /tmp/opencode.pid)\" 2>/dev/null || true; ")
            append("rm -f /tmp/opencode.pid; fi; ")
            append("pkill -9 -f '[o]pencode web' >/dev/null 2>&1 || true; true")
        }
    }
}
