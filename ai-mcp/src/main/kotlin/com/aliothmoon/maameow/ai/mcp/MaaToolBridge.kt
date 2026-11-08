package com.aliothmoon.maameow.ai.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

@Serializable
data class TaskItemStatusDto(
    val taskId: Int,
    val taskChain: String,
    val status: String,
    val nodeId: String? = null,
    val logName: String? = null,
)

@Serializable
data class MaaStatusDto(
    val state: String,
    val activeProfileId: String,
    val clientType: String,
    val currentRunId: String? = null,
    val runDeadlineMs: Long? = null,
    val recordingActive: Boolean = false,
    val tasks: List<TaskItemStatusDto> = emptyList(),
    val lastError: String? = null,
    val handoverRequired: Boolean = false,
    val handoverReason: String? = null,
)

@Serializable
data class RunLaunchResultDto(
    val started: Boolean,
    val runId: String,
    val profileId: String,
    val message: String,
)

@Serializable
data class ScriptSummaryDto(
    val profileId: String,
    val name: String,
    val nodeCount: Int,
    val isRecordedDraft: Boolean = false,
    val chain: JsonArray,
)

/**
 * Thin bridge connecting the MCP server tool table to MaaMeow's existing domain services
 * (`MaaCompositionService`, `TaskChainState`, `CopilotManager`, `MaaSessionLogger`, `FrameSnapshotter`).
 */
interface MaaToolBridge {
    val taskSpecs: List<TaskTypeSpec>
    val validator: ArknightsEntityValidator

    suspend fun getStatus(): MaaStatusDto
    suspend fun listProfiles(): JsonArray
    suspend fun getProfile(profileId: String): JsonObject?
    suspend fun createProfile(name: String, chain: JsonArray): Result<JsonObject>
    suspend fun updateProfile(profileId: String, name: String?, chain: JsonArray?): Result<JsonObject>
    suspend fun deleteProfile(profileId: String): Result<Boolean>
    suspend fun runProfile(profileId: String?, force: Boolean): Result<RunLaunchResultDto>
    suspend fun stop(): Result<Boolean>

    // §4 Scripting
    suspend fun saveScript(name: String, fromRecordedOrLastRun: Boolean = true, chainOverride: JsonArray? = null): Result<ScriptSummaryDto>
    suspend fun listScripts(): List<ScriptSummaryDto>
    suspend fun runScript(profileId: String, force: Boolean = false): Result<RunLaunchResultDto>

    // Logs, Screenshot, Copilot
    suspend fun getLogs(limit: Int = 100, redactSensitive: Boolean = true): List<String>
    suspend fun captureScreenshotBase64(): Result<String>
    suspend fun loadAndRunCopilot(copilotInput: String, configJson: JsonObject? = null): Result<RunLaunchResultDto>

    // Wiki Search
    suspend fun searchWiki(query: String, limit: Int = 5, wiki: String = "auto"): Result<String>

    // User Data (OperBox & Depot)
    suspend fun getUserData(category: String = "all", filter: String? = null): Result<JsonObject>

    // Wiki Article
    suspend fun getWikiArticle(title: String, wiki: String = "auto"): Result<String>

    // Emergency Touch & Navigation
    suspend fun click(x: Int, y: Int): Result<Boolean>
    suspend fun backToHome(): Result<Boolean>
}

object LogRedactor {
    private val SENSITIVE_PATTERNS = listOf(
        Regex("""(Bearer\s+)[A-Za-z0-9._\-]+""", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
        Regex("""("?(?:token|password|credential|cdk|penguinId|accountName)"?\s*[:=]\s*"?)([^",\s}]+)""", RegexOption.IGNORE_CASE) to "$1[REDACTED]",
        Regex("""(/data/user/\d+/[^/\s]+/files/)""") to "<app_files>/",
        Regex("""(/storage/emulated/\d+/)""") to "<sdcard>/",
    )

    fun redact(line: String): String {
        var result = line
        for ((regex, replacement) in SENSITIVE_PATTERNS) {
            result = regex.replace(result, replacement)
        }
        return result
    }
}
