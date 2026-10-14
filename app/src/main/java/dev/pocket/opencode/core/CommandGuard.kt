package dev.pocket.opencode.core

/**
 * 危险命令防护：设备 Shell 桥在执行前过一遍这份规则。
 *
 * 为什么需要它：容器里的 agent 是「读外部内容 → 执行命令」的模型，
 * 一个恶意仓库的 README 就可能让它去 `pm uninstall` 或 `dd of=/dev/block/...`。
 * 用户在设置里可以关掉防护（`deviceShellGuard=false`），但默认必须拦。
 *
 * 纯逻辑，规则由单元测试锁着。
 */
object CommandGuard {

    /** 允许放行的例外（例如官方推荐的关闭幻影进程限制那条）。 */
    private val allowList = listOf(
        Regex("""settings\s+put\s+global\s+settings_enable_monitor_phantom_procs\b"""),
    )

    private data class Rule(val pattern: Regex, val reason: String)

    private val rules = listOf(
        // 只匹配「删到根」这一种：rm -rf / 或 rm -rf /*（不能把 rm -f /data/... 也算进来）
        Rule(Regex("""\brm\s+(-[a-zA-Z]+\s+)*/(\*)?(\s|$)"""), "删除文件系统根目录"),
        // 系统目录/共享存储的“根”：/data、/system、/vendor、/sdcard 本身
        Rule(
            Regex("""\brm\s+(-[a-zA-Z]+\s+)*/(data|system|vendor|sdcard|storage)(/\*)?(\s|$)"""),
            "删除系统/共享存储目录",
        ),
        // 用户看得见的媒体目录：删了就是照片/视频没了
        Rule(
            Regex("""\brm\s+(-[a-zA-Z]+\s+)*/sdcard/(DCIM|Pictures|Movies|Music|Android)(/|\s|$)"""),
            "删除相册/系统共享目录",
        ),
        Rule(Regex("""\bmkfs(\.\w+)?\b"""), "格式化文件系统"),
        Rule(Regex("""\bdd\b[^\n]*\bof=/dev/block/"""), "直接写块设备"),
        Rule(Regex("""\bdd\b[^\n]*\bof=/dev/\w"""), "直接写设备节点"),
        Rule(Regex("""\bpm\s+(uninstall|disable|clear|hide|suspend)\b"""), "卸载/禁用/清空应用"),
        Rule(Regex("""\bam\s+force-stop\s+com\.android\b"""), "强停系统组件"),
        Rule(Regex("""\b(content|settings)\s+(delete|put)\b"""), "修改系统设置/数据库（幻影进程那条除外）"),
        Rule(Regex("""\b(reboot|shutdown|halt|poweroff)\b"""), "重启/关机"),
        Rule(Regex("""\bmount\b[^\n]*\b(remount|rw)\b"""), "重新挂载分区为可写"),
        Rule(Regex("""\bchmod\b[^\n]*\s/(system|vendor|data)\b"""), "修改系统目录权限"),
        Rule(Regex("""\b(wipe|fastboot|flash)\b"""), "刷机/擦除类命令"),
        Rule(Regex("""\bsu\b\s+-?c?\s"""), "提权（桥已经是 shell/root 身份，无需 su）"),
        Rule(Regex(""">\s*/(system|vendor)/"""), "覆盖系统分区文件"),
        Rule(Regex("""\bkillall\b|\bkill\s+-9\s+1\b"""), "批量杀进程"),
    )

    /**
     * 检查一条命令；被拦下时返回给用户/agent 看的原因，通过则返回 null。
     */
    fun check(command: String): String? {
        val normalized = command.replace('\r', ' ').replace('\n', ' ')
        if (allowList.any { it.containsMatchIn(normalized) }) return null
        for (rule in rules) {
            if (rule.pattern.containsMatchIn(normalized)) {
                return "${rule.reason}（命中防护规则 ${rule.pattern.pattern.take(48)}）"
            }
        }
        return null
    }

    /** 给 UI 展示用的规则数量。 */
    val ruleCount: Int get() = rules.size
}
