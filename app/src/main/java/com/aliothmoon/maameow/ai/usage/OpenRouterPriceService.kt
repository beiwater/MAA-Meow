package com.aliothmoon.maameow.ai.usage

import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

data class ModelPricing(
    val modelId: String,
    val promptPrice: BigDecimal,
    val completionPrice: BigDecimal,
    val cachedInputPrice: BigDecimal? = null,
) {
    val isFree: Boolean
        get() = promptPrice.compareTo(BigDecimal.ZERO) == 0 &&
            completionPrice.compareTo(BigDecimal.ZERO) == 0
}

data class CostEvaluation(
    val costUsd: BigDecimal?,
    val isReported: Boolean,
    val isFree: Boolean,
    val source: String?,
    val timestampMillis: Long?,
)

data class TotalCostEvaluation(
    val totalCostUsd: BigDecimal?,
    val reportedCostUsd: BigDecimal?,
    val estimatedCostUsd: BigDecimal?,
    val isAllReported: Boolean,
    val hasEstimated: Boolean,
    val hasUnknown: Boolean,
    val isFree: Boolean,
    val source: String?,
    val timestampMillis: Long?,
)

/**
 * Caches OpenRouter public model pricing from both:
 * 1. Standard chat catalog: https://openrouter.ai/api/v1/models
 * 2. Decisions catalog: https://openrouter.ai/api/v1/models?output_modalities=decisions
 *
 * OpenRouter pricing is USD per token directly.
 * Handles canonical slugs, cached input discounts, free models, and distinguishes
 * provider-reported cost from catalog estimations.
 */
class OpenRouterPriceService(
    private val httpClient: OkHttpClient,
) {
    companion object {
        const val OPENROUTER_CHAT_MODELS_URL = "https://openrouter.ai/api/v1/models"
        const val OPENROUTER_DECISIONS_MODELS_URL = "https://openrouter.ai/api/v1/models?output_modalities=decisions"
        private const val CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L // 24 hours

        const val SOURCE_REPORTED = "reported"
        const val SOURCE_OPENROUTER_CATALOG = "openrouter_catalog"

    }

    private val json = JsonUtils.common
    private val pricingCache = ConcurrentHashMap<String, ModelPricing>()
    @Volatile
    private var lastFetchedAt: Long = 0L
    private val refreshMutex = Mutex()

    fun getCachedPricing(modelId: String): ModelPricing? = resolvePricing(modelId)

    /**
     * Refreshes OpenRouter pricing catalogs if expired or [force] is true.
     * Network failures will not crash or disrupt chat or tools.
     */
    suspend fun refreshPrices(force: Boolean = false) = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (!force && pricingCache.isNotEmpty() && now - lastFetchedAt < CACHE_TTL_MILLIS) {
                return@withContext
            }
            try {
                val chatLoaded = fetchCatalog(OPENROUTER_CHAT_MODELS_URL)
                val decisionsLoaded = fetchCatalog(OPENROUTER_DECISIONS_MODELS_URL)
                if (chatLoaded && decisionsLoaded) lastFetchedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Failed to refresh OpenRouter pricing; retaining the last published cache")
            }
        }
    }

    private fun fetchCatalog(url: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Timber.w("OpenRouter catalog HTTP %d from %s", response.code, url)
                return@use false
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return@use false

            val root = json.parseToJsonElement(body) as? JsonObject ?: return@use false
            val data = root["data"] as? JsonArray ?: return@use false

            for (element in data) {
                val item = element as? JsonObject ?: continue
                val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (id.isEmpty()) continue

                val canonicalSlug = (item["canonical_slug"] as? JsonPrimitive)?.contentOrNull?.trim()
                val pricingObj = item["pricing"] as? JsonObject

                val prompt = parsePrice(pricingObj?.get("prompt")) ?: continue
                val completion = parsePrice(pricingObj?.get("completion")) ?: continue
                val inputCacheRead = parsePrice(pricingObj?.get("input_cache_read"))

                val pricing = ModelPricing(
                    modelId = id,
                    promptPrice = prompt,
                    completionPrice = completion,
                    cachedInputPrice = inputCacheRead,
                )

                pricingCache[id] = pricing
                pricingCache[id.lowercase()] = pricing
                if (!canonicalSlug.isNullOrBlank()) {
                    pricingCache[canonicalSlug] = pricing
                    pricingCache[canonicalSlug.lowercase()] = pricing
                }
            }
            true
        }
    }

    private fun parsePrice(element: kotlinx.serialization.json.JsonElement?): BigDecimal? {
        val prim = element as? JsonPrimitive ?: return null
        val str = prim.contentOrNull?.trim() ?: return null
        return runCatching {
            BigDecimal(str)
        }.getOrNull()?.takeIf { it >= BigDecimal.ZERO }
    }

    fun resolvePricing(modelId: String): ModelPricing? {
        val clean = modelId.trim()
        if (clean.isBlank()) return null

        pricingCache[clean]?.let { return it }
        pricingCache[clean.lowercase()]?.let { return it }


        return null
    }

    /**
     * Calculates cost for a single request.
     * Respects reported cost, cached input discount, free models, and does NOT
     * guess OpenRouter prices for non-OpenRouter models.
     */
    fun evaluateCost(usage: AiRequestUsage, nowMillis: Long = System.currentTimeMillis()): CostEvaluation {
        // 1. Provider reported cost takes priority
        if (usage.reportedCostUsd != null) {
            val parsed = runCatching { BigDecimal(usage.reportedCostUsd) }.getOrNull()
            if (parsed != null && parsed.signum() >= 0) {
                return CostEvaluation(
                    costUsd = parsed,
                    isReported = true,
                    isFree = parsed.compareTo(BigDecimal.ZERO) == 0,
                    source = SOURCE_REPORTED,
                    timestampMillis = nowMillis,
                )
            }
        }

        // 2. Do not falsely match OpenRouter pricing if provider is not OpenRouter and source is not JEV
        val isOpenRouter = usage.baseUrl.toHttpUrlOrNull()?.host.equals("openrouter.ai", true)
        if (!isOpenRouter) {
            return CostEvaluation(
                costUsd = null,
                isReported = false,
                isFree = false,
                source = null,
                timestampMillis = null,
            )
        }

        // 3. Look up OpenRouter catalog
        val pricing = resolvePricing(usage.model)
            ?: return CostEvaluation(
                costUsd = null,
                isReported = false,
                isFree = false,
                source = null,
                timestampMillis = null,
            )

        if (pricing.isFree) {
            return CostEvaluation(
                costUsd = BigDecimal.ZERO,
                isReported = false,
                isFree = true,
                source = SOURCE_OPENROUTER_CATALOG,
                timestampMillis = nowMillis,
            )
        }

        val totalInput = usage.inputTokens
        val output = usage.outputTokens
        val cachedInput = usage.cachedInputTokens ?: 0L
        if (totalInput == null || output == null || totalInput < 0L || output < 0L ||
            cachedInput !in 0L..totalInput
        ) {
            return CostEvaluation(null, false, false, null, null)
        }
        val nonCachedInput = totalInput - cachedInput

        val cachedPrice = pricing.cachedInputPrice ?: pricing.promptPrice
        val promptCost = pricing.promptPrice.multiply(BigDecimal.valueOf(nonCachedInput))
        val cachedCost = cachedPrice.multiply(BigDecimal.valueOf(cachedInput))
        val outputCost = pricing.completionPrice.multiply(BigDecimal.valueOf(output))
        val totalCost = promptCost.add(cachedCost).add(outputCost)

        return CostEvaluation(
            costUsd = totalCost,
            isReported = false,
            isFree = totalCost.compareTo(BigDecimal.ZERO) == 0,
            source = SOURCE_OPENROUTER_CATALOG,
            timestampMillis = lastFetchedAt.takeIf { it > 0L },
        )
    }

    /**
     * Evaluates total cost for a collection of requests.
     */
    fun evaluateTotal(
        requests: List<AiRequestUsage>,
        nowMillis: Long = System.currentTimeMillis(),
    ): TotalCostEvaluation {
        if (requests.isEmpty()) {
            return TotalCostEvaluation(
                totalCostUsd = null,
                reportedCostUsd = null,
                estimatedCostUsd = null,
                isAllReported = false,
                hasEstimated = false,
                hasUnknown = false,
                isFree = false,
                source = null,
                timestampMillis = null,
            )
        }

        var reportedSum = BigDecimal.ZERO
        var estimatedSum = BigDecimal.ZERO
        var hasReported = false
        var hasEstimated = false
        var hasUnknown = false
        var allFree = true

        for (req in requests) {
            val eval = evaluateCost(req, nowMillis)
            if (eval.costUsd != null) {
                if (eval.isReported) {
                    hasReported = true
                    reportedSum = reportedSum.add(eval.costUsd)
                } else {
                    hasEstimated = true
                    estimatedSum = estimatedSum.add(eval.costUsd)
                }
                if (!eval.isFree) {
                    allFree = false
                }
            } else {
                hasUnknown = true
                allFree = false
            }
        }

        val totalCost = if (hasReported || hasEstimated) {
            reportedSum.add(estimatedSum)
        } else if (allFree && !hasUnknown) {
            BigDecimal.ZERO
        } else {
            null
        }

        val primarySource = when {
            hasReported && !hasEstimated -> SOURCE_REPORTED
            hasEstimated && !hasReported -> SOURCE_OPENROUTER_CATALOG
            hasReported && hasEstimated -> "mixed"
            allFree && !hasUnknown -> SOURCE_OPENROUTER_CATALOG
            else -> null
        }

        return TotalCostEvaluation(
            totalCostUsd = totalCost,
            reportedCostUsd = if (hasReported) reportedSum else null,
            estimatedCostUsd = if (hasEstimated) estimatedSum else null,
            isAllReported = hasReported && !hasEstimated && !hasUnknown,
            hasEstimated = hasEstimated,
            hasUnknown = hasUnknown,
            isFree = totalCost != null && totalCost.compareTo(BigDecimal.ZERO) == 0,
            source = primarySource,
            timestampMillis = if (totalCost != null) nowMillis else null,
        )
    }

    /** Manually inject pricing (useful for tests or pre-cached values) */
    fun injectPricing(pricing: ModelPricing) {
        pricingCache[pricing.modelId] = pricing
        pricingCache[pricing.modelId.lowercase()] = pricing
    }
}
