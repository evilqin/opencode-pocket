package dev.pocket.opencode.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pocket.opencode.PocketApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 开机（或应用被覆盖安装）后按用户设置把服务拉起来。
 *
 * 注意：
 *   - 默认关闭（设置里 `autoStartOnBoot`），因为 Android 12+ 对后台启动前台服务有额外限制，
 *     不同 ROM 的行为也不一样；
 *   - 这里不主动 initialize（PocketApp 已经在做），只等它完成，避免并发初始化；
 *   - 任何失败都静默跳过 —— 开机广播里抛异常没有意义，用户下次打开 App 会看到状态。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val app = context.applicationContext as? PocketApp ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val engine = app.engine
                // 等初始化完成（最多 6 秒：goAsync 只有 ~10 秒预算，不能等太久）
                withTimeoutOrNull(6_000) { engine.initialized.first { it } } ?: return@launch
                val settings = engine.settings.snapshot()
                if (!settings.autoStartOnBoot) return@launch
                if (!engine.installer.state.value.installed) return@launch
                engine.startServer()
            } catch (_: Throwable) {
                // 开机广播里不做任何补救，静默失败
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
