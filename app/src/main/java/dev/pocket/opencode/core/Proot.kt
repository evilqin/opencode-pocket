package dev.pocket.opencode.core

import android.content.Context
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * proot 启动器。
 *
 * 运行机制（Android 10+ 的 W^X 限制下的标准做法）：
 *   - proot / prootloader 由系统从 APK 解压到 `nativeLibraryDir`，这是应用唯一被允许执行代码的目录；
 *   - libtalloc / libandroid-shmem 在 jniLibs 里必须叫 `*.so`，而 proot 的 DT_NEEDED 是
 *     `libtalloc.so.2` / `libandroid-shmem.so`，所以运行前复制成正确名字放进 files/linux/lib；
 *   - guest 里的程序由 `PROOT_LOADER` 做 userland exec，不依赖内核对 rootfs 的执行权限。
 */
class Proot(
    private val context: Context,
    private val paths: PocketPaths,
    private val settings: () -> AppSettings,
) {

    /** 把 jniLibs 里的依赖复制成正确的 SONAME 文件名（幂等）。 */
    fun stageBootRuntime() {
        require(paths.prootBin.isFile) { "内置运行时缺失：${paths.prootBin.name}（请重新安装 APK）" }
        require(paths.prootLoader.isFile) { "内置 loader 缺失：${paths.prootLoader.name}（请重新安装 APK）" }
        // 这两个是可执行文件：系统解压出来的通常已经带 x 位，chmod 失败不该让安装失败
        runCatching { Os.chmod(paths.prootBin.absolutePath, 0b111_101_101) }
        runCatching { Os.chmod(paths.prootLoader.absolutePath, 0b111_101_101) }
        FileOps.copyIfChanged(paths.tallocSource, paths.tallocRuntime, 0b110_100_100)
        FileOps.copyIfChanged(paths.shmemSource, paths.shmemRuntime, 0b110_100_100)
    }

    private var hardlinkCache: Boolean? = null

    /** rootfs 所在分区是否支持硬链接（不支持才需要 --link2symlink 模拟）。 */
    fun hardlinkSupported(): Boolean = hardlinkCache ?: run {
        val probeDir = File(paths.rootfs, "root/.pocket").apply { mkdirs() }
        val source = File(probeDir, "link-probe-a")
        val link = File(probeDir, "link-probe-b")
        val supported = try {
            source.writeText("probe")
            link.delete()
            Os.link(source.absolutePath, link.absolutePath)
            link.delete()
            true
        } catch (_: Exception) {
            false
        } finally {
            source.delete()
            link.delete()
        }
        hardlinkCache = supported
        supported
    }

    /** 工作区：宿主目录 + 容器内挂载点。 */
    data class Workspace(val host: File, val guest: String, val external: Boolean)

    fun resolveWorkspace(config: AppSettings): Workspace {
        if (!config.useExternalWorkspace) {
            paths.internalWorkspace.mkdirs()
            return Workspace(paths.internalWorkspace, PocketPaths.Guest.WORKSPACE, false)
        }
        // 路径必须过策略校验：绝不允许把 / 或 /data 这种目录当工作区挂进去
        val normalized = WorkspacePolicy.normalize(config.workspace)
        // 再解析软链 / `.` / `..`：字符串看起来合法的路径，真实落点可能是别的目录，
        // 解析不出来（或解析完不合法）就退回内部工作区
        val canonical = WorkspacePolicy.canonicalize(normalized)
        if (canonical == null) {
            paths.internalWorkspace.mkdirs()
            return Workspace(paths.internalWorkspace, PocketPaths.Guest.WORKSPACE, false)
        }
        val host = File(canonical)
        // canWrite 在 Android 的 FUSE 上会撒谎：真的建个临时文件试试
        val usable = (host.isDirectory || host.mkdirs()) && FileOps.canReallyWrite(host)
        if (!usable) {
            paths.internalWorkspace.mkdirs()
            return Workspace(paths.internalWorkspace, PocketPaths.Guest.WORKSPACE, false)
        }
        return Workspace(host, PocketPaths.Guest.WORKSPACE, true)
    }

    /** 组装 proot 的参数（不含最终要执行的 guest 命令）。 */
    fun baseArgv(
        config: AppSettings = settings(),
        extraBinds: List<Pair<File, String>> = emptyList(),
        cwd: String = PocketPaths.Guest.ROOT,
    ): MutableList<String> {
        val workspace = resolveWorkspace(config)

        val argv = mutableListOf<String>()
        argv += paths.prootBin.absolutePath
        // 外部工作区在 FUSE 上：宿主侧硬链接本来就不支持，同样需要 link2symlink 模拟
        if (!hardlinkSupported() || workspace.external) {
            argv += ProotArgs.LINK2SYMLINK
            val l2s = File(paths.rootfs, ".l2s").apply { mkdirs() }
            argv += ProotArgs.bind(l2s.absolutePath, l2s.absolutePath)
        }
        argv += ProotArgs.extensionFlags()
        argv += ProotArgs.FAKE_ROOT              // fake root
        argv += ProotArgs.rootfs(paths.rootfs.absolutePath)
        argv += ProotArgs.cwd(cwd)

        val binds = mutableListOf(
            "/dev" to "/dev",
            "/dev/urandom" to "/dev/random",
            "/proc" to "/proc",
            "/sys" to "/sys",
            "/system" to "/system",
            "/apex" to "/apex",
            "/proc/self/fd" to "/dev/fd",
            paths.shmDir.absolutePath to "/dev/shm",
        )
        if (File("/vendor").isDirectory) binds += "/vendor" to "/vendor"
        // 整个共享存储默认不挂：容器只需要看到用户选的工作区，不该顺带看到相册与其它 App 数据
        if (config.exposeSdcard && File("/storage/emulated/0").isDirectory) {
            binds += "/storage/emulated/0" to "/sdcard"
            binds += "/storage/emulated/0" to "/storage/emulated/0"
        }

        if (workspace.host.absolutePath != paths.internalWorkspace.absolutePath || !workspace.external) {
            workspace.host.mkdirs()
        }
        binds += workspace.host.absolutePath to workspace.guest

        for ((host, guest) in binds) {
            if (!File(host).exists()) continue
            argv += ProotArgs.bind(host, guest)
        }
        for ((host, guest) in extraBinds) {
            if (!host.exists()) continue
            argv += ProotArgs.bind(host.absolutePath, guest)
        }
        return argv
    }

    /** 组装 guest 环境变量。 */
    fun applyEnv(builder: ProcessBuilder, config: AppSettings = settings(), extraEnv: Map<String, String> = emptyMap()) {
        val environment = builder.environment()
        environment["LD_LIBRARY_PATH"] = "${paths.nativeDir.absolutePath}:${paths.libDir.absolutePath}"
        environment["PROOT_TMP_DIR"] = paths.tmpDir.absolutePath
        environment["PROOT_LOADER"] = paths.prootLoader.absolutePath
        environment["TMPDIR"] = paths.tmpDir.absolutePath
        if (config.compatMode) environment["PROOT_NO_SECCOMP"] = "1" else environment.remove("PROOT_NO_SECCOMP")
        environment.remove("LD_PRELOAD")
        environment.putAll(extraEnv)
    }

    /** 默认 guest 环境（在容器内生效）。 */
    fun guestEnv(extra: Map<String, String> = emptyMap()): List<String> {
        val base = linkedMapOf(
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "TERM" to "xterm-256color",
            "SHELL" to "/bin/bash",
            "USER" to "root",
            "LOGNAME" to "root",
            "TMPDIR" to "/tmp",
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
        )
        base.putAll(extra)
        return base.flatMap { (key, value) -> listOf("$key=$value") }
    }

    /**
     * 启动一个 guest 命令。
     *
     * @param command 交给 `bash --login -c` 的命令
     */
    fun start(
        command: String,
        config: AppSettings = settings(),
        extraEnv: Map<String, String> = emptyMap(),
        cwd: String = PocketPaths.Guest.ROOT,
        extraBinds: List<Pair<File, String>> = emptyList(),
    ): Process {
        val argv = baseArgv(config, extraBinds, cwd)
        argv += "/usr/bin/env"
        argv += "-i"
        argv += guestEnv(extraEnv)
        argv += "/bin/bash"
        argv += "--login"
        argv += "-c"
        argv += command
        val builder = ProcessBuilder(argv)
        builder.directory(paths.home)
        builder.redirectErrorStream(true)
        applyEnv(builder, config, emptyMap())
        return builder.start()
    }

    /** 打开原始 argv（终端等场景）。 */
    fun startRaw(argv: List<String>, config: AppSettings = settings()): Process {
        val builder = ProcessBuilder(argv)
        builder.directory(paths.home)
        builder.redirectErrorStream(true)
        applyEnv(builder, config)
        return builder.start()
    }

    fun stdinPump(process: Process): (String) -> Unit = { line ->
        try {
            process.outputStream.write((line + "\n").toByteArray())
            process.outputStream.flush()
        } catch (_: IOException) {
        }
    }

    companion object {
        fun readAll(input: InputStream): String = try {
            input.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
    }
}
