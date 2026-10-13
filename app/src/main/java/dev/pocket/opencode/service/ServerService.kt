package dev.pocket.opencode.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import dev.pocket.opencode.PocketApp
import dev.pocket.opencode.core.ServerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：容器里的 opencode 服务在这个进程生命期内保持运行。
 *
 * 说明：Android 12+ 的「幻影进程杀手」按应用进程数下手，容器里的 node/opencode
 * 子进程也算在内。前台服务 + 单个服务进程是最省的做法；如果设备上装的是
 * Shizuku/adb，可以用 `settings put global settings_enable_monitor_phantom_procs false` 彻底关掉限制。
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val engine get() = PocketApp.instance.engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        startForeground(
            Notifications.ID_SERVER,
            Notifications.serverNotification(
                this,
                title = "OpenCode 口袋",
                text = "准备中…",
                running = true,
            ),
        )
        startObserving()

        when (action) {
            ACTION_STOP -> {
                scope.launch {
                    engine.server.stop(engine.settings.snapshot())
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_RESTART -> scope.launch {
                val settings = engine.settings.snapshot()
                engine.server.stop(settings)
                acquireWakeLock()
                engine.server.start(settings)
            }
            else -> scope.launch {
                val settings = engine.settings.snapshot()
                if (engine.server.isAlive()) return@launch
                acquireWakeLock()
                engine.server.start(settings)
            }
        }
        return START_STICKY
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            engine.server.status.collect { status ->
                val text = when (status.phase) {
                    ServerPhase.RUNNING -> "127.0.0.1:${status.port}  ·  v${status.version.ifBlank { "?" }}"
                    ServerPhase.STARTING -> status.hint.ifBlank { "启动中…" }
                    ServerPhase.STOPPING -> "正在停止…"
                    ServerPhase.ERROR -> status.error ?: "出错了"
                    ServerPhase.STOPPED -> "已停止"
                }
                val title = when (status.phase) {
                    ServerPhase.RUNNING -> "OpenCode 运行中"
                    ServerPhase.STARTING -> "OpenCode 启动中"
                    ServerPhase.ERROR -> "OpenCode 启动失败"
                    else -> "OpenCode 口袋"
                }
                runCatching {
                    NotificationManagerCompat.from(this@ServerService).notify(
                        Notifications.ID_SERVER,
                        Notifications.serverNotification(
                            this@ServerService,
                            title = title,
                            text = text,
                            running = status.phase != ServerPhase.STOPPED,
                            showStop = status.phase == ServerPhase.RUNNING || status.phase == ServerPhase.STARTING,
                        ),
                    )
                }
                if (status.phase == ServerPhase.STOPPED && !engine.server.isAlive()) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode-pocket:server").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.pocket.opencode.START"
        const val ACTION_STOP = "dev.pocket.opencode.STOP"
        const val ACTION_RESTART = "dev.pocket.opencode.RESTART"
    }
}
