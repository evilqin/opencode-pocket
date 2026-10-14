package dev.pocket.opencode.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    @Test
    fun `provider 密钥被打码`() {
        val text = "auth: sk-abcdef1234567890 ok"
        val redacted = LogRedactor.redact(text)
        assertFalse(redacted.contains("abcdef1234567890"))
        assertTrue(redacted.contains("sk-***"))
    }

    @Test
    fun `key=value 形式被打码`() {
        val redacted = LogRedactor.redact("DEEPSEEK_API_KEY=sk-1234567890abcdef\nOPENAI_API_KEY=\"sk-0987654321zyxwvu\"")
        assertFalse(redacted.contains("sk-1234567890abcdef"))
        assertFalse(redacted.contains("sk-0987654321zyxwvu"))
        assertTrue(redacted.contains("DEEPSEEK_API_KEY=***"))
        assertTrue(redacted.contains("OPENAI_API_KEY=***"))
    }

    @Test
    fun `Authorization 头被打码`() {
        val redacted = LogRedactor.redact("Authorization: Bearer abcdef1234567890abcdef")
        assertFalse(redacted.contains("abcdef1234567890abcdef"))
        assertTrue(redacted.contains("Bearer ***"))
    }

    @Test
    fun `Basic 口令被打码`() {
        val redacted = LogRedactor.redact("Authorization: Basic b3BlbmNvZGU6c2VjcmV0")
        assertFalse(redacted.contains("b3BlbmNvZGU6c2VjcmV0"))
        assertTrue(redacted.contains("Basic ***"))
    }

    @Test
    fun `额外传入的秘密（桥令牌）整体替换`() {
        val token = "0f8a1b2c3d4e5f60718293a4b5c6d7e8"
        val redacted = LogRedactor.redact("curl -H 'Authorization: Bearer $token' ...", listOf(token))
        assertFalse(redacted.contains(token))
    }

    @Test
    fun `普通日志内容不受影响`() {
        val text = "[12:00:01] ✔ 基础工具：完成（git / curl / ripgrep 已就绪）\n[12:00:02] apt: Reading package lists..."
        assertEquals(text, LogRedactor.redact(text))
    }

    @Test
    fun `短字符串不会被误替换`() {
        // 太短的“秘密”不替换，避免把普通文本打得到处是 ***
        val redacted = LogRedactor.redact("port=4096 token=abc", listOf("abc"))
        assertTrue(redacted.contains("4096"))
    }

    @Test
    fun `多个密钥在同一行都会被处理`() {
        val redacted = LogRedactor.redact("keys: sk-aaaaaaaaaaaa and ghp_bbbbbbbbbbbb")
        assertFalse(redacted.contains("sk-aaaaaaaaaaaa"))
        assertFalse(redacted.contains("ghp_bbbbbbbbbbbb"))
    }

    @Test
    fun `download 相关的 shasum 也会打码`() {
        val redacted = LogRedactor.redact("shasum=a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2")
        assertTrue(redacted.contains("shasum=***"))
    }
}
