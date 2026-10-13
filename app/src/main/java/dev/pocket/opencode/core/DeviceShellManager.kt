package dev.pocket.opencode.core

import android.content.Context
import android.system.Os
import dev.pocket.opencode.service.DeviceShellServer
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 设备 Shell 桥的管家：负责「要不要开、开在哪、容器里怎么用」这三件事。
 *
 * 开启条件（三者缺一不可）：用户在设置里打开开关、Shizuku 在运行、Shizuku 已授权。
 * 只要开着，就往容器里写三样东西：
 *   - `/usr/local/bin/dexec`：容器里调桥的命令
 *   - `/root/.config/opencode/pocket.json`：端口与 token（注意是 root 家目录，不是容器根目录）
 *   - `/root/.config/opencode/skills/device-shell/SKILL.md`：告诉 agent 怎么用
 */
class DeviceShellManager(
    private val context: Context,
    private val paths: PocketPaths,
    private val settings: SettingsStore,
    val shizuku: ShizukuBridge,
) {

    data class Status(
        val enabled: Boolean = false,
        val available: Boolean = false,
        val granted: Boolean = false,
        val running: Boolean = false,
        val port: Int = DEFAULT_PORT,
        val identity: String? = null,
        val message: String = "未开启",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val syncLock = Mutex()
    private var server: DeviceShellServer? = null

    /** 按当前设置把桥拉齐（幂等，随时可调；并发调用会串行执行）。 */
    fun sync() {
        scope.launch { syncNow() }
    }

    suspend fun syncNow() = syncLock.withLock {
        withContext(Dispatchers.IO) { doSync() }
    }

    private suspend fun doSync() {
        val config = settings.snapshot()
        val token = ensureToken(config)
        val available = shizuku.available()
        val granted = shizuku.permissionGranted()
        val shouldRun = config.deviceShell && available && granted

        val identity = if (shouldRun) runCatching { shizuku.identity() }.getOrNull() else null

        if (shouldRun) {
            val current = server
            // 没起来、或者端口被改过，都要重建监听
            if (current == null || !current.isRunning || current.listeningPort != config.deviceShellPort) {
                current?.stop()
                val created = DeviceShellServer(config.deviceShellPort, token) { command, timeout ->
                    shizuku.exec(command, timeout)
                }
                try {
                    created.start()
                    server = created
                } catch (error: Exception) {
                    server = null
                    _status.value = Status(
                        enabled = true,
                        available = available,
                        granted = granted,
                        running = false,
                        port = config.deviceShellPort,
                        message = "端口 ${config.deviceShellPort} 启动失败：${error.message}",
                    )
                    // 端口没起来就不要把容器里的配置写成“可用”
                    writeContainerFiles(enabled = false, config = config, token = token)
                    return
                }
            }
        } else {
            server?.stop()
            server = null
            runCatching { shizuku.disconnect() }
            // 关掉就作废旧令牌，下次开启换一把新的
            if (config.deviceShellToken.isNotEmpty()) {
                settings.update { it.copy(deviceShellToken = "") }
            }
        }

        writeContainerFiles(enabled = shouldRun, config = config, token = token)

        _status.value = Status(
            enabled = config.deviceShell,
            available = available,
            granted = granted,
            running = server?.isRunning == true,
            port = server?.listeningPort ?: config.deviceShellPort,
            identity = identity,
            message = when {
                !config.deviceShell -> "未开启"
                !available -> "Shizuku 没有在运行（先启动 Shizuku，再用无线调试或 root 启动）"
                !granted -> "等待 Shizuku 授权"
                else -> "运行中 · 身份 ${identity ?: "?"} · 127.0.0.1:${server?.listeningPort ?: config.deviceShellPort}"
            },
        )
    }

    /** 在容器里跑一次测试命令，返回可直接展示的结果。 */
    suspend fun testCommand(): String {
        if (!shizuku.permissionGranted()) return "Shizuku 未授权"
        val result = shizuku.exec("id; getprop ro.product.model; uname -a", timeoutMs = 15_000)
        return buildString {
            append(result.stdout.trim())
            if (result.stderr.isNotBlank()) append("\n").append(result.stderr.trim())
            append("\n(exit=${result.exitCode}${if (result.timedOut) " 超时" else ""})")
        }
    }

    private suspend fun ensureToken(config: AppSettings): String {
        if (config.deviceShellToken.isNotBlank()) return config.deviceShellToken
        val token = UUID.randomUUID().toString().replace("-", "")
        settings.update { it.copy(deviceShellToken = token) }
        return token
    }

    /** 把容器里的三份文件写成与当前状态一致。 */
    private suspend fun writeContainerFiles(enabled: Boolean, config: AppSettings, token: String) {
        if (!paths.rootfsLooksInstalled()) return
        // 注意：rootfs 是容器的 /，而 opencode 读的是 /root/.config/opencode
        val configDir = File(paths.rootfs, "root/.config/opencode")

        // 1) dexec（永远写好，能不能用由 pocket.json 决定）
        runCatching {
            val dexec = File(paths.rootfs, "usr/local/bin/dexec")
            dexec.parentFile?.mkdirs()
            dexec.writeText(assetText("device-shell/dexec.sh"))
            Os.chmod(dexec.absolutePath, 0b111_101_101)
        }

        // 2) pocket.json
        runCatching {
            configDir.mkdirs()
            val json = JSONObject()
                .put("enabled", enabled)
                .put("port", if (enabled) (server?.listeningPort ?: config.deviceShellPort) else config.deviceShellPort)
                .put("token", token)
            File(configDir, "pocket.json").writeText(json.toString(2))
        }

        // 3) skill：只有真正能用的时候才让 agent 看到
        val skillDir = File(configDir, "skills/device-shell")
        runCatching {
            if (enabled) {
                skillDir.mkdirs()
                File(skillDir, "SKILL.md").writeText(assetText("device-shell/SKILL.md"))
            } else {
                File(skillDir, "SKILL.md").delete()
            }
        }
    }

    private fun assetText(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    companion object {
        const val DEFAULT_PORT = 3090
    }
}
