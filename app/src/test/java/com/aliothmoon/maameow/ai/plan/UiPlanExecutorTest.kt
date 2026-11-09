package com.aliothmoon.maameow.ai.plan

import com.aliothmoon.maameow.ai.jev.JevBox
import com.aliothmoon.maameow.ai.jev.JevBoxAnswer
import com.aliothmoon.maameow.ai.jev.JevDecisionResult
import com.aliothmoon.maameow.ai.jev.JevSystemOneClient
import com.aliothmoon.maameow.domain.service.FrameSnapshotter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class UiPlanExecutorTest {

    @Test
    fun `validator rejects steps exceeding maximum limit`() {
        val plan = UiPlan(
            goal = "Test exceeding steps",
            steps = List(16) {
                UiStep(action = "click", target = "Button $it")
            }
        )
        val res = UiPlanValidator.validate(plan)
        assertTrue(res.isFailure)
    }

    @Test
    fun `validator rejects repeat exceeding per-step limit`() {
        val plan = UiPlan(
            goal = "Test repeat limit",
            steps = listOf(
                UiStep(action = "click", target = "Button", repeat = 11)
            )
        )
        val res = UiPlanValidator.validate(plan)
        assertTrue(res.isFailure)
    }

    @Test
    fun `validator rejects unauthorized paid operations`() {
        val paidKeywords = listOf("充值648", "购买源石十连", "碎石回复体力", "purchase originite pack", "spend prime")
        for (kw in paidKeywords) {
            val plan = UiPlan(
                goal = "Reject paid target",
                steps = listOf(UiStep(action = "click", target = kw))
            )
            val res = UiPlanValidator.validate(plan)
            assertTrue("Expected failure for keyword '$kw'", res.isFailure)
        }
    }

    @Test
    fun `validator rejects in-combat tactical deployment operations`() {
        val combatKeywords = listOf("下干员到A1", "开启技能打BOSS", "撤退干员换人", "combat deploy silverash")
        for (kw in combatKeywords) {
            val plan = UiPlan(
                goal = "Combat action: $kw",
                steps = listOf(UiStep(action = "click", target = kw))
            )
            val res = UiPlanValidator.validate(plan)
            assertTrue("Expected failure for combat keyword '$kw'", res.isFailure)
        }
    }

    @Test
    fun `safe crop bounds satisfy TypeSafe image requirements and correctly maps coordinates`() {
        // ROI top=0.7, left=0.8, bottom=1.0, right=1.0 on 1280x720 screen
        // raw width = 256, raw height = 216 (both below min 280)
        val region = UiRegion(top = 0.7f, left = 0.8f, bottom = 1.0f, right = 1.0f)
        val cropBounds = region.toSafeCropBounds(1280, 720)

        // Must satisfy TypeSafe limits: sides >= 280, total area >= 78400
        assertTrue("Width must be >= 280", cropBounds.width >= 280)
        assertTrue("Height must be >= 280", cropBounds.height >= 280)
        assertTrue("Total pixels must be >= 78400", cropBounds.width * cropBounds.height >= 78_400)
        assertTrue("Must be inside screen width", cropBounds.x + cropBounds.width <= 1280)
        assertTrue("Must be inside screen height", cropBounds.y + cropBounds.height <= 720)

        // Box inside crop returned by Jev: top=0.34, bottom=0.60, left=0.16, right=1.00
        val jevBox = JevBox(top = 0.34f, left = 0.16f, bottom = 0.60f, right = 1.0f)
        val (mappedX, mappedY) = cropBounds.toGlobalCoordinates(jevBox)

        // Verify mapped coordinates land around expected screen position (around 1172, 606)
        assertTrue("Mapped X ($mappedX) should be near right edge", mappedX in 1100..1279)
        assertTrue("Mapped Y ($mappedY) should be in bottom half", mappedY in 550..719)
    }

    @Test
    fun `plan executes multi-step sequence without intermediate LLM round-trips`() = runBlocking {
        val jevClient = mockk<JevSystemOneClient>()
        val frameSnapshotter = mockk<FrameSnapshotter>()
        val clickedCoords = mutableListOf<Pair<Int, Int>>()
        val dummyJpeg = ByteArray(100) { 1 }

        coEvery { frameSnapshotter.captureJpeg() } returns dummyJpeg

        // Step 1: Intermediate EXP card click
        val step1Decision = JevDecisionResult(
            popupBox = JevBoxAnswer(box = JevBox(0.4f, 0.5f, 0.6f, 0.7f), confidence = 0.95f),
            noulAnswers = mapOf("target_present" to 0.98f),
            latencyMs = 50L,
            inputTokens = 1200L,
            outputTokens = 20L,
        )
        // Step 2: Confirm upgrade click with checkpoint condition checked
        val step2Decision = JevDecisionResult(
            popupBox = JevBoxAnswer(box = JevBox(0.8f, 0.8f, 0.9f, 0.95f), confidence = 0.92f),
            noulAnswers = mapOf("target_present" to 0.96f),
            latencyMs = 50L,
            inputTokens = 1300L,
            outputTokens = 20L,
        )
        // Final checkpoint verification
        val finalCheckpointDecision = JevDecisionResult(
            noulAnswers = mapOf("condition" to 0.95f),
            latencyMs = 40L,
            inputTokens = 1100L,
            outputTokens = 15L,
        )

        coEvery {
            jevClient.evaluate(
                baseUrl = any(),
                apiKey = any(),
                model = any(),
                stateText = any(),
                stateImageBase64 = any(),
                toolOptions = any(),
                boxInstruction = "Intermediate EXP Card",
                noulQuestions = any(),
                stateObject = any(),
            )
        } returns Result.success(step1Decision)

        coEvery {
            jevClient.evaluate(
                baseUrl = any(),
                apiKey = any(),
                model = any(),
                stateText = any(),
                stateImageBase64 = any(),
                toolOptions = any(),
                boxInstruction = "Confirm Button",
                noulQuestions = any(),
                stateObject = any(),
            )
        } returns Result.success(step2Decision)

        coEvery {
            jevClient.evaluate(
                baseUrl = any(),
                apiKey = any(),
                model = any(),
                stateText = any(),
                stateImageBase64 = any(),
                toolOptions = any(),
                boxInstruction = null,
                noulQuestions = match { it.containsKey("condition") },
                stateObject = any(),
            )
        } returns Result.success(finalCheckpointDecision)

        val executor = UiPlanExecutor(
            jevClient = jevClient,
            frameSnapshotter = frameSnapshotter,
            clickDispatcher = { x, y ->
                clickedCoords += (x to y)
                Result.success(true)
            },
            swipeDispatcher = { _, _, _, _, _ -> Result.success(true) },
            jevSettings = { JevSettings("http://127.0.0.1", "key", "jev-1.14") },
        )

        val planJson = buildJsonObject {
            put("goal", "Upgrade Kroos")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("action", "click")
                    put("target", "Intermediate EXP Card")
                    put("repeat", 1)
                    put("wait_after_ms", 10)
                })
                add(buildJsonObject {
                    put("action", "click")
                    put("target", "Confirm Button")
                    put("checkpoint", true)
                    put("post_condition", "Is Kroos now at Level 7 on details screen?")
                    put("repeat", 1)
                    put("wait_after_ms", 10)
                })
            })
        }

        val res = executor.execute(planJson)
        assertTrue(res.isSuccess)
        val out = res.getOrThrow()

        assertTrue(out["success"]?.jsonPrimitive?.content == "true")
        assertEquals("verified", out["status"]?.jsonPrimitive?.content)
        assertEquals(2, out["total_steps"]?.jsonPrimitive?.content?.toInt())
        assertEquals(2, clickedCoords.size)
        assertEquals(1, out["verified_count"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `checkpoint failure stops execution early and returns failure status with screenshot`() = runBlocking {
        val jevClient = mockk<JevSystemOneClient>()
        val frameSnapshotter = mockk<FrameSnapshotter>()
        val dummyJpeg = ByteArray(100) { 1 }

        coEvery { frameSnapshotter.captureJpeg() } returns dummyJpeg

        val step1Decision = JevDecisionResult(
            popupBox = JevBoxAnswer(box = JevBox(0.4f, 0.5f, 0.6f, 0.7f), confidence = 0.95f),
            noulAnswers = mapOf("target_present" to 0.98f),
            latencyMs = 50L,
        )
        // Final checkpoint fails with low confidence (0.05 < 0.70)
        val finalCheckpointDecision = JevDecisionResult(
            noulAnswers = mapOf("condition" to 0.05f),
            latencyMs = 40L,
        )

        coEvery {
            jevClient.evaluate(
                baseUrl = any(),
                apiKey = any(),
                model = any(),
                stateText = any(),
                stateImageBase64 = any(),
                toolOptions = any(),
                boxInstruction = "Level up button",
                noulQuestions = any(),
                stateObject = any(),
            )
        } returns Result.success(step1Decision)

        coEvery {
            jevClient.evaluate(
                baseUrl = any(),
                apiKey = any(),
                model = any(),
                stateText = any(),
                stateImageBase64 = any(),
                toolOptions = any(),
                boxInstruction = null,
                noulQuestions = match { it.containsKey("condition") },
                stateObject = any(),
            )
        } returns Result.success(finalCheckpointDecision)

        val executor = UiPlanExecutor(
            jevClient = jevClient,
            frameSnapshotter = frameSnapshotter,
            clickDispatcher = { _, _ -> Result.success(true) },
            swipeDispatcher = { _, _, _, _, _ -> Result.success(true) },
            jevSettings = { JevSettings("http://127.0.0.1", "key", "jev-1.14") },
        )

        val planJson = buildJsonObject {
            put("goal", "Upgrade Kroos")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("action", "click")
                    put("target", "Level up button")
                    put("checkpoint", true)
                    put("post_condition", "Is Kroos now at Level 7 on details screen?")
                    put("wait_after_ms", 10)
                })
            })
        }

        val res = executor.execute(planJson)
        assertTrue(res.isSuccess)
        val out = res.getOrThrow()

        assertFalse(out["success"]?.jsonPrimitive?.content == "true")
        assertEquals("final_checkpoint_failed", out["status"]?.jsonPrimitive?.content)
        assertNotNull(out["screenshot_base64"])
    }

    @Test
    fun `target not found stops execution early with target_not_found status`() = runBlocking {
        val jevClient = mockk<JevSystemOneClient>()
        val frameSnapshotter = mockk<FrameSnapshotter>()
        val dummyJpeg = ByteArray(100) { 1 }

        coEvery { frameSnapshotter.captureJpeg() } returns dummyJpeg

        val notFoundDecision = JevDecisionResult(
            popupBox = JevBoxAnswer(box = JevBox(0.1f, 0.1f, 0.2f, 0.2f), confidence = 0.20f),
            noulAnswers = mapOf("target_present" to 0.15f),
            latencyMs = 50L,
        )

        coEvery {
            jevClient.evaluate(
                baseUrl = any(),
                apiKey = any(),
                model = any(),
                stateText = any(),
                stateImageBase64 = any(),
                toolOptions = any(),
                boxInstruction = "Missing operator",
                noulQuestions = any(),
                stateObject = any(),
            )
        } returns Result.success(notFoundDecision)

        val executor = UiPlanExecutor(
            jevClient = jevClient,
            frameSnapshotter = frameSnapshotter,
            clickDispatcher = { _, _ -> Result.success(true) },
            swipeDispatcher = { _, _, _, _, _ -> Result.success(true) },
            jevSettings = { JevSettings("http://127.0.0.1", "key", "jev-1.14") },
        )

        val planJson = buildJsonObject {
            put("goal", "Find missing operator")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("action", "click")
                    put("target", "Missing operator")
                    put("wait_after_ms", 10)
                })
            })
        }

        val res = executor.execute(planJson)
        assertTrue(res.isSuccess)
        val out = res.getOrThrow()

        assertFalse(out["success"]?.jsonPrimitive?.content == "true")
        assertEquals("target_not_found", out["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `cancellation stops execution and always calls gesture cancel`() = runBlocking {
        val jevClient = mockk<JevSystemOneClient>()
        val frameSnapshotter = mockk<FrameSnapshotter>()
        val gestureCancelled = AtomicBoolean(false)
        val dummyJpeg = ByteArray(100) { 1 }

        coEvery { frameSnapshotter.captureJpeg() } returns dummyJpeg
        coEvery {
            jevClient.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            delay(1000)
            Result.success(JevDecisionResult(latencyMs = 100L))
        }

        val executor = UiPlanExecutor(
            jevClient = jevClient,
            frameSnapshotter = frameSnapshotter,
            clickDispatcher = { _, _ -> Result.success(true) },
            swipeDispatcher = { _, _, _, _, _ -> Result.success(true) },
            cancelGestureDispatcher = { gestureCancelled.set(true) },
            jevSettings = { JevSettings("http://127.0.0.1", "key", "jev-1.14") },
        )

        val planJson = buildJsonObject {
            put("goal", "Cancelled plan")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("action", "click")
                    put("target", "Button")
                })
            })
        }

        val job = async { executor.execute(planJson) }
        delay(50)
        job.cancel()

        try {
            job.await()
        } catch (_: CancellationException) {}

        assertTrue("Gesture cancel must be called upon cancellation", gestureCancelled.get())
    }
    @Test
    fun `out of bounds and incomplete swipe coordinates are rejected before dispatch`() {
        for (step in listOf(
            UiStep("swipe", startX = 1280, startY = 100, endX = 100, endY = 200),
            UiStep("swipe", direction = "up", startX = 100),
            UiStep("swipe", startX = 0, startY = 0, endX = 1279, endY = 720),
        )) {
            assertTrue(UiPlanValidator.validate(UiPlan("Scroll roster", listOf(step))).isFailure)
        }
        assertTrue(UiPlanValidator.validate(UiPlan("Scroll roster", listOf(
            UiStep("swipe", startX = 0, startY = 0, endX = 1279, endY = 719)
        ))).isSuccess)
    }
    @Test
    fun `named slider swipe uses its detected area instead of the screen center`() = runBlocking {
        val jev = mockk<JevSystemOneClient>()
        val gestures = mutableListOf<List<Int>>()
        coEvery { jev.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(JevDecisionResult(
                popupBox = JevBoxAnswer(JevBox(0.1f, 0.7f, 0.9f, 0.85f), 0.95f),
                noulAnswers = mapOf("target_present" to 0.98f),
            ))
        val executor = UiPlanExecutor(
            jevClient = jev,
            frameSnapshotter = FrameSnapshotter { byteArrayOf(1, 2, 3) },
            clickDispatcher = { _, _ -> error("A swipe must not dispatch a click") },
            swipeDispatcher = { sx, sy, ex, ey, _ ->
                gestures += listOf(sx, sy, ex, ey)
                Result.success(true)
            },
            jevSettings = { JevSettings("http://localhost", "") },
        )
        val result = executor.execute(buildJsonObject {
            put("goal", "Move the level selector")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("action", "swipe")
                    put("target", "right-hand level selector")
                    put("direction", "down")
                    put("wait_after_ms", 0)
                })
            })
        }).getOrThrow()
        val (sx, sy, ex, ey) = gestures.single()
        assertTrue(sx in 896..1088 && ex in 896..1088)
        assertTrue(sy in 72..648 && ey in 72..648 && sy < ey)
        assertEquals("dispatched", result["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `absent swipe target does not fall back to a guessed central gesture`() = runBlocking {
        val jev = mockk<JevSystemOneClient>()
        var dispatched = false
        coEvery { jev.evaluate(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(JevDecisionResult(
                popupBox = JevBoxAnswer(JevBox(0.1f, 0.7f, 0.9f, 0.85f), 0.95f),
                noulAnswers = mapOf("target_present" to 0.1f),
            ))
        val executor = UiPlanExecutor(
            jevClient = jev,
            frameSnapshotter = FrameSnapshotter { byteArrayOf(1, 2, 3) },
            clickDispatcher = { _, _ -> error("Unexpected click") },
            swipeDispatcher = { _, _, _, _, _ -> dispatched = true; Result.success(true) },
            jevSettings = { JevSettings("http://localhost", "") },
        )
        val result = executor.execute(buildJsonObject {
            put("goal", "Move the level selector")
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("action", "swipe")
                    put("target", "missing slider")
                    put("direction", "down")
                })
            })
        }).getOrThrow()
        assertFalse(dispatched)
        assertEquals("target_not_found", result["status"]!!.jsonPrimitive.content)
        assertEquals("0", result["dispatched_count"]!!.jsonPrimitive.content)
    }
}
