package com.aliothmoon.maameow.ai.jev

import com.aliothmoon.maameow.ai.usage.AiRequestUsage
import com.aliothmoon.maameow.ai.usage.recordAiUsage
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
/**
 * Jev System 1 客户端：调用 Jev 快速端点 `/systemone` 执行快速直觉决策。
 */
class JevSystemOneClient(
    private val httpClient: OkHttpClient,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun evaluate(
        baseUrl: String,
        apiKey: String,
        model: String = "jev-1.14",
        stateText: String = "",
        stateImageBase64: String? = null,
        toolOptions: List<String>? = null,
        boxInstruction: String? = null,
        noulQuestions: Map<String, String>? = null,
        stateObject: JsonObject? = null,
    ): Result<JevDecisionResult> {
        val url = "${baseUrl.trimEnd('/')}/systemone"
        val imageReference = stateImageBase64?.let { UUID.randomUUID().toString() }

        val requestBodyJson = buildJsonObject {
            put("model", model)
            put("state", buildJsonObject {
                if (stateObject != null) {
                    stateObject.forEach { (k, v) -> put(k, v) }
                } else {
                    put("text", stateText)
                }
                if (imageReference != null && (stateObject == null || !stateObject.containsKey("image"))) {
                    put("image", imageReference)
                }
            })
            if (imageReference != null) {
                put("assets", buildJsonObject {
                    put(imageReference, buildJsonObject {
                        put("type", "image")
                        put("format", "jpg")
                        put("data_base64", stateImageBase64)
                    })
                })
            }
            put("questions", buildJsonObject {
                if (toolOptions != null) {
                    put("tool_choice", buildJsonObject {
                        put("type", "choice")
                        put("criteria", buildJsonObject {
                            toolOptions.forEach { put(it, it) }
                            put("none", "No tool is appropriate or the request is ambiguous.")
                        })
                        put("instructions", "Select the single best tool to execute immediately, or none if not confident.")
                    })
                }
                if (boxInstruction != null) {
                    put("popup_box", buildJsonObject {
                        put("type", "box")
                        put("instructions", "The target described as '$boxInstruction' in the game screenshot `image`.")
                    })
                }
                noulQuestions?.forEach { (key, instr) ->
                    put(key, buildJsonObject {
                        put("type", "noul")
                        put("instructions", instr)
                    })
                }
            })
        }.toString()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBodyJson.toRequestBody(jsonMediaType))
            .addHeader("Content-Type", "application/json")

        if (apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        if (stateImageBase64 != null || boxInstruction != null) {
            requestBuilder.addHeader("TypeSafe-Danger-Zone-Images", "true")
        }

        val request = requestBuilder.build()
        val startNs = System.nanoTime()
        val requestId = UUID.randomUUID().toString()
        recordAiUsage(AiRequestUsage(requestId, "JEV", model, baseUrl))

        return try {
            val responseString = executeCall(request)
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000L
            val root = JsonUtils.common.parseToJsonElement(responseString).jsonObject
            val decision = JevJsonHelper.parseDecisionResult(root, latencyMs)
            recordAiUsage(
                AiRequestUsage(
                    requestId = requestId,
                    source = "JEV",
                    model = decision.model ?: model,
                    baseUrl = baseUrl,
                    inputTokens = decision.inputTokens,
                    outputTokens = decision.outputTokens,
                    cachedInputTokens = null,
                    reportedCostUsd = decision.costUsd,
                    providerRequestId = decision.id,
                )
            )
            Result.success(decision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Jev evaluation failed")
            Result.failure(e)
        }
    }

    private suspend fun executeCall(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = httpClient.newCall(request)
        cont.invokeOnCancellation {
            try {
                call.cancel()
            } catch (_: Throwable) {}
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) {
                    cont.resumeWith(kotlin.Result.failure(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = resp.body.string()
                        val err = IOException("HTTP ${resp.code}: $body")
                        if (cont.isActive) {
                            cont.resumeWith(kotlin.Result.failure(err))
                        }
                        return
                    }
                    val body = resp.body.string()
                    if (cont.isActive) {
                        cont.resume(body)
                    }
                }
            }
        })
    }
}
