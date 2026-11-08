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
  - Connects to Dify's `/v1/chat-messages` streaming SSE API and renders responses and tool call summary cards using the existing `compose-markdown` dependency.

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
| `maa_get_user_data` | `category?` ("operators"\|"depot"\|"all", default "all"), `filter?` (operator or item name/ID) | Queries player's owned operators (elite, level, potential, skill masteries, modules) and depot materials for personalization | `OperBoxRepository` + `DepotRepository` + `ItemHelper` |
| `maa_get_wiki_article` | `title`, `wiki?` ("auto"\|"prts"\|"wiki_gg", default "auto") | Fetches full wiki article as clean Markdown with dual-source intelligent routing and overseas fallback | MediaWiki API (`prop=extracts&explaintext=1` / `parse`) + `OkHttpClient` |
| `maa_click` | `x`, `y` | Sends targeted emergency click to game coordinates (standard 1280x720 space) to dismiss popups or dialogs | `RemoteServiceManager` + `MaaCoreService.AsyncClick` |
| `maa_back_to_home` | None | Executes MAA Core's native BackToHome navigation routine to return from any stuck sub-screen to the main lobby | `RemoteServiceManager` + `MaaCoreService.BackToHome` |

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
   - `maa_click` is strictly bounded to the 1280x720 coordinate space with clamping (`coerceIn(0, 1280)`, `coerceIn(0, 720)`).
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

## 5. License

- MaaMeow and MaaMCP are licensed under **AGPL-3.0**. Redistributing modified APKs requires preserving the AGPL-3.0 license, attribution, and providing corresponding source code.
- Dify and RAGFlow are governed by their respective licenses.
