package dev.pocket.opencode

import android.Manifest
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
}
