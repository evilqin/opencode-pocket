package dev.pocket.opencode.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {

    private fun parseAll(vararg lines: String): List<String> {
        val parser = SseParser()
        val events = mutableListOf<String>()
        lines.forEach { line -> parser.feed(line)?.let(events::add) }
        return events
    }

    @Test
    fun `标准的单行事件`() {
        val events = parseAll(
            "data: {\"type\":\"server.connected\"}",
            "",
            "data: {\"type\":\"message.updated\"}",
            "",
        )
        assertEquals(2, events.size)
        assertEquals("""{"type":"server.connected"}""", events[0])
        assertEquals("""{"type":"message.updated"}""", events[1])
    }

    @Test
    fun `多行 data 会拼成一条`() {
        val events = parseAll("data: line1", "data: line2", "data: line3", "")
        assertEquals(1, events.size)
        assertEquals("line1\nline2\nline3", events[0])
    }

    @Test
    fun `注释心跳不影响解析`() {
        val events = parseAll(": ping", "", "data: x", "")
        assertEquals(1, events.size)
        assertEquals("x", events[0])
    }

    @Test
    fun `其它字段被忽略`() {
        val events = parseAll("event: message", "id: 42", "retry: 1000", "data: payload", "")
        assertEquals(listOf("payload"), events)
    }

    @Test
    fun `空事件不会产生空载荷`() {
        val events = parseAll("", "", ": keep-alive", "")
        assertEquals(0, events.size)
    }

    @Test
    fun `没有空行收尾时事件不会提前发出`() {
        val parser = SseParser()
        assertNull(parser.feed("data: half"))
        // 断开重连：reset 之后半截事件必须被丢掉，否则会跟新事件拼在一起
        parser.reset()
        assertNull(parser.feed("data: fresh"))
        assertEquals("fresh", parser.feed(""))
    }

    @Test
    fun `data 冒号后没有空格也能解析`() {
        assertEquals(listOf("no-space"), parseAll("data:no-space", ""))
    }

    @Test
    fun `data 后面的前导空格只去掉一个`() {
        // SSE 规范：只去掉冒号后的第一个空格，其余空格属于数据
        assertEquals(listOf("  two-spaces"), parseAll("data:   two-spaces", ""))
    }

    @Test
    fun `连续事件之间不会串台`() {
        val events = parseAll("data: a", "", "data: b", "", "data: c", "")
        assertEquals(listOf("a", "b", "c"), events)
    }

    @Test
    fun `JSON 里带冒号和换行转义也能原样传递`() {
        val payload = """{"type":"x","properties":{"text":"a: b\nnext"}}"""
        assertEquals(listOf(payload), parseAll("data: $payload", ""))
    }
}
