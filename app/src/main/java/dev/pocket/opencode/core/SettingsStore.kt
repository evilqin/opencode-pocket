package dev.pocket.opencode.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 全部用户设置。
 *
 * 编解码（含默认值与向后兼容）在 [SettingsCodec] 里，并有单元测试锁着；
 * 加字段时记得两处都补：这里的 data class + Codec 的 encode/decode。
 */
data class AppSettings(
    /** 镜像源（id 见 [Mirrors]）。 */
    val ubuntuMirror: String = "tuna",
    val aptMirror: String = "tuna",
    val nodeMirror: String = "npmmirror",
    val npmMirror: String = "npmmirror",

    /** 服务端口与自启。 */
    val port: Int = 4096,
    val autoStart: Boolean = true,

    /** 本机 HTTP 服务的口令（自动生成；同机其它 App 也能访问 127.0.0.1，所以默认必须有）。 */
    val serverPassword: String = "",

    /** 低内存模式：关掉文件监视、关闭 LSP 自动下载，适合 4GB 以下机型。 */
    val lowMemory: Boolean = false,
    val disableWatcher: Boolean = false,
    val snapshot: Boolean = true,
    val share: String = "disabled",

    /**
     * 权限策略：allow / ask / deny。
     *
     * 默认是 ask 而不是 allow：容器里的 agent 是「读外部内容 → 执行命令」的模型，
     * 一个恶意仓库的 README 就可能诱导它去删文件或提权；改文件与执行命令都应该先问一句。
     */
    val permissionEdit: String = "ask",
    val permissionBash: String = "ask",

    /** 兼容模式：给 proot 加 PROOT_NO_SECCOMP（个别内核上更稳）。 */
    val compatMode: Boolean = false,

    /** 安装 Node.js（可选，插件与 npm 项目会用到）。 */
    val installNode: Boolean = true,

    /** 设备 Shell 桥（Shizuku）：让 agent 能在手机本体上执行命令。 */
    val deviceShell: Boolean = false,
    val deviceShellPort: Int = 3090,
    val deviceShellToken: String = "",
    /** 危险命令防护（rm -rf /、pm uninstall、dd 写块设备…），默认开启。 */
    val deviceShellGuard: Boolean = true,

    /** 工作区：手机上的真实目录，会挂进容器 /root/workspace。 */
    val workspace: String = "/sdcard/OpenCode",
    val useExternalWorkspace: Boolean = true,

    /**
     * 是否把整个共享存储（/sdcard）也挂进容器。
     * 默认关闭：容器里的 agent 只需要看到你选的工作区，不该顺带看到相册、聊天记录导出等。
     */
    val exposeSdcard: Boolean = false,

    /** Provider API Key（写进容器里的 opencode.json）。 */
    val providerKeys: Map<String, String> = emptyMap(),
    /** 自定义 baseURL（OpenAI 兼容中转等）。 */
    val providerBaseUrls: Map<String, String> = emptyMap(),
    val defaultModel: String = "",
    val smallModel: String = "",

    /** 外观。 */
    val themeMode: String = "dark",
    val dynamicColor: Boolean = false,

    /** 是否已经弹过一次通知权限（避免每次冷启动都打扰用户）。 */
    val notificationAsked: Boolean = false,

    /** 开机后自动把服务拉起来（默认关闭；Android 12+ 对后台启动前台服务有额外限制）。 */
    val autoStartOnBoot: Boolean = false,
)

class SettingsStore(private val context: Context) {

    private val store = context.settingsDataStore
    private val key = stringPreferencesKey("settings_json")

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        _settings.value = store.data.map { parse(it[key]) }.first()
    }

    suspend fun snapshot(): AppSettings = parse(store.data.first()[key])

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs ->
            prefs[key] = SettingsCodec.encode(transform(parse(prefs[key])))
        }
        // 以磁盘为准刷新内存快照：并发 update 时，edit 内部的 transform 是基于最新值，
        // 不能再用「本协程算出的 updated」去覆盖，否则会写回旧快照。
        _settings.value = snapshot()
    }

    /**
     * 保证本机 HTTP 服务有口令（首次调用时随机生成）。
     *
     * 为什么必须有：Android 的 127.0.0.1 对**同机所有 App** 开放，
     * opencode 服务若不带口令，任何 App 都能驱动我们的 agent。
     */
    suspend fun ensureServerPassword(): String {
        var password = ""
        // 「读-判-生成-写」全放进同一次 edit：DataStore 的 edit 是原子的，
        // 并发调用只会有一个真正生成，不会各写各的口令。
        store.edit { prefs ->
            val current = parse(prefs[key])
            val existing = current.serverPassword
            if (existing.isBlank()) {
                // 只有当口令「为空/空白」时才生成；用户自定义的短口令不再被静默重置。
                password = generateServerPassword()
                prefs[key] = SettingsCodec.encode(current.copy(serverPassword = password))
            } else {
                password = existing
            }
        }
        _settings.value = snapshot()
        return password
    }

    private fun generateServerPassword(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parse(raw: String?): AppSettings = SettingsCodec.decode(raw)
}

internal fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (name in keys()) {
        val value = optString(name, "")
        if (value.isNotBlank()) result[name] = value
    }
    return result
}
