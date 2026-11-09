package com.aliothmoon.maameow.ai.plan

import com.aliothmoon.maameow.ai.jev.JevBox
import com.aliothmoon.maameow.ai.jev.JevSystemOneClient
import com.aliothmoon.maameow.domain.service.FrameSnapshotter
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.Base64

@Serializable
data class UiRegion(
    val top: Float,
    val left: Float,
    val bottom: Float,
    val right: Float,
) {
    /**
     * Pad a focus crop to at least 280² pixels (the API requires sides >=256 and area >=78,400).
     */
    fun toSafeCropBounds(screenWidth: Int = 1280, screenHeight: Int = 720): CropBounds {
        val clampLeft = left.coerceIn(0f, 1f)
        val clampTop = top.coerceIn(0f, 1f)
        val clampRight = right.coerceIn(clampLeft, 1f)
        val clampBottom = bottom.coerceIn(clampTop, 1f)

        var x = (clampLeft * screenWidth).toInt().coerceIn(0, screenWidth - 1)
        var y = (clampTop * screenHeight).toInt().coerceIn(0, screenHeight - 1)
        var w = ((clampRight - clampLeft) * screenWidth).toInt().coerceAtLeast(1)
        var h = ((clampBottom - clampTop) * screenHeight).toInt().coerceAtLeast(1)

        val minDimension = 280
        if (w < minDimension) {
            val expand = minDimension - w
            x = (x - expand / 2).coerceIn(0, (screenWidth - minDimension).coerceAtLeast(0))
            w = minDimension.coerceAtMost(screenWidth)
        }
        if (h < minDimension) {
            val expand = minDimension - h
            y = (y - expand / 2).coerceIn(0, (screenHeight - minDimension).coerceAtLeast(0))
            h = minDimension.coerceAtMost(screenHeight)
        }

        return CropBounds(x, y, w, h)
    }
}

data class CropBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun toGlobalCoordinates(box: JevBox): Pair<Int, Int> {
        val localCenterX = (box.left + box.right) / 2f
        val localCenterY = (box.top + box.bottom) / 2f
        val globalX = (x + localCenterX * width).toInt().coerceIn(0, 1279)
        val globalY = (y + localCenterY * height).toInt().coerceIn(0, 719)
        return Pair(globalX, globalY)
    }
}

data class UiStep(
    val action: String,
    val target: String = "",
    val region: UiRegion? = null,
    val direction: String? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMs: Int = 300,
    val repeat: Int = 1,
    val waitAfterMs: Int = 300,
    val checkpoint: Boolean = false,
    val postCondition: String? = null,
)

data class UiPlan(
    val goal: String,
    val steps: List<UiStep>,
)

data class JevSettings(
    val baseUrl: String,
    val apiKey: String,
    val model: String = "jev-1.14",
    val tauBox: Float = 0.60f,
    val tauPresence: Float = 0.70f,
    val tauCondition: Float = 0.70f,
)

object UiPlanValidator {
    const val MAX_STEPS = 15
    const val MAX_REPEAT_PER_STEP = 10
    const val MAX_WAIT_MS_PER_STEP = 10_000
    const val MAX_TOTAL_ACTIONS = 30
    const val MAX_TOTAL_WAIT_MS = 60_000

    private val FORBIDDEN_PAID_KEYWORDS = listOf(
        "充值", "购买源石", "买源石", "付费", "扣款", "碎石", "纯源石", "人民币", "支付",
        "purchase originite", "buy prime", "spend prime", "recharge", "real money", "payment"
    )

    private val FORBIDDEN_COMBAT_KEYWORDS = listOf(
        "战斗内操作", "下干员", "开启技能", "撤退干员", "部署干员", "战斗规划",
        "combat deploy", "retreat operator", "in-battle skill", "activate skill in combat"
    )

    fun validate(plan: UiPlan): Result<Unit> {
        if (plan.steps.isEmpty()) {
            return Result.failure(IllegalArgumentException("Plan steps cannot be empty"))
        }
        if (plan.steps.size > MAX_STEPS) {
            return Result.failure(IllegalArgumentException("Plan steps count (${plan.steps.size}) exceeds maximum allowed ($MAX_STEPS)"))
        }

        var totalActions = 0
        var totalWaitMs = 0L

        if (plan.goal.isBlank()) return Result.failure(IllegalArgumentException("Plan goal cannot be blank"))

        for ((index, step) in plan.steps.withIndex()) {
            if (step.repeat < 1 || step.repeat > MAX_REPEAT_PER_STEP) {
                return Result.failure(IllegalArgumentException("Step $index repeat (${step.repeat}) out of range (1..$MAX_REPEAT_PER_STEP)"))
            }
            if (step.waitAfterMs < 0 || step.waitAfterMs > MAX_WAIT_MS_PER_STEP) {
                return Result.failure(IllegalArgumentException("Step $index wait_after_ms (${step.waitAfterMs}) out of range (0..$MAX_WAIT_MS_PER_STEP)"))
            }
            totalActions += step.repeat
            totalWaitMs += step.waitAfterMs.toLong() * step.repeat
            if (step.action.lowercase() in setOf("swipe", "scroll")) {
                totalWaitMs += step.durationMs.toLong() * step.repeat
                if (step.durationMs !in 100..2000) {
                    return Result.failure(IllegalArgumentException("Step $index: duration_ms must be in 100..2000"))
                }
                val coords = listOf(step.startX, step.startY, step.endX, step.endY)
                if (coords.any { it != null } && (
                    coords.any { it == null } ||
                        step.startX !in 0..1279 || step.endX !in 0..1279 ||
                        step.startY !in 0..719 || step.endY !in 0..719
                    )) {
                    return Result.failure(IllegalArgumentException("Step $index: invalid swipe coordinates"))
                }
            }
            step.region?.let {
                if (!it.top.isFinite() || !it.left.isFinite() || !it.bottom.isFinite() || !it.right.isFinite() ||
                    it.top !in 0f..1f || it.left !in 0f..1f || it.bottom !in 0f..1f || it.right !in 0f..1f ||
                    it.bottom <= it.top || it.right <= it.left
                ) return Result.failure(IllegalArgumentException("Step $index: invalid region"))
            }
            if ((step.checkpoint || step.action.equals("verify", true)) && step.postCondition.isNullOrBlank()) {
                return Result.failure(IllegalArgumentException("Step $index: checkpoint/verify requires post_condition"))
            }
            if (step.action.lowercase() in setOf("wait", "verify") && step.repeat != 1) {
                return Result.failure(IllegalArgumentException("Step $index: repeat applies only to click or swipe"))
            }

            checkForbiddenText(step.target)?.let { return Result.failure(IllegalArgumentException("Step $index: $it")) }

            when (step.action.lowercase()) {
                "click" -> {
                    if (step.target.isBlank()) {
                        return Result.failure(IllegalArgumentException("Step $index: 'click' requires a non-blank target"))
                    }
                }
                "swipe", "scroll" -> {
                    val hasDirection = step.direction?.lowercase() in setOf("up", "down", "left", "right")
                    val hasCoords = step.startX != null && step.startY != null && step.endX != null && step.endY != null
                    if (!hasDirection && !hasCoords) {
                        return Result.failure(IllegalArgumentException("Step $index: 'swipe/scroll' requires valid 'direction' (up/down/left/right) or start/end coordinates"))
                    }
                }
                "wait", "verify" -> {
                    // Valid
                }
                else -> {
                    return Result.failure(IllegalArgumentException("Step $index: Unknown action '${step.action}'. Must be click, swipe, scroll, wait, or verify"))
                }
            }
        }

        if (totalActions > MAX_TOTAL_ACTIONS) {
            return Result.failure(IllegalArgumentException("Plan total actions ($totalActions) exceeds maximum ($MAX_TOTAL_ACTIONS)"))
        }
        if (totalWaitMs > MAX_TOTAL_WAIT_MS) {
            return Result.failure(IllegalArgumentException("Plan total wait time (${totalWaitMs}ms) exceeds maximum (${MAX_TOTAL_WAIT_MS}ms)"))
        }

        return Result.success(Unit)
    }

    private fun checkForbiddenText(text: String): String? {
        val lower = text.lowercase()
        for (kw in FORBIDDEN_PAID_KEYWORDS) {
            if (lower.contains(kw)) {
                return "Unauthorized paid operation detected ('$kw'). Real-money or prime spending is strictly prohibited."
            }
        }
        for (kw in FORBIDDEN_COMBAT_KEYWORDS) {
            if (lower.contains(kw)) {
                return "In-combat tactical planning detected ('$kw'). Combat execution must use standard MAA task profiles or PRTS copilot, not ui_plan."
            }
        }
        return null
    }
}

object DefaultImageCropper {
    fun crop(jpegBytes: ByteArray, x: Int, y: Int, w: Int, h: Int): ByteArray? {
        val decoder = android.graphics.BitmapRegionDecoder.newInstance(jpegBytes, 0, jpegBytes.size, false)
            ?: return null
        try {
            require(decoder.width == 1280 && decoder.height == 720) {
                "UI plans require a 1280x720 game frame"
            }
            require(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= decoder.width && y + h <= decoder.height)
            val bitmap = decoder.decodeRegion(
                android.graphics.Rect(x, y, x + w, y + h),
                android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                },
            ) ?: return null
            val scale = minOf(
                maxOf(1f, 560f / minOf(bitmap.width, bitmap.height)),
                2048f / maxOf(bitmap.width, bitmap.height),
            )
            val focusedBitmap = if (scale > 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true,
                )
            } else bitmap
            try {
                return ByteArrayOutputStream().use { out ->
                    check(focusedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out))
                    out.toByteArray()
                }
            } finally {
                if (focusedBitmap !== bitmap) focusedBitmap.recycle()
                bitmap.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }
}

class UiPlanExecutor(
    private val jevClient: JevSystemOneClient,
    private val frameSnapshotter: FrameSnapshotter,
    private val clickDispatcher: suspend (x: Int, y: Int) -> Result<Boolean>,
    private val swipeDispatcher: suspend (startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Int) -> Result<Boolean>,
    private val cancelGestureDispatcher: suspend () -> Unit = {},
    private val jevSettings: () -> JevSettings,
    private val imageCropper: (jpegBytes: ByteArray, x: Int, y: Int, w: Int, h: Int) -> ByteArray? = DefaultImageCropper::crop,
) {
    suspend fun execute(planJson: JsonObject): Result<JsonObject> = try {
        val plan = parsePlan(planJson)
        val validation = UiPlanValidator.validate(plan)
        if (validation.isFailure) {
            val err = validation.exceptionOrNull()
            return Result.success(buildJsonObject {
                put("success", false)
                put("status", "validation_failed")
                put("reason", err?.message ?: "Plan validation failed")
                put("completed_steps", buildJsonArray {})
                put("dispatched_count", 0)
                put("verified_count", 0)
            })
        }

        val settings = jevSettings()
        var pendingCondition: String? = null
        val completedSteps = mutableListOf<JsonObject>()
        val historySummary = mutableListOf<String>()
        var dispatchedCount = 0
        var verifiedCount = 0
        var lastActionVerified = false
        var lastScreenshotBase64: String? = null

        try {
            for ((stepIndex, step) in plan.steps.withIndex()) {
                currentCoroutineContext().ensureActive()

                val actionName = step.action.lowercase()

                // 处理累积的前序 checkpoint：若当前 step 有局部 ROI 裁切，必须先用全图验证前序条件
                if (pendingCondition != null && (step.region != null || actionName != "click")) {
                    val fullJpeg = frameSnapshotter.captureJpeg()
                    if (fullJpeg == null) {
                        return Result.success(buildJsonObject {
                            put("success", false)
                            put("status", "screenshot_failed")
                            put("failed_at_step", stepIndex)
                            put("reason", "Cannot capture screen frame for checkpoint verification")
                            put("completed_steps", JsonArray(completedSteps))
                            put("dispatched_count", dispatchedCount)
                            put("verified_count", verifiedCount)
                        })
                    }
                    val fullBase64 = encodeBase64(fullJpeg)
                    lastScreenshotBase64 = fullBase64

                    val checkResult = jevClient.evaluate(
                        baseUrl = settings.baseUrl,
                        apiKey = settings.apiKey,
                        model = settings.model,
                        stateText = "Goal: ${plan.goal}\nVerify checkpoint condition before proceeding with step ${stepIndex + 1}.",
                        stateImageBase64 = fullBase64,
                        noulQuestions = mapOf("condition" to pendingCondition),
                    ).getOrThrow()

                    val condNoul = checkResult.noulAnswers["condition"] ?: 0f
                    if (condNoul < settings.tauCondition) {
                        return Result.success(buildJsonObject {
                            put("success", false)
                            put("status", "checkpoint_failed")
                            put("failed_at_step", stepIndex)
                            put("reason", "Checkpoint condition '$pendingCondition' failed with confidence $condNoul (< ${settings.tauCondition})")
                            put("completed_steps", JsonArray(completedSteps))
                            put("dispatched_count", dispatchedCount)
                            put("verified_count", verifiedCount)
                            put("screenshot_base64", fullBase64)
                        })
                    }
                    verifiedCount++
                    lastActionVerified = true
                    pendingCondition = null
                }

                when (actionName) {
                    "click" -> {
                        val fullJpeg = frameSnapshotter.captureJpeg()
                        if (fullJpeg == null) {
                            return Result.success(buildJsonObject {
                                put("success", false)
                                put("status", "screenshot_failed")
                                put("failed_at_step", stepIndex)
                                put("reason", "Cannot capture screen frame for click step $stepIndex")
                                put("completed_steps", JsonArray(completedSteps))
                                put("dispatched_count", dispatchedCount)
                                put("verified_count", verifiedCount)
                            })
                        }
                        val fullBase64 = encodeBase64(fullJpeg)
                        lastScreenshotBase64 = fullBase64

                        val cropBounds = step.region?.toSafeCropBounds()
                        val (evalBase64, evalBounds) = if (cropBounds != null) {
                            val croppedJpeg = imageCropper(fullJpeg, cropBounds.x, cropBounds.y, cropBounds.width, cropBounds.height)
                            if (croppedJpeg != null) {
                                Pair(encodeBase64(croppedJpeg), cropBounds)
                            } else {
                                throw IllegalStateException("Cannot crop the requested game viewport")
                            }
                        } else {
                            Pair(fullBase64, null)
                        }

                        val noulMap = mutableMapOf(
                            "target_present" to "Is the target described as '${step.target}' clearly visible and interactable in `image`?"
                        )
                        // 若未裁图且有前序 checkpoint，合并到同一 Jev 请求中
                        val checkPendingInSameCall = pendingCondition != null && evalBounds == null
                        if (checkPendingInSameCall) {
                            noulMap["condition"] = pendingCondition!!
                        }

                        val stateContext = buildJsonObject {
                            put("goal", plan.goal)
                            put("current_step", "${stepIndex + 1}/${plan.steps.size}: click '${step.target}'")
                            if (evalBounds != null) {
                                put("viewport", "This image is a focused crop. All bounding-box edges must be relative to this image, not to the original screen.")
                            }
                            if (historySummary.isNotEmpty()) {
                                put("recent_history", JsonArray(historySummary.takeLast(4).map { JsonPrimitive(it) }))
                            }
                        }

                        val evalResult = jevClient.evaluate(
                            baseUrl = settings.baseUrl,
                            apiKey = settings.apiKey,
                            model = settings.model,
                            stateObject = stateContext,
                            stateImageBase64 = evalBase64,
                            boxInstruction = step.target,
                            noulQuestions = noulMap,
                        ).getOrThrow()

                        // 验证合并的前序条件
                        if (checkPendingInSameCall) {
                            val condNoul = evalResult.noulAnswers["condition"] ?: 0f
                            if (condNoul < settings.tauCondition) {
                                return Result.success(buildJsonObject {
                                    put("success", false)
                                    put("status", "checkpoint_failed")
                                    put("failed_at_step", stepIndex)
                                    put("reason", "Checkpoint condition '$pendingCondition' failed with confidence $condNoul (< ${settings.tauCondition})")
                                    put("completed_steps", JsonArray(completedSteps))
                                    put("dispatched_count", dispatchedCount)
                                    put("verified_count", verifiedCount)
                                    put("screenshot_base64", fullBase64)
                                })
                            }
                            verifiedCount++
                            lastActionVerified = true
                            pendingCondition = null
                        }

                        val targetPresence = evalResult.noulAnswers["target_present"] ?: 0f
                        val popupBox = evalResult.popupBox

                        val box = popupBox?.box
                        val validBox = box != null &&
                            listOf(box.top, box.left, box.bottom, box.right).all { it.isFinite() && it in 0f..1f } &&
                            box.bottom > box.top && box.right > box.left
                        if (!validBox || popupBox == null || popupBox.confidence < settings.tauBox ||
                            !targetPresence.isFinite() || targetPresence < settings.tauPresence
                        ) {
                            val conf = popupBox?.confidence ?: 0f
                            return Result.success(buildJsonObject {
                                put("success", false)
                                put("status", "target_not_found")
                                put("failed_at_step", stepIndex)
                                put("reason", "Target '${step.target}' confidence insufficient: box=$conf (threshold ${settings.tauBox}), presence=$targetPresence (threshold ${settings.tauPresence})")
                                put("completed_steps", JsonArray(completedSteps))
                                put("dispatched_count", dispatchedCount)
                                put("verified_count", verifiedCount)
                                put("screenshot_base64", fullBase64)
                            })
                        }

                        // 映射坐标回 1280x720 屏幕
                        val (clickX, clickY) = if (evalBounds != null) {
                            evalBounds.toGlobalCoordinates(popupBox.box)
                        } else {
                            popupBox.box.toScreenCoordinates(1280, 720)
                        }

                        // 执行点击
                        repeat(step.repeat) {
                            currentCoroutineContext().ensureActive()
                            check(clickDispatcher(clickX, clickY).getOrThrow()) { "Click dispatch rejected" }
                            dispatchedCount++
                            if (it < step.repeat - 1 && step.waitAfterMs > 0) {
                                delay(step.waitAfterMs.toLong())
                            }
                        }
                        lastActionVerified = false

                        if (step.waitAfterMs > 0) {
                            delay(step.waitAfterMs.toLong())
                        }

                        val stepRecord = buildJsonObject {
                            put("step_index", stepIndex)
                            put("action", "click")
                            put("target", step.target)
                            put("x", clickX)
                            put("y", clickY)
                            put("repeat", step.repeat)
                            put("confidence", popupBox.confidence)
                            put("presence", targetPresence)
                        }
                        completedSteps += stepRecord
                        historySummary += "Clicked '${step.target}' x${step.repeat}"

                        if (step.checkpoint || !step.postCondition.isNullOrBlank()) {
                            pendingCondition = step.postCondition
                        }
                    }

                    "swipe", "scroll" -> {
                        var swipeStep = step
                        if (step.target.isNotBlank() && step.startX == null && step.region == null) {
                            val frame = frameSnapshotter.captureJpeg()
                                ?: error("Cannot capture frame for swipe target '${step.target}'")
                            val frameBase64 = encodeBase64(frame)
                            lastScreenshotBase64 = frameBase64
                            val decision = jevClient.evaluate(
                                baseUrl = settings.baseUrl,
                                apiKey = settings.apiKey,
                                model = settings.model,
                                stateText = "Goal: ${plan.goal}\nLocate the scrollable area or slider: ${step.target}",
                                stateImageBase64 = frameBase64,
                                boxInstruction = step.target,
                                noulQuestions = mapOf(
                                    "target_present" to "Is the scrollable area or slider '${step.target}' visible and interactable in `image`?"
                                ),
                            ).getOrThrow()
                            val answer = decision.popupBox
                            val box = answer?.box
                            val presence = decision.noulAnswers["target_present"] ?: 0f
                            if (box == null || !answer.confidence.isFinite() || answer.confidence < settings.tauBox ||
                                !presence.isFinite() || presence < settings.tauPresence ||
                                listOf(box.top, box.left, box.bottom, box.right).any { !it.isFinite() || it !in 0f..1f } ||
                                box.bottom <= box.top || box.right <= box.left
                            ) {
                                return Result.success(buildJsonObject {
                                    put("success", false)
                                    put("status", "target_not_found")
                                    put("failed_at_step", stepIndex)
                                    put("reason", "Swipe target '${step.target}' cannot be localized confidently")
                                    put("completed_steps", JsonArray(completedSteps))
                                    put("dispatched_count", dispatchedCount)
                                    put("verified_count", verifiedCount)
                                    put("screenshot_base64", frameBase64)
                                })
                            }
                            swipeStep = step.copy(region = UiRegion(box.top, box.left, box.bottom, box.right))
                        }
                        val (startX, startY, endX, endY) = resolveSwipeCoords(swipeStep)
                        repeat(step.repeat) {
                            currentCoroutineContext().ensureActive()
                            check(swipeDispatcher(startX, startY, endX, endY, step.durationMs).getOrThrow()) {
                                "Swipe dispatch rejected"
                            }
                            dispatchedCount++
                            if (it < step.repeat - 1 && step.waitAfterMs > 0) {
                                delay(step.waitAfterMs.toLong())
                            }
                        }
                        lastActionVerified = false

                        if (step.waitAfterMs > 0) {
                            delay(step.waitAfterMs.toLong())
                        }

                        val stepRecord = buildJsonObject {
                            put("step_index", stepIndex)
                            put("action", actionName)
                            put("start_x", startX)
                            put("start_y", startY)
                            put("end_x", endX)
                            put("end_y", endY)
                            put("repeat", step.repeat)
                        }
                        completedSteps += stepRecord
                        historySummary += "Swiped ($startX, $startY)->($endX, $endY) x${step.repeat}"

                        if (step.checkpoint || !step.postCondition.isNullOrBlank()) {
                            pendingCondition = step.postCondition
                        }
                    }

                    "wait", "verify" -> {
                        if (step.waitAfterMs > 0) {
                            delay(step.waitAfterMs.toLong())
                        }

                        val cond = step.postCondition?.ifBlank { null }
                        if (cond != null) {
                            val fullJpeg = frameSnapshotter.captureJpeg()
                            if (fullJpeg == null) {
                                return Result.success(buildJsonObject {
                                    put("success", false)
                                    put("status", "screenshot_failed")
                                    put("failed_at_step", stepIndex)
                                    put("reason", "Cannot capture screen frame for verification")
                                    put("completed_steps", JsonArray(completedSteps))
                                    put("dispatched_count", dispatchedCount)
                                    put("verified_count", verifiedCount)
                                })
                            }
                            val fullBase64 = encodeBase64(fullJpeg)
                            lastScreenshotBase64 = fullBase64

                            val verifyResult = jevClient.evaluate(
                                baseUrl = settings.baseUrl,
                                apiKey = settings.apiKey,
                                model = settings.model,
                                stateText = "Goal: ${plan.goal}\nVerifying step condition: $cond",
                                stateImageBase64 = fullBase64,
                                noulQuestions = mapOf("condition" to cond),
                            ).getOrThrow()

                            val condNoul = verifyResult.noulAnswers["condition"] ?: 0f
                            if (condNoul < settings.tauCondition) {
                                return Result.success(buildJsonObject {
                                    put("success", false)
                                    put("status", "verification_failed")
                                    put("failed_at_step", stepIndex)
                                    put("reason", "Verification condition '$cond' failed with confidence $condNoul (< ${settings.tauCondition})")
                                    put("completed_steps", JsonArray(completedSteps))
                                    put("dispatched_count", dispatchedCount)
                                    put("verified_count", verifiedCount)
                                    put("screenshot_base64", fullBase64)
                                })
                            }
                            verifiedCount++
                            lastActionVerified = true
                        }

                        val stepRecord = buildJsonObject {
                            put("step_index", stepIndex)
                            put("action", actionName)
                            put("verified", cond != null)
                            if (cond != null) put("condition", cond)
                        }
                        completedSteps += stepRecord
                        historySummary += "Verified/Waited: ${cond ?: "wait ${step.waitAfterMs}ms"}"
                    }
                }
            }

            // 执行完成后检验末尾留存的 checkpoint
            if (pendingCondition != null) {
                val fullJpeg = frameSnapshotter.captureJpeg()
                if (fullJpeg == null) {
                    return Result.success(buildJsonObject {
                        put("success", false)
                        put("status", "screenshot_failed")
                        put("reason", "Cannot capture screen frame for final checkpoint")
                        put("completed_steps", JsonArray(completedSteps))
                        put("dispatched_count", dispatchedCount)
                        put("verified_count", verifiedCount)
                    })
                }
                val fullBase64 = encodeBase64(fullJpeg)
                lastScreenshotBase64 = fullBase64

                val finalResult = jevClient.evaluate(
                    baseUrl = settings.baseUrl,
                    apiKey = settings.apiKey,
                    model = settings.model,
                    stateText = "Goal: ${plan.goal}\nFinal checkpoint verification.",
                    stateImageBase64 = fullBase64,
                    noulQuestions = mapOf("condition" to pendingCondition),
                ).getOrThrow()

                val condNoul = finalResult.noulAnswers["condition"] ?: 0f
                if (condNoul < settings.tauCondition) {
                    return Result.success(buildJsonObject {
                        put("success", false)
                        put("status", "final_checkpoint_failed")
                        put("reason", "Final checkpoint '$pendingCondition' failed with confidence $condNoul (< ${settings.tauCondition})")
                        put("completed_steps", JsonArray(completedSteps))
                        put("dispatched_count", dispatchedCount)
                        put("verified_count", verifiedCount)
                        put("screenshot_base64", fullBase64)
                    })
                }
                verifiedCount++
                lastActionVerified = true
                pendingCondition = null
            }
            if (!lastActionVerified) {
                lastScreenshotBase64 = frameSnapshotter.captureJpeg()?.let(::encodeBase64)
            }

            Result.success(buildJsonObject {
                put("success", true)
                put("status", if (lastActionVerified) "verified" else "dispatched")
                put("total_steps", plan.steps.size)
                put("executed_steps", completedSteps.size)
                put("completed_steps", JsonArray(completedSteps))
                put("dispatched_count", dispatchedCount)
                put("verified_count", verifiedCount)
                put("summary", "Successfully executed ${completedSteps.size} steps ($dispatchedCount actions dispatched, $verifiedCount checkpoints verified).")
                if (lastScreenshotBase64 != null) {
                    put("screenshot_base64", lastScreenshotBase64)
                }
            })
        } finally {
            // 确保手势在中断或异常时始终释放
            runCatching { cancelGestureDispatcher() }
        }
    } catch (e: CancellationException) {
        runCatching { cancelGestureDispatcher() }
        throw e
    } catch (e: Exception) {
        Timber.w(e, "UiPlanExecutor failed")
        runCatching { cancelGestureDispatcher() }
        Result.failure(e)
    }

    private fun resolveSwipeCoords(step: UiStep): IntArray {
        if (step.startX != null && step.startY != null && step.endX != null && step.endY != null) {
            return intArrayOf(
                step.startX,
                step.startY,
                step.endX,
                step.endY,
            )
        }
        val area = step.region ?: UiRegion(0f, 0f, 1f, 1f)
        val left = (area.left * 1280).toInt()
        val top = (area.top * 720).toInt()
        val width = ((area.right - area.left) * 1280).toInt()
        val height = ((area.bottom - area.top) * 720).toInt()
        val centerX = left + width / 2
        val centerY = top + height / 2
        val lowX = left + width * 3 / 10
        val highX = left + width * 7 / 10
        val lowY = top + height * 3 / 10
        val highY = top + height * 7 / 10
        return when (step.direction?.lowercase()) {
            "up" -> intArrayOf(centerX, highY, centerX, lowY)
            "down" -> intArrayOf(centerX, lowY, centerX, highY)
            "left" -> intArrayOf(highX, centerY, lowX, centerY)
            "right" -> intArrayOf(lowX, centerY, highX, centerY)
            else -> error("Validated swipe has no direction or coordinates")
        }
    }

    private fun encodeBase64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun parsePlan(root: JsonObject): UiPlan {
        val goal = root["goal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val stepsArray = root["steps"]?.jsonArray
            ?: throw IllegalArgumentException("Missing required 'steps' array")

        val steps = stepsArray.map { element ->
            val obj = element.jsonObject
            val action = obj["action"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: throw IllegalArgumentException("Missing required 'action' in step")
            val target = obj["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val region = parseRegion(obj["region"])
            val direction = obj["direction"]?.jsonPrimitive?.contentOrNull?.trim()
            val startX = obj["start_x"]?.jsonPrimitive?.intOrNull
            val startY = obj["start_y"]?.jsonPrimitive?.intOrNull
            val endX = obj["end_x"]?.jsonPrimitive?.intOrNull
            val endY = obj["end_y"]?.jsonPrimitive?.intOrNull
            val durationMs = obj["duration_ms"]?.jsonPrimitive?.intOrNull ?: 300
            val repeat = obj["repeat"]?.jsonPrimitive?.intOrNull ?: 1
            val waitAfterMs = obj["wait_after_ms"]?.jsonPrimitive?.intOrNull ?: 300
            val checkpoint = obj["checkpoint"]?.jsonPrimitive?.booleanOrNull ?: false
            val postCondition = obj["post_condition"]?.jsonPrimitive?.contentOrNull?.trim()

            UiStep(
                action = action,
                target = target,
                region = region,
                direction = direction,
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = durationMs,
                repeat = repeat,
                waitAfterMs = waitAfterMs,
                checkpoint = checkpoint,
                postCondition = postCondition,
            )
        }

        return UiPlan(goal = goal, steps = steps)
    }

    private fun parseRegion(element: JsonElement?): UiRegion? {
        if (element == null) return null
        val obj = element as? JsonObject ?: error("region must be an object")
        fun edge(name: String): Float = obj[name]?.jsonPrimitive?.floatOrNull
            ?: error("region requires numeric $name")
        return UiRegion(top = edge("top"), left = edge("left"), bottom = edge("bottom"), right = edge("right"))
    }
}
