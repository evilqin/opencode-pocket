package dev.pocket.opencode.core

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 各个上游仓库的“发现 + 测速”逻辑：
 *   - Ubuntu base rootfs 文件名（各镜像目录里点版本不同）
 *   - Node.js LTS 版本（读 index.json）
 *   - opencode 版本（读 npm registry）
 *   - 多镜像并行测速（真实文件 + Range 请求，测得就是真实带宽）
 */
class Repos(private val downloader: Downloader) {

    data class Artifact(
        val url: String,
        val fileName: String,
        val version: String,
        val size: Long = -1,
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

    // ------------------------------------------------------------------ Ubuntu

    private val ubuntuFallbackVersions = listOf("24.04.3", "24.04.2", "24.04.1")

    suspend fun ubuntuBase(mirror: Mirrors.Mirror): Artifact = withContext(Dispatchers.IO) {
        val listing = try {
            httpGetText(mirror.base + "/")
        } catch (_: Exception) {
            null
        }
        val pattern = Regex("ubuntu-base-(\\d+\\.\\d+\\.\\d+)-base-arm64\\.tar\\.gz")
        // 按数字比较版本号（字符串比较会把 24.04.10 排在 24.04.9 前面）
        val found = listing?.let { html ->
            pattern.findAll(html).map { it.groupValues[1] to it.value }
                .distinctBy { it.second }
                .maxByOrNull { Versions.score(it.first) }
        }
        if (found != null) {
            val url = mirror.base + "/" + found.second
            return@withContext Artifact(url, found.second, found.first, sizeOf(url))
        }
        for (version in ubuntuFallbackVersions) {
            val fileName = "ubuntu-base-$version-base-arm64.tar.gz"
            val url = mirror.base + "/" + fileName
            val size = sizeOf(url)
            if (size > 0) return@withContext Artifact(url, fileName, version, size)
        }
        throw IllegalStateException("镜像 ${mirror.name} 上找不到 ubuntu-base 24.04 arm64")
    }

    // ------------------------------------------------------------------ Node

    suspend fun nodeLts(mirror: Mirrors.Mirror): Artifact = withContext(Dispatchers.IO) {
        val index = httpGetText(mirror.base + "/index.json")
        val array = JSONArray(index)
        var best: JSONObject? = null
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val lts = entry.opt("lts")
            val isLts = lts != null && lts != false && !"false".equals(lts.toString(), true)
            if (!isLts) continue
            val files = entry.optJSONArray("files") ?: continue
            val hasArm64 = (0 until files.length()).any {
                files.optString(it).contains("linux-arm64.tar.xz")
            }
            if (!hasArm64) continue
            val version = entry.optString("version")
            if (best == null || Versions.compare(version, best.optString("version")) > 0) best = entry
        }
        val selected = best ?: throw IllegalStateException("镜像 ${mirror.name} 的 index.json 里没有 arm64 LTS")
        val version = selected.optString("version")
        val fileName = "node-$version-linux-arm64.tar.xz"
        Artifact("${mirror.base}/$version/$fileName", fileName, version, sizeOf("${mirror.base}/$version/$fileName"))
    }

    /** 单独取某个 Node 版本（用于“已装版本/更新”判断）。 */
    suspend fun nodeArtifact(mirror: Mirrors.Mirror, version: String): Artifact {
        val fileName = "node-$version-linux-arm64.tar.xz"
        val url = "${mirror.base}/$version/$fileName"
        return Artifact(url, fileName, version, sizeOf(url))
    }

    // --------------------------------------------------------------- opencode

    suspend fun opencodeVersion(registry: String): String = withContext(Dispatchers.IO) {
        val text = httpGetText("$registry/opencode-ai/latest")
        val json = JSONObject(text)
        json.optString("version").ifBlank { throw IllegalStateException("registry 没有返回版本号") }
    }

    fun opencodeTarball(registry: String, version: String): Artifact {
        val fileName = "opencode-linux-arm64-$version.tgz"
        return Artifact("$registry/opencode-linux-arm64/-/$fileName", fileName, version)
    }

    // ------------------------------------------------------------------ 测速

    suspend fun speedTestUbuntu(mirrors: List<Mirrors.Mirror>): List<Speed> = coroutineScope {
        mirrors.map { mirror ->
            async {
                try {
                    val artifact = ubuntuBase(mirror)
                    measure(mirror, artifact.url)
                } catch (error: Exception) {
                    Speed(mirror.id, mirror.name, null, null, null, error.message)
                }
            }
        }.map { it.await() }
    }

    suspend fun speedTestNode(mirrors: List<Mirrors.Mirror>): List<Speed> = coroutineScope {
        mirrors.map { mirror ->
            async {
                try {
                    val artifact = nodeLts(mirror)
                    measure(mirror, artifact.url)
                } catch (error: Exception) {
                    Speed(mirror.id, mirror.name, null, null, null, error.message)
                }
            }
        }.map { it.await() }
    }

    suspend fun speedTestNpm(mirrors: List<Mirrors.Mirror>): List<Speed> = coroutineScope {
        mirrors.map { mirror ->
            async {
                try {
                    val version = opencodeVersion(mirror.base)
                    measure(mirror, opencodeTarball(mirror.base, version).url)
                } catch (error: Exception) {
                    Speed(mirror.id, mirror.name, null, null, null, error.message)
                }
            }
        }.map { it.await() }
    }

    /** 用 Range 拉一小段真实文件，得到“首字节延迟 + 带宽”。 */
    private suspend fun measure(mirror: Mirrors.Mirror, url: String): Speed = withContext(Dispatchers.IO) {
        val sampleBytes = 900_000L
        try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-${sampleBytes - 1}")
                .header("User-Agent", Downloader.USER_AGENT)
                .build()
            val startNs = System.nanoTime()
            var firstByteNs = 0L
            var total = 0L
            downloader.http.newCall(request).execute().use { response ->
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
        } catch (error: Exception) {
            Speed(mirror.id, mirror.name, null, null, url, error.message)
        }
    }

    // ------------------------------------------------------------------ utils

    private fun sizeOf(url: String): Long = try {
        downloader.http.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            response.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    } catch (_: Exception) {
        -1L
    }

    private fun httpGetText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Downloader.USER_AGENT)
            .build()
        downloader.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code} @ $url")
            return response.body?.string() ?: throw IllegalStateException("空响应 @ $url")
        }
    }
}
