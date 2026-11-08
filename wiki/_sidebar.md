- **概览 (Overview)**
  - [项目定位与核心原则](README.md)

- **架构设计 (Architecture)**
  - [脑手分离设计草案](architecture/design_draft.md)
  - [逐项能力复用审计表](architecture/reuse_audit.md)
  - [账户个性化与练度自适应](architecture/account_personalization.md)
  - [异常回退与大模型自愈决策](architecture/ai_fallback_decision.md)

- **手侧实现与 MCP (The Hand: :ai-mcp)**
  - [20 项 MCP 工具规范与 Schema](hand/mcp_tools.md)
  - [服务生命周期与安全隐私闸门](hand/service_and_security.md)
  - [定时任务、自动唤醒与自启机制](hand/scheduled_tasks.md)
- **脑侧知识库与防幻觉 (The Brain: RAG & Eval)**
  - [明日方舟专用 RAG 语料库](brain/rag_knowledge_base.md)
  - [实体校验与防幻觉设计](brain/anti_hallucination.md)
  - [32 题评测集与评测基准](brain/evaluation_benchmark.md)

- **脚本与配置互通 (Scripting)**
  - [任务录制与 TaskProfile 零差回放](scripting/script_recorder.md)

- **客户端集成 (Clients)**
  - [应用内 Compose 聊天客户端](client/in_app_chat.md)
  - [AI 订阅与 API Key 接入指南](client/external_clients.md)
