package dev.pocket.opencode.service

import android.content.Context
import android.os.Process
import dev.pocket.opencode.IDeviceShellService
import java.io.BufferedReader
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
        Process.killProcess(Process.myPid())
        System.exit(0)
    }

    override fun getUid(): Int = Process.myUid()

    override fun exec(command: String, timeoutMs: Int): String {
        val timeout = timeoutMs.coerceIn(500, MAX_TIMEOUT_MS).toLong()
        val process = try {
            ProcessBuilder("sh", "-c", command).start()
        } catch (error: Exception) {
            return JSONObject()
                .put("exitCode", -1)
                .put("stdout", "")
                .put("stderr", "无法启动进程：${error.message}")
                .put("timedOut", false)
                .toString()
        }

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
        }
        val errThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                    lines.forEach { line ->
                        if (stderr.length < MAX_OUTPUT) stderr.append(line).append('\n')
                    }
                }
            }
        }
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
            runCatching { process.destroyForcibly() }
        }
        runCatching { outThread.join(1_500) }
        runCatching { errThread.join(1_500) }

        return JSONObject()
            .put("exitCode", if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1))
            .put("stdout", stdout.toString().take(MAX_OUTPUT))
            .put("stderr", stderr.toString().take(MAX_OUTPUT))
            .put("timedOut", timedOut)
            .toString()
    }

    private companion object {
        const val MAX_OUTPUT = 256 shl 10
        const val MAX_TIMEOUT_MS = 10 * 60_000
    }
}
