package dev.pocket.opencode.core

import android.content.Context
import android.content.Intent
import dev.pocket.opencode.data.OpencodeApi
import dev.pocket.opencode.service.ServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级“组合根”：所有长生命周期对象的唯一持有者。
 * UI 只从这里拿状态，不再自己 new。
 */
class PocketEngine(context: Context) {

    private val appContext = context.applicationContext

    val paths = PocketPaths(appContext)
    val settings = SettingsStore(appContext)
    val downloads = Downloader(appContext)
    val net = NetworkBridge(appContext, paths, downloads)
    val repos = Repos(downloads)
    val proot = Proot(appContext, paths) { settings.settings.value }
    val shell = GuestShell(proot)
    val installer = Installer(appContext, paths, proot, shell, downloads, repos, net, settings)
    val server = ServerController(appContext, paths, proot, shell, downloads, net)
    val api = OpencodeApi(downloads) { server.status.value.port }
    val doctor = Doctor(appContext, paths, proot, shell, settings, installer)
    val deviceShell = DeviceShellManager(appContext, paths, settings, ShizukuBridge(appContext))

    /** 首次初始化（读设置、探测安装状态、拉齐设备桥）完成前，UI 不要做任何基于设置的决策。 */
    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    suspend fun initialize() {
        settings.load()
        installer.refresh()
        deviceShell.syncNow()
        _initialized.value = true
    }

    fun startServer(restart: Boolean = false) {
        val intent = Intent(appContext, ServerService::class.java).apply {
            action = if (restart) ServerService.ACTION_RESTART else ServerService.ACTION_START
        }
        appContext.startForegroundService(intent)
    }

    fun stopServer() {
        val intent = Intent(appContext, ServerService::class.java).apply {
            action = ServerService.ACTION_STOP
        }
        // 服务没在跑时直接 startService 会被后台启动限制拦下，失败就当它已经停了
        runCatching { appContext.startService(intent) }
    }
}
