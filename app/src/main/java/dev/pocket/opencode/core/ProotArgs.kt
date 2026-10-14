package dev.pocket.opencode.core

/**
 * proot 的命令行参数（纯逻辑，单独抽出来是为了能写单测）。
 *
 * 为什么这么讲究：这些参数决定「手机能不能启动容器」，而**选项名在不同 proot 构建里并不一致**——
 * Ubuntu 24.04 自带的 proot 5.1.0 就不认 `-L`（SysV IPC 的短选项），只认 `--sysvipc` 的构建也存在。
 * 于是约定：
 *   1. 除了自古就有、所有构建都支持的 `-0`，一律用长选项；
 *   2. `tools/fetch-boot-runtime.mjs` 在 CI 里对着内置的 proot 二进制复核这些名字，
 *      换 proot 版本时会先在这里报错，而不是等到手机上启动容器才发现参数不认识。
 */
object ProotArgs {

    /** 扩展选项（顺序即传参顺序）。 */
    fun extensionFlags(): List<String> = listOf(
        "--sysvipc",       // SysV IPC 模拟（短选项 -L 在部分构建里不存在）
        "--kill-on-exit",  // proot 退出时带走整个容器进程树
    )

    /** fake root：`-0` 是最古老也最通用的写法，Ubuntu 5.1.0 与 Termux 版都支持。 */
    const val FAKE_ROOT = "-0"

    const val LINK2SYMLINK = "--link2symlink"

    fun rootfs(path: String): String = "--rootfs=$path"

    fun cwd(path: String): String = "--cwd=$path"

    /** 绑定挂载：宿主与 guest 相同就只写一个路径，否则 `host:guest`。 */
    fun bind(host: String, guest: String): List<String> =
        listOf("--bind", if (host == guest) host else "$host:$guest")
}
