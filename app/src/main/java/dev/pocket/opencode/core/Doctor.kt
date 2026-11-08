package dev.pocket.opencode.core

import dev.pocket.opencode.i18n.t

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
                Check(t("检查出错", "Check failed"), Status.FAIL, error.message ?: error.toString())
            }
            onUpdate(result)
        }
    }

    private fun probes(): List<suspend () -> Check> = listOf(
        {
            val abis = Build.SUPPORTED_ABIS.toList()
            if (abis.any { it == "arm64-v8a" }) {
                Check(t("设备架构", "Device architecture"), Status.OK, "arm64-v8a · ${Build.MODEL}")
            } else {
                Check(t("设备架构", "Device architecture"), Status.FAIL, t("这台设备不是 arm64：${abis.joinToString()}", "This device is not arm64: ${abis.joinToString()}"))
            }
        },

        {
            val missing = listOf(paths.prootBin, paths.prootLoader, paths.tallocSource, paths.shmemSource)
                .filter { !it.isFile || it.length() == 0L }
            if (missing.isEmpty()) {
                Check(
                    t("内置 proot 运行时", "Bundled proot runtime"), Status.OK,
                    "proot ${paths.prootBin.length() / 1024} KB · loader ${paths.prootLoader.length() / 1024} KB",
                )
            } else {
                Check(t("内置 proot 运行时", "Bundled proot runtime"), Status.FAIL, t("缺少 ${missing.joinToString { it.name }}，请重新安装 APK", "Missing ${missing.joinToString { it.name }}; reinstall the APK"))
            }
        },

        {
            runCatching { proot.stageBootRuntime() }
            if (paths.bootRuntimeStaged()) {
                Check(t("运行时依赖落地", "Runtime libraries staged"), Status.OK, t("libtalloc.so.2 / libandroid-shmem.so 已就绪", "libtalloc.so.2 / libandroid-shmem.so are ready"))
            } else {
                Check(t("运行时依赖落地", "Runtime libraries staged"), Status.FAIL, t("依赖没能复制到 ${paths.libDir.absolutePath}", "Libraries could not be copied to ${paths.libDir.absolutePath}"))
            }
        },

        {
            if (!File(paths.rootfs, "bin/bash").isFile) {
                Check(t("容器 rootfs", "Container rootfs"), Status.FAIL, t("还没解压（缺 /bin/bash），到安装页跑一次", "Not extracted yet (no /bin/bash); run Setup"))
            } else {
                val config = settings.snapshot()
                val output = shell.run(
                    "uname -m && . /etc/os-release 2>/dev/null && echo \$PRETTY_NAME",
                    config,
                    timeoutMs = 120_000,
                )
                if (output.success && output.text.contains("aarch64")) {
                    val pretty = output.lines.lastOrNull { it.isNotBlank() && !it.contains("aarch64") }?.trim()
                    Check(t("容器 rootfs", "Container rootfs"), Status.OK, "${pretty ?: "Ubuntu"} · aarch64")
                } else {
                    Check(
                        t("容器 rootfs", "Container rootfs"), Status.FAIL,
                        t(
                            "容器起不来：${output.text.takeLast(200).ifBlank { "无输出（exit=${output.exitCode}）" }}",
                            "Container failed to start: ${output.text.takeLast(200).ifBlank { "no output (exit=${output.exitCode})" }}",
                        ),
                    )
                }
            }
        },

        {
            if (!File(paths.rootfs, "usr/bin/git").isFile) {
                Check(t("基础工具", "Base tools"), Status.WARN, t("没有 git，重跑安装页的「基础工具」这一步", "git is missing; rerun the Base tools step in Setup"))
            } else {
                val config = settings.snapshot()
                val output = shell.run(
                    t(
                        "git --version; command -v rg >/dev/null && rg --version | head -n1 || echo 'rg 缺失'",
                        "git --version; command -v rg >/dev/null && rg --version | head -n1 || echo 'rg missing'",
                    ),
                    config,
                    timeoutMs = 120_000,
                )
                Check(
                    t("基础工具", "Base tools"),
                    if (output.success) Status.OK else Status.WARN,
                    output.lines.filter { it.isNotBlank() }.take(2).joinToString(" · ").ifBlank { t("检查失败", "Check failed") },
                )
            }
        },

        {
            val binary = File(paths.rootfs, "usr/local/lib/opencode/bin/opencode")
            if (!binary.isFile) {
                Check(t("opencode 可执行文件", "opencode executable"), Status.FAIL, t("还没下载，重跑安装页的「OpenCode 本体」这一步", "Not downloaded yet; rerun the OpenCode step in Setup"))
            } else {
                val config = settings.snapshot()
                val output = shell.run("opencode --version", config, timeoutMs = 180_000)
                if (output.success) {
                    Check(t("opencode 可执行文件", "opencode executable"), Status.OK, "v${output.text.trim().take(40)}")
                } else {
                    Check(
                        t("opencode 可执行文件", "opencode executable"), Status.FAIL,
                        t("跑不起来：${output.text.takeLast(200).ifBlank { "exit=${output.exitCode}" }}", "Cannot run: ${output.text.takeLast(200).ifBlank { "exit=${output.exitCode}" }}"),
                    )
                }
            }
        },

        {
            if (!File(paths.rootfs, "usr/bin/getent").isFile) {
                Check(t("容器内域名解析", "DNS inside the container"), Status.SKIP, t("容器里没有 getent，跳过", "getent is missing in the container; skipped"))
            } else {
                val config = settings.snapshot()
                val host = Mirrors.find(Mirrors.apt, config.aptMirror).base
                    .removePrefix("https://").removePrefix("http://").substringBefore('/')
                val output = shell.run("getent hosts $host | head -n1", config, timeoutMs = 60_000)
                if (output.success && output.text.isNotBlank()) {
                    Check(t("容器内域名解析", "DNS inside the container"), Status.OK, "$host → ${output.text.trim().take(60)}")
                } else {
                    Check(t("容器内域名解析", "DNS inside the container"), Status.WARN, t("解析 $host 失败：apt 可能装不了包（换网络或换镜像源）", "Failed to resolve $host: apt may not be able to install packages (change network or mirror)"))
                }
            }
        },

        {
            val free = installer.freeSpaceBytes()
            val used = withContext(Dispatchers.IO) { shell.hostSize(paths.home) }
            val detail = t("容器占用 ${Format.bytes(used)} · 可用 ${Format.bytes(free)}", "Used ${Format.bytes(used)} · free ${Format.bytes(free)}")
            when {
                free < 0 -> Check(t("存储空间", "Storage"), Status.INFO, detail)
                free < 300L * 1024 * 1024 -> Check(t("存储空间", "Storage"), Status.FAIL, t("$detail（不足 300 MB）", "$detail (less than 300 MB free)"))
                free < 1024L * 1024 * 1024 -> Check(t("存储空间", "Storage"), Status.WARN, detail)
                else -> Check(t("存储空间", "Storage"), Status.OK, detail)
            }
        },

        {
            val config = settings.snapshot()
            val occupied = withContext(Dispatchers.IO) { isPortOccupied(config.port) }
            if (!occupied) {
                Check(t("服务端口", "Server port"), Status.OK, t("127.0.0.1:${config.port} 空闲", "127.0.0.1:${config.port} is free"))
            } else {
                // 自己正在跑的时候端口当然是被占用的，先问一句健康检查
                val healthy = withContext(Dispatchers.IO) { isOurServer(config.port) }
                if (healthy) {
                    Check(t("服务端口", "Server port"), Status.OK, t("127.0.0.1:${config.port} 是本应用的服务（健康检查通过）", "127.0.0.1:${config.port} is this app's server (health check passed)"))
                } else {
                    Check(t("服务端口", "Server port"), Status.WARN, t("127.0.0.1:${config.port} 被其它进程占用，换一个端口", "127.0.0.1:${config.port} is used by another process; choose another port"))
                }
            }
        },

        {
            val configured = settings.snapshot().providerKeys.filterValues { it.isNotBlank() }
            if (configured.isEmpty()) {
                Check(t("模型密钥", "Model keys"), Status.WARN, t("还没配置任何 API Key（设置 → 模型与密钥）", "No API key configured yet (Settings → Models & keys)"))
            } else {
                Check(t("模型密钥", "Model keys"), Status.OK, t("已配置：${configured.keys.joinToString()}", "Configured: ${configured.keys.joinToString()}"))
            }
        },

        {
            val missing = buildList {
                if (!DevicePermissions.hasNotificationPermission(context)) add(t("通知", "Notifications"))
                if (!DevicePermissions.hasAllFilesAccess()) add(t("所有文件访问", "All files access"))
                if (!DevicePermissions.isIgnoringBatteryOptimizations(context)) add(t("电池白名单", "Battery allowlist"))
            }
            if (missing.isEmpty()) {
                Check(t("系统权限", "System permissions"), Status.OK, t("通知 / 文件 / 电池白名单都已就绪", "Notifications, files and the battery allowlist are ready"))
            } else {
                Check(t("系统权限", "System permissions"), Status.WARN, t("待开启：${missing.joinToString()}（首页有对应的授权入口）", "To enable: ${missing.joinToString()} (shortcuts are on Home)"))
            }
        },

        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Check(t("幻影进程限制", "Phantom process limit"), Status.SKIP, t("Android 11 及以下没有这个限制", "This limit does not exist on Android 11 and below"))
            } else {
                Check(
                    t("幻影进程限制", "Phantom process limit"), Status.INFO,
                    t("长任务被杀时用 adb/Shizuku 执行：settings put global settings_enable_monitor_phantom_procs false", "If long tasks get killed, run via adb/Shizuku: settings put global settings_enable_monitor_phantom_procs false"),
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

    /** 端口上是不是我们自己的 opencode 服务（看健康检查接口）。 */
    private fun isOurServer(port: Int): Boolean = try {
        java.net.URL("http://127.0.0.1:$port/global/health").openConnection().let { connection ->
            (connection as java.net.HttpURLConnection).apply {
                connectTimeout = 1_500
                readTimeout = 1_500
                // 服务带 Basic 口令，这里也要带上，否则永远 401
                val password = kotlinx.coroutines.runBlocking { settings.snapshot().serverPassword }
                if (password.isNotBlank()) {
                    val token = android.util.Base64.encodeToString(
                        "opencode:$password".toByteArray(),
                        android.util.Base64.NO_WRAP,
                    )
                    setRequestProperty("Authorization", "Basic $token")
                }
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            body.contains("healthy")
        }
    } catch (_: Exception) {
        false
    }
}
