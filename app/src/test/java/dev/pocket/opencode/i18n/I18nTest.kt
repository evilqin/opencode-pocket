package dev.pocket.opencode.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 语言模式收敛与文案选择（纯逻辑）。 */
class I18nTest {

    @Before
    fun setUp() {
        I18n.mode = I18n.CHINESE
    }

    @After
    fun tearDown() {
        I18n.mode = I18n.CHINESE
    }

    @Test
    fun `语言设置的各种写法都能收敛`() {
        assertEquals(I18n.ENGLISH, I18n.normalize("EN"))
        assertEquals(I18n.ENGLISH, I18n.normalize("en-US"))
        assertEquals(I18n.CHINESE, I18n.normalize("zh-Hans"))
        assertEquals(I18n.CHINESE, I18n.normalize("zh-CN"))
        assertEquals(I18n.SYSTEM, I18n.normalize(null))
        assertEquals(I18n.SYSTEM, I18n.normalize("fr"))
    }

    @Test
    fun `默认中文`() {
        assertFalse(I18n.isEnglish)
        assertEquals("停止", t("停止", "Stop"))
    }

    @Test
    fun `显式英文`() {
        I18n.mode = I18n.ENGLISH
        assertTrue(I18n.isEnglish)
        assertEquals("Stop", t("停止", "Stop"))
    }

    @Test
    fun `系统模式会根据设备语言选择`() {
        I18n.mode = I18n.SYSTEM
        val expected = I18n.systemLanguage() == I18n.ENGLISH
        assertEquals(expected, I18n.isEnglish)
    }
}
