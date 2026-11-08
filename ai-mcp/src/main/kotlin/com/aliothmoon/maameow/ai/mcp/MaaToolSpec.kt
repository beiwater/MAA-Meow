package com.aliothmoon.maameow.ai.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 单个 MCP 工具的声明式元数据。
 *
 * 这里只放「工具长什么样」，不放「工具怎么执行」：执行逻辑留在
 * [MaaMcpServerFactory.createServer] 的 handler 里。这样同一份元数据可以同时供
 * 三个消费方使用，避免三处各写一遍而漂移：
 * 1. MCP server 的 `tools/list`（[MaaMcpServerFactory.createServer]）；
 * 2. 应用内助手的系统提示词（`MaaChatSystemPrompt`）；
 * 3. OpenAI 兼容接口的 `tools[]`（`OpenAiCompatibleChatClient`）。
 */
data class MaaToolSpec(
    val name: String,
    val description: String,
    val properties: JsonObject,
    val required: List<String> = emptyList(),
)

/**
 * 全部 20 个 MaaMeow 本机 MCP 工具的唯一权威列表。
 *
 * 顺序即系统提示词中的展示顺序：先「只读查询」，再「配置读写」，再「执行」，
 * 最后「诊断/应急」。
 */
val maaToolSpecs: List<MaaToolSpec> = listOf(
    // ---- 状态与目录（只读） ----
    MaaToolSpec(
        name = "maa_status",
        description = "Query current MaaCompositionService execution state, active profile, and TaskChainStatusTracker task progress.",
        properties = buildJsonObject {},
    ),
    MaaToolSpec(
        name = "maa_list_task_types",
        description = "Return JSON Schema and default config for all TaskTypeInfo / TaskParamProvider task types (auto-generated from kotlinx.serialization descriptors).",
        properties = buildJsonObject {
            put("type_id", buildJsonObject {
                put("type", "string")
                put("description", "Optional task type filter (e.g. COMBAT, BASE, RECRUITING, AUTO_ROGUELIKE)")
            })
        },
    ),
    MaaToolSpec(
        name = "maa_list_profiles",
        description = "List all TaskProfiles in TaskChainState.",
        properties = buildJsonObject {},
    ),
    MaaToolSpec(
        name = "maa_get_profile",
        description = "Get full TaskProfile (TaskChainNode -> TaskParamProvider) by profile_id (or active profile if omitted).",
        properties = buildJsonObject {
            put("profile_id", buildJsonObject {
                put("type", "string")
                put("description", "Profile ID to fetch; defaults to active profile if empty")
            })
        },
    ),

    // ---- 配置读写 ----
    MaaToolSpec(
        name = "maa_create_profile",
        description = "Validate against TaskParamProvider schema and Arknights stable IDs, then create and persist a new TaskProfile in TaskChainState.",
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
    MaaToolSpec(
        name = "maa_update_profile",
        description = "Validate and update an existing TaskProfile in TaskChainState; unknown fields or invalid stage/operator IDs are rejected.",
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
    MaaToolSpec(
        name = "maa_delete_profile",
        description = "Delete a TaskProfile by profile_id (requires confirm=true; cannot delete the last remaining profile).",
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

    // ---- 执行 ----
    MaaToolSpec(
        name = "maa_run_profile",
        description = "Asynchronously launch a TaskProfile via MaaCompositionService.start and immediately return run_id. Poll maa_status for progress. 'force' aligns with extra_force_start.",
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
    MaaToolSpec(
        name = "maa_stop",
        description = "Stop the currently running task chain or copilot via MaaCompositionService.stop.",
        properties = buildJsonObject {},
    ),
    MaaToolSpec(
        name = "maa_save_script",
        description = "Save effective run parameters / recorded task chain as a versioned TaskProfile script compatible with ConfigBackupManager.",
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
    MaaToolSpec(
        name = "maa_list_scripts",
        description = "List saved TaskProfile scripts and any active recorded draft.",
        properties = buildJsonObject {},
    ),
    MaaToolSpec(
        name = "maa_run_script",
        description = "Asynchronously execute a saved script (TaskProfile) by profile_id.",
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

    // ---- 诊断 ----
    MaaToolSpec(
        name = "maa_get_logs",
        description = "Fetch recent session logs from MaaSessionLogger (redacted by default).",
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
    MaaToolSpec(
        name = "maa_screenshot",
        description = "Capture a current game frame via FrameSnapshotter / MaaCoreService.GetImage (disabled by default in settings for privacy).",
        properties = buildJsonObject {},
    ),
    MaaToolSpec(
        name = "maa_load_copilot",
        description = "Load and start a PRTS copilot code (maa://...) or JSON via CopilotManager and CopilotConfig.",
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

    // ---- 知识检索 ----
    MaaToolSpec(
        name = "maa_search_wiki",
        description = "Search Arknights wikis in real-time for operator stats, skills, talents, stage walkthroughs, enemy attributes, lore, or drop rates. Supports PRTS Wiki (prts.wiki) and Arknights Terra Wiki (arknights.wiki.gg).",
        properties = buildJsonObject {
            put("query", buildJsonObject {
                put("type", "string")
                put("description", """Search keywords, e.g. "银灰", "SilverAsh", "1-7", "纯金年代", "萨米肉鸽 坍缩范式"""")
            })
            put("limit", buildJsonObject {
                put("type", "integer")
                put("description", "Max results to return, default 5, range 1-10")
            })
            put("wiki", buildJsonObject {
                put("type", "string")
                put("description", """Wiki source: "auto" (default: fast wiki.gg for English queries, PRTS with wiki.gg fallback for Chinese), "prts" (PRTS Wiki), or "wiki_gg" (Arknights Terra Wiki)""")
            })
        },
        required = listOf("query"),
    ),
    MaaToolSpec(
        name = "maa_get_user_data",
        description = "Query the player's personal owned operators (OperBox: levels, elite promotion E0/E1/E2, potential, skill masteries, modules) and depot materials inventory (Depot).",
        properties = buildJsonObject {
            put("category", buildJsonObject {
                put("type", "string")
                put("description", """Query category: "operators" (owned operators), "depot" (depot materials), or "all" (default, both)""")
            })
            put("filter", buildJsonObject {
                put("type", "string")
                put("description", "Operator name or material name/ID to filter")
            })
        },
    ),
    MaaToolSpec(
        name = "maa_get_wiki_article",
        description = "Fetch the full content of an Arknights Wiki article (PRTS Wiki or Arknights Terra Wiki) converted into clean Markdown text.",
        properties = buildJsonObject {
            put("title", buildJsonObject {
                put("type", "string")
                put("description", """Article title, e.g. "银灰", "SilverAsh", "1-7", "纯金年代"""")
            })
            put("wiki", buildJsonObject {
                put("type", "string")
                put("description", """Wiki source: "auto" (default: wiki.gg for pure English titles, PRTS for Chinese with wiki.gg fallback), "prts" (PRTS Wiki), or "wiki_gg" (Arknights Terra Wiki)""")
            })
        },
        required = listOf("title"),
    ),

    // ---- 应急输入 ----
    MaaToolSpec(
        name = "maa_click",
        description = "Send a targeted emergency click to game coordinates (x, y) (standard 1280x720 space) to dismiss unexpected popups, announcements, network dialogs, or handle stuck screens.",
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
    MaaToolSpec(
        name = "maa_back_to_home",
        description = "Execute MAA Core's native BackToHome navigation routine to return from any stuck sub-screen or modal dialog back to the main lobby.",
        properties = buildJsonObject {},
    ),
)
