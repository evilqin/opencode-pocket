package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * proot 参数的单测。
 *
 * 这些断言看起来"过分具体"，但它们是真机上最容易踩的坑：选项名写错 proot 会直接
 * `unknown option` 退出，容器一个都起不来，而手机端排查成本极高。
 * （CI 里 tools/fetch-boot-runtime.mjs 还会对着内置的 proot 二进制复核这些名字。）
 */
class ProotArgsTest {

    @Test
    fun `扩展选项一律用长选项`() {
        val flags = ProotArgs.extensionFlags()
        assertTrue("缺少 --sysvipc", flags.contains("--sysvipc"))
        assertTrue("缺少 --kill-on-exit", flags.contains("--kill-on-exit"))
        assertFalse("不能再出现短选项 -L（Ubuntu 的 proot 5.1.0 不认它）", flags.contains("-L"))
        assertTrue("不该出现空串", flags.none { it.isBlank() })
    }

    @Test
    fun `fake root 用最通用的 -0`() {
        assertEquals("-0", ProotArgs.FAKE_ROOT)
    }

    @Test
    fun `rootfs 与 cwd 带等号拼接`() {
        assertEquals("--rootfs=/data/data/dev.pocket/files/linux/rootfs", ProotArgs.rootfs("/data/data/dev.pocket/files/linux/rootfs"))
        assertEquals("--cwd=/root", ProotArgs.cwd("/root"))
    }

    @Test
    fun `绑定挂载同路径只写一次`() {
        assertEquals(listOf("--bind", "/dev"), ProotArgs.bind("/dev", "/dev"))
    }

    @Test
    fun `绑定挂载异路径写宿主冒号 guest`() {
        assertEquals(
            listOf("--bind", "/data/user/0/dev.pocket/files/linux/shm:/dev/shm"),
            ProotArgs.bind("/data/user/0/dev.pocket/files/linux/shm", "/dev/shm"),
        )
    }

    @Test
    fun `link2symlink 是长选项`() {
        assertEquals("--link2symlink", ProotArgs.LINK2SYMLINK)
        assertTrue(ProotArgs.LINK2SYMLINK.startsWith("--"))
    }
}
