# 架构选型与能力复用审计表 (Reuse Audit & Technical Decision)

> **最高研发原则**：能复用不自研，写代码排在第二位。
>
> 每一项技术需求在动笔写代码前，必须先全面排查开源社区与项目内已有的成熟实现，比对维护状态、许可证、依赖体积与 Android 可行性，唯有完全不可替代且属于纯业务薄胶水时，方可编写少量适配代码。
> 
> **绝对禁止自研领域**：MCP 协议栈、JSON-RPC 2.0 序列化/反序列化、SSE 流式通道、移动端向量数据库、Embedding 模型推理、自研文档分块器、Agent 推理循环、Markdown 渲染引擎、HTTP 客户端。

---

## 1. 架构路线对比：零改动路线 vs. MaaMeow Fork 路线

在项目立项之初，团队对 AI 控制架构进行了两条技术路线的深入评估与论证：

### 1.1 路线对比矩阵

| 评估维度 | 路线 A：零改动路线（PC `uvx maa-mcp` + Dify + ADB） | 路线 B：MaaMeow Fork 路线（内置 `:ai-mcp` 薄 Server + 服务端 Dify） |
|---|---|---|
| **代表项目** | `MAA-AI/MaaMCP` (PyPI `maa-mcp` v1.2.3, AGPL-3.0) | `:ai-mcp`（基于官方 Kotlin MCP SDK）+ 原生 `MaaCompositionService` |
| **控制与抽象粒度** | **底层原子触控/OCR 级**<br>(`adb screencap`、`adb shell input tap`、底层 pipeline 匹配) | **应用业务配置级 (High-Level Business Logic)**<br>直接读写与编排 `TaskProfile`、`TaskChainNode`、`CopilotConfig`、`TaskParamProvider` |
| **运行物理拓扑** | **强绑定 PC 宿主机**<br>PC 必须常驻 Python 运行环境、MaaFramework 桌面动态库以及常开的 ADB 有线/无线连接 | **Android 手机原生自闭环**<br>手机端原生运行 `libMaaCore.so` + Shizuku 虚拟屏，离线挂机；云端/PC 随时可通过网络调用 |
| **与 MaaMeow 状态互通** | **完全割裂**<br>外部通过 ADB 强行点击无法感知 MaaMeow 内部的公招算法、基建换班排班表、信用商店清单、小黑屋与定时调度 | **100% 互通**<br>直连 `TaskChainState`、`TaskChainStatusTracker`、`ConfigBackupManager`，支持配置双向互认与零差备份 |
| **断网与抗干扰能力** | 脆弱：网络微小抖动或 ADB 进程断开即导致整个任务中断 | 强韧：Dify 下发任务启动命令后，手机端原生任务链自治执行，无需长连接挂起 |
| **适用场景** | 桌面模拟器临时让 AI 随便点点看、无移动端挂机需求的用户 | **需要深度管理日常日常链、基建生产、活动挂机、一键抄作业的核心用户** |

### 1.2 选型定稿结论与理由

**决定采用：路线 B（MaaMeow Fork 内置 `:ai-mcp` 极薄 Server + 服务端 Dify/RAGFlow）**。

#### 核心理由阐述：
1. **用户核心数字资产的传承**：用户在使用 MaaMeow 时，真正依赖的是其经过数年打磨的 **11 类日常任务链（`TaskTypeInfo`）、公招保底计算器、基建疲劳度轮换算法以及 Shizuku 虚拟屏免打扰后台运行能力**。路线 A 的 `maa-mcp` 纯粹只是通过 ADB 发送坐标点击，完全丢弃了 App 内数十万行经过实战检验的高级业务逻辑。
2. **极薄侵入（几百行胶水）**：路线 B 并非在移动端重写一套框架，而仅仅是在项目内增加一个只有几百行 Kotlin 代码的 `:ai-mcp` 模块。该模块直接使用 Anthropic 官方的 `kotlin-sdk-server`，将既有的 `MaaCompositionService`、`TaskChainState` 和 `CopilotManager` 对外暴露为 20 个干净的 MCP 工具。
3. **保留路线 A 作为 PC 端纯对照方案**：在文档与工具生态中，仍完整保留对路线 A 的操作指引，供仅拥有桌面 Python 环境的用户选用。

---

## 2. 逐项能力复用审计表 (10 大核心能力域)

以下为 MaaMeow AI Control 涵盖的全部 10 项能力域复用评估。每一项均明确了候选者、依赖坐标、许可证、体积成本以及选型决策。

| 序号 | 能力域 | 候选技术实现 | 仓库 / 坐标 | 开源协议 | 维护状态 | 依赖体积 (APK 增量) | Android (minSdk 28) 可行性 | 选型决策 | 选型分析与自研边界约束 |
|:---:|---|---|---|:---:|:---:|:---:|:---:|:---:|---|
| **1** | **MCP 协议栈与传输层 (手机端)** | **MCP Kotlin SDK (`kotlin-sdk-server`) + Ktor CIO (`ktor-server-cio` + `ktor-server-sse`)** | `io.modelcontextprotocol:kotlin-sdk-server:0.16.0`<br>`io.ktor:ktor-server-cio:3.6.1`<br>`io.ktor:ktor-server-sse:3.6.1` | Apache-2.0 / MIT | 2026-10 活跃维系 | R8 压缩后约 ~450 KB | **完全可行**<br>纯 Kotlin/JVM 协程驱动，CIO 轻量非阻塞，无 Netty 的 JNI C++ 动态库负担 | **选用 (核心)** | **官方 SDK 处理全部 JSON-RPC 帧规范、会话上下文与 SSE 协议**。业务侧仅注册 `server.addTool` 薄包装，代码量 < 300 行。 |
| | | `MAA-AI/MaaMCP` | `github.com/MAA-AI/MaaMCP` | AGPL-3.0 | 2026-07 (v1.2.3) | 0 KB (仅运行于 PC) | 无法在 Android 进程内直接运行 (依赖 Python 环境与桌面 MaaFramework) | **不进入 APK** | 作为 PC 直连路线对照保留。 |
| | | 手写 Socket / HTTP + 手写 JSON-RPC | 自研轮子 | — | — | 0 KB | 充斥并发 Bug 与协议不合规风险 | **绝对禁止** | 严禁自行手写任何 JSON-RPC 2.0 序列化及 SSE 解析逻辑。 |
| **2** | **MCP 单元测试通道** | **MCP Kotlin SDK Testing (`kotlin-sdk-testing`) + `kotlin-sdk-client`** | `io.modelcontextprotocol:kotlin-sdk-testing:0.16.0`<br>`io.modelcontextprotocol:kotlin-sdk-client:0.16.0` | Apache-2.0 / MIT | 2026-10 活跃 | **0 KB**<br>(仅限 `testImplementation`) | **完全可行**<br>基于内存 Kotlin 协程通道 (`ChannelTransport`)，单测完全脱离网络端口 | **选用** | 单元测试无需在测试机上绑定真实 Socket 端口，毫秒级测试全部 20 项工具与非法实体防幻觉拦截。 |
| **3** | **任务参数 JSON Schema 生成** | **`kotlinx.serialization` SerialDescriptor 反射动态生成** | 已内置：`org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0` | Apache-2.0 | 持续活跃维护 | **0 KB**<br>(复用项目既有核心依赖) | **完全可行**<br>直接解析已打上了 `@Serializable` 的 `TaskParamProvider` 各类描述符 | **选用** | **零新依赖，零手写维护**。<br>直接从 Kotlin `data class` 自动导出字段类型、默认值与枚举范围，从源头杜绝文档与实现脱节。 |
| | | `victools/jsonschema-generator` / `json-schema-serialization` | 第三方 Java/Kotlin 反射库 | Apache-2.0 | 维护缓慢且依赖 Jackson 反射 | +300 KB ~ 1.5 MB | Jackson 在 Android R8/Proguard 下混淆规则极其繁重 | **淘汰** | 不引入重型反射依赖。标准库 `SerialDescriptor` 50 行递归遍历即可覆盖全部 `data class`。 |
| **4** | **RAG 向量检索与 Agent 编排 (脑)** | **Dify (Docker Compose 自托管)** | `github.com/langgenius/dify` | Dify Open Source License | 活跃演进 | **0 KB**<br>(服务端独立运行) | **完全可行**<br>手机端仅通过普通 HTTP 消费其暴露的 `/v1/chat-messages` | **选用 (主力)** | 原生集成高质量切块分词、向量混合检索、Agent 思考循环以及直连外部 MCP 端点能力。 |
| | | **RAGFlow (Docker Compose 自托管)** | `github.com/infiniflow/ragflow` | Apache-2.0 | 持续维护 | **0 KB**<br>(服务端独立运行) | 具备 DeepDoc 复杂表格结构化提取能力，作为备选方案 | **选用 (备选)** | 文档提供接入配置指南，供追求复杂表格精准检索的进阶用户选用。 |
| | | 端侧嵌入向量库 (ObjectBox-Vector / sqlite-vec / Sherpa) | 各类端侧嵌入式向量库 | 各异 | — | +25 MB ~ 180 MB | 严重违背移动端耗电、发热及内存约束；知识库更新需强推全量 APK | **绝对禁止** | 恪守「脑手分离」原则，坚决不允许向量库与大模型打入手机端 APK。 |
| **5** | **明日方舟专用语料源** | **MAA 内置资源 + PRTS + `ArknightsGameData` + 企鹅物流** | `MaaResource` (本地已有)<br>`Kengxxiao/ArknightsGameData`<br>`prts.wiki` / `penguin-stats.io` | CC BY-NC-SA 4.0 / MIT / AGPL-3.0 | 随游戏版本实时同步 | **0 KB**<br>(通过离线脚本生成并在服务端索引，不打入 APK) | 离线脚本提取实体稳定 ID 并切块，手机端仅读取本包已有的 `MaaResource` 进行防幻觉校验 | **选用** | 语料提取脚本收敛于 `scripts/build_rag_corpus.py`，生成高密度 JSONL；手机端防幻觉完全基于已有本地资产。 |
| **6** | **应用内聊天 HTTP & SSE 流式通信** | **OkHttp (`okhttp3`)** | 已内置：`com.squareup.okhttp3:okhttp:5.5.1` | Apache-2.0 | 业界标准网络库 | **0 KB**<br>(复用项目既有全局依赖) | **完全可行**<br>项目网络层基础设施 | **选用** | 复用项目单例 `OkHttpClient` 读取 Dify `/v1/chat-messages` SSE 响应事件，杜绝引入第二套网络客户端。 |
| **7** | **聊天界面 Markdown 渲染** | **`compose-markdown`** | 已内置：`com.github.jeziellago:compose-markdown:0.7.3` | MIT | 稳定可靠 | **0 KB**<br>(复用项目既有依赖) | **完全可行**<br>Jetpack Compose 原生适配 | **选用** | 统一采用 `MaaMarkdownText` 封装，严禁自研 Markdown 解析器或嵌入 WebView。 |
| **8** | **自动化脚本存储与迁移** | **`TaskProfile` + `ConfigBackupManager`** | 本项目核心模型与备份器 | AGPL-3.0 | 本仓核心代码 | **0 KB**<br>(复用既有架构) | **完全可行** | **选用** | 脚本在底层即是标准的 `TaskProfile`，格式完全对齐，可直接无缝使用 App 的导入/导出备份功能。 |
| **9** | **设备执行、触控、截图与调度** | **`MaaCompositionService` + `StartTaskChainUseCase` + `FrameSnapshotter`** | 本项目核心领域服务 | AGPL-3.0 | 本仓核心代码 | **0 KB**<br>(复用既有服务) | **完全可行** | **选用** | MCP 工具 Handler 仅作参数解包与转发，不自写任何底层的 ADB 交互、触控分发或 JNI 调度。 |
| **10** | **依赖注入与参数持久化** | **Koin + DataStore (`@PrefSchema` KSP 代码生成)** | 已内置：`koin-android:4.2.2`<br>`datastore-preferences:1.3.0` | Apache-2.0 | 持续演进 | **0 KB**<br>(复用既有基础设施) | **完全可行** | **选用** | 在 `AppSettings` 中声明 AI 控制参数，由 KSP 自动生成类型安全的 DataStore 读写代码。 |

---

## 3. 研发边界核查矩阵 (Zero-Waste Compliance)

为确保工程落地不走样、不退化，所有代码提交均需通过以下 5 项「零自研承诺」核查：

1. **协议层合规性**：
   - 是否包含自研 JSON-RPC 编解码？$\to$ **否**，100% 由 `io.modelcontextprotocol:kotlin-sdk-server` 承担。
   - 是否自行管理 SSE 心跳与会话生命周期？$\to$ **否**，100% 由 Ktor CIO 与 SDK 内部管理。
2. **端侧纯洁性**：
   - APK 内是否包含任何向量检索库（`.so` 或 Java/Kotlin 依赖）？$\to$ **否**，体积增量为 0。
   - APK 内是否包含任何 ONNX / TFLite 大模型权重？$\to$ **否**，模型完全部署于服务端。
3. **参数描述一致性**：
   - 任务参数 Schema 是否存在硬编码维护的 JSON 文本？$\to$ **否**，100% 由 `TaskSchemaGenerator` 从 `SerialDescriptor` 动态提取。
4. **资产互通完备性**：
   - AI 生成或录制的脚本是否需要专属转换器才能被常规 MaaMeow 执行？$\to$ **否**，产出即标准 `TaskProfile`，与用户手动创建的配置完全等价。
5. **冷启动与常态消耗**：
   - 用户在未开启 AI 控制时，是否有任何常驻守护线程或服务被唤起？$\to$ **否**，`aiControlEnabled` 默认关闭，零开销。
