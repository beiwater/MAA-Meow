package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.mcp.MaaMcpServerFactory
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException

class OpenAiCompatibleChatClientTest {

    private val json = JsonUtils.common

    /** 覆盖：空内容 delta、两条正文 delta、finish_reason 收尾、[DONE] 终止。 */
    private val sseFixture = listOf(
        """{"id":"c1","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}""",
        """{"id":"c1","choices":[{"index":0,"delta":{"content":"你好"},"finish_reason":null}]}""",
        """{"id":"c1","choices":[{"index":0,"delta":{"content":"，世界"},"finish_reason":null}]}""",
        """{"id":"c1","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
        "[DONE]",
    ).joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    private val expectedEvents = listOf(
        ChatStreamEvent.TextDelta("你好"),
        ChatStreamEvent.TextDelta("，世界"),
        ChatStreamEvent.Completed("stop"),
        ChatStreamEvent.Completed(null),
    )

    @Test
    fun `SSE parser appends two content deltas, skips empty delta, then finish_reason and DONE`() {
        val client = OpenAiCompatibleChatClient(OkHttpClient())

        val events = sseFixture.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .flatMap { line ->
                client.parseSseData(line.removePrefix("data:").trim()).asSequence()
            }
            .toList()

        assertEquals(expectedEvents, events)
    }

    @Test
    fun `streams deltas over OkHttp with bearer auth and stream request body`() = runBlocking {
        val captured = mutableListOf<Request>()
        val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
            captured += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/event-stream")
                .body(sseFixture.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()

        val client = OpenAiCompatibleChatClient(okHttp)
        val events = client.streamChat(
            baseUrl = "https://api.deepseek.com/v1/",
            apiKey = "sk-test-key",
            model = "deepseek-chat",
            messages = listOf(OpenAiChatMessage("user", "把今天的日常跑完")),
            systemPrompt = "be brief",
        ).toList()

        val text = events.filterIsInstance<ChatStreamEvent.TextDelta>()
            .joinToString("") { it.delta }
        assertEquals("你好，世界", text)
        assertEquals(expectedEvents, events)
        assertFalse(events.any { it is ChatStreamEvent.Error })

        val request = captured.single()
        assertEquals("https://api.deepseek.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer sk-test-key", request.header("Authorization"))
        assertEquals("application/json; charset=utf-8", request.body?.contentType().toString())

        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val payload = json.parseToJsonElement(buffer.readUtf8()).jsonObject
        assertEquals("deepseek-chat", payload["model"]!!.jsonPrimitive.content)
        assertEquals("true", payload["stream"]!!.jsonPrimitive.content)

        val messages = payload["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("be brief", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `non-2xx response surfaces HTTP status and provider body as error event`() = runBlocking {
        val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(401)
                .message("Unauthorized")
                .header("Content-Type", "application/json")
                .body(
                    """{"error":{"message":"invalid api key"}}"""
                        .toResponseBody("application/json".toMediaType()),
                )
                .build()
        }.build()

        val client = OpenAiCompatibleChatClient(okHttp)
        val events = client.streamChat(
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk-rejected",
            model = "deepseek-chat",
            messages = listOf(OpenAiChatMessage("user", "hi")),
        ).toList()

        val error = events.single() as ChatStreamEvent.Error
        assertEquals(ChatErrorKind.UNAUTHORIZED, error.kind)
        assertEquals(401, error.httpCode)
        assertEquals("api.deepseek.com", error.host)
        assertEquals(443, error.port)
        val detail = error.detail.orEmpty()
        assertTrue("must carry status code: $detail", detail.contains("HTTP 401"))
        assertTrue("must carry provider body: $detail", detail.contains("invalid api key"))
    }

    @Test
    fun `connection refused is reported as NETWORK error with host and port`() = runBlocking {
        val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
            throw ConnectException("Failed to connect to ${chain.request().url.host}")
        }.build()

        val client = OpenAiCompatibleChatClient(okHttp)
        val events = client.streamChat(
            baseUrl = "http://127.0.0.1/v1",
            apiKey = "sk-any",
            model = "deepseek-chat",
            messages = listOf(OpenAiChatMessage("user", "hi")),
        ).toList()

        val error = events.single() as ChatStreamEvent.Error
        assertEquals(ChatErrorKind.NETWORK, error.kind)
        assertEquals("127.0.0.1", error.host)
        assertEquals(80, error.port)
        assertTrue(
            "raw exception must be kept: ${error.detail}",
            error.detail.orEmpty().contains("Failed to connect"),
        )
    }

    // ---- 推理模型：思维链单独一条通道 ----

    /** OpenRouter 用 `reasoning`，DeepSeek 用 `reasoning_content`；两者都不得混进正文。 */
    private val reasoningFixture = listOf(
        """{"id":"c2","choices":[{"index":0,"delta":{"role":"assistant","reasoning":"我先看一下"},"finish_reason":null}]}""",
        """{"id":"c2","choices":[{"index":0,"delta":{"reasoning_content":"当前状态"},"finish_reason":null}]}""",
        """{"id":"c2","choices":[{"index":0,"delta":{"content":"正在运行"},"finish_reason":"stop"}]}""",
        "[DONE]",
    ).joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    /** 一个工具调用：name 只在第一片，arguments 被切成两段。 */
    private val toolCallFixture = listOf(
        """{"id":"c3","choices":[{"index":0,"delta":{"role":"assistant","content":null},"finish_reason":null}]}""",
        """{"id":"c3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"maa_run_profile","arguments":""}}]},"finish_reason":null}]}""",
        """{"id":"c3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"profile_id\":"}}]},"finish_reason":null}]}""",
        """{"id":"c3","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"p1\",\"force\":true}"}}]},"finish_reason":null}]}""",
        """{"id":"c3","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""",
        "[DONE]",
    ).joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    @Test
    fun `reasoning deltas stay separate from content`() {
        val client = OpenAiCompatibleChatClient(OkHttpClient())

        val events = reasoningFixture.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .flatMap { line -> client.parseSseData(line.removePrefix("data:").trim()).asSequence() }
            .toList()

        assertEquals(
            listOf(
                ChatStreamEvent.ThinkingDelta("我先看一下"),
                ChatStreamEvent.ThinkingDelta("当前状态"),
                ChatStreamEvent.TextDelta("正在运行"),
                ChatStreamEvent.Completed("stop"),
                ChatStreamEvent.Completed(null),
            ),
            events,
        )
        assertEquals(
            "我先看一下当前状态",
            events.filterIsInstance<ChatStreamEvent.ThinkingDelta>().joinToString("") { it.delta },
        )
        assertEquals(
            "正在运行",
            events.filterIsInstance<ChatStreamEvent.TextDelta>().joinToString("") { it.delta },
        )
    }

    @Test
    fun `tool call deltas accumulate name and split arguments into one call`() {
        val client = OpenAiCompatibleChatClient(OkHttpClient())

        val events = toolCallFixture.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .flatMap { line -> client.parseSseData(line.removePrefix("data:").trim()).asSequence() }
            .toList()

        assertEquals(
            listOf(
                ChatStreamEvent.ToolCallDelta(
                    index = 0,
                    id = "call_abc",
                    name = "maa_run_profile",
                    argumentsDelta = "",
                ),
                ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = """{"profile_id":"""),
                ChatStreamEvent.ToolCallDelta(index = 0, argumentsDelta = """"p1","force":true}"""),
                ChatStreamEvent.Completed("tool_calls"),
                ChatStreamEvent.Completed(null),
            ),
            events,
        )

        val accumulator = ToolCallAccumulator()
        events.filterIsInstance<ChatStreamEvent.ToolCallDelta>().forEach(accumulator::apply)
        assertEquals(
            listOf(
                OpenAiToolCall(
                    id = "call_abc",
                    name = "maa_run_profile",
                    arguments = """{"profile_id":"p1","force":true}""",
                ),
            ),
            accumulator.build(),
        )
    }

    @Test
    fun `tool call stream emits ToolCallsReady before Completed and sends tools and tool messages`() = runBlocking {
        val captured = mutableListOf<Request>()
        val okHttp = OkHttpClient.Builder().addInterceptor { chain ->
            captured += chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "text/event-stream")
                .body(toolCallFixture.toResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()

        val client = OpenAiCompatibleChatClient(okHttp)
        val runProfileSpec = MaaMcpServerFactory.toolSpecs.first { it.name == "maa_run_profile" }

        val events = client.streamChat(
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-test",
            model = "meta/muse-spark-1.3-contributor",
            messages = listOf(
                OpenAiChatMessage("user", "把今天日常跑完"),
                OpenAiChatMessage(
                    role = "assistant",
                    content = "",
                    toolCalls = listOf(
                        OpenAiToolCall("call_abc", "maa_run_profile", """{"profile_id":"p1"}"""),
                    ),
                ),
                OpenAiChatMessage(role = "tool", content = """{"started":true}""", toolCallId = "call_abc"),
            ),
            systemPrompt = "你是 MaaMeow 内置助手",
            tools = listOf(runProfileSpec),
        ).toList()

        val ready = events.filterIsInstance<ChatStreamEvent.ToolCallsReady>().single()
        assertEquals(
            listOf(OpenAiToolCall("call_abc", "maa_run_profile", """{"profile_id":"p1","force":true}""")),
            ready.calls,
        )
        // 先给 UI 调用清单，再收尾：ViewModel 才来得及建工具卡片
        assertTrue(
            "ToolCallsReady must precede Completed(tool_calls)",
            events.indexOf(ready) < events.indexOf(ChatStreamEvent.Completed("tool_calls")),
        )

        val buffer = Buffer()
        captured.single().body?.writeTo(buffer)
        val payload = json.parseToJsonElement(buffer.readUtf8()).jsonObject

        // 1. tools[] 序列化
        assertEquals("auto", payload["tool_choice"]!!.jsonPrimitive.content)
        val tools = payload["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val toolJson = tools[0].jsonObject
        assertEquals("function", toolJson["type"]!!.jsonPrimitive.content)
        val function = toolJson["function"]!!.jsonObject
        assertEquals("maa_run_profile", function["name"]!!.jsonPrimitive.content)
        assertEquals("object", function["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        // 2. assistant(tool_calls) 与 role:"tool" 的序列化
        val messages = payload["messages"]!!.jsonArray
        assertEquals(4, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)

        val assistant = messages[2].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, assistant["content"])
        val wireCall = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_abc", wireCall["id"]!!.jsonPrimitive.content)
        assertEquals("function", wireCall["type"]!!.jsonPrimitive.content)
        assertEquals("maa_run_profile", wireCall["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)

        val toolMessage = messages[3].jsonObject
        assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
        assertEquals("call_abc", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"started":true}""", toolMessage["content"]!!.jsonPrimitive.content)
    }
}
