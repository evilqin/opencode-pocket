package dev.pocket.opencode

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.pocket.opencode.core.DeepLinks
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.ui.PocketRoot
import dev.pocket.opencode.ui.theme.PocketTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val engine = (application as PocketApp).engine
        splash.setKeepOnScreenCondition { false }

        handleShortcutIntent(intent, engine)

        setContent {
            val settings by engine.settings.settings.collectAsStateWithLifecycle()
            PocketTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
            ) {
                PocketRoot(engine)
            }
        }

        // 通知权限只主动申请一次；之后用户可以在首页 Banner 里自己去开
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lifecycleScope.launch {
                if (!dev.pocket.opencode.util.DevicePermissions.hasNotificationPermission(this@MainActivity)) {
                    if (!engine.settings.snapshot().notificationAsked) {
                        engine.settings.update { it.copy(notificationAsked = true) }
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent, (application as PocketApp).engine)
    }

    /**
     * 桌面快捷方式（长按图标）进来的意图：启动服务 / 打开网页。
     *
     * 安全取舍（P2，低危，已评估后保持原行为）：
     * 这两个自定义 action 由**系统桌面**按 res/xml/shortcuts.xml 里的静态快捷方式构造 Intent
     * 发出，XML 里的东西是公开的，我们没法塞一个「只有本应用知道的 token」进去校验；
     * 而 MainActivity 是 LAUNCHER，exported 必须为 true，所以同机任意 App 显式指定组件时
     * 也能发出这两个 action（无法可靠区分来源）。
     * 影响面有限：START_SERVICE 只是启动用户本来就装着的服务，OPEN_WEB 只是把界面切到网页页，
     * 都不回传数据、不碰口令。真要收紧只能放弃静态快捷方式，改成自己用 PendingIntent 带 token 发。
     */
    private fun handleShortcutIntent(intent: Intent?, engine: PocketEngine) {
        when (intent?.action) {
            ACTION_START_SERVICE -> engine.startServer()
            ACTION_OPEN_WEB -> DeepLinks.request("web")
        }
    }

    private companion object {
        const val ACTION_START_SERVICE = "dev.pocket.opencode.START_SERVICE"
        const val ACTION_OPEN_WEB = "dev.pocket.opencode.OPEN_WEB"
    }
}
