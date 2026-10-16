package dev.pocket.opencode.core

import java.io.File

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

    /** 共享存储下的 `Android/data` / `Android/obb` 装的是**别的应用**的私有数据，不该被容器代理读写。 */
    private val forbiddenSegments = listOf("/Android/data", "/Android/obb")

    /**
     * @return null 表示合法；否则返回给用户看的错误说明。
     */
    fun validate(path: String): String? {
        val trimmed = path.trim().trimEnd('/')
        if (trimmed.isEmpty()) return "路径不能为空"
        if (trimmed.length > 200) return "路径太长了"
        if (!trimmed.startsWith("/")) return "请填绝对路径（例如 /sdcard/OpenCode）"
        // `.` / `..` 这样的段会被文件系统解析掉：`/sdcard/.` 就是 /sdcard 本体，
        // 放行等于把整个共享存储 bind 进容器，和 exposeSdcard=false 的承诺矛盾
        if (trimmed.split('/').any { it == "." || it == ".." }) return "路径里不能有 . 或 .. 这样的段"
        if (trimmed in listOf("/sdcard", "/storage", "/storage/emulated/0", "/", "/data", "/system")) {
            return "不能把整个 $trimmed 当工作区，请选它下面的具体目录"
        }
        if (forbiddenSegments.any { trimmed.contains(it, ignoreCase = true) }) {
            return "不能选 Android/data 或 Android/obb 里的目录（那是其他应用的私有数据）"
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

    /**
     * 解析成文件系统上的真实路径（解析软链、`.`、`..`、重复斜杠）。
     *
     * 字符串合法 ≠ 真的落在允许的目录里：比如 `/sdcard/OpenCode/../..`、
     * 或者用户自己建的指向 `/` 的软链。bind 之前必须过这一步。
     *
     * 纯 JVM 逻辑（只用 java.io.File），不依赖 Android，方便单测。
     *
     * @return 规范化后的绝对路径；解析失败、或者解析完又变成裸根 / 非法目录时返回 null。
     */
    fun canonicalize(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val real = runCatching { File(trimmed).canonicalFile.absolutePath }.getOrNull() ?: return null
        val canonical = real.trimEnd('/')
        if (canonical.isEmpty()) return null // 裸根 "/"
        if (validate(canonical) != null) return null
        return canonical
    }
}
