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
  - 双后端可选：默认走 OpenAI 兼容 `POST {baseUrl}/chat/completions`（`OpenAiCompatibleChatClient`），也可切回 Dify 应用 API `/v1/chat-messages`（`DifyChatClient`）；
  - 两者均为 SSE 流式，并复用已有 OkHttpClient 与 Markdown 渲染；OpenAI 兼容后端还会在本地执行模型请求的 20 个 MCP 工具（见 5.7）。

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

## 5. 应用内对话（OpenAI 兼容 / Dify 双后端）

### 5.1 入口

| 入口 | 位置 | 条件 |
|---|---|---|
| 底部 Tab | 底栏第 2 个「AI」（`BottomNavTab.AI`，路由 `Routes.AI_CHAT`），由 `BottomNavTab.visibleTabs` 按开关过滤；开关变化后 `resolvePageForTabs` 按路由重算下标 | 仅 `aiControlEnabled` 开启时出现 |
| 首页卡片 | 首页「AI 对话」卡片，副标题为「服务商 · 模型」（`aiChatTargetLabel`），未填密钥时显示「未配置模型密钥」 | 同上 |
| 任务 / 定时卡片 | 任务页与定时任务列表顶部的紧凑卡片（`compact = true`） | 同上 |
| 设置页入口 | 设置 → AI 控制与 MCP 服务 → 顶栏对话图标（`Icons.Rounded.Chat`）与底部「打开应用内 AI 助手对话」按钮，均为切换到底部 AI Tab | 同上 |

对话页是底栏主 Tab 之一（`AiChatView`），顶部可「清空当前会话」；键盘弹出时 `MainScreen` 隐藏底栏，对话页用 `Scaffold(contentWindowInsets = WindowInsets(0))` + `imePadding()` 避让 IME。

### 5.2 两种后端

| 后端 | 协议 | 会话状态 | 工具调用卡片 | 停止生成 |
|---|---|---|---|---|
| `OPENAI`（默认） | `POST {baseUrl}/chat/completions`，解析 `choices[0].delta.content`、`delta.tool_calls[]`、`delta.reasoning(_content)` 与 `[DONE]` | 协议无状态，由本地消息列表回放（仅内存）；工具循环的中间消息只在本次请求内 | 有（本地工具循环，经 `MaaToolBridgeInvoker`） | 取消协程（下一次工具执行前 `ensureActive()`），并 `call.cancel()` 中止 HTTP |
| `DIFY` | `POST {baseUrl}/chat-messages`（`response_mode=streaming`） | 由 `conversation_id` 维护 | 有（`AiToolCallCard`，来自 `agent_thought`） | 另发 `POST {baseUrl}/chat-messages/{task_id}/stop` |

`OpenAiCompatibleChatClient` 还提供 `vendorLabel()`，按 Base URL 的 host 推断展示名（`deepseek`→DeepSeek、`openai`→OpenAI、`moonshot`/`kimi`→Kimi、`siliconflow`→SiliconFlow、`bigmodel`/`zhipu`→GLM、`dashscope`/`aliyun`→Qwen，猜不出则退回 host）；日志中的密钥按既有规则脱敏。

### 5.3 设置项与默认值

`domain/models/AppSettings.kt` 中声明的 DataStore 键：

| 键名 | 含义 | 默认值 |
|---|---|---|
| `ai_chat_provider` | 对话后端，`OPENAI` / `DIFY`（解析失败回退 `OPENAI`） | `OPENAI` |
| `ai_openai_base_url` | OpenAI 兼容 Base URL | `https://api.deepseek.com/v1` |
| `ai_openai_api_key` | API Key；为空时对话页显示配置卡片，且不会发出请求 | `""`（空） |
| `ai_openai_model` | 模型名 | `deepseek-chat` |

Dify 后端沿用既有三项：`ai_dify_base_url`（默认 `http://127.0.0.1/v1`）、`ai_dify_api_key`（默认空）、`ai_dify_user`（默认空）。

### 5.4 常用 Base URL

| 服务商 | Base URL | 备注 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | 默认值 |
| OpenAI | `https://api.openai.com/v1` | |
| Kimi (Moonshot) | `https://api.moonshot.cn/v1` | |
| SiliconFlow | `https://api.siliconflow.cn/v1` | |
| Ollama（PC 本机） | `http://<PC-IP>:11434/v1` | 需手机与 PC 同一局域网且端口可达。注意 `network_security_config.xml` 仅对 `127.0.0.1` / `localhost` / `::1` 放行明文 HTTP，因此 PC 的 `http://<PC-IP>` 会被系统网络策略拦截；推荐 `adb reverse tcp:11434 tcp:11434` 后填 `http://127.0.0.1:11434/v1`，或给 PC 侧套 HTTPS 反代 |

填写时容忍已带 `/chat/completions` 的完整地址；建议统一以 `/v1` 结尾，否则易触发 `404`（错误提示会直接点名这一点）。

### 5.5 能力与边界

已支持：SSE 流式逐字输出（末尾 `▍` 光标）、停止生成、Markdown 渲染、「有什么可以帮你？」空态与 4 个可点示例（`ai_chat_example_1..4`）、未配置密钥时的引导卡片、错误分类映射与可复制的原始错误小字、模型驱动的本机工具调用（见 5.7）。

明确不做：

- **不做会话管理**：无本地数据库、无历史会话列表、无多会话切换；OpenAI 后端每次请求回放当前内存消息，Dify 后端仅持有一个 `conversation_id`，进程退出即丢失。
- **不做知识库 UI**：RAG 语料、向量库与 Dify 知识库配置全部留在服务端，APK 内不引入向量库或 Embedding。
- **不在 APK 内放置业务知识 / 人设提示词**：APK 只生成结构性系统提示词（身份、工具清单、grounding 规则，见 5.7），游戏知识与检索仍在服务端；`OpenAiCompatibleChatClient.streamChat()` 的 `systemPrompt` 默认为空，由 `MaaChatSystemPrompt` 显式传入。

### 5.6 一键导入配置

设置 → AI 控制与 MCP 服务 →「从链接或 JSON 导入」（粘贴框 +「粘贴并导入」+「导入」）；也可用深链 `maameow://ai-import?…` 直接拉起设置页导入（解析失败时静默忽略，不跳页）。导入成功只提示「已导入 `<服务商>` · `<模型 / Base URL>`」，密钥不回显。

| 形态 | 示例 |
|---|---|
| Base URL + Key | `https://api.deepseek.com/v1#sk-xxx`；也支持 `https://api.deepseek.com/v1#key=sk-xxx&model=deepseek-chat`，以及空格或 `\|` 分隔（`https://api.deepseek.com/v1 sk-xxx`） |
| 导入深链 | `maameow://ai-import?baseUrl=https%3A%2F%2Fapi.deepseek.com%2Fv1&apiKey=sk-xxx&model=deepseek-chat&provider=OPENAI`，`provider` 可为 `OPENAI` / `DIFY` |
| pi `models.json` 片段 | `{"providers":{"deepseek":{"baseUrl":"https://api.deepseek.com/v1","api":"openai-completions","apiKey":"sk-xxx","models":[{"id":"deepseek-chat"}]}}}`（`api` 需为 `openai-completions` / `openai-responses`） |

会被拒绝：空内容、不支持的链接、非法 JSON、无 OpenAI 兼容条目、OAuth 凭据、缺少可用 Key。**OAuth 订阅凭据（pi 的 `auth.json`）无法导入**：访问令牌需要 Node 侧定时刷新，APK 内没有刷新循环；请改用该服务的 OpenAI 兼容端点 + 普通 API Key。

adb 验证：

```bash
adb shell "am start -a android.intent.action.VIEW -d 'maameow://ai-import?baseUrl=https%3A%2F%2Fapi.deepseek.com%2Fv1&apiKey=sk-test&model=deepseek-chat&provider=OPENAI'"
```

> 本地 mock 需先 `adb reverse`，且 `network_security_config.xml` 只对 `127.0.0.1` / `localhost` / `::1` 放行明文 HTTP；深链正文会留在 shell 历史与系统日志里，验证请用假 Key。

### 5.7 助手即 Agent：工具循环与生成式提示词

开启 AI 控制后，OpenAI 兼容后端把 20 个本机 MCP 工具作为 `tools[]` 随请求发送（`tool_choice: "auto"`）。模型请求工具时，`MaaToolBridgeInvoker` 通过 Koin 注入的同一个 `MaaToolBridge` 执行（与 MCP 服务端共享状态与校验），结果以 `role: "tool"` 回灌，再发起下一轮请求。

- **上限**：每回合最多 6 轮（`MAX_TOOL_ROUNDS`）/ 8 次工具调用（`MAX_TOOL_CALLS`）；触顶时追加「已达到本轮工具调用上限（6 轮 / 8 次）…」并停下。
- **提示词**：`MaaChatSystemPrompt.build(toolSpecs, aiControlEnabled, profileSummary)` 生成身份、工具事实（明确「你有工具，不要说看不到 tools」）、7 条 grounding 规则、用户真实配置名（名字 + `profile_id` + 已启用任务）与语言规则。
- **不漂移**：工具清单直接遍历 `maaToolSpecs`（`ai-mcp/src/main/kotlin/com/aliothmoon/maameow/ai/mcp/MaaToolSpec.kt`），同一份数据供 MCP `tools/list`（`MaaMcpServerFactory.toolSpecs`）、OpenAI `tools[]` 与系统提示词三处使用；新增或改名工具只改这一处，提示词不可能落后于 MCP 服务端。
- **思考通道**：`delta.reasoning` / `delta.reasoning_content` 单独累积，UI 显示可折叠的「思考中… (N 字)」，出现正文后自动折叠，思考内容不混入正文。

| # | grounding 规则 |
|---|---|
| 1 | 需要事实先查工具：状态/进度→`maa_status`；配置→`maa_list_profiles` / `maa_get_profile`；干员与仓库→`maa_get_user_data`；游戏知识→`maa_search_wiki` / `maa_get_wiki_article` |
| 2 | 不许编造关卡 id、干员名、任务类型或参数字段；查不到就直说并列出候选 |
| 3 | 配置结构不确定先 `maa_list_task_types` 查 schema，再 create/update |
| 4 | 长任务用 `maa_run_profile` 拿 `run_id` 后轮询 `maa_status`，不阻塞 |
| 5 | `maa_click` 仅用于关闭意外弹窗等排障，禁止手动打关或刷图 |
| 6 | `maa_screenshot` 受设置开关控制，被拒绝时不反复重试 |
| 7 | 破坏性操作（`maa_delete_profile`、`maa_stop`、`force=true`）执行前先说明后果 |

**限制**：工具执行依赖 AI 控制已开启。关闭时请求里根本不带 `tools` 字段，也不执行任何工具，模型只能纯文本回答；系统提示词同样会声明「AI 控制已关闭，无法执行设备操作」。

---

## 6. 许可证说明

- MaaMeow 与 MaaMCP 均为 **AGPL-3.0** 许可证：二次分发 APK 或修改版本时必须保留 AGPL-3.0 协议与原作者署名，并提供完整对应源码。
- Dify / RAGFlow 采用其各自开源许可证，部署前请确认具体商用与分发条款。
