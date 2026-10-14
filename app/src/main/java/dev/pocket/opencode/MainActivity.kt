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

    /** 桌面快捷方式（长按图标）进来的意图：启动服务 / 打开网页。 */
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
