package dev.pocket.opencode

import android.app.Application
import dev.pocket.opencode.core.PocketEngine
import dev.pocket.opencode.i18n.I18n
import dev.pocket.opencode.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PocketApp : Application() {

    lateinit var engine: PocketEngine
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 让 Service / 通知等非 Compose 代码在设置读出来之前也有合理的语言
        I18n.mode = I18n.systemLanguage()
        engine = PocketEngine(this)
        Notifications.ensureChannels(this)
        scope.launch { engine.initialize() }
    }

    companion object {
        lateinit var instance: PocketApp
            private set
    }
}
