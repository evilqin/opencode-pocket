package dev.pocket.opencode.core

/**
 * 语义化版本比较（只比较数字段，够用且没有第三方依赖）。
 * 抽成独立对象是为了能在 JVM 单元测试里直接验证。
 */
object Versions {

    /** a > b 返回正数，a < b 返回负数，相等返回 0。 */
    fun compare(a: String, b: String): Int {
        val left = a.trimStart('v').split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val right = b.trimStart('v').split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        for (index in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l - r
        }
        return 0
    }

    /** 把 "24.04.10" 之类的版本串压成一个可直接比较的整数（每段最多 999）。 */
    fun score(version: String): Long =
        version.trimStart('v').split('.').fold(0L) { acc, part ->
            acc * 1000 + (part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0)
        }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
