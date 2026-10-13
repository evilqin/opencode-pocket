package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    @Test
    fun `比较基本的语义化版本`() {
        assertTrue(Versions.compare("1.19.0", "1.18.3") > 0)
        assertTrue(Versions.compare("1.18.3", "1.19.0") < 0)
        assertEquals(0, Versions.compare("1.19.0", "1.19.0"))
        assertEquals(0, Versions.compare("v1.19.0", "1.19.0"))
    }

    @Test
    fun `缺段按 0 处理`() {
        assertEquals(0, Versions.compare("24.04", "24.04.0"))
        assertTrue(Versions.compare("24.04.1", "24.04") > 0)
        assertTrue(Versions.compare("22", "22.1.0") < 0)
    }

    @Test
    fun `两位数的段不会退化成字符串比较`() {
        // 字符串比较下 "24.04.9" > "24.04.10"，数字比较必须反过来
        assertTrue(Versions.compare("24.04.10", "24.04.9") > 0)
        assertTrue(Versions.score("24.04.10") > Versions.score("24.04.9"))
        assertTrue(Versions.score("24.04.5") > Versions.score("24.04.1"))
    }

    @Test
    fun `node 版本带后缀也能比`() {
        assertTrue(Versions.compare("v22.11.0", "v22.9.0") > 0)
        assertFalse(Versions.isNewer("v22.9.0", "v22.11.0"))
        assertTrue(Versions.isNewer("v24.1.0", "v22.11.0"))
    }
}
