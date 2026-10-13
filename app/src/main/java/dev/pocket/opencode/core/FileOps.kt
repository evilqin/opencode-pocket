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
 * 这里一律用 lstat 判断，绝不跟随。
 */
object FileOps {

    private fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.absolutePath).st_mode)
    } catch (_: Exception) {
        false
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
    fun replaceDirectory(newDir: File, target: File) {
        val backup = File(target.parentFile, target.name + ".old")
        deleteTree(backup)
        if (target.exists() || isSymlink(target)) {
            if (!target.renameTo(backup)) {
                // 换名失败就退化成直接删（至少保证最终状态正确）
                deleteTree(target)
            }
        }
        if (!newDir.renameTo(target)) {
            throw IOException("无法把 ${newDir.name} 换成 ${target.name}")
        }
        deleteTree(backup)
    }
}
