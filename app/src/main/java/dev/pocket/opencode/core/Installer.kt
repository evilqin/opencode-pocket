package dev.pocket.opencode.core

import android.content.Context
import android.system.Os
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 安装流水线的步骤。 */
enum class InstallStep(
    val id: String,
    val title: String,
    val subtitle: String,
    val optional: Boolean = false,
) {
    BOOT("boot", "运行时就绪", "内置 proot 与动态库"),
    ROOTFS("rootfs", "Ubuntu 根文件系统", "下载并解压 arm64 基础系统（约 30 MB）"),
    TOOLS("tools", "基础工具", "git / curl / ripgrep 等（走镜像源 apt）"),
    NODE("node", "Node.js（可选）", "LTS 版本，插件与 npm 项目会用到", optional = true),
    OPENCODE("opencode", "OpenCode 本体", "下载官方 linux-arm64 可执行文件"),
    CONFIG("config", "容器配置", "写入 shell 环境、npm 源与 opencode 配置"),
}

enum class StepState { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class StepSnapshot(
    val step: InstallStep,
    val state: StepState = StepState.PENDING,
    val detail: String = "",
    val progress: Float = -1f,
    val error: String? = null,
)

data class InstallSnapshot(
    val steps: List<StepSnapshot> = InstallStep.entries.map { StepSnapshot(it) },
    val running: InstallStep? = null,
    val message: String = "",
    val installed: Boolean = false,
) {
    fun step(step: InstallStep): StepSnapshot = steps.first { it.step == step }
}

/**
 * 分步安装：每一步都可单独重跑（断网、换镜像、换版本都不需要从头来）。
 * 所有状态落盘到 `files/linux/state.json`，重启 App 后能续上。
 *
 * 几条必须守住的规则：
 *   - 取消只是「请求」：正在跑的协程真正结束后，job 才会清空，不会出现两套流水线同时改 rootfs；
 *   - 失败信息（FAILED + error）不会被 refresh() 抹掉，用户随时能看到哪一步为什么失败；
 *   - 大动作尽量原子：rootfs 先解压到 `ubuntu.new` 再整体替换，下载包留到自检通过才删。
 */
class Installer(
    private val context: Context,
    private val paths: PocketPaths,
    private val proot: Proot,
    private val shell: GuestShell,
    private val downloads: Downloader,
    private val repos: Repos,
    private val net: NetworkBridge,
    private val settings: SettingsStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(InstallSnapshot())
    val state: StateFlow<InstallSnapshot> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 1024)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    /** 日志环形缓冲：给「终端 → 安装日志」做历史回填，并且支持真正的「清屏」。 */
    private val recent = ArrayDeque<String>()
    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private val _versions = MutableStateFlow<Map<String, String>>(emptyMap())
    val versions: StateFlow<Map<String, String>> = _versions.asStateFlow()

    private var job: Job? = null

    /** 用户点了取消：下载与解压都会尽快退出（协程取消 + 显式检查双保险）。 */
    @Volatile
    private var cancelRequested = false

    val isBusy: Boolean get() = job?.isActive == true

    fun clearLogs() {
        synchronized(recent) {
            recent.clear()
            _recentLogs.value = emptyList()
        }
    }

    // ------------------------------------------------------------------ 对外 API

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val records = readState()
        val disk = detectOnDisk()
        val steps = InstallState.merge(
            records = records,
            disk = disk,
            running = _state.value.running,
            current = { step -> _state.value.step(step) },
        )
        _state.update {
            it.copy(
                steps = steps,
                installed = InstallState.isInstalled(steps, settings.snapshot().installNode),
            )
        }
        _versions.value = loadVersions()
    }

    /** 一键安装：按依赖顺序跑所有还没完成的步骤。 */
    suspend fun installAll() {
        val pending = _state.value.steps
            .filter { it.state != StepState.DONE }
            .map { it.step }
        installSteps(InstallStep.entries.filter { it in pending })
    }

    /** 单独重跑某一步。 */
    suspend fun reinstall(step: InstallStep) = installSteps(listOf(step))

    suspend fun installSteps(steps: List<InstallStep>) {
        // 有任务在跑就拒绝新任务（取消也走这个判断：cancel() 不会立刻清 job）
        if (job?.isActive == true) return
        cancelRequested = false
        val current = settings.snapshot()
        val started = scope.launch {
            try {
                for (step in steps) {
                    if (cancelRequested) throw CancellationException("已取消")
                    if (step == InstallStep.NODE && !current.installNode) {
                        updateStep(step, StepState.SKIPPED, detail = "已跳过")
                        continue
                    }
                    // 任何一步都先把内置运行时铺好（幂等），避免用户只重跑某一步时缺依赖
                    runCatching { proot.stageBootRuntime() }
                    markRunning(step)
                    val detail = withContext(Dispatchers.IO) { runStep(step, current) }
                    updateStep(step, StepState.DONE, detail = detail, progress = 1f)
                    log("✔ ${step.title}：完成（$detail）")
                }
                _state.update { it.copy(running = null, message = "安装完成") }
            } catch (cancelled: CancellationException) {
                // 被取消的步骤不能留在 RUNNING：它已经不会继续跑了，界面得允许重试
                _state.value.running?.let { step ->
                    updateStep(step, StepState.PENDING, detail = "已取消")
                }
                _state.update { it.copy(running = null, message = "已取消") }
                throw cancelled
            } catch (error: Exception) {
                val running = _state.value.running
                if (running != null) {
                    updateStep(running, StepState.FAILED, error = error.message ?: error.toString())
                    log("✘ ${running.title}：失败 — ${error.message}")
                }
                _state.update { it.copy(running = null, message = error.message ?: "安装失败") }
            } finally {
                // 取消时这里也在取消的协程里：必须用 NonCancellable，否则状态刷不回去
                withContext(NonCancellable) {
                    persistState()
                    refresh()
                }
            }
        }
        job = started
        started.join()
        if (job === started) job = null
    }

    fun cancel() {
        cancelRequested = true
        _state.update { it.copy(message = "正在取消…") }
    }

    /** 更新 opencode 到最新版（只跑 OPENCODE 步骤）。 */
    suspend fun updateOpencode() = reinstall(InstallStep.OPENCODE)

    /** 容器目录所在分区的可用空间（-1 表示读不到）。 */
    fun freeSpaceBytes(): Long = try {
        android.os.StatFs(paths.home.absolutePath).availableBytes
    } catch (_: Exception) {
        -1L
    }

    /** 完整环境大约占 1.2~1.8 GB，留 2 GB 比较稳妥。 */
    fun hasEnoughSpace(): Boolean {
        val free = freeSpaceBytes()
        return free < 0 || free > 2L * 1024 * 1024 * 1024
    }

    /** 删除容器环境。调用方负责先停服务。 */
    suspend fun uninstallEnvironment() = withContext(Dispatchers.IO) {
        if (isBusy) {
            cancel()
            log("等待当前任务停止后再清理…")
            runCatching { job?.join() }
            if (job?.isActive == true) {
                throw IllegalStateException("安装任务还在运行，请稍后再试")
            }
        }
        log("开始清理容器环境…")
        FileOps.deleteTree(paths.rootfs)
        // 半途留下的中间目录也要清，否则「已删除」之后还能剩几百 MB
        FileOps.deleteTree(File(paths.home, "ubuntu.new"))
        FileOps.deleteTree(File(paths.home, "ubuntu.old"))
        FileOps.deleteTree(paths.tmpDir)
        FileOps.deleteTree(paths.libDir)
        FileOps.deleteTree(paths.downloadDir)
        FileOps.deleteTree(paths.shmDir)
        paths.stateFile.delete()
        _state.value = InstallSnapshot()
        _versions.value = emptyMap()
        log("容器环境已删除（工作区文件不受影响）")
    }

    /** 读取可展示的版本信息。 */
    suspend fun loadVersions(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, String>()
        val state = readStateJson()
        state.optJSONObject("versions")?.let { versions ->
            for (key in versions.keys()) result[key] = versions.optString(key)
        }
        if (result["ubuntu"].isNullOrBlank()) {
            val osRelease = File(paths.rootfs, "etc/os-release")
            if (osRelease.isFile) {
                osRelease.readLines().firstOrNull { it.startsWith("PRETTY_NAME=") }
                    ?.substringAfter('=')?.trim('"')?.let { result["ubuntu"] = it }
            }
        }
        result
    }

    // ------------------------------------------------------------------ 步骤实现

    private suspend fun runStep(step: InstallStep, config: AppSettings): String = when (step) {
        InstallStep.BOOT -> stepBoot()
        InstallStep.ROOTFS -> stepRootfs(config)
        InstallStep.TOOLS -> stepTools(config)
        InstallStep.NODE -> stepNode(config)
        InstallStep.OPENCODE -> stepOpencode(config)
        InstallStep.CONFIG -> stepConfig(config)
    }

    private suspend fun stepBoot(): String {
        log("准备 proot 运行时…")
        proot.stageBootRuntime()
        log("proot: ${paths.prootBin.length()} 字节，loader: ${paths.prootLoader.length()} 字节")
        return "proot 已就绪"
    }

    private suspend fun stepRootfs(config: AppSettings): String {
        // 峰值用量：下载包 + 新 rootfs + 旧 rootfs 同时存在，1.2 GB 是个务实下限
        val free = freeSpaceBytes()
        if (free > 0 && free < MIN_FREE_BYTES) {
            throw IllegalStateException(
                "存储空间不足：至少需要 ${MIN_FREE_BYTES / 1024 / 1024} MB 空闲（当前 ${free / 1024 / 1024} MB）",
            )
        }
        val mirror = Mirrors.find(Mirrors.ubuntu, config.ubuntuMirror)
        setProgress(0.01f, "查找镜像文件…")
        val artifact = try {
            repos.ubuntuBase(mirror)
        } catch (error: Exception) {
            log("镜像 ${mirror.name} 不可用（${error.message}），自动换一个…")
            val fallback = Mirrors.ubuntu.firstOrNull { it.id != mirror.id } ?: throw error
            repos.ubuntuBase(fallback).also { log("改用 ${fallback.name}") }
        }
        val sizeText = if (artifact.size > 0) "${artifact.size / 1024 / 1024} MB" else "大小未知"
        log("rootfs：${artifact.fileName}（$sizeText）来自 ${mirror.name}")

        // 备选地址直接用同一个文件名拼，不再逐个镜像去拉目录列表（那会让下载晚十几秒）
        val urls = buildList {
            add(artifact.url)
            Mirrors.ubuntu.filter { it.id != mirror.id }.forEach { add("${it.base}/${artifact.fileName}") }
        }.distinct()

        val target = File(paths.downloadDir, artifact.fileName)
        val expected = Downloader.ExpectedHash.sha256(artifact.sha256)
        if (expected == null) log("提示：该镜像没有提供 SHA256SUMS，本次跳过摘要校验")
        val file = downloads.fetchFrom(urls, target, expected, isCanceled = { cancelRequested }) { progress ->
            val detail = if (progress.known) {
                "下载中 ${progress.bytes / 1024 / 1024}MB / ${progress.total / 1024 / 1024}MB"
            } else {
                "下载中 ${progress.bytes / 1024 / 1024}MB"
            }
            setProgress((progress.fraction * 0.7f).coerceIn(0f, 0.7f), detail)
        }
        if (expected != null) log("rootfs 摘要校验通过")

        // 先解压到 ubuntu.new：自检通过后再整体替换，失败则回滚，避免把能用的环境毁掉
        setProgress(0.72f, "解压中…")
        val staging = File(paths.home, "ubuntu.new")
        FileOps.deleteTree(staging)
        staging.mkdirs()
        val stats = TarExtract.extract(
            archive = file,
            dest = staging,
            isActive = { !cancelRequested },
            onProgress = { s ->
                val fraction = if (artifact.size > 0) (s.bytes.toFloat() / (artifact.size * 4f)) else 0f
                setProgress(0.72f + fraction.coerceIn(0f, 0.2f), "解压 ${s.files} 个文件…")
            },
        )
        log("解压完成：${stats.files} 个文件，${stats.bytes / 1024 / 1024} MB" +
            if (stats.skippedLinks > 0 || stats.escaped > 0) {
                "（跳过链接 ${stats.skippedLinks} 个，越界条目 ${stats.escaped} 个）"
            } else {
                ""
            })

        val backup = FileOps.beginSwap(staging, paths.rootfs)
        try {
            writeGuestFile("/etc/profile.d/90-pocket.sh", assetText("scripts/profile.sh"), 0b110_100_100)
            net.writeResolvConf()
            installSystemCaBundle()

            setProgress(0.95f, "自检…")
            val check = shell.run(
                "uname -m && . /etc/os-release && echo \$PRETTY_NAME && id -u",
                config,
                timeoutMs = 90_000,
                isCanceled = { cancelRequested },
            )
            if (!check.success) {
                throw IllegalStateException("rootfs 自检失败：${check.text.takeLast(400)}")
            }
            val arch = check.lines.firstOrNull()?.trim().orEmpty()
            if (arch != "aarch64") throw IllegalStateException("rootfs 架构异常：$arch")
        } catch (error: Exception) {
            // 自检/写配置失败：把旧环境换回来（如果本来就没有旧环境，就清掉这份坏的）
            runCatching { FileOps.rollbackSwap(backup, paths.rootfs) }
                .onFailure { log("回滚失败，请到设置里删除环境后重装：${it.message}") }
            throw error
        }
        FileOps.finishSwap(backup)

        // 自检通过才删下载包（删早了失败就得重下 30 MB）
        file.delete()
        saveVersion("ubuntu", artifact.version)
        return "Ubuntu ${artifact.version}"
    }

    private suspend fun stepTools(config: AppSettings): String {
        val apt = Mirrors.find(Mirrors.apt, config.aptMirror)
        net.writeResolvConf()
        installSystemCaBundle()
        // apt 源是 HTTPS：没有 CA 就没有证书校验，明确失败，绝不悄悄退回明文源
        val ca = File(paths.rootfs, CaBundle.GUEST_PATH)
        if (CaBundle.needsInjection(ca.isFile, if (ca.isFile) ca.length() else 0)) {
            throw IllegalStateException(
                "容器里没有可用的 CA 证书，无法安全地安装软件包：" +
                    "请确认设备系统证书可读（注入失败会打日志），或换一个支持 HTTPS 的镜像源后重试。",
            )
        }
        if (apt.base.startsWith("http://")) {
            throw IllegalStateException("当前 apt 源是明文 http（${apt.name}），请在安装页换成 https 源后重试。")
        }
        val distro = detectUbuntuCodename() ?: "noble"
        log("apt 源：${apt.name}（${apt.base}，发行版 $distro）")
        val script = assetText("scripts/setup-apt.sh")
            .replace("@APT_MIRROR@", apt.base)
            .replace("@DISTRO@", distro)
        writeGuestFile("/root/.pocket/setup-apt.sh", script, 0b111_101_101)
        writeGuestFile("/root/.pocket/install-tools.sh", assetText("scripts/install-tools.sh"), 0b111_101_101)

        setProgress(0.1f, "刷新软件源…")
        val update = shell.run(
            "/root/.pocket/setup-apt.sh",
            config,
            timeoutMs = 600_000,
            isCanceled = { cancelRequested },
        ) { line -> log("apt: $line") }
        if (!update.success) {
            val text = update.text
            val hint = if (text.contains("No system certificates", ignoreCase = true) || text.contains("Certificate verification failed", ignoreCase = true)) {
                "（容器里的 CA 证书不可用：重跑第 2 步「安装 Ubuntu 环境」会重新注入系统证书）"
            } else {
                ""
            }
            throw IllegalStateException("apt update 失败$hint：${text.takeLast(400)}")
        }

        setProgress(0.5f, "安装基础工具…")
        val install = shell.run(
            "/root/.pocket/install-tools.sh",
            config,
            timeoutMs = 900_000,
            isCanceled = { cancelRequested },
        ) { line -> log("apt: $line") }
        if (!install.success) {
            val text = install.text
            val hint = when {
                text.contains("dpkg was interrupted", ignoreCase = true) ->
                    "（dpkg 中断过，重跑本步骤会自动修复）"
                text.contains("CA 证书") || text.contains("No system certificates", ignoreCase = true) ->
                    "（容器里缺少 CA 证书：先重跑第 2 步「安装 Ubuntu 环境」注入系统证书，再回来重试）"
                else -> ""
            }
            throw IllegalStateException("工具安装失败$hint：${text.takeLast(400)}")
        }
        saveVersion("tools", "ready")
        return "git / curl / ripgrep 已就绪"
    }

    private suspend fun stepNode(config: AppSettings): String {
        val mirror = Mirrors.find(Mirrors.node, config.nodeMirror)
        setProgress(0.02f, "解析 Node 版本…")
        val artifact = try {
            repos.nodeLts(mirror)
        } catch (error: Exception) {
            log("镜像 ${mirror.name} 不可用（${error.message}），自动换一个…")
            val fallback = Mirrors.node.firstOrNull { it.id != mirror.id } ?: throw error
            repos.nodeLts(fallback).also { log("改用 ${fallback.name}") }
        }
        log("Node：${artifact.version}（${artifact.fileName}）来自 ${mirror.name}")

        val urls = buildList {
            add(artifact.url)
            Mirrors.node.filter { it.id != mirror.id }.forEach {
                add("${it.base}/${artifact.version}/${artifact.fileName}")
            }
        }.distinct()

        val target = File(paths.downloadDir, artifact.fileName)
        val expected = Downloader.ExpectedHash.sha256(artifact.sha256)
        if (expected == null) log("提示：该镜像没有 SHASUMS256.txt，本次跳过摘要校验")
        val file = downloads.fetchFrom(urls, target, expected, isCanceled = { cancelRequested }) { progress ->
            setProgress(
                (progress.fraction * 0.8f).coerceIn(0f, 0.8f),
                "下载 Node ${progress.bytes / 1024 / 1024}MB / ${(progress.total / 1024 / 1024).coerceAtLeast(0)}MB",
            )
        }
        if (expected != null) log("Node 摘要校验通过")

        setProgress(0.82f, "解压 Node…")
        // 换镜像/LTS 版本时把旧 Node 清干净，否则 include/lib 里头和库会混版本
        listOf(
            "usr/local/lib/node_modules",
            "usr/local/include/node",
            "usr/local/share/doc/node",
            "usr/local/share/man/man1/node.1",
        ).forEach { relative -> FileOps.deleteTree(File(paths.rootfs, relative)) }
        listOf("node", "npm", "npx").forEach { name -> File(paths.rootfs, "usr/local/bin/$name").delete() }
        val dest = File(paths.rootfs, "usr/local")
        TarExtract.extract(
            archive = file,
            dest = dest,
            strip = 1,
            isActive = { !cancelRequested },
            onProgress = { s ->
                setProgress(0.82f + (s.files % 100) / 500f, "解压 ${s.files} 个文件…")
            },
        )
        runCatching { Os.chmod(File(dest, "bin/node").absolutePath, 0b111_101_101) }
        val npmBin = File(dest, "lib/node_modules/npm/bin/npm-cli.js")
        if (npmBin.isFile) {
            File(dest, "bin/npm").delete()
            File(dest, "bin/npx").delete()
            runCatching {
                Os.symlink("../lib/node_modules/npm/bin/npm-cli.js", File(dest, "bin/npm").absolutePath)
                Os.symlink("../lib/node_modules/npm/bin/npx-cli.js", File(dest, "bin/npx").absolutePath)
            }
        }
        file.delete()

        setProgress(0.95f, "自检…")
        val check = shell.run("node -v", config, timeoutMs = 120_000, isCanceled = { cancelRequested })
        if (!check.success) throw IllegalStateException("Node 自检失败：${check.text.takeLast(300)}")

        // npm 是 Node 包自带的，opencode 本体并不依赖它。个别 Node 大版本在 proot 下的
        // 模块解析会失败（CI 里 Node 24 的 npm 报 Cannot find module …/walk-up-path/…），
        // 这不该让整个安装中断：只记一条日志，用户真要用 npm 时可以在终端里看到原因。
        val npm = shell.run("npm -v", config, timeoutMs = 120_000, isCanceled = { cancelRequested })
        if (!npm.success) {
            log("注意：npm 自检失败（不影响 opencode 本体）：${npm.text.lineSequence().firstOrNull()?.trim().orEmpty()}")
        }

        saveVersion("node", artifact.version)
        return "Node ${check.lines.firstOrNull()?.trim() ?: artifact.version}"
    }

    private suspend fun stepOpencode(config: AppSettings): String {
        val registry = Mirrors.find(Mirrors.npm, config.npmMirror)
        setProgress(0.02f, "查询最新版本…")
        val primary = registry.base
        val primaryVersion = runCatching { repos.opencodeVersion(primary) }.getOrNull()
        val registryBase: String
        val version: String
        if (primaryVersion != null) {
            registryBase = primary
            version = primaryVersion
        } else {
            val fallback = Mirrors.npm.firstOrNull { it.id != registry.id } ?: throw IllegalStateException("所有 npm 镜像都不可用")
            log("registry ${registry.name} 不可用，改用 ${fallback.name}")
            registryBase = fallback.base
            version = repos.opencodeVersion(registryBase)
        }
        log("OpenCode 最新版：$version（registry: $registryBase）")

        val tarball = repos.opencodeTarball(registryBase, version)
        val urls = buildList {
            add(tarball.url)
            Mirrors.npm.filter { it.base != registryBase }.forEach {
                add("${it.base}/opencode-linux-arm64/-/${tarball.fileName}")
            }
        }.distinct()
        val target = File(paths.downloadDir, tarball.fileName)
        // npm 的 dist.shasum 是 sha1；拿不到就不校验（HTTPS 仍在）
        val expected = Downloader.ExpectedHash.sha1(repos.opencodeSha1(registryBase, version))
        if (expected == null) log("提示：registry 没有提供 shasum，本次跳过摘要校验")

        val file = downloads.fetchFrom(urls, target, expected, isCanceled = { cancelRequested }) { progress ->
            setProgress(
                (progress.fraction * 0.85f).coerceIn(0f, 0.85f),
                "下载 ${progress.bytes / 1024 / 1024}MB / ${(progress.total / 1024 / 1024).coerceAtLeast(0)}MB",
            )
        }
        if (expected != null) log("opencode 摘要校验通过")

        setProgress(0.88f, "安装可执行文件…")
        val binDir = File(paths.rootfs, "usr/local/lib/opencode")
        FileOps.deleteTree(binDir)
        binDir.mkdirs()
        TarExtract.extract(
            archive = file,
            dest = binDir,
            strip = 1,
            isActive = { !cancelRequested },
            include = { relative -> relative == "bin/opencode" },
        )
        val binary = File(binDir, "bin/opencode")
        if (!binary.isFile) throw IllegalStateException("压缩包里没有找到 bin/opencode")
        runCatching { Os.chmod(binary.absolutePath, 0b111_101_101) }
        val link = File(paths.rootfs, "usr/local/bin/opencode")
        link.parentFile?.mkdirs()
        link.delete()
        runCatching { Os.symlink("../lib/opencode/bin/opencode", link.absolutePath) }
        file.delete()

        setProgress(0.94f, "自检（首次运行会慢一些）…")
        val check = shell.run("opencode --version", config, timeoutMs = 240_000, isCanceled = { cancelRequested }) { line ->
            log("opencode: $line")
        }
        if (!check.success) throw IllegalStateException("opencode 无法运行：${check.text.takeLast(500)}")
        val detected = check.lines.firstOrNull { it.trim().isNotEmpty() }?.trim() ?: version
        saveVersion("opencode", detected)
        return "OpenCode $detected"
    }

    private suspend fun stepConfig(config: AppSettings): String {
        writeGuestFile("/root/.pocket/setup-env.sh", assetText("scripts/setup-env.sh"), 0b111_101_101)
        val env = buildMap {
            put("POCKET_NPM_REGISTRY", Mirrors.find(Mirrors.npm, config.npmMirror).base)
        }
        val result = shell.run(
            "/root/.pocket/setup-env.sh",
            config,
            extraEnv = env,
            timeoutMs = 120_000,
            isCanceled = { cancelRequested },
        )
        if (!result.success) throw IllegalStateException("容器配置失败：${result.text.takeLast(300)}")

        // opencode 配置（模型、密钥、权限、服务器）
        val json = OpencodeConfig.build(config)
        writeGuestFile(PocketPaths.Guest.OPENCODE_CONFIG, json, 0b110_000_000)
        log("已写入 opencode 配置（${json.length} 字节）")
        saveVersion("config", "ready")
        return "配置已写入"
    }

    // ------------------------------------------------------------------ 工具方法

    private suspend fun detectUbuntuCodename(): String? = withContext(Dispatchers.IO) {
        val osRelease = File(paths.rootfs, "etc/os-release")
        if (!osRelease.isFile) return@withContext null
        val value = osRelease.readLines().firstOrNull { it.startsWith("VERSION_CODENAME=") }
            ?.substringAfter('=')?.trim('"')
        // 这个值会被拼进 apt 源脚本：必须是纯小写字母，否则可能被投毒的 rootfs 注入命令
        value?.takeIf { it.matches(Regex("^[a-z]{3,20}$")) }
    }

    private fun writeGuestFile(guestPath: String, content: String, mode: Int) {
        val file = File(paths.rootfs, guestPath.trimStart('/'))
        file.parentFile?.mkdirs()
        file.writeText(content)
        runCatching { Os.chmod(file.absolutePath, mode) }
    }

    private fun assetText(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun log(line: String) {
        val stamped = "[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $line"
        _logs.tryEmit(stamped)
        synchronized(recent) {
            recent.addLast(stamped)
            while (recent.size > MAX_LOG_LINES) recent.removeFirst()
            _recentLogs.value = recent.toList()
        }
        runCatching {
            rotateInstallLogIfNeeded()
            paths.installLog.appendText(stamped + "\n")
        }
    }

    /** install.log 超过 2 MB 就轮转一次，避免长期使用无限增长。 */
    private fun rotateInstallLogIfNeeded() {
        val log = paths.installLog
        if (!log.isFile || log.length() < MAX_LOG_BYTES) return
        val backup = File(log.parentFile, log.name + ".1")
        backup.delete()
        log.renameTo(backup)
    }

    private fun markRunning(step: InstallStep) {
        _state.update { snapshot ->
            snapshot.copy(
                running = step,
                message = step.title,
                steps = snapshot.steps.map {
                    if (it.step == step) it.copy(state = StepState.RUNNING, progress = 0f, error = null) else it
                },
            )
        }
        log("▶ ${step.title}：${step.subtitle}")
    }

    private fun updateStep(
        step: InstallStep,
        state: StepState,
        detail: String = "",
        progress: Float = -1f,
        error: String? = null,
    ) {
        _state.update { snapshot ->
            snapshot.copy(
                steps = snapshot.steps.map {
                    if (it.step == step) it.copy(state = state, detail = detail, progress = progress, error = error) else it
                },
            )
        }
    }

    private fun setProgress(value: Float, message: String) {
        _state.update { snapshot ->
            val running = snapshot.running ?: return@update snapshot.copy(message = message)
            snapshot.copy(
                message = message,
                steps = snapshot.steps.map {
                    if (it.step == running) it.copy(progress = value) else it
                },
            )
        }
    }

    private fun isInstalled(steps: List<StepSnapshot>, config: AppSettings): Boolean =
        InstallState.isInstalled(steps, config.installNode)

    /** 从磁盘探测真实安装情况（升级/手动改动后也能自愈）。注意 guest 路径要带 root/ 前缀。 */
    private fun detectOnDisk(): Map<InstallStep, Boolean> = linkedMapOf(
        InstallStep.BOOT to paths.bootRuntimeStaged(),
        InstallStep.ROOTFS to paths.rootfsLooksInstalled(),
        InstallStep.TOOLS to File(paths.rootfs, "usr/bin/git").isFile,
        InstallStep.NODE to File(paths.rootfs, "usr/local/bin/node").isFile,
        InstallStep.OPENCODE to File(paths.rootfs, PocketPaths.Guest.OPENCODE_BIN.trimStart('/')).isFile,
        InstallStep.CONFIG to File(paths.rootfs, PocketPaths.Guest.OPENCODE_CONFIG.trimStart('/')).isFile,
    )

    // ------------------------------------------------------------------ 持久化

    private fun readStateJson(): JSONObject = try {
        if (paths.stateFile.isFile) JSONObject(paths.stateFile.readText()) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun readState(): Map<String, StepSnapshot> {
        val json = readStateJson()
        val steps = json.optJSONObject("steps") ?: return emptyMap()
        val result = LinkedHashMap<String, StepSnapshot>()
        for (step in InstallStep.entries) {
            val item = steps.optJSONObject(step.id) ?: continue
            val state = runCatching { StepState.valueOf(item.optString("state", "PENDING")) }
                .getOrDefault(StepState.PENDING)
            result[step.id] = StepSnapshot(
                step = step,
                state = state,
                detail = item.optString("detail"),
                progress = if (state == StepState.DONE) 1f else -1f,
                error = item.optString("error").takeIf { it.isNotBlank() },
            )
        }
        return result
    }

    private fun persistState() {
        val json = JSONObject()
        val steps = JSONObject()
        for (item in _state.value.steps) {
            steps.put(
                item.step.id,
                JSONObject().apply {
                    put("state", item.state.name)
                    put("detail", item.detail)
                    item.error?.let { put("error", it) }
                },
            )
        }
        json.put("steps", steps)
        json.put("versions", JSONObject(_versions.value as Map<*, *>))
        json.put("updatedAt", System.currentTimeMillis())
        runCatching { paths.stateFile.writeText(json.toString(2)) }
    }

    private fun saveVersion(key: String, value: String) {
        _versions.value = _versions.value + (key to value)
        persistState()
    }

    /**
     * 把 Android 系统里的 CA 证书拼成一份 PEM 包写进 rootfs。
     *
     * Ubuntu base 镜像里没有 ca-certificates，apt 走 https 源会直接失败；
     * 这里直接借系统的证书库（/system/etc/security/cacerts 每张证书一个 PEM 文件），
     * 装好 ca-certificates 之后由 update-ca-certificates 覆盖成正式版本。
     */
    private fun installSystemCaBundle() {
        val sources = listOf(
            File("/system/etc/security/cacerts"),
            File("/apex/com.android.conscrypt/cacerts"),
        ).filter { it.isDirectory }
        if (sources.isEmpty()) return

        val target = File(paths.rootfs, CaBundle.GUEST_PATH)
        if (!CaBundle.needsInjection(target.isFile, if (target.isFile) target.length() else 0)) return
        target.parentFile?.mkdirs()

        var count = 0
        try {
            java.io.FileOutputStream(target).use { output ->
                val seen = HashSet<String>()
                for (directory in sources) {
                    val files = directory.listFiles()?.sortedBy { it.name } ?: continue
                    for (file in files) {
                        if (!file.isFile || !seen.add(file.name)) continue
                        // 按字节拼接：证书是 PEM 文本，但不该假设编码/换行
                        file.inputStream().use { input -> input.copyTo(output) }
                        output.write('\n'.code)
                        count++
                    }
                }
            }
        } catch (error: Exception) {
            log("注入系统 CA 失败：${error.message}")
        }
        if (count == 0) {
            target.delete()
            log("系统 CA 目录里没有可用的证书，apt 将退回 http 源")
        } else {
            log("已注入系统 CA 证书 $count 张（${target.length() / 1024} KB）")
        }
    }

    private companion object {
        const val MAX_LOG_LINES = 600
        const val MAX_LOG_BYTES = 2L shl 20 // 2 MiB

        /** 安装期间的最小空闲空间（下载包 + 新 rootfs + 旧 rootfs 的峰值）。 */
        const val MIN_FREE_BYTES = 1200L * 1024 * 1024
    }
}
