package dev.pocket.opencode.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.pocket.opencode.PocketApp
import dev.pocket.opencode.core.ServerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务：容器里的 opencode 服务在这个进程生命期内保持运行。
 *
 * 注意：服务只在「用户/系统明确要求停止」时自己结束（ACTION_STOP / onDestroy），
 * 不根据状态流的初始 STOPPED 值自杀——那会在启动瞬间把刚起来的前台服务干掉。
 *
 * Android 12+ 的「幻影进程杀手」按应用进程数下手，容器里的 node/opencode 子进程也算在内。
 * 前台服务 + 单服务进程是最省的做法；如果设备上有 Shizuku/adb，可以用
 * `settings put global settings_enable_monitor_phantom_procs false` 彻底关掉限制。
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val engine get() = PocketApp.instance.engine

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        // 必须在 5 秒内进前台。系统可能拒绝（后台启动限制、通知被关等），
        // 失败时不要崩溃，也不要把服务硬撑在没前台的半吊子状态。
        val foregroundOk = runCatching {
            startForeground(
                Notifications.ID_SERVER,
                Notifications.serverNotification(
                    this,
                    title = "OpenCode 口袋",
                    text = "准备中…",
                    running = true,
                ),
            )
        }.isSuccess
        if (!foregroundOk) {
            Log.w(TAG, "startForeground 失败，停止服务")
            releaseWakeLock()
            runCatching { stopSelf() }
            return START_NOT_STICKY
        }
        startObserving()

        when (action) {
            ACTION_STOP -> scope.launch {
                engine.server.stop(engine.settings.snapshot())
                releaseWakeLock()
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(this@ServerService)
                        .cancel(Notifications.ID_SERVER)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_RESTART -> scope.launch {
                acquireWakeLock()
                val settings = engine.settings.snapshot()
                engine.server.restart(settings)
                releaseWakeLockOnImmediateFailure()
            }

            else -> scope.launch {
                if (engine.server.isAlive()) return@launch
                acquireWakeLock()
                engine.server.start(engine.settings.snapshot())
                releaseWakeLockOnImmediateFailure()
            }
        }
        return START_STICKY
    }

    private fun startObserving() {
        if (observeJob?.isActive == true) return
        observeJob = scope.launch {
            // 本次前台服务是否真的进入过活动状态（RUNNING/STARTING）。
            var sawActive = false
            // WakeLock 续期：状态流在值不变时不会重新发射，所以不能只在状态跳变时续期，
            // 否则连续运行超过 60 分钟后锁会静默过期（长任务是这个应用的核心场景）。
            launch {
                while (isActive) {
                    delay(WAKELOCK_RENEW_INTERVAL_MS)
                    if (engine.server.status.value.phase == ServerPhase.RUNNING) acquireWakeLock()
                }
            }
            engine.server.status.collect { status ->
                // 见过 RUNNING/STARTING 才说明「确实起来过」，之后失败/停止才有必要收尾，
                // 否则会误伤启动瞬间状态流里那个初始的 STOPPED。
                when (status.phase) {
                    ServerPhase.RUNNING, ServerPhase.STARTING -> sawActive = true
                    ServerPhase.ERROR -> if (sawActive) {
                        // 启动失败：释放 WakeLock，并让前台服务退场，
                        // 否则锁会一直拖到 60 分钟超时、ERROR 通知也一直挂着。
                        releaseWakeLock()
                        launch {
                            delay(ERROR_STOP_DELAY_MS)
                            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                            runCatching { stopSelf() }
                        }
                    }
                    ServerPhase.STOPPED -> if (sawActive) {
                        // 服务已停：不再需要 WakeLock（保留 STOPPED 不自杀的既有行为）。
                        releaseWakeLock()
                    }
                    else -> Unit
                }
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
                            // 失败态也别做成不可划掉的常驻通知：允许停止，也允许划走
                            running = status.phase == ServerPhase.RUNNING || status.phase == ServerPhase.STARTING,
                            showStop = status.phase != ServerPhase.STOPPED,
                        ),
                    )
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val current = wakeLock
        if (current?.isHeld == true) {
            // 续期：先释放再按新超时获取
            runCatching { current.release() }
        }
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "opencode-pocket:server").apply {
            setReferenceCounted(false)
            runCatching { acquire(WAKELOCK_TIMEOUT_MS) }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    /**
     * 补一次「启动立刻失败」的收尾。
     *
     * start()/restart() 会在口令没生成、容器没装好这类情况下**同步**置 ERROR 并返回，
     * 状态从没到过 STARTING/RUNNING —— 状态收集协程里的 sawActive 永远是 false，
     * 于是刚 acquireWakeLock() 拿到的锁没人释放，会一直拖到 60 分钟超时。
     * 这里按「启动流程走完仍是 ERROR」兜底释放；锁已经释放过时是空操作。
     */
    private fun releaseWakeLockOnImmediateFailure() {
        if (engine.server.status.value.phase == ServerPhase.ERROR) releaseWakeLock()
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

        /** 单次 WakeLock 最长 60 分钟，服务运行时会自动续期。 */
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L

        /** 续期间隔：比超时短一半，保证锁不会过期。 */
        private const val WAKELOCK_RENEW_INTERVAL_MS = 30 * 60 * 1000L

        /** 启动失败后延迟几秒再退场：给用户留一点看到错误通知的时间。 */
        private const val ERROR_STOP_DELAY_MS = 5_000L

        private const val TAG = "ServerService"
    }
}
