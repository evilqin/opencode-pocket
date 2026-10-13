package dev.pocket.opencode.core

import android.content.Context
import android.os.Build
import dev.pocket.opencode.util.DevicePermissions
import dev.pocket.opencode.util.Format
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「一键体检」：把「为什么跑不起来」变成一排可以照着修的检查项。
 *
 * 全部检查都是只读的；最重的一项是在容器里执行 `uname` / `git --version` /
 * `opencode --version`，几秒钟能跑完。
 */
class Doctor(
    private val context: Context,
    private val paths: PocketPaths,
    private val proot: Proot,
    private val shell: GuestShell,
    private val settings: SettingsStore,
    private val installer: Installer,
) {

    enum class Status { OK, WARN, FAIL, INFO, SKIP }

    data class Check(
        val title: String,
        val status: Status,
        val detail: String = "",
    )

    /** 依次跑完所有检查，每完成一项回调一次（UI 边跑边显示）。 */
    suspend fun run(onUpdate: (Check) -> Unit) {
        for (probe in probes()) {
            val result = try {
                probe()
            } catch (error: Exception) {
                Check("检查出错", Status.FAIL, error.message ?: error.toString())
            }
            onUpdate(result)
        }
    }

    private fun probes(): List<suspend () -> Check> = listOf(
        {
            val abis = Build.SUPPORTED_ABIS.toList()
            if (abis.any { it == "arm64-v8a" }) {
                Check("设备架构", Status.OK, "arm64-v8a · ${Build.MODEL}")
            } else {
                Check("设备架构", Status.FAIL, "这台设备不是 arm64：${abis.joinToString()}")
            }
        },

        {
            val missing = listOf(paths.prootBin, paths.prootLoader, paths.tallocSource, paths.shmemSource)
                .filter { !it.isFile || it.length() == 0L }
            if (missing.isEmpty()) {
                Check(
                    "内置 proot 运行时", Status.OK,
                    "proot ${paths.prootBin.length() / 1024} KB · loader ${paths.prootLoader.length() / 1024} KB",
                )
            } else {
                Check("内置 proot 运行时", Status.FAIL, "缺少 ${missing.joinToString { it.name }}，请重新安装 APK")
            }
        },

        {
            runCatching { proot.stageBootRuntime() }
            if (paths.bootRuntimeStaged()) {
                Check("运行时依赖落地", Status.OK, "libtalloc.so.2 / libandroid-shmem.so 已就绪")
            } else {
                Check("运行时依赖落地", Status.FAIL, "依赖没能复制到 ${paths.libDir.absolutePath}")
            }
        },

        {
            if (!File(paths.rootfs, "bin/bash").isFile) {
                Check("容器 rootfs", Status.FAIL, "还没解压（缺 /bin/bash），到安装页跑一次")
            } else {
                val config = settings.snapshot()
                val output = shell.run(
                    "uname -m && . /etc/os-release 2>/dev/null && echo \$PRETTY_NAME",
                    config,
                    timeoutMs = 120_000,
                )
                if (output.success && output.text.contains("aarch64")) {
                    val pretty = output.lines.lastOrNull { it.isNotBlank() && !it.contains("aarch64") }?.trim()
                    Check("容器 rootfs", Status.OK, "${pretty ?: "Ubuntu"} · aarch64")
                } else {
                    Check(
                        "容器 rootfs", Status.FAIL,
                        "容器起不来：${output.text.takeLast(200).ifBlank { "无输出（exit=${output.exitCode}）" }}",
                    )
                }
            }
        },

        {
            if (!File(paths.rootfs, "usr/bin/git").isFile) {
                Check("基础工具", Status.WARN, "没有 git，重跑安装页的「基础工具」这一步")
            } else {
                val config = settings.snapshot()
                val output = shell.run(
                    "git --version; command -v rg >/dev/null && rg --version | head -n1 || echo 'rg 缺失'",
                    config,
                    timeoutMs = 120_000,
                )
                Check(
                    "基础工具",
                    if (output.success) Status.OK else Status.WARN,
                    output.lines.filter { it.isNotBlank() }.take(2).joinToString(" · ").ifBlank { "检查失败" },
                )
            }
        },

        {
            val binary = File(paths.rootfs, "usr/local/lib/opencode/bin/opencode")
            if (!binary.isFile) {
                Check("opencode 可执行文件", Status.FAIL, "还没下载，重跑安装页的「OpenCode 本体」这一步")
            } else {
                val config = settings.snapshot()
                val output = shell.run("opencode --version", config, timeoutMs = 180_000)
                if (output.success) {
                    Check("opencode 可执行文件", Status.OK, "v${output.text.trim().take(40)}")
                } else {
                    Check(
                        "opencode 可执行文件", Status.FAIL,
                        "跑不起来：${output.text.takeLast(200).ifBlank { "exit=${output.exitCode}" }}",
                    )
                }
            }
        },

        {
            if (!File(paths.rootfs, "usr/bin/getent").isFile) {
                Check("容器内域名解析", Status.SKIP, "容器里没有 getent，跳过")
            } else {
                val config = settings.snapshot()
                val host = Mirrors.find(Mirrors.apt, config.aptMirror).base
                    .removePrefix("https://").removePrefix("http://").substringBefore('/')
                val output = shell.run("getent hosts $host | head -n1", config, timeoutMs = 60_000)
                if (output.success && output.text.isNotBlank()) {
                    Check("容器内域名解析", Status.OK, "$host → ${output.text.trim().take(60)}")
                } else {
                    Check("容器内域名解析", Status.WARN, "解析 $host 失败：apt 可能装不了包（换网络或换镜像源）")
                }
            }
        },

        {
            val free = installer.freeSpaceBytes()
            val used = withContext(Dispatchers.IO) { shell.hostSize(paths.home) }
            val detail = "容器占用 ${Format.bytes(used)} · 可用 ${Format.bytes(free)}"
            when {
                free < 0 -> Check("存储空间", Status.INFO, detail)
                free < 300L * 1024 * 1024 -> Check("存储空间", Status.FAIL, "$detail（不足 300 MB）")
                free < 1024L * 1024 * 1024 -> Check("存储空间", Status.WARN, detail)
                else -> Check("存储空间", Status.OK, detail)
            }
        },

        {
            val config = settings.snapshot()
            val occupied = withContext(Dispatchers.IO) { isPortOccupied(config.port) }
            when {
                !occupied -> Check("服务端口", Status.OK, "127.0.0.1:${config.port} 空闲")
                else -> Check("服务端口", Status.WARN, "127.0.0.1:${config.port} 已被占用，换一个端口或重启服务")
            }
        },

        {
            val configured = settings.snapshot().providerKeys.filterValues { it.isNotBlank() }
            if (configured.isEmpty()) {
                Check("模型密钥", Status.WARN, "还没配置任何 API Key（设置 → 模型与密钥）")
            } else {
                Check("模型密钥", Status.OK, "已配置：${configured.keys.joinToString()}")
            }
        },

        {
            val missing = buildList {
                if (!DevicePermissions.hasNotificationPermission(context)) add("通知")
                if (!DevicePermissions.hasAllFilesAccess()) add("所有文件访问")
                if (!DevicePermissions.isIgnoringBatteryOptimizations(context)) add("电池白名单")
            }
            if (missing.isEmpty()) {
                Check("系统权限", Status.OK, "通知 / 文件 / 电池白名单都已就绪")
            } else {
                Check("系统权限", Status.WARN, "待开启：${missing.joinToString()}（首页有对应的授权入口）")
            }
        },

        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Check("幻影进程限制", Status.SKIP, "Android 11 及以下没有这个限制")
            } else {
                Check(
                    "幻影进程限制", Status.INFO,
                    "长任务被杀时用 adb/Shizuku 执行：settings put global settings_enable_monitor_phantom_procs false",
                )
            }
        },
    )

    private fun isPortOccupied(port: Int): Boolean = try {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (_: Exception) {
        false
    }
}
