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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _logs = MutableSharedFlow<String>(replay = 300, extraBufferCapacity = 512)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _versions = MutableStateFlow<Map<String, String>>(emptyMap())
    val versions: StateFlow<Map<String, String>> = _versions.asStateFlow()

    private var job: Job? = null

    /** 用户点了取消：下载与解压都会尽快退出（协程取消 + 显式检查双保险）。 */
    @Volatile
    private var cancelRequested = false

    // ------------------------------------------------------------------ 对外 API

    suspend fun refresh() {
        val records = readState()
        val disk = detectOnDisk()
        val merged = records.toMutableMap()
        for ((step, ok) in disk) {
            if (ok) merged[step.id] = merged[step.id] ?: StepSnapshot(step, StepState.DONE)
            else merged.remove(step.id)
        }
        val steps = InstallStep.entries.map { step ->
            val record = merged[step.id]
            when {
                record == null -> StepSnapshot(step)
                record.state == StepState.DONE || disk[step] == true -> StepSnapshot(
                    step,
                    StepState.DONE,
                    record.detail,
                    progress = 1f,
                )
                else -> record
            }
        }
        _state.value = _state.value.copy(
            steps = steps,
            running = null,
            installed = isInstalled(steps, settings.snapshot()),
        )
        _versions.value = loadVersions()
    }

    /** 一键安装：按依赖顺序跑所有还没完成的步骤。 */
    suspend fun installAll() {
        val pending = _state.value.steps
            .filter { it.state != StepState.DONE }
            .map { it.step }
            .toMutableList()
        val ordered = InstallStep.entries.filter { it in pending }
        installSteps(ordered)
    }

    /** 单独重跑某一步。 */
    suspend fun reinstall(step: InstallStep) = installSteps(listOf(step))

    suspend fun installSteps(steps: List<InstallStep>) {
        if (job?.isActive == true) return
        cancelRequested = false
        val current = settings.snapshot()
        val job = scope.launch {
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
                _state.value = _state.value.copy(running = null, message = "安装完成")
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(running = null, message = "已取消")
                throw cancelled
            } catch (error: Exception) {
                val running = _state.value.running
                if (running != null) {
                    updateStep(running, StepState.FAILED, error = error.message ?: error.toString())
                    log("✘ ${running.title}：失败 — ${error.message}")
                }
                _state.value = _state.value.copy(running = null, message = error.message ?: "安装失败")
            } finally {
                persistState()
                refresh()
            }
        }
        this.job = job
        job.join()
    }

    fun cancel() {
        cancelRequested = true
        job?.cancel()
        job = null
        _state.value = _state.value.copy(running = null, message = "已取消")
    }

    val isBusy: Boolean get() = job?.isActive == true

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

    suspend fun uninstallEnvironment() = withContext(Dispatchers.IO) {
        log("开始清理容器环境…")
        paths.rootfs.deleteRecursively()
        paths.tmpDir.deleteRecursively()
        paths.libDir.deleteRecursively()
        paths.stateFile.delete()
        _state.value = InstallSnapshot()
        _versions.value = emptyMap()
        log("容器环境已删除")
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
        val free = freeSpaceBytes()
        if (free in 1 until 400L * 1024 * 1024) {
            throw IllegalStateException("存储空间不足：至少需要 400 MB 空闲（当前 ${free / 1024 / 1024} MB）")
        }
        val mirror = Mirrors.find(Mirrors.ubuntu, config.ubuntuMirror)
        setProgress(0.01f, "查找镜像文件…")
        var artifact = try {
            repos.ubuntuBase(mirror)
        } catch (error: Exception) {
            log("镜像 ${mirror.name} 不可用（${error.message}），尝试自动切换…")
            val fallback = Mirrors.ubuntu.firstOrNull { it.id != mirror.id } ?: throw error
            repos.ubuntuBase(fallback).also { log("改用 ${fallback.name}") }
        }
        log("rootfs：${artifact.fileName}（${artifact.size / 1024 / 1024} MB）来自 ${mirror.name}")

        val target = File(paths.downloadDir, artifact.fileName)
        val urls = buildList {
            add(artifact.url)
            Mirrors.ubuntu.filter { it.id != config.ubuntuMirror }.forEach { m ->
                runCatching { repos.ubuntuBase(m) }.getOrNull()?.let { add(it.url) }
            }
        }.distinct()

        val file = downloads.fetchFrom(urls, target, isCanceled = { cancelRequested }) { progress ->
            setProgress((progress.fraction * 0.7f).coerceIn(0f, 0.7f), "下载中 ${progress.bytes / 1024 / 1024}MB / ${progress.total / 1024 / 1024}MB")
        }

        setProgress(0.72f, "解压中…")
        if (paths.rootfs.exists()) paths.rootfs.deleteRecursively()
        paths.rootfs.mkdirs()
        val stats = TarExtract.extract(
            archive = file,
            dest = paths.rootfs,
            isActive = { !cancelRequested },
            onProgress = { s ->
                val fraction = if (artifact.size > 0) (s.bytes.toFloat() / (artifact.size * 4f)) else -1f
                setProgress((0.72f + fraction.coerceIn(0f, 0.2f)), "解压 ${s.files} 个文件…")
            },
        )
        log("解压完成：${stats.files} 个文件，${stats.bytes / 1024 / 1024} MB")

        // 基础目录与解析器（先于 TOOLS 步骤，保证 apt 能解析域名）
        writeGuestFile("/etc/profile.d/90-pocket.sh", assetText("scripts/profile.sh"), 0b110_100_100)
        net.writeResolvConf()
        installSystemCaBundle()
        file.delete()

        setProgress(0.95f, "自检…")
        val check = shell.run("uname -m && . /etc/os-release && echo \$PRETTY_NAME && id -u", config, timeoutMs = 90_000)
        if (!check.success) {
            throw IllegalStateException("rootfs 自检失败：${check.text.takeLast(400)}")
        }
        val arch = check.lines.firstOrNull()?.trim().orEmpty()
        if (arch != "aarch64") throw IllegalStateException("rootfs 架构异常：$arch")
        saveVersion("ubuntu", artifact.version)
        return "Ubuntu $artifact.version"
    }

    private suspend fun stepTools(config: AppSettings): String {
        val apt = Mirrors.find(Mirrors.apt, config.aptMirror)
        net.writeResolvConf()
        installSystemCaBundle()
        // Ubuntu base 不带 CA；如果连系统 CA 也拿不到，就只能退回 http 源，否则 apt 会卡在 TLS 校验
        val hasCa = File(paths.rootfs, "etc/ssl/certs/ca-certificates.crt").let { it.isFile && it.length() > 0 }
        val aptBase = if (hasCa) apt.base else apt.base.replace("https://", "http://")
        log("apt 源：${apt.name}（$aptBase${if (hasCa) "" else "，无 CA 暂用 http"}）")
        val distro = detectUbuntuCodename() ?: "noble"
        val script = assetText("scripts/setup-apt.sh")
            .replace("@APT_MIRROR@", aptBase)
            .replace("@DISTRO@", distro)
        writeGuestFile("/root/.pocket/setup-apt.sh", script, 0b111_101_101)
        writeGuestFile("/root/.pocket/install-tools.sh", assetText("scripts/install-tools.sh"), 0b111_101_101)

        setProgress(0.1f, "刷新软件源…")
        val update = shell.run("/root/.pocket/setup-apt.sh", config, timeoutMs = 600_000) { line -> log("apt: $line") }
        if (!update.success) throw IllegalStateException("apt update 失败：${update.text.takeLast(400)}")

        setProgress(0.5f, "安装基础工具…")
        val install = shell.run("/root/.pocket/install-tools.sh", config, timeoutMs = 900_000) { line -> log("apt: $line") }
        if (!install.success) throw IllegalStateException("工具安装失败：${install.text.takeLast(400)}")
        saveVersion("tools", "ready")
        return "git / curl / ripgrep 已就绪"
    }

    private suspend fun stepNode(config: AppSettings): String {
        val mirror = Mirrors.find(Mirrors.node, config.nodeMirror)
        setProgress(0.02f, "解析 Node 版本…")
        val artifact = try {
            repos.nodeLts(mirror)
        } catch (error: Exception) {
            val fallback = Mirrors.node.firstOrNull { it.id != mirror.id } ?: throw error
            repos.nodeLts(fallback)
        }
        log("Node：${artifact.version}（${artifact.fileName}）来自 ${mirror.name}")
        val target = File(paths.downloadDir, artifact.fileName)
        val urls = buildList {
            add(artifact.url)
            Mirrors.node.filter { it.id != config.nodeMirror }.forEach { m ->
                runCatching { repos.nodeLts(m) }.getOrNull()?.let { add(A("${m.base}/${artifact.version}/node-${artifact.version}-linux-arm64.tar.xz")) }
            }
        }.distinct()

        val file = downloads.fetchFrom(urls, target, isCanceled = { cancelRequested }) { progress ->
            setProgress((progress.fraction * 0.8f).coerceIn(0f, 0.8f), "下载 Node ${progress.bytes / 1024 / 1024}MB / ${progress.total / 1024 / 1024}MB")
        }

        setProgress(0.82f, "解压 Node…")
        if (File(paths.rootfs, "usr/local/bin/node").exists()) {
            File(paths.rootfs, "usr/local/lib/node_modules").deleteRecursively()
        }
        val dest = File(paths.rootfs, "usr/local")
        TarExtract.extract(file, dest, strip = 1, onProgress = { s ->
            setProgress(0.82f + (s.files % 100) / 500f, "解压 ${s.files} 个文件…")
        })
        Os.chmod(File(dest, "bin/node").absolutePath, 0b111_101_101)
        val npmBin = File(dest, "lib/node_modules/npm/bin/npm-cli.js")
        if (npmBin.isFile) {
            File(dest, "bin/npm").delete()
            File(dest, "bin/npx").delete()
            Os.symlink("../lib/node_modules/npm/bin/npm-cli.js", File(dest, "bin/npm").absolutePath)
            Os.symlink("../lib/node_modules/npm/bin/npx-cli.js", File(dest, "bin/npx").absolutePath)
        }
        file.delete()

        setProgress(0.95f, "自检…")
        val check = shell.run("node -v && npm -v", config, timeoutMs = 120_000)
        if (!check.success) throw IllegalStateException("Node 自检失败：${check.text.takeLast(300)}")
        saveVersion("node", artifact.version)
        return "Node ${check.lines.firstOrNull()?.trim() ?: artifact.version}"
    }

    private suspend fun stepOpencode(config: AppSettings): String {
        val registry = Mirrors.find(Mirrors.npm, config.npmMirror)
        setProgress(0.02f, "查询最新版本…")
        val version = try {
            repos.opencodeVersion(registry.base)
        } catch (error: Exception) {
            val fallback = Mirrors.npm.firstOrNull { it.id != registry.id } ?: throw error
            repos.opencodeVersion(fallback.base)
        }
        log("OpenCode 最新版：$version（registry: ${registry.name}）")
        val tarball = repos.opencodeTarball(registry.base, version)
        val urls = buildList {
            add(tarball.url)
            Mirrors.npm.filter { it.id != registry.id }.forEach { it ->
                add("${it.base}/opencode-linux-arm64/-/opencode-linux-arm64-$version.tgz")
            }
        }.distinct()
        val target = File(paths.downloadDir, tarball.fileName)
        val file = downloads.fetchFrom(urls, target, isCanceled = { cancelRequested }) { progress ->
            setProgress((progress.fraction * 0.85f).coerceIn(0f, 0.85f), "下载 ${progress.bytes / 1024 / 1024}MB / ${progress.total / 1024 / 1024}MB")
        }

        setProgress(0.88f, "安装可执行文件…")
        val binDir = File(paths.rootfs, "usr/local/lib/opencode")
        binDir.deleteRecursively()
        binDir.mkdirs()
        TarExtract.extract(
            archive = file,
            dest = binDir,
            strip = 1,
            include = { relative -> relative == "bin/opencode" },
        )
        val binary = File(binDir, "bin/opencode")
        if (!binary.isFile) throw IllegalStateException("压缩包里没有找到 bin/opencode")
        Os.chmod(binary.absolutePath, 0b111_101_101)
        val link = File(paths.rootfs, "usr/local/bin/opencode")
        link.parentFile?.mkdirs()
        link.delete()
        Os.symlink("../lib/opencode/bin/opencode", link.absolutePath)
        file.delete()

        setProgress(0.94f, "自检（首次运行会慢一些）…")
        val check = shell.run("opencode --version", config, timeoutMs = 240_000) { line -> log("opencode: $line") }
        if (!check.success) throw IllegalStateException("opencode 无法运行：${check.text.takeLast(500)}")
        val detected = check.text.lines().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: version
        saveVersion("opencode", detected)
        return "OpenCode $detected"
    }

    private suspend fun stepConfig(config: AppSettings): String {
        writeGuestFile("/root/.pocket/setup-env.sh", assetText("scripts/setup-env.sh"), 0b111_101_101)
        val env = buildMap {
            put("POCKET_NPM_REGISTRY", Mirrors.find(Mirrors.npm, config.npmMirror).base)
        }
        val result = shell.run("/root/.pocket/setup-env.sh", config, extraEnv = env, timeoutMs = 120_000)
        if (!result.success) throw IllegalStateException("容器配置失败：${result.text.takeLast(300)}")

        // opencode 配置（模型、密钥、权限、服务器）
        val json = OpencodeConfig.build(config, proot.resolveWorkspace(config).guest)
        writeGuestFile(PocketPaths.Guest.OPENCODE_CONFIG, json, 0b110_000_000)
        log("已写入 opencode 配置（${json.length} 字节）")
        saveVersion("config", "ready")
        return "配置已写入"
    }

    // ------------------------------------------------------------------ 工具方法

    private fun A(url: String) = url

    private suspend fun detectUbuntuCodename(): String? = withContext(Dispatchers.IO) {
        val osRelease = File(paths.rootfs, "etc/os-release")
        if (!osRelease.isFile) return@withContext null
        osRelease.readLines().firstOrNull { it.startsWith("VERSION_CODENAME=") }
            ?.substringAfter('=')?.trim('"')
    }

    private fun writeGuestFile(guestPath: String, content: String, mode: Int) {
        val file = File(paths.rootfs, guestPath.trimStart('/'))
        file.parentFile?.mkdirs()
        file.writeText(content)
        try {
            Os.chmod(file.absolutePath, mode)
        } catch (_: Exception) {
        }
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

        val target = File(paths.rootfs, "etc/ssl/certs/ca-certificates.crt")
        if (target.isFile && target.length() > 0) return
        target.parentFile?.mkdirs()

        val builder = StringBuilder()
        val seen = HashSet<String>()
        for (directory in sources) {
            val files = directory.listFiles()?.sortedBy { it.name } ?: continue
            for (file in files) {
                if (!file.isFile || !seen.add(file.name)) continue
                runCatching { builder.append(file.readText()).append('\n') }
            }
        }
        if (builder.isNotEmpty()) {
            target.writeText(builder.toString())
            log("已注入系统 CA 证书（${target.length() / 1024} KB）")
        }
    }

    private fun assetText(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private fun log(line: String) {
        val stamped = "[${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}] $line"
        _logs.tryEmit(stamped)
        runCatching {
            paths.installLog.appendText(stamped + "\n")
        }
    }

    private fun markRunning(step: InstallStep) {
        _state.value = _state.value.copy(
            running = step,
            message = step.title,
            steps = _state.value.steps.map {
                if (it.step == step) it.copy(state = StepState.RUNNING, progress = 0f, error = null) else it
            },
        )
        log("▶ ${step.title}：${step.subtitle}")
    }

    private fun updateStep(step: InstallStep, state: StepState, detail: String = "", progress: Float = -1f, error: String? = null) {
        _state.value = _state.value.copy(
            steps = _state.value.steps.map {
                if (it.step == step) it.copy(state = state, detail = detail, progress = progress, error = error) else it
            },
        )
    }

    private fun setProgress(value: Float, message: String) {
        val running = _state.value.running ?: return
        _state.value = _state.value.copy(
            message = message,
            steps = _state.value.steps.map {
                if (it.step == running) it.copy(progress = value) else it
            },
        )
    }

    private fun isInstalled(steps: List<StepSnapshot>, config: AppSettings): Boolean =
        steps.filter { !it.step.optional || (it.step == InstallStep.NODE && config.installNode) }
            .all { it.state == StepState.DONE || (it.step == InstallStep.NODE && it.state == StepState.SKIPPED) }

    /** 从磁盘探测真实安装情况（升级/手动改动后也能自愈）。 */
    private fun detectOnDisk(): Map<InstallStep, Boolean> = linkedMapOf(
        InstallStep.BOOT to paths.bootRuntimeStaged(),
        InstallStep.ROOTFS to paths.rootfsLooksInstalled(),
        InstallStep.TOOLS to File(paths.rootfs, "usr/bin/git").isFile,
        InstallStep.NODE to File(paths.rootfs, "usr/local/bin/node").isFile,
        InstallStep.OPENCODE to File(paths.rootfs, "usr/local/lib/opencode/bin/opencode").isFile,
        InstallStep.CONFIG to File(paths.rootfs, ".config/opencode/opencode.json").isFile,
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
            val state = runCatching { StepState.valueOf(item.optString("state", "PENDING")) }.getOrDefault(StepState.PENDING)
            result[step.id] = StepSnapshot(step, state, item.optString("detail"), progress = if (state == StepState.DONE) 1f else -1f)
        }
        return result
    }

    private fun persistState() {
        val json = JSONObject()
        val steps = JSONObject()
        for (item in _state.value.steps) {
            steps.put(item.step.id, JSONObject().apply {
                put("state", item.state.name)
                put("detail", item.detail)
            })
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
}
