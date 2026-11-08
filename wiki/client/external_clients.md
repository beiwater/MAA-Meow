# AI 订阅与 API Key 接入完全指南 (Subscription & API Key Guide)

很多博士最关心的问题是：**“我可以使用现有的 AI 订阅（如 Claude Pro/Max）或者自己的 API Key（如 DeepSeek、OpenAI、Kimi、通义千问等）来控制 MaaMeow 吗？需要自己写代码吗？”**

**答案：完全支持，且零改动、开箱即用！**

MaaMeow 的 `:ai-mcp` 严格遵循开放标准 **Model Context Protocol (MCP)**。只要客户端支持 MCP 工具调用（Tool Calling / Function Calling），就能直接通过标准协议操控手机上的明日方舟。

根据你的预算和使用习惯，目前有 **三大主流使用模式**：

```text
+----------------------------------------------------------------------------------------------------+
|                                    三大接入与使用模式对照表                                         |
+--------------------------+------------------------------+--------------------+---------------------+
| 使用场景                 | 推荐客户端                   | 计费与凭据方式     | 是否需要自建服务器  |
+--------------------------+------------------------------+--------------------+---------------------+
| 模式 1: 拥有现成 AI 订阅 | Claude Desktop (官方客户端)  | 扣 Claude Pro/Max  | 否 (纯桌面客户端)   |
| 模式 2: 拥有个人 API Key | Cherry Studio / Chatbox/Cursor| 扣 DeepSeek/OpenAI | 否 (零服务器客户端) |
| 模式 3: 手机 App 内直接聊| MaaMeow 原生「AI助手」界面   | Dify 托管 API Key  | 需自建 Docker Dify  |
+--------------------------+------------------------------+--------------------+---------------------+
```

---

## 1. 物理连接前提：通过 `adb forward` 端口映射

无论使用哪种客户端，由于手机默认仅监听 `127.0.0.1:18080`（保护隐私安全），电脑与手机连接（USB 线或无线调试）后只需执行一次：

```bash
# 将 PC 本地的 18080 端口映射到手机上的 18080 端口
adb forward tcp:18080 tcp:18080

# 验证转发是否生效
adb forward --list
```

> **提示**：可以在 MaaMeow 手机 App 的「设置 -> AI 控制设置」中复制你的 **Bearer Token**（如 `Bearer 3a7b...`）。

---

## 2. 模式 1：使用现有的 AI 订阅 (Claude Pro / Max)

如果你已经订阅了 Claude Pro 或 Claude Max，**完全不需要购买任何 API Key，也不需要自建任何服务器**，直接使用官方桌面客户端控制游戏。

### 2.1 配置步骤

1. 打开 Claude Desktop 的配置文件：
   - **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
   - **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
2. 在 `mcpServers` 中添加 `maameow` 项（采用官方推荐的 `streamable-http` 模式）：
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
3. 重启 Claude Desktop。
4. 打开 Claude 对话窗口，点击输入框右下角的 🔨 图标，即可看到自动挂载的 **20 个 MaaMeow 工具**（`maa_status`, `maa_run_profile`, `maa_search_wiki`, `maa_click`, `maa_back_to_home` 等）。

### 2.2 自然语言指令示例
直接对 Claude 说话即可触发执行：
- *“帮我看一下 MaaMeow 现在是什么状态，有没有在刷图？”* $\to$ 触发 `maa_status`
- *“帮我把理智刷在 1-7，打 5 次，然后去基建换班”* $\to$ 触发 `maa_create_profile` 与 `maa_run_profile`
- *“帮我抄这个作业：maa://1234567”* $\to$ 触发 `maa_load_copilot`

---

## 3. 模式 2：使用个人 API Key（零服务器方案，DeepSeek/OpenAI/Kimi等）

如果你拥有 **DeepSeek**（1 元百万 Token，极具性价比）、**OpenAI**、**Kimi (Moonshot)**、**通义千问 (Qwen)**、**智谱 GLM** 或 **OpenRouter** 的 API Key，无需自建 Docker 服务器，使用流行的开源桌面大模型客户端（如 **Cherry Studio**、**Chatbox** 或 **Cursor**）即可直接驱动。

以当下最流行、原生支持 MCP 的开源客户端 **Cherry Studio** 为例：

### 3.1 Cherry Studio 接入步骤

```text
+-------------------------------------------------------------------------------------------------+
|                                 Cherry Studio 接入流程示意                                      |
|                                                                                                 |
|   1. 设置 -> 模型服务                               2. 设置 -> MCP 服务器                        |
|   +---------------------------------------+         +---------------------------------------+   |
|   | 提供商: DeepSeek                      |         | 名称: MaaMeow                         |   |
|   | API Key: sk-xxxx                      |         | 类型: Streamable HTTP (或 SSE)        |   |
|   | 模型: deepseek-chat (V3) 或 deepseek-r1|        | URL: http://127.0.0.1:18080/mcp       |   |
|   +---------------------------------------+         | 请求头: Authorization: Bearer <Token> |   |
|                                                     +---------------------------------------+   |
|                                                                     |                           |
|   3. 新建对话 -> 勾选 Agent / MCP 插件 -----------------------------+                           |
|   -> 自然语言对话: "帮我看看现在基建换班情况" -> DeepSeek V3 自动推理并调用 maa_status / maa_run_profile! |
+-------------------------------------------------------------------------------------------------+
```

1. **填写 API Key**：在 Cherry Studio 设置中的「模型服务」添加你的服务商（如 DeepSeek，输入 `sk-...`），推荐选用具备强函数调用能力的模型（如 `deepseek-chat` 或 `gpt-4o`）。
2. **添加 MCP 服务器**：
   - 进入「设置」->「MCP 扩展 / 服务器」；
   - 点击「添加服务器」；
   - **名称**：`MaaMeow`；
   - **协议类型**：`Streamable HTTP` 或 `SSE`；
   - **URL**：`http://127.0.0.1:18080/mcp`；
   - **自定义 Headers**：`Authorization: Bearer YOUR_BEARER_TOKEN`。
3. **测试连接**：点击测试，Cherry Studio 将自动拉取 20 个工具的 Schema。
4. **开始对话**：新建对话，在输入框上方开启「智能体模式 (Agent)」并勾选 `MaaMeow` 工具，即可让 DeepSeek / GPT 全权代理手机游戏！

---

## 4. 模式 3：使用 API Key + 手机 App 内聊天 (Dify 方案)

如果你希望**脱离电脑**，在手机上的 MaaMeow App 里随时随地打开「AI 对话」页面进行聊天和控制：

### 4.1 拓扑架构
- **服务端**：在家用 NAS、PC 或云服务器上一键运行 Dify (Docker Compose)；
- **模型配置**：在 Dify 的「设置 -> 模型供应商」填入你的 DeepSeek / OpenAI API Key；
- **知识库挂载**：运行 `python scripts/build_rag_corpus.py` 生成的语料上传到 Dify 知识库；
- **MCP 工具连接**：在 Dify 的「工具」中配置手机的 MCP 端点；
- **手机 App 连接**：在 MaaMeow App 内填写 Dify 暴露的 Base URL 和 API Key。

### 4.2 手机 App 设置页面配置

在手机 MaaMeow 进入「设置 -> AI 控制设置」：
- **开启 AI 控制服务**：打开总开关；
- **服务监听端口**：默认 `18080`；
- **Bearer Token**：系统自动生成，可点击一键复制；
- **Dify API 基础路径**：填写 `http://<你的服务器IP>:port/v1`；
- **Dify API Key**：填写 Dify Studio 应用的 `app-xxxx` Key。

配置完毕后，进入首页导航中的 **「AI 助手」** 界面，即可在手机上直接与装载了明日方舟知识库的大模型进行流式问答、查看工具调用卡片与一键下发任务！

---

## 5. 常见问题排查 (FAQ)

### Q1: 没有 Claude Pro 订阅，也没有国外信用卡，怎么用最便宜？
**答**：推荐 **模式 2**。前往国内大模型平台（如 **DeepSeek 开放平台** `platform.deepseek.com`）注册并充值 5~10 元，获取 `sk-...` 开头的 API Key，然后在电脑上下载开源免费的 **Cherry Studio**，配置 DeepSeek V3 模型 + MaaMeow MCP。几块钱足够日常高强度使用数月！

### Q2: 电脑配置完后提示 `401 Unauthorized`？
**答**：说明 Bearer Token 错误或请求头格式不对。请确认请求头填写的是 `Authorization: Bearer <实际Token>`，注意 `Bearer` 和 Token 之间有一个空格。

### Q3: 电脑提示 `Connection Refused`？
**答**：请检查：
1. 手机端 MaaMeow 设置中「开启 AI 控制服务」开关是否已处于开启状态；
2. 手机是否已开启 USB 调试且已在电脑命令行执行 `adb forward tcp:18080 tcp:18080`。
