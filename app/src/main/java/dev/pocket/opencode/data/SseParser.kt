package dev.pocket.opencode.data

/**
 * 极简 SSE 解析：一次喂一行，遇到空行就把累积的 `data:` 拼成一条事件返回。
 *
 * 为什么单独抽出来：事件流是对话页的命脉，而它的边界（多行 data、注释、
 * 其它字段、空载荷）全靠手写解析，很容易在某个服务端版本上悄悄丢事件。
 * 纯逻辑 → 可以在 JVM 单元测试里逐条锁住。
 */
class SseParser {

    private val buffer = StringBuilder()

    /** 喂一行（不含换行符）；返回非 null 表示这里凑齐了一条完整事件。 */
    fun feed(line: String): String? {
        when {
            line.isEmpty() -> {
                if (buffer.isEmpty()) return null
                val payload = buffer.toString()
                buffer.setLength(0)
                return payload
            }
            line.startsWith(":") -> {
                // 注释（有些服务端用它做心跳）
            }
            line.startsWith("data:") -> {
                val value = line.removePrefix("data:").removePrefix(" ")
                if (buffer.isNotEmpty()) buffer.append('\n')
                buffer.append(value)
            }
            else -> {
                // event:/id:/retry: 之类的字段暂时不需要
            }
        }
        return null
    }

    /** 连接断开时清掉半截事件（否则会跟重连后的第一条拼在一起）。 */
    fun reset() {
        buffer.setLength(0)
    }
}
