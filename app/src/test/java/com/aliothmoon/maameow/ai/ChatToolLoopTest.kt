package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.mcp.ArknightsEntityValidator
import com.aliothmoon.maameow.ai.mcp.MaaMcpServerFactory
import com.aliothmoon.maameow.ai.mcp.MaaStatusDto
import com.aliothmoon.maameow.ai.mcp.MaaToolBridge
import com.aliothmoon.maameow.ai.mcp.MaaToolSpec
import com.aliothmoon.maameow.ai.mcp.RunLaunchResultDto
import com.aliothmoon.maameow.ai.mcp.ScriptSummaryDto
import com.aliothmoon.maameow.ai.mcp.TaskTypeSpec
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 用本地 mock HTTP 服务（JDK [HttpServer]）验证工具循环，不需要真机、不需要真 bridge。
 *
 * 覆盖：提示词/tools 下发 → 工具真执行 → `role:"tool"` 回灌 → 卡片 started/finished →
 * 轮次/次数上限 → AI 关闭不执行 → 取消后不再执行下一个工具。
 */
class ChatToolLoopTest {

    // ---- mock SSE 服务 ----

    private class FakeChatServer(responses: List<String>) : AutoCloseable {
        private val queue = ArrayDeque(responses)
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requestBodies: MutableList<String> = Collections.synchronizedList(mutableListOf())

        val requestCount: Int get() = requestBodies.size

        init {
            server.createContext("/v1/chat/completions") { exchange ->
                val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                requestBodies += body
                // 最后一个响应会被重复使用：用于「一直返回 tool_calls」的轮次上限场景
                val payload = synchronized(queue) {
                    if (queue.size > 1) queue.removeFirst() else queue.firstOrNull().orEmpty()
                }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "fake-chat-server").apply { isDaemon = true }
            }
            server.start()
        }

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

        override fun close() = server.stop(0)
    }

    private fun sse(vararg payloads: String): String =
        payloads.joinToString("\n\n", postfix = "\n\n") { "data: $it" }

    private fun textResponse(text: String): String {
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
        return sse(delta, finish, "[DONE]")
    }

    private fun toolCallsResponse(vararg calls: Pair<String, String>): String {
        val deltas = calls.mapIndexed { index, (name, arguments) ->
            buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("index", 0)
                        put(
                            "delta",
                            buildJsonObject {
                                put("tool_calls", buildJsonArray {
                                    add(buildJsonObject {
                                        put("index", index)
                                        put("id", "call_$index")
                                        put("type", "function")
                                        put(
                                            "function",
                                            buildJsonObject {
                                                put("name", name)
                                                put("arguments", arguments)
                                            },
                                        )
                                    })
                                })
                            },
                        )
                        put("finish_reason", JsonNull)
                    })
                })
            }.toString()
        }
        val finish = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {})
                    put("finish_reason", "tool_calls")
                })
            })
        }.toString()
        return sse(*(deltas + listOf(finish, "[DONE]")).toTypedArray())
    }

    private fun request(
        server: FakeChatServer,
        tools: List<MaaToolSpec> = MaaMcpServerFactory.toolSpecs,
    ) = ChatRequest(
        baseUrl = server.baseUrl,
        apiKey = "sk-test",
        model = "test-model",
        systemPrompt = "SYSTEM-PROMPT",
        messages = listOf(OpenAiChatMessage("user", "把今天日常跑完")),
        tools = tools,
    )

    private fun loop(
        server: FakeChatServer,
        invoker: suspend (String, String) -> String,
        limitNote: String = "LIMIT",
        maxRounds: Int = 6,
        maxCalls: Int = 8,
    ) = ChatToolLoop(
        client = OpenAiCompatibleChatClient(OkHttpClient()),
        invoker = invoker,
        limitNote = limitNote,
        maxRounds = maxRounds,
        maxCalls = maxCalls,
    )

    /** 只实现被调用到的最小行为，其余返回占位结果。 */
    private class FakeMaaToolBridge : MaaToolBridge {
        override val taskSpecs: List<TaskTypeSpec> = emptyList()
        override val validator = ArknightsEntityValidator(
            validStages = emptySet(),
            stageAliases = emptyMap(),
            validOperators = emptySet(),
            validItemIds = emptyMap(),
            taskSpecsByType = emptyMap(),
        )

        var runProfileCalls = 0
        var lastRunProfileId: String? = null

        override suspend fun getStatus() = MaaStatusDto(
            state = "IDLE",
            activeProfileId = "p1",
            clientType = "Official",
        )

        override suspend fun listProfiles(): JsonArray = buildJsonArray {}

        override suspend fun getProfile(profileId: String): JsonObject? = null

        override suspend fun createProfile(name: String, chain: JsonArray): Result<JsonObject> =
            Result.failure(UnsupportedOperationException())

        override suspend fun updateProfile(
            profileId: String,
            name: String?,
            chain: JsonArray?,
        ): Result<JsonObject> = Result.failure(UnsupportedOperationException())

        override suspend fun deleteProfile(profileId: String): Result<Boolean> =
            Result.failure(UnsupportedOperationException())

        override suspend fun runProfile(profileId: String?, force: Boolean): Result<RunLaunchResultDto> {
            runProfileCalls++
            lastRunProfileId = profileId
            return Result.success(
                RunLaunchResultDto(
                    started = true,
                    runId = "run-1",
                    profileId = profileId ?: "p1",
                    message = "started",
                ),
            )
        }

        override suspend fun stop(): Result<Boolean> = Result.failure(UnsupportedOperationException())

        override suspend fun saveScript(
            name: String,
            fromRecordedOrLastRun: Boolean,
            chainOverride: JsonArray?,
        ): Result<ScriptSummaryDto> = Result.failure(UnsupportedOperationException())

        override suspend fun listScripts(): List<ScriptSummaryDto> = emptyList()

        override suspend fun runScript(profileId: String, force: Boolean): Result<RunLaunchResultDto> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getLogs(limit: Int, redactSensitive: Boolean): List<String> = emptyList()

        override suspend fun captureScreenshotBase64(): Result<String> =
            Result.failure(UnsupportedOperationException())

        override suspend fun loadAndRunCopilot(
            copilotInput: String,
            configJson: JsonObject?,
        ): Result<RunLaunchResultDto> = Result.failure(UnsupportedOperationException())

        override suspend fun searchWiki(query: String, limit: Int, wiki: String): Result<String> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getUserData(category: String, filter: String?): Result<JsonObject> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getWikiArticle(title: String, wiki: String): Result<String> =
            Result.failure(UnsupportedOperationException())

        override suspend fun click(x: Int, y: Int): Result<Boolean> =
            Result.failure(UnsupportedOperationException())

        override suspend fun backToHome(): Result<Boolean> =
            Result.failure(UnsupportedOperationException())
    }

    // ---- 测试 ----

    @Test
    fun `tool loop executes a requested tool and feeds the result back as role tool`() = runBlocking {
        val server = FakeChatServer(
            listOf(
                toolCallsResponse("maa_run_profile" to """{"profile_id":"p1"}"""),
                textResponse("已按『刷日常』开始"),
            ),
        )
        val bridge = FakeMaaToolBridge()
        val invoker = MaaToolBridgeInvoker(bridge, screenshotEnabled = { false })
        val loop = ChatToolLoop(
            client = OpenAiCompatibleChatClient(OkHttpClient()),
            invoker = { name, arguments -> invoker.invoke(name, arguments) },
            limitNote = "LIMIT",
        )

        val texts = mutableListOf<String>()
        val cards = mutableListOf<Pair<AiToolCallCard, Boolean>>()

        val result = loop.run(
            request = request(server),
            onText = { texts += it },
            onThinking = {},
            onToolCard = { card, finished -> cards += card to finished },
        )

        assertEquals(ChatLoopResult.Completed, result)
        assertEquals(1, bridge.runProfileCalls)
        assertEquals("p1", bridge.lastRunProfileId)
        assertEquals("已按『刷日常』开始", texts.joinToString(""))

        // 第一次请求：系统提示词 + tools[] 真的发出去了
        val first = server.requestBodies[0]
        assertTrue("system prompt must be sent: $first", first.contains("SYSTEM-PROMPT"))
        assertTrue("tools must be sent: $first", first.contains("\"tools\""))
        assertTrue("tool schema must be sent: $first", first.contains("maa_run_profile"))

        // 第二次请求：assistant(tool_calls) + role:"tool" + 工具真实结果
        val second = server.requestBodies[1]
        assertTrue("tool result must be fed back: $second", second.contains("\"role\":\"tool\""))
        assertTrue("tool_call_id must match: $second", second.contains("\"tool_call_id\":\"call_0\""))
        assertTrue("bridge result must be in history: $second", second.contains("run-1"))
        assertTrue("assistant tool_calls must be replayed: $second", second.contains("\"tool_calls\""))

        server.close()
    }

    @Test
    fun `tool loop stops at max rounds and appends the limit note`() = runBlocking {
        val server = FakeChatServer(listOf(toolCallsResponse("maa_status" to "{}")))
        var invocations = 0
        val texts = mutableListOf<String>()

        val result = loop(
            server = server,
            invoker = { _, _ ->
                invocations++
                "{}"
            },
            maxRounds = 1,
        ).run(
            request = request(server),
            onText = { texts += it },
            onThinking = {},
            onToolCard = { _, _ -> },
        )

        assertEquals(ChatLoopResult.LimitReached, result)
        assertTrue("limit note must be appended: $texts", texts.contains("LIMIT"))
        assertEquals("no tool may run after the limit", 0, invocations)
        assertEquals("must not request again", 1, server.requestCount)

        server.close()
    }

    @Test
    fun `tool loop stops at max calls`() = runBlocking {
        val server = FakeChatServer(
            listOf(toolCallsResponse("maa_status" to "{}", "maa_stop" to "{}")),
        )
        var invocations = 0
        val texts = mutableListOf<String>()

        val result = loop(
            server = server,
            invoker = { _, _ ->
                invocations++
                "{}"
            },
            maxCalls = 1,
        ).run(
            request = request(server),
            onText = { texts += it },
            onThinking = {},
            onToolCard = { _, _ -> },
        )

        assertEquals(ChatLoopResult.LimitReached, result)
        assertTrue(texts.contains("LIMIT"))
        assertEquals("batch larger than remaining budget must not run", 0, invocations)
        assertEquals(1, server.requestCount)

        server.close()
    }

    @Test
    fun `tool loop emits started then finished tool cards`() = runBlocking {
        val server = FakeChatServer(
            listOf(
                toolCallsResponse("maa_status" to "{}"),
                textResponse("现在空闲"),
            ),
        )
        val cards = mutableListOf<Pair<AiToolCallCard, Boolean>>()

        val result = loop(
            server = server,
            invoker = { _, _ -> """{"ok":true}""" },
        ).run(
            request = request(server),
            onText = {},
            onThinking = {},
            onToolCard = { card, finished -> cards += card to finished },
        )

        assertEquals(ChatLoopResult.Completed, result)
        assertEquals(2, cards.size)

        val (started, startedFinished) = cards[0]
        val (finished, finishedFlag) = cards[1]
        assertFalse(startedFinished)
        assertFalse(started.isFinished)
        assertEquals("maa_status", started.toolName)
        assertEquals("{}", started.toolInput)

        assertTrue(finishedFlag)
        assertTrue(finished.isFinished)
        assertEquals("same card id", started.id, finished.id)
        assertEquals("""{"ok":true}""", finished.observation)

        server.close()
    }

    @Test
    fun `tool loop does not execute tools when ai control is disabled`() = runBlocking {
        val server = FakeChatServer(
            listOf(
                toolCallsResponse("maa_stop" to "{}"),
                textResponse("AI 控制已关闭"),
            ),
        )
        var invocations = 0

        val result = loop(
            server = server,
            invoker = { _, _ ->
                invocations++
                "{}"
            },
        ).run(
            // AI 控制关闭：ViewModel 传 tools = emptyList()
            request = request(server, tools = emptyList()),
            onText = {},
            onThinking = {},
            onToolCard = { _, _ -> },
        )

        assertEquals(ChatLoopResult.Completed, result)
        assertEquals("must never touch the bridge", 0, invocations)

        val body = server.requestBodies.single()
        assertFalse("no tools[] when disabled: $body", body.contains("\"tools\""))

        server.close()
    }

    @Test
    fun `tool loop cancellation before a tool call prevents execution`() = runBlocking {
        val server = FakeChatServer(
            listOf(
                toolCallsResponse("maa_status" to "{}"),
                textResponse("第二轮不应该到达"),
            ),
        )
        var invocations = 0
        val loop = loop(
            server = server,
            invoker = { _, _ ->
                invocations++
                """{"ok":true}"""
            },
        )

        lateinit var job: Job
        // 取消发生在第一轮工具「已完成」之后：第二轮的工具不得执行，也不再发第二次请求
        job = launch(start = CoroutineStart.LAZY) {
            loop.run(
                request = request(server),
                onText = {},
                onThinking = {},
                onToolCard = { _, finished -> if (finished) job.cancel() },
            )
        }
        job.start()
        job.join()

        assertTrue("job must be cancelled", job.isCancelled)
        assertEquals("round 2 tool must not run", 1, invocations)
        assertEquals("round 2 request must not be sent", 1, server.requestCount)

        server.close()
    }
}
