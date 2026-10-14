package dev.pocket.opencode.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 在容器里跑一条命令并流式回收输出（stdout+stderr 合并）。
 *
 * 几个必须做对的点（都是踩过的坑）：
 *   - 子进程的 stdin 立刻关掉：默认是管道且永不 EOF，`dpkg`/`cat`/任何读 stdin 的命令会永久挂死；
 *   - 超时用「看门狗杀进程」实现，而不是 `withTimeout`（阻塞的 readLine 不响应协程取消）；
 *   - 取消同样落到进程上（kill 掉 proot，`--kill-on-exit` 会带走整棵进程树）；
 *   - 输出行数有上限，避免 `yes` / 编译日志把内存打爆。
 */
class GuestShell(private val proot: Proot) {

    /**
     * 看门狗与取消监视跑在独立作用域：调用方的协程被取消时，阻塞中的 readLine
     * 不会响应取消 —— 必须靠外部协程把进程杀掉，否则 run() 永远不返回（线程 + 进程双泄漏）。
     */
    private val watcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Output(
        val exitCode: Int,
        val lines: List<String>,
        val timedOut: Boolean = false,
        val dropped: Int = 0,
    ) {
        val text: String get() = lines.joinToString("\n")
        val success: Boolean get() = exitCode == 0 && !timedOut
    }

    class TimeoutException(val partial: List<String>) :
        Exception("命令超时（已强制结束）")

    /**
     * @param keepLastLines 只保留最后 N 行（默认 4000），再多就丢掉最老的并计数
     * @param isCanceled 取消探测（由上层传入，例如安装页的取消按钮）
     */
    suspend fun run(
        command: String,
        config: AppSettings,
        extraEnv: Map<String, String> = emptyMap(),
        cwd: String = PocketPaths.Guest.ROOT,
        timeoutMs: Long = 0,
        keepLastLines: Int = 4000,
        isCanceled: () -> Boolean = { false },
        onLine: (String) -> Unit = {},
    ): Output {
        val process = withContext(Dispatchers.IO) {
            proot.start(command, config, extraEnv, cwd).also {
                // 不给 stdin 留管道：读 stdin 的命令立刻拿到 EOF 而不是挂死
                runCatching { it.outputStream.close() }
            }
        }
        val lines = ArrayDeque<String>()
        var dropped = 0
        var timedOut = false
        var cancelled = false

        try {
            // 看门狗（超时）与取消监视跑在独立作用域，不受调用方取消影响
            val watchdog = if (timeoutMs > 0) {
                watcherScope.launch {
                    delay(timeoutMs)
                    timedOut = true
                    runCatching { process.destroy() }
                    delay(1_500)
                    runCatching { process.destroyForcibly() }
                }
            } else {
                null
            }
            val cancelWatcher = watcherScope.launch {
                while (!cancelled && process.isAlive) {
                    if (isCanceled()) {
                        cancelled = true
                        runCatching { process.destroyForcibly() }
                        break
                    }
                    delay(200)
                }
            }

            try {
                withContext(Dispatchers.IO) {
                    val reader = BufferedReader(InputStreamReader(process.inputStream), 64 * 1024)
                    while (true) {
                        if (isCanceled() && !cancelled) {
                            cancelled = true
                            runCatching { process.destroyForcibly() }
                        }
                        val line = reader.readLine() ?: break
                        synchronized(lines) {
                            lines.addLast(line)
                            while (lines.size > keepLastLines) {
                                lines.removeFirst()
                                dropped++
                            }
                        }
                        onLine(line)
                    }
                }
                // 进程此时要么已经退出，要么被看门狗/取消杀掉
                runCatching { process.waitFor() }
            } finally {
                watchdog?.cancel()
                cancelWatcher.cancel()
            }
        } catch (cancelledException: CancellationException) {
            runCatching { process.destroyForcibly() }
            throw cancelledException
        } finally {
            withContext(NonCancellable) {
                if (process.isAlive) {
                    process.destroy()
                    delay(600)
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        }

        if (cancelled) throw CancellationException("命令已取消")
        if (timedOut) throw TimeoutException(lines.toList())
        return Output(
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            lines = lines.toList(),
            dropped = dropped,
        )
    }

    /** 便捷方法：跑一条命令并把输出拿到手（默认 5 分钟超时）。 */
    suspend fun runChecked(
        command: String,
        config: AppSettings,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 300_000,
        isCanceled: () -> Boolean = { false },
        onLine: (String) -> Unit = {},
    ): Output = run(command, config, extraEnv, timeoutMs = timeoutMs, isCanceled = isCanceled, onLine = onLine)

    /**
     * 交互式 shell：返回进程，调用方负责写 stdin 与读 stdout（stdin 保持打开）。
     */
    fun startInteractive(config: AppSettings, extraEnv: Map<String, String> = emptyMap()): Process =
        proot.start("/bin/bash --login", config, extraEnv)

    /** 读取一个文件在容器内的内容（用于读版本号之类），文件不存在返回 null。 */
    suspend fun readGuestFile(guestPath: String, config: AppSettings): String? {
        val output = run("cat '$guestPath' 2>/dev/null || true", config, timeoutMs = 30_000)
        val text = output.text.trim()
        return text.ifEmpty { null }
    }

    /** 判断 guest 路径是否存在。 */
    suspend fun exists(guestPath: String, config: AppSettings): Boolean {
        val output = run("test -e '$guestPath' && echo yes || echo no", config, timeoutMs = 30_000)
        return output.text.trim() == "yes"
    }

    /** 宿主侧目录大小（不跟随符号链接）。 */
    fun hostSize(dir: File): Long = FileOps.directorySize(dir)
}
