package dev.pocket.opencode.core

import android.content.Context
import java.io.File

/**
 * 应用私有目录布局：
 *
 * ```
 * files/linux/                 容器根目录（相当于 Termux 的 $HOME）
 *   ├── ubuntu/                Ubuntu rootfs（proot -r 的目标）
 *   ├── lib/                   libtalloc.so.2 / libandroid-shmem.so（从 jniLibs 复制改名）
 *   ├── tmp/                   proot 的临时目录与 /dev/shm 的后端
 *   ├── logs/                  install.log / server.log
 *   ├── state.json             安装进度与版本记录
 *   └── workspace/             默认工作区（未授权 /sdcard 时使用）
 * ```
 */
class PocketPaths(context: Context) {

    private val app = context.applicationContext

    /** 容器主目录。 */
    val home: File = File(app.filesDir, "linux").apply { mkdirs() }

    /** Ubuntu 根文件系统。 */
    val rootfs: File = File(home, "ubuntu")

    /** 宿主侧共享库（供 proot 动态链接）。 */
    val libDir: File = File(home, "lib").apply { mkdirs() }

    /** proot 临时目录。 */
    val tmpDir: File = File(home, "tmp").apply { mkdirs() }

    /** 日志。 */
    val logsDir: File = File(home, "logs").apply { mkdirs() }
    val installLog: File = File(logsDir, "install.log")
    val serverLog: File = File(logsDir, "server.log")

    /** 安装状态。 */
    val stateFile: File = File(home, "state.json")

    /** 容器内挂给 /dev/shm 的目录。 */
    val shmDir: File = File(app.cacheDir, "shm").apply { mkdirs() }

    /** 下载缓存。 */
    val downloadDir: File = File(app.cacheDir, "downloads").apply { mkdirs() }

    /** 未授权外部存储时的内部工作区。 */
    val internalWorkspace: File = File(home, "workspace").apply { mkdirs() }

    /** 导出目录（分享日志、备份等）。 */
    val exportDir: File = File(app.filesDir, "exports").apply { mkdirs() }

    /** 系统解压出来的原生库目录（唯一允许执行的位置）。 */
    val nativeDir: File = File(app.applicationInfo.nativeLibraryDir)

    val prootBin: File get() = File(nativeDir, "libproot.so")
    val prootLoader: File get() = File(nativeDir, "libprootloader.so")
    val tallocSource: File get() = File(nativeDir, "libtalloc.so")
    val shmemSource: File get() = File(nativeDir, "libandroidshmem.so")

    /** 运行期正确 SONAME 的两个依赖。 */
    val tallocRuntime: File get() = File(libDir, "libtalloc.so.2")
    val shmemRuntime: File get() = File(libDir, "libandroid-shmem.so")

    /** rootfs 内的路径常量。 */
    object Guest {
        const val ROOT = "/root"
        const val WORKSPACE = "/root/workspace"
        const val OPENCODE_CONFIG = "/root/.config/opencode/opencode.json"
        const val OPENCODE_SKILLS = "/root/.config/opencode/skills"
        const val PROFILE = "/etc/profile.d/90-pocket.sh"
        const val APT_SOURCES = "/etc/apt/sources.list"
        const val RESOLV = "/etc/resolv.conf"
        /** 可执行文件真实位置（由 npm 包 bin/opencode 解压而来）。 */
        const val OPENCODE_BIN = "/usr/local/lib/opencode/bin/opencode"
        const val BIN_LINK = "/usr/local/bin/opencode"
    }

    /** rootfs 是否已经解压出一套像样的系统。 */
    fun rootfsLooksInstalled(): Boolean =
        File(rootfs, "bin/bash").isFile() && File(rootfs, "usr/bin/env").isFile()

    /** 应用私有目录里已解压的原生库是否齐备。 */
    fun bootRuntimeStaged(): Boolean =
        tallocRuntime.isFile() && shmemRuntime.isFile() && prootBin.isFile() && prootLoader.isFile()
}
