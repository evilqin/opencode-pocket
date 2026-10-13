package dev.pocket.opencode.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * 各个上游仓库的“发现 + 测速”逻辑：
 *   - Ubuntu base rootfs 文件名（各镜像目录里点版本不同）
 *   - Node.js LTS 版本（读 index.json）
 *   - opencode 版本（读 npm registry）
 *   - 多镜像并行测速（真实文件 + Range 请求，测得就是真实带宽）
 *
 * 所有解析都走 [UpstreamParsers]（纯函数、有单测），这里只负责网络与并发。
 */
class Repos(private val downloads: Downloader) {

    data class Artifact(
        val url: String,
        val fileName: String,
        val version: String,
        val size: Long = -1,
        /** 上游发布的摘要（Ubuntu 的 SHA256SUMS / Node 的 SHASUMS256.txt），拿不到就是 null。 */
        val sha256: String? = null,
    )

    data class Speed(
        val mirrorId: String,
        val mirrorName: String,
        val latencyMs: Long?,
        val bytesPerSecond: Long?,
        val sampleUrl: String?,
        val error: String? = null,
    ) {
        val ok: Boolean get() = bytesPerSecond != null && bytesPerSecond > 0
    }

    /** 测速并发上限：19 个镜像同时拉 900 KB 会把移动网络打爆。 */
    private val speedGate = Semaphore(4)

    // ------------------------------------------------------------------ Ubuntu

    private val ubuntuFallbackVersions = listOf("24.04.5", "24.04.4", "24.04.3", "24.04.2", "24.04.1")

    suspend fun ubuntuBase(mirror: Mirrors.Mirror): Artifact = withContext(Dispatchers.IO) {
        val listing = runCatching { httpGetText(mirror.base + "/") }.getOrNull()
        val fileName = listing?.let { UpstreamParsers.pickUbuntuBase(it) }
        if (fileName != null) {
            val url = mirror.base + "/" + fileName
            return@withContext Artifact(
                url = url,
                fileName = fileName,
                version = Regex("ubuntu-base-(\\d+\\.\\d+\\.\\d+)-").find(fileName)?.groupValues?.get(1) ?: "",
                size = sizeOf(url),
                sha256 = ubuntuSha256(mirror, fileName),
            )
        }
        for (version in ubuntuFallbackVersions) {
            val fallback = "ubuntu-base-$version-base-arm64.tar.gz"
            val url = mirror.base + "/" + fallback
            val size = sizeOf(url)
            if (size > 0) {
                return@withContext Artifact(url, fallback, version, size, ubuntuSha256(mirror, fallback))
            }
        }
        throw IllegalStateException("镜像 ${mirror.name} 上找不到 ubuntu-base 24.04 arm64")
    }

    /** Ubuntu 官方/镜像目录里的 SHA256SUMS。 */
    private fun ubuntuSha256(mirror: Mirrors.Mirror, fileName: String): String? = runCatching {
        UpstreamParsers.sha256For(fileName, httpGetText(mirror.base + "/SHA256SUMS"))
    }.getOrNull()

    // ------------------------------------------------------------------ Node

    suspend fun nodeLts(mirror: Mirrors.Mirror): Artifact = withContext(Dispatchers.IO) {
        val index = httpGetText(mirror.base + "/index.json")
        val pick = UpstreamParsers.pickNodeLts(index)
            ?: throw IllegalStateException("镜像 ${mirror.name} 的 index.json 里没有带 arm64 的 LTS")
        val fileName = "node-${pick.version}-linux-arm64.tar.xz"
        val url = "${mirror.base}/${pick.version}/$fileName"
        Artifact(
            url = url,
            fileName = fileName,
            version = pick.version,
            size = sizeOf(url),
            sha256 = nodeSha256(mirror, pick.version, fileName),
        )
    }

    /** Node 每个版本目录里的 SHASUMS256.txt。 */
    private fun nodeSha256(mirror: Mirrors.Mirror, version: String, fileName: String): String? = runCatching {
        UpstreamParsers.sha256For(fileName, httpGetText("${mirror.base}/$version/SHASUMS256.txt"))
    }.getOrNull()

    // --------------------------------------------------------------- opencode

    suspend fun opencodeVersion(registry: String): String = withContext(Dispatchers.IO) {
        val text = httpGetText("$registry/opencode-ai/latest")
        JSONObject(text).optString("version").ifBlank { throw IllegalStateException("registry 没有返回版本号") }
    }

    fun opencodeTarball(registry: String, version: String): Artifact {
        val fileName = "opencode-linux-arm64-$version.tgz"
        return Artifact("$registry/opencode-linux-arm64/-/$fileName", fileName, version)
    }

    /** npm 的 packument 里带 `dist.shasum`（sha1），用于校验 tarball。 */
    suspend fun opencodeSha1(registry: String, version: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val text = httpGetText("$registry/opencode-linux-arm64/$version")
            JSONObject(text).optJSONObject("dist")?.optString("shasum")?.takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 测速

    suspend fun speedTestUbuntu(mirrors: List<Mirrors.Mirror>): List<Speed> =
        speedTest(mirrors) { ubuntuBase(it).url }

    suspend fun speedTestNode(mirrors: List<Mirrors.Mirror>): List<Speed> =
        speedTest(mirrors) { nodeLts(it).url }

    suspend fun speedTestNpm(mirrors: List<Mirrors.Mirror>): List<Speed> =
        speedTest(mirrors) { mirror ->
            val version = opencodeVersion(mirror.base)
            opencodeTarball(mirror.base, version).url
        }

    private suspend fun speedTest(
        mirrors: List<Mirrors.Mirror>,
        resolve: suspend (Mirrors.Mirror) -> String,
    ): List<Speed> = coroutineScope {
        mirrors
            .map { mirror ->
                async {
                    speedGate.withPermit {
                        try {
                            val url = resolve(mirror)
                            measure(mirror, url)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Speed(mirror.id, mirror.name, null, null, null, error.message)
                        }
                    }
                }
            }
            .awaitAll()
    }

    /** 用 Range 拉一小段真实文件，得到“首字节延迟 + 带宽”。 */
    private suspend fun measure(mirror: Mirrors.Mirror, url: String): Speed = withContext(Dispatchers.IO) {
        val sampleBytes = 900_000L
        try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${sampleBytes - 1}")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", Downloader.USER_AGENT)
                .build()
            val startNs = System.nanoTime()
            var firstByteNs = 0L
            var total = 0L
            downloads.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val stream = response.body?.byteStream() ?: throw IllegalStateException("空响应")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (firstByteNs == 0L) firstByteNs = System.nanoTime()
                    total += count
                    if (total >= sampleBytes) break
                }
            }
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            val transferMs = ((System.nanoTime() - firstByteNs) / 1_000_000).coerceAtLeast(1)
            val latency = if (firstByteNs > 0) (firstByteNs - startNs) / 1_000_000 else elapsedMs
            Speed(mirror.id, mirror.name, latency, total * 1000L / transferMs, url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Speed(mirror.id, mirror.name, null, null, url, error.message)
        }
    }

    // ------------------------------------------------------------------ utils

    /** 文件大小：先 HEAD，失败退化成 Range GET（有些 CDN 不允许 HEAD）。 */
    private fun sizeOf(url: String): Long = try {
        downloads.http.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            val length = response.header("Content-Length")?.toLongOrNull()
            when {
                response.isSuccessful && length != null -> length
                response.isSuccessful -> -1L
                else -> rangeSize(url)
            }
        }
    } catch (_: Exception) {
        rangeSize(url)
    }

    private fun rangeSize(url: String): Long = try {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .build()
        downloads.http.newCall(request).execute().use { response ->
            // Content-Range: bytes 0-0/12345678
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
        }
    } catch (_: Exception) {
        -1L
    }

    private fun httpGetText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", Downloader.USER_AGENT)
            .build()
        return downloads.http.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} @ $url")
                response.body?.string() ?: throw IllegalStateException("空响应 @ $url")
            }
    }
}
