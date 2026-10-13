package dev.pocket.opencode.core

import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * 在容器里跑一条命令并流式回收输出（stdout+stderr 合并）。
 */
class GuestShell(private val proot: Proot) {

    data class Output(
        val exitCode: Int,
        val lines: List<String>,
        val timedOut: Boolean = false,
    ) {
        val text: String get() = lines.joinToString("\n")
        val success: Boolean get() = exitCode == 0 && !timedOut
    }

    class TimeoutException(val partial: List<String>) : Exception("命令超时")

    suspend fun run(
        command: String,
        config: AppSettings,
        extraEnv: Map<String, String> = emptyMap(),
        cwd: String = PocketPaths.Guest.ROOT,
        timeoutMs: Long = 0,
        onLine: (String) -> Unit = {},
    ): Output {
        val process = withContext(Dispatchers.IO) { proot.start(command, config, extraEnv, cwd) }
        val lines = ArrayList<String>(256)
        try {
            val completed = withTimeoutOrNull(if (timeoutMs > 0) timeoutMs else Long.MAX_VALUE) {
                withContext(Dispatchers.IO) {
                    val reader = BufferedReader(InputStreamReader(process.inputStream), 64 * 1024)
                    while (true) {
                        val line = reader.readLine() ?: break
                        lines += line
                        onLine(line)
                    }
                }
                process.waitFor()
            }
            if (completed == null) throw TimeoutException(lines)
            return Output(process.exitValue(), lines)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                if (process.isAlive) {
                    process.destroy()
                    delay(900)
                    if (process.isAlive) process.destroyForcibly()
                }
            }
        }
    }

    /** 便捷方法：跑一条命令并把输出拿到手（默认 5 分钟超时）。 */
    suspend fun runChecked(
        command: String,
        config: AppSettings,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = 300_000,
        onLine: (String) -> Unit = {},
    ): Output = run(command, config, extraEnv, timeoutMs = timeoutMs, onLine = onLine)

    /**
     * 交互式 shell：返回进程，调用方负责写 stdin 与读 stdout。
     * 命令骨架：`bash --login` + 每次执行后打印 cwd 标记，便于终端页做“像 shell 一样”的体验。
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

    /** 宿主侧目录大小（不动容器）。 */
    fun hostSize(dir: File): Long {
        if (!dir.exists()) return 0
        var total = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) total += file.length()
        }
        return total
    }
}
