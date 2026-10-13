package dev.pocket.opencode

import android.app.Application
import dev.pocket.opencode.core.PocketEngine
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
        engine = PocketEngine(this)
        Notifications.ensureChannels(this)
        scope.launch { engine.initialize() }
    }

    companion object {
        lateinit var instance: PocketApp
            private set
    }
}
