package dev.pocket.opencode.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** 解析出来的 Markdown 块（纯数据，方便单元测试）。 */
sealed interface MdBlock {
    data class Code(val language: String, val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val items: List<String>) : MdBlock
    data class Quote(val text: String) : MdBlock
}

/**
 * 模型回复里最常见的 Markdown 子集：代码块、标题、列表、引用、行内代码/加粗/斜体/链接。
 *
 * 刻意不引第三方 Markdown 库：手机上行数少、样式由我们自己控制，出错面也小；
 * 关键路径（解析 + 行内样式）都在 JVM 单元测试里覆盖。
 */
object MarkdownParser {

    private fun isBullet(line: String): Boolean = line.startsWith("- ") || line.startsWith("* ")

    fun parse(source: String): List<MdBlock> {
        val blocks = ArrayList<MdBlock>()
        val lines = source.replace("\r\n", "\n").split('\n')
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmedStart = line.trimStart()
            when {
                trimmedStart.startsWith("```") -> {
                    val language = trimmedStart.removePrefix("```").trim()
                    index++
                    val buffer = StringBuilder()
                    while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                        buffer.append(lines[index]).append('\n')
                        index++
                    }
                    index++ // 收尾的 ```
                    blocks += MdBlock.Code(language, buffer.toString().trimEnd('\n'))
                }

                trimmedStart.startsWith("#") -> {
                    val level = trimmedStart.takeWhile { it == '#' }.length.coerceAtMost(4)
                    blocks += MdBlock.Heading(level, trimmedStart.drop(level).trim())
                    index++
                }

                isBullet(trimmedStart) -> {
                    val items = ArrayList<String>()
                    while (index < lines.size && isBullet(lines[index].trimStart())) {
                        items += lines[index].trimStart().substring(2).trim()
                        index++
                    }
                    blocks += MdBlock.Bullet(items)
                }

                trimmedStart.startsWith("> ") -> {
                    val buffer = StringBuilder()
                    while (index < lines.size && lines[index].trimStart().startsWith("> ")) {
                        buffer.append(lines[index].trimStart().removePrefix("> ")).append('\n')
                        index++
                    }
                    blocks += MdBlock.Quote(buffer.toString().trim())
                }

                line.isBlank() -> index++

                else -> {
                    val buffer = StringBuilder()
                    while (index < lines.size) {
                        val current = lines[index]
                        if (current.isBlank() || current.trimStart().startsWith("```") ||
                            current.trimStart().startsWith("#") || isBullet(current.trimStart()) ||
                            current.trimStart().startsWith("> ")
                        ) {
                            break
                        }
                        if (buffer.isNotEmpty()) buffer.append('\n')
                        buffer.append(current)
                        index++
                    }
                    blocks += MdBlock.Paragraph(buffer.toString())
                }
            }
        }
        return blocks
    }

    /** 行内标记：`code`、**粗体**、*斜体*、[链接](url)。 */
    fun inline(
        text: String,
        codeColor: Color,
        codeBackground: Color,
        linkColor: Color,
    ): AnnotatedString = buildAnnotatedString {
        var i = 0
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isNotEmpty()) {
                append(buffer.toString())
                buffer.clear()
            }
        }

        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBackground,
                                color = codeColor,
                            ),
                        )
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        buffer.append('`')
                        i++
                    }
                }

                rest.startsWith("**") -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        flush()
                        pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
                        append(text.substring(i + 2, end))
                        pop()
                        i = end + 2
                    } else {
                        buffer.append("**")
                        i += 2
                    }
                }

                rest.startsWith("[") -> {
                    val close = text.indexOf(']', i)
                    val open = if (close > i) text.indexOf('(', close) else -1
                    val end = if (open == close + 1) text.indexOf(')', open) else -1
                    if (close > i && open == close + 1 && end > open) {
                        flush()
                        val label = text.substring(i + 1, close)
                        val url = text.substring(open + 1, end)
                        withLink(LinkAnnotation.Url(url)) {
                            pushStyle(
                                SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            )
                            append(label)
                            pop()
                        }
                        i = end + 1
                    } else {
                        buffer.append('[')
                        i++
                    }
                }

                rest.startsWith("*") && !rest.startsWith("**") -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        flush()
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else {
                        buffer.append('*')
                        i++
                    }
                }

                else -> {
                    buffer.append(text[i])
                    i++
                }
            }
        }
        flush()
    }
}
