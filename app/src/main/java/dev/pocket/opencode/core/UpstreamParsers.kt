package dev.pocket.opencode.core

/**
 * 上游文件的**纯解析逻辑**：不触网、不碰文件系统，方便单元测试。
 * 这些解析一旦写错，就是「Node 步骤永远找不到 arm64」这类必失败问题。
 */
object UpstreamParsers {

    data class NodePick(val version: String, val lts: String)

    /**
     * 从 Node 的 index.json 里挑最新的 arm64 LTS。
     *
     * 注意 `files` 里是平台标识（`linux-arm64`），**不是**带扩展名的文件名——
     * 早先按 `linux-arm64.tar.xz` 匹配，会导致所有镜像都判定「没有 arm64 LTS」。
     */
    fun pickNodeLts(indexJson: String): NodePick? {
        val list = runCatching { org.json.JSONArray(indexJson) }.getOrNull() ?: return null
        var best: NodePick? = null
        for (i in 0 until list.length()) {
            val entry = list.optJSONObject(i) ?: continue
            val lts = entry.opt("lts")
            if (lts == null || lts == false || lts.toString() == "false") continue
            val files = entry.optJSONArray("files") ?: continue
            val hasArm64 = (0 until files.length()).any { index ->
                val file = files.optString(index)
                file == "linux-arm64" || file.startsWith("linux-arm64")
            }
            if (!hasArm64) continue
            val version = entry.optString("version")
            if (version.isBlank()) continue
            if (best == null || Versions.compare(version, best.version) > 0) {
                best = NodePick(version, lts.toString())
            }
        }
        return best
    }

    /** 从 ubuntu-cdimage 的目录列表 HTML 里挑最新的 arm64 rootfs。 */
    fun pickUbuntuBase(html: String): String? {
        val pattern = Regex("ubuntu-base-(\\d+\\.\\d+\\.\\d+)-base-arm64\\.tar\\.gz")
        return pattern.findAll(html)
            .map { it.groupValues[1] to it.value }
            .distinctBy { it.second }
            .maxByOrNull { Versions.score(it.first) }
            ?.second
    }

    /**
     * 目录列表不可用时的候选文件名：从 `SHA256SUMS` 里找出所有 arm64 rootfs，按版本从新到旧。
     *
     * 比写死版本号抗上游更新——镜像把每个点版本都列在 SHA256SUMS 里，
     * 上游发了 24.04.7 之后回退列表也不会「一个都找不到」。
     */
    fun ubuntuBaseCandidates(shasumsText: String): List<String> {
        val pattern = Regex("ubuntu-base-(\\d+\\.\\d+\\.\\d+)-base-arm64\\.tar\\.gz")
        return pattern.findAll(shasumsText)
            .map { it.groupValues[1] to it.value }
            .distinctBy { it.second }
            .sortedByDescending { Versions.score(it.first) }
            .map { it.second }
            .toList()
    }

    /**
     * 从 `SHA256SUMS` / `SHASUMS256.txt` 这类文本里取出指定文件的摘要。
     * 行格式：`<hex>  <filename>` 或 `<hex> *<filename>`。
     */
    fun sha256For(fileName: String, shasumsText: String): String? {
        for (rawLine in shasumsText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size < 2) continue
            val digest = parts[0]
            if (!digest.matches(Regex("[0-9a-fA-F]{64}"))) continue
            val name = parts[1].trim().removePrefix("*").substringAfterLast('/')
            if (name == fileName) return digest.lowercase()
        }
        return null
    }
}
