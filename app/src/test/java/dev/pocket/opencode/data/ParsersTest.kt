package dev.pocket.opencode.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这些样本 JSON 直接照着 opencode 服务端类型（types.gen.d.ts）写，
 * 保证原生对话页解析的字段名与官方一致。
 */
class ParsersTest {

    @Test
    fun `解析 session`() {
        val json = JSONObject(
            """
            {
              "id": "ses_abc",
              "projectID": "prj_1",
              "directory": "/root/workspace",
              "title": "写个脚本",
              "version": "1.19.0",
              "time": { "created": 1730000000000, "updated": 1730000100000 },
              "summary": { "additions": 12, "deletions": 3, "files": 2 }
            }
            """.trimIndent(),
        )
        val session = Parsers.session(json)
        assertEquals("ses_abc", session.id)
        assertEquals("写个脚本", session.title)
        assertEquals(1730000000000L, session.created)
        assertEquals(1730000100000L, session.updated)
        assertEquals(12, session.additions)
        assertEquals(3, session.deletions)
        assertNull(session.parentID)
    }

    @Test
    fun `标题为空时有兜底`() {
        val session = Parsers.session(JSONObject().put("id", "ses_x"))
        assertEquals("未命名会话", session.title)
    }

    @Test
    fun `解析用户消息与 token 统计`() {
        val user = Parsers.message(
            JSONObject(
                """
                {
                  "id": "msg_1", "sessionID": "ses_abc", "role": "user",
                  "time": { "created": 1730000000000 },
                  "agent": "build",
                  "model": { "providerID": "deepseek", "modelID": "deepseek-chat" }
                }
                """.trimIndent(),
            ),
        )
        assertTrue(user.isUser)
        assertNotNull(user.created)

        val assistant = Parsers.message(
            JSONObject(
                """
                {
                  "id": "msg_2", "sessionID": "ses_abc", "role": "assistant",
                  "time": { "created": 1730000001000, "completed": 1730000005000 },
                  "parentID": "msg_1", "modelID": "deepseek-chat", "providerID": "deepseek",
                  "mode": "build", "cost": 0.00123,
                  "tokens": { "input": 1200, "output": 300, "reasoning": 40,
                    "cache": { "read": 800, "write": 100 } }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("deepseek", assistant.providerID)
        assertEquals(1200L, assistant.tokens.input)
        assertEquals(300L, assistant.tokens.output)
        assertEquals(40L, assistant.tokens.reasoning)
        assertEquals(800L, assistant.tokens.cacheRead)
        assertEquals(1540L, assistant.tokens.total)
        assertEquals(0.00123, assistant.cost, 0.0000001)
        assertFalsePending(assistant)
    }

    private fun assertFalsePending(message: MessageInfo) {
        assertTrue(!message.pending)
    }

    @Test
    fun `未完成的回复算 pending`() {
        val message = Parsers.message(
            JSONObject("""{"id":"msg_3","sessionID":"s","role":"assistant","time":{"created":1}}"""),
        )
        assertTrue(message.pending)
    }

    @Test
    fun `解析文本 推理 与工具调用`() {
        val text = Parsers.part(
            JSONObject(
                """
                {"id":"prt_1","sessionID":"ses","messageID":"msg","type":"text","text":"你好"}
                """.trimIndent(),
            ),
        )
        assertEquals(Part.Text("prt_1", "msg", "你好", false), text)

        val reasoning = Parsers.part(
            JSONObject("""{"id":"prt_2","sessionID":"ses","messageID":"msg","type":"reasoning","text":"先看目录"}"""),
        )
        assertTrue(reasoning is Part.Reasoning)

        val tool = Parsers.part(
            JSONObject(
                """
                {
                  "id": "prt_3", "sessionID": "ses", "messageID": "msg", "type": "tool",
                  "callID": "call_1", "tool": "bash",
                  "state": {
                    "status": "completed",
                    "input": { "command": "ls -la" },
                    "output": "total 0",
                    "title": "列出目录",
                    "time": { "start": 1, "end": 2 }
                  }
                }
                """.trimIndent(),
            ),
        ) as Part.ToolCall
        assertEquals("bash", tool.tool)
        assertEquals("completed", tool.status)
        assertEquals("列出目录", tool.title)
        assertTrue(tool.input.contains("ls -la"))
        assertEquals("total 0", tool.output)
        assertNull(tool.error)
    }

    @Test
    fun `工具失败时带错误信息`() {
        val tool = Parsers.part(
            JSONObject(
                """
                {"id":"p","sessionID":"s","messageID":"m","type":"tool","callID":"c","tool":"edit",
                 "state":{"status":"error","input":{},"error":"权限不足","time":{"start":1,"end":2}}}
                """.trimIndent(),
            ),
        ) as Part.ToolCall
        assertEquals("权限不足", tool.error)
    }

    @Test
    fun `未知 part 不会炸`() {
        val part = Parsers.part(
            JSONObject("""{"id":"p","sessionID":"s","messageID":"m","type":"未来新类型","foo":1}"""),
        )
        assertTrue(part is Part.Unknown)
        assertEquals("未来新类型", (part as Part.Unknown).type)
    }

    @Test
    fun `错误对象翻译成人话`() {
        val auth = Parsers.formatError(
            JSONObject("""{"name":"ProviderAuthError","data":{"providerID":"deepseek","message":"bad key"}}"""),
        )
        assertTrue(auth!!.contains("API Key"))

        val api = Parsers.formatError(
            JSONObject("""{"name":"APIError","data":{"message":"rate limited","statusCode":429}}"""),
        )
        assertTrue(api!!.contains("429"))

        assertNull(Parsers.formatError(null))
    }

    @Test
    fun `解析会话消息列表`() {
        val array = JSONArray(
            """
            [
              {"info":{"id":"m1","sessionID":"s","role":"user","time":{"created":1}},"parts":[{"id":"p1","sessionID":"s","messageID":"m1","type":"text","text":"hi"}]},
              {"info":{"id":"m2","sessionID":"s","role":"assistant","time":{"created":2}},"parts":[{"id":"p2","sessionID":"s","messageID":"m2","type":"step-finish","reason":"stop","cost":0.01,"tokens":{"input":10,"output":5,"reasoning":0,"cache":{"read":0,"write":0}}}]}
            ]
            """.trimIndent(),
        )
        val message = Parsers.chatMessage(array.getJSONObject(0).getJSONObject("info"), array.getJSONObject(0).getJSONArray("parts"))
        assertEquals(1, message.parts.size)
        assertEquals("hi", (message.parts[0] as Part.Text).text)

        val finish = Parsers.part(
            JSONObject(
                """{"id":"p2","sessionID":"s","messageID":"m2","type":"step-finish","reason":"stop","cost":0.01,
                    "tokens":{"input":10,"output":5,"reasoning":0,"cache":{"read":0,"write":0}}}""".trimIndent(),
            ),
        ) as Part.StepFinish
        assertEquals(15L, finish.tokens.total)
    }

    @Test
    fun `解析 provider 列表与默认模型`() {
        val (providers, defaults) = Parsers.providers(
            JSONObject(
                """
                {
                  "providers": [
                    {"id":"deepseek","name":"DeepSeek","models":{
                       "deepseek-chat":{"id":"deepseek-chat","name":"DeepSeek Chat"},
                       "deepseek-reasoner":{"id":"deepseek-reasoner","name":"DeepSeek Reasoner","reasoning":true}}},
                    {"id":"empty","name":"Empty","models":{}}
                  ],
                  "default": { "deepseek": "deepseek-chat" }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(2, providers.size)
        assertEquals("DeepSeek", providers[0].name)
        assertEquals(2, providers[0].models.size)
        assertEquals("deepseek-chat", defaults["deepseek"])
    }

    @Test
    fun `解析权限请求`() {
        val request = PermissionRequest.from(
            JSONObject(
                """
                {"id":"per_1","type":"bash","pattern":"rm -rf *","sessionID":"ses",
                 "messageID":"msg","title":"执行命令 rm -rf *","metadata":{},"time":{"created":1}}
                """.trimIndent(),
            ),
        )
        assertNotNull(request)
        assertEquals("per_1", request!!.id)
        assertEquals("ses", request.sessionID)
        assertTrue(request.title.contains("rm -rf"))

        // pattern 是数组的形态
        val listPattern = PermissionRequest.from(
            JSONObject("""{"id":"per_2","sessionID":"ses","type":"edit","pattern":["a.txt","b.txt"]}"""),
        )
        assertTrue(listPattern!!.pattern.contains("a.txt"))

        assertNull(PermissionRequest.from(JSONObject("{}")))
    }

    @Test
    fun `解析 agent 列表`() {
        val agents = Parsers.agents(
            JSONArray(
                """
                [ {"name":"build","description":"默认","mode":"primary"},
                  {"name":"explore","description":"搜索","mode":"subagent"} ]
                """.trimIndent(),
            ),
        )
        assertEquals(2, agents.size)
        assertTrue(agents[0].isPrimary)
        assertTrue(!agents[1].isPrimary)
    }
}
