package dev.pocket.opencode.core

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.pocket.opencode.BuildConfig
import dev.pocket.opencode.IDeviceShellService
import dev.pocket.opencode.service.DeviceShellUserService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Shizuku 封装：让**手机本体**（而不是容器）以 shell / root 身份执行命令。
 *
 * 原理：Shizuku 会把我们 APK 里的 `DeviceShellUserService`（一个 AIDL Stub）
 * 加载到它自己的 shell/root 进程里，App 通过 Binder 调它的 `exec`。
 *
 * 注意点：
 *   - 服务标识用 `tag`（类名会被 R8 改写）；`version` 用 versionCode，
 *     版本不匹配时 Shizuku 会自己重启服务进程；
 *   - 所有 Shizuku 调用都可能因为 binder 掉线而抛异常，一律包起来，失败即视为不可用。
 */
class ShizukuBridge(private val context: Context) {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean = false,
    )

    @Volatile
    private var service: IDeviceShellService? = null

    @Volatile
    private var uid: Int? = null

    @Volatile
    private var connection: ServiceConnection? = null

    private val connectLock = Mutex()

    private fun serviceArgs(): Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, DeviceShellUserService::class.java.name),
    )
        .daemon(false)
        .tag(SERVICE_TAG)
        .processNameSuffix("device_shell")
        .version(BuildConfig.VERSION_CODE)
        .debuggable(false)

    /** Shizuku（或 Sui）是否在运行。 */
    fun available(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun permissionGranted(): Boolean = runCatching {
        available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** 需要用户去 Shizuku 里手动授权：Shizuku 在跑但权限没拿到。
     *
     *  为什么不用「永久拒绝」：Shizuku 没有可靠的永久拒绝判断
     *  （`shouldShowRequestPermissionRationale()` 的语义与 Android 相反，且各版本行为不一致），
     *  所以这里只保守地表达「需要手动授权」，不去断言用户已永久拒绝。 */
    fun needsManualGrant(): Boolean = runCatching {
        available() && !permissionGranted()
    }.getOrDefault(false)

    /** 旧名，语义不可靠（见 [needsManualGrant]）。保留以免调用方编译失败，请改用 [needsManualGrant]。 */
    @Deprecated("语义不可靠，请改用 needsManualGrant()", ReplaceWith("needsManualGrant()"))
    fun deniedForever(): Boolean = needsManualGrant()

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

    /** 绑定 Shizuku 用户服务（幂等、可并发调用）。返回是否可用。 */
    suspend fun connect(timeoutMs: Long = 8_000): Boolean = connectLock.withLock {
        // 已有服务也要复核权限：权限可能在使用过程中被撤销。
        val granted = permissionGranted()
        if (service != null && granted) return@withLock true
        if (!granted) {
            // 权限不在了：清掉可能已失效的连接，避免留下悬挂引用。
            if (service != null) disconnect()
            return@withLock false
        }
        withContext(Dispatchers.IO) {
            // 重新绑定前先解绑旧连接，防止 ServiceConnection 泄漏。
            connection?.let { old -> runCatching { Shizuku.unbindUserService(serviceArgs(), old, true) } }
            connection = null
            service = null
            uid = null

            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val stub = binder?.let { IDeviceShellService.Stub.asInterface(it) }
                    service = stub
                    uid = runCatching { stub?.uid }.getOrNull()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    service = null
                    uid = null
                }
            }
            connection = serviceConnection

            val bound = runCatching {
                Shizuku.bindUserService(serviceArgs(), serviceConnection)
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
    }

    fun disconnect() {
        val current = connection ?: return
        connection = null
        service = null
        uid = null
        runCatching { Shizuku.unbindUserService(serviceArgs(), current, true) }
    }

    /** 当前身份（2000=shell / 0=root），拿不到返回 null。 */
    suspend fun identity(): String? {
        if (!connect()) return null
        return when (val current = uid) {
            null -> "unknown"
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
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // 协程取消要原样抛出，不能被下面的兜底吞掉。
            throw cancel
        } catch (error: Exception) {
            // binder 可能掉了：清掉引用，下一次调用重新绑定
            service = null
            uid = null
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

    private companion object {
        /** 固定 tag：类名可能被 R8 改写，用 tag 做服务标识更稳。 */
        const val SERVICE_TAG = "device_shell"
    }
}
