package dev.pocket.opencode.core

/**
 * 工作区路径策略（纯逻辑，可单测）。
 *
 * 工作区会被直接 bind 进容器 /root/workspace，容器里的 agent 默认可以读写它；
 * 所以绝不能让用户（或被社工的用户）把它设成 `/`、`/data`、`/storage` 这种
 * 「一删就是大事故」的目录。
 */
object WorkspacePolicy {

    /** 允许的根：共享存储下的具体子目录（自带外置卡的 /storage/XXXX-XXXX 也放行）。 */
    private val allowedRoots = listOf(
        Regex("""^/sdcard/[^/]+(/.*)?$"""),
        Regex("""^/storage/emulated/0/[^/]+(/.*)?$"""),
        Regex("""^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/[^/]+(/.*)?$"""),
    )

    /**
     * @return null 表示合法；否则返回给用户看的错误说明。
     */
    fun validate(path: String): String? {
        val trimmed = path.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "路径不能为空"
        if (trimmed.length > 200) return "路径太长了"
        if (!trimmed.startsWith("/")) return "请填绝对路径（例如 /sdcard/OpenCode）"
        if (trimmed.split('/').any { it == ".." }) return "路径里不能有 .."
        if (trimmed in listOf("/sdcard", "/storage", "/storage/emulated/0", "/", "/data", "/system")) {
            return "不能把整个 $trimmed 当工作区，请选它下面的具体目录"
        }
        if (allowedRoots.none { it.matches(trimmed) }) {
            return "只支持共享存储里的目录（/sdcard/… 或 /storage/…）"
        }
        return null
    }

    fun isValid(path: String): Boolean = validate(path) == null

    /** 规范化：去空白、去尾部斜杠；不合法时返回默认工作区。 */
    fun normalize(path: String, fallback: String = "/sdcard/OpenCode"): String {
        val trimmed = path.trim().trimEnd('/')
        return if (isValid(trimmed)) trimmed else fallback
    }
}
