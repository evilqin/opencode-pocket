package dev.pocket.opencode.core

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 支持续传、多源回退、限速统计、可选 SHA-256 校验的下载器。
 *
 * 大文件（rootfs / node / opencode 二进制）全部走这里：
 *   - 断点续传：写 .part 文件，重试时带 Range 头（注意：要校验哈希时就不续传，避免半成品无法验签）；
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
        /** 总长度未知时返回 0，UI 应该显示不确定进度。 */
        val fraction: Float
            get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

        val known: Boolean get() = total > 0
    }

    /**
     * 单个源下载；失败抛 IOException。
     *
     * @param expected 非空时边下边算哈希，校验失败会删掉文件并抛错；
     *                        这种模式下不做断点续传（半成品无法验签）。
     */
    suspend fun fetch(
        url: String,
        target: File,
        expected: ExpectedHash? = null,
        isCanceled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()

        // 已有且摘要正确的文件直接复用：重试/换镜像/重装都不用重下几十 MB
        if (expected != null && target.isFile && digestOf(target, expected.algorithm).equals(expected.hex, true)) {
            onProgress(Progress(target.length(), target.length(), 0, url))
            return@withContext target
        }
        if (expected != null) part.delete()

        var attempt = 0
        var lastError: Exception? = null
        while (attempt < 3) {
            currentCoroutineContext().ensureActive()
            if (isCanceled()) throw CancellationException("下载已取消")
            attempt++
            try {
                downloadOnce(url, part, target, expected, onProgress, isCanceled)
                return@withContext target
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IllegalStateException) {
                // 校验失败之类的“重试也没用”的问题，直接抛出
                throw IOException(error.message, error)
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
        expected: ExpectedHash? = null,
        isCanceled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): File {
        var lastError: Exception? = null
        for ((index, url) in urls.withIndex()) {
            try {
                // 换源时旧的半成品没意义
                if (index > 0) {
                    File(target.parentFile, target.name + ".part").delete()
                    target.delete()
                }
                return fetch(url, target, expected, isCanceled, onProgress)
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
        expected: ExpectedHash?,
        onProgress: (Progress) -> Unit,
        isCanceled: () -> Boolean,
    ) {
        val existing = if (expected == null && part.isFile) part.length() else 0L
        val builder = Request.Builder()
            .url(url)
            // 关掉 OkHttp 的透明 gzip：否则 Range 偏移与 Content-Length 全部错位
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val request = builder.build()

        client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                // 请求的偏移超过服务端文件大小：多半是 .part 已经完整（或文件变小了），
                // 删掉半成品从零重来，而不是让三次重试全撞 416
                part.delete()
                throw IOException("续传起点超出文件大小（已重置下载）")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("空响应")
            val appending = response.code == 206
            if (appending) {
                // 服务端必须明确给出续传区间，否则宁可从零重来（拼错的文件比慢更糟）
                val start = response.header("Content-Range")
                    ?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    ?: throw IOException("206 响应缺少 Content-Range，无法安全续传")
                if (start != existing) {
                    throw IOException("服务端返回的续传区间不对：$start != $existing")
                }
            }
            val bodyLength = body.contentLength()
            val total = when {
                appending && bodyLength >= 0 -> existing + bodyLength
                !appending && bodyLength >= 0 -> bodyLength
                else -> -1L
            }
            val mode = "rw" // 只有 r/rw/rws/rwd 合法；"w" 会抛 IllegalArgumentException
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
                output.fd.sync()
                onProgress(Progress(written, total, speed, url))
            }
            if (total > 0 && part.length() < total) {
                throw IOException("连接提前中断（${part.length()}/$total）")
            }
            if (expected != null) {
                // 校验失败时删掉半成品，避免下次误用
                val actual = digestOf(part, expected.algorithm)
                if (!actual.equals(expected.hex, ignoreCase = true)) {
                    part.delete()
                    throw IllegalStateException(
                        "校验和不匹配：期望 ${expected.hex.take(16)}…，实际 ${actual.take(16)}…",
                    )
                }
            }
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            // 跨卷或被占用时 rename 会失败，退化成复制
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /** 下载小型文本文件（SHA256SUMS 之类）。 */
    suspend fun fetchText(url: String, timeoutMs: Long = 20_000): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} @ $url")
                response.body?.string() ?: throw IOException("空响应 @ $url")
            }
    }

    companion object {
        const val USER_AGENT = "OpenCodePocket/1.0 (Android)"

        fun digestOf(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256Of(file: File): String = digestOf(file, "SHA-256")
    }

    /** 期望的摘要：Ubuntu/Node 给 sha256，npm 的 dist.shasum 是 sha1。 */
    data class ExpectedHash(val algorithm: String, val hex: String) {
        companion object {
            fun sha256(hex: String?) = hex?.takeIf { it.isNotBlank() }?.let { ExpectedHash("SHA-256", it) }
            fun sha1(hex: String?) = hex?.takeIf { it.isNotBlank() }?.let { ExpectedHash("SHA-1", it) }
        }
    }
}
