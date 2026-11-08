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
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
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
     * 全部工具的元数据（唯一权威来源，见 [maaToolSpecs]）。
     * 本机 MCP 服务端、应用内助手的系统提示词、OpenAI `tools[]` 都从这里生成。
     */
    val toolSpecs: List<MaaToolSpec> get() = maaToolSpecs

    private val toolSpecsByName: Map<String, MaaToolSpec> by lazy {
        maaToolSpecs.associateBy { it.name }
    }

    /**
     * Creates an official MCP [Server] instance with all tool handlers registered.
     *
     * 工具的名称/描述/参数 schema 来自 [toolSpecs]，这里只保留执行逻辑，
     * 因此 `tools/list` 与系统提示词天然一致。
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

        // 元数据一律取自 toolSpecs；name 缺失会在这里立即失败，而不是悄悄少注册一个工具。
        fun addTool(
            name: String,
            handler: suspend ClientConnection.(CallToolRequest) -> CallToolResult,
        ) {
            val spec = toolSpecsByName.getValue(name)
            server.addTool(
                name = spec.name,
                description = spec.description,
                inputSchema = ToolSchema(
                    properties = spec.properties,
                    required = spec.required,
                ),
                handler = handler,
            )
        }

        addTool("maa_status") {
            val status = bridge.getStatus()
            okResult(json.encodeToString(status))
        }

        addTool("maa_list_task_types") { req ->
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

        addTool("maa_list_profiles") {
            val profiles = bridge.listProfiles()
            okResult(json.encodeToString(profiles))
        }

        addTool("maa_get_profile") { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val targetId = profileId.ifBlank { bridge.getStatus().activeProfileId }
            val profile = bridge.getProfile(targetId)
                ?: return@addTool errorResult("Profile not found: $targetId")
            okResult(json.encodeToString(profile))
        }

        addTool("maa_create_profile") { req ->
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

        addTool("maa_update_profile") { req ->
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

        addTool("maa_delete_profile") { req ->
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

        addTool("maa_run_profile") { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val force = (req.arguments?.get("force") as? JsonPrimitive)?.booleanOrNull ?: false
            bridge.runProfile(profileId, force).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to start profile") },
            )
        }

        addTool("maa_stop") {
            bridge.stop().fold(
                onSuccess = { okResult(buildJsonObject { put("stopped", it) }.toString()) },
                onFailure = { errorResult(it.message ?: "Failed to stop") },
            )
        }

        addTool("maa_save_script") { req ->
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

        addTool("maa_list_scripts") {
            val scripts = bridge.listScripts()
            okResult(json.encodeToString(scripts))
        }

        addTool("maa_run_script") { req ->
            val profileId = (req.arguments?.get("profile_id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (profileId.isEmpty()) return@addTool errorResult("Missing required 'profile_id'")
            val force = (req.arguments?.get("force") as? JsonPrimitive)?.booleanOrNull ?: false
            bridge.runScript(profileId, force).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to run script") },
            )
        }

        addTool("maa_get_logs") { req ->
            val limit = ((req.arguments?.get("limit") as? JsonPrimitive)?.intOrNull ?: 100).coerceIn(1, 500)
            val redact = (req.arguments?.get("redact") as? JsonPrimitive)?.booleanOrNull ?: true
            val logs = bridge.getLogs(limit = limit, redactSensitive = redact)
            okResult(json.encodeToString(buildJsonObject {
                put("count", logs.size)
                put("redacted", redact)
                put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
            }))
        }

        addTool("maa_screenshot") {
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

        addTool("maa_load_copilot") { req ->
            val copilotInput = (req.arguments?.get("copilot") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (copilotInput.isEmpty()) return@addTool errorResult("Missing required 'copilot'")
            val configObj = req.arguments?.get("config") as? JsonObject
            bridge.loadAndRunCopilot(copilotInput, configObj).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to load/start copilot") },
            )
        }

        addTool("maa_search_wiki") { req ->
            val query = (req.arguments?.get("query") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (query.isEmpty()) return@addTool errorResult("Missing required 'query'")
            val limit = (req.arguments?.get("limit") as? JsonPrimitive)?.intOrNull?.coerceIn(1, 10) ?: 5
            val wiki = (req.arguments?.get("wiki") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "auto"
            bridge.searchWiki(query, limit, wiki).fold(
                onSuccess = { okResult(it) },
                onFailure = { errorResult(it.message ?: "Failed to search wiki") },
            )
        }

        addTool("maa_get_user_data") { req ->
            val category = (req.arguments?.get("category") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "all"
            val filter = (req.arguments?.get("filter") as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            bridge.getUserData(category, filter).fold(
                onSuccess = { okResult(json.encodeToString(it)) },
                onFailure = { errorResult(it.message ?: "Failed to get user data") },
            )
        }

        addTool("maa_get_wiki_article") { req ->
            val title = (req.arguments?.get("title") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) return@addTool errorResult("Missing required 'title'")
            val wiki = (req.arguments?.get("wiki") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "auto"
            bridge.getWikiArticle(title, wiki).fold(
                onSuccess = { okResult(it) },
                onFailure = { errorResult(it.message ?: "Failed to get wiki article") },
            )
        }

        addTool("maa_click") { req ->
            val x = (req.arguments?.get("x") as? JsonPrimitive)?.intOrNull
                ?: return@addTool errorResult("Missing or invalid 'x' coordinate")
            val y = (req.arguments?.get("y") as? JsonPrimitive)?.intOrNull
                ?: return@addTool errorResult("Missing or invalid 'y' coordinate")
            bridge.click(x, y).fold(
                onSuccess = { okResult("Click dispatched successfully to ($x, $y)") },
                onFailure = { errorResult(it.message ?: "Failed to execute click") },
            )
        }

        addTool("maa_back_to_home") { _ ->
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
