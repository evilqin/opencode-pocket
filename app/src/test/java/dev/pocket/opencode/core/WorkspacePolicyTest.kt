package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspacePolicyTest {

    @Test
    fun `共享存储里的具体目录允许`() {
        listOf(
            "/sdcard/OpenCode",
            "/sdcard/Projects/my-app",
            "/storage/emulated/0/OpenCode",
            "/storage/ABCD-1234/work",
        ).forEach { path ->
            assertNull("应该允许：$path", WorkspacePolicy.validate(path))
            assertTrue(WorkspacePolicy.isValid(path))
        }
    }

    @Test
    fun `危险的根目录会被拒绝`() {
        listOf(
            "/",
            "/data",
            "/system",
            "/sdcard",
            "/storage",
            "/storage/emulated/0",
            "/data/data/dev.pocket.opencode",
        ).forEach { path ->
            assertNotNull("不该允许：$path", WorkspacePolicy.validate(path))
        }
    }

    @Test
    fun `相对路径与穿越会被拒绝`() {
        assertNotNull(WorkspacePolicy.validate("OpenCode"))
        assertNotNull(WorkspacePolicy.validate("/sdcard/../data"))
        assertNotNull(WorkspacePolicy.validate("/sdcard/a/../../etc"))
        assertNotNull(WorkspacePolicy.validate(""))
        assertNotNull(WorkspacePolicy.validate("/sdcard/" + "x".repeat(300)))
    }

    @Test
    fun `尾部斜杠与空白会被规范化`() {
        assertEquals("/sdcard/OpenCode", WorkspacePolicy.normalize("  /sdcard/OpenCode/  "))
        assertEquals("/sdcard/OpenCode", WorkspacePolicy.normalize("/data"))   // 非法 -> 回退默认
        assertEquals("/sdcard/My Projects", WorkspacePolicy.normalize("/sdcard/My Projects/"))
    }

    @Test
    fun `中文与空格路径允许`() {
        assertNull(WorkspacePolicy.validate("/sdcard/我的项目"))
        assertNull(WorkspacePolicy.validate("/sdcard/工作 space"))
    }

    @Test
    fun `外置卡路径允许但根不允许`() {
        assertNull(WorkspacePolicy.validate("/storage/1A2B-3C4D/proj"))
        assertNotNull(WorkspacePolicy.validate("/storage/1A2B-3C4D"))
    }
}
