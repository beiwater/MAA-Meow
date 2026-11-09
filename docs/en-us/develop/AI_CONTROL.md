# AI Control & MCP Integration Guide

This document describes MaaMeow's "Brain-Hand Separation" AI control architecture, MCP tool table, security model, anti-hallucination validation gate, and integration with Dify, RAGFlow, and Claude Desktop.

---

## 1. Architecture: Brain-Hand Separation

- **Hand (Android Device, `:ai-mcp` + `AiControlService`)**:
  - Uses the official `io.modelcontextprotocol:kotlin-sdk-server` + Ktor CIO `mcpStreamableHttp` to expose standard MCP HTTP endpoints (`/mcp` and legacy `/sse`).
  - Every tool handler is a thin wrapper over existing services: `MaaCompositionService`, `TaskChainState`, `TaskChainStatusTracker`, `CopilotManager`, `MaaSessionLogger`, and `FrameSnapshotter`.
  - **Excluded from APK**: vector databases, embedding models, new ONNX dependencies, custom chunkers, or hand-rolled JSON-RPC framing.
- **Brain (PC / NAS Server, Dify or RAGFlow)**:
  - Hosts the Arknights-specific knowledge base (`scripts/build_rag_corpus.py` extracts entity-keyed chunks from `MaaResource`, PRTS, `ArknightsGameData`, `item_index.json`, `prts.plus`, and Penguin Stats) and the Agent tool loop.
- **In-App Chat (Thin Client)**:
  - Two interchangeable backends: OpenAI-compatible `POST {baseUrl}/chat/completions` by default (`OpenAiCompatibleChatClient`), or the Dify app API `/v1/chat-messages` (`DifyChatClient`).
  - Both stream over SSE and reuse the existing `OkHttpClient` and Markdown renderer; the OpenAI-compatible backend executes the local MCP tools the model requests (see 5.7).

---

## 2. MCP Tool Table

| Tool Name | Arguments | Behavior | Underlying Implementation |
|---|---|---|---|
| `maa_status` | None | Returns execution state (`IDLE`/`STARTING`/`RUNNING`/`STOPPING`/`ERROR`), `activeProfileId`, `currentRunId`, and task progress | `MaaCompositionService.state` + `TaskChainStatusTracker` |
| `maa_list_task_types` | `type_id?` | Auto-generates JSON Schema and default values from `TaskTypeInfo` + `TaskParamProvider` `SerialDescriptor` | `TaskSchemaGenerator` + `TaskTypeInfo` |
| `maa_list_profiles` | None | Lists all `TaskProfile` entries | `TaskChainState.profiles` |
| `maa_get_profile` | `profile_id?` | Returns the full `TaskProfile` (`TaskChainNode` -> `TaskParamProvider`) | `TaskChainState` |
| `maa_create_profile` | `name`, `chain` | Validates schema fields and stable Arknights IDs via `ArknightsEntityValidator`, then persists to `TaskChainState` | `ArknightsEntityValidator` + `TaskChainState.saveOrUpdateCustomProfile` |
| `maa_update_profile` | `profile_id`, `name?`, `chain?` | Validates unknown fields and entity IDs, then updates the profile | `ArknightsEntityValidator` + `TaskChainState.saveOrUpdateCustomProfile` |
| `maa_delete_profile` | `profile_id`, `confirm=true` | Deletes a profile when `confirm=true` (blocks deleting the last profile) | `TaskChainState.removeProfile` |
| `maa_run_profile` | `profile_id?`, `force?` | **Asynchronously** launches the profile and immediately returns `run_id`; `force=true` matches `extra_force_start` | `StartTaskChainUseCase` + `MaaCompositionService.start` |
| `maa_stop` | None | Stops the active task chain or copilot session | `MaaCompositionService.stop` |
| `maa_save_script` | `name`, `chain?` | Converts recorded or last-run effective `List<MaaTaskParams>` into a `TaskProfile` compatible with `ConfigBackupManager` | `AiScriptRecorder` + `TaskChainState` |
| `maa_list_scripts` | None | Lists recorded drafts and saved `TaskProfile` scripts | `AiScriptRecorder` + `TaskChainState` |
| `maa_run_script` | `profile_id`, `force?` | Asynchronously executes a saved script and returns `run_id` | `StartTaskChainUseCase` |
| `maa_get_logs` | `limit?` (100), `redact?` (true) | Returns session logs with sensitive tokens/paths/IDs redacted by default | `MaaSessionLogger` + `LogRedactor` |
| `maa_screenshot` | None | Captures a JPEG game frame as Base64 (**disabled by default** in settings) | `FrameSnapshotter` (`RemoteService.captureFrameJpeg`) |
| `maa_load_copilot` | `copilot`, `config?` | Parses a `maa://` / `prts://` code or JSON and starts copilot asynchronously | `CopilotManager` + `MaaCompositionService.startCopilot` |
| `maa_search_wiki` | `query`, `wiki?` ("auto"\|"prts"\|"wiki_gg"), `limit?` (5) | Searches Arknights PRTS Wiki (CN native) or Arknights Terra Wiki (`arknights.wiki.gg`, Cloudflare CDN for fast overseas access) with auto-routing & timeout fallback | MediaWiki API (PRTS / wiki.gg) + `OkHttpClient` |
| `maa_get_user_data` | `category?` ("operators"\|"depot"\|"all", default "all"), `filter?` (operator or item name/ID), `refresh?` (boolean, default false) | Reads cached snapshots with timestamps and provenance by default; `refresh=true` reuses existing recognition/import and awaits results. Inspect update status; this is not live data (see 5.10) | `OperBoxRepository` + `DepotRepository` + `OperBoxYituliuSync` + `MaaCompositionService` |
| `maa_get_wiki_article` | `title`, `wiki?` ("auto"\|"prts"\|"wiki_gg", default "auto") | Fetches full wiki article as clean Markdown with dual-source intelligent routing and overseas fallback | MediaWiki API (`prop=extracts&explaintext=1` / `parse`) + `OkHttpClient` |
| `maa_click` | `x`, `y` | Dispatches an emergency click; strictly rejects coordinates outside x=0..1279, y=0..719 rather than clamping them. Success means dispatched, not verified | `RemoteServiceManager` + `MaaCoreService.AsyncClick` |
| `maa_back_to_home` | None | Executes MAA Core's native BackToHome navigation routine to return from any stuck sub-screen to the main lobby | `RemoteServiceManager` + `MaaCoreService.BackToHome` |
| `maa_visual_click` | `instruction` | Single-step diagnosis/emergency use: Jev locates and dispatches one click, returning a freshly captured post-dispatch frame when available; not a guarantee of goal completion | `JevSystemOneClient` + `AndroidMaaToolBridge` |
| `maa_execute_ui_plan` | `goal`, `steps` | Executes multiple click, swipe/scroll, wait and verify steps with optional focus regions and checkpoints; returns progress, dispatch/verification counts and a screenshot | `UiPlanExecutor` + `JevSystemOneClient` |

---

## 3. Security & Privacy Model

1. **Disabled by Default, Zero Cold-Start Overhead**:
   - `aiControlEnabled` defaults to `false`. When disabled, `AiControlService` is never started and Ktor is never initialized.
2. **Loopback Default + Mandatory Bearer Token**:
   - Listens on `127.0.0.1:18080` by default. Use `adb forward tcp:18080 tcp:18080` from PC hosts.
   - All `/mcp` and `/sse` requests require `Authorization: Bearer <token>`.
   - Enabling `0.0.0.0` LAN listening requires explicit confirmation in the UI.
3. **Screenshot & Log Redaction**:
   - `maa_screenshot` is disabled by default.
   - `maa_get_logs` redacts tokens, account names, Penguin IDs, MirrorChyan CDKs, and device file paths by default.
4. **Anti-Hallucination Gate (`ArknightsEntityValidator`)**:
   - Stage codes, operator names, item IDs, task types, and config fields must match `MaaResource` stable IDs and `TaskParamProvider` descriptors; unknown values are rejected with closest candidate suggestions.
5. **Auto-Update Protection**:
   - Enabling AI Control disables `autoCheckUpdate` and `autoDownloadUpdate` so official builds do not overwrite the AI build.

6. **Emergency Touch & Navigation Boundaries (`maa_click` & `maa_back_to_home`)**:
   - `maa_click` rejects coordinates outside x=0..1279, y=0..719. A native dispatch failure must not be reported as success.
   - Designed exclusively for unexpected emergency events (closing banner popups, daily login reward dialogs, reconnect buttons), never for manual combat or macro exploitation.
   - `maa_back_to_home` reuses MAA Core's robust native state machine with built-in timeouts.
---

## 4. Client Setup & RAG Evaluation

### 4.1 Port Forwarding via ADB

```bash
adb forward tcp:18080 tcp:18080
```

### 4.2 Claude Desktop / MCP Inspector Configuration

```json
{
  "mcpServers": {
    "maameow": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:18080/mcp",
      "headers": {
        "Authorization": "Bearer YOUR_BEARER_TOKEN"
      }
    }
  }
}
```

### 4.3 Corpus Generation & 32-Question Benchmark

```bash
python scripts/build_rag_corpus.py
python scripts/eval_rag_benchmark.py
```

---

## 5. In-App Chat (OpenAI-Compatible / Dify Dual Backend)

### 5.1 Entry Points

| Entry | Location | Condition |
|---|---|---|
| Bottom tab | The second bottom-bar tab `AI` (`BottomNavTab.AI`, route `Routes.AI_CHAT`); `BottomNavTab.visibleTabs` filters it by the switch, and `resolvePageForTabs` remaps the selected page by route when the tab list changes | Only while `aiControlEnabled` is on |
| Home card | `AI 对话` card on the Home screen; subtitle is `vendor · model` (from `aiChatTargetLabel`), or `未配置模型密钥` when no key is set | Same as above |
| Task / Schedule cards | Compact cards (`compact = true`) at the top of the background-task page and the schedule list | Same as above |
| Settings entry | Settings → AI Control & MCP Service → chat icon in the top bar (`Icons.Rounded.Chat`) and the `打开应用内 AI 助手对话` button at the bottom; both switch to the AI tab | Same as above |

The chat screen is one of the bottom-bar tabs (`AiChatView`) and offers `清空当前会话`. While the IME is visible `MainScreen` hides the bottom bar, and the chat screen uses `Scaffold(contentWindowInsets = WindowInsets(0))` + `imePadding()` to avoid the keyboard.

### 5.2 Backends

| Backend | Protocol | Conversation state | Tool-call cards | Stop |
|---|---|---|---|---|
| `OPENAI` (default) | `POST {baseUrl}/chat/completions`, reading `choices[0].delta.content`, `delta.tool_calls[]`, `delta.reasoning(_content)` and `[DONE]` | Stateless protocol; the in-memory message list is replayed; tool-loop messages live only inside the current request | Yes (local tool loop via `MaaToolBridgeInvoker`) | Cancel the coroutine (`ensureActive()` before the next tool) and `call.cancel()` the HTTP call |
| `DIFY` | `POST {baseUrl}/chat-messages` (`response_mode=streaming`) | Kept via `conversation_id` | Yes (`AiToolCallCard`, from `agent_thought`) | Extra `POST {baseUrl}/chat-messages/{task_id}/stop` |

`OpenAiCompatibleChatClient` also exposes `vendorLabel()`, which guesses the display name from the Base URL host (`deepseek`→DeepSeek, `openai`→OpenAI, `moonshot`/`kimi`→Kimi, `siliconflow`→SiliconFlow, `bigmodel`/`zhipu`→GLM, `dashscope`/`aliyun`→Qwen, otherwise the host itself). Keys are redacted in logs by the existing rules.

### 5.3 Settings Keys and Defaults

DataStore keys declared in `domain/models/AppSettings.kt`:

| Key | Meaning | Default |
|---|---|---|
| `ai_chat_provider` | Chat backend, `OPENAI` / `DIFY` (unparseable values fall back to `OPENAI`) | `OPENAI` |
| `ai_openai_base_url` | OpenAI-compatible Base URL | `https://api.deepseek.com/v1` |
| `ai_openai_api_key` | API key; when empty the chat screen shows the setup card and no request is sent | `""` (empty) |
| `ai_openai_model` | Model name | `deepseek-chat` |

The Dify backend keeps its existing three keys: `ai_dify_base_url` (default `http://127.0.0.1/v1`), `ai_dify_api_key` (default empty), `ai_dify_user` (default empty).

### 5.4 Common Base URLs

| Provider | Base URL | Notes |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | Default value |
| OpenAI | `https://api.openai.com/v1` | |
| Kimi (Moonshot) | `https://api.moonshot.cn/v1` | |
| SiliconFlow | `https://api.siliconflow.cn/v1` | |
| Ollama (on your PC) | `http://<PC-IP>:11434/v1` | The phone and PC must share a LAN and the port must be reachable. Note that `network_security_config.xml` only permits cleartext HTTP to `127.0.0.1` / `localhost` / `::1`, so `http://<PC-IP>` is blocked by the platform network policy; prefer `adb reverse tcp:11434 tcp:11434` and use `http://127.0.0.1:11434/v1`, or put an HTTPS reverse proxy in front of Ollama |

A full URL already ending in `/chat/completions` is tolerated; ending with `/v1` is recommended, otherwise a `404` is likely (the error message says so explicitly).

### 5.5 Capabilities and Non-Goals

Supported: SSE streaming, stopping generation, Markdown, persisted local sessions and switching, drafts retained during navigation, collapsed tool groups and details, on-demand screenshots, jump to latest, setup guidance, classified errors and model-driven local tool calls (see 5.7).

Explicit non-goals:

- **No knowledge base UI**: RAG corpora, vector databases and Dify knowledge-base configuration stay server-side; no vector DB or embedding model is added to the APK.
- **No business-knowledge or persona prompt in the APK**: the app only generates a structural system prompt (identity, tool list, grounding rules — see 5.7); game knowledge and retrieval stay server-side. `systemPrompt` on `OpenAiCompatibleChatClient.streamChat()` defaults to empty and is passed explicitly by `MaaChatSystemPrompt`.

### 5.6 One-Click Config Import

Settings → AI Control & MCP Service → `从链接或 JSON 导入` (paste box + `粘贴并导入` + `导入`). Alternatively use the deep link `maameow://ai-import?…`, which opens the settings page and imports there (a failed parse is silently ignored and does not navigate). On success only `已导入 <vendor> · <model / Base URL>` is shown; the key is never echoed.

| Form | Example |
|---|---|
| Base URL + key | `https://api.deepseek.com/v1#sk-xxx`; also `https://api.deepseek.com/v1#key=sk-xxx&model=deepseek-chat`, or the pair separated by a space or `\|` (`https://api.deepseek.com/v1 sk-xxx`) |
| Import deep link | `maameow://ai-import?baseUrl=https%3A%2F%2Fapi.deepseek.com%2Fv1&apiKey=sk-xxx&model=deepseek-chat&provider=OPENAI`; `provider` may be `OPENAI` or `DIFY` |
| pi `models.json` fragment | `{"providers":{"deepseek":{"baseUrl":"https://api.deepseek.com/v1","api":"openai-completions","apiKey":"sk-xxx","models":[{"id":"deepseek-chat"}]}}}` (`api` must be `openai-completions` / `openai-responses`) |

Rejected: empty input, unsupported link, invalid JSON, no OpenAI-compatible entry, OAuth credential, missing usable key. **OAuth subscription credentials (pi `auth.json`) cannot be imported**: access tokens require a Node-side refresh flow and the APK has no refresh loop; use the service's OpenAI-compatible endpoint with a regular API key instead.

adb check:

```bash
adb shell "am start -a android.intent.action.VIEW -d 'maameow://ai-import?baseUrl=https%3A%2F%2Fapi.deepseek.com%2Fv1&apiKey=sk-test&model=deepseek-chat&provider=OPENAI'"
```

> A local mock needs `adb reverse` first, and `network_security_config.xml` permits cleartext HTTP only to `127.0.0.1` / `localhost` / `::1`. Deep-link URLs also remain in shell history and system logs, so use a fake key when testing.

### 5.7 The Assistant Is an Agent: Tool Loop and Generated Prompt

With AI Control enabled, the OpenAI-compatible backend sends local MCP tools as `tools[]` (`tool_choice: "auto"`). `MaaToolBridgeInvoker` uses the same Koin `MaaToolBridge` as the MCP server, feeds results back as `role: "tool"` and issues the next request. Screenshot Base64 is removed from tool text and sent as `image_url` content, not billed as a giant text string.

- **Limits**: at most 32 rounds (`MAX_TOOL_ROUNDS`) / 64 tool calls (`MAX_TOOL_CALLS`) per turn; on reaching the cap the reply appends `已达到本轮工具调用上限（32 轮 / 64 次）…` and stops.
- **Prompt**: `MaaChatSystemPrompt.build(toolSpecs, aiControlEnabled, profileSummary)` generates the identity, the tool facts (explicitly "you DO have tools, don't claim otherwise"), 11 workflow and grounding rules, the user's real profile names (name + `profile_id` + enabled tasks) and language rules.
- **No drift**: the tool list iterates `maaToolSpecs` (`ai-mcp/src/main/kotlin/com/aliothmoon/maameow/ai/mcp/MaaToolSpec.kt`); the same data feeds MCP `tools/list` (`MaaMcpServerFactory.toolSpecs`), the OpenAI `tools[]` and the system prompt. Adding or renaming a tool touches one place, so the prompt can never lag behind the MCP server.
- **Thinking channel**: reasoning is kept separate from the answer and collapsed by default. Tool groups and individual tool details are also collapsed.

| # | Grounding rule |
|---|---|
| 1 | Query before answering: state/progress→`maa_status`; profiles→`maa_list_profiles` / `maa_get_profile`; operators & depot→`maa_get_user_data`; game knowledge→`maa_search_wiki` / `maa_get_wiki_article` |
| 2 | Never fabricate stage ids, operator names, task types or parameter fields; say you don't know and list candidates |
| 3 | Check `maa_list_task_types` for the schema before create/update |
| 4 | Launch long tasks asynchronously and return `run_id`; query progress only when the user asks, rather than continuously polling within a turn |
| 5 | `maa_click` is for single-step emergencies or precise screenshot-grounded clicks, never guessed coordinates or manual combat; prefer `maa_execute_ui_plan` for normal multi-step UI workflows |
| 6 | `maa_screenshot` obeys the settings switch; do not retry when denied |
| 7 | Explain the consequences first for destructive ops (`maa_delete_profile`, `maa_stop`, `force=true`) |
| 8 | Run temporary tasks directly through the flat `tasks` of `maa_execute_adhoc`, without forcing creation of persistent profiles |
| 9 | Save/overwrite reusable workflows with `maa_save_workspace_file`, replay through `maa_run_workspace_script`, or inspect with `maa_list_workspace_files` |
| 10 | The main model supplies ordered business steps; the executor owns localization, coordinates and waits. Resume from completed steps and current frames, never blindly replay spending actions, and respect budgets/run limits |
| 11 | Player data is cached by default; refresh relevant categories when missing, stale or changed by upgrades/spending and inspect update status. Do not click or change profiles during updates, or equate download time with live observation |

**Limitation**: tool execution requires AI Control to be enabled. When it is off the request contains no `tools` field at all and no tool ever runs; the system prompt states that device operations are unavailable.

### 5.8 Multi-Step Jev Execution

The main model supplies `goal` and ordered `steps` (normally `action` and a natural-language `target`; add `direction` for swipes and optionally `repeat`). The local executor owns capture, localization, coordinates and waiting without calling the main model for every click. Jev locates targets/evaluates conditions; it is not an autonomous planner, and this does not introduce `click_batch`.

- Ordinary clicks perform localization and presence checks, without requiring verification after every click or page transition. Only supply a screenshot-observable `post_condition` when a key outcome such as saving, resource confirmation or the final business result needs it. `checkpoint=true` requires that condition; pending checks merge with the next full-frame localization where possible.
- `region`, custom coordinates and timing are optional advanced parameters; normal plans do not need crops or hand-tuned waits. An explicit `region` requires normalized `top/left/bottom/right` fields. Each localization uses one full frame or focused crop, enlarged where needed and mapped locally to the original frame, without mixing coordinate systems.
- For `swipe` / `scroll` without coordinates or `region`, a supplied `target` first resolves the named scrollable area/slider; the executor then swipes within it according to `direction`. The main model need not guess coordinates.
- Limits: 15 steps, 30 actions, 60 seconds cumulative waiting and at most 10 repetitions per step. Plan cancellation stops subsequent actions and releases gestures. UI plans and visual clicks are blocked while native tasks or data refresh are busy, avoiding competing page changes.
- `dispatched` is not completion. `verified` requires the final explicit postcondition to pass. Low confidence or failed checks stop the plan; resume unfinished work from `completed_steps` and the returned frame, without claiming false success.
- `maa_visual_click` returns a freshly captured `screenshot_phase=after_dispatch` frame when available, not a guarantee of goal completion. `unavailable` means the post-dispatch frame is missing, not that the click failed; it must not prompt a blind re-click. Do not immediately request another screenshot when a current frame is already available.
- In-combat deployment and skills remain standard MAA / Copilot work. A UI workflow, plan file or replay of an already-cleared stage is not end-to-end complex-task first-clear acceptance; claiming a first clear requires actual combat and result evidence.

### 5.9 Tokens, Cost and Caching

Per-turn and session totals record actual main-model and Jev input, output and cache-read usage. Deduplicate by `requestId`: later streaming snapshots replace earlier values for that request rather than counting them repeatedly. Missing usage, including untracked legacy history, stays unknown rather than being inferred from text length or filled with zero.

Provider-reported actual costs take precedence. Otherwise, estimates apply only to requests addressed to `openrouter.ai` whose model matches a public model ID / `canonical_slug` alias exactly, without fuzzy alias matching or applying another provider's price. Public prices come from the [chat catalog](https://openrouter.ai/api/v1/models) and [decisions catalog](https://openrouter.ai/api/v1/models?output_modalities=decisions), cached for 24 hours. Non-cached input, cache reads and output are priced separately; absent a cache-read rate, use the ordinary input rate. Cost records retain the source (`reported` / `openrouter_catalog`) and corresponding evaluation/catalog-cache timestamp. Missing usage or pricing is not zero, and a model name alone does not prove it is free. When some costs are unknown, the known sum is only a lower bound, shown with `≥` and a warning; public-price estimates show `≈`, not a final bill.

OpenRouter requests include a stable `prompt_cache_key` routing hint for the shared agent prefix; cache hits are not guaranteed. Compute input cache hit rate as `cached_tokens / prompt_tokens`, excluding output and Jev tokens. See [OpenRouter caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching) and [Meta caching](https://dev.meta.ai/docs/cookbook/prompt-caching.md).

### 5.10 Player Data Snapshots and On-Demand Updates

`maa_get_user_data` accepts optional boolean `refresh`, default `false`. Results always declare `source=cached_snapshot` and `isLive=false`: `readAtMillis` is the read time, not an in-game observation time. Per-category `hasSynced`, `syncTimeMillis` and `ageMillis` describe stored snapshots; `hasSynced=true` does not prove this update succeeded. Use `profileId` together with update-report provenance and timestamps to judge applicability.

- When data is missing, stale, changed by upgrades/spending or inconsistent with the current frame, request `refresh=true` for the relevant `category`, not a full refresh every turn. According to existing configuration, the tool reuses native operator recognition or Yituliu import, and native depot recognition. It awaits results without main-model polling; native recognition changes game pages.
- Inspect `refresh.status`: `snapshot_updated` means all requested categories produced newer nonempty snapshots with no skipped imported entries; `partial` means only some categories updated or entries were skipped; `not_updated` means no category met update criteria. Default cached reads report `not_requested`. Also inspect `operatorsUpdated`, `depotUpdated`, `warnings` and `upstreamOperatorsSkipped`, and disclose missing/skipped/failed data. Successful startup or returning to idle is not snapshot-update success.
- Yituliu provenance is `operatorsSource=yituliu_download`: its snapshot time is the download/import time, not when the source observed the game; `upstreamObservationTimeKnown=false`. Refreshed results remain cached snapshots, without a guarantee that upstream data is current.
- Cancelling the chat wait does not forcibly stop an admitted native refresh. A timeout also cannot be called success; native recognition may still be running. Use existing task-stop controls when needed. Do not change profiles or dispatch UI operations during updates; UI plans and visual clicks reject execution while refresh or native tasks are busy.

---

## 6. License

- MaaMeow and MaaMCP are licensed under **AGPL-3.0**. Redistributing modified APKs requires preserving the AGPL-3.0 license, attribution, and providing corresponding source code.
- Dify and RAGFlow are governed by their respective licenses.
