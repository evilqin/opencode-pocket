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
 * 全部用户设置。用一份 JSON 存进 DataStore，加字段时天然向后兼容（缺字段用默认值）。
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

    /** 低内存模式：关掉文件监视、关闭 LSP 自动下载，适合 4GB 以下机型。 */
    val lowMemory: Boolean = false,
    val disableWatcher: Boolean = false,
    val snapshot: Boolean = true,
    val share: String = "disabled",

    /** 权限策略：allow / ask / deny。 */
    val permissionEdit: String = "allow",
    val permissionBash: String = "allow",

    /** 兼容模式：给 proot 加 PROOT_NO_SECCOMP（个别内核上更稳）。 */
    val compatMode: Boolean = false,

    /** 安装 Node.js（可选，插件与 npm 项目会用到）。 */
    val installNode: Boolean = true,

    /** 设备 Shell 桥（Shizuku）：让 agent 能在手机本体上执行命令。 */
    val deviceShell: Boolean = false,
    val deviceShellPort: Int = 3090,
    val deviceShellToken: String = "",

    /** 工作区：手机上的真实目录，会挂进容器 /root/workspace。 */
    val workspace: String = "/sdcard/OpenCode",
    val useExternalWorkspace: Boolean = true,

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
)

class SettingsStore(private val context: Context) {

    private val store = context.settingsDataStore
    private val key = stringPreferencesKey("settings_json")

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    suspend fun load() {
        _settings.value = store.data.map { parse(it[key]) }.first()
    }

    suspend fun snapshot(): AppSettings {
        val current = store.data.first()[key]
        return parse(current)
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        var updated = AppSettings()
        store.edit { prefs ->
            updated = transform(parse(prefs[key]))
            prefs[key] = encode(updated)
        }
        _settings.value = updated
    }

    private fun parse(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return try {
            val json = JSONObject(raw)
            val defaults = AppSettings()
            AppSettings(
                ubuntuMirror = json.optString("ubuntuMirror", defaults.ubuntuMirror),
                aptMirror = json.optString("aptMirror", defaults.aptMirror),
                nodeMirror = json.optString("nodeMirror", defaults.nodeMirror),
                npmMirror = json.optString("npmMirror", defaults.npmMirror),
                port = json.optInt("port", defaults.port).coerceIn(1024, 65535),
                autoStart = json.optBoolean("autoStart", defaults.autoStart),
                lowMemory = json.optBoolean("lowMemory", defaults.lowMemory),
                disableWatcher = json.optBoolean("disableWatcher", defaults.disableWatcher),
                snapshot = json.optBoolean("snapshot", defaults.snapshot),
                share = json.optString("share", defaults.share),
                permissionEdit = json.optString("permissionEdit", defaults.permissionEdit),
                permissionBash = json.optString("permissionBash", defaults.permissionBash),
                compatMode = json.optBoolean("compatMode", defaults.compatMode),
                installNode = json.optBoolean("installNode", defaults.installNode),
                deviceShell = json.optBoolean("deviceShell", defaults.deviceShell),
                deviceShellPort = json.optInt("deviceShellPort", defaults.deviceShellPort).coerceIn(1024, 65535),
                deviceShellToken = json.optString("deviceShellToken", defaults.deviceShellToken),
                workspace = json.optString("workspace", defaults.workspace),
                useExternalWorkspace = json.optBoolean("useExternalWorkspace", defaults.useExternalWorkspace),
                providerKeys = json.optJSONObject("providerKeys").toStringMap(),
                providerBaseUrls = json.optJSONObject("providerBaseUrls").toStringMap(),
                defaultModel = json.optString("defaultModel", defaults.defaultModel),
                smallModel = json.optString("smallModel", defaults.smallModel),
                themeMode = json.optString("themeMode", defaults.themeMode),
                dynamicColor = json.optBoolean("dynamicColor", defaults.dynamicColor),
                notificationAsked = json.optBoolean("notificationAsked", defaults.notificationAsked),
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }

    private fun encode(value: AppSettings): String {
        val json = JSONObject()
        json.put("ubuntuMirror", value.ubuntuMirror)
        json.put("aptMirror", value.aptMirror)
        json.put("nodeMirror", value.nodeMirror)
        json.put("npmMirror", value.npmMirror)
        json.put("port", value.port)
        json.put("autoStart", value.autoStart)
        json.put("lowMemory", value.lowMemory)
        json.put("disableWatcher", value.disableWatcher)
        json.put("snapshot", value.snapshot)
        json.put("share", value.share)
        json.put("permissionEdit", value.permissionEdit)
        json.put("permissionBash", value.permissionBash)
        json.put("compatMode", value.compatMode)
        json.put("installNode", value.installNode)
        json.put("deviceShell", value.deviceShell)
        json.put("deviceShellPort", value.deviceShellPort)
        json.put("deviceShellToken", value.deviceShellToken)
        json.put("workspace", value.workspace)
        json.put("useExternalWorkspace", value.useExternalWorkspace)
        json.put("providerKeys", JSONObject(value.providerKeys as Map<*, *>))
        json.put("providerBaseUrls", JSONObject(value.providerBaseUrls as Map<*, *>))
        json.put("defaultModel", value.defaultModel)
        json.put("smallModel", value.smallModel)
        json.put("themeMode", value.themeMode)
        json.put("dynamicColor", value.dynamicColor)
        json.put("notificationAsked", value.notificationAsked)
        return json.toString()
    }
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
