package dev.pocket.opencode.util

/**
 * 日志脱敏（纯逻辑，可单测）。
 *
 * 导出日志是常见的排障路径，但 server.log 里可能出现 API Key、设备桥令牌、
 * Basic 口令 —— 导出前统一打码，别把密钥顺手发出去。
 */
object LogRedactor {

    private val providerKey = Regex("""\b(sk|rk|pk|gsk|xai|ghp|gho|glpat|hf)[-_][A-Za-z0-9_\-]{8,}""")

    private val authHeader = Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9\-_.+/=]{8,}""")

    private val keyValue = Regex(
        """(?i)\b([A-Za-z0-9_]*(?:api[_-]?key|apikey|token|password|secret|shasum|integrity))\b\s*[:=]\s*"?[A-Za-z0-9\-_.+/=]{6,}"?""",
    )

    fun redact(text: String, extraSecrets: List<String> = emptyList()): String {
        var result = text
        for (secret in extraSecrets) {
            if (secret.length >= 6) {
                result = result.replace(secret, "***")
            }
        }
        // 先按「键=值」整体打码（这样 DEEPSEEK_API_KEY=sk-xxx 会变成 KEY=***），
        // 再兜底处理没有键名的裸密钥（例如日志里直接出现 sk-xxxx）
        result = keyValue.replace(result) { match -> "${match.groupValues[1]}=***" }
        result = providerKey.replace(result) { match -> "${match.groupValues[1]}-***" }
        result = authHeader.replace(result) { match -> "${match.groupValues[1]} ***" }
        return result
    }
}
