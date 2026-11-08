package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.mcp.MaaToolSpec
import kotlinx.coroutines.ensureActive
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
 * - 每回合最多 [maxRounds] 轮模型请求、[maxCalls] 次工具调用；
 * - [request] 的 `tools` 为空时（AI 控制关闭）绝不执行任何工具；
 * - 协程被取消后，下一个工具调用不会开始。
 */
class ChatToolLoop(
    private val client: OpenAiCompatibleChatClient,
    private val invoker: suspend (name: String, argumentsJson: String) -> String,
    /** 触达上限时追加到回答气泡的一句话（由 ViewModel 传入本地化文案）。 */
    private val limitNote: String = "",
    private val maxRounds: Int = 6,
    private val maxCalls: Int = 8,
    private val observationLimit: Int = 400,
) {

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
    ): ChatLoopResult {
        val working = request.messages.toMutableList()
        var rounds = 0
        var executedCalls = 0

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
            ).collect { event ->
                when (event) {
                    is ChatStreamEvent.TextDelta -> {
                        roundText.append(event.delta)
                        onText(event.delta)
                    }
                    is ChatStreamEvent.ThinkingDelta -> onThinking(event.delta)
                    is ChatStreamEvent.ToolCallsReady -> requested = event.calls
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

            if (rounds >= maxRounds || calls.size > maxCalls - executedCalls) {
                if (limitNote.isNotBlank()) onText(limitNote)
                return ChatLoopResult.LimitReached
            }

            working += OpenAiChatMessage(
                role = "assistant",
                content = roundText.toString(),
                toolCalls = calls,
            )

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
                onToolCard(
                    started.copy(
                        observation = result.take(observationLimit),
                        isFinished = true,
                    ),
                    true,
                )
                // 完整结果交给模型，卡片里只留截断后的摘要
                working += OpenAiChatMessage(
                    role = "tool",
                    content = result,
                    toolCallId = cardId,
                )
                executedCalls++
            }
            coroutineContext.ensureActive()
        }
    }
}
