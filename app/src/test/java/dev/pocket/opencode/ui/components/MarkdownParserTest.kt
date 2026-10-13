package dev.pocket.opencode.ui.components

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    private val codeColor = Color(0xFF22D3EE)
    private val codeBackground = Color(0xFF182741)
    private val linkColor = Color(0xFF22D3EE)

    @Test
    fun `解析代码块并保留语言`() {
        val blocks = MarkdownParser.parse(
            """
            先看一下：

            ```kotlin
            fun main() {
                println("hi")
            }
            ```

            就这些。
            """.trimIndent(),
        )
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        val code = blocks[1] as MdBlock.Code
        assertEquals("kotlin", code.language)
        assertTrue(code.text.contains("fun main()"))
        // 收尾的 ``` 不能留在代码里
        assertTrue(!code.text.contains("```"))
    }

    @Test
    fun `解析标题的层级`() {
        val blocks = MarkdownParser.parse("# 一级\n## 二级\n### 三级\n")
        assertEquals(3, blocks.size)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertEquals(2, (blocks[1] as MdBlock.Heading).level)
        assertEquals("三级", (blocks[2] as MdBlock.Heading).text)
    }

    @Test
    fun `解析列表与引用`() {
        val blocks = MarkdownParser.parse("- 第一项\n- 第二项\n\n> 引用一行\n> 引用两行\n")
        assertEquals(2, blocks.size)
        val bullet = blocks[0] as MdBlock.Bullet
        assertEquals(listOf("第一项", "第二项"), bullet.items)
        val quote = blocks[1] as MdBlock.Quote
        assertEquals("引用一行\n引用两行", quote.text)
    }

    @Test
    fun `多行段落合并成一个块`() {
        val blocks = MarkdownParser.parse("第一行\n第二行\n\n新段落")
        assertEquals(2, blocks.size)
        assertEquals("第一行\n第二行", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("新段落", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun `代码块里的井号不当标题`() {
        val blocks = MarkdownParser.parse("```bash\n# 这是注释\nls\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdBlock.Code
        assertTrue(code.text.contains("# 这是注释"))
    }

    @Test
    fun `行内代码 加粗与链接都能识别`() {
        val text = "用 `proot` 启动，**注意** 权限，见 [文档](https://opencode.ai/docs/) 和 *斜体*。"
        val annotated = MarkdownParser.inline(text, codeColor, codeBackground, linkColor)
        val plain = annotated.text
        assertEquals("用 proot 启动，注意 权限，见 文档 和 斜体。", plain)

        // 至少要有：代码样式、加粗、链接、斜体
        assertTrue(annotated.spanStyles.size >= 4)
        assertTrue(annotated.getLinkAnnotations(0, plain.length).isNotEmpty())
    }

    @Test
    fun `没闭合的标记原样保留`() {
        val annotated = MarkdownParser.inline("这里有个 ` 和 ** 单个星号 *", codeColor, codeBackground, linkColor)
        assertTrue(annotated.text.startsWith("这里有个 ` 和 ** 单个星号 *"))
    }

    @Test
    fun `链接里的中文与括号不会崩`() {
        val annotated = MarkdownParser.inline("看[中文说明](https://example.com/a?b=1&c=2)", codeColor, codeBackground, linkColor)
        assertEquals("看中文说明", annotated.text)
    }

    @Test
    fun `空文本返回空块列表`() {
        assertTrue(MarkdownParser.parse("").isEmpty())
        assertTrue(MarkdownParser.parse("\n\n").isEmpty())
    }
}
