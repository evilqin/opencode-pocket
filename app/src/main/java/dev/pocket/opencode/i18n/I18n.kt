package dev.pocket.opencode.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 极简双语支持。
 *
 * 为什么不走 Android 资源：这个 App 的界面文案绝大多数写死在 Compose 代码里，
 * 而且需要「应用内即时切换语言」（不重建 Activity）。这里用一个 Compose
 * snapshot state 保存当前语言，[t] 在组合期间读取它，切换语言会自然触发重组；
 * 同一套 [I18n] 也能被 Service / 安装器等非 Compose 代码使用。
 *
 * 约定：调用方把中文和英文写在同一个位置（`t("停止", "Stop")`），
 * 这样不会出现「翻译文件里缺一条、运行时回退成 key」的问题。
 */
object I18n {

    const val SYSTEM = "system"
    const val CHINESE = "zh"
    const val ENGLISH = "en"

    /** 默认中文：单元测试与后台逻辑在没有人设置语言时保持稳定。 */
    private var current by mutableStateOf(CHINESE)

    /** 当前语言模式：system / zh / en。 */
    var mode: String
        get() = current
        set(value) {
            val normalized = normalize(value)
            if (current != normalized) current = normalized
        }

    val isEnglish: Boolean
        get() = when (current) {
            ENGLISH -> true
            CHINESE -> false
            else -> !Locale.getDefault().language.equals("zh", ignoreCase = true)
        }

    /** 把设置里可能出现的各种写法收敛成三种值。 */
    fun normalize(value: String?): String = when (value?.trim()?.lowercase(Locale.ROOT)) {
        ENGLISH, "en-us", "en-gb", "en_us", "en_gb" -> ENGLISH
        CHINESE, "zh-cn", "zh-hans", "zh-hant", "zh-tw", "zh_cn", "zh_tw" -> CHINESE
        else -> SYSTEM
    }

    /** 系统语言对应的具体语言（用于首次启动时给一个合理默认）。 */
    fun systemLanguage(): String =
        if (Locale.getDefault().language.equals("zh", ignoreCase = true)) CHINESE else ENGLISH

    fun t(zh: String, en: String): String = if (isEnglish) en else zh
}

/** 语法糖：`Text(t("停止", "Stop"))`。 */
fun t(zh: String, en: String): String = I18n.t(zh, en)
