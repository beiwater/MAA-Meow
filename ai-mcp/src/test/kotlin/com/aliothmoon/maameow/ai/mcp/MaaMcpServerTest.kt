package com.aliothmoon.maameow.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Serializable
data class DummyFightConfig(
    val useMedicine: Boolean = false,
    val medicineNumber: Int = 999,
    val stage1: String = "",
    val alternateStages: List<String> = emptyList(),
    val dropsItemId: String = "",
)

@Serializable
data class DummyWakeUpConfig(
    val clientType: String = "Official",
    val startGameEnabled: Boolean = true,
    val accountName: String = "",
)

@Serializable
data class DummyRoguelikeStartingOper(
    val name: String = "",
    val use: Boolean = true,
    val skill: Int = 1,
)

@Serializable
data class DummyRoguelikeConfig(
    val theme: String = "Phantom",
    val startingOpers: List<DummyRoguelikeStartingOper> = emptyList(),
)


class FakeMaaToolBridge(
    override val taskSpecs: List<TaskTypeSpec>,
    override val validator: ArknightsEntityValidator,
) : MaaToolBridge {
    val profiles = mutableMapOf<String, JsonObject>()
    var activeProfileId = "profile-default"
    var currentState = "IDLE"
    var lastRunId: String? = null
    var lastError: String? = null
    var handoverRequired: Boolean = false
    var handoverReason: String? = null
    var tasks: List<TaskItemStatusDto> = emptyList()
    val recordedChains = mutableListOf<JsonArray>()
    init {
        val defaultChain = buildJsonArray {
            add(buildJsonObject {
                put("id", "node-1")
                put("name", "开始唤醒")
                put("enabled", true)
                put("order", 0)
                put("config", buildJsonObject {
                    put("type", "com.aliothmoon.maameow.data.model.WakeUpConfig")
                    put("clientType", "Official")
                    put("startGameEnabled", true)
                })
            })
        }
        profiles[activeProfileId] = buildJsonObject {
            put("id", activeProfileId)
            put("name", "默认配置")
            put("chain", defaultChain)
        }
        recordedChains += defaultChain
    }

    override suspend fun getStatus(): MaaStatusDto = MaaStatusDto(
        state = currentState,
        activeProfileId = activeProfileId,
        clientType = "Official",
        currentRunId = lastRunId,
        tasks = tasks,
        lastError = lastError,
        handoverRequired = handoverRequired,
        handoverReason = handoverReason,
    )

    override suspend fun listProfiles(): JsonArray = buildJsonArray {
        profiles.values.forEach { add(it) }
    }

    override suspend fun getProfile(profileId: String): JsonObject? = profiles[profileId]

    override suspend fun createProfile(name: String, chain: JsonArray): Result<JsonObject> {
        val id = "profile-${profiles.size + 1}"
        val obj = buildJsonObject {
            put("id", id)
            put("name", name)
            put("chain", chain)
        }
        profiles[id] = obj
        return Result.success(obj)
    }

    override suspend fun updateProfile(profileId: String, name: String?, chain: JsonArray?): Result<JsonObject> {
        val existing = profiles[profileId] ?: return Result.failure(IllegalArgumentException("Not found: $profileId"))
        val updated = buildJsonObject {
            put("id", profileId)
            put("name", name ?: existing["name"]!!.jsonPrimitive.content)
            put("chain", chain ?: existing["chain"]!!.jsonArray)
        }
        profiles[profileId] = updated
        return Result.success(updated)
    }

    override suspend fun deleteProfile(profileId: String): Result<Boolean> {
        if (profiles.size <= 1) return Result.failure(IllegalStateException("Cannot delete last profile"))
        val removed = profiles.remove(profileId) != null
        return Result.success(removed)
    }

    override suspend fun runProfile(profileId: String?, force: Boolean): Result<RunLaunchResultDto> {
        if (currentState == "RUNNING" && !force) {
            return Result.failure(IllegalStateException("Already running; set force=true to preempt"))
        }
        val targetId = profileId ?: activeProfileId
        val p = profiles[targetId] ?: return Result.failure(IllegalArgumentException("Profile not found: $targetId"))
        currentState = "RUNNING"
        val runId = "run-1001"
        lastRunId = runId
        recordedChains += p["chain"]!!.jsonArray
        return Result.success(RunLaunchResultDto(started = true, runId = runId, profileId = targetId, message = "Started"))
    }

    override suspend fun stop(): Result<Boolean> {
        currentState = "IDLE"
        return Result.success(true)
    }

    override suspend fun saveScript(
        name: String,
        fromRecordedOrLastRun: Boolean,
        chainOverride: JsonArray?,
    ): Result<ScriptSummaryDto> {
        val chain = chainOverride ?: recordedChains.lastOrNull()
            ?: return Result.failure(IllegalStateException("No recorded run to save"))
        val created = createProfile(name, chain).getOrThrow()
        val pid = created["id"]!!.jsonPrimitive.content
        return Result.success(
            ScriptSummaryDto(
                profileId = pid,
                name = name,
                nodeCount = chain.size,
                isRecordedDraft = false,
                chain = chain,
            ),
        )
    }

    override suspend fun listScripts(): List<ScriptSummaryDto> = profiles.values.map { p ->
        ScriptSummaryDto(
            profileId = p["id"]!!.jsonPrimitive.content,
            name = p["name"]!!.jsonPrimitive.content,
            nodeCount = p["chain"]!!.jsonArray.size,
            chain = p["chain"]!!.jsonArray,
        )
    }

    override suspend fun runScript(profileId: String, force: Boolean): Result<RunLaunchResultDto> =
        runProfile(profileId, force)

    override suspend fun getLogs(limit: Int, redactSensitive: Boolean): List<String> {
        val raw = listOf(
            "[INFO] Session started for accountName=\"doctor_secret_123\" in /data/user/0/com.aliothmoon.maameow/files/debug",
            "[INFO] Token Bearer secretTokenAbc123xyz used",
        ).take(limit)
        return if (redactSensitive) raw.map(LogRedactor::redact) else raw
    }

    override suspend fun captureScreenshotBase64(): Result<String> = Result.success("dGVzdGpwZWc=")

    override suspend fun loadAndRunCopilot(copilotInput: String, configJson: JsonObject?): Result<RunLaunchResultDto> {
        currentState = "RUNNING"
        return Result.success(
            RunLaunchResultDto(
                started = true,
                runId = "copilot-run-1",
                profileId = activeProfileId,
                message = "Copilot loaded: $copilotInput",
            ),
        )
    }

    override suspend fun searchWiki(query: String, limit: Int, wiki: String): Result<String> {
        return if (query == "error_trigger") {
            Result.failure(RuntimeException("Network error"))
        } else if (wiki == "wiki_gg" || (wiki == "auto" && !query.any { it.code in 0x4E00..0x9FFF })) {
            Result.success("[Arknights Terra Wiki (wiki.gg)]\n[1] **[$query](https://arknights.wiki.gg/wiki/$query)**\nSnippet for $query (limit=$limit)")
        } else {
            Result.success("[PRTS Wiki (中文)]\n[1] **[$query](https://prts.wiki/w/$query)**\nSnippet for $query (limit=$limit)")
        }
    }

    override suspend fun getUserData(category: String, filter: String?): Result<JsonObject> {
        if (filter == "error_trigger") return Result.failure(RuntimeException("User data error"))
        return Result.success(buildJsonObject {
            if (category == "operators" || category == "all") {
                put("operators", buildJsonObject {
                    put("hasSynced", true)
                    put("syncTimeMillis", 1700000000000L)
                    val opList = if (filter == "SilverAsh" || filter == "char_102_texas") {
                        listOf(
                            buildJsonObject {
                                put("name", filter)
                                put("id", "char_test")
                                put("rarity", 5)
                                put("elite", 2)
                                put("level", 90)
                                put("potential", 5)
                                put("skills", buildJsonArray {
                                    add(buildJsonObject {
                                        put("id", "skchr_test_3")
                                        put("level", 3)
                                    })
                                })
                                put("equips", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "X")
                                        put("level", 3)
                                    })
                                })
                            }
                        )
                    } else {
                        listOf(
                            buildJsonObject {
                                put("name", "SilverAsh")
                                put("id", "char_silverash")
                                put("rarity", 5)
                                put("elite", 2)
                                put("level", 90)
                                put("potential", 6)
                                put("skills", buildJsonArray {
                                    add(buildJsonObject {
                                        put("id", "skchr_silverash_3")
                                        put("level", 3)
                                    })
                                })
                                put("equips", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "X")
                                        put("level", 3)
                                    })
                                })
                            }
                        )
                    }
                    put("owned", buildJsonArray { opList.forEach { add(it) } })
                })
            }
            if (category == "depot" || category == "all") {
                put("depot", buildJsonObject {
                    put("hasSynced", true)
                    put("syncTimeMillis", 1700000000000L)
                    put("items", buildJsonObject {
                        if (filter == null || "固源岩" in filter || "30012" in filter) {
                            put("固源岩", 125)
                        }
                    })
                })
            }
        })
    }

    override suspend fun getWikiArticle(title: String, wiki: String): Result<String> {
        if (title == "error_trigger") return Result.failure(RuntimeException("Article fetch error"))
        val source = if (wiki == "wiki_gg" || (wiki == "auto" && !title.any { it.code in 0x4E00..0x9FFF })) {
            "Arknights Terra Wiki"
        } else {
            "PRTS Wiki"
        }
        return Result.success("# $title\n\n[$source]\nContent of article $title.")
    }
    val clicks = mutableListOf<Pair<Int, Int>>()
    var backToHomeCount = 0
    var clickError: String? = null
    var backToHomeError: String? = null

    override suspend fun click(x: Int, y: Int): Result<Boolean> {
        clickError?.let { return Result.failure(RuntimeException(it)) }
        clicks.add(x to y)
        return Result.success(true)
    }

    override suspend fun backToHome(): Result<Boolean> {
        backToHomeError?.let { return Result.failure(RuntimeException(it)) }
        backToHomeCount++
        return Result.success(true)
    }
}

class MaaMcpServerTest {

    private val specs = listOf(
        TaskTypeSpec(
            typeId = "WAKE_UP",
            configClassName = "WakeUpConfig",
            description = "开始唤醒",
            serializer = DummyWakeUpConfig.serializer(),
            defaultInstance = DummyWakeUpConfig(),
        ),
        TaskTypeSpec(
            typeId = "COMBAT",
            configClassName = "FightConfig",
            description = "刷理智",
            serializer = DummyFightConfig.serializer(),
            defaultInstance = DummyFightConfig(),
        ),
        TaskTypeSpec(
            typeId = "AUTO_ROGUELIKE",
            configClassName = "RoguelikeConfig",
            description = "自动肉鸽",
            serializer = DummyRoguelikeConfig.serializer(),
            defaultInstance = DummyRoguelikeConfig(),
        ),
    )

    private fun createValidator(): ArknightsEntityValidator {
        val resourceDir = File("../app/src/main/assets/MaaSync/MaaResource")
        return if (resourceDir.isDirectory) {
            ArknightsEntityValidator.fromResourceDir(resourceDir, specs)
        } else {
            val specMap = specs.flatMap {
                listOf(it.typeId to it, it.configClassName to it, it.serializer.descriptor.serialName to it)
            }.toMap()
            ArknightsEntityValidator(
                validStages = setOf("1-7", "CE-6", "LS-6", "CA-5", "AP-5", "SK-5", "Annihilation"),
                validOperators = setOf("银灰", "玛恩纳", "维什戴尔", "黍", "伊内丝", "桃金娘"),
                validItemIds = mapOf("30012" to "固源岩", "30013" to "固源岩组", "30062" to "装置"),
                taskSpecsByType = specMap,
            )
        }
    }

    @Test
    fun `tools list and call over in-memory transport`() = runBlocking {
        val validator = createValidator()
        val bridge = FakeMaaToolBridge(specs, validator)
        var screenshotAllowed = false
        val server = MaaMcpServerFactory.createServer(
            bridge = bridge,
            screenshotEnabled = { screenshotAllowed },
        )

        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        server.createSession(serverTransport)

        val client = Client(clientInfo = Implementation(name = "test-client", version = "1.0.0"))
        client.connect(clientTransport)

        // 1. Verify tools/list
        val tools = client.listTools().tools
        val toolNames = tools.map { it.name }.toSet()
        assertTrue(toolNames.containsAll(listOf(
            "maa_status",
            "maa_list_task_types",
            "maa_list_profiles",
            "maa_get_profile",
            "maa_create_profile",
            "maa_update_profile",
            "maa_delete_profile",
            "maa_run_profile",
            "maa_stop",
            "maa_save_script",
            "maa_list_scripts",
            "maa_run_script",
            "maa_get_logs",
            "maa_screenshot",
            "maa_load_copilot",
            "maa_search_wiki",
            "maa_get_user_data",
            "maa_get_wiki_article",
            "maa_click",
            "maa_back_to_home",
        )))

        // 2. Verify maa_list_task_types auto-generated schema
        val schemaRes = client.callTool(name = "maa_list_task_types", arguments = emptyMap())
        assertFalse(schemaRes.isError ?: false)
        val schemaText = (schemaRes.content.first() as TextContent).text
        assertTrue(schemaText.contains("FightConfig"))
        assertTrue(schemaText.contains("medicineNumber"))
        assertTrue(schemaText.contains("999"))

        // 3. Anti-hallucination gate: reject nonexistent stage "99-99" and unknown field
        val badCreateRes = client.callTool(
            name = "maa_create_profile",
            arguments = mapOf(
                "name" to JsonPrimitive("错误配置"),
                "chain" to buildJsonArray {
                    add(buildJsonObject {
                        put("name", "刷图")
                        put("config", buildJsonObject {
                            put("type", "FightConfig")
                            put("stage1", "99-99-FAKE")
                            put("nonExistentParam", 123)
                        })
                    })
                },
            ),
        )
        assertTrue("Expected error on hallucinated stage/field", badCreateRes.isError == true)
        val badErrorJson = (badCreateRes.content.first() as TextContent).text
        assertTrue(badErrorJson.contains("99-99-FAKE"))
        assertTrue(badErrorJson.contains("nonExistentParam"))
        assertTrue(badErrorJson.contains("candidates"))

        // 4. Anti-hallucination gate: reject nonexistent operator
        val badOperRes = client.callTool(
            name = "maa_create_profile",
            arguments = mapOf(
                "name" to JsonPrimitive("错误肉鸽"),
                "chain" to buildJsonArray {
                    add(buildJsonObject {
                        put("name", "肉鸽")
                        put("config", buildJsonObject {
                            put("type", "RoguelikeConfig")
                            put("theme", "Sami")
                            put("startingOpers", buildJsonArray {
                                add(buildJsonObject {
                                    put("name", "不存在的幻觉干员X")
                                })
                            })
                        })
                    })
                },
            ),
        )
        assertTrue("Expected error on hallucinated operator", badOperRes.isError == true)
        val badOperText = (badOperRes.content.first() as TextContent).text
        assertTrue(badOperText.contains("不存在的幻觉干员X"))

        // 5. Valid profile creation -> run -> status -> stop -> save_script
        val goodCreateRes = client.callTool(
            name = "maa_create_profile",
            arguments = mapOf(
                "name" to JsonPrimitive("刷1-7"),
                "chain" to buildJsonArray {
                    add(buildJsonObject {
                        put("name", "刷理智")
                        put("enabled", true)
                        put("config", buildJsonObject {
                            put("type", "FightConfig")
                            put("stage1", "1-7")
                            put("useMedicine", true)
                        })
                    })
                },
            ),
        )
        assertFalse(goodCreateRes.isError ?: false)
        val createdProfile = Json.parseToJsonElement((goodCreateRes.content.first() as TextContent).text).jsonObject
        val newProfileId = createdProfile["id"]!!.jsonPrimitive.content

        // Run profile asynchronously
        val runRes = client.callTool(
            name = "maa_run_profile",
            arguments = mapOf("profile_id" to JsonPrimitive(newProfileId)),
        )
        assertFalse(runRes.isError ?: false)
        val runObj = Json.parseToJsonElement((runRes.content.first() as TextContent).text).jsonObject
        assertEquals("run-1001", runObj["runId"]!!.jsonPrimitive.content)

        // Status transitions to RUNNING
        val statusRunning = client.callTool(name = "maa_status", arguments = emptyMap())
        assertTrue((statusRunning.content.first() as TextContent).text.contains("RUNNING"))

        // Stop transitions to IDLE
        val stopRes = client.callTool(name = "maa_stop", arguments = emptyMap())
        assertFalse(stopRes.isError ?: false)
        val statusIdle = client.callTool(name = "maa_status", arguments = emptyMap())
        assertTrue((statusIdle.content.first() as TextContent).text.contains("IDLE"))

        // Screenshot disabled by default
        val shotDisabled = client.callTool(name = "maa_screenshot", arguments = emptyMap())
        assertTrue(shotDisabled.isError == true)
        screenshotAllowed = true
        val shotEnabled = client.callTool(name = "maa_screenshot", arguments = emptyMap())
        assertFalse(shotEnabled.isError ?: false)

        // Log redaction
        val logsRes = client.callTool(name = "maa_get_logs", arguments = emptyMap())
        val logsText = (logsRes.content.first() as TextContent).text
        assertFalse(logsText.contains("doctor_secret_123"))
        assertFalse(logsText.contains("secretTokenAbc123xyz"))
        assertTrue(logsText.contains("[REDACTED]"))

        // Save script & replay
        val saveScriptRes = client.callTool(
            name = "maa_save_script",
            arguments = mapOf("name" to JsonPrimitive("录制脚本-1-7")),
        )
        assertFalse(saveScriptRes.isError ?: false)
        val savedScript = Json.parseToJsonElement((saveScriptRes.content.first() as TextContent).text).jsonObject
        assertNotNull(savedScript["profileId"])


        // maa_search_wiki tool call (PRTS / Chinese)
        val wikiRes = client.callTool(
            name = "maa_search_wiki",
            arguments = mapOf("query" to JsonPrimitive("银灰"), "limit" to JsonPrimitive(3)),
        )
        assertFalse(wikiRes.isError ?: false)
        val wikiText = (wikiRes.content.first() as TextContent).text
        assertTrue(wikiText.contains("[PRTS Wiki (中文)]"))
        assertTrue(wikiText.contains("[1] **[银灰](https://prts.wiki/w/银灰)**"))
        assertTrue(wikiText.contains("limit=3"))

        // maa_search_wiki tool call (wiki.gg / English auto)
        val wikiGgRes = client.callTool(
            name = "maa_search_wiki",
            arguments = mapOf("query" to JsonPrimitive("SilverAsh"), "wiki" to JsonPrimitive("auto")),
        )
        assertFalse(wikiGgRes.isError ?: false)
        val wikiGgText = (wikiGgRes.content.first() as TextContent).text
        assertTrue(wikiGgText.contains("[Arknights Terra Wiki (wiki.gg)]"))
        assertTrue(wikiGgText.contains("[1] **[SilverAsh](https://arknights.wiki.gg/wiki/SilverAsh)**"))

        // maa_search_wiki tool call explicit wiki_gg
        val explicitWikiGgRes = client.callTool(
            name = "maa_search_wiki",
            arguments = mapOf("query" to JsonPrimitive("1-7"), "wiki" to JsonPrimitive("wiki_gg")),
        )
        assertFalse(explicitWikiGgRes.isError ?: false)
        assertTrue((explicitWikiGgRes.content.first() as TextContent).text.contains("wiki.gg"))

        // maa_search_wiki missing query -> error
        val wikiMissingRes = client.callTool(
            name = "maa_search_wiki",
            arguments = emptyMap(),
        )
        assertTrue(wikiMissingRes.isError == true)

        // maa_search_wiki failure handling
        val wikiFailRes = client.callTool(
            name = "maa_search_wiki",
            arguments = mapOf("query" to JsonPrimitive("error_trigger")),
        )
        assertTrue(wikiFailRes.isError == true)

        // maa_get_user_data (all)
        val userDataRes = client.callTool(
            name = "maa_get_user_data",
            arguments = emptyMap(),
        )
        assertFalse(userDataRes.isError ?: false)
        val userDataJson = Json.parseToJsonElement((userDataRes.content.first() as TextContent).text).jsonObject
        assertNotNull(userDataJson["operators"])
        assertNotNull(userDataJson["depot"])
        assertTrue(userDataJson["operators"]!!.jsonObject["hasSynced"]!!.jsonPrimitive.booleanOrNull == true)

        // maa_get_user_data (operators only with filter)
        val operDataRes = client.callTool(
            name = "maa_get_user_data",
            arguments = mapOf("category" to JsonPrimitive("operators"), "filter" to JsonPrimitive("SilverAsh")),
        )
        assertFalse(operDataRes.isError ?: false)
        val operDataJson = Json.parseToJsonElement((operDataRes.content.first() as TextContent).text).jsonObject
        assertNotNull(operDataJson["operators"])
        assertTrue(operDataJson["depot"] == null)
        val ownedOps = operDataJson["operators"]!!.jsonObject["owned"]!!.jsonArray
        assertEquals(1, ownedOps.size)
        assertEquals("SilverAsh", ownedOps[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(3, ownedOps[0].jsonObject["skills"]!!.jsonArray[0].jsonObject["level"]!!.jsonPrimitive.intOrNull)

        // maa_get_user_data error
        val userDataErrRes = client.callTool(
            name = "maa_get_user_data",
            arguments = mapOf("filter" to JsonPrimitive("error_trigger")),
        )
        assertTrue(userDataErrRes.isError == true)

        // maa_get_wiki_article (PRTS)
        val articlePrtsRes = client.callTool(
            name = "maa_get_wiki_article",
            arguments = mapOf("title" to JsonPrimitive("银灰")),
        )
        assertFalse(articlePrtsRes.isError ?: false)
        val articlePrtsText = (articlePrtsRes.content.first() as TextContent).text
        assertTrue(articlePrtsText.startsWith("# 银灰"))
        assertTrue(articlePrtsText.contains("[PRTS Wiki]"))

        // maa_get_wiki_article (wiki.gg)
        val articleWikiGgRes = client.callTool(
            name = "maa_get_wiki_article",
            arguments = mapOf("title" to JsonPrimitive("SilverAsh")),
        )
        assertFalse(articleWikiGgRes.isError ?: false)
        val articleWikiGgText = (articleWikiGgRes.content.first() as TextContent).text
        assertTrue(articleWikiGgText.startsWith("# SilverAsh"))
        assertTrue(articleWikiGgText.contains("[Arknights Terra Wiki]"))

        // maa_get_wiki_article missing title
        val articleMissingRes = client.callTool(
            name = "maa_get_wiki_article",
            arguments = emptyMap(),
        )
        assertTrue(articleMissingRes.isError == true)

        // maa_get_wiki_article error trigger
        val articleErrRes = client.callTool(
            name = "maa_get_wiki_article",
            arguments = mapOf("title" to JsonPrimitive("error_trigger")),
        )
        assertTrue(articleErrRes.isError == true)

        // 23. Test maa_click
        val clickRes = client.callTool(
            name = "maa_click",
            arguments = mapOf("x" to JsonPrimitive(640), "y" to JsonPrimitive(360)),
        )
        assertFalse(clickRes.isError ?: false)
        assertEquals(listOf(640 to 360), bridge.clicks)
        val clickText = (clickRes.content.first() as TextContent).text
        assertTrue(clickText.contains("Click dispatched successfully to (640, 360)"))

        // maa_click missing coordinate
        val clickMissingRes = client.callTool(
            name = "maa_click",
            arguments = mapOf("x" to JsonPrimitive(640)),
        )
        assertTrue(clickMissingRes.isError == true)

        // maa_click failure
        bridge.clickError = "Remote service disconnected"
        val clickFailedRes = client.callTool(
            name = "maa_click",
            arguments = mapOf("x" to JsonPrimitive(100), "y" to JsonPrimitive(100)),
        )
        assertTrue(clickFailedRes.isError == true)
        val clickErrText = (clickFailedRes.content.first() as TextContent).text
        assertTrue(clickErrText.contains("Remote service disconnected"))
        bridge.clickError = null

        // 24. Test maa_back_to_home
        val backHomeRes = client.callTool(
            name = "maa_back_to_home",
            arguments = emptyMap(),
        )
        assertFalse(backHomeRes.isError ?: false)
        assertEquals(1, bridge.backToHomeCount)
        val backHomeText = (backHomeRes.content.first() as TextContent).text
        assertTrue(backHomeText.contains("BackToHome routine executed successfully"))

        // maa_back_to_home failure
        bridge.backToHomeError = "MaaCore has no active instance"
        val backHomeErrRes = client.callTool(
            name = "maa_back_to_home",
            arguments = emptyMap(),
        )
        assertTrue(backHomeErrRes.isError == true)
        val backHomeErrText = (backHomeErrRes.content.first() as TextContent).text
        assertTrue(backHomeErrText.contains("MaaCore has no active instance"))
        bridge.backToHomeError = null
        client.close()
        server.close()
    }

    @Test
    fun `http server enforces Bearer token and serves MCP over Streamable HTTP`() = runBlocking {
        val validator = createValidator()
        val bridge = FakeMaaToolBridge(specs, validator)
        val port = java.net.ServerSocket(0).use { it.localPort }
        val token = "test-bearer-token-98765"
        val httpServer = MaaMcpServerFactory.createHttpServer(
            host = "127.0.0.1",
            port = port,
            bearerToken = token,
            bridge = bridge,
        )
        httpServer.start(wait = false)
        try {
            // 1. Unauthorized request without Bearer token -> 401
            val unauthConn = (java.net.URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
            }
            assertEquals(401, unauthConn.responseCode)

            // 2. Health check -> 200
            val healthConn = (java.net.URI("http://127.0.0.1:$port/health").toURL().openConnection() as java.net.HttpURLConnection)
            assertEquals(200, healthConn.responseCode)

            // 3. Authorized JSON-RPC initialize + tools/call over HTTP
            val initConn = (java.net.URI("http://127.0.0.1:$port/mcp").toURL().openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
            }
            val initBody = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"http-test","version":"1.0"}}}"""
            initConn.outputStream.use { it.write(initBody.toByteArray()) }
            assertEquals(200, initConn.responseCode)
            val sessionId = initConn.getHeaderField("Mcp-Session-Id")
            assertNotNull("Expected Mcp-Session-Id header", sessionId)
            val initResp = initConn.inputStream.bufferedReader().readText()
            assertTrue(initResp.contains("maameow-mcp"))
        } finally {
            httpServer.stop(100, 200)
        }
    }
    @Test
    fun `maa_status returns handoverRequired and lastError when task errors`() = runBlocking {
        val validator = createValidator()
        val bridge = FakeMaaToolBridge(specs, validator).apply {
            currentState = "ERROR"
            lastError = "Connection to emulator lost"
            handoverRequired = true
            handoverReason = "Task execution encountered an error: Connection to emulator lost. Agent decision required (diagnose via maa_get_logs/maa_screenshot, modify profile or restart)."
            tasks = listOf(
                TaskItemStatusDto(
                    taskId = 101,
                    taskChain = "Fight",
                    status = "ERROR",
                )
            )
        }
        val server = MaaMcpServerFactory.createServer(bridge)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        server.createSession(serverTransport)

        val client = Client(
            clientInfo = Implementation(name = "test-client", version = "1.0.0"),
        )
        client.connect(clientTransport)

        val res = client.callTool(name = "maa_status", arguments = emptyMap())
        assertFalse(res.isError ?: false)
        val text = (res.content.first() as TextContent).text
        assertTrue("Expected ERROR state in response: $text", text.contains(""""state":"ERROR"""") || text.contains(""""state": "ERROR""""))
        assertTrue("Expected handoverRequired: true in response: $text", text.contains(""""handoverRequired":true""") || text.contains(""""handoverRequired": true"""))
        assertTrue("Expected lastError in response: $text", text.contains("Connection to emulator lost"))
        assertTrue("Expected handoverReason in response: $text", text.contains("Agent decision required"))

        server.close()
        client.close()
    }
}
