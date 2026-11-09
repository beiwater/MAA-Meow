package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.usage.AiMessageUsage
import com.aliothmoon.maameow.ai.usage.AiRequestUsage
import java.math.BigDecimal
import java.util.UUID
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

@Serializable
data class AiToolCallCard(
    val id: String,
    val toolName: String,
    val toolInput: String,
    val observation: String = "",
    val isFinished: Boolean = false,
    val tier: String? = null,
    val screenshotBase64: String? = null,
    val detailLogs: String? = null,
)

@Serializable
data class AiChatMessage(
    val id: String,
    val isUser: Boolean,
    val content: String,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val toolCalls: List<AiToolCallCard> = emptyList(),
    /** 推理模型的思维链；只在气泡上方以可折叠标题展示，绝不混进正文 */
    val thinking: String = "",
    /** 原始错误文本（异常/HTTP 体），UI 以小字附在友好提示下方 */
    val errorDetail: String? = null,
    /** 该错误可通过「去设置」跳转 AI 控制页补配置 */
    val errorNeedsSetup: Boolean = false,
    /** 本轮用量与费用统计；旧历史为空表示未知 */
    val usage: AiMessageUsage? = null,
)

sealed interface DifyStreamEvent {
    data class TaskInfo(val taskId: String, val conversationId: String) : DifyStreamEvent
    data class TextDelta(val delta: String) : DifyStreamEvent
    data class ToolCallStarted(val id: String, val toolName: String, val toolInput: String) : DifyStreamEvent
    data class ToolCallFinished(val id: String, val toolName: String, val toolInput: String, val observation: String) : DifyStreamEvent
    data class Completed(val messageId: String, val conversationId: String, val usage: AiRequestUsage? = null) : DifyStreamEvent
    data class Usage(val usage: AiRequestUsage) : DifyStreamEvent
    data class Error(
        val message: String,
        val kind: ChatErrorKind = ChatErrorKind.UNKNOWN,
        val host: String = "",
        val port: Int = -1,
        val httpCode: Int = 0,
    ) : DifyStreamEvent
}

/**
 * Thin HTTP SSE client for Dify `/v1/chat-messages` and `/v1/chat-messages/{task_id}/stop`
 * reusing the app's existing [OkHttpClient]. No business prompts hardcoded in APK.
 */
class DifyChatClient(
    private val httpClient: OkHttpClient,
) {
    private val json = JsonUtils.common
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun streamChat(
        baseUrl: String,
        apiKey: String,
        user: String,
        query: String,
        conversationId: String = "",
        inputs: Map<String, String> = emptyMap(),
    ): Flow<DifyStreamEvent> = callbackFlow {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val endpoint = if (cleanBase.endsWith("/chat-messages")) {
            cleanBase
        } else {
            "$cleanBase/chat-messages"
        }

        val payload = buildJsonObject {
            put("query", query)
            put("response_mode", "streaming")
            put("user", user.ifBlank { "maameow-user" })
            if (conversationId.isNotBlank()) {
                put("conversation_id", conversationId)
            }
            put("inputs", buildJsonObject {
                inputs.forEach { (k, v) -> put(k, v) }
            })
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Accept", "text/event-stream")
            .post(json.encodeToString(payload).toRequestBody(jsonMediaType))
            .build()

        val call = httpClient.newCall(request)
        val host = request.url.host
        val port = request.url.port
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    trySend(
                        DifyStreamEvent.Error(
                            message = e.message ?: "Network error",
                            kind = ChatErrorKind.NETWORK,
                            host = host,
                            port = port,
                        ),
                    )
                }
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                        trySend(
                            DifyStreamEvent.Error(
                                message = "HTTP ${resp.code}: ${errBody.take(500)}",
                                kind = classifyHttpStatus(resp.code),
                                host = host,
                                port = port,
                                httpCode = resp.code,
                            ),
                        )
                        close()
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(
                            DifyStreamEvent.Error(
                                message = "Empty response stream",
                                kind = ChatErrorKind.UNKNOWN,
                                host = host,
                                port = port,
                            ),
                        )
                        close()
                        return
                    }
                    try {
                        while (!source.exhausted() && !call.isCanceled()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val dataPayload = line.removePrefix("data:").trim()
                            if (dataPayload.isEmpty() || dataPayload == "[DONE]") continue
                            parseSseData(dataPayload)?.let { event ->
                                trySend(event)
                            }
                        }
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            trySend(
                                DifyStreamEvent.Error(
                                    message = e.message ?: "Stream interrupted",
                                    kind = ChatErrorKind.UNKNOWN,
                                    host = host,
                                    port = port,
                                ),
                            )
                        }
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose {
            call.cancel()
        }
    }

    suspend fun stopGeneration(
        baseUrl: String,
        apiKey: String,
        user: String,
        taskId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (taskId.isBlank()) return@withContext false
        val cleanBase = baseUrl.trim().trimEnd('/')
        val stopUrl = "$cleanBase/chat-messages/$taskId/stop"
        val body = buildJsonObject {
            put("user", user.ifBlank { "maameow-user" })
        }
        val req = Request.Builder()
            .url(stopUrl)
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .post(json.encodeToString(body).toRequestBody(jsonMediaType))
            .build()
        runCatching {
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    internal fun parseSseData(dataPayload: String): DifyStreamEvent? {
        val obj = runCatching { json.parseToJsonElement(dataPayload) as? JsonObject }.getOrNull()
            ?: return null
        val eventType = (obj["event"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val taskId = (obj["task_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val convId = (obj["conversation_id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val messageId = (obj["message_id"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        return when (eventType) {
            "message", "agent_message" -> {
                val answer = (obj["answer"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (taskId.isNotEmpty() && answer.isEmpty()) {
                    DifyStreamEvent.TaskInfo(taskId, convId)
                } else {
                    DifyStreamEvent.TextDelta(answer)
                }
            }
            "agent_thought" -> {
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: messageId
                val tool = (obj["tool"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val toolInput = (obj["tool_input"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val observation = (obj["observation"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (tool.isBlank()) {
                    null
                } else if (observation.isNotBlank()) {
                    DifyStreamEvent.ToolCallFinished(id, tool, toolInput, observation)
                } else {
                    DifyStreamEvent.ToolCallStarted(id, tool, toolInput)
                }
            }
            "message_end" -> {
                val metadata = obj["metadata"] as? JsonObject
                val usageObj = metadata?.get("usage") as? JsonObject
                val difyUsage = if (usageObj != null) {
                    val promptTokens = (usageObj["prompt_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                    val completionTokens = (usageObj["completion_tokens"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
                    val totalPrice = (usageObj["total_price"] as? JsonPrimitive)?.contentOrNull?.let { raw ->
                        runCatching { BigDecimal(raw).toPlainString() }.getOrDefault(raw)
                    }
                    AiRequestUsage(
                        requestId = messageId.ifBlank { taskId }.ifBlank { UUID.randomUUID().toString() },
                        source = "LLM",
                        model = (metadata["model"] as? JsonPrimitive)?.contentOrNull ?: "dify",
                        baseUrl = "",
                        inputTokens = promptTokens,
                        outputTokens = completionTokens,
                        reportedCostUsd = totalPrice,
                    )
                } else null
                DifyStreamEvent.Completed(messageId, convId, difyUsage)
            }
            "error" -> {
                val msg = (obj["message"] as? JsonPrimitive)?.contentOrNull ?: "Unknown Dify error"
                DifyStreamEvent.Error(msg, kind = ChatErrorKind.PROVIDER)
            }
            else -> {
                // OpenAI compatible fallback delta (`choices[0].delta.content`)
                val choices = obj["choices"] as? kotlinx.serialization.json.JsonArray
                val firstChoice = choices?.firstOrNull() as? JsonObject
                val delta = firstChoice?.get("delta") as? JsonObject
                val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull
                if (!content.isNullOrEmpty()) DifyStreamEvent.TextDelta(content) else null
            }
        }
    }
}
