package com.aliothmoon.maameow.ai.usage

import java.math.BigDecimal
import java.util.Locale

data class UsageCategoryBreakdown(
    val requestCount: Int = 0,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val costUsd: BigDecimal? = null,
    val isReportedCost: Boolean = false,
    val isCostEstimated: Boolean = false,
    val hasUnknownCost: Boolean = false,
) {
    val totalTokens: Long?
        get() = if (inputTokens == null && outputTokens == null) {
            null
        } else {
            (inputTokens ?: 0L) + (outputTokens ?: 0L)
        }
}

data class AiUsageStats(
    val llm: UsageCategoryBreakdown = UsageCategoryBreakdown(),
    val jev: UsageCategoryBreakdown = UsageCategoryBreakdown(),
    val totalCostUsd: BigDecimal? = null,
    val reportedCostUsd: BigDecimal? = null,
    val estimatedCostUsd: BigDecimal? = null,
    val isAllReported: Boolean = false,
    val hasEstimatedCost: Boolean = false,
    val hasUnknownCost: Boolean = false,
    val isIncomplete: Boolean = false,
    val hasAnyUsage: Boolean = false,
    val costSource: String? = null,
    val costTimestamp: Long? = null,
    val requests: List<AiRequestUsage> = emptyList(),
) {
    val totalInputTokens: Long?
        get() {
            val l = llm.inputTokens
            val j = jev.inputTokens
            return if (l == null && j == null) null else (l ?: 0L) + (j ?: 0L)
        }

    val totalOutputTokens: Long?
        get() {
            val l = llm.outputTokens
            val j = jev.outputTokens
            return if (l == null && j == null) null else (l ?: 0L) + (j ?: 0L)
        }

    val totalTokens: Long?
        get() {
            val inp = totalInputTokens
            val out = totalOutputTokens
            return if (inp == null && out == null) null else (inp ?: 0L) + (out ?: 0L)
        }
}

object AiUsageCalculator {

    /**
     * Aggregates usage across [requests] with deduplication by requestId.
     */
    fun computeStats(
        requests: List<AiRequestUsage>,
        isIncomplete: Boolean = false,
        priceService: OpenRouterPriceService,
    ): AiUsageStats {
        if (requests.isEmpty()) {
            return AiUsageStats(
                isIncomplete = isIncomplete,
                hasAnyUsage = false,
            )
        }

        // Deduplicate requests by requestId, preserving latest snapshot
        val requestMap = LinkedHashMap<String, AiRequestUsage>()
        for (req in requests) {
            requestMap[req.requestId] = req
        }
        val distinct = requestMap.values.toList()

        val llmRequests = distinct.filter { it.source.equals("LLM", ignoreCase = true) }
        val jevRequests = distinct.filter { it.source.equals("JEV", ignoreCase = true) }

        val llmBreakdown = buildCategoryBreakdown(llmRequests, priceService)
        val jevBreakdown = buildCategoryBreakdown(jevRequests, priceService)

        val totalCostEval = priceService.evaluateTotal(distinct)

        val hasAnyMetrics = distinct.any {
            it.inputTokens != null || it.outputTokens != null || it.reportedCostUsd != null
        }

        return AiUsageStats(
            llm = llmBreakdown,
            jev = jevBreakdown,
            totalCostUsd = totalCostEval.totalCostUsd,
            reportedCostUsd = totalCostEval.reportedCostUsd,
            estimatedCostUsd = totalCostEval.estimatedCostUsd,
            isAllReported = totalCostEval.isAllReported,
            hasEstimatedCost = totalCostEval.hasEstimated,
            hasUnknownCost = totalCostEval.hasUnknown,
            isIncomplete = isIncomplete || distinct.any { it.inputTokens == null || it.outputTokens == null },
            hasAnyUsage = hasAnyMetrics,
            costSource = totalCostEval.source,
            costTimestamp = totalCostEval.timestampMillis,
            requests = distinct,
        )
    }

    private fun buildCategoryBreakdown(
        items: List<AiRequestUsage>,
        priceService: OpenRouterPriceService,
    ): UsageCategoryBreakdown {
        if (items.isEmpty()) return UsageCategoryBreakdown()

        val hasInput = items.any { it.inputTokens != null }
        val inputSum = if (hasInput) items.mapNotNull { it.inputTokens }.sum() else null

        val hasOutput = items.any { it.outputTokens != null }
        val outputSum = if (hasOutput) items.mapNotNull { it.outputTokens }.sum() else null

        val hasCached = items.any { it.cachedInputTokens != null }
        val cachedSum = if (hasCached) items.mapNotNull { it.cachedInputTokens }.sum() else null

        var costSum = BigDecimal.ZERO
        var hasValidCost = false
        var isReported = false
        var isEstimated = false
        var hasUnknown = false

        for (item in items) {
            val eval = priceService.evaluateCost(item)
            if (eval.costUsd != null) {
                costSum = costSum.add(eval.costUsd)
                hasValidCost = true
                if (eval.isReported) isReported = true else isEstimated = true
            } else {
                hasUnknown = true
            }
        }

        return UsageCategoryBreakdown(
            requestCount = items.size,
            inputTokens = inputSum,
            outputTokens = outputSum,
            cachedInputTokens = cachedSum,
            costUsd = if (hasValidCost) costSum else null,
            isReportedCost = isReported,
            isCostEstimated = isEstimated,
            hasUnknownCost = hasUnknown,
        )
    }

    /**
     * Formats USD cost ensuring tiny fractions (e.g. $0.000031122 or $0.0001)
     * are never rounded down to $0.00.
     */
    fun formatCostUsd(amount: BigDecimal?): String? {
        if (amount == null) return null
        if (amount.compareTo(BigDecimal.ZERO) == 0) return "$0.00"

        val plain = amount.stripTrailingZeros().toPlainString()
        return when {
            amount < BigDecimal("0.0001") -> {
                // Tiny USD: keep full precision representation
                "$$plain"
            }
            amount < BigDecimal("0.01") -> {
                String.format(Locale.US, "$%.4f", amount.toDouble())
            }
            else -> {
                val formatted = String.format(Locale.US, "$%.4f", amount.toDouble()).trimEnd('0')
                if (formatted.endsWith(".")) {
                    formatted + "00"
                } else if (formatted.substringAfter(".").length < 2) {
                    formatted + "0"
                } else {
                    formatted
                }
            }
        }
    }

    /**
     * Formats token counts with thousands separators or M suffix.
     */
    fun formatTokens(count: Long?): String? {
        if (count == null) return null
        return when {
            count >= 1_000_000 -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(Locale.US, "%,d", count)
            else -> count.toString()
        }
    }
}
