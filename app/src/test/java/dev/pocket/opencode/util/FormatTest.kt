package dev.pocket.opencode.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {

    @Test
    fun `字节格式化`() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 MB", Format.bytes(1_572_864))
        assertEquals("2.0 GB", Format.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `速度格式化`() {
        assertEquals("—", Format.speed(0))
        assertTrue(Format.speed(2_500_000).endsWith("/s"))
    }

    @Test
    fun `时长格式化`() {
        assertEquals("0s", Format.duration(0))
        assertEquals("5秒", Format.duration(5_000))
        assertEquals("2分3秒", Format.duration(123_000))
        assertEquals("1小时2分", Format.duration(3_720_000))
        assertEquals("1天1小时", Format.duration(25 * 3_600_000L))
    }

    @Test
    fun `相对时间`() {
        val now = System.currentTimeMillis()
        assertEquals("刚刚", Format.relativeTime(now - 10_000))
        assertEquals("5 分钟前", Format.relativeTime(now - 5 * 60_000))
        assertEquals("2 小时前", Format.relativeTime(now - 2 * 3_600_000))
        assertTrue(Format.relativeTime(0).isEmpty())
    }

    @Test
    fun `token 数量缩写`() {
        assertEquals("999", Format.tokens(999))
        assertEquals("1.5k", Format.tokens(1500))
        assertEquals("2.0M", Format.tokens(2_000_000))
    }

    @Test
    fun `成本格式化`() {
        assertEquals("", Format.cost(0.0))
        assertEquals("$0.0012", Format.cost(0.0012))
        assertEquals("$0.012", Format.cost(0.012))
        assertEquals("$1.234", Format.cost(1.234))
    }
}
