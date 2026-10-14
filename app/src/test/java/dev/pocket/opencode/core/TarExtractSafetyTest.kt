package dev.pocket.opencode.core

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * tar 解压的安全边界与基本行为。
 *
 * 这些用例对应真实攻击面：镜像被劫持/投毒时，tarball 里可以塞 `../`、绝对路径、
 * 指向树外的软链与硬链；解压器必须在**不报错**的前提下把这些条目丢掉。
 */
class TarExtractSafetyTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private fun buildTar(block: (TarArchiveOutputStream) -> Unit): File {
        val file = temp.newFile("test.tar")
        TarArchiveOutputStream(file.outputStream().buffered()).use { out ->
            out.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            block(out)
        }
        return file
    }

    private fun TarArchiveOutputStream.addFile(name: String, content: String, mode: Int = 0b110100100) {
        val entry = TarArchiveEntry(name)
        entry.size = content.toByteArray(Charsets.UTF_8).size.toLong()
        entry.mode = mode
        putArchiveEntry(entry)
        write(content.toByteArray(Charsets.UTF_8))
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.addDir(name: String) {
        val entry = TarArchiveEntry(name)
        entry.mode = 0b111101101
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.addLink(name: String, target: String, linkFlag: Byte) {
        val entry = TarArchiveEntry(name, linkFlag)
        entry.linkName = target
        putArchiveEntry(entry)
        closeArchiveEntry()
    }

    @Test
    fun `正常文件 目录 与软链都能解压`() {
        val tar = buildTar { out ->
            out.addDir("root/")
            out.addFile("root/hello.txt", "hello world")
            out.addFile("root/bin/tool.sh", "#!/bin/sh\necho hi\n", mode = 0b111101101)
            out.addLink("root/link.txt", "hello.txt", TarConstants.LF_SYMLINK)
        }
        val dest = temp.newFolder("dest")
        val stats = TarExtract.extract(tar, dest)

        assertEquals("hello world", File(dest, "root/hello.txt").readText())
        assertTrue(File(dest, "root/bin/tool.sh").isFile)
        assertTrue(stats.files >= 3)
        assertTrue(stats.bytes > 0)
    }

    @Test
    fun `上级目录逃逸的条目会被丢弃`() {
        val dest = temp.newFolder("dest-escape")
        val parentSentinel = File(temp.root, "sentinel.txt").apply { writeText("must-not-change") }
        val tar = buildTar { out ->
            out.addFile("ok.txt", "fine")
            out.addFile("../escaped.txt", "evil")
            out.addFile("../../escaped-deeper.txt", "evil")
            out.addFile("a/../../escaped-mid.txt", "evil")
        }
        val stats = TarExtract.extract(tar, dest)

        assertTrue("正常条目应该解出来", File(dest, "ok.txt").isFile)
        assertFalse(File(temp.root, "escaped.txt").exists())
        assertFalse(File(temp.root.parentFile, "escaped-deeper.txt").exists())
        assertFalse(File(dest, "a/../escaped-mid.txt").exists())
        // 越界条目要么被 sanitize 丢掉，要么被 escapesTree 拦下，两者都会计数
        assertTrue("越界条目应该有计数", stats.skipped + stats.escaped >= 3)
        assertEquals("must-not-change", parentSentinel.readText())
    }

    @Test
    fun `指向树外的硬链接会被跳过且不产生文件`() {
        val dest = temp.newFolder("dest-hardlink")
        val tar = buildTar { out ->
            out.addFile("real.txt", "content")
            out.addLink("hard-escape.txt", "../../../etc/passwd", TarConstants.LF_LINK)
            out.addLink("hard-relative.txt", "real.txt", TarConstants.LF_LINK)
        }
        val stats = TarExtract.extract(tar, dest)

        assertTrue(File(dest, "real.txt").isFile)
        assertFalse("越界硬链接不能落盘", File(dest, "hard-escape.txt").exists())
        assertTrue("越界硬链接要被计数", stats.skippedLinks >= 1)
        // 相对硬链接（源在树内）在 JVM 上会退化为拷贝，也允许被跳过，但不该越界
        assertFalse(File(temp.root, "hard-escape.txt").exists())
    }

    @Test
    fun `绝对路径不会写到文件系统根目录`() {
        val dest = temp.newFolder("dest-abs")
        val tar = buildTar { out ->
            out.addFile("/tmp/evil-absolute.txt", "evil")
        }
        val stats = TarExtract.extract(tar, dest)
        // 无论如何都不能写到 dest 之外；容器里的绝对路径由 proot 兜着
        assertFalse(File("/tmp/evil-absolute.txt").exists())
        assertTrue(stats.files + stats.skipped + stats.escaped >= 1)
    }

    @Test
    fun `设备节点与 FIFO 会被跳过`() {
        val dest = temp.newFolder("dest-dev")
        val tar = buildTar { out ->
            out.addFile("normal.txt", "x")
            // 注意别用 "nul"：Windows 上 File("nul").exists() 恒为真，会误判
            val device = TarArchiveEntry("dev/pocket-fake-device", TarConstants.LF_CHR)
            device.devMajor = 1
            device.devMinor = 3
            device.mode = 0b110110110
            out.putArchiveEntry(device)
            out.closeArchiveEntry()
            val fifo = TarArchiveEntry("dev/pocket-fake-fifo", TarConstants.LF_FIFO)
            out.putArchiveEntry(fifo)
            out.closeArchiveEntry()
        }
        val stats = TarExtract.extract(tar, dest)
        assertTrue(File(dest, "normal.txt").isFile)
        val leftovers = File(dest, "dev").listFiles()?.map { it.name }.orEmpty()
        assertTrue("设备节点/FIFO 不该落盘，实际有：$leftovers", leftovers.isEmpty())
        assertTrue(stats.skipped >= 2)
    }

    @Test
    fun `strip 去掉顶层目录`() {
        val tar = buildTar { out ->
            out.addFile("node-v24.21.0-linux-arm64/bin/node", "binary")
            out.addFile("node-v24.21.0-linux-arm64/README.md", "readme")
        }
        val dest = temp.newFolder("dest-strip")
        TarExtract.extract(tar, dest, strip = 1)

        assertTrue(File(dest, "bin/node").isFile)
        assertTrue(File(dest, "README.md").isFile)
        assertEquals("binary", File(dest, "bin/node").readText())
    }

    @Test
    fun `include 过滤器只保留想要的条目`() {
        val tar = buildTar { out ->
            out.addFile("package/bin/opencode", "BINARY")
            out.addFile("package/package.json", "{}")
            out.addFile("package/lib/other.js", "x")
        }
        val dest = temp.newFolder("dest-include")
        val stats = TarExtract.extract(
            archive = tar,
            dest = dest,
            strip = 1,
            include = { relative -> relative == "bin/opencode" },
        )
        assertTrue(File(dest, "bin/opencode").isFile)
        assertFalse(File(dest, "package.json").exists())
        assertFalse(File(dest, "lib/other.js").exists())
        assertEquals(1L, stats.files)
    }

    @Test
    fun `取消回调会中断解压`() {
        val tar = buildTar { out ->
            repeat(200) { index -> out.addFile("f/$index.txt", "x".repeat(200)) }
        }
        val dest = temp.newFolder("dest-cancel")
        var seen = 0
        try {
            TarExtract.extract(tar, dest, isActive = { seen++ < 5 })
            org.junit.Assert.fail("应该在取消时抛出 CancellationException")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            // 预期
        }
    }
}
