package com.aliothmoon.maameow.ai.usage

import com.aliothmoon.maameow.ai.AiChatMessage
import com.aliothmoon.maameow.ai.ChatStreamEvent
import com.aliothmoon.maameow.ai.OpenAiCompatibleChatClient
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AiUsageConsumerRegressionTest {

    private val json = JsonUtils.common

    @Test
    fun `empty choices usage chunk is parsed into Usage event without requiring choices`() {
        val client = OpenAiCompatibleChatClient(OkHttpClient())
        // Standard OpenAI streaming usage chunk where choices is empty
        val payload = """
            {
                "id": "gen-test-123",
                "object": "chat.completion.chunk",
                "model": "meta/muse-spark-1.3-contributor",
                "choices": [],
                "usage": {
                    "prompt_tokens": 100,
                    "completion_tokens": 50,
                    "total_tokens": 150,
                    "prompt_tokens_details": {
                        "cached_tokens": 20
                    },
                    "cost": 0.0000123
                }
            }
        """.trimIndent()

        val events = client.parseSseData(payload, fallbackModel = "default-model", baseUrl = "https://openrouter.ai/api/v1")
        assertEquals(1, events.size)
        val usageEvent = events.single() as ChatStreamEvent.Usage
        val usage = usageEvent.usage

        assertEquals("gen-test-123", usage.requestId)
        assertEquals("meta/muse-spark-1.3-contributor", usage.model)
        assertEquals(100L, usage.inputTokens)
        assertEquals(50L, usage.outputTokens)
        assertEquals(20L, usage.cachedInputTokens)
        assertEquals("0.0000123", usage.reportedCostUsd)
    }

    @Test
    fun `chunk with both finish_reason and usage does not double count reasoning tokens`() {
        val client = OpenAiCompatibleChatClient(OkHttpClient())
        // Real OpenRouter fixture: choices has 1 element, completion_tokens_details has reasoning_tokens 30, completion_tokens is 32
        val payload = """
            {
                "id": "gen-1794136305-XPoKXI8srOI6kqdQayDq",
                "model": "meta/muse-spark-1.3-contributor",
                "choices": [{
                    "index": 0,
                    "delta": {"content": ""},
                    "finish_reason": "length"
                }],
                "usage": {
                    "prompt_tokens": 13,
                    "completion_tokens": 32,
                    "total_tokens": 45,
                    "cost": 7.623e-06,
                    "prompt_tokens_details": {"cached_tokens": 0},
                    "completion_tokens_details": {"reasoning_tokens": 30}
                }
            }
        """.trimIndent()

        val events = client.parseSseData(payload)
        val usageEvent = events.filterIsInstance<ChatStreamEvent.Usage>().single()
        val completedEvent = events.filterIsInstance<ChatStreamEvent.Completed>().single()

        assertEquals("length", completedEvent.finishReason)
        assertEquals(13L, usageEvent.usage.inputTokens)
        // completionTokens MUST be 32, reasoning_tokens (30) must NOT be added again to make 62
        assertEquals(32L, usageEvent.usage.outputTokens)
        assertEquals(0L, usageEvent.usage.cachedInputTokens)
        // Scientific notation 7.623e-06 correctly parsed to plain decimal string without exponential representation
        assertEquals("0.000007623", usageEvent.usage.reportedCostUsd)
    }

    @Test
    fun `stream multiple usage chunks with same requestId are deduplicated in aggregator`() {
        val priceService = OpenRouterPriceService(OkHttpClient())
        // Two usage reports in same stream (e.g. intermediate snapshot and final snapshot)
        val intermediate = AiRequestUsage(
            requestId = "req-1",
            source = "LLM",
            model = "test-model",
            baseUrl = "https://openrouter.ai/api/v1",
            inputTokens = 100L,
            outputTokens = 20L,
        )
        val finalUsage = AiRequestUsage(
            requestId = "req-1",
            source = "LLM",
            model = "test-model",
            baseUrl = "https://openrouter.ai/api/v1",
            inputTokens = 100L,
            outputTokens = 50L,
        )

        val stats = AiUsageCalculator.computeStats(
            requests = listOf(intermediate, finalUsage),
            isIncomplete = false,
            priceService = priceService,
        )

        // Only 1 distinct request
        assertEquals(1, stats.llm.requestCount)
        assertEquals(100L, stats.llm.inputTokens)
        // Latest outputTokens (50L), not 70L!
        assertEquals(50L, stats.llm.outputTokens)
    }

    @Test
    fun `cancelled or unknown usage does not fake zero cost or zero tokens`() {
        val priceService = OpenRouterPriceService(OkHttpClient())

        // 1. Incomplete cancelled usage
        val incompleteStats = AiUsageCalculator.computeStats(
            requests = emptyList(),
            isIncomplete = true,
            priceService = priceService,
        )
        assertTrue(incompleteStats.isIncomplete)
        assertFalse(incompleteStats.hasAnyUsage)
        assertNull(incompleteStats.totalCostUsd)
        assertNull(incompleteStats.totalTokens)
        assertNull(AiUsageCalculator.formatCostUsd(incompleteStats.totalCostUsd))

        // 2. Request with missing token metrics and unknown model
        val unknownModelReq = AiRequestUsage(
            requestId = "req-unknown",
            source = "LLM",
            model = "some-unknown-private-model",
            baseUrl = "http://localhost:11434/v1",
            inputTokens = null,
            outputTokens = null,
        )
        val unknownStats = AiUsageCalculator.computeStats(
            requests = listOf(unknownModelReq),
            isIncomplete = false,
            priceService = priceService,
        )
        assertNull(unknownStats.totalCostUsd)
        assertNull(unknownStats.totalTokens)
    }

    @Test
    fun `catalog caching handles free models zero cost and missing prices as unknown`() {
        val priceService = OpenRouterPriceService(OkHttpClient())
        priceService.injectPricing(
            ModelPricing(
                modelId = "meta/muse-spark-1.3-contributor",
                promptPrice = BigDecimal("0.0000001"),
                completionPrice = BigDecimal("0.0000002"),
                cachedInputPrice = BigDecimal("0.000000002"),
            )
        )

        priceService.injectPricing(ModelPricing(
            modelId = "google/gemini-2.0-flash-exp:free",
            promptPrice = BigDecimal.ZERO,
            completionPrice = BigDecimal.ZERO,
        ))
        // Free model
        val freeUsage = AiRequestUsage(
            requestId = "req-free",
            source = "LLM",
            model = "google/gemini-2.0-flash-exp:free",
            baseUrl = "https://openrouter.ai/api/v1",
            inputTokens = 1000L,
            outputTokens = 200L,
        )
        val freeEval = priceService.evaluateCost(freeUsage)
        assertNotNull(freeEval.costUsd)
        assertEquals(BigDecimal.ZERO, freeEval.costUsd)
        assertTrue(freeEval.isFree)
        assertEquals("$0.00", AiUsageCalculator.formatCostUsd(freeEval.costUsd))

        // Missing price model on OpenRouter
        val missingUsage = AiRequestUsage(
            requestId = "req-missing",
            source = "LLM",
            model = "unlisted/experimental-model",
            baseUrl = "https://openrouter.ai/api/v1",
            inputTokens = 1000L,
            outputTokens = 200L,
        )
        val missingEval = priceService.evaluateCost(missingUsage)
        assertNull(missingEval.costUsd)
        assertFalse(missingEval.isFree)
    }

    @Test
    fun `tinyUSD precision preserves small dollar amounts and cached discount`() {
        val priceService = OpenRouterPriceService(OkHttpClient())
        // Real typesafe/jev-1.14 pricing
        priceService.injectPricing(
            ModelPricing(
                modelId = "typesafe/jev-1.14",
                promptPrice = BigDecimal("0.000000042"),
                completionPrice = BigDecimal.ZERO,
                cachedInputPrice = null,
            )
        )

        // Real fixture: 741 input tokens, 38 output tokens -> 741 * 0.000000042 = 0.000031122
        val jevUsage = AiRequestUsage(
            requestId = "gen-dec-1794136247-H2CgyyQTluq1BUEGuWmP",
            source = "JEV",
            model = "typesafe/jev-1.14-20261014",
            baseUrl = "https://openrouter.ai/api/v1",
            inputTokens = 741L,
            outputTokens = 38L,
            reportedCostUsd = "0.000031122",
        )

        val eval = priceService.evaluateCost(jevUsage)
        assertNotNull(eval.costUsd)
        assertEquals(BigDecimal("0.000031122"), eval.costUsd)
        assertTrue(eval.isReported)
        assertEquals("reported", eval.source)

        // Verify formatCostUsd does NOT round 0.000031122 down to $0.00
        val formatted = AiUsageCalculator.formatCostUsd(eval.costUsd)
        assertEquals("$0.000031122", formatted)

        // Test cached input discount with muse-spark
        priceService.injectPricing(
            ModelPricing(
                modelId = "meta/muse-spark-1.3-contributor",
                promptPrice = BigDecimal("0.0000001"),
                completionPrice = BigDecimal("0.0000002"),
                cachedInputPrice = BigDecimal("0.000000002"),
            )
        )
        val cachedUsage = AiRequestUsage(
            requestId = "req-cached",
            source = "LLM",
            model = "meta/muse-spark-1.3-contributor",
            baseUrl = "https://openrouter.ai/api/v1",
            inputTokens = 1000L,
            cachedInputTokens = 200L,
            outputTokens = 100L,
            reportedCostUsd = null, // estimated
        )
        val cachedEval = priceService.evaluateCost(cachedUsage)
        // 800 * 0.0000001 = 0.0000800
        // 200 * 0.000000002 = 0.0000004
        // 100 * 0.0000002 = 0.0000200
        // Total = 0.0001004
        assertEquals(0, BigDecimal("0.0001004").compareTo(cachedEval.costUsd))
        assertFalse(cachedEval.isReported)
        assertEquals("openrouter_catalog", cachedEval.source)
    }

    @Test
    fun `multi-layer Jev and LLM attribution in AiUsageContext groups requests correctly`() = runBlocking {
        val captured = mutableListOf<AiRequestUsage>()

        withContext(AiUsageContext { usage -> captured += usage }) {
            // Nested Jev call (e.g. cascade intercept or visual click)
            recordAiUsage(
                AiRequestUsage(
                    requestId = "jev-1",
                    source = "JEV",
                    model = "typesafe/jev-1.14-20261014",
                    baseUrl = "https://openrouter.ai/api/v1",
                    inputTokens = 2716L,
                    outputTokens = 21L,
                    reportedCostUsd = "0.000114072",
                )
            )

            // LLM call
            recordAiUsage(
                AiRequestUsage(
                    requestId = "llm-1",
                    source = "LLM",
                    model = "meta/muse-spark-1.3-contributor",
                    baseUrl = "https://openrouter.ai/api/v1",
                    inputTokens = 500L,
                    outputTokens = 80L,
                    reportedCostUsd = "0.000066",
                )
            )
        }

        assertEquals(2, captured.size)
        val priceService = OpenRouterPriceService(OkHttpClient())
        val stats = AiUsageCalculator.computeStats(captured, false, priceService)

        // LLM Breakdown
        assertEquals(1, stats.llm.requestCount)
        assertEquals(500L, stats.llm.inputTokens)
        assertEquals(80L, stats.llm.outputTokens)
        assertEquals(BigDecimal("0.000066"), stats.llm.costUsd)

        // JEV Breakdown
        assertEquals(1, stats.jev.requestCount)
        assertEquals(2716L, stats.jev.inputTokens)
        assertEquals(21L, stats.jev.outputTokens)
        assertEquals(BigDecimal("0.000114072"), stats.jev.costUsd)

        // Combined Total
        assertEquals(3216L, stats.totalInputTokens)
        assertEquals(101L, stats.totalOutputTokens)
        assertEquals(BigDecimal("0.000180072"), stats.totalCostUsd)
        assertTrue(stats.isAllReported)
    }

    @Test
    fun `legacy history without usage field deserializes to null default and shows unknown`() {
        val legacyJson = """
            {
                "id": "msg-old",
                "isUser": false,
                "content": "这是旧版本生成的回答"
            }
        """.trimIndent()

        val msg = json.decodeFromString<AiChatMessage>(legacyJson)
        assertNull(msg.usage)

        val priceService = OpenRouterPriceService(OkHttpClient())
        // Stats for null usage
        val stats = AiUsageCalculator.computeStats(msg.usage?.requests ?: emptyList(), false, priceService)
        assertFalse(stats.hasAnyUsage)
        assertNull(stats.totalCostUsd)
        assertNull(stats.totalTokens)
    }
    @Test
    fun `published paid model with missing usage stays unknown and aliases are not guessed`() {
        val prices = OpenRouterPriceService(OkHttpClient())
        prices.injectPricing(ModelPricing("paid/model", BigDecimal("0.000001"), BigDecimal("0.000002")))
        val missing = AiRequestUsage("pending", "LLM", "paid/model", "https://openrouter.ai/api/v1")
        assertNull(prices.evaluateCost(missing).costUsd)
        assertTrue(AiUsageCalculator.computeStats(listOf(missing), false, prices).isIncomplete)
        assertNull(prices.resolvePricing("unknown/model:free"))
        assertNull(prices.resolvePricing("paid/model-20990101"))
        val spoofed = missing.copy(baseUrl = "https://openrouter.ai.attacker.invalid/v1", inputTokens = 5, outputTokens = 2)
        assertNull(prices.evaluateCost(spoofed).costUsd)
    }
}
