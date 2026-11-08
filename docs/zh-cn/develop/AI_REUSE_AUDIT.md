# M1 复用审计表与选型定稿（Reuse Audit & Architecture Decision）

> **最高原则**：能复用不自研。每一块能力先列出现有可复用实现（名字 / 仓库 / 许可证 / 最近提交 / 依赖体积 / Android 可行性），再决定用哪个。
> **禁止自研**：MCP 协议、JSON-RPC 帧、SSE、向量检索、embedding 推理、分块器、Agent 工具循环、Markdown 渲染、HTTP 客户端。

---

## 1. 零改动路线 vs. MaaMeow Fork 路线评估

### 1.1 候选对比

| 维度 | 路线 A：零改动路线（PC `uvx maa-mcp` + Dify + ADB） | 路线 B：MaaMeow Fork（内置 `:ai-mcp` 薄 MCP Server + Dify/RAGFlow） |
|---|---|---|
| **代表实现** | `MAA-AI/MaaMCP`（PyPI `maa-mcp` v1.2.3，AGPL-3.0） | `:ai-mcp`（基于 `io.modelcontextprotocol:kotlin-sdk-server`）+ `MaaCompositionService` |
| **控制粒度** | 原始触控/截图/OCR pipeline 级（`adb screencap` / `adb shell input`） | **App 业务配置级**：直接读写/执行 `TaskProfile`、`TaskChainNode`、`TaskParamProvider`、PRTS 作业码 `CopilotConfig`、录制脚本 |
| **运行拓扑** | 强依赖 PC 宿主机常驻 Python + MaaFramework + ADB 线/无线连接 | 手机端原生运行 `libMaaCore.so`（Shizuku / Root + 虚拟屏后台运行），Dify/Claude/手机端聊天均可随时触发 |
| **与 MaaMeow 状态同步** | 割裂：外部 ADB 操作无法感知/复用 MaaMeow 内的公招策略、基建排班、刷图计划、小黑屋、定时任务与通知 | **完全复用**：直接桥接 `TaskChainState`、`MaaCompositionService`、`TaskChainStatusTracker`、`ConfigBackupManager` |
| **适用场景** | 桌面端临时让 AI 点几下屏幕、无 MaaMeow 配置资产的场景 | 让 AI 管理/生成/执行 MaaMeow 的 **Profile 与脚本**、查运行日志、跑指定关卡/肉鸽/基建/抄作业 |

### 1.2 选型结论与理由

- **选择路线 B（MaaMeow Fork 薄 MCP Server + 服务端 Dify/RAGFlow）**，同时在文档中保留路线 A（`uvx maa-mcp`）作为纯 PC 侧零改动备选说明。
- **核心理由**：
  1. 用户在 MaaMeow 中的核心资产是 **`TaskProfile`（11 类 `TaskTypeInfo` 构成的完整日常链路、基建排班、干员养成计划、库存维护）** 以及 **Shizuku 虚拟屏后台挂机能力**。`maa-mcp` 只暴露底层 MaaFramework 原子操作，无法读写 MaaMeow 的 `TaskChainState` DataStore，也无法复用 MaaMeow 的虚拟屏保活、静音协调与完成通知。
  2. 通过在 MaaMeow 内置极薄的 `:ai-mcp`（仅把现有 `MaaCompositionService` / `TaskChainState` / `CopilotManager` / `MaaSessionLogger` 暴露为 MCP 工具，代码量控制在几百行薄胶水），既能复用手机端原生 `libMaaCore.so` + 虚拟屏，又能让 Dify/Claude 直接编排高层任务配置。

---

## 2. 逐项能力复用审计表

| 能力域 | 候选实现 | 仓库 / 坐标 | 许可证 | 最近维护状态 | 依赖体积 (APK 增量) | Android (minSdk 28) 可行性 | 决策 | 选型与自研边界说明 |
|---|---|---|---|---|---|---|---|---|
| **1. MCP Server 协议与传输（手机侧）** | **MCP Kotlin SDK (`kotlin-sdk-server`) + Ktor (`ktor-server-cio` + `ktor-server-sse`)** | `github.com/modelcontextprotocol/kotlin-sdk`<br>`io.modelcontextprotocol:kotlin-sdk-server:0.16.0`<br>`io.ktor:ktor-server-cio:3.6.1` | Apache-2.0 / MIT | 2026-10 活跃维护 (`0.16.0`) | R8 压缩后约 ~450 KB | **完全可行**：纯 Kotlin/JVM 协程实现，CIO 引擎无 Netty JNI 负担，支持 `mcpStreamableHttp` | **选用** | 官方 SDK 负责全部 JSON-RPC 2.0 帧、Session 管理、SSE/Streamable HTTP；业务侧仅注册 `addTool` 薄函数 |
| | `MAA-AI/MaaMCP` | `github.com/MAA-AI/MaaMCP` | AGPL-3.0 | 2026-07 (`v1.2.3`) | 0 KB（PC 侧运行） | 不可在 Android 进程内直接嵌入（Python + 桌面 MaaFramework 绑定） | 不进 APK（作为 PC 零改动对照） | 仅适用于 PC ADB 直连场景 |
| | 手写 HTTP + JSON-RPC Server | 自研 | — | — | — | — | **严禁** | 违反最高原则 §3，禁止手写 JSON-RPC 与 SSE 帧 |
| **2. MCP Server 单元测试传输** | **MCP Kotlin SDK Testing (`kotlin-sdk-testing` / `ChannelTransport`) + `kotlin-sdk-client`** | `io.modelcontextprotocol:kotlin-sdk-testing:0.16.0`<br>`io.modelcontextprotocol:kotlin-sdk-client:0.16.0` | Apache-2.0 / MIT | 2026-10 (`0.16.0`) | 0 KB (`testImplementation`) | **完全可行**：JVM 单测内存协程通道直连，无需起端口 | **选用** | 单测直接用内存 `Channel` 跑通 `Client.listTools()` 与 `Client.callTool()` |
| **3. 任务参数 JSON Schema 生成** | **`kotlinx-serialization` SerialDescriptor 反射生成** | 已内置：`org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0` | Apache-2.0 | 2026 活跃 (`1.12.0`) | **0 KB（复用既有依赖）** | **完全可行**：直接遍历 `TaskParamProvider` 各 `@Serializable` 子类的 `serializer().descriptor` | **选用** | 零新依赖，直接从 `TaskTypeInfo` 关联的 `TaskParamProvider.serializer().descriptor` 动态导出字段名、类型、默认值与枚举范围，杜绝手抄 Schema 漂移 |
| | `kotlinx-schema-generator` / `victools/jsonschema-generator` | `github.com/Ricky12Awesome/json-schema-serialization` / `victools` | Apache-2.0 | 维护较慢或依赖 Jackson | ~100 KB - 1.5 MB | Jackson 在 Android R8 下反射配置重 | 不选用 | 标准库 `SerialDescriptor` 50 行转换函数即可精确覆盖 Kotlin `sealed interface` + `data class` |
| **4. RAG + Agent + 知识库编排（脑）** | **Dify（Docker Compose 自托管）** | `github.com/langgenius/dify` | Dify Open Source License (基于 Apache-2.0 附加商用条款) | 2026 持续活跃 | **0 KB（服务端运行）** | 手机端仅通过 HTTP 调用 `/v1/chat-messages`，APK 零向量库负担 | **选用（主力）** | 原生提供文档切块、Embedding、混合检索、Agent 工具调用循环（支持连接外部 MCP Server）及 `/v1/chat-messages` SSE API |
| | **RAGFlow（Docker Compose 自托管）** | `github.com/infiniflow/ragflow` | Apache-2.0 | 2026 持续活跃 | **0 KB（服务端运行）** | 服务端运行，原生支持 MCP Server 暴露知识库 | **选用（备选）** | DeepDoc 复杂表格/结构化切块能力强，作为纯检索或无状态 Agent 备选方案提供配置 |
| | APK 内置 ONNX / 向量库 (ObjectBox / sqlite-vec / Sherpa) | 各端侧库 | — | — | +20 MB ~ 150 MB | 显著增加 APK 体积、内存与发热 | **严禁** | 明确违反 §2/§8 约束：向量库/embedding 模型/分块器一律不进 APK |
| **5. 明日方舟专用语料源** | **MAA 内置 `resource/` + PRTS + `ArknightsGameData` + `prts.plus` + 企鹅物流** | - `app/src/main/assets/MaaSync/MaaResource`<br>- `github.com/Kengxxiao/ArknightsGameData` (MIT/数据)<br>- `prts.wiki` (CC BY-NC-SA 4.0)<br>- `prts.plus` / `penguin-stats.io` | AGPL-3.0 / CC / MIT | 随游戏版本实时更新 | **0 KB（运行期在服务端索引，不打入 APK）** | 离线脚本提取实体稳定 ID 与切块上传 Dify/RAGFlow；手机端仅用 MAA 已自带的 `MaaResource` 做本地稳定 ID 校验 | **选用** | 语料切块脚本放 `scripts/rag/`，生成稳定实体块供 Dify 索引；防幻觉闸门直接读取已部署的 `MaaResource` 与 `TaskTypeInfo` |
| **6. HTTP 客户端与 SSE 流式解析（应用内聊天）** | **OkHttp (`okhttp3`)** | 已内置：`com.squareup.okhttp3:okhttp:5.5.1` | Apache-2.0 | 2026 (`5.5.1`) | **0 KB（复用既有依赖）** | **完全可行**：项目已全局使用 OkHttp | **选用** | 复用既有 `OkHttpClient` 读取 Dify `/v1/chat-messages` 流式响应，不引入第二套 HTTP 客户端 |
| **7. 聊天页 Markdown 渲染** | **`compose-markdown`** | 已内置：`com.github.jeziellago:compose-markdown:0.7.3` | MIT | 已在 `libs.versions.toml` | **0 KB（复用既有依赖）** | **完全可行**：已在项目中集成用于公告等 Markdown 渲染 | **选用** | 直接复用 `MarkdownText`，严禁自研 Markdown 解析/编辑器 |
| **8. 脚本格式与导入导出** | **`TaskProfile` + `ConfigBackupManager` + `kotlinx.serialization`** | 已内置：`com.aliothmoon.maameow.data.model.TaskProfile` / `ConfigBackupManager` | AGPL-3.0 | 本仓核心模型 | **0 KB（复用既有代码）** | **完全可行** | **选用** | 脚本即 `TaskProfile`，序列化复用 `JsonUtils.common`，与备份文件及 `ScheduledExecutionRequest.ACTION_LAUNCH_PROFILE` 完全互通 |
| **9. 设备连接、触控、截图与任务执行** | **`MaaCompositionService` + `StartTaskChainUseCase` + `RemoteServiceManager` + `FrameSnapshotter`** | 已内置：`com.aliothmoon.maameow.domain.service.*` | AGPL-3.0 | 本仓核心服务 | **0 KB（复用既有代码）** | **完全可行** | **选用** | Handler 仅做参数校验与委托调用，不自建连接或触控逻辑 |
| **10. 依赖注入与持久化设置** | **Koin + DataStore (`@PrefSchema` KSP)** | 已内置：`io.insert-koin:koin-android:4.2.2` + `androidx.datastore:datastore-preferences:1.3.0` | Apache-2.0 | 本仓基础设施 | **0 KB（复用既有代码）** | **完全可行** | **选用** | 在 `AppSettings` 新增 AI 控制开关/端口/Token/Dify 配置项，由 KSP 自动生成 DataStore Schema；**AI 默认关闭**，关闭时不启动 `AiControlService`、不初始化 Ktor |

---

## 3. 架构边界与防幻觉校验设计（定稿）

1. **模块划分**：
   - `:ai-mcp`（纯 Kotlin JVM/Android 兼容模块）：
     - 依赖 `io.modelcontextprotocol:kotlin-sdk-server`、`io.ktor:ktor-server-cio`、`io.ktor:ktor-server-sse`、`kotlinx-serialization-json`。
     - 定义薄接口 `MaaToolBridge`、JSON Schema 自动生成器 `TaskSchemaGenerator`（基于 `SerialDescriptor`）、防幻觉校验器 `ArknightsEntityValidator`、MCP Server 工厂 `MaaMcpServerFactory`。
     - 单测使用 `io.modelcontextprotocol:kotlin-sdk-client` + 内存 `ChannelTransport` 测试全部工具与防幻觉闸门。
   - `:app` 最小改动：
     - `AiControlService`（前台服务，仅在用户开启 AI 控制时启动 Ktor CIO `127.0.0.1` + Bearer Token 鉴权，桥接 `MaaCompositionService` / `TaskChainState` / `CopilotManager` / `MaaSessionLogger` / `FrameSnapshotter`）。
     - `ScriptRecorder`（监听任务运行时的实际生效 `List<MaaTaskParams>` / `TaskChainNode`，一键转为 `TaskProfile` 保存并兼容 `ConfigBackupManager`）。
     - `AiControlSettingsScreen` & `AiChatScreen`（设置开关页 + Dify `/v1/chat-messages` 薄客户端）。
     - 自动更新保护：当 AI 控制开启或处于 AI fork 构建时，默认关闭 `autoCheckUpdate` / `autoDownloadUpdate`，防止被上游官方包覆盖。
2. **零自研承诺核查**：
   - MCP 协议与传输：100% 由 `io.modelcontextprotocol:kotlin-sdk-server` 处理。
   - RAG / Embedding / 向量库 / 分块 / Agent 循环：100% 由服务端 Dify / RAGFlow 处理，APK 内 **0 字节** 向量/模型依赖。
   - HTTP / Markdown / JSON 序列化 / DI / DataStore：100% 复用 `app` 既有依赖。
