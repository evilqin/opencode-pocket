package dev.pocket.opencode.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置的编解码必须能长期演进：加字段用默认值，坏数据不崩。 */
class SettingsCodecTest {

    @Test
    fun `往返一致（含所有字段）`() {
        val original = AppSettings(
            ubuntuMirror = "ustc",
            aptMirror = "aliyun",
            nodeMirror = "tencent",
            npmMirror = "huawei",
            port = 4567,
            autoStart = false,
            lowMemory = true,
            disableWatcher = true,
            snapshot = false,
            share = "manual",
            permissionEdit = "ask",
            permissionBash = "deny",
            compatMode = true,
            installNode = false,
            deviceShell = true,
            deviceShellPort = 3210,
            deviceShellToken = "deadbeef",
            workspace = "/sdcard/Projects",
            useExternalWorkspace = false,
            providerKeys = mapOf("deepseek" to "sk-xxx", "openai" to "sk-yyy"),
            providerBaseUrls = mapOf("openai-compatible" to "https://api.example.com/v1"),
            defaultModel = "deepseek/deepseek-chat",
            smallModel = "deepseek/deepseek-chat",
            themeMode = "light",
            dynamicColor = true,
            notificationAsked = true,
            autoStartOnBoot = true,
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `默认值就是空设置`() {
        assertEquals(AppSettings(), SettingsCodec.decode(null))
        assertEquals(AppSettings(), SettingsCodec.decode(""))
        assertEquals(AppSettings(), SettingsCodec.decode("not json at all"))
        assertEquals(AppSettings(), SettingsCodec.decode("""{"unknown":123}"""))
    }

    @Test
    fun `缺字段用默认值补齐`() {
        val partial = """{"port": 5000, "themeMode": "light"}"""
        val decoded = SettingsCodec.decode(partial)
        assertEquals(5000, decoded.port)
        assertEquals("light", decoded.themeMode)
        assertEquals(AppSettings().workspace, decoded.workspace)
        assertEquals(AppSettings().snapshot, decoded.snapshot)
        assertTrue(decoded.providerKeys.isEmpty())
    }

    @Test
    fun `端口越界会被夹回合法区间`() {
        assertEquals(1024, SettingsCodec.decode("""{"port": 80}""").port)
        assertEquals(65535, SettingsCodec.decode("""{"port": 99999}""").port)
        assertEquals(1024, SettingsCodec.decode("""{"deviceShellPort": 1}""").deviceShellPort)
    }

    @Test
    fun `空白的密钥不会写进 map`() {
        val decoded = SettingsCodec.decode(
            """{"providerKeys":{"deepseek":"sk-real","openai":"","gemini":"   "}}""",
        )
        assertEquals(mapOf("deepseek" to "sk-real"), decoded.providerKeys)
    }

    @Test
    fun `中文与特殊字符不会丢`() {
        val value = AppSettings(
            defaultModel = "vendor/模型-v2",
            workspace = "/sdcard/我的项目",
            providerBaseUrls = mapOf("openai-compatible" to "https://api.example.com/v1?x=1&y=2"),
        )
        val decoded = SettingsCodec.decode(SettingsCodec.encode(value))
        assertEquals("vendor/模型-v2", decoded.defaultModel)
        assertEquals("/sdcard/我的项目", decoded.workspace)
        assertEquals("https://api.example.com/v1?x=1&y=2", decoded.providerBaseUrls["openai-compatible"])
    }

    @Test
    fun `错误类型的字段会退化成默认值而不是崩溃`() {
        val weird = """{"port":"abc","providerKeys":"nope","autoStart":"yes"}"""
        val decoded = SettingsCodec.decode(weird)
        assertEquals(AppSettings().port, decoded.port)
        assertTrue(decoded.providerKeys.isEmpty())
        assertEquals(AppSettings().autoStart, decoded.autoStart)
    }

    @Test
    fun `权限策略等字符串字段保留原样`() {
        val decoded = SettingsCodec.decode("""{"permissionEdit":"ask","permissionBash":"deny","share":"disabled"}""")
        assertEquals("ask", decoded.permissionEdit)
        assertEquals("deny", decoded.permissionBash)
        assertEquals("disabled", decoded.share)
        assertFalse(decoded.snapshot.not())
    }
}
