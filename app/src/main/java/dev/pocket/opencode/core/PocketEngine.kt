package dev.pocket.opencode.core

import android.content.Context
import android.content.Intent
import dev.pocket.opencode.data.OpencodeApi
import dev.pocket.opencode.service.ServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用级“组合根”：所有长生命周期对象的唯一持有者。
 * UI 只从这里拿状态，不再自己 new。
 */
class PocketEngine(context: Context) {

    private val appContext = context.applicationContext
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    val paths = PocketPaths(appContext)
    val settings = SettingsStore(appContext)
    val downloads = Downloader(appContext)
    val net = NetworkBridge(appContext, paths, downloads)
    val repos = Repos(downloads)
    val proot = Proot(appContext, paths) { settings.settings.value }
    val shell = GuestShell(proot)
    val installer = Installer(appContext, paths, proot, shell, downloads, repos, net, settings)
    val server = ServerController(appContext, paths, proot, shell, downloads, net)
    val api = OpencodeApi(downloads, { server.status.value.port }) { settings.settings.value.serverPassword }
    val doctor = Doctor(appContext, paths, proot, shell, settings, installer)
    val deviceShell = DeviceShellManager(appContext, paths, settings, ShizukuBridge(appContext))

    /** 首次初始化（读设置、探测安装状态、拉齐设备桥）完成前，UI 不要做任何基于设置的决策。 */
    private val _initialized = MutableStateFlow(false)
    val initialized: StateFlow<Boolean> = _initialized.asStateFlow()

    suspend fun initialize() {
        settings.load()
        // 本机服务必须有口令：首启就生成，避免出现「无认证窗口期」
        runCatching { settings.ensureServerPassword() }
        installer.refresh()
        // 设备桥的 sync 会去 spawn/连接 Shizuku 用户服务（最长几秒），
        // 这里不要 await，免得拖慢冷启动；状态会自己刷新到 UI
        deviceShell.sync()
        _initialized.value = true
    }

    fun startServer(restart: Boolean = false) {
        // 先确保口令存在：服务一旦带口令启动，App 侧所有客户端（对话/网页/体检）都要用它
        scope.launch {
            runCatching { settings.ensureServerPassword() }
            val intent = Intent(appContext, ServerService::class.java).apply {
                action = if (restart) ServerService.ACTION_RESTART else ServerService.ACTION_START
            }
            runCatching { appContext.startForegroundService(intent) }
        }
    }

    fun stopServer() {
        val intent = Intent(appContext, ServerService::class.java).apply {
            action = ServerService.ACTION_STOP
        }
        // 服务没在跑时直接 startService 会被后台启动限制拦下，失败就当它已经停了
        runCatching { appContext.startService(intent) }
    }
}
