package dev.pocket.opencode.core

/**
 * 危险命令防护：设备 Shell 桥（dexec）执行前的最后一道闸门。
 *
 * 设计原则（都来自安全审查踩过的坑）：
 *   - **不做「绝对拦截」的承诺**：这是提高门槛，不是沙箱。真正的安全边界是「用户手动开启 + 随时可关」。
 *   - **先规范化再匹配**：`rm '' -rf //`、`r\m -rf /`、`rm -rf ${IFS}/` 这类写法会先被还原成
 *     常规形式，再交给规则。只在原始文本上匹配等于没防护。
 *   - **按分隔符分段判定**：`settings put ...; pm uninstall x` 这种「白名单开头 + 危险尾巴」
 *     不能整条放行 —— allowList 只豁免命中的那一段。
 *   - **结构性构造直接拦**：子 shell / 命令替换 / 管道给 shell / eval 这类写法本身就是
 *     「把真实命令藏起来」，一律拦截（要用就让用户到设置里关掉防护）。
 */
object CommandGuard {

    /** 允许列表：只豁免「命中了白名单的那一段」，不再短路整条命令。 */
    private val allowList = listOf(
        // 幻影进程监控的关闭命令：官方文档推荐，属于正常操作
        Regex("""settings\s+put\s+global\s+settings_enable_monitor_phantom_procs\b"""),
    )

    private data class Rule(val pattern: Regex, val reason: String)

    /**
     * 结构性规则：与具体动词无关，「怎么执行」本身就可疑的写法。
     * 在**整条命令**（规范化后）上匹配，不受分段影响。
     */
    private val structuralRules = listOf(
        Rule(Regex("""\b(sh|bash|zsh|ksh|dash)\s+-c\b"""), "通过子 shell 解释字符串执行（sh -c）"),
        Rule(Regex("""\|\s*(sh|bash|zsh|ksh)\b"""), "把内容管道给 shell 执行"),
        Rule(Regex("""\b(eval|exec)\b\s"""), "动态执行（eval/exec）"),
        Rule(Regex("""\$\("""), "命令替换 $() ——可能隐藏真实命令"),
        Rule(Regex("""`"""), "反引号命令替换 ——可能隐藏真实命令"),
        Rule(Regex("""\bfind\b[^;|]*(?:-delete\b|-exec\b)"""), "find 的删除/执行动作"),
        Rule(Regex("""\bxargs\b[^;|]*\b(rm|sh|bash)\b"""), "xargs 调用危险命令"),
    )

    /** 常规规则：按 `;`/`&&`/`|`/`&` 分段后逐段匹配。 */
    private val rules = listOf(
        Rule(Regex("""\brm\b(\s+(-{1,2}[A-Za-z][\w=.-]*))*\s+/(\*)?(\s|$)"""), "删除文件系统根目录"),
        Rule(
            Regex("""\brm\b(\s+(-{1,2}[A-Za-z][\w=.-]*))*\s+/(data|system|vendor|sdcard|storage|etc|bin|usr|lib|opt|root)(/\*)?(\s|$)"""),
            "删除系统/共享存储目录",
        ),
        Rule(
            Regex("""\brm\b(\s+(-{1,2}[A-Za-z][\w=.-]*))*\s+/sdcard/(DCIM|Pictures|Movies|Music|Android)(/|\s|$)"""),
            "删除相册/系统共享目录",
        ),
        Rule(Regex("""\brm\b[^;|]*\s/[^;|]*\.\."""), "rm 的绝对路径目标里带 ..（路径穿越）"),
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
        Rule(Regex("""\s*/(system|vendor)/"""), "覆盖系统分区文件"),
        Rule(Regex("""\bkillall\b|\bkill\s+-9\s+1\b"""), "批量杀进程"),
        // 「动词被拆出来单独用」的间接形式，补在最后：pm 等包名被变量绕开也躲不过这层
        Rule(Regex("""\buninstall\b"""), "卸载应用（uninstall）"),
        Rule(Regex("""\bwipe\s+(data|userdata|cache)\b"""), "擦除数据（wipe）"),
    )

    /**
     * 检查一条命令；返回 null 表示放行，否则返回拒绝原因（直接给 agent 看）。
     */
    fun check(command: String): String? {
        val flattened = command.replace('\r', ' ').replace('\n', ' ')
        val normalizedWhole = normalize(flattened)

        // 1) 结构性构造：整条命令上判定（这些写法本身就是「藏命令」，不享受白名单）
        for (rule in structuralRules) {
            if (rule.pattern.containsMatchIn(normalizedWhole)) return describe(rule)
        }

        // 2) 逐段判定：白名单只豁免命中的那一段
        for (rawSegment in flattened.split(Regex("""[;&|]+"""))) {
            val segment = rawSegment.trim()
            if (segment.isEmpty()) continue
            val normalized = normalize(segment)
            if (allowList.any { it.containsMatchIn(segment) || it.containsMatchIn(normalized) }) continue
            for (rule in rules) {
                if (rule.pattern.containsMatchIn(normalized) || rule.pattern.containsMatchIn(segment)) {
                    return describe(rule)
                }
            }
        }
        return null
    }

    private fun describe(rule: Rule): String =
        "${rule.reason}（命中防护规则 ${rule.pattern.pattern.take(48)}）"

    /**
     * 规范化：把常见的「绕写」还原成常规形式，再交给规则匹配。
     * `rm '' -rf //` → `rm -rf /`；`rm -rf ${IFS}/` → `rm -rf /`；`r\m -rf /` → `rm -rf /`
     */
    private fun normalize(text: String): String {
        var out = text
        out = out.replace("\${IFS}", " ").replace("\$IFS", " ")
        out = out.replace("\${IFS:- }", " ")
        out = out.replace(Regex("""['"]"""), "")            // 去引号（含空串引号拼接）
        out = out.replace(Regex("""\\(?=[\w/.\-${'$'}])"""), "") // 去转义反斜杠
        out = out.replace(Regex("""/+"""), "/")             // 折叠 //、///
        out = out.replace(Regex("""[ \t\u00A0]+"""), " ")   // 折叠空白（含不换行空格）
        return out.trim()
    }

    val ruleCount: Int get() = rules.size + structuralRules.size
}
