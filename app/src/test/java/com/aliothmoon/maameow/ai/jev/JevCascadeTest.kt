package com.aliothmoon.maameow.ai.jev

import com.aliothmoon.maameow.ai.AiToolCallCard
import com.aliothmoon.maameow.ai.ChatLoopResult
import com.aliothmoon.maameow.ai.ChatRequest
import com.aliothmoon.maameow.ai.ChatToolLoop
import com.aliothmoon.maameow.ai.OpenAiChatMessage
import com.aliothmoon.maameow.ai.OpenAiCompatibleChatClient
import com.aliothmoon.maameow.ai.mcp.MaaMcpServerFactory
import com.aliothmoon.maameow.ai.mcp.MaaToolSpec
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

class JevCascadeTest {

    private class FakeHttpServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val jevResponses = Collections.synchronizedList(mutableListOf<String>())
        private val chatResponses = Collections.synchronizedList(mutableListOf<String>())

        val jevRequests = Collections.synchronizedList(mutableListOf<String>())
        val chatRequests = Collections.synchronizedList(mutableListOf<String>())

        init {
            server.createContext("/systemone") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                jevRequests += body
                val payload = synchronized(jevResponses) {
                    if (jevResponses.isNotEmpty()) jevResponses.removeAt(0) else "{}"
                }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            server.createContext("/v1/systemone") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                jevRequests += body
                val payload = synchronized(jevResponses) {
                    if (jevResponses.isNotEmpty()) jevResponses.removeAt(0) else "{}"
                }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            server.createContext("/v1/chat/completions") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                chatRequests += body
                val payload = synchronized(chatResponses) {
                    if (chatResponses.isNotEmpty()) chatResponses.removeAt(0) else ""
                }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }

            server.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "fake-http-server").apply { isDaemon = true }
            }
            server.start()
        }

        fun enqueueJev(json: String) {
            jevResponses.add(json)
        }

        fun enqueueChatText(text: String) {
            val delta = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { put("content", text) })
                        put("finish_reason", JsonNull)
                    })
                })
            }.toString()
            val finish = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject {})
                        put("finish_reason", "stop")
                    })
                })
            }.toString()
            val sse = listOf("data: $delta", "data: $finish", "data: [DONE]").joinToString("\n\n", postfix = "\n\n")
            chatResponses.add(sse)
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

        override fun close() = server.stop(0)
    }

    private lateinit var fakeServer: FakeHttpServer
    private lateinit var httpClient: OkHttpClient

    @Before
    fun setUp() {
        fakeServer = FakeHttpServer()
        httpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        fakeServer.close()
    }

    @Test
    fun `jev box coordinate conversion to 1280x720`() {
        val box = JevBox(
            top = 0.1f,
            left = 0.2f,
            bottom = 0.3f,
            right = 0.4f,
        )
        // Center X = (0.2 + 0.4) / 2 * 1280 = 0.3 * 1280 = 384
        // Center Y = (0.1 + 0.3) / 2 * 720 = 0.2 * 720 = 144
        val (x, y) = box.toScreenCoordinates(1280, 720)
        assertEquals(384, x)
        assertEquals(144, y)
    }

    @Test
    fun `high confidence safe tool executes directly via cascade`() = runBlocking {
        val jevJsonResponse = """
            {
                "answers": {
                    "tool_choice": {
                        "choice": "maa_status",
                        "confidence": 0.96,
                        "probabilities": { "maa_status": 0.96, "maa_stop": 0.04 }
                    }
                },
                "usage": { "total_tokens": 12 }
            }
        """.trimIndent()
        fakeServer.enqueueJev(jevJsonResponse)

        val jevClient = JevSystemOneClient(httpClient)
        val controller = JevCascadeController(
            client = jevClient,
            mode = JevMode.CASCADE,
            tauToolSelect = 0.90f,
        )

        var executedTool: String? = null
        var executedArgs: String? = null
        val loop = ChatToolLoop(
            client = OpenAiCompatibleChatClient(httpClient),
            invoker = { name, args ->
                executedTool = name
                executedArgs = args
                "{\"status\":\"running\"}"
            },
            jevController = controller,
        )

        val toolCards = mutableListOf<Pair<AiToolCallCard, Boolean>>()
        val request = ChatRequest(
            baseUrl = fakeServer.baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "sys",
            messages = listOf(OpenAiChatMessage(role = "user", content = "Maa 当前状态怎么样？")),
            tools = MaaMcpServerFactory.toolSpecs,
        )

        val result = loop.run(
            request = request,
            onText = {},
            onThinking = {},
            onToolCard = { card, finished -> toolCards += card to finished },
        )

        assertEquals(ChatLoopResult.Completed, result)
        assertEquals("maa_status", executedTool)
        assertEquals(2, toolCards.size)
        val finalCard = toolCards.last().first
        assertTrue(finalCard.isFinished)
        val tier = finalCard.tier
        assertNotNull(tier)
        assertTrue(tier!!.contains("System 1"))
        assertTrue(tier.contains("Jev 1.14"))
    }

    @Test
    fun `low confidence falls through to system 2`() = runBlocking {
        val jevJsonResponse = """
            {
                "answers": {
                    "tool_choice": {
                        "choice": "maa_status",
                        "confidence": 0.75
                    }
                }
            }
        """.trimIndent()
        fakeServer.enqueueJev(jevJsonResponse)
        fakeServer.enqueueChatText("System 2 response")

        val jevClient = JevSystemOneClient(httpClient)
        val controller = JevCascadeController(
            client = jevClient,
            mode = JevMode.CASCADE,
            tauToolSelect = 0.90f,
        )

        var executedTool: String? = null
        val loop = ChatToolLoop(
            client = OpenAiCompatibleChatClient(httpClient),
            invoker = { name, _ ->
                executedTool = name
                ""
            },
            jevController = controller,
        )

        val texts = mutableListOf<String>()
        val request = ChatRequest(
            baseUrl = fakeServer.baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "sys",
            messages = listOf(OpenAiChatMessage(role = "user", content = "你好")),
            tools = MaaMcpServerFactory.toolSpecs,
        )

        val result = loop.run(
            request = request,
            onText = { texts += it },
            onThinking = {},
            onToolCard = { _, _ -> },
        )

        assertEquals(ChatLoopResult.Completed, result)
        assertEquals(null, executedTool)
        assertEquals("System 2 response", texts.joinToString(""))
    }

    @Test
    fun `irreversible tool falls through even with 1_0 confidence`() = runBlocking {
        // maa_delete_profile is in IRREVERSIBLE_TOOLS
        val jevJsonResponse = """
            {
                "answers": {
                    "tool_choice": {
                        "choice": "maa_delete_profile",
                        "confidence": 1.0
                    }
                }
            }
        """.trimIndent()
        fakeServer.enqueueJev(jevJsonResponse)
        fakeServer.enqueueChatText("Falling through safely")

        val jevClient = JevSystemOneClient(httpClient)
        val controller = JevCascadeController(
            client = jevClient,
            mode = JevMode.CASCADE,
            tauToolSelect = 0.90f,
        )

        var executedTool: String? = null
        val loop = ChatToolLoop(
            client = OpenAiCompatibleChatClient(httpClient),
            invoker = { name, _ ->
                executedTool = name
                ""
            },
            jevController = controller,
        )

        val texts = mutableListOf<String>()
        val request = ChatRequest(
            baseUrl = fakeServer.baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "sys",
            messages = listOf(OpenAiChatMessage(role = "user", content = "删除配置")),
            tools = MaaMcpServerFactory.toolSpecs,
        )

        val result = loop.run(
            request = request,
            onText = { texts += it },
            onThinking = {},
            onToolCard = { _, _ -> },
        )

        assertEquals(ChatLoopResult.Completed, result)
        // Ensure the irreversible tool was NOT executed directly by System 1
        assertEquals(null, executedTool)
        assertEquals("Falling through safely", texts.joinToString(""))
    }

    @Test
    fun `shadow mode logs without interception`() = runBlocking {
        val jevJsonResponse = """
            {
                "answers": {
                    "tool_choice": {
                        "choice": "maa_status",
                        "confidence": 0.99
                    }
                }
            }
        """.trimIndent()
        fakeServer.enqueueJev(jevJsonResponse)
        fakeServer.enqueueChatText("Shadow mode direct to LLM")

        val jevClient = JevSystemOneClient(httpClient)
        val controller = JevCascadeController(
            client = jevClient,
            mode = JevMode.SHADOW,
            tauToolSelect = 0.90f,
        )

        var executedTool: String? = null
        val loop = ChatToolLoop(
            client = OpenAiCompatibleChatClient(httpClient),
            invoker = { name, _ ->
                executedTool = name
                ""
            },
            jevController = controller,
        )

        val texts = mutableListOf<String>()
        val request = ChatRequest(
            baseUrl = fakeServer.baseUrl,
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "sys",
            messages = listOf(OpenAiChatMessage(role = "user", content = "查看状态")),
            tools = MaaMcpServerFactory.toolSpecs,
        )

        val result = loop.run(
            request = request,
            onText = { texts += it },
            onThinking = {},
            onToolCard = { _, _ -> },
        )

        assertEquals(ChatLoopResult.Completed, result)
        // Shadow mode must not intercept execution
        assertEquals(null, executedTool)
        assertEquals("Shadow mode direct to LLM", texts.joinToString(""))
    }
}
