package dev.pocket.opencode.core

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * 纯 Kotlin 的 tar 解压（支持 .tar.gz / .tar.xz / 裸 tar），用于把
 * Ubuntu base rootfs、Node.js 发行包、opencode 二进制直接展开到应用私有目录。
 *
 * 关键点：
 *   - 只信任相对路径，`..` 与绝对路径一律跳过（防目录穿越）；
 *   - 目录/普通文件按 tar 里的权限位 chmod（没有可执行位 rootfs 就废了）；
 *   - 软链接用 Os.symlink，硬链接先 Os.link，失败退化为拷贝；
 *   - 设备节点/FIFO 跳过（应用没有 mknod 权限，Ubuntu base 里那些节点换个方式补）。
 */
object TarExtract {

    data class Stats(var files: Long = 0, var bytes: Long = 0, var skipped: Long = 0)

    fun extract(
        archive: File,
        dest: File,
        strip: Int = 0,
        isActive: () -> Boolean = { true },
        onProgress: (Stats) -> Unit = {},
        include: ((String) -> Boolean)? = null,
    ): Stats {
        dest.mkdirs()
        val stats = Stats()
        var lastReport = System.currentTimeMillis()

        FileInputStream(archive).use { raw ->
            val buffered = BufferedInputStream(raw, 256 * 1024)
            val decompressed = wrapDecompressor(buffered)
            TarArchiveInputStream(decompressed).use { tar ->
                while (true) {
                    if (!isActive()) throw CancellationException("解压已取消")
                    val entry = tar.nextEntry ?: break
                    processEntry(tar, entry, dest, strip, include, stats, isActive)
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        onProgress(stats)
                        lastReport = now
                    }
                }
            }
        }
        onProgress(stats)
        return stats
    }

    private fun wrapDecompressor(input: BufferedInputStream): InputStream {
        input.mark(8)
        val magic = ByteArray(8)
        val read = input.read(magic)
        input.reset()
        if (read >= 2 && magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()) {
            return GzipCompressorInputStream(input, true)
        }
        if (read >= 6 && magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() && magic[2] == 0x7A.toByte() &&
            magic[3] == 0x58.toByte() && magic[4] == 0x5A.toByte() && magic[5] == 0x00.toByte()
        ) {
            return XZCompressorInputStream(input, true)
        }
        return input
    }

    private fun processEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        dest: File,
        strip: Int,
        include: ((String) -> Boolean)?,
        stats: Stats,
        isActive: () -> Boolean,
    ) {
        val rawName = entry.name.replace('\\', '/').removePrefix("./")
        if (rawName.isEmpty()) return
        val segments = rawName.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == ".." }) {
            stats.skipped++
            return
        }
        val stripped = segments.drop(strip)
        if (stripped.isEmpty()) return
        val relative = stripped.joinToString("/")
        if (include != null && !include(relative)) {
            stats.skipped++
            return
        }
        val target = File(dest, relative)
        val mode = entry.mode and 0x1FF

        when {
            entry.isDirectory -> {
                target.mkdirs()
                chmodQuiet(target, mode or 0x1C0)
                stats.files++
            }

            entry.isSymbolicLink -> {
                target.parentFile?.mkdirs()
                val link = entry.linkName
                if (link.isNullOrBlank()) return
                target.delete()
                try {
                    Os.symlink(link, target.absolutePath)
                } catch (_: Exception) {
                    stats.skipped++
                }
                stats.files++
            }

            entry.isLink -> {
                val linkName = entry.linkName.orEmpty().removePrefix("./")
                val source = File(dest, linkName.split('/').filter { it.isNotEmpty() }.drop(strip).joinToString("/"))
                target.parentFile?.mkdirs()
                target.delete()
                if (source.isFile) {
                    try {
                        Os.link(source.absolutePath, target.absolutePath)
                    } catch (_: Exception) {
                        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
                    }
                }
                stats.files++
            }

            entry.isFile -> {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    while (written < entry.size) {
                        if (!isActive()) throw CancellationException("解压已取消")
                        val want = minOf(buffer.size.toLong(), entry.size - written).toInt()
                        val count = tar.read(buffer, 0, want)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        written += count
                        stats.bytes += count
                    }
                }
                chmodQuiet(target, mode)
                stats.files++
            }

            else -> stats.skipped++
        }
    }

    private fun chmodQuiet(file: File, mode: Int) {
        if (mode <= 0) return
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (_: Exception) {
        }
    }
}
