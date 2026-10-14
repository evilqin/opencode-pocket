package dev.pocket.opencode.core

import org.json.JSONObject

/**
 * AppSettings ⇄ JSON 的编解码（纯函数，可单测）。
 *
 * 设置是长期演进的东西：加字段、改默认值、旧版本留下的 JSON 都得能读。
 * 规则写死在这里，并有单元测试保证「往返一致 + 缺字段用默认 + 坏 JSON 不崩」。
 */
object SettingsCodec {

    fun encode(value: AppSettings): String {
        val json = JSONObject()
        json.put("ubuntuMirror", value.ubuntuMirror)
        json.put("aptMirror", value.aptMirror)
        json.put("nodeMirror", value.nodeMirror)
        json.put("npmMirror", value.npmMirror)
        json.put("port", value.port)
        json.put("serverPassword", value.serverPassword)
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
        json.put("deviceShellGuard", value.deviceShellGuard)
        json.put("workspace", value.workspace)
        json.put("useExternalWorkspace", value.useExternalWorkspace)
        json.put("exposeSdcard", value.exposeSdcard)
        json.put("providerKeys", JSONObject(value.providerKeys as Map<*, *>))
        json.put("providerBaseUrls", JSONObject(value.providerBaseUrls as Map<*, *>))
        json.put("defaultModel", value.defaultModel)
        json.put("smallModel", value.smallModel)
        json.put("themeMode", value.themeMode)
        json.put("dynamicColor", value.dynamicColor)
        json.put("notificationAsked", value.notificationAsked)
        json.put("autoStartOnBoot", value.autoStartOnBoot)
        return json.toString()
    }

    fun decode(raw: String?): AppSettings {
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
                serverPassword = json.optString("serverPassword", defaults.serverPassword),
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
                deviceShellGuard = json.optBoolean("deviceShellGuard", defaults.deviceShellGuard),
                workspace = json.optString("workspace", defaults.workspace),
                useExternalWorkspace = json.optBoolean("useExternalWorkspace", defaults.useExternalWorkspace),
                exposeSdcard = json.optBoolean("exposeSdcard", defaults.exposeSdcard),
                providerKeys = json.optJSONObject("providerKeys").toStringMap(),
                providerBaseUrls = json.optJSONObject("providerBaseUrls").toStringMap(),
                defaultModel = json.optString("defaultModel", defaults.defaultModel),
                smallModel = json.optString("smallModel", defaults.smallModel),
                themeMode = json.optString("themeMode", defaults.themeMode),
                dynamicColor = json.optBoolean("dynamicColor", defaults.dynamicColor),
                notificationAsked = json.optBoolean("notificationAsked", defaults.notificationAsked),
                autoStartOnBoot = json.optBoolean("autoStartOnBoot", defaults.autoStartOnBoot),
            )
        } catch (_: Exception) {
            AppSettings()
        }
    }
}
