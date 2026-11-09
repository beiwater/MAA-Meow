package com.aliothmoon.maameow.ai.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
        name = "maa_execute_adhoc",
        description = "Execute an ad-hoc custom chain of tasks on the fly without persisting a TaskProfile. Takes a flat array of simplified task steps.",
        properties = buildJsonObject {
            put("tasks", buildJsonObject {
                put("type", "array")
                put("description", "Array of task step specifications (e.g. Fight, Infrast, Recruit, Mall, Award, Roguelike, Reclamation)")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("description", "Task type: Fight, Infrast, Recruit, Mall, Award, Roguelike, Reclamation")
                        })
                        put("stage", buildJsonObject {
                            put("type", "string")
                            put("description", "Stage code for Fight (e.g. '1-7', 'LS-6')")
                        })
                        put("stage1", buildJsonObject {
                            put("type", "string")
                            put("description", "Alias for stage")
                        })
                        put("times", buildJsonObject {
                            put("type", "integer")
                            put("description", "Battle repetitions for Fight (default 1)")
                        })
                        put("max_times", buildJsonObject {
                            put("type", "integer")
                            put("description", "Alias for times")
                        })
                        put("medicine", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max sanity medicines to use (capped at 5 for safety)")
                        })
                        put("use_medicine", buildJsonObject {
                            put("type", "integer")
                            put("description", "Alias for medicine")
                        })
                        put("mode", buildJsonObject {
                            put("type", "string")
                            put("description", "Infrast mode: 'shift_and_harvest' (default) or 'harvest'")
                        })
                        put("max_star", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max star rating for Recruit (e.g. 4, 5)")
                        })
                        put("auto_confirm", buildJsonObject {
                            put("type", "boolean")
                            put("description", "Auto confirm recruitment tags for Recruit")
                        })
                        put("buy_free", buildJsonObject {
                            put("type", "boolean")
                            put("description", "For Mall, collect free items / buy only discounted goods")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("type")) })
                })
            })
            put("force", buildJsonObject {
                put("type", "boolean")
                put("description", "If true, stop any currently running session before starting (extra_force_start semantics, default true)")
            })
        },
        required = listOf("tasks"),
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
        description = "Read cached owned operators and depot materials, with snapshot timestamps and explicit provenance. Set refresh=true when data is missing, stale, or changed by upgrades/spending; this reuses existing recognition/import functions and waits for their result rather than asking you to poll. Native recognition changes game pages; do not run UI actions concurrently. Startup success is not refresh success. A cancelled wait does not forcibly stop an already-started native task. Yituliu import freshness means download time, not in-game observation time.",
        properties = buildJsonObject {
            put("category", buildJsonObject {
                put("type", "string")
                put("description", """Query category: "operators" (owned operators), "depot" (depot materials), or "all" (default, both)""")
            })
            put("filter", buildJsonObject {
                put("type", "string")
                put("description", "Operator name or material name/ID to filter")
            })
            put("refresh", buildJsonObject {
                put("type", "boolean")
                put("description", "Default false: cached read only. True: request and await an update of the selected category; inspect refresh status and category update flags before treating the result as newly observed.")
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
        description = "Click game coordinates (x, y) in standard 1280x720 space to dismiss popups or perform emergency single-step UI navigation. Valid coordinate ranges: x in 0..1279, y in 0..719. Coordinates outside this range are strictly rejected. For multi-step UI navigation, use maa_execute_ui_plan instead of guessing coordinates.",
        properties = buildJsonObject {
            put("x", buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", 1279)
                put("description", "X coordinate in standard 1280x720 game space (0..1279)")
            })
            put("y", buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
                put("maximum", 719)
                put("description", "Y coordinate in standard 1280x720 game space (0..719)")
            })
        },
        required = listOf("x", "y"),
    ),
    MaaToolSpec(
        name = "maa_back_to_home",
        description = "Execute MAA Core's native BackToHome navigation routine to return from any stuck sub-screen or modal dialog back to the main lobby.",
        properties = buildJsonObject {},
    ),

    // ---- 视觉智能执行 (Jev) ----
    MaaToolSpec(
        name = "maa_visual_click",
        description = "Single-step visual diagnosis or emergency intervention only. For a normal multi-step workflow, use maa_execute_ui_plan instead of a series of maa_visual_click calls. Locates a visible target and dispatches one click; returns a post-dispatch frame when available, not proof that the business goal succeeded.",
        properties = buildJsonObject {
            put("instruction", buildJsonObject {
                put("type", "string")
                put("description", "Natural language visual target description (e.g. '升级按钮', '确认', '银灰头像')")
            })
        },
        required = listOf("instruction"),
    ),
    MaaToolSpec(
        name = "maa_execute_ui_plan",
        description = "Execute an ordered UI plan locally with Jev. Supply the goal and natural-language action targets in steps; the executor handles screenshots, visual localization, coordinates and waiting without returning to the main model for each click. Repeat is supported for repeated actions. Normally omit region, custom timing and per-click verification. Use an observable business post_condition only where necessary. On failure, use completed_steps and the returned current screenshot to resume rather than replaying completed work.",
        properties = buildJsonObject {
            put("goal", buildJsonObject {
                put("type", "string")
                put("description", "Overall high-level objective of this UI plan (e.g. 'Upgrade Kroos to Lv7 and adjust team formation')")
            })
            put("steps", buildJsonObject {
                put("type", "array")
                put("description", "List of structured UI action steps to execute sequentially")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("action", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("click"))
                                add(JsonPrimitive("swipe"))
                                add(JsonPrimitive("scroll"))
                                add(JsonPrimitive("wait"))
                                add(JsonPrimitive("verify"))
                            })
                            put("description", "Action type to perform: click, swipe, scroll, wait, verify")
                        })
                        put("target", buildJsonObject {
                            put("type", "string")
                            put("description", "Natural language visual description of the target UI element or verification target")
                        })
                        put("region", buildJsonObject {
                            put("type", "object")
                            put("description", "Optional normalized search region object with top, left, bottom, right in 0.0..1.0")
                            put("properties", buildJsonObject {
                                put("top", buildJsonObject { put("type", "number"); put("minimum", 0.0); put("maximum", 1.0) })
                                put("left", buildJsonObject { put("type", "number"); put("minimum", 0.0); put("maximum", 1.0) })
                                put("bottom", buildJsonObject { put("type", "number"); put("minimum", 0.0); put("maximum", 1.0) })
                                put("right", buildJsonObject { put("type", "number"); put("minimum", 0.0); put("maximum", 1.0) })
                            })
                            put("required", buildJsonArray {
                                listOf("top", "left", "bottom", "right").forEach { add(JsonPrimitive(it)) }
                            })
                        })
                        put("direction", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                add(JsonPrimitive("up"))
                                add(JsonPrimitive("down"))
                                add(JsonPrimitive("left"))
                                add(JsonPrimitive("right"))
                            })
                            put("description", "Swipe/scroll direction: up, down, left, right")
                        })
                        put("repeat", buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 10)
                            put("description", "Repetition count for this step (default 1, max 10)")
                        })
                        put("wait_after_ms", buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 10000)
                            put("description", "Wait duration in ms after executing this action (default 300ms)")
                        })
                        put("checkpoint", buildJsonObject {
                            put("type", "boolean")
                            put("description", "If true, enforces a checkpoint verification condition before proceeding")
                        })
                        put("post_condition", buildJsonObject {
                            put("type", "string")
                            put("description", "A final state directly visible in the current screenshot, checked via Jev Noul (e.g. 'Is Kroos Level 7 on her details screen?'). Do not ask whether a button was pressed or an earlier action occurred; a still image cannot prove that. Ordinary clicks need no extra verification step.")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("action"))
                    })
                })
            })
        },
        required = listOf("goal", "steps"),
    ),

    // ---- Agent 工作区 ----
    MaaToolSpec(
        name = "maa_save_workspace_file",
        description = "Save or update a script, note, or JSON workflow in the Agent's persistent workspace (upsert).",
        properties = buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "File name (e.g. 'daily_farm.json', 'custom_macro.txt')")
            })
            put("content", buildJsonObject {
                put("type", "string")
                put("description", "Content of the file")
            })
            put("description", buildJsonObject {
                put("type", "string")
                put("description", "Optional summary or description of what this workflow/file does")
            })
        },
        required = listOf("name", "content"),
    ),
    MaaToolSpec(
        name = "maa_read_workspace_file",
        description = "Read a script, workflow JSON, or note from the Agent's persistent workspace by file name.",
        properties = buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "File name to read")
            })
        },
        required = listOf("name"),
    ),
    MaaToolSpec(
        name = "maa_list_workspace_files",
        description = "List all files saved in the Agent's persistent workspace with metadata.",
        properties = buildJsonObject {},
    ),
    MaaToolSpec(
        name = "maa_run_workspace_script",
        description = "Execute a saved ad-hoc script/workflow from the Agent's workspace by name. Launches tasks via MaaCompositionService.",
        properties = buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "Script file name to execute")
            })
            put("force", buildJsonObject {
                put("type", "boolean")
                put("description", "Force start if another task is running (default true)")
            })
        },
        required = listOf("name"),
    ),
    MaaToolSpec(
        name = "maa_delete_workspace_file",
        description = "Delete a file from the Agent's persistent workspace by name.",
        properties = buildJsonObject {
            put("name", buildJsonObject {
                put("type", "string")
                put("description", "File name to delete")
            })
        },
        required = listOf("name"),
    ),
)
