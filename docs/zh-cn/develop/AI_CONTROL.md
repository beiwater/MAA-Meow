# AI 控制与 MCP 集成指南 (AI Control & MCP Guide)

本文档介绍 MaaMeow 的「脑手分离」AI 控制架构、MCP 工具表、安全模型、防幻觉校验机制以及 Dify / Claude Desktop / RAGFlow 接入方式。

> 复用审计表与选型依据见：[AI_REUSE_AUDIT.md](./AI_REUSE_AUDIT.md)。

---

## 1. 架构概览：脑手分离

- **手（手机侧，`:ai-mcp` + `AiControlService`）**：
  - 使用官方 `io.modelcontextprotocol:kotlin-sdk-server` + Ktor CIO `mcpStreamableHttp` 暴露标准 MCP HTTP 端点（`/mcp` 与兼容 `/sse`）。
  - 所有工具 handler 均为对既有 `MaaCompositionService`、`TaskChainState`、`TaskChainStatusTracker`、`CopilotManager`、`MaaSessionLogger`、`FrameSnapshotter` 的薄封装。
  - **APK 内不包含**：向量数据库、Embedding 模型、ONNX 新依赖、自研分块器或手写 JSON-RPC 帧。
- **脑（PC / NAS 侧，Dify 或 RAGFlow）**：
  - 承载明日方舟专用知识库（`scripts/build_rag_corpus.py` 从 `MaaResource`、PRTS、`ArknightsGameData`、`item_index.json`、`prts.plus`、企鹅物流提取按实体切块的语料）与 Agent 工具循环。
- **应用内聊天（薄客户端）**：
  - 仅调用 Dify `/v1/chat-messages` 流式 API 并使用已有 `compose-markdown` 渲染回复与工具调用摘要卡片。

---

## 2. MCP 工具表

| 工具名 | 参数 | 返回 / 行为 | 底层复用落点 |
|---|---|---|---|
| `maa_status` | 无 | 返回执行状态 (`IDLE`/`STARTING`/`RUNNING`/`STOPPING`/`ERROR`)、当前 `activeProfileId`、`currentRunId` 与子任务状态列表 | `MaaCompositionService.state` + `TaskChainStatusTracker` |
| `maa_list_task_types` | `type_id?` (可选过滤) | 由 `TaskTypeInfo` + `TaskParamProvider` 的 `kotlinx.serialization` `SerialDescriptor` **自动生成** JSON Schema 与默认配置 | `TaskSchemaGenerator` + `TaskTypeInfo` |
| `maa_list_profiles` | 无 | 列出全部 `TaskProfile` | `TaskChainState.profiles` |
| `maa_get_profile` | `profile_id?` | 获取指定（或当前激活）`TaskProfile` 完整节点链 | `TaskChainState` |
| `maa_create_profile` | `name`, `chain` | 先通过 `ArknightsEntityValidator` 校验字段名、关卡 ID、干员名、物品 ID，通过后落盘到 `TaskChainState` | `ArknightsEntityValidator` + `TaskChainState.saveOrUpdateCustomProfile` |
| `maa_update_profile` | `profile_id`, `name?`, `chain?` | 校验未知字段与实体稳定 ID 后更新指定 `TaskProfile` | `ArknightsEntityValidator` + `TaskChainState.saveOrUpdateCustomProfile` |
| `maa_delete_profile` | `profile_id`, `confirm=true` | 显式确认后删除配置（禁止删除最后一个配置） | `TaskChainState.removeProfile` |
| `maa_run_profile` | `profile_id?`, `force?` | **异步立即返回** `run_id`，后台通过 `StartTaskChainUseCase` / `MaaCompositionService.start` 启动；`force=true` 对齐 `extra_force_start` | `StartTaskChainUseCase` + `MaaCompositionService.start` |
| `maa_stop` | 无 | 停止当前运行中的任务链或作业 | `MaaCompositionService.stop` |
| `maa_save_script` | `name`, `chain?` | 将录制草稿或最近一次实际生效的 `List<MaaTaskParams>` 转为 `TaskProfile` 落盘，与 `ConfigBackupManager` 互通 | `AiScriptRecorder` + `TaskChainState` |
| `maa_list_scripts` | 无 | 列出录制草稿与已存脚本 (`TaskProfile`) | `AiScriptRecorder` + `TaskChainState` |
| `maa_run_script` | `profile_id`, `force?` | 异步执行指定脚本并返回 `run_id` | `StartTaskChainUseCase` |
| `maa_get_logs` | `limit?` (默认100), `redact?` (默认true) | 获取当前会话或最近日志文件记录，默认脱敏 Token/路径/账号 | `MaaSessionLogger` + `LogRedactor` |
| `maa_screenshot` | 无 | 返回当前游戏画面 JPEG Base64（**默认关闭**，需在设置中显式开启「允许 AI 获取游戏截图」） | `FrameSnapshotter` (`RemoteService.captureFrameJpeg`) |
| `maa_load_copilot` | `copilot`, `config?` | 解析 `maa://` / `prts://` 作业码或 JSON 并异步启动自动战斗 | `CopilotManager` + `MaaCompositionService.startCopilot` |
| `maa_search_wiki` | `query`, `wiki?` ("auto"\|"prts"\|"wiki_gg"), `limit?` (默认5) | 在线联网检索明日方舟 PRTS Wiki (简中原生) 或海外 Arknights Terra Wiki (`arknights.wiki.gg`，Cloudflare CDN 极速)，支持双源智能路由与海外网络超时自动降级 | MediaWiki API (PRTS / wiki.gg) + `OkHttpClient` |
| `maa_get_user_data` | `category?` ("operators"\|"depot"\|"all", 默认"all"), `filter?` (干员名或物品名过滤) | 查询玩家真实干员箱（星级、精二、等级、潜能、专精、模组）与仓库库存，支持个性化与千人千面自适应 | `OperBoxRepository` + `DepotRepository` + `ItemHelper` |
| `maa_get_wiki_article` | `title` (页面标题), `wiki?` ("auto"\|"prts"\|"wiki_gg", 默认"auto") | 获取百科词条全文并转换为纯净标准 Markdown 格式，具备双源路由与海外秒级降级能力 | MediaWiki API (`prop=extracts&explaintext=1` / `parse`) + `OkHttpClient` |
| `maa_click` | `x`, `y` | 向游戏画面（1280x720 空间）发送单次精准应急点击，用于关闭突发弹窗、活动海报 [X] 或断线重连按钮 | `RemoteServiceManager` + `MaaCoreService.AsyncClick` |
| `maa_back_to_home` | 无 | 调用 MAA Core 原生 BackToHome 状态机，从任意未知/深层子界面或卡死页面安全返回主大厅 | `RemoteServiceManager` + `MaaCoreService.BackToHome` |

---

## 3. 安全与隐私模型

1. **默认关闭、零冷启动开销**：
   - `aiControlEnabled` 默认为 `false`。未开启时不启动 `AiControlService`、不初始化 Ktor Engine，冷启动无任何额外开销。
2. **默认仅本机回环 + Bearer Token 强制鉴权**：
   - 默认监听 `127.0.0.1:18080`。PC 侧连接推荐使用 `adb forward tcp:18080 tcp:18080`。
   - 所有 `/mcp` 与 `/sse` 请求必须携带 `Authorization: Bearer <token>`，否则返回 `401 Unauthorized`。
   - 开启 `0.0.0.0` 局域网监听需在设置页通过二次确认弹窗。
3. **截图与日志隐私保护**：
   - `maa_screenshot` 默认关闭，未开启时调用直接拒绝。
   - `maa_get_logs` 默认经过 `LogRedactor` 脱敏 `Bearer` Token、`accountName`、`penguinId`、`cdk` 及沙盒路径。
4. **防幻觉闸门 (`ArknightsEntityValidator`)**：
   - 模型生成的关卡代码（如 `1-7`, `CE-6`）、干员名（如 `维什戴尔`, `黍`）、掉落物品 ID（如 `30012`）与配置字段名必须命中 `MaaResource` 稳定 ID 与 `TaskParamProvider` Schema；若输出不存在的关卡（如 `99-99`）或未知字段，直接拒答并返回编辑距离最近的合法候选列表。
5. **自动更新防覆盖**：
   - 启用 AI 控制时自动将 `autoCheckUpdate` 与 `autoDownloadUpdate` 置为 `false`，避免被上游官方包覆盖。

6. **应急触屏与全局导航安全边界 (`maa_click` & `maa_back_to_home`)**：
   - `maa_click` 统一基于 1280x720 逻辑分辨率，内部做 `coerceIn(0, 1280)` 与 `coerceIn(0, 720)` 钳制保护；
   - 严格限定为应急故障干预微创工具（处理意外活动海报、签到、网络弹窗），绝对禁止用于长程手动通关或脱离任务链的手动刷图；
   - `maa_back_to_home` 复用 MAA Core 原生状态机导航链，内置超时与防卡死保护。
---

## 4. 外部 MCP 客户端与 Dify 配置示例

### 4.1 通过 `adb forward` 连接手机 MCP Server

```bash
adb forward tcp:18080 tcp:18080
```

### 4.2 Claude Desktop / MCP Inspector 配置 JSON

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

### 4.3 curl 验收脚本示例

```bash
TOKEN="YOUR_BEARER_TOKEN"

# 1. 健康检查
curl -s http://127.0.0.1:18080/health

# 2. 初始化 MCP 会话并获取 Mcp-Session-Id
curl -i -X POST http://127.0.0.1:18080/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "Accept: application/json, text/event-stream" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0"}}}'
```

### 4.4 生成知识库语料与运行 32 题防幻觉评测集

```bash
# 生成按实体切块的明日方舟 + MAA 语料 (.rag-cache/arknights_maa_corpus.jsonl)
python scripts/build_rag_corpus.py

# 运行 32 题基准评测（含 7 道不存在关卡/干员/物品陷阱题）
python scripts/eval_rag_benchmark.py
```

---

## 5. 许可证说明

- MaaMeow 与 MaaMCP 均为 **AGPL-3.0** 许可证：二次分发 APK 或修改版本时必须保留 AGPL-3.0 协议与原作者署名，并提供完整对应源码。
- Dify / RAGFlow 采用其各自开源许可证，部署前请确认具体商用与分发条款。
