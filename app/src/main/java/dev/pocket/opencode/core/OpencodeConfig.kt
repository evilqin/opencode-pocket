package dev.pocket.opencode.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 把 App 内的设置翻译成容器里 opencode 的配置：
 *   - `~/.config/opencode/opencode.json`（模型、密钥、权限、服务器端口）
 *   - 启动服务时的环境变量（密钥兜底、省电模式开关）
 */
object OpencodeConfig {

    /** 常用 provider 目录：id 与 models.dev 一致，env 是该 provider 认的环境变量名。 */
    data class Provider(
        val id: String,
        val label: String,
        val env: String?,
        val hint: String = "",
    )

    val providers = listOf(
        Provider("deepseek", "DeepSeek", "DEEPSEEK_API_KEY", "platform.deepseek.com"),
        Provider("anthropic", "Anthropic", "ANTHROPIC_API_KEY", "claude 系列"),
        Provider("openai", "OpenAI", "OPENAI_API_KEY", ""),
        Provider("openrouter", "OpenRouter", "OPENROUTER_API_KEY", "聚合多家模型"),
        Provider("google", "Google Gemini", "GOOGLE_GENERATIVE_AI_API_KEY", ""),
        Provider("xai", "xAI Grok", "XAI_API_KEY", ""),
        Provider("groq", "Groq", "GROQ_API_KEY", "推理速度快"),
        Provider("mistral", "Mistral", "MISTRAL_API_KEY", ""),
        Provider("moonshotai", "Moonshot 月之暗面", "MOONSHOT_API_KEY", "kimi"),
        Provider("zhipuai", "智谱 GLM", "ZHIPUAI_API_KEY", ""),
        Provider("alibaba", "阿里通义千问", "DASHSCOPE_API_KEY", "OpenAI 兼容模式"),
        Provider("siliconflow", "硅基流动", "SILICONFLOW_API_KEY", "国内聚合"),
        Provider("openai-compatible", "自定义 OpenAI 兼容", null, "需要填 baseURL"),
    )

    fun findProvider(id: String): Provider? = providers.firstOrNull { it.id == id }

    /** 生成 opencode.json。 */
    fun build(settings: AppSettings, workspace: String): String {
        val root = JSONObject()
        root.put("\$schema", "https://opencode.ai/config.json")
        root.put("autoupdate", false)
        root.put("share", settings.share.ifBlank { "disabled" })

        if (settings.defaultModel.isNotBlank()) root.put("model", settings.defaultModel.trim())
        if (settings.smallModel.isNotBlank()) root.put("small_model", settings.smallModel.trim())

        val server = JSONObject()
        server.put("port", settings.port)
        server.put("hostname", "127.0.0.1")
        root.put("server", server)

        val permission = JSONObject()
        permission.put("edit", settings.permissionEdit)
        permission.put("bash", settings.permissionBash)
        root.put("permission", permission)

        // 手机上文件系统事件多，忽略掉噪音目录
        val watcher = JSONObject()
        watcher.put(
            "ignore",
            JSONArray(
                listOf(
                    "**/node_modules/**",
                    "**/.git/**",
                    "**/build/**",
                    "**/.gradle/**",
                    "**/__pycache__/**",
                ),
            ),
        )
        root.put("watcher", watcher)

        // 快照：opencode 用 git 记录改动，手机存储紧张时可以关掉
        root.put("snapshot", settings.snapshot)

        if (settings.providerKeys.isNotEmpty() || settings.providerBaseUrls.isNotEmpty()) {
            val providerObject = JSONObject()
            val ids = settings.providerKeys.keys + settings.providerBaseUrls.keys
            for (id in ids) {
                val entry = JSONObject()
                val options = JSONObject()
                settings.providerKeys[id]?.takeIf { it.isNotBlank() }?.let { options.put("apiKey", it.trim()) }
                settings.providerBaseUrls[id]?.takeIf { it.isNotBlank() }?.let { options.put("baseURL", it.trim()) }
                if (options.length() > 0) {
                    entry.put("options", options)
                    providerObject.put(id, entry)
                }
            }
            if (providerObject.length() > 0) root.put("provider", providerObject)
        }

        return root.toString(2)
    }

    /** 启动服务时注入的环境变量。 */
    fun environment(settings: AppSettings): Map<String, String> {
        val env = linkedMapOf<String, String>()
        // 密钥：即使配置文件被用户改过，环境变量仍然生效（部分 provider 只认 env）
        for ((id, key) in settings.providerKeys) {
            if (key.isBlank()) continue
            findProvider(id)?.env?.let { env[it] = key.trim() }
        }
        // 自定义 baseURL 的 OpenAI 兼容端点
        settings.providerBaseUrls["openai-compatible"]?.takeIf { it.isNotBlank() }?.let {
            env["OPENAI_BASE_URL"] = it.trim()
        }
        env["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
        env["OPENCODE_DISABLE_TERMINAL_TITLE"] = "1"
        env["BROWSER"] = "/bin/true"
        if (settings.lowMemory) {
            env["OPENCODE_DISABLE_LSP_DOWNLOAD"] = "1"
            env["OPENCODE_DISABLE_PRUNE"] = "1"
        }
        if (settings.disableWatcher || settings.lowMemory) {
            env["OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER"] = "1"
        }
        return env
    }
}
