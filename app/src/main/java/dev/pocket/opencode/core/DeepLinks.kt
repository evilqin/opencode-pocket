package dev.pocket.opencode.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 从应用外（快捷方式、通知、桌面图标长按）进来的意图，交给 UI 决定怎么跳。
 *
 * 用单例而不是 Intent 传参：Activity 是 singleTask，第二次进来的 Intent
 * 走 onNewIntent，用 Flow 更省事。
 */
object DeepLinks {

    /** 目前支持：`web`（打开网页页）、`setup`（打开安装页）。 */
    private val _requested = MutableStateFlow<String?>(null)
    val requested: StateFlow<String?> = _requested.asStateFlow()

    fun request(target: String) {
        _requested.value = target
    }

    fun consume() {
        _requested.value = null
    }
}
