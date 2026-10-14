package dev.pocket.opencode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedDiffTest {

    @Test
    fun `内容相同时没有任何块`() {
        assertTrue(UnifiedDiff.compute("a\nb\nc", "a\nb\nc").isEmpty())
    }

    @Test
    fun `单行修改会渲染成一减一加`() {
        val lines = UnifiedDiff.compute("a\nb\nc", "a\nB\nc", context = 1)
        val text = UnifiedDiff.render(lines)
        assertTrue(text.contains("-b"))
        assertTrue(text.contains("+B"))
        assertTrue(text.contains("@@"))
        // 上下文行保留
        assertTrue(text.contains(" a"))
        assertTrue(text.contains(" c"))
    }

    @Test
    fun `新增文件（原内容为空）全是加号`() {
        val lines = UnifiedDiff.compute("", "x\ny")
        val plus = lines.count { it is UnifiedDiff.Line.Add }
        assertTrue("应该有两行新增，实际 $plus", plus >= 2)
        assertEquals(0, lines.count { it is UnifiedDiff.Line.Remove })
    }

    @Test
    fun `删除内容全是减号`() {
        val lines = UnifiedDiff.compute("x\ny", "")
        assertTrue(lines.count { it is UnifiedDiff.Line.Remove } >= 2)
        assertEquals(0, lines.count { it is UnifiedDiff.Line.Add })
    }

    @Test
    fun `相隔很远的改动会拆成多个块`() {
        val before = (1..40).joinToString("\n") { "line$it" }
        val after = (1..40).joinToString("\n") { if (it == 2 || it == 38) "changed$it" else "line$it" }
        val hunks = UnifiedDiff.compute(before, after, context = 3).filterIsInstance<UnifiedDiff.Line.Hunk>()
        assertEquals(2, hunks.size)
    }

    @Test
    fun `相邻的改动会合并成一个块`() {
        val before = (1..20).joinToString("\n") { "line$it" }
        val after = (1..20).joinToString("\n") { if (it in 8..11) "changed$it" else "line$it" }
        val hunks = UnifiedDiff.compute(before, after, context = 3).filterIsInstance<UnifiedDiff.Line.Hunk>()
        assertEquals(1, hunks.size)
    }

    @Test
    fun `块头里的行号正确`() {
        val lines = UnifiedDiff.compute("a\nb\nc\nd", "a\nb\nX\nd", context = 1)
        val hunk = lines.first() as UnifiedDiff.Line.Hunk
        // 改动在第 3 行，上下文 1 行 -> 从第 2 行开始，原 3 行新 3 行
        assertTrue("块头不对：${hunk.text}", hunk.text.contains("-2,3"))
        assertTrue("块头不对：${hunk.text}", hunk.text.contains("+2,3"))
    }

    @Test
    fun `超大文件不会被差分（直接给提示）`() {
        val big = (1..(UnifiedDiff.MAX_LINES + 10)).joinToString("\n") { "line$it" }
        val lines = UnifiedDiff.compute(big, big + "\nextra")
        assertEquals(1, lines.size)
        assertTrue(lines.first() is UnifiedDiff.Line.Note)
    }

    @Test
    fun `末尾空行的增删能被识别`() {
        val lines = UnifiedDiff.compute("a\nb\n", "a\nb")
        assertTrue(lines.any { it is UnifiedDiff.Line.Remove })
    }

    @Test
    fun `render 会加上标准前缀`() {
        val rendered = UnifiedDiff.render(
            listOf(
                UnifiedDiff.Line.Hunk("@@ -1,2 +1,2 @@"),
                UnifiedDiff.Line.Context("same"),
                UnifiedDiff.Line.Remove("old"),
                UnifiedDiff.Line.Add("new"),
            ),
        )
        assertEquals("@@ -1,2 +1,2 @@\n same\n-old\n+new", rendered)
    }

    @Test
    fun `中文与空行混排不会错位`() {
        val before = "第一行\n\n第三行\n"
        val after = "第一行\n\n第三行改了\n"
        val lines = UnifiedDiff.compute(before, after)
        val text = UnifiedDiff.render(lines)
        assertTrue(text.contains("-第三行"))
        assertTrue(text.contains("+第三行改了"))
    }

    @Test
    fun `纯新增行（无删除）`() {
        val lines = UnifiedDiff.compute("a\nc", "a\nb\nc")
        assertEquals(1, lines.count { it is UnifiedDiff.Line.Add })
        assertEquals(0, lines.count { it is UnifiedDiff.Line.Remove })
        assertEquals(2, lines.count { it is UnifiedDiff.Line.Context })
    }
}
