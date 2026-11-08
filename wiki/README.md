# MaaMeow AI Control 文档中心

欢迎查阅 **MaaMeow AI Control** 架构与开发文档库。

MaaMeow 是一款原生运行于 Android 系统的明日方舟日常自动化伴侣。AI Control 则是为 MaaMeow 注入大语言模型（LLM）与智能体（Agent）编排能力的外挂式/内生式智能交互中枢。

---

## 1. 项目定位与解决痛点

在传统的自动化工具中，用户通常需要手动逐项勾选任务链（如：登录唤醒 -> 刷理智 1-7 -> 自动公招 -> 基建换班 -> 信用购物 -> 领邮件）。然而在面对动态策略时：

1. **复杂意图无法直接表达**：“帮我把今天的理智都刷在 CE-6 龙门币，如果没开就去刷 1-7，打完去基建把菲亚梅塔换上去上班”。
2. **移动端资源极度受限**：手机本地无法承载数十亿参数的本地大模型、向量数据库（Vector DB）或 Embedding 推理。
3. **传统控制粒度割裂**：普通的屏幕触控 MCP（如通过 ADB 截图与点击）完全无法感知 MaaMeow 内部已有的基建策略、公招计算、小黑屋调度和 Shizuku 虚拟屏后台挂机状态。

**MaaMeow AI Control 的破局方案**：采用「脑手分离」架构。
- **手（手机端）**：依托内置轻量级 `:ai-mcp` 模块，将 App 已有的任务链 (`TaskProfile`)、执行器 (`MaaCompositionService`) 与作战作业 (`CopilotManager`) 暴露为标准 **MCP (Model Context Protocol)** 工具。
- **脑（服务端/PC）**：依托 Dify / RAGFlow / Claude 等宿主，承载明日方舟专业知识库检索（RAG）、策略推理与工具调用循环。

---

## 2. AI 订阅与 API Key 接入支持 (Claude Pro / DeepSeek / OpenAI 等)

很多博士最关心的是费用与模型使用门槛：**我可以使用现有的 AI 订阅（如 Claude Pro/Max）或者自己的 API Key（如 DeepSeek、OpenAI、Kimi、通义千问等）来控制 MaaMeow 吗？**

**答案：完全支持，且零改动、开箱即用！**

- **模式 1：现成 AI 订阅用户（Claude Pro / Max）**
  - **客户端**：官方桌面客户端 **Claude Desktop**
  - **费用**：直接扣除用户现有的 Claude 订阅用量，**不需要额外买 API Key，不需要自建服务器**。
  - **连接**：`adb forward tcp:18080 tcp:18080`，在 `claude_desktop_config.json` 中添加 MaaMeow MCP 配置即可。
- **模式 2：个人 API Key 用户（零服务器方案，DeepSeek / OpenAI / Kimi 等）**
  - **客户端**：流行的开源桌面客户端，如 **Cherry Studio**、**Chatbox** 或 **Cursor**
  - **费用**：按 Token 计费（例如 1 元几百万 Token 的 **DeepSeek V3 / R1**，极具性价比）。
  - **连接**：在客户端设置中填入 API Key，并在 MCP 设置中添加 MaaMeow 地址 `http://127.0.0.1:18080/mcp`，客户端自带 ReAct 工具循环，直接指挥手机！
- **模式 3：API Key + 手机 App 内聊天（Dify 方案）**
  - **客户端**：MaaMeow 手机 App 内置「AI 助手」界面
  - **服务端**：家庭 NAS/PC 运行 Dify 容器，在 Dify 填入你的 DeepSeek/OpenAI API Key 并挂载明日方舟知识库。

详细图文与配置步骤详见：[AI 订阅与 API Key 接入完全指南](client/external_clients.md)。

---

## 3. 核心架构原则

### 原则一：能复用不自研，写代码排在第二位 (Reuse First, Code Second)
任何技术选型必须优先排查既有成熟实现，严禁自研已有成熟轮子的领域：
- **MCP 协议与通信**：直接采用 Anthropic 官方维护的 `io.modelcontextprotocol:kotlin-sdk-server`，传输层采用 `io.ktor:ktor-server-cio`（支持 `mcpStreamableHttp` 与 SSE）。
- **向量检索与 RAG**：交由服务端 Dify / RAGFlow 容器托管，**严禁向 Android APK 内引入任何向量库（如 ObjectBox/sqlite-vec）、Embedding 模型或自研文档分块器**。
- **UI 与网络库**：Markdown 渲染复用既有 `compose-markdown`，网络流式通信复用全局单例 `OkHttpClient`，任务序列化复用 `kotlinx.serialization`。

### 原则二：零多余抽象 (Zero Redundant Abstractions)
拒绝过度设计与套娃抽象：
- `:ai-mcp` 中的每个工具 Handler 均是对底层服务（`MaaCompositionService`、`TaskChainState`、`CopilotManager`、`MaaSessionLogger`、`FrameSnapshotter`）的**薄封装（Thin Wrapper）**。
- 任务参数 Schema 由 Kotlin 序列化描述符 (`SerialDescriptor`) 自动生成，**杜绝手写维护一套重复的 JSON Schema**。

### 原则三：零冷启动开销与严格安全边界
- AI Control 功能默认处于关闭状态（`aiControlEnabled = false`）。未开启时，前台服务不启动、Ktor 引擎不加载、网络端口不监听，App 冷启动耗时与内存增量为 **绝对的 0**。
- 强制要求 Bearer Token 鉴权，默认仅监听 `127.0.0.1` 环回接口，截图工具默认禁用，自动更新自动防护。

---

## 4. 架构总览全景

```text
+-----------------------------------------------------------------------------------+
|                            THE BRAIN (PC / NAS / Cloud)                           |
|                                                                                   |
|   +--------------------------+          +-------------------------------------+   |
|   |   Claude / Dify Agent    |          |    Arknights Dedicated RAG Corpus   |   |
|   | (LLM + ReAct Tool Loop)  | <------> |  - 2,000+ Tasks & Stage Entity Chunks|  |
|   |                          |          |  - Operator & Item Index Chunks     |   |
|   +------------+-------------+          +-------------------------------------+   |
+----------------|------------------------------------------------------------------+
                 | MCP Protocol (Streamable HTTP / SSE over ADB or LAN)
                 | Authorization: Bearer <token>
+----------------v------------------------------------------------------------------+
|                            THE HAND (Android Device)                              |
|                                                                                   |
|   +---------------------------------------------------------------------------+   |
|   |  AiControlService (Foreground Service / Ktor CIO 127.0.0.1:18080)         |   |
|   |  - Token Authenticator (401 Gate)                                         |   |
|   |  - Route: /mcp (Streamable HTTP) | /sse (SSE Transport) | /health         |   |
|   +-------------------------------------+-------------------------------------+   |
|                                         |                                         |
|   +-------------------------------------v-------------------------------------+   |
|   |  :ai-mcp Core Layer (Official Kotlin MCP SDK Server)                      |   |
|   |  - 16 Standard Tools (Status, Profiles, Scripts, Copilot, Logs, Wiki, etc.)|   |
|   |  - TaskSchemaGenerator (kotlinx.serialization SerialDescriptor reflection)|   |
|   |  - ArknightsEntityValidator (Levenshtein Candidate Suggestion Gate)       |   |
|   +-------------------------------------+-------------------------------------+   |
|                                         |                                         |
|   +-------------------------------------v-------------------------------------+   |
|   |  MaaMeow Native Domain & Infrastructure Layer (Zero-Change Reuse)         |   |
|   |  - MaaCompositionService (FSM: IDLE/STARTING/RUNNING/STOPPING)            |   |
|   |  - TaskChainState (DataStore Persistence & Custom Profiles)               |   |
|   |  - CopilotManager (prts.plus / maa:// Combat JSON runner)                 |   |
|   |  - AiScriptRecorder (Record/Replay TaskProfile with 0 param diff)         |   |
|   |  - FrameSnapshotter (MaaCoreService.GetImage JPEG capture)                |   |
|   |  - libMaaCore.so (Native MaaFramework Core via JNI + Shizuku Virtual Display|
|   +---------------------------------------------------------------------------+   |
+-----------------------------------------------------------------------------------+
```

---

## 5. 文档快速导航

| 章节分类 | 文档路径 | 核心要点 |
|---|---|---|
| **架构设计** | [脑手分离设计草案](architecture/design_draft.md) | 完整架构方案、Mermaid 序列图、状态机模型与安全纵深防御 |
| | [逐项能力复用审计表](architecture/reuse_audit.md) | 10 大能力域选型审计、与 PC 零改动路线对比分析 |
| | [账户个性化与练度自适应](architecture/account_personalization.md) | 千人千面练度痛点、`OperBoxRepository`/`DepotRepository`、自动扫描与智能抄作业平替 |
| | [智能自愈与兜底决策](architecture/ai_fallback_decision.md) | 传统 if-else 局限、五大典型自愈场景、ReAct 闭环时序与开箱即用 System Prompt |
| **手侧实现 (The Hand)** | [20 项 MCP 工具规范与 Schema](hand/mcp_tools.md) | 20 项标准工具参数、自动生成 Schema、成功与错误返回格式 |
| | [服务生命周期与安全模型](hand/service_and_security.md) | `AiControlService` 运行逻辑、Token 鉴权、网络策略与日志脱敏 |
| | [定时任务、自动唤醒与自启机制](hand/scheduled_tasks.md) | 硬件精确闹钟自启、WakeUnlockEngine 亮屏解锁、虚拟屏免打扰、异常守护看门狗 |
| **脑侧与评测 (The Brain)** | [明日方舟专用 RAG 语料库](brain/rag_knowledge_base.md) | 实体级分块策略、`build_rag_corpus.py` 工作机制与向量化配置 |
| | [实体校验与防幻觉设计](brain/anti_hallucination.md) | `ArknightsEntityValidator`、稳定 ID 校验与 Levenshtein 候选推荐 |
| | [32 题基准评测体系](brain/evaluation_benchmark.md) | 32 题评测集、Top-1 100% 召回率与 7 道陷阱题 100% 拦截率 |
| **脚本互通** | [任务录制与配置回放](scripting/script_recorder.md) | `AiScriptRecorder`、参数快照与 `ConfigBackupManager` 零差互通证明 |
| **客户端集成** | [应用内 Compose 聊天客户端](client/in_app_chat.md) | SSE 流式解析器、工具卡片组件与中止生成机制 |
| | [AI 订阅与 API Key 接入指南](client/external_clients.md) | Claude Pro 订阅、Cherry Studio + DeepSeek/OpenAI API Key、Dify 接入教程 |

---

## 6. 许可证与合规声明

- **MaaMeow** 核心项目与 **MaaMCP** 均遵循 **GNU Affero General Public License v3.0 (AGPL-3.0)**。任何基于本项目的二次分发或网络服务修改，均须保留原作者署名与 AGPL-3.0 协议，并公开完整源代码。
- **Anthropic Model Context Protocol (MCP) Kotlin SDK** 遵循 Apache-2.0 许可证。
- **Dify** 与 **RAGFlow** 遵循各自官方开源协议。
