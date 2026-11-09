package com.aliothmoon.maameow.ai.usage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One provider request. Null counters mean the provider did not report them. */
@Serializable
data class AiRequestUsage(
    val requestId: String,
    val source: String,
    val model: String,
    val baseUrl: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cachedInputTokens: Long? = null,
    val reportedCostUsd: String? = null,
    val providerRequestId: String? = null,
)

/**
 * Aggregated usage and cost state for one chat message turn.
 * Null in legacy history or when no requests were tracked.
 */
@Serializable
data class AiMessageUsage(
    val requests: List<AiRequestUsage> = emptyList(),
    val isIncomplete: Boolean = false,
    val estimatedCostUsd: String? = null,
    val costSource: String? = null,
    val costTimestamp: Long? = null,
)

/** Keeps nested Jev/tool requests attributed to their originating chat turn. */
class AiUsageContext(val onUsage: suspend (AiRequestUsage) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AiUsageContext>
}

suspend fun recordAiUsage(usage: AiRequestUsage) {
    currentCoroutineContext()[AiUsageContext]?.onUsage?.invoke(usage)
}
