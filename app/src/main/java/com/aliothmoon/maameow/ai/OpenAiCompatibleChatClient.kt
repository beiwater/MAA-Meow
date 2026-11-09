package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.mcp.MaaToolSpec
import com.aliothmoon.maameow.ai.usage.AiRequestUsage
import java.math.BigDecimal
import java.util.UUID
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * `/chat/completions` 的 `messages[]` 元素。
 *
 * 除普通文本外还支持两种工具协议需要的形态：
 * - [toolCalls] 非空：`{"role":"assistant","content":null,"tool_calls":[…]}`；
 * - `role == "tool"`：`{"role":"tool","tool_call_id":…,"content":"<结果>"}`。
 */
@Serializable
data class OpenAiChatMessage(
    val role: String,
    val content: String = "",
    val toolCalls: List<OpenAiToolCall> = emptyList(),
    val toolCallId: String? = null,
    val imageUrls: List<String> = emptyList(),
)

/** 一次完整的工具调用（流式增量已在 [ToolCallAccumulator] 里拼好）。 */
@Serializable
data class OpenAiToolCall(
    val id: String,
    val name: String,
    /** 模型给出的 JSON 字符串参数；解析失败时为空对象 `{}`。 */
    val arguments: String,
)

/** 对话失败的分类，交由 ViewModel 映射成本地化文案。 */
enum class ChatErrorKind {
    /** 连接失败 / 超时 / DNS */
    NETWORK,

    /** 401 / 403 */
    UNAUTHORIZED,

    /** 404 */
    NOT_FOUND,

    /** 429 */
    RATE_LIMITED,

    /** 5xx */
    SERVER,

    /** 服务商在流内返回的业务错误 */
    PROVIDER,

    /** 其它未分类错误 */
    UNKNOWN,
}

internal fun classifyHttpStatus(code: Int): ChatErrorKind = when {
    code == 401 || code == 403 -> ChatErrorKind.UNAUTHORIZED
    code == 404 -> ChatErrorKind.NOT_FOUND
    code == 429 -> ChatErrorKind.RATE_LIMITED
    code in 500..599 -> ChatErrorKind.SERVER
    else -> ChatErrorKind.UNKNOWN
}

/**
 * 与具体厂商无关的对话流事件。
 * 只描述 UI 需要的状态，供 [com.aliothmoon.maameow.presentation.viewmodel.AiControlViewModel]
 * 映射到 [AiChatMessage]。
 */
sealed interface ChatStreamEvent {
    /** 正文增量，进回答气泡。 */
    data class TextDelta(val delta: String) : ChatStreamEvent

    /**
     * 推理模型的思维链增量（OpenRouter `delta.reasoning` / DeepSeek `delta.reasoning_content`）。
     * 单独存放，绝不混进正文气泡。
     */
    data class ThinkingDelta(val delta: String) : ChatStreamEvent

    /** 单条工具调用的流式增量；由 [ToolCallAccumulator] 拼接。 */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String? = null,
    ) : ChatStreamEvent

    /** 流结束且本轮请求了工具调用；[calls] 已按 index 拼好、可直接执行。 */
    data class ToolCallsReady(val calls: List<OpenAiToolCall>) : ChatStreamEvent

    data class Usage(val usage: AiRequestUsage) : ChatStreamEvent

    data class Completed(val finishReason: String?) : ChatStreamEvent

    /**
     * @param detail 原始错误文本（HTTP 状态 + 响应体 / 异常 toString），
     * 由 UI 作为小字附在友好提示下方，保持可复制。
     */
    data class Error(
        val kind: ChatErrorKind,
        val host: String = "",
        val port: Int = -1,
        val httpCode: Int = 0,
        val detail: String? = null,
    ) : ChatStreamEvent
}

/**
 * 把 `delta.tool_calls[]` 的碎片拼成完整调用。
 *
 * 服务端只在第一片给 `id`/`name`，`arguments` 会被切成任意段，甚至逐字符下发；
 * 同一次响应的多个调用用 `index` 区分。
 */
internal class ToolCallAccumulator {
    private class Partial {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private val parts = sortedMapOf<Int, Partial>()

    val isEmpty: Boolean get() = parts.isEmpty()

    fun apply(delta: ChatStreamEvent.ToolCallDelta) {
        val part = parts.getOrPut(delta.index) { Partial() }
        delta.id?.takeIf { it.isNotBlank() }?.let { part.id = it }
        delta.name?.takeIf { it.isNotBlank() }?.let { part.name = it }
        delta.argumentsDelta?.let { part.arguments.append(it) }
    }

    fun build(): List<OpenAiToolCall> = parts.map { (index, part) ->
        OpenAiToolCall(
            id = part.id.ifBlank { "call_$index" },
            name = part.name,
            arguments = part.arguments.toString().ifBlank { "{}" },
        )
    }
}

/**
 * OpenAI 兼容 `POST {baseUrl}/chat/completions` 的薄 SSE 客户端
 * （DeepSeek / OpenAI / Kimi / SiliconFlow / OpenRouter / Ollama 等同一协议）。
 *
 * 复用应用既有的 [OkHttpClient] 与 [JsonUtils]，不引入任何新依赖。
 * 流式解析直接读 `response.body.source()`，不整体缓冲响应体。
 */
class OpenAiCompatibleChatClient(
    internal val httpClient: OkHttpClient,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"

        /** 错误响应体只保留前 500 字符，避免把整页 HTML 塞进气泡 */
        private const val ERROR_BODY_LIMIT = 500

        /** 依据 Base URL 的 host 猜一个展示名，猜不出就退回 host 本身。 */
        fun vendorLabel(baseUrl: String): String {
            val host = baseUrl.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore(':')
                .lowercase()
            return when {
                host.isEmpty() -> "OpenAI"
                "deepseek" in host -> "DeepSeek"
                "openai" in host -> "OpenAI"
                "moonshot" in host || "kimi" in host -> "Kimi"
                "siliconflow" in host -> "SiliconFlow"
                "openrouter" in host -> "OpenRouter"
                "bigmodel" in host || "zhipu" in host -> "GLM"
                "dashscope" in host || "aliyun" in host -> "Qwen"
                else -> host
            }
        }
    }

    private val json = JsonUtils.common
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * @param messages 完整对话历史（不含 system），最后一条通常是本次 user 输入。
     * @param systemPrompt 非空时作为 `messages[0]` 发送；调用方不传则完全不带系统提示。
     * @param tools 非空时随请求发送 `tools[]` + `tool_choice:"auto"`，模型即可发起工具调用。
     */
    fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<OpenAiChatMessage>,
        systemPrompt: String = "",
        tools: List<MaaToolSpec> = emptyList(),
        temperature: Float? = null,
    ): Flow<ChatStreamEvent> = callbackFlow {
        val endpoint = buildEndpoint(baseUrl)
        val requestUrl = endpoint.toHttpUrl()
        val payload = buildJsonObject {
            put("model", model.trim().ifBlank { DEFAULT_MODEL })
            if (requestUrl.host == "openrouter.ai") {
                // Share the stable agent prefix across turns; this is a routing hint, not a cache guarantee.
                put("prompt_cache_key", "maameow-agent-v1")
            }
            put("stream", true)
            put(
                "stream_options",
                buildJsonObject {
                    put("include_usage", true)
                },
            )
            if (temperature != null) {
                put("temperature", temperature)
            }
            put(
                "messages",
                buildJsonArray {
                    if (systemPrompt.isNotBlank()) {
                        add(
                            buildJsonObject {
                                put("role", "system")
                                put("content", systemPrompt)
                            },
                        )
                    }
                    messages.forEach { msg -> add(msg.toWireJson()) }
                },
            )
            if (tools.isNotEmpty()) {
                put("tools", tools.toWireJson())
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(payload).toRequestBody(jsonMediaType))
            .build()

        val host = request.url.host
        val port = request.url.port
        val streamRequestId = "stream_${UUID.randomUUID()}"
        trySend(ChatStreamEvent.Usage(AiRequestUsage(
            requestId = streamRequestId,
            source = "LLM",
            model = model,
            baseUrl = baseUrl,
        )))
        val call = httpClient.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    trySend(
                        ChatStreamEvent.Error(
                            kind = ChatErrorKind.NETWORK,
                            host = host,
                            port = port,
                            detail = e.toString(),
                        ),
                    )
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = runCatching { resp.body.string() }.getOrDefault("")
                        // 只记录状态码与端点，绝不输出 Authorization
                        Timber.w("Chat completion failed: HTTP %d from %s", resp.code, endpoint)
                        trySend(
                            ChatStreamEvent.Error(
                                kind = classifyHttpStatus(resp.code),
                                host = host,
                                port = port,
                                httpCode = resp.code,
                                detail = "HTTP ${resp.code}: ${errBody.trim().take(ERROR_BODY_LIMIT)}",
                            ),
                        )
                        close()
                        return
                    }

                    val source = resp.body.source()
                    val accumulator = ToolCallAccumulator()
                    var toolsFlushed = false
                    var terminated = false
                    try {
                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            // SSE 空行/注释(keep-alive)与其它字段一律跳过
                            if (line.isBlank() || line.startsWith(":")) continue
                            if (!line.startsWith("data:")) continue
                            val dataPayload = line.removePrefix("data:").trim()
                            parseSseData(
                                dataPayload = dataPayload,
                                fallbackModel = model,
                                baseUrl = baseUrl,
                                streamRequestId = streamRequestId,
                            ).forEach { event ->
                                when (event) {
                                    is ChatStreamEvent.ToolCallDelta -> accumulator.apply(event)
                                    is ChatStreamEvent.Completed -> {
                                        if (!toolsFlushed && !accumulator.isEmpty) {
                                            toolsFlushed = true
                                            trySend(ChatStreamEvent.ToolCallsReady(accumulator.build()))
                                        }
                                        if (!terminated) {
                                            terminated = true
                                            trySend(event)
                                        }
                                    }
                                    else -> trySend(event)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            trySend(
                                ChatStreamEvent.Error(
                                    kind = ChatErrorKind.UNKNOWN,
                                    host = host,
                                    port = port,
                                    detail = e.message ?: e.toString(),
                                ),
                            )
                        }
                    } finally {
                        // 服务端未给 finish_reason / [DONE] 就断开时也要收尾
                        if (!terminated && !call.isCanceled()) {
                            if (!accumulator.isEmpty) {
                                toolsFlushed = true
                                trySend(ChatStreamEvent.ToolCallsReady(accumulator.build()))
                            }
                            trySend(ChatStreamEvent.Completed(null))
                        }
                        close()
                    }
                }
            }
        })

        awaitClose {
            // 停止按钮取消 collect → 中止底层 HTTP 调用（工具循环也随之停止）
            call.cancel()
        }
    }

    /** `{baseUrl}/chat/completions`，容忍用户已经填到 `/chat/completions`。 */
    private fun buildEndpoint(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    private fun normalizeRole(role: String): String = when (role.trim().lowercase()) {
        "system", "user", "assistant", "tool" -> role.trim().lowercase()
        else -> "user"
    }

    private fun OpenAiChatMessage.toWireJson(): JsonObject = when (val normRole = normalizeRole(role)) {
        "assistant" -> if (toolCalls.isEmpty()) {
            buildJsonObject {
                put("role", "assistant")
                put("content", content)
            }
        } else {
            buildJsonObject {
                put("role", "assistant")
                // 官方协议允许 content=null；但模型本轮说过的话要保留，否则上下文会丢
                if (content.isBlank()) put("content", JsonNull) else put("content", content)
                put("tool_calls", buildJsonArray {
                    toolCalls.forEach { tc ->
                        add(
                            buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            },
                        )
                    }
                })
            }
        }

        "tool" -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", toolCallId.orEmpty())
            put("content", content)
        }

        "user" -> buildJsonObject {
            put("role", "user")
            if (imageUrls.isEmpty()) {
                put("content", content)
            } else {
                put(
                    "content",
                    buildJsonArray {
                        if (content.isNotBlank()) {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", content)
                                },
                            )
                        }
                        imageUrls.forEach { image ->
                            val url = image.trim()
                            val formatted = when {
                                url.startsWith("data:") || url.startsWith("http://") || url.startsWith("https://") -> url
                                else -> "data:image/jpeg;base64,$url"
                            }
                            add(
                                buildJsonObject {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        buildJsonObject {
                                            put("url", formatted)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }

        else -> buildJsonObject {
            put("role", normRole)
            put("content", content)
        }
    }

    /** `tools[]`：`{"type":"function","function":{name, description, parameters}}`。 */
    private fun List<MaaToolSpec>.toWireJson(): JsonArray = buildJsonArray {
        this@toWireJson.forEach { spec ->
            add(
                buildJsonObject {
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", spec.name)
                            put("description", spec.description)
                            put(
                                "parameters",
                                buildJsonObject {
                                    put("type", "object")
                                    put("properties", spec.properties)
                                    put(
                                        "required",
                                        buildJsonArray {
                                            spec.required.forEach { add(JsonPrimitive(it)) }
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    /**
     * 解析单条 `data:` 负载（已去掉前缀）。
     *
     * - `[DONE]` → [ChatStreamEvent.Completed]（终止）
     * - `choices[0].delta.reasoning` / `delta.reasoning_content` → [ChatStreamEvent.ThinkingDelta]
     * - `choices[0].delta.content` 非空 → [ChatStreamEvent.TextDelta]
     * - `choices[0].delta.tool_calls[]` → [ChatStreamEvent.ToolCallDelta]
     * - `choices[0].finish_reason` 非空 → [ChatStreamEvent.Completed]
     * - 空 delta / 心跳 / 非 JSON → 空列表
     */
    internal fun parseSseData(
        dataPayload: String,
        fallbackModel: String = "",
        baseUrl: String = "",
        streamRequestId: String = "",
    ): List<ChatStreamEvent> {
        if (dataPayload.isBlank()) return emptyList()
        if (dataPayload == "[DONE]") return listOf(ChatStreamEvent.Completed(null))

        val obj = runCatching { json.parseToJsonElement(dataPayload) as? JsonObject }.getOrNull()
            ?: return emptyList()

        (obj["error"] as? JsonObject)?.let { error ->
            val message = (error["message"] as? JsonPrimitive)?.contentOrNull
            return listOf(
                ChatStreamEvent.Error(
                    kind = ChatErrorKind.PROVIDER,
                    detail = message?.takeIf { it.isNotBlank() } ?: "Provider error",
                ),
            )
        }

        val events = ArrayList<ChatStreamEvent>(4)

        val usageObj = obj["usage"] as? JsonObject
        if (usageObj != null) {
            val providerId = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val reqId = streamRequestId.takeIf { it.isNotBlank() } ?: providerId ?: UUID.randomUUID().toString()
            val respModel = (obj["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: fallbackModel

            val inputTokens = (usageObj["prompt_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: (usageObj["input_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            val outputTokens = (usageObj["completion_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: (usageObj["output_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

            val details = usageObj["prompt_tokens_details"] as? JsonObject
            val cachedTokens = (details?.get("cached_tokens") as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: (usageObj["cached_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                ?: (usageObj["cached_input_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

            val rawCost = (usageObj["cost"] as? JsonPrimitive)?.contentOrNull
            val costStr = rawCost?.let { raw ->
                runCatching { BigDecimal(raw).toPlainString() }.getOrDefault(raw)
            }

            if (inputTokens != null || outputTokens != null || cachedTokens != null || costStr != null) {
                events += ChatStreamEvent.Usage(
                    AiRequestUsage(
                        requestId = reqId,
                        source = "LLM",
                        model = respModel,
                        baseUrl = baseUrl,
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        cachedInputTokens = cachedTokens,
                        reportedCostUsd = costStr,
                        providerRequestId = providerId,
                    ),
                )
            }
        }

        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return events
        val delta = choice["delta"] as? JsonObject
        if (delta != null) {
            // 推理模型的思维链：OpenRouter 用 reasoning，DeepSeek 用 reasoning_content
            val reasoning = (delta["reasoning"] as? JsonPrimitive)?.contentOrNull
            val reasoningContent = (delta["reasoning_content"] as? JsonPrimitive)?.contentOrNull
            val thinking = when {
                !reasoning.isNullOrEmpty() -> reasoning
                !reasoningContent.isNullOrEmpty() -> reasoningContent
                else -> null
            }
            if (thinking != null) {
                events += ChatStreamEvent.ThinkingDelta(thinking)
            }

            val content = (delta["content"] as? JsonPrimitive)?.contentOrNull
            if (!content.isNullOrEmpty()) {
                events += ChatStreamEvent.TextDelta(content)
            }

            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { fallbackIndex, element ->
                val toolCall = element as? JsonObject ?: return@forEachIndexed
                val index = (toolCall["index"] as? JsonPrimitive)?.intOrNull ?: fallbackIndex
                val function = toolCall["function"] as? JsonObject
                val id = (toolCall["id"] as? JsonPrimitive)?.contentOrNull
                val name = (function?.get("name") as? JsonPrimitive)?.contentOrNull
                val argumentsDelta = (function?.get("arguments") as? JsonPrimitive)?.contentOrNull
                if (id != null || name != null || argumentsDelta != null) {
                    events += ChatStreamEvent.ToolCallDelta(
                        index = index,
                        id = id,
                        name = name,
                        argumentsDelta = argumentsDelta,
                    )
                }
            }
        }

        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
        if (!finishReason.isNullOrBlank()) {
            events += ChatStreamEvent.Completed(finishReason)
        }

        return events
    }
}
