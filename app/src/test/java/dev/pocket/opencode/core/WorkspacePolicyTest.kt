package dev.pocket.opencode.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    @Test
    fun `Android data 与 obb 会被拒绝（别的应用的私有数据）`() {
        listOf(
            "/sdcard/Android/data",
            "/sdcard/Android/data/com.bank.app",
            "/sdcard/Android/data/com.bank.app/files",
            "/sdcard/Android/obb/com.game",
            "/storage/emulated/0/Android/data/com.x",
            "/sdcard/Work/../Android/data/com.x",
        ).forEach { path ->
            assertNotNull("不该允许：$path", WorkspacePolicy.validate(path))
        }
    }

    @Test
    fun `Android media 与其它 Android 子目录仍允许`() {
        assertNull(WorkspacePolicy.validate("/sdcard/Android/media/com.x"))
        assertNull(WorkspacePolicy.validate("/sdcard/AndroidProjects"))
        // 大小写不同也不能绕过去
        assertNotNull(WorkspacePolicy.validate("/sdcard/android/DATA/com.x"))
    }

    @Test
    fun `点段路径会被拒绝（等于整个共享存储）`() {
        // `.` 会被文件系统解析掉：`/sdcard/.` 就是 /sdcard 本体
        listOf(
            "/sdcard/.",
            "/sdcard/./x",
            "/sdcard/a/./b",
            "/storage/emulated/0/.",
            "/storage/emulated/0/./",
            "/sdcard/../data",
        ).forEach { path ->
            assertNotNull("不该允许：$path", WorkspacePolicy.validate(path))
        }
    }

    @Test
    fun `canonicalize 对解析后非法的路径返回 null`() {
        listOf(
            "",
            "/",                       // 裸根
            "/sdcard",                 // 解析软链后是 /storage/emulated/0，等于整个共享存储
            "/sdcard/../data",
            "/data",
        ).forEach { path ->
            assertNull("不该允许：$path", WorkspacePolicy.canonicalize(path))
        }
    }

    @Test
    fun `canonicalize 对合法的不存在路径返回其自身`() {
        // 这里需要 POSIX 语义：Windows 上 /sdcard/OpenCode 会被解析成 C:\sdcard\OpenCode
        // （带盘符 + 反斜杠），本来就不再是合法的 Android 工作区路径，故跳过
        assumeTrue("该断言只在类 Unix（Android / CI）下有意义", File.separatorChar == '/')
        assertEquals("/sdcard/OpenCode", WorkspacePolicy.canonicalize("/sdcard/OpenCode"))
        assertEquals("/sdcard/My Projects", WorkspacePolicy.canonicalize("  /sdcard/My Projects  "))
        // 中间的 .. 会被解析掉，落到允许的目录里
        assertEquals("/sdcard/b", WorkspacePolicy.canonicalize("/sdcard/a/../b"))
    }
}
