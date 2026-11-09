package com.aliothmoon.maameow.ai.jev

import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

enum class JevMode {
    OFF,
    SHADOW,
    CASCADE,
}

sealed interface JevInterceptDecision {
    data object PassThrough : JevInterceptDecision

    data class DirectTool(
        val toolName: String,
        val argumentsJson: String,
        val confidence: Float,
        val latencyMs: Long,
        val tier: String,
    ) : JevInterceptDecision

    data class DirectClick(
        val x: Int,
        val y: Int,
        val confidence: Float,
        val latencyMs: Long,
        val tier: String,
    ) : JevInterceptDecision
}

class JevCascadeController(
    private val client: JevSystemOneClient,
    val mode: JevMode = JevMode.CASCADE,
    val tauToolSelect: Float = 0.90f,
    val tauBox: Float = 0.85f,
    val timeoutMs: Long = 600L,
    val defaultModel: String = "jev-1.14",
    val customBaseUrl: String? = null,
    val customApiKey: String? = null,
) {
    /**
     * 在 ChatToolLoop 中决定是否直接由 System 1 拦截。
     *
     * @param baseUrl Jev / OpenAI 兼容接口地址
     * @param apiKey 凭据
     * @param stateText 当前上下文（如最新用户问题或对话）
     * @param toolOptions 当前可调用的工具名称列表
     * @param stateImageBase64 可选屏幕截图（Base64）
     * @param boxInstruction 可选屏幕检测指令
     */
    suspend fun evaluateIntercept(
        baseUrl: String,
        apiKey: String,
        stateText: String,
        toolOptions: List<String>?,
        stateImageBase64: String? = null,
        boxInstruction: String? = null,
    ): JevInterceptDecision {
        if (mode == JevMode.OFF) {
            return JevInterceptDecision.PassThrough
        }

        val effectiveBaseUrl = customBaseUrl?.takeIf { it.isNotBlank() } ?: baseUrl
        val effectiveApiKey = customApiKey?.takeIf { it.isNotBlank() } ?: apiKey

        val evalResult = withTimeoutOrNull(timeoutMs) {
            client.evaluate(
                baseUrl = effectiveBaseUrl,
                apiKey = effectiveApiKey,
                model = defaultModel,
                stateText = stateText,
                stateImageBase64 = stateImageBase64,
                toolOptions = toolOptions,
                boxInstruction = boxInstruction,
            ).getOrNull()
        }

        if (evalResult == null) {
            Timber.d("Jev evaluation timed out or returned error; falling back to System 2")
            return JevInterceptDecision.PassThrough
        }

        val toolChoice = evalResult.toolChoice
        val popupBox = evalResult.popupBox

        if (mode == JevMode.SHADOW) {
            Timber.i(
                "[Jev-Shadow] evaluated in %d ms: tool=%s (conf=%.2f), box=%s (conf=%.2f)",
                evalResult.latencyMs,
                toolChoice?.selected,
                toolChoice?.confidence ?: 0f,
                popupBox?.box,
                popupBox?.confidence ?: 0f,
            )
            return JevInterceptDecision.PassThrough
        }

        // CASCADE mode
        if (toolChoice != null && toolChoice.confidence >= tauToolSelect) {
            if (JevSafetyPolicy.isSafeToExecute(toolChoice.selected)) {
                val tier = "⚡ System 1 (Jev 1.14, conf=${"%.2f".format(toolChoice.confidence)}, ${evalResult.latencyMs}ms)"
                return JevInterceptDecision.DirectTool(
                    toolName = toolChoice.selected,
                    argumentsJson = "{}",
                    confidence = toolChoice.confidence,
                    latencyMs = evalResult.latencyMs,
                    tier = tier,
                )
            } else {
                Timber.w("Tool %s proposed by Jev is blocked by safety policy; falling back to System 2", toolChoice.selected)
            }
        }

        if (popupBox != null && popupBox.confidence >= tauBox) {
            val (x, y) = popupBox.box.toScreenCoordinates()
            val tier = "⚡ System 1 (Jev 1.14 Box, conf=${"%.2f".format(popupBox.confidence)}, ${evalResult.latencyMs}ms)"
            return JevInterceptDecision.DirectClick(
                x = x,
                y = y,
                confidence = popupBox.confidence,
                latencyMs = evalResult.latencyMs,
                tier = tier,
            )
        }

        return JevInterceptDecision.PassThrough
    }

    /**
     * 用于在 SHADOW 模式或事后比对 System 1 与 System 2 选择的一致性
     */
    fun logShadowComparison(
        jevDecision: JevDecisionResult?,
        systemTwoTool: String?,
    ) {
        val jevTool = jevDecision?.toolChoice?.selected
        val agree = (jevTool != null && jevTool == systemTwoTool)
        Timber.i(
            "[Jev-Shadow-Compare] System1=%s (conf=%.2f) vs System2=%s, match=%b",
            jevTool,
            jevDecision?.toolChoice?.confidence ?: 0f,
            systemTwoTool,
            agree,
        )
    }
}
