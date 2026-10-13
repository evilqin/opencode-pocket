package dev.pocket.opencode.core

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 支持续传、多源回退、限速统计的下载器。
 *
 * 大文件（rootfs / node / opencode 二进制）全部走这里：
 *   - 断点续传：写 .part 文件，重试时带 Range 头；
 *   - 多源：调用方给一串候选 URL，逐个尝试；
 *   - 进度：字节数 / 总字节 / 瞬时速度，直接喂给安装界面。
 */
class Downloader(context: Context) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val http: OkHttpClient get() = client

    data class Progress(
        val bytes: Long,
        val total: Long,
        val speedBytesPerSecond: Long,
        val source: String,
    ) {
        val fraction: Float
            get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    /** 单个源下载；失败抛 IOException。 */
    suspend fun fetch(
        url: String,
        target: File,
        isCanceled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 3) {
            currentCoroutineContext().ensureActive()
            if (isCanceled()) throw CancellationException("下载已取消")
            attempt++
            try {
                downloadOnce(url, part, target, onProgress, isCanceled)
                return@withContext target
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
                if (attempt < 3) delay(1200L * attempt)
            }
        }
        throw IOException("下载失败：$url（${lastError?.message}）", lastError)
    }

    /** 依次尝试多个镜像，任一成功即返回。 */
    suspend fun fetchFrom(
        urls: List<String>,
        target: File,
        isCanceled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): File {
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                // 换源时旧的半成品没意义
                if (index > 0) File(target.parentFile, target.name + ".part").delete()
                return fetch(url, target, isCanceled, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IOException("所有镜像都失败了：${lastError?.message}", lastError)
    }

    private fun downloadOnce(
        url: String,
        part: File,
        target: File,
        onProgress: (Progress) -> Unit,
        isCanceled: () -> Boolean,
    ) {
        val existing = if (part.isFile()) part.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val request = builder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val appending = response.code == 206
            val total = when {
                appending -> existing + body.contentLength()
                else -> body.contentLength()
            }
            val mode = if (appending) "r" else "w"
            if (!appending) part.delete()
            RandomAccessFile(part, mode).use { output ->
                if (appending) output.seek(existing)
                var written = if (appending) existing else 0L
                val buffer = ByteArray(128 * 1024)
                var windowStart = System.currentTimeMillis()
                var windowBytes = 0L
                var speed = 0L
                body.byteStream().use { input ->
                    while (true) {
                        if (isCanceled()) throw CancellationException("下载已取消")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        windowBytes += read
                        val now = System.currentTimeMillis()
                        if (now - windowStart >= 400) {
                            speed = windowBytes * 1000L / (now - windowStart).coerceAtLeast(1)
                            windowStart = now
                            windowBytes = 0
                        }
                        onProgress(Progress(written, total, speed, url))
                    }
                }
                onProgress(Progress(written, total, speed, url))
            }
            if (total > 0 && part.length() < total) throw IOException("连接提前中断（${part.length()}/$total）")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("无法写入目标文件 ${target.name}")
    }

    companion object {
        const val USER_AGENT = "OpenCodePocket/1.0 (Android)"
    }
}
