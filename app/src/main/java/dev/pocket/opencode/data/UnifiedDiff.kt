package dev.pocket.opencode.data

/**
 * 统一差分（unified diff）：给「本次改动」视图用。
 *
 * 纯逻辑、有单测：agent 改完文件后用户最想看的就是「到底改了哪几行」，
 * 而这段逻辑一旦错位（上下文行数、块合并）改动的可读性就全毁了。
 *
 * 性能：LCS 是 O(n*m)，所以对超大文件先做上限保护（超过 [MAX_LINES] 直接给个提示块）。
 */
object UnifiedDiff {

    const val MAX_LINES = 2000

    sealed interface Line {
        /** 差分块头，例如 `@@ -12,6 +12,9 @@` */
        data class Hunk(val text: String) : Line

        /** 上下文行（未改动）。 */
        data class Context(val text: String) : Line

        /** 新增行。 */
        data class Add(val text: String) : Line

        /** 删除行。 */
        data class Remove(val text: String) : Line

        /** 超大/不可比时的提示。 */
        data class Note(val text: String) : Line
    }

    /** 空文本按 0 行处理（新建文件的 before 就是空串，不该算作「删掉一个空行」）。 */
    private fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.split('\n')

    /** 计算 before/after 的统一差分（默认 3 行上下文）。 */
    fun compute(before: String, after: String, context: Int = 3): List<Line> {
        if (before == after) return emptyList()
        val a = splitLines(before)
        val b = splitLines(after)
        if (a.size > MAX_LINES || b.size > MAX_LINES) {
            return listOf(
                Line.Note("文件太大（${a.size} -> ${b.size} 行），这里只显示摘要；完整内容请用编辑器或 git diff 查看"),
            )
        }

        val ops = lcsOps(a, b)
        return mergeIntoHunks(ops, context)
    }

    /** 差分操作。 */
    private sealed interface Op {
        data class Same(val text: String) : Op
        data class Add(val text: String) : Op
        data class Remove(val text: String) : Op
    }

    private fun lcsOps(a: List<String>, b: List<String>): List<Op> {
        // 经典 LCS 表：a.size * b.size 在最坏情况下也要有上限（上面已限制 2000 行）
        val n = a.size
        val m = b.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                table[i][j] = if (a[i] == b[j]) {
                    table[i + 1][j + 1] + 1
                } else {
                    maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
        }
        val ops = ArrayList<Op>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    ops += Op.Same(a[i]); i++; j++
                }
                table[i + 1][j] >= table[i][j + 1] -> {
                    ops += Op.Remove(a[i]); i++
                }
                else -> {
                    ops += Op.Add(b[j]); j++
                }
            }
        }
        while (i < n) {
            ops += Op.Remove(a[i]); i++
        }
        while (j < m) {
            ops += Op.Add(b[j]); j++
        }
        return ops
    }

    /** 把操作序列切成带上下文的块（相隔很近的改动合并到一个块里）。 */
    private fun mergeIntoHunks(ops: List<Op>, context: Int): List<Line> {
        val changed = ops.indices.filter { ops[it] !is Op.Same }
        if (changed.isEmpty()) return emptyList()

        val ranges = ArrayList<IntRange>()
        var start = maxOf(0, changed.first() - context)
        var end = minOf(ops.lastIndex, changed.first() + context)
        for (index in changed.drop(1)) {
            val from = maxOf(0, index - context)
            val to = minOf(ops.lastIndex, index + context)
            if (from <= end + 1) {
                end = maxOf(end, to)
            } else {
                ranges += start..end
                start = from
                end = to
            }
        }
        ranges += start..end

        val lines = ArrayList<Line>()
        // 行号：需要按块头展示「原文件/新文件」的起始行
        var oldLineNo = 1
        var newLineNo = 1
        val lineNumbers = IntArray(ops.size)
        val newLineNumbers = IntArray(ops.size)
        for (index in ops.indices) {
            lineNumbers[index] = oldLineNo
            newLineNumbers[index] = newLineNo
            when (ops[index]) {
                is Op.Same -> {
                    oldLineNo++; newLineNo++
                }
                is Op.Remove -> oldLineNo++
                is Op.Add -> newLineNo++
            }
        }

        for (range in ranges) {
            val oldCount = range.count { ops[it] !is Op.Add }
            val newCount = range.count { ops[it] !is Op.Remove }
            lines += Line.Hunk(
                "@@ -${lineNumbers[range.first]},$oldCount +${newLineNumbers[range.first]},$newCount @@",
            )
            for (index in range) {
                when (val op = ops[index]) {
                    is Op.Same -> lines += Line.Context(op.text)
                    is Op.Add -> lines += Line.Add(op.text)
                    is Op.Remove -> lines += Line.Remove(op.text)
                }
            }
        }
        return lines
    }

    /** 给文本视图用的带前缀形式（+/-/空格）。 */
    fun render(lines: List<Line>): String = lines.joinToString("\n") { line ->
        when (line) {
            is Line.Hunk -> line.text
            is Line.Context -> " ${line.text}"
            is Line.Add -> "+${line.text}"
            is Line.Remove -> "-${line.text}"
            is Line.Note -> line.text
        }
    }
}
