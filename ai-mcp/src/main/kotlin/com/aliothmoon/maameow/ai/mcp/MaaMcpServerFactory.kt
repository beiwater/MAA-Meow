package com.aliothmoon.maameow.ai.mcp

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

object MaaMcpServerFactory {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Creates an official MCP [Server] instance with all tool handlers registered.
     */
    fun createServer(
        bridge: MaaToolBridge,
        screenshotEnabled: () -> Boolean = { false },
        version: String = "1.0.0",
    ): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "maameow-mcp",
                version = version,
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        // 1. maa_status
        server.addTool(
            name = "maa_status",
            description = "Query current MaaCompositionService execution state, active profile, and TaskChainStatusTracker task progress.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            val status = bridge.getStatus()
            okResult(json.encodeToString(status))
        }

        // 2. maa_list_task_types
        server.addTool(
            name = "maa_list_task_types",
            description = "Return JSON Schema and default config for all TaskTypeInfo / TaskParamProvider task types (auto-generated from kotlinx.serialization descriptors).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("type_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional task type filter (e.g. COMBAT, BASE, RECRUITING, AUTO_ROGUELIKE)")
                    })
                },
            ),
        ) { req ->
            val filter = (req.arguments?.get("type_id") as? JsonPrimitive)?.contentOrNull
            val specs = if (filter.isNullOrBlank()) {
                bridge.taskSpecs
            } else {
                bridge.taskSpecs.filter {
                    it.typeId.equals(filter, ignoreCase = true) ||
                        it.configClassName.equals(filter, ignoreCase = true)
                }
            }
            val catalog = TaskSchemaGenerator.generateCatalogJson(specs)
            okResult(json.encodeToString(catalog))
        }

        // 3. maa_list_profiles
        server.addTool(
            name = "maa_list_profiles",
            description = "List all TaskProfiles in TaskChainState.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            val profiles = bridge.listProfiles()
            okResult(json.encodeToString(profiles))
        }

        // 4. maa_get_profile
        server.addTool(
            name = "maa_get_profile",
            description = "Get full TaskProfile (TaskChainNode -> TaskParamProvider) by profile_id (or active profile if omitted).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("profile_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Profile ID to fetch; defaults to active profile if empty")
                    })
                },
            ),
        ) { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val targetId = profileId.ifBlank { bridge.getStatus().activeProfileId }
            val profile = bridge.getProfile(targetId)
                ?: return@addTool errorResult("Profile not found: $targetId")
            okResult(json.encodeToString(profile))
        }

        // 5. maa_create_profile
        server.addTool(
            name = "maa_create_profile",
            description = "Validate against TaskParamProvider schema and Arknights stable IDs, then create and persist a new TaskProfile in TaskChainState.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Profile display name (max 20 chars)")
                    })
                    put("chain", buildJsonObject {
                        put("type", "array")
                        put("description", "List of TaskChainNode objects ({name, enabled, config: {type, ...}})")
                    })
                },
                required = listOf("name", "chain"),
            ),
        ) { req ->
            val name = (req.arguments?.get("name") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@addTool errorResult("Missing required 'name'")
            val chain = req.arguments?.get("chain") as? JsonArray
                ?: return@addTool errorResult("Missing or invalid 'chain' array")

            val validation = bridge.validator.validateChainJson(chain)
            if (!validation.valid) {
                return@addTool errorResult(json.encodeToString(validation))
            }

            bridge.createProfile(name, chain).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to create profile") },
            )
        }

        // 6. maa_update_profile
        server.addTool(
            name = "maa_update_profile",
            description = "Validate and update an existing TaskProfile in TaskChainState; unknown fields or invalid stage/operator IDs are rejected.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("profile_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Target profile ID")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional new profile name")
                    })
                    put("chain", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional updated TaskChainNode array")
                    })
                },
                required = listOf("profile_id"),
            ),
        ) { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (profileId.isEmpty()) return@addTool errorResult("Missing required 'profile_id'")
            val name = (req.arguments?.get("name") as? JsonPrimitive)?.contentOrNull?.trim()
            val chain = req.arguments?.get("chain") as? JsonArray

            if (chain != null) {
                val validation = bridge.validator.validateChainJson(chain)
                if (!validation.valid) {
                    return@addTool errorResult(json.encodeToString(validation))
                }
            }

            bridge.updateProfile(profileId, name, chain).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to update profile") },
            )
        }

        // 7. maa_delete_profile
        server.addTool(
            name = "maa_delete_profile",
            description = "Delete a TaskProfile by profile_id (requires confirm=true; cannot delete the last remaining profile).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("profile_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Profile ID to delete")
                    })
                    put("confirm", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Must be true to confirm deletion")
                    })
                },
                required = listOf("profile_id", "confirm"),
            ),
        ) { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val confirm = (req.arguments?.get("confirm") as? JsonPrimitive)?.booleanOrNull ?: false
            if (!confirm) {
                return@addTool errorResult("Deletion rejected: 'confirm' must be explicitly set to true")
            }
            bridge.deleteProfile(profileId).fold(
                onSuccess = { okResult(buildJsonObject { put("deleted", it); put("profile_id", profileId) }.toString()) },
                onFailure = { errorResult(it.message ?: "Failed to delete profile") },
            )
        }

        // 8. maa_run_profile
        server.addTool(
            name = "maa_run_profile",
            description = "Asynchronously launch a TaskProfile via MaaCompositionService.start and immediately return run_id. Poll maa_status for progress. 'force' aligns with extra_force_start.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("profile_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Profile ID to run; defaults to active profile if omitted")
                    })
                    put("force", buildJsonObject {
                        put("type", "boolean")
                        put("description", "If true, stop any currently running session before starting (extra_force_start semantics)")
                    })
                },
            ),
        ) { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val force = (req.arguments?.get("force") as? JsonPrimitive)?.booleanOrNull ?: false
            bridge.runProfile(profileId, force).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to start profile") },
            )
        }

        // 9. maa_stop
        server.addTool(
            name = "maa_stop",
            description = "Stop the currently running task chain or copilot via MaaCompositionService.stop.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            bridge.stop().fold(
                onSuccess = { okResult(buildJsonObject { put("stopped", it) }.toString()) },
                onFailure = { errorResult(it.message ?: "Failed to stop") },
            )
        }

        // 10. maa_save_script
        server.addTool(
            name = "maa_save_script",
            description = "Save effective run parameters / recorded task chain as a versioned TaskProfile script compatible with ConfigBackupManager.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Script / profile name")
                    })
                    put("chain", buildJsonObject {
                        put("type", "array")
                        put("description", "Optional explicit chain; if omitted, snapshots the recorded/last effective run parameters")
                    })
                },
                required = listOf("name"),
            ),
        ) { req ->
            val name = (req.arguments?.get("name") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (name.isEmpty()) return@addTool errorResult("Missing required 'name'")
            val chainOverride = req.arguments?.get("chain") as? JsonArray
            if (chainOverride != null) {
                val validation = bridge.validator.validateChainJson(chainOverride)
                if (!validation.valid) {
                    return@addTool errorResult(json.encodeToString(validation))
                }
            }
            bridge.saveScript(name, fromRecordedOrLastRun = chainOverride == null, chainOverride = chainOverride).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to save script") },
            )
        }

        // 11. maa_list_scripts
        server.addTool(
            name = "maa_list_scripts",
            description = "List saved TaskProfile scripts and any active recorded draft.",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            val scripts = bridge.listScripts()
            okResult(json.encodeToString(scripts))
        }

        // 12. maa_run_script
        server.addTool(
            name = "maa_run_script",
            description = "Asynchronously execute a saved script (TaskProfile) by profile_id.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("profile_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Script / profile ID to execute")
                    })
                    put("force", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Force start if another task is running")
                    })
                },
                required = listOf("profile_id"),
            ),
        ) { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (profileId.isEmpty()) return@addTool errorResult("Missing required 'profile_id'")
            val force = (req.arguments?.get("force") as? JsonPrimitive)?.booleanOrNull ?: false
            bridge.runScript(profileId, force).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to run script") },
            )
        }

        // 13. maa_get_logs
        server.addTool(
            name = "maa_get_logs",
            description = "Fetch recent session logs from MaaSessionLogger (redacted by default).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max log entries to return (default 100, max 500)")
                    })
                    put("redact", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Redact tokens/paths/account IDs (default true)")
                    })
                },
            ),
        ) { req ->
            val limit = ((req.arguments?.get("limit") as? JsonPrimitive)?.intOrNull ?: 100).coerceIn(1, 500)
            val redact = (req.arguments?.get("redact") as? JsonPrimitive)?.booleanOrNull ?: true
            val logs = bridge.getLogs(limit = limit, redactSensitive = redact)
            okResult(json.encodeToString(buildJsonObject {
                put("count", logs.size)
                put("redacted", redact)
                put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
            }))
        }

        // 14. maa_screenshot
        server.addTool(
            name = "maa_screenshot",
            description = "Capture a current game frame via FrameSnapshotter / MaaCoreService.GetImage (disabled by default in settings for privacy).",
            inputSchema = ToolSchema(properties = buildJsonObject {}),
        ) {
            if (!screenshotEnabled()) {
                return@addTool errorResult("maa_screenshot is disabled by default. Enable 'Allow AI Screenshot' in MaaMeow AI Control Settings first.")
            }
            bridge.captureScreenshotBase64().fold(
                onSuccess = { base64Jpeg ->
                    CallToolResult(
                        content = listOf(
                            ImageContent(
                                data = base64Jpeg,
                                mimeType = "image/jpeg",
                            ),
                        ),
                        isError = false,
                    )
                },
                onFailure = { errorResult(it.message ?: "Failed to capture screenshot") },
            )
        }

        // 15. maa_load_copilot
        server.addTool(
            name = "maa_load_copilot",
            description = "Load and start a PRTS copilot code (maa://...) or JSON via CopilotManager and CopilotConfig.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("copilot", buildJsonObject {
                        put("type", "string")
                        put("description", "PRTS copilot code (e.g. maa://12345) or copilot JSON string")
                    })
                    put("config", buildJsonObject {
                        put("type", "object")
                        put("description", "Optional CopilotConfig overrides (formation, addTrust, loopTimes, etc.)")
                    })
                },
                required = listOf("copilot"),
            ),
        ) { req ->
            val copilotInput = (req.arguments?.get("copilot") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (copilotInput.isEmpty()) return@addTool errorResult("Missing required 'copilot'")
            val configObj = req.arguments?.get("config") as? JsonObject
            bridge.loadAndRunCopilot(copilotInput, configObj).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to load/start copilot") },
            )
        }

        // 16. maa_search_wiki
        server.addTool(
            name = "maa_search_wiki",
            description = "Search Arknights wikis in real-time for operator stats, skills, talents, stage walkthroughs, enemy attributes, lore, or drop rates. Supports PRTS Wiki (prts.wiki) and Arknights Terra Wiki (arknights.wiki.gg).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Search keywords, e.g. \"银灰\", \"SilverAsh\", \"1-7\", \"纯金年代\", \"萨米肉鸽 坍缩范式\"")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "Max results to return, default 5, range 1-10")
                    })
                    put("wiki", buildJsonObject {
                        put("type", "string")
                        put("description", "Wiki source: \"auto\" (default: fast wiki.gg for English queries, PRTS with wiki.gg fallback for Chinese), \"prts\" (PRTS Wiki), or \"wiki_gg\" (Arknights Terra Wiki)")
                    })
                },
                required = listOf("query"),
            ),
        ) { req ->
            val query = (req.arguments?.get("query") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (query.isEmpty()) return@addTool errorResult("Missing required 'query'")
            val limit = (req.arguments?.get("limit") as? JsonPrimitive)?.intOrNull?.coerceIn(1, 10) ?: 5
            val wiki = (req.arguments?.get("wiki") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "auto"
            bridge.searchWiki(query, limit, wiki).fold(
                onSuccess = { okResult(it) },
                onFailure = { errorResult(it.message ?: "Failed to search wiki") },
            )
        }

        // 17. maa_get_user_data
        server.addTool(
            name = "maa_get_user_data",
            description = "Query the player's personal owned operators (OperBox: levels, elite promotion E0/E1/E2, potential, skill masteries, modules) and depot materials inventory (Depot).",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("category", buildJsonObject {
                        put("type", "string")
                        put("description", "Query category: \"operators\" (owned operators), \"depot\" (depot materials), or \"all\" (default, both)")
                    })
                    put("filter", buildJsonObject {
                        put("type", "string")
                        put("description", "Operator name or material name/ID to filter")
                    })
                },
                required = emptyList(),
            ),
        ) { req ->
            val category = (req.arguments?.get("category") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "all"
            val filter = (req.arguments?.get("filter") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            bridge.getUserData(category, filter).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to get user data") },
            )
        }

        // 18. maa_get_wiki_article
        server.addTool(
            name = "maa_get_wiki_article",
            description = "Fetch the full content of an Arknights Wiki article (PRTS Wiki or Arknights Terra Wiki) converted into clean Markdown text.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "Article title, e.g. \"银灰\", \"SilverAsh\", \"1-7\", \"纯金年代\"")
                    })
                    put("wiki", buildJsonObject {
                        put("type", "string")
                        put("description", "Wiki source: \"auto\" (default: wiki.gg for pure English titles, PRTS for Chinese with wiki.gg fallback), \"prts\" (PRTS Wiki), or \"wiki_gg\" (Arknights Terra Wiki)")
                    })
                },
                required = listOf("title"),
            ),
        ) { req ->
            val title = (req.arguments?.get("title") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) return@addTool errorResult("Missing required 'title'")
            val wiki = (req.arguments?.get("wiki") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "auto"
            bridge.getWikiArticle(title, wiki).fold(
                onSuccess = { okResult(it) },
                onFailure = { errorResult(it.message ?: "Failed to get wiki article") },
            )
        }

        // 19. maa_click
        server.addTool(
            name = "maa_click",
            description = "Send a targeted emergency click to game coordinates (x, y) (standard 1280x720 space) to dismiss unexpected popups, announcements, network dialogs, or handle stuck screens.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("x", buildJsonObject {
                        put("type", "integer")
                        put("description", "X coordinate in standard 1280x720 game space (0..1280)")
                    })
                    put("y", buildJsonObject {
                        put("type", "integer")
                        put("description", "Y coordinate in standard 1280x720 game space (0..720)")
                    })
                },
                required = listOf("x", "y"),
            ),
        ) { req ->
            val x = (req.arguments?.get("x") as? JsonPrimitive)?.intOrNull
                ?: return@addTool errorResult("Missing or invalid 'x' coordinate")
            val y = (req.arguments?.get("y") as? JsonPrimitive)?.intOrNull
                ?: return@addTool errorResult("Missing or invalid 'y' coordinate")
            bridge.click(x, y).fold(
                onSuccess = { okResult("Click dispatched successfully to ($x, $y)") },
                onFailure = { errorResult(it.message ?: "Failed to execute click") },
            )
        }

        // 20. maa_back_to_home
        server.addTool(
            name = "maa_back_to_home",
            description = "Execute MAA Core's native BackToHome navigation routine to return from any stuck sub-screen or modal dialog back to the main lobby.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {},
                required = emptyList(),
            ),
        ) { _ ->
            bridge.backToHome().fold(
                onSuccess = { okResult("BackToHome routine executed successfully") },
                onFailure = { errorResult(it.message ?: "Failed to execute BackToHome") },
            )
        }

        return server
    }

    /**
     * Starts an embedded Ktor CIO HTTP server hosting:
     * - Streamable HTTP MCP endpoint at `/mcp` (`mcpStreamableHttp`)
     * - Legacy SSE MCP endpoint at `/sse` (`mcp`) for backwards compatibility with older MCP hosts
     * - Health check at `/health`
     * Enforces Bearer token authentication on all MCP endpoints.
     */
    fun createHttpServer(
        host: String = "127.0.0.1",
        port: Int = 18080,
        bearerToken: String,
        bridge: MaaToolBridge,
        screenshotEnabled: () -> Boolean = { false },
        version: String = "1.0.0",
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        require(bearerToken.isNotBlank()) { "Bearer token must not be blank" }
        return embeddedServer(CIO, host = host, port = port) {
            install(SSE)
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.local.uri
                if (path == "/health") return@intercept
                val authHeader = call.request.headers[HttpHeaders.Authorization]
                val expected = "Bearer $bearerToken"
                if (authHeader != expected) {
                    call.respondText(
                        text = """{"error":"Unauthorized: valid Bearer token required"}""",
                        status = HttpStatusCode.Unauthorized,
                    )
                    finish()
                }
            }
            mcpStreamableHttp(path = "/mcp") {
                createServer(bridge, screenshotEnabled, version)
            }
            routing {
                get("/health") {
                    call.respondText("""{"status":"ok","service":"maameow-mcp"}""")
                }
                route("/sse") {
                    mcp {
                        createServer(bridge, screenshotEnabled, version)
                    }
                }
            }
        }
    }

    private fun okResult(text: String): CallToolResult = CallToolResult(
        content = listOf(TextContent(text = text)),
        isError = false,
    )

    private fun errorResult(message: String): CallToolResult = CallToolResult(
        content = listOf(TextContent(text = message)),
        isError = true,
    )
}
