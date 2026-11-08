package com.aliothmoon.maameow.ai

import com.aliothmoon.maameow.ai.mcp.MaaToolBridge
import com.aliothmoon.maameow.ai.mcp.TaskSchemaGenerator
import com.aliothmoon.maameow.utils.JsonUtils
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import timber.log.Timber

/**
 * 应用内助手执行本机 MCP 工具的执行器。
 *
 * 与 `MaaMcpServerFactory.createServer` 的 handler 一一对应，但走的是同一个
 * [MaaToolBridge] 单例（Koin 提供），因此聊天里的工具调用和外部 MCP 客户端
 * 看到的是同一份状态、同一套校验，不存在第二套实现。
 */
class MaaToolBridgeInvoker(
    private val bridge: MaaToolBridge,
    private val screenshotEnabled: () -> Boolean = { false },
) {
    private val json = JsonUtils.common

    /**
     * 执行一次工具调用。
     *
     * 永不抛出：参数错误、未实现的方法、桥接异常都会变成给模型看的文本，
     * 让它可以自我纠正，而不是把整个对话打断。
     *
     * @param argumentsJson 模型输出的 `function.arguments`（可能是空串或非法 JSON）
     */
    suspend fun invoke(name: String, argumentsJson: String): String = try {
        execute(name, parseArguments(argumentsJson))
    } catch (e: Exception) {
        Timber.w(e, "Tool invocation failed: %s", name)
        failure("${e.javaClass.simpleName}: ${e.message ?: "tool invocation failed"}")
    }

    private suspend fun execute(name: String, args: JsonObject): String {
        return when (name) {
            "maa_status" -> json.encodeToString(bridge.getStatus())

            "maa_list_task_types" -> {
                val filter = args.stringOrNull("type_id")
                val specs = if (filter.isNullOrBlank()) {
                    bridge.taskSpecs
                } else {
                    bridge.taskSpecs.filter {
                        it.typeId.equals(filter, ignoreCase = true) ||
                            it.configClassName.equals(filter, ignoreCase = true)
                    }
                }
                json.encodeToString(TaskSchemaGenerator.generateCatalogJson(specs))
            }

            "maa_list_profiles" -> json.encodeToString(bridge.listProfiles())

            "maa_get_profile" -> {
                val profileId = args.stringOrNull("profile_id").orEmpty()
                val targetId = profileId.ifBlank { bridge.getStatus().activeProfileId }
                val profile = bridge.getProfile(targetId)
                    ?: return failure("Profile not found: $targetId")
                json.encodeToString(profile)
            }

            "maa_create_profile" -> {
                val profileName = args.stringOrNull("name")?.trim().orEmpty()
                if (profileName.isEmpty()) return failure("Missing required 'name'")
                val chain = args["chain"] as? JsonArray
                    ?: return failure("Missing or invalid 'chain' array")
                val validation = bridge.validator.validateChainJson(chain)
                if (!validation.valid) return failure(json.encodeToString(validation))
                bridge.createProfile(profileName, chain).fold(
                    onSuccess = { json.encodeToString(it) },
                    onFailure = { failure(it.message ?: "Failed to create profile") },
                )
            }

            "maa_update_profile" -> {
                val profileId = args.stringOrNull("profile_id")?.trim().orEmpty()
                if (profileId.isEmpty()) return failure("Missing required 'profile_id'")
                val newName = args.stringOrNull("name")?.trim()
                val chain = args["chain"] as? JsonArray
                if (chain != null) {
                    val validation = bridge.validator.validateChainJson(chain)
                    if (!validation.valid) return failure(json.encodeToString(validation))
                }
                bridge.updateProfile(profileId, newName, chain).fold(
                    onSuccess = { json.encodeToString(it) },
                    onFailure = { failure(it.message ?: "Failed to update profile") },
                )
            }

            "maa_delete_profile" -> {
                val profileId = args.stringOrNull("profile_id")?.trim().orEmpty()
                if (profileId.isEmpty()) return failure("Missing required 'profile_id'")
                if (args.booleanOrFalse("confirm").not()) {
                    return failure("Deletion rejected: 'confirm' must be explicitly set to true")
                }
                bridge.deleteProfile(profileId).fold(
                    onSuccess = {
                        buildJsonObject {
                            put("deleted", it)
                            put("profile_id", profileId)
                        }.toString()
                    },
                    onFailure = { failure(it.message ?: "Failed to delete profile") },
                )
            }

            "maa_run_profile" -> {
                val profileId = args.stringOrNull("profile_id")?.takeIf { it.isNotBlank() }
                bridge.runProfile(profileId, args.booleanOrFalse("force")).fold(
                    onSuccess = { json.encodeToString(it) },
                    onFailure = { failure(it.message ?: "Failed to start profile") },
                )
            }

            "maa_stop" -> bridge.stop().fold(
                onSuccess = { buildJsonObject { put("stopped", it) }.toString() },
                onFailure = { failure(it.message ?: "Failed to stop") },
            )

            "maa_save_script" -> {
                val scriptName = args.stringOrNull("name")?.trim().orEmpty()
                if (scriptName.isEmpty()) return failure("Missing required 'name'")
                val chainOverride = args["chain"] as? JsonArray
                if (chainOverride != null) {
                    val validation = bridge.validator.validateChainJson(chainOverride)
                    if (!validation.valid) return failure(json.encodeToString(validation))
                }
                bridge.saveScript(
                    name = scriptName,
                    fromRecordedOrLastRun = chainOverride == null,
                    chainOverride = chainOverride,
                ).fold(
                    onSuccess = { json.encodeToString(it) },
                    onFailure = { failure(it.message ?: "Failed to save script") },
                )
            }

            "maa_list_scripts" -> json.encodeToString(bridge.listScripts())

            "maa_run_script" -> {
                val profileId = args.stringOrNull("profile_id")?.trim().orEmpty()
                if (profileId.isEmpty()) return failure("Missing required 'profile_id'")
                bridge.runScript(profileId, args.booleanOrFalse("force")).fold(
                    onSuccess = { json.encodeToString(it) },
                    onFailure = { failure(it.message ?: "Failed to run script") },
                )
            }

            "maa_get_logs" -> {
                val limit = (args.intOrNull("limit") ?: 100).coerceIn(1, 500)
                val redact = if (args.containsKey("redact")) args.booleanOrFalse("redact") else true
                val logs = bridge.getLogs(limit = limit, redactSensitive = redact)
                json.encodeToString(
                    buildJsonObject {
                        put("count", logs.size)
                        put("redacted", redact)
                        put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
                    },
                )
            }

            "maa_screenshot" -> {
                if (!screenshotEnabled()) {
                    return failure(
                        "maa_screenshot is disabled by default. Enable 'Allow AI Screenshot' in MaaMeow AI Control Settings first.",
                    )
                }
                bridge.captureScreenshotBase64().fold(
                    onSuccess = { base64 ->
                        "Screenshot captured (image/jpeg, ${base64.length} base64 chars)。" +
                            "本对话通道只显示文本，无法内联图像；请让用户在应用内查看截图结果。"
                    },
                    onFailure = { failure(it.message ?: "Failed to capture screenshot") },
                )
            }

            "maa_load_copilot" -> {
                val copilotInput = args.stringOrNull("copilot")?.trim().orEmpty()
                if (copilotInput.isEmpty()) return failure("Missing required 'copilot'")
                bridge.loadAndRunCopilot(copilotInput, args["config"] as? JsonObject).fold(
                    onSuccess = { json.encodeToString(it) },
                    onFailure = { failure(it.message ?: "Failed to load/start copilot") },
                )
            }

            "maa_search_wiki" -> {
                val query = args.stringOrNull("query")?.trim().orEmpty()
                if (query.isEmpty()) return failure("Missing required 'query'")
                val limit = (args.intOrNull("limit") ?: 5).coerceIn(1, 10)
                val wiki = args.stringOrNull("wiki")?.trim()?.lowercase() ?: "auto"
                bridge.searchWiki(query, limit, wiki).fold(
                    onSuccess = { it },
                    onFailure = { failure(it.message ?: "Failed to search wiki") },
                )
            }

            "maa_get_user_data" -> {
                val category = args.stringOrNull("category")?.trim()?.lowercase() ?: "all"
                val filter = args.stringOrNull("filter")?.trim()?.takeIf { it.isNotEmpty() }
                bridge.getUserData(category, filter).fold(
                    onSuccess = { json.encodeToString(it) },
                    onFailure = { failure(it.message ?: "Failed to get user data") },
                )
            }

            "maa_get_wiki_article" -> {
                val title = args.stringOrNull("title")?.trim().orEmpty()
                if (title.isEmpty()) return failure("Missing required 'title'")
                val wiki = args.stringOrNull("wiki")?.trim()?.lowercase() ?: "auto"
                bridge.getWikiArticle(title, wiki).fold(
                    onSuccess = { it },
                    onFailure = { failure(it.message ?: "Failed to get wiki article") },
                )
            }

            "maa_click" -> {
                val x = args.intOrNull("x") ?: return failure("Missing or invalid 'x' coordinate")
                val y = args.intOrNull("y") ?: return failure("Missing or invalid 'y' coordinate")
                bridge.click(x, y).fold(
                    onSuccess = { "Click dispatched successfully to ($x, $y)" },
                    onFailure = { failure(it.message ?: "Failed to execute click") },
                )
            }

            "maa_back_to_home" -> bridge.backToHome().fold(
                onSuccess = { "BackToHome routine executed successfully" },
                onFailure = { failure(it.message ?: "Failed to execute BackToHome") },
            )

            else -> failure("Unknown tool: $name")
        }
    }

    private fun parseArguments(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
    }

    private fun failure(message: String): String = "Error: $message"

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intOrNull(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
}
