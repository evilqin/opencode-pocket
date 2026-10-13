package dev.pocket.opencode.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.pocket.opencode.IDeviceShellService
import dev.pocket.opencode.service.DeviceShellUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Shizuku 封装：让**手机本体**（而不是容器）以 shell / root 身份执行命令。
 *
 * 为什么不是一个 `Runtime.exec`：Android 10+ 起普通应用既没有权限，
 * 也拿不到 shell 身份。Shizuku 的做法是把我们的实现类加载到它的
 * shell/root 进程里，通过 Binder 调用 —— 也就是下面这个「用户服务」。
 *
 * 所有 Shizuku 调用都可能因为 binder 掉线而抛异常，这里一律包起来，失败即视为不可用。
 */
class ShizukuBridge(private val context: Context) {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
    )

    private var service: IDeviceShellService? = null
    private var connection: ServiceConnection? = null
    private var uid: Int? = null

    /** Shizuku（或 Sui）是否在运行。 */
    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permissionGranted(): Boolean = runCatching {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 已经拒绝且勾了「不再询问」。 */
    fun deniedForever(): Boolean = runCatching {
        available() && Shizuku.shouldShowRequestPermissionRationale()
    }.getOrDefault(false)

    fun requestPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    fun connected(): Boolean = service != null

    /**
     * 绑定 Shizuku 用户服务（幂等）。返回是否可用。
     */
    suspend fun connect(timeoutMs: Long = 8_000): Boolean = withContext(Dispatchers.IO) {
        if (service != null) return@withContext true
        if (!permissionGranted()) return@withContext false

        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, DeviceShellUserService::class.java.name),
        )
            .daemon(false)
            .version(1)
            .processNameSuffix("device_shell")
            .debuggable(false)

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.let { IDeviceShellService.Stub.asInterface(it) }
                uid = runCatching { service?.uid }.getOrNull()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                uid = null
            }
        }
        connection = serviceConnection

        val bound = runCatching {
            Shizuku.bindUserService(args, serviceConnection)
            true
        }.getOrDefault(false)
        if (!bound) {
            connection = null
            return@withContext false
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service != null) return@withContext true
            delay(120)
        }
        disconnect()
        false
    }

    fun disconnect() {
        val current = connection ?: return
        connection = null
        service = null
        uid = null
        runCatching {
            Shizuku.unbindUserService(
                Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, DeviceShellUserService::class.java.name),
                ).daemon(false).version(1).processNameSuffix("device_shell").debuggable(false),
                current,
                true,
            )
        }
    }

    /** 当前身份（2000=shell / 0=root），拿不到返回 null。 */
    suspend fun identity(): String? {
        if (!connect()) return null
        val current = uid ?: return null
        return when (current) {
            0 -> "root"
            2000 -> "shell"
            else -> "uid=$current"
        }
    }

    /** 在手机本体上执行一条命令。 */
    suspend fun exec(command: String, timeoutMs: Long = 30_000): Result = withContext(Dispatchers.IO) {
        if (!connect()) {
            return@withContext Result(-1, "", "Shizuku 未授权或未运行（App → 设置 → 设备 Shell）")
        }
        val target = service
            ?: return@withContext Result(-1, "", "Shizuku 用户服务未连接")
        val raw = try {
            target.exec(command, timeoutMs.toInt())
        } catch (error: Throwable) {
            service = null // binder 可能掉了，下一次调用重新绑定
            return@withContext Result(-1, "", "Shizuku 调用失败：${error.message}")
        }
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext Result(-1, raw, "")
        Result(
            exitCode = json.optInt("exitCode", -1),
            stdout = json.optString("stdout"),
            stderr = json.optString("stderr"),
            timedOut = json.optBoolean("timedOut", false),
        )
    }
}
