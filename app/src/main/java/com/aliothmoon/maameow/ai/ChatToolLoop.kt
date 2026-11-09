package com.aliothmoon.maameow.ai
import com.aliothmoon.maameow.ai.usage.recordAiUsage
import com.aliothmoon.maameow.ai.jev.JevCascadeController
import com.aliothmoon.maameow.ai.jev.JevInterceptDecision
import com.aliothmoon.maameow.ai.mcp.MaaToolSpec
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.coroutineContext
/** 一轮用户输入的完整请求参数（历史 + 系统提示词 + 可选工具表）。 */
data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String,
    val messages: List<OpenAiChatMessage>,
    val tools: List<MaaToolSpec>,
    val temperature: Float? = null,
)

/** 工具循环的终止原因。本地化文案由调用方决定（loop 不持 Context）。 */
sealed interface ChatLoopResult {
    /** 模型不再请求工具，这一轮就是最终回答。 */
    data object Completed : ChatLoopResult

    /** 达到轮次/调用次数上限（[ChatToolLoop] 已把 `limitNote` 交给 `onText`）。 */
    data object LimitReached : ChatLoopResult

    /** 流内错误；字段原样交给上层做本地化映射。 */
    data class Failure(
        val kind: ChatErrorKind,
        val host: String,
        val port: Int,
        val httpCode: Int,
        val detail: String?,
    ) : ChatLoopResult
}

/**
 * 应用内助手的「请求 → 执行工具 → 回灌结果 → 再请求」循环。
 *
 * 从 `AiControlViewModel` 里抽出来是为了能在 JVM 单测里用本地 mock HTTP 服务验证：
 * 不需要 Android、不需要真机、不需要真的 MaaToolBridge。
 *
 * - 每回合最多 [maxRounds] 轮模型请求、[maxCalls] 次工具调用；默认上限 32 轮 / 64 次；
 * - [request] 的 `tools` 为空时（AI 控制关闭）绝不执行任何工具；
 * - 协程被取消后，下一个工具调用不会开始。
 */
class ChatToolLoop(
    private val client: OpenAiCompatibleChatClient,
    private val invoker: suspend (name: String, argumentsJson: String) -> String,
    /** 触达上限时追加到回答气泡的一句话（由 ViewModel 传入本地化文案）。 */
    private val limitNote: String = "",
    private val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    private val maxCalls: Int = DEFAULT_MAX_CALLS,
    private val observationLimit: Int = 400,
    private val jevController: JevCascadeController? = null,
) {
    companion object {
        const val DEFAULT_MAX_ROUNDS = 32
        const val DEFAULT_MAX_CALLS = 64
    }


    /**
     * @param onText 正文增量（含到达上限时的 [limitNote]）
     * @param onThinking 思维链增量，调用方必须与正文分开存
     * @param onToolCard 工具卡片：`finished=false` 表示开始执行，`true` 表示已拿到观察结果；
     *        两次回调携带同一个 `id`
     */
    suspend fun run(
        request: ChatRequest,
        onText: (String) -> Unit,
        onThinking: (String) -> Unit,
        onToolCard: (AiToolCallCard, Boolean) -> Unit,
        cascadeController: JevCascadeController? = jevController,
    ): ChatLoopResult {
        val working = request.messages.toMutableList()
        var rounds = 0
        var executedCalls = 0

        // Jev System 1 级联拦截检查
        val effectiveController = cascadeController
        if (effectiveController != null && request.tools.isNotEmpty()) {
            val userState = request.messages.lastOrNull { it.role == "user" }?.content
                ?: request.messages.lastOrNull()?.content.orEmpty()
            val toolOptions = request.tools.map { it.name }
            val decision = effectiveController.evaluateIntercept(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                stateText = userState,
                toolOptions = toolOptions,
            )
            when (decision) {
                is JevInterceptDecision.DirectTool -> {
                    coroutineContext.ensureActive()
                    val cardId = UUID.randomUUID().toString()
                    val started = AiToolCallCard(
                        id = cardId,
                        toolName = decision.toolName,
                        toolInput = decision.argumentsJson,
                        isFinished = false,
                        tier = decision.tier,
                    )
                    onToolCard(started, false)
                    val result = invoker(decision.toolName, decision.argumentsJson)
                    val parsed = parseToolResult(result, observationLimit)
                    onToolCard(
                        started.copy(
                            observation = parsed.observation,
                            screenshotBase64 = parsed.screenshotBase64,
                            detailLogs = parsed.detailLogs,
                            isFinished = true,
                        ),
                        true,
                    )
                    return ChatLoopResult.Completed
                }
                is JevInterceptDecision.DirectClick -> {
                    coroutineContext.ensureActive()
                    val cardId = UUID.randomUUID().toString()
                    val inputJson = """{"x":${decision.x},"y":${decision.y}}"""
                    val started = AiToolCallCard(
                        id = cardId,
                        toolName = "maa_click",
                        toolInput = inputJson,
                        isFinished = false,
                        tier = decision.tier,
                    )
                    onToolCard(started, false)
                    val result = invoker("maa_click", inputJson)
                    val parsed = parseToolResult(result, observationLimit)
                    onToolCard(
                        started.copy(
                            observation = parsed.observation,
                            screenshotBase64 = parsed.screenshotBase64,
                            detailLogs = parsed.detailLogs,
                            isFinished = true,
                        ),
                        true,
                    )
                    return ChatLoopResult.Completed
                }
                is JevInterceptDecision.PassThrough -> {
                    // 继续回退到 System 2
                }
            }
        }

        while (true) {
            rounds++
            var requested: List<OpenAiToolCall> = emptyList()
            var failure: ChatStreamEvent.Error? = null
            // 本轮模型说过的话：要随 assistant(tool_calls) 一起回灌，否则上下文会丢
            val roundText = StringBuilder()

            client.streamChat(
                baseUrl = request.baseUrl,
                apiKey = request.apiKey,
                model = request.model,
                messages = working,
                systemPrompt = request.systemPrompt,
                tools = request.tools,
                temperature = request.temperature,
            ).collect { event ->
                when (event) {
                    is ChatStreamEvent.TextDelta -> {
                        roundText.append(event.delta)
                        onText(event.delta)
                    }
                    is ChatStreamEvent.ThinkingDelta -> onThinking(event.delta)
                    is ChatStreamEvent.ToolCallsReady -> requested = event.calls
                    // 单条 delta 已由客户端累加成整包，这里只等 ToolCallsReady
                    is ChatStreamEvent.Usage -> recordAiUsage(event.usage)
                    // 单条 delta 已由客户端累加成整包，这里只等 ToolCallsReady
                    is ChatStreamEvent.ToolCallDelta -> Unit
                    is ChatStreamEvent.Completed -> Unit
                    is ChatStreamEvent.Error -> failure = event
                }
            }

            failure?.let {
                return ChatLoopResult.Failure(
                    kind = it.kind,
                    host = it.host,
                    port = it.port,
                    httpCode = it.httpCode,
                    detail = it.detail,
                )
            }

            val calls = requested.filter { it.name.isNotBlank() }
            // 没有工具调用 → 最终回答；tools 为空（AI 控制关闭）时永远不执行
            if (calls.isEmpty() || request.tools.isEmpty()) return ChatLoopResult.Completed

            // 检查工具执行预算：
            // 1. 若 rounds 已经超出 maxRounds（例如 maxRounds <= 0），绝不能执行工具；
            // 2. 本批次工具调用量大于剩余配额，不能超预算执行。
            if (rounds > maxRounds || calls.size > maxCalls - executedCalls) {
                if (limitNote.isNotBlank()) onText(limitNote)
                return ChatLoopResult.LimitReached
            }

            working += OpenAiChatMessage(
                role = "assistant",
                content = roundText.toString(),
                toolCalls = calls,
            )

            var screenshots: MutableList<String>? = null

            for (call in calls) {
                // 停止按钮取消协程后立刻停手：不再执行后续工具
                coroutineContext.ensureActive()

                val cardId = call.id.ifBlank { UUID.randomUUID().toString() }
                val started = AiToolCallCard(
                    id = cardId,
                    toolName = call.name,
                    toolInput = call.arguments,
                    isFinished = false,
                )
                onToolCard(started, false)

                val result = invoker(call.name, call.arguments)
                val parsed = parseToolResult(result, observationLimit)
                onToolCard(
                    started.copy(
                        observation = parsed.observation,
                        screenshotBase64 = parsed.screenshotBase64,
                        detailLogs = parsed.detailLogs,
                        isFinished = true,
                    ),
                    true,
                )
                // 从模型文本结果中剥离 base64 图片数据，保留协议完整与卡片原有截图
                working += OpenAiChatMessage(
                    role = "tool",
                    content = parsed.modelText,
                    toolCallId = cardId,
                )
                parsed.screenshotBase64?.takeIf { it.isNotBlank() }?.let {
                    val images = screenshots ?: mutableListOf<String>().also { screenshots = it }
                    images += it
                }
                executedCalls++
            }

            // 通过 OpenAI image_url 多模态 user 消息传给模型（在所有 tool 结果之后，确保 OpenAI 协议完整）
            screenshots?.let {
                working += OpenAiChatMessage(
                    role = "user",
                    content = "Current tool screenshots in execution order. Use the observed state, not dispatch success alone, to judge progress. Do not repeat completed actions blindly.",
                    imageUrls = it,
                )
            }

            coroutineContext.ensureActive()

            // 最后一轮工具执行完毕后，检查是否已达到预算上限。
            // 不能超预算、不能无响应循环。
            if (rounds >= maxRounds || executedCalls >= maxCalls) {
                if (limitNote.isNotBlank()) onText(limitNote)
                return ChatLoopResult.LimitReached
            }
        }
    }


    private data class ParsedToolResult(
        val observation: String,
        val screenshotBase64: String?,
        val detailLogs: String?,
        val modelText: String,
    )

    private fun parseToolResult(result: String, limit: Int): ParsedToolResult {
        return try {
            val element = JsonUtils.common.parseToJsonElement(result)
            if (element is JsonObject) {
                val screenshot = (element["screenshot_base64"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["screenshot"] as? JsonPrimitive)?.contentOrNull
                val modelText = if (screenshot != null) {
                    buildJsonObject {
                        element.forEach { (key, value) ->
                            if (key != "screenshot_base64" && key != "screenshot") put(key, value)
                        }
                    }.toString()
                } else result
                val detailLogs = (element["detail_logs"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["logs"] as? JsonPrimitive)?.contentOrNull
                    ?: modelText
                val obs = (element["message"] as? JsonPrimitive)?.contentOrNull
                    ?: modelText.take(limit)
                ParsedToolResult(
                    observation = obs.take(limit),
                    screenshotBase64 = screenshot,
                    detailLogs = detailLogs,
                    modelText = modelText,
                )
            } else {
                ParsedToolResult(
                    observation = result.take(limit),
                    screenshotBase64 = null,
                    detailLogs = result,
                    modelText = result,
                )
            }
        } catch (_: Exception) {
            ParsedToolResult(
                observation = result.take(limit),
                screenshotBase64 = null,
                detailLogs = result,
                modelText = result,
            )
        }
    }
}
