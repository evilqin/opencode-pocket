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

    private val _logs = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 512)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val recent = ArrayDeque<String>()
    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    private var process: Process? = null
    private var readerJob: Job? = null
    private var startJob: Job? = null

    fun baseUrl(port: Int = _status.value.port): String = "http://127.0.0.1:$port"

    fun isAlive(): Boolean = process?.isAlive == true

    // ------------------------------------------------------------------ 启动

    suspend fun start(settings: AppSettings): Boolean {
        if (isAlive()) return true
        if (startJob?.isActive == true) return false

        if (!paths.rootfsLooksInstalled() || !File(paths.rootfs, "usr/local/lib/opencode/bin/opencode").isFile) {
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

                // 清掉上次残留的进程（强制杀，避免端口占用）
                runCatching {
                    shell.run(
                        "pkill -9 -f 'opencode' >/dev/null 2>&1; sleep 0.5; true",
                        settings,
                        timeoutMs = 20_000,
                    )
                }

                _status.value = _status.value.copy(hint = "拉起源码运行时…")
                val env = OpencodeConfig.environment(settings)
                val command =
                    "cd ${PocketPaths.Guest.WORKSPACE} 2>/dev/null || cd /root; " +
                        "exec opencode web --hostname 127.0.0.1 --port ${settings.port}"
                val started = withContext(Dispatchers.IO) {
                    proot.start(
                        command = command,
                        config = settings.copy(port = settings.port),
                        extraEnv = env,
                        cwd = PocketPaths.Guest.ROOT,
                    )
                }
                process = started
                readerJob = scope.launch { pump(started) }

                _status.value = _status.value.copy(hint = "等待服务就绪…")
                val healthy = awaitHealthy(settings.port, timeoutMs = 240_000)
                if (!healthy) {
                    if (started.isAlive) {
                        _status.value = _status.value.copy(
                            phase = ServerPhase.ERROR,
                            error = "服务启动超时",
                            hint = "看一下运行日志，常见原因是没配 API Key 或者内存不足",
                        )
                    } else {
                        _status.value = _status.value.copy(
                            phase = ServerPhase.ERROR,
                            error = "进程退出了",
                            hint = "运行日志里有原因",
                        )
                    }
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
                "pkill -9 -f 'opencode' >/dev/null 2>&1; true",
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

    private suspend fun awaitHealthy(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var attempts = 0
        while (System.currentTimeMillis() < deadline) {
            attempts++
            if (!isAlive()) return false
            if (attempts % 8 == 0) {
                val seconds = ((System.currentTimeMillis() - (deadline - timeoutMs)) / 1000)
                appendLog("… 仍在等待服务就绪（${seconds}s）")
            }
            val body = health()
            if (body != null && body.optBoolean("healthy", false)) return true
            delay(700)
        }
        return false
    }

    private fun health(): JSONObject? = try {
        val request = Request.Builder().url("${baseUrl()}/global/health").build()
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
        runCatching { paths.serverLog.appendText(stamped + "\n") }
    }

    fun clearRecentLogs() {
        synchronized(recent) {
            recent.clear()
            _recentLogs.value = emptyList()
        }
    }
}
