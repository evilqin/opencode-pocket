package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorsTest {

    @Test
    fun `按 id 找镜像`() {
        assertEquals("ustc", Mirrors.find(Mirrors.ubuntu, "ustc").id)
        assertEquals("npmmirror", Mirrors.find(Mirrors.npm, "npmmirror").id)
    }

    @Test
    fun `找不到就回退到第一个`() {
        assertEquals(Mirrors.ubuntu.first().id, Mirrors.find(Mirrors.ubuntu, "not-exist").id)
        assertEquals(Mirrors.node.first().id, Mirrors.find(Mirrors.node, null).id)
    }

    @Test
    fun `每个镜像都有 https 或 http 地址且 id 唯一`() {
        listOf(Mirrors.ubuntu, Mirrors.apt, Mirrors.node, Mirrors.npm).forEach { group ->
            val ids = group.map { it.id }
            assertEquals("镜像 id 不能重复：$ids", ids.size, ids.toSet().size)
            group.forEach { mirror ->
                assertTrue(
                    "${mirror.id} 的地址不对：${mirror.base}",
                    mirror.base.startsWith("https://") || mirror.base.startsWith("http://"),
                )
            }
        }
    }

    @Test
    fun `Ubuntu 源与 apt 源的第一顺位都是国内镜像`() {
        assertEquals("tuna", Mirrors.ubuntu.first().id)
        assertEquals("tuna", Mirrors.apt.first().id)
        assertTrue(Mirrors.ubuntu.any { it.id == "official" })
    }
}
