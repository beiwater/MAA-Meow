package com.aliothmoon.maameow.ai.jev

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 归一化矩形框：[top, left, bottom, right]，取值范围通常在 [0.0, 1.0]。
 */
@Serializable
data class JevBox(
    val top: Float,
    val left: Float,
    val bottom: Float,
    val right: Float,
) {
    val ymin: Float get() = top
    val xmin: Float get() = left
    val ymax: Float get() = bottom
    val xmax: Float get() = right

    /**
     * 将归一化矩形框转换为目标屏幕分辨率下的中心点物理坐标。
     */
    fun toScreenCoordinates(displayWidth: Int = 1280, displayHeight: Int = 720): Pair<Int, Int> {
        val centerX = ((left + right) / 2f * displayWidth).toInt()
        val centerY = ((top + bottom) / 2f * displayHeight).toInt()
        return Pair(centerX, centerY)
    }
}

/**
 * Jev Choice 问答预测结果。
 */
@Serializable
data class JevChoiceAnswer(
    val selected: String,
    val confidence: Float,
    val probabilities: Map<String, Float> = emptyMap(),
)

/**
 * Jev Bounding Box 问答预测结果。
 */
@Serializable
data class JevBoxAnswer(
    val box: JevBox,
    val confidence: Float,
)

/**
 * Jev Noul (yes/no 概率) 预测结果。
 */
@Serializable
data class JevNoulAnswer(
    val noul: Float,
)

/**
 * Jev /v1/systemone 统一决策解析结果。
 */
@Serializable
data class JevDecisionResult(
    val toolChoice: JevChoiceAnswer? = null,
    val popupBox: JevBoxAnswer? = null,
    val noulAnswers: Map<String, Float> = emptyMap(),
    val latencyMs: Long = 0L,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: String? = null,
    val id: String? = null,
    val model: String? = null,
    val rawTokens: Int? = null,
)
/**
 * Jev API 序列化辅助对象。
 */
object JevJsonHelper {

    /**
     * 解析 `/v1/systemone` 接口返回的响应内容。
     * 响应通常形如：
     * {
     *   "answers": {
     *     "tool_choice": {
     *       "choice": "maa_run_profile",
     *       "confidence": 0.95,
     *       "probabilities": { "maa_run_profile": 0.95, "maa_stop": 0.05 }
     *     },
     *     "popup_box": {
     *       "box": { "top": 0.1, "left": 0.2, "bottom": 0.3, "right": 0.4 },
     *       "confidence": 0.88
     *     }
     *   },
     *   "usage": { "total_tokens": 42 }
     * }
     */
    fun parseDecisionResult(root: JsonObject, latencyMs: Long): JevDecisionResult {
        val answers = root["answers"]?.jsonObject

        val toolChoice = answers?.get("tool_choice")?.jsonObject?.let { obj ->
            val selected = obj["choice"]?.jsonPrimitive?.content ?: ""
            val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull?.takeIf { it in 0f..1f } ?: 0f
            val probs = mutableMapOf<String, Float>()
            obj["probabilities"]?.jsonObject?.forEach { (key, elem) ->
                elem.jsonPrimitive.floatOrNull?.let { probs[key] = it }
            }
            if (selected.isNotBlank()) {
                JevChoiceAnswer(selected = selected, confidence = confidence, probabilities = probs)
            } else null
        }

        val popupBox = answers?.get("popup_box")?.jsonObject?.let { obj ->
            val confidence = obj["confidence"]?.jsonPrimitive?.floatOrNull?.takeIf { it in 0f..1f } ?: 0f
            val boxObj = obj["box"]?.jsonObject
            if (boxObj != null) {
                val top = boxObj["top"]?.jsonPrimitive?.floatOrNull ?: return@let null
                val left = boxObj["left"]?.jsonPrimitive?.floatOrNull ?: return@let null
                val bottom = boxObj["bottom"]?.jsonPrimitive?.floatOrNull ?: return@let null
                val right = boxObj["right"]?.jsonPrimitive?.floatOrNull ?: return@let null
                JevBoxAnswer(
                    box = JevBox(top = top, left = left, bottom = bottom, right = right),
                    confidence = confidence,
                )
            } else null
        }
        val noulAnswers = mutableMapOf<String, Float>()
        answers?.forEach { (key, elem) ->
            val obj = elem.jsonObject
            val noulVal = obj["noul"]?.jsonPrimitive?.floatOrNull?.takeIf { it in 0f..1f }
            if (noulVal != null) {
                noulAnswers[key] = noulVal
            }
        }

        val usage = root["usage"]?.jsonObject
        val inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.longOrNull
        val outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.longOrNull
        val costUsd = usage?.get("cost")?.jsonPrimitive?.content?.toBigDecimalOrNull()
            ?.takeIf { it.signum() >= 0 }?.toPlainString()
        val id = root["id"]?.jsonPrimitive?.content
        val model = root["model"]?.jsonPrimitive?.content

        val tokens: Int? = when {
            inputTokens != null || outputTokens != null -> ((inputTokens ?: 0L) + (outputTokens ?: 0L)).toInt()
            usage?.containsKey("total_tokens") == true -> usage["total_tokens"]?.jsonPrimitive?.intOrNull
            else -> null
        }

        return JevDecisionResult(
            toolChoice = toolChoice,
            popupBox = popupBox,
            noulAnswers = noulAnswers,
            latencyMs = latencyMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = costUsd,
            id = id,
            model = model,
            rawTokens = tokens,
        )
}
}
