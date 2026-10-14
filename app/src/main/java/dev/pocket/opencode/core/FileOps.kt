package dev.pocket.opencode.core

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/**
 * 与符号链接打交道的文件操作。
 *
 * 为什么要单独放着几行：Ubuntu rootfs 里有 `var/run -> /run`、`/bin -> usr/bin`
 * 这类链接，Kotlin 标准库的 `deleteRecursively()` / `walkTopDown()` 都会**跟随**符号链接，
 * 于是「删掉容器目录」可能跑到宿主机的 /run、/etc 上去，统计体积也会跑到树外甚至死循环。
 * 这里一律用「不跟随链接」的判断（java.nio 的 isSymbolicLink 在 JVM 单测里也能跑）。
 */
object FileOps {

    fun isSymlink(file: File): Boolean = try {
        java.nio.file.Files.isSymbolicLink(java.nio.file.Paths.get(file.absolutePath))
    } catch (_: Exception) {
        false
    }

    /**
     * 写文件/建目录前调用：如果这个路径当前是个符号链接，先删掉它。
     *
     * 否则「tarball 里先放一个指向树外的软链、再放同名文件」就能把内容写到树外
     * （软链的父目录检查挡不住这种写法）。
     */
    fun clearIfSymlink(file: File): Boolean {
        if (!isSymlink(file)) return false
        return file.delete()
    }

    /**
     * 交换目录：把 [newDir] 换到 [target]，旧目录改名为 `<target>.old` 保留。
     *
     * @return 备份目录（可能不存在），调用方在自检通过后调用 [finishSwap] 删除，
     *         失败时调用 [rollbackSwap] 换回来。
     */
    fun beginSwap(newDir: File, target: File): File {
        val backup = File(target.parentFile, target.name + ".old")
        deleteTree(backup)
        if (target.exists() || isSymlink(target)) {
            if (!target.renameTo(backup)) {
                // 换名失败就退化成直接删（至少保证最终状态正确）
                deleteTree(target)
            }
        }
        if (!newDir.renameTo(target)) {
            // 换过去失败：尽量把旧环境换回来再报错
            if (backup.exists()) backup.renameTo(target)
            throw IOException("无法把 ${newDir.name} 换成 ${target.name}")
        }
        return backup
    }

    /** 自检通过：丢掉旧环境。 */
    fun finishSwap(backup: File) {
        deleteTree(backup)
    }

    /** 自检失败：把旧环境换回来，新环境删掉。 */
    fun rollbackSwap(backup: File, target: File) {
        if (!backup.exists()) {
            deleteTree(target)
            return
        }
        deleteTree(target)
        if (!backup.renameTo(target)) {
            throw IOException("回滚失败：无法把 ${backup.name} 换回 ${target.name}")
        }
    }

    /** 兼容旧调用：交换并立即丢弃备份。 */
    fun replaceDirectory(newDir: File, target: File) {
        finishSwap(beginSwap(newDir, target))
    }

    /** 递归删除，不跟随符号链接（链接本身会被 unlink）。 */
    fun deleteTree(root: File) {
        if (!root.exists() && !isSymlink(root)) return
        if (isSymlink(root) || root.isFile) {
            root.delete()
            return
        }
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.addLast(root to false)
        while (stack.isNotEmpty()) {
            val (current, visited) = stack.removeLast()
            if (visited) {
                current.delete()
                continue
            }
            stack.addLast(current to true)
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) {
                    child.delete()
                } else if (child.isDirectory) {
                    stack.addLast(child to false)
                } else {
                    child.delete()
                }
            }
        }
    }

    /** 目录体积，不跟随符号链接。 */
    fun directorySize(root: File): Long {
        if (!root.exists() || isSymlink(root)) return 0
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    /** 只有当内容不同（大小或首块不同）才复制，避免升级后残留旧文件。 */
    fun copyIfChanged(source: File, target: File, mode: Int? = null): Boolean {
        if (!source.isFile) throw IOException("缺少源文件：${source.name}")
        if (target.isFile && target.length() == source.length() && sameHead(source, target)) return false
        target.parentFile?.mkdirs()
        source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        mode?.let { runCatching { Os.chmod(target.absolutePath, it) } }
        return true
    }

    private fun sameHead(a: File, b: File, bytes: Int = 4096): Boolean = try {
        val headA = ByteArray(bytes)
        val headB = ByteArray(bytes)
        val readA = a.inputStream().use { it.read(headA) }
        val readB = b.inputStream().use { it.read(headB) }
        readA == readB && headA.copyOf(readA).contentEquals(headB.copyOf(readB))
    } catch (_: Exception) {
        false
    }

    /**
     * 安全的“写入探测”：在目录里真的建一个临时文件再删掉，
     * 比 `File.canWrite()` 可靠（Android 的 FUSE 上 canWrite 经常撒谎）。
     */
    fun canReallyWrite(directory: File): Boolean {
        if (!directory.isDirectory && !directory.mkdirs()) return false
        return try {
            val probe = File.createTempFile(".pocket-write-", ".probe", directory)
            probe.writeText("ok")
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 原子替换目录：先把新内容放 ".new"，成功后再换上去，避免中途失败留下半个系统。 */
}
