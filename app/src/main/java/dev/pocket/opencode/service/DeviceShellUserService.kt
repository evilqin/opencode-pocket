package dev.pocket.opencode.service

import android.content.Context
import dev.pocket.opencode.IDeviceShellService
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * 运行在 **Shizuku 进程**（shell 或 root 身份）里的服务实现。
 *
 * 关键约束（来自 Shizuku API 的实现）：
 *   - 必须继承 AIDL 生成的 `Stub`（**不是** android.app.Service），Shizuku 会直接
 *     `newInstance()` 后当作 IBinder 用；
 *   - 必须有无参构造；带 Context 的构造只有 Shizuku v13+ 才会用到，且必须 @Keep；
 *   - 必须实现保留事务 `destroy()`（16777114）并结束进程，否则 unbind 后进程不退出。
 *
 * 这个类不会被 App 自己启动，因此只用最基础的 API，不依赖 App 里的任何状态。
 */
class DeviceShellUserService @JvmOverloads constructor(
    @Suppress("unused") private val context: Context? = null,
) : IDeviceShellService.Stub() {

    override fun destroy() {
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(0)
    }

    override fun getUid(): Int = android.os.Process.myUid()

    override fun exec(command: String, timeoutMs: Int): String {
        val timeout = timeoutMs.coerceIn(500, MAX_TIMEOUT_MS).toLong()
        // 优先用 setsid 起一个独立会话（进程组）：这样超时时可以「整组」杀掉，
        // 孙进程不会变成孤儿继续跑。设备上没有 setsid 时退回普通方式。
        var isolated = false
        val process = try {
            val started = ProcessBuilder("setsid", "sh", "-c", command).start()
            isolated = true
            started
        } catch (_: IOException) {
            try {
                ProcessBuilder("sh", "-c", command).start()
            } catch (error: Exception) {
                return JSONObject()
                    .put("exitCode", -1)
                    .put("stdout", "")
                    .put("stderr", "无法启动进程：${error.message}")
                    .put("timedOut", false)
                    .toString()
            }
        }
        val pid = pidOf(process)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (stdout.length < MAX_OUTPUT) stdout.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true }
        val errThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (stderr.length < MAX_OUTPUT) stderr.append(line).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true }
        outThread.start()
        errThread.start()

        // stdin 立刻关掉：读 stdin 的命令拿到 EOF，而不是挂到超时
        runCatching { process.outputStream.close() }

        val finished = try {
            process.waitFor(timeout, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        var timedOut = false
        if (!finished) {
            timedOut = true
            // 超时：先清掉直接子进程，若用了 setsid 再杀整个进程组，
            // 尽量不给系统留下孤儿进程；清理失败不影响下面的返回值。
            killProcessTree(process, pid, isolated)
        }

        // 结束/超时后主动关掉三条流：读线程能尽快退出，不再只靠 join 一小会儿。
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        runCatching { outThread.join(STREAM_JOIN_MS) }
        runCatching { errThread.join(STREAM_JOIN_MS) }

        return JSONObject()
            .put("exitCode", if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1))
            .put("stdout", stdout.toString().take(MAX_OUTPUT))
            .put("stderr", stderr.toString().take(MAX_OUTPUT))
            .put("timedOut", timedOut)
            .toString()
    }

    /**
     * 取子进程 pid（best-effort）：Android 13（API 33）起 java.lang.Process 才有 pid()，
     * 旧版本用反射读实现类里的 pid 字段；都拿不到就返回 null，超时清理退化为只杀直接子进程。
     */
    private fun pidOf(process: Process): Long? {
        runCatching {
            val value = Process::class.java.getMethod("pid").invoke(process) as? Number
            if (value != null) return value.toLong()
        }
        runCatching {
            val field = process.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            val value = field.get(process) as? Number
            if (value != null) return value.toLong()
        }
        return null
    }

    /** 尽力清理进程树：pkill 杀直接子进程，setsid 起作用时再按进程组杀一次。 */
    private fun killProcessTree(process: Process, pid: Long?, isolated: Boolean) {
        if (pid != null) {
            // pkill -P <pid>：按父 pid 精确匹配直接子进程
            runCatching {
                ProcessBuilder("sh", "-c", "pkill -KILL -P $pid")
                    .start()
                    .waitFor(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS)
            }
            if (isolated) {
                // setsid 让子进程成为组长：负号代表「整个进程组」，可连孙进程一起清掉
                runCatching {
                    ProcessBuilder("sh", "-c", "kill -KILL -- -$pid")
                        .start()
                        .waitFor(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
        runCatching { process.destroyForcibly() }
    }

    private companion object {
        const val MAX_OUTPUT = 256 shl 10
        const val MAX_TIMEOUT_MS = 10 * 60_000

        /** 等待清理辅助进程的最长时间。 */
        const val CLEANUP_WAIT_MS = 1_000L

        /** 等待输出读线程收尾的时间（线程是 daemon，超了也不会挂住进程）。 */
        const val STREAM_JOIN_MS = 1_500L
    }
}
