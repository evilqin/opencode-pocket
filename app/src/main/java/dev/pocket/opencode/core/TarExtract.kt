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
 * 安全约束（tar 的经典攻击面）：
 *   - 条目名：过滤绝对路径与 `..`；
 *   - **写出前校验父目录的真实路径仍在目标树内**（canonicalPath 会解析符号链接）——
 *     否则「先放一个指向树外的软链，再往链接里写文件」就能逃出沙箱；
 *   - 硬链接的源路径同样做段级清洗并要求真实存在；
 *   - 设备节点 / FIFO 一律跳过（应用没有 mknod 权限）。
 *
 * 链接/权限设置失败不再悄悄吞掉：统计进 [Stats.skippedLinks] / [Stats.skipped]，
 * 由调用方决定是否提示「解压完成但有 N 个条目被跳过」。
 */
object TarExtract {

    data class Stats(
        var files: Long = 0,
        var bytes: Long = 0,
        var skipped: Long = 0,
        var skippedLinks: Long = 0,
        var escaped: Long = 0,
    )

    fun extract(
        archive: File,
        dest: File,
        strip: Int = 0,
        isActive: () -> Boolean = { true },
        onProgress: (Stats) -> Unit = {},
        include: ((String) -> Boolean)? = null,
    ): Stats {
        dest.mkdirs()
        val root = try {
            dest.canonicalPath
        } catch (_: Exception) {
            dest.absolutePath
        }
        val stats = Stats()
        var lastReport = System.currentTimeMillis()

        FileInputStream(archive).use { raw ->
            val buffered = BufferedInputStream(raw, 256 * 1024)
            val decompressed = wrapDecompressor(buffered)
            TarArchiveInputStream(decompressed).use { tar ->
                while (true) {
                    if (!isActive()) throw CancellationException("解压已取消")
                    val entry = tar.nextEntry ?: break
                    processEntry(tar, entry, dest, root, strip, include, stats, isActive)
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

    /** 段级清洗：返回 null 表示这个条目不安全（绝对路径 / 含 `..` / 被 strip 光了）。 */
    private fun sanitize(rawName: String, strip: Int): String? {
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) return null
        val segments = name.removePrefix("./").split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == ".." }) return null
        val stripped = segments.drop(strip)
        if (stripped.isEmpty()) return null
        return stripped.joinToString("/")
    }

    /** 父目录的真实路径必须还在目标树内（挡住「软链逃逸」）。 */
    private fun escapesTree(root: String, target: File): Boolean {
        val parent = target.parentFile ?: return true
        val canonicalParent = try {
            parent.canonicalPath
        } catch (_: Exception) {
            return true
        }
        return !(canonicalParent == root || canonicalParent.startsWith(root + File.separator))
    }

    private fun processEntry(
        tar: TarArchiveInputStream,
        entry: TarArchiveEntry,
        dest: File,
        root: String,
        strip: Int,
        include: ((String) -> Boolean)?,
        stats: Stats,
        isActive: () -> Boolean,
    ) {
        val relative = sanitize(entry.name, strip) ?: run {
            stats.skipped++
            return
        }
        if (include != null && !include(relative)) {
            stats.skipped++
            return
        }
        val target = File(dest, relative)
        val mode = entry.mode and 0x1FF

        when {
            entry.isDirectory -> {
                if (escapesTree(root, target)) {
                    stats.escaped++
                    return
                }
                target.mkdirs()
                chmodQuiet(target, mode or 0x1C0)
                stats.files++
            }

            entry.isSymbolicLink -> {
                if (escapesTree(root, target)) {
                    stats.escaped++
                    return
                }
                val link = entry.linkName
                if (link.isNullOrBlank()) {
                    stats.skippedLinks++
                    return
                }
                target.parentFile?.mkdirs()
                target.delete()
                try {
                    Os.symlink(link, target.absolutePath)
                    stats.files++
                } catch (_: Exception) {
                    stats.skippedLinks++
                }
            }

            entry.isLink -> {
                // 硬链接的源同样要清洗：既要存在，也要在树内
                val sourceRelative = sanitize(entry.linkName.orEmpty(), strip) ?: run {
                    stats.skippedLinks++
                    return
                }
                val source = File(dest, sourceRelative)
                if (escapesTree(root, target) || escapesTree(root, source) || !source.isFile) {
                    stats.skippedLinks++
                    return
                }
                target.parentFile?.mkdirs()
                target.delete()
                try {
                    Os.link(source.absolutePath, target.absolutePath)
                    stats.files++
                } catch (_: Exception) {
                    try {
                        source.copyTo(target, overwrite = true)
                        stats.files++
                    } catch (_: Exception) {
                        stats.skippedLinks++
                    }
                }
            }

            entry.isFile -> {
                if (escapesTree(root, target)) {
                    stats.escaped++
                    return
                }
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
        runCatching { Os.chmod(file.absolutePath, mode) }
    }
}
