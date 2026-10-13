package dev.pocket.opencode.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpencodeConfigTest {

    private val settings = AppSettings(
        port = 4321,
        defaultModel = "deepseek/deepseek-chat",
        smallModel = "deepseek/deepseek-chat",
        permissionEdit = "ask",
        permissionBash = "allow",
        snapshot = false,
        providerKeys = mapOf(
            "deepseek" to "sk-test-123",
            "openai-compatible" to "sk-custom",
        ),
        providerBaseUrls = mapOf("openai-compatible" to "https://api.example.com/v1"),
    )

    @Test
    fun `生成的 JSON 包含模型 端口 权限与快照设置`() {
        val json = JSONObject(OpencodeConfig.build(settings))
        assertEquals("deepseek/deepseek-chat", json.getString("model"))
        assertEquals(false, json.getBoolean("autoupdate"))
        assertEquals("disabled", json.getString("share"))
        assertFalse(json.getBoolean("snapshot"))
        assertEquals(4321, json.getJSONObject("server").getInt("port"))
        assertEquals("127.0.0.1", json.getJSONObject("server").getString("hostname"))
        assertEquals("ask", json.getJSONObject("permission").getString("edit"))
        assertEquals("allow", json.getJSONObject("permission").getString("bash"))
    }

    @Test
    fun `密钥写进 provider options`() {
        val json = JSONObject(OpencodeConfig.build(settings))
        val provider = json.getJSONObject("provider")
        assertEquals("sk-test-123", provider.getJSONObject("deepseek").getJSONObject("options").getString("apiKey"))
        val custom = provider.getJSONObject("openai-compatible").getJSONObject("options")
        assertEquals("sk-custom", custom.getString("apiKey"))
        assertEquals("https://api.example.com/v1", custom.getString("baseURL"))
    }

    @Test
    fun `没有密钥时不写 provider 段`() {
        val json = JSONObject(
            OpencodeConfig.build(
                settings.copy(providerKeys = emptyMap(), providerBaseUrls = emptyMap()),
            ),
        )
        assertFalse(json.has("provider"))
    }

    @Test
    fun `启动环境变量覆盖常用 provider 并处理省电开关`() {
        val env = OpencodeConfig.environment(
            settings.copy(lowMemory = true, disableWatcher = false),
        )
        assertEquals("sk-test-123", env["DEEPSEEK_API_KEY"])
        // 自定义端点的 baseURL 不能塞进全局 OPENAI_BASE_URL，否则会污染真正的 OpenAI provider
        assertFalse(env.containsKey("OPENAI_BASE_URL"))
        assertEquals("1", env["OPENCODE_DISABLE_AUTOUPDATE"])
        assertEquals("1", env["OPENCODE_DISABLE_LSP_DOWNLOAD"])
        // 低内存模式同时关掉文件监视
        assertEquals("1", env["OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER"])
        assertTrue(env.containsKey("BROWSER"))
    }

    @Test
    fun `自定义 OpenAI 兼容端点会生成 npm 与 models`() {
        val json = JSONObject(OpencodeConfig.build(settings.copy(defaultModel = "openai-compatible/my-model")))
        val custom = json.getJSONObject("provider").getJSONObject("openai-compatible")
        assertEquals("@ai-sdk/openai-compatible", custom.getString("npm"))
        assertTrue(custom.getJSONObject("models").has("my-model"))
        assertEquals(
            "https://api.example.com/v1",
            custom.getJSONObject("options").getString("baseURL"),
        )
    }

    @Test
    fun `普通模式下不注入省电相关变量`() {
        val env = OpencodeConfig.environment(settings.copy(lowMemory = false, disableWatcher = false))
        assertFalse(env.containsKey("OPENCODE_DISABLE_LSP_DOWNLOAD"))
        assertFalse(env.containsKey("OPENCODE_EXPERIMENTAL_DISABLE_FILEWATCHER"))
    }

    @Test
    fun `provider 目录里的 id 都有展示名`() {
        OpencodeConfig.providers.forEach { provider ->
            assertTrue(provider.id.isNotBlank())
            assertTrue(provider.label.isNotBlank())
        }
        // 常见 provider 一定要在目录里
        listOf("deepseek", "anthropic", "openai", "openrouter").forEach { id ->
            assertTrue("缺少 provider: $id", OpencodeConfig.findProvider(id) != null)
        }
    }
}
