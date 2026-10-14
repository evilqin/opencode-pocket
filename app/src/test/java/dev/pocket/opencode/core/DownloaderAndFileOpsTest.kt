package dev.pocket.opencode.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 下载摘要与文件操作（这两个都在上一轮修过真实 bug）。 */
class DownloaderAndFileOpsTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    // ----------------------------------------------------------------- 摘要

    @Test
    fun `sha256 已知向量`() {
        val file = temp.newFile("abc.txt")
        file.writeText("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Downloader.digestOf(file, "SHA-256"),
        )
        assertEquals(Downloader.sha256Of(file), Downloader.digestOf(file, "SHA-256"))
    }

    @Test
    fun `sha1 已知向量`() {
        val file = temp.newFile("abc2.txt")
        file.writeText("abc")
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Downloader.digestOf(file, "SHA-1"))
    }

    @Test
    fun `ExpectedHash 工厂会忽略空值`() {
        assertNull(Downloader.ExpectedHash.sha256(null))
        assertNull(Downloader.ExpectedHash.sha256(""))
        assertNull(Downloader.ExpectedHash.sha1("   "))
        assertEquals("SHA-256", Downloader.ExpectedHash.sha256("abc")!!.algorithm)
        assertEquals("SHA-1", Downloader.ExpectedHash.sha1("abc")!!.algorithm)
        assertEquals("ABC", Downloader.ExpectedHash.sha256("ABC")!!.hex)
    }

    // ----------------------------------------------------------------- 文件

    @Test
    fun `deleteTree 删干净且不动外部目录`() {
        val outside = temp.newFolder("outside")
        val outsideFile = File(outside, "keep.txt").apply { writeText("keep") }
        val root = temp.newFolder("tree")
        File(root, "a/b").mkdirs()
        File(root, "a/b/c.txt").writeText("x")
        File(root, "top.txt").writeText("y")

        FileOps.deleteTree(root)

        assertFalse(root.exists())
        assertTrue(outsideFile.isFile)
        assertEquals("keep", outsideFile.readText())
    }

    @Test
    fun `directorySize 只统计目录内部`() {
        val root = temp.newFolder("size")
        File(root, "a").mkdirs()
        File(root, "a/one.txt").writeText("12345")   // 5
        File(root, "two.txt").writeText("123")       // 3
        assertEquals(8L, FileOps.directorySize(root))
        assertEquals(0L, FileOps.directorySize(File(temp.root, "not-exist")))
    }

    @Test
    fun `copyIfChanged 按内容判断而不是长度`() {
        val source = temp.newFile("src.bin")
        val target = File(temp.root, "dst.bin")
        source.writeText("AAAA")
        FileOps.copyIfChanged(source, target)
        assertTrue(target.isFile)
        assertEquals("AAAA", target.readText())

        // 同长度但内容不同：必须重新复制（早先只比长度，会留下旧库）
        target.writeText("BBBB")
        FileOps.copyIfChanged(source, target)
        assertEquals("AAAA", target.readText())

        // 完全相同：不复制（保持 mtime 可用来验证）
        val mtime = target.lastModified()
        FileOps.copyIfChanged(source, target)
        assertEquals(mtime, target.lastModified())
    }

    @Test
    fun `canReallyWrite 能识别不可写目录`() {
        val dir = temp.newFolder("write-probe")
        assertTrue(FileOps.canReallyWrite(dir))
        // 探测用过的临时文件不该留下
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test
    fun `replaceDirectory 原子替换并清掉旧目录`() {
        val target = temp.newFolder("target")
        File(target, "old.txt").writeText("old")
        val staging = temp.newFolder("staging")
        File(staging, "new.txt").writeText("new")

        FileOps.replaceDirectory(staging, target)

        assertTrue(File(target, "new.txt").isFile)
        assertFalse(File(target, "old.txt").exists())
        assertFalse(staging.exists())
        assertFalse(File(temp.root, "target.old").exists())
    }
}
