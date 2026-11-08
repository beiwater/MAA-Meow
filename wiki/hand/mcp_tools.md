# 20 项 MCP 工具规范与 Schema 体系 (MCP Tools Specification)

`:ai-mcp` 模块基于官方 `io.modelcontextprotocol:kotlin-sdk-server` 实现了 20 个标准 MCP 工具，将 MaaMeow 的核心自动化能力、数据透视与突发事件应急干预手段全面开放给大语言模型智能体（如 Dify、Claude Desktop、Cherry Studio）。

---

## 1. 工具全景概览表

| 编号 | 工具名称 | 参数签名 | 核心行为与返回值 | 底层复用组件与类 |
|:---:|---|---|---|---|
| 1 | `maa_status` | *无* | 查询执行器状态 (`IDLE`/`RUNNING` 等)、活跃 Profile ID 与各节点进度 | `MaaCompositionService` + `TaskChainStatusTracker` |
| 2 | `maa_list_task_types` | `type_id?` (可选过滤) | 动态导出 11 类任务的完整 JSON Schema 与默认配置 | `TaskSchemaGenerator` + `TaskTypeInfo` |
| 3 | `maa_list_profiles` | *无* | 列出持久化存储中的所有 `TaskProfile` 简要信息 | `TaskChainState.profiles` |
| 4 | `maa_get_profile` | `profile_id?` (默认当前) | 获取指定 Profile 的完整节点链 (`List<TaskChainNode>`) | `TaskChainState` |
| 5 | `maa_create_profile` | `name`, `chain` | 经过实体与未知字段强校验后，新增自定义 `TaskProfile` | `ArknightsEntityValidator` + `TaskChainState` |
| 6 | `maa_update_profile` | `profile_id`, `name?`, `chain?` | 校验通过后更新已有 `TaskProfile` 的名称或任务链 | `ArknightsEntityValidator` + `TaskChainState` |
| 7 | `maa_delete_profile` | `profile_id`, `confirm=true` | 显式安全确认后删除指定 Profile（防误删唯一配置） | `TaskChainState.removeProfile` |
| 8 | `maa_run_profile` | `profile_id?`, `force?` | 异步立即触发启动并返回 `run_id`，对齐强制启动策略 | `StartTaskChainUseCase` + `MaaCompositionService` |
| 9 | `maa_stop` | *无* | 立即安全中止当前正在运行的任务链或抄作业进程 | `MaaCompositionService.stop` |
| 10 | `maa_save_script` | `name`, `chain?` | 将录制草稿或上一轮实际生效的参数快照存为脚本 Profile | `AiScriptRecorder` + `TaskChainState` |
| 11 | `maa_list_scripts` | *无* | 列出录制草稿中的节点与已持久化的脚本 Profile | `AiScriptRecorder` + `TaskChainState` |
| 12 | `maa_run_script` | `profile_id`, `force?` | 异步执行已保存的脚本并立即返回 `run_id` | `StartTaskChainUseCase` |
| 13 | `maa_get_logs` | `limit?` (默认100), `redact?` (默认true)| 读取会话诊断日志，默认经过敏感信息脱敏处理器 | `MaaSessionLogger` + `LogRedactor` |
| 14 | `maa_screenshot` | *无* | 捕获当前游戏画面 JPEG Base64（需显式开启截图授权） | `FrameSnapshotter` (`RemoteService.captureFrameJpeg`)|
| 15 | `maa_load_copilot` | `copilot`, `config?` | 解析 `maa://` 作业码或 Copilot JSON 并异步拉起自动作战 | `CopilotManager` + `MaaCompositionService.startCopilot` |
| 16 | `maa_search_wiki` | `query`, `wiki?` ("auto"\|"prts"\|"wiki_gg"), `limit?` (默认5) | 在线联网检索明日方舟双源百科 (PRTS Wiki / arknights.wiki.gg)，支持双源智能路由与海外 CDN 降级 | MediaWiki API (PRTS / wiki.gg) + OkHttp |
| 17 | `maa_get_user_data` | `category?` ("operators"\|"depot"\|"all", 默认"all"), `filter?` (干员名或物品名过滤) | 查询玩家真实干员箱（星级、精二、等级、潜能、专精、模组）与仓库库存，支持个性化与千人千面自适应 | `OperBoxRepository` + `DepotRepository` + `ItemHelper` |
| 18 | `maa_get_wiki_article` | `title` (页面标题), `wiki?` ("auto"\|"prts"\|"wiki_gg", 默认"auto") | 获取百科词条全文并转换为纯净标准 Markdown 格式，具备双源路由与海外秒级降级能力 | MediaWiki API (`prop=extracts&explaintext=1` / `parse`) + OkHttp |
| 19 | `maa_click` | `x`, `y` | 向游戏画面指定坐标发送单次精准应急点击（1280x720 空间），处理突发活动弹窗/签到关闭/断线重连 | `RemoteServiceManager` + `MaaCoreService.AsyncClick` |
| 20 | `maa_back_to_home` | *无* | 调用 MAA Core 原生 BackToHome 状态机，从卡死、未知子界面或脱轨状态强制安全返回主大厅 | `RemoteServiceManager` + `MaaCoreService.BackToHome` |

---

## 2. 动态 Schema 自动生成机制 (`TaskSchemaGenerator`)

在以往的自动化 Agent 接入中，开发者往往需要手工编写庞大的 JSON Schema 声明，不仅极其耗时，而且一旦数据模型新增字段（如新活动特定参数），Schema 就会发生严重漂移。

MaaMeow 通过 Kotlin 官方的 `kotlinx.serialization` 反射机制实现了 **动态 0 维护 Schema 导出**：
1. 所有的任务配置（如 `FightConfig`、`InfrastConfig`、`RecruitConfig`）均为打上了 `@Serializable` 的 `TaskParamProvider` 实现类；
2. `TaskSchemaGenerator` 递归遍历其 `serializer().descriptor`：
   - 提取 `SerialKind`（如 `PrimitiveKind.STRING` $\to$ `"type": "string"`, `StructureKind.LIST` $\to$ `"type": "array"`）；
   - 从 Kotlin 属性的默认实例中自动提取 `default` 默认值；
   - 提取枚举所有取值生成 `enum` 限制。
3. 杜绝任何手动维护的 Schema 文本，保持与底层业务代码绝对同步。

---

## 3. 逐项工具详细规范

### 3.1 `maa_status`
- **功能描述**：查询当前调度器执行状态、正在执行的 Profile 以及各个任务节点的完成进度。
- **输入参数**：无。
- **返回示例**：
```json
{
  "state": "RUNNING",
  "activeProfileId": "default_profile",
  "currentRunId": "run_20261108_104201",
  "tasks": [
    {
      "nodeId": "node-wakeup",
      "taskType": "START_UP",
      "taskName": "开始唤醒",
      "status": "COMPLETED",
      "detail": "游戏客户端已启动就绪"
    },
    {
      "nodeId": "node-fight",
      "taskType": "FIGHT",
      "taskName": "刷理智",
      "status": "RUNNING",
      "detail": "当前关卡: 1-7 (正在进行第 3/5 次战斗)"
    }
  ]
}
```

---

### 3.2 `maa_list_task_types`
- **功能描述**：列出系统支持的任务类型，并附带动态生成的完整 JSON Schema 和默认配置字典。
- **输入参数**：
  - `type_id` (*string, 可选*)：任务类型过滤标识（如 `COMBAT`、`BASE`、`RECRUITING`、`AUTO_ROGUELIKE`）。
- **返回示例 (节选)**：
```json
{
  "task_types": [
    {
      "type_id": "COMBAT",
      "config_type": "com.aliothmoon.maameow.data.model.FightConfig",
      "short_name": "FightConfig",
      "description": "刷理智与关卡作战 (支持自动碎石/吃理智药/掉落统计)",
      "schema": {
        "type": "object",
        "properties": {
          "stage1": { "type": "string", "default": "" },
          "useMedicine": { "type": "boolean", "default": false },
          "medicineNumber": { "type": "integer", "default": 0 },
          "hasTimesLimited": { "type": "boolean", "default": false },
          "maxTimes": { "type": "integer", "default": 1 }
        }
      },
      "default_config": {
        "stage1": "",
        "useMedicine": false,
        "medicineNumber": 0,
        "hasTimesLimited": false,
        "maxTimes": 1
      }
    }
  ]
}
```

---

### 3.3 `maa_list_profiles` & `maa_get_profile`
- **`maa_list_profiles`**：返回全部配置列表（包含 `id`、`name`、`nodeCount`、`isActive`）。
- **`maa_get_profile`**：
  - **输入参数**：
    - `profile_id` (*string, 可选*)：目标 Profile 唯一 ID。不填时返回当前处于激活状态的 Profile。
  - **返回示例**：
```json
{
  "id": "daily_rock_farm",
  "name": "每日石头与基建",
  "chain": [
    {
      "id": "node-1",
      "name": "刷理智",
      "enabled": true,
      "order": 0,
      "config": {
        "stage1": "1-7",
        "hasTimesLimited": true,
        "maxTimes": 10
      }
    }
  ]
}
```

---

### 3.4 `maa_create_profile` & `maa_update_profile`
- **防幻觉前置拦截**：传入的 `chain` 数组会首先经过 `ArknightsEntityValidator` 验证。若关卡不存在、干员不存在或存在未经声明的未知参数字段，直接阻断写入。
- **`maa_create_profile` 输入参数**：
  - `name` (*string, 必填*)：配置显示名称。
  - `chain` (*array, 必填*)：任务链节点定义数组。
- **校验失败返回**：
```json
{
  "valid": false,
  "errors": [
    {
      "path": "chain[0].config.stage1",
      "invalidValue": "99-99",
      "reason": "Unknown stage ID/code '99-99' (not found in PRTS/Arknights-Tile-Pos or permanent stages)",
      "candidates": ["1-7", "CE-6", "LS-6"]
    }
  ]
}
```

---

### 3.5 `maa_delete_profile`
- **安全约束**：
  - 必须显式传递 `confirm=true`，防止模型无意删除配置；
  - 若系统仅剩最后一个 Profile，拒绝删除，保证 App 内部状态不崩塌。
- **输入参数**：
  - `profile_id` (*string, 必填*)
  - `confirm` (*boolean, 必填*)

---

### 3.6 `maa_run_profile` & `maa_stop`
- **`maa_run_profile`**：
  - **核心特性**：**异步非阻塞立即返回**。调用后立即向系统派发 `StartTaskChainUseCase`，生成唯一的 `run_id` 并返回给调用方，模型无需在 HTTP 连接上死等任务结束。
  - **输入参数**：
    - `profile_id` (*string, 可选*)：启动的配置 ID（缺省为当前激活配置）；
    - `force` (*boolean, 可选*)：对应界面的「强制启动」标志，忽略当前正在进行的单步。
  - **返回**：`{"run_id": "run_20261108_1102", "status": "STARTING"}`。
- **`maa_stop`**：
  - 中止当前执行，立即向 `MaaCompositionService` 发送取消信号，并在数秒内完成清理。

---

### 3.7 `maa_save_script`、`maa_list_scripts`、`maa_run_script`
- **定位**：解决用户在手动或由 AI 执行时，希望把“刚才生效的那一整套参数”固化为固定自动化脚本的诉求。
- **输入参数 (`maa_save_script`)**：
  - `name` (*string, 必填*)：保存的脚本名称；
  - `chain` (*array, 可选*)：可选的节点覆盖覆盖项。若为空，则直接截取最近一次真实运行生效的 `List<MaaTaskParams>`。

---

### 3.8 `maa_get_logs`
- **输入参数**：
  - `limit` (*integer, 默认 100*)：最大返回日志行数；
  - `redact` (*boolean, 默认 true*)：是否脱敏。
- **脱敏策略**：自动对日志中出现的 Bearer Token、账号 ID、CDK 及本地敏感路径进行掩码替换（如 `Bearer sk-****`）。

---

### 3.9 `maa_screenshot` (隐私防护门控)
- **输入参数**：无。
- **安全约束**：**默认禁用**。必须在 MaaMeow 的 AI 设置中打开「允许 AI 获取游戏截图」开关，否则直接返回错误文本。
- **返回格式**：标准 MCP `ImageContent`，MIME 类型为 `image/jpeg`，内含 Base64 编码的 JPEG 画面数据。

---

### 3.10 `maa_load_copilot`
- **功能描述**：用于一键导入 PRTS 作业站（`prts.plus`）或作业分享码（`maa://...`）并直接运行抄作业模式。
- **输入参数**：
  - `copilot` (*string, 必填*)：作业码（`maa://`）、PRTS 链接或作业 JSON 文本；
  - `config` (*object, 可选*)：额外执行配置（如替换干员、等级约束等）。
- **行为**：底层交给 `CopilotManager` 解析并调用 `MaaCompositionService.startCopilot` 启动作战。

---

### 3.16 `maa_search_wiki` (双源智能 Wiki 检索与海外极速优化)
- **功能描述**：在线联网检索明日方舟官方 PRTS Wiki 或海外英文 Arknights Terra Wiki 的百科词条、关卡攻略、干员天赋机制与敌人抗性数据，为大模型提供实时动态知识支撑。
- **双源知识库对比与海外路由设计**：
  1. **中文版 PRTS Wiki (`https://prts.wiki/w/首页`)**：
     - 适用场景：国内网络环境与简体中文原生语料；
     - 优势：收录最全的国服最新活动、中文干员外号、基建排班与中文剧情记录；
     - 海外痛点：境内服务器，部分海外节点访问延迟较高或偶尔 SSL 握手超时。
  2. **英文版 Arknights Terra Wiki (`https://arknights.wiki.gg/`)**：
     - 适用场景：海外网络环境或纯英文/拼音查询；
     - 优势：依托全球 **Cloudflare CDN** 节点，海外访问延迟低至数十毫秒，抗波动能力极强；
     - 适合用户：身处国外或使用境外服务器部署 Dify/LLM 的博士。
- **输入参数**：
  - `query` (*string, 必填*)：搜索关键词或自然语言问题（如 `"维什戴尔 天赋"`, `"SilverAsh"`, `"1-7"`, `"爱国者 阶段抗性"`）；
  - `wiki` (*string, 可选, 默认 `"auto"`*)：目标知识库源，支持：
    - `"auto"`（默认）：**智能多活与自动降级**。纯英文/拉丁字母自动走 `arknights.wiki.gg`；包含中文优先请求 `prts.wiki`，若发生网络超时（海外网络波动）则**自动秒级降级 fallback 至 `arknights.wiki.gg`**；
    - `"prts"`：强制使用中文 PRTS Wiki；
    - `"wiki_gg"`：强制使用海外英文 Arknights Terra Wiki (`arknights.wiki.gg`)。
  - `limit` (*integer, 可选, 默认 5, 上限 20*)：最大返回搜索结果条数。
- **返回格式**：结构化 Markdown 文本，包含条目标题、高亮摘要/片段以及标准超链接（`https://prts.wiki/w/...` 或 `https://arknights.wiki.gg/wiki/...`）。
- **返回示例 (auto 命中海外 wiki.gg)**：
```markdown
### Arknights Wiki Search Results for **SilverAsh** (Arknights Terra Wiki (https://arknights.wiki.gg)):

1. **[SilverAsh](https://arknights.wiki.gg/wiki/SilverAsh)**
   - SilverAsh Base Elite 2 York's Bise Seeker SKm01 Never-melting Ice Based on Snow leopard (Panthera uncia)
   - 链接 / URL: https://arknights.wiki.gg/wiki/SilverAsh

2. **[SilverAsh the Reignfrost](https://arknights.wiki.gg/wiki/SilverAsh_the_Reignfrost)**
   - Internal name char_1045_svash2 In Other Languages Chinese 凛御银灰 Japanese 凛御りんぎょシルバーアッシュ
   - 链接 / URL: https://arknights.wiki.gg/wiki/SilverAsh_the_Reignfrost
```
- **CLI 命令行调试支持**：
可在项目根目录下直接使用提供的双源搜索脚本调试：
```bash
# 默认 auto 智能路由（中文优先 PRTS，超时自动降级 wiki.gg；英文自动走 wiki.gg）
python scripts/search_arknights_wiki.py "1-7"

# 显式指定使用海外英文版 arknights.wiki.gg (极速 Cloudflare CDN)
python scripts/search_arknights_wiki.py "SilverAsh" --wiki wiki_gg

# 显式指定使用中文 PRTS Wiki
python scripts/search_arknights_wiki.py "黍 天赋" --wiki prts
```

---

### 3.17 `maa_get_user_data` (账户个性化干员箱与仓库查询)
- **功能描述**：实时查询玩家本人的实际干员练度（干员箱 `OperBox`：是否精二、等级、潜能、技能专精层级、已装配模组）以及仓库素材持有量（`Depot`）。解决「千人千面，每个人的干员都不一样」的核心痛点，让大模型在推荐作业平替干员、规划材料刷取、基建排班优化时，直接基于玩家本人的真实练度底账做精准决策。
- **数据源与生命周期**：
  - 数据来源于 MaaMeow 既有的 `OperBoxRepository` 与 `DepotRepository`（本地 JSON/Proto 持久化仓储）。
  - 若用户曾运行过一次 `UserDataUpdateConfig(updateOperBox = true, updateDepot = true)` 任务，则自动持有最新同步数据 (`hasSynced: true`, `syncTimeMillis: ...`)。
  - 若尚未同步 (`hasSynced: false`)，大模型可主动提示博士进游戏自动扫描，或自动在任务链中下发干员箱更新任务。
- **输入参数**：
  - `category` (*string, 可选, 默认 `"all"`*)：
    - `"operators"`：仅查询干员拥有情况与练度；
    - `"depot"`：仅查询仓库材料与龙门币/合成玉等库存；
    - `"all"`：同时返回干员箱与仓库数据。
  - `filter` (*string, 可选*)：过滤关键词，可传入干员中文名（如 `"银灰"`、`"维什戴尔"`）、干员 ID（如 `"char_002_amiya"`）或材料名称/ID（如 `"聚合剂"`、`"30012"`）。
- **返回 JSON 结构示例**：
```json
{
  "operBox": {
    "hasSynced": true,
    "syncTimeMillis": 1731057600000,
    "totalCount": 248,
    "operators": [
      {
        "id": "char_103_silverash",
        "name": "银灰",
        "rarity": 6,
        "elite": 2,
        "level": 90,
        "potential": 6,
        "skills": [
          { "name": "强力击·γ型", "level": 1, "specializeLevel": 0 },
          { "name": "雪境生存法则", "level": 1, "specializeLevel": 0 },
          { "name": "真银斩", "level": 7, "specializeLevel": 3 }
        ],
        "equips": [
          { "type": "X", "level": 3 }
        ]
      },
      {
        "id": "char_010_chen",
        "name": "陈",
        "rarity": 6,
        "elite": 2,
        "level": 60,
        "potential": 2,
        "skills": [
          { "name": "鞘击", "level": 1, "specializeLevel": 0 },
          { "name": "赤霄·拔刀", "level": 7, "specializeLevel": 1 },
          { "name": "赤霄·绝影", "level": 7, "specializeLevel": 0 }
        ],
        "equips": []
      }
    ]
  },
  "depot": {
    "hasSynced": true,
    "syncTimeMillis": 1731057600000,
    "items": [
      { "id": "30012", "name": "固源岩", "count": 312 },
      { "id": "30064", "name": "改量装置", "count": 18 },
      { "id": "4001", "name": "龙门币", "count": 2450000 }
    ]
  }
}
```

---

### 3.18 `maa_get_wiki_article` (百科全文转纯净 Markdown)
- **功能描述**：抓取明日方舟中文 PRTS Wiki 或海外英文 Arknights Terra Wiki 的指定页面，通过 MediaWiki 原生提取引擎直接转化为无冗余 HTML/CSS 干扰的纯净 Markdown 文本，为大模型提供深度长文研读、关卡详细攻略与剧情原著。
- **与 `maa_search_wiki` 的协同关系**：
  - `maa_search_wiki`：轻量**检索**（1~5 条摘要、标题和 URL），用于在大模型不知道具体页面标题或探索概念时快速命中候选；
  - `maa_get_wiki_article`：精准**读取**（单篇全文正文），在命中特定干员或关卡后，一次性获取结构化 Markdown 全文放入上下文窗口。
- **输入参数**：
  - `title` (*string, 必填*)：百科页面标题（如 `"维什戴尔"`, `"SilverAsh"`, `"1-7"`, `"萨卡兹肉鸽"`）；
  - `wiki` (*string, 可选, 默认 `"auto"`*)：
    - `"auto"`（默认）：若 `title` 为纯英文/拉丁字母，走 `arknights.wiki.gg`；包含中文字符优先走 `prts.wiki`，发生网络超时/错误时自动秒级降级 fallback 到 `arknights.wiki.gg`；
    - `"prts"`：强制使用中文 PRTS Wiki；
    - `"wiki_gg"`：强制使用海外英文 Arknights Terra Wiki (`arknights.wiki.gg`)。
- **返回 Markdown 示例**：
```markdown
# 维什戴尔

维什戴尔，萨卡兹佣兵头领，前整合运动干部 W。在伦蒂尼姆事件后与罗德岛达成深度合作协议。

## 基础档案
- **职业**：狙击（投掷手）
- **星级**：6 星
- **代号**：维什戴尔 (Wis'adel)
- **阵营**：罗德岛 / 巴别塔

## 天赋与特性
### 特性
攻击对攻击范围内的所有敌人造成物理伤害，对攻击范围外圈的敌人造成的物理伤害提升至 110%。

### 第一天赋：灵魂出窍
攻击时有几率额外攻击一个目标并引爆，产生小范围余震造成物理伤害并附加短暂眩晕。

## 技能详情
### 三技能：去往何方
攻击范围扩大，攻击力提升至 +220%，攻击间隔增大，每次攻击发射两枚弹药，优先攻击未处于眩晕状态的目标...
```
- **CLI 命令行调试支持**：
可在项目根目录下直接使用提供的百科文章获取脚本调试：
```bash
# 获取中文 PRTS Wiki 上的 1-7 关卡数据转 Markdown
python scripts/fetch_wiki_markdown.py "1-7" --wiki prts

# 获取海外 wiki.gg 上的 SilverAsh 条目转 Markdown
python scripts/fetch_wiki_markdown.py "SilverAsh" --wiki wiki_gg
```

---

### 3.19 `maa_click` (精准应急触屏点击)
- **功能描述**：向游戏画面指定的绝对坐标 `(x, y)` 发送单次精准物理/虚拟触屏点击。专为大模型智能体应对黑天鹅突发异常（如大版本更新全屏活动海报、签到赠送 10 连弹窗、网络波动断线重连对话框、理智耗尽弹窗关闭 [X] 等）提供微创级主动干预能力。
- **坐标系统规范 (Standard 1280x720 16:9 Normalized Space)**：
  - **坐标基准**：严格基于明日方舟官方原生渲染标准分辨率 **1280 $\times$ 720**（16:9 坐标系）；
  - **原点定义**：以游戏画面左上角为 `(0, 0)`，右下角为 `(1280, 720)`；
  - **跨设备黑边与拉伸适配**：若设备屏幕为非 16:9 比例（如 18:9、19.5:9、21:9 带鱼屏或 4:3 平板），MaaMeow 底层触控与渲染管道已实现等比黑边裁切与视口逆变换映射。大模型在调用工具时**完全无需换算物理屏幕 DPI 或屏幕长宽比**，直接基于 1280x720 的标准逻辑画面传入坐标即可；
  - **越界保护**：手侧服务自动执行 `x.coerceIn(0, 1280)` 与 `y.coerceIn(0, 720)` 钳制保护，杜绝因大模型输出负数或超限坐标引发底层崩溃。
- **输入参数**：
  - `x` (*integer, 必填, 0..1280*)：目标点击位置的水平 X 坐标；
  - `y` (*integer, 必填, 0..720*)：目标点击位置的垂直 Y 坐标。
- **安全边界与使用规范 (Safety Boundaries & Guardrails)**：
  1. **定位为应急干预微创工具，严禁代替主自动化流水线**：本工具仅限应对阻碍自动化的非预期浮层或重试按钮，绝对严禁让大模型通过循环调用 `maa_click` 手工打关、手动刷材料（战斗与编排必须走 `maa_run_profile` 与 `maa_load_copilot`）；
  2. **频率限制与防卡死**：单次异常诊断循环中，大模型调用 `maa_click` 连续点击建议不超过 3 次；若点击 3 次后画面依然未能解脱，必须调用 `maa_back_to_home` 或 `maa_stop` 进行状态归位或冷重启；
  3. **底层复用落点**：优先通过 `RemoteServiceManager` 调用 `MaaCoreService.AsyncClick(x, y, true)` 发送带轻微拟人随机微扰的点击；若当前无活跃核心任务链，自动降级为 `RemoteService.touchDown / touchUp` 注入。
- **返回 JSON 结构示例**：
```json
{
  "content": [
    {
      "type": "text",
      "text": "Click dispatched successfully to (1200, 75)"
    }
  ]
}
```

---

### 3.20 `maa_back_to_home` (原生全局大厅导航回退)
- **功能描述**：调用 MAA Core 底层原生经过严格状态机校验的 `BackToHome()` 导航流。无论当前游戏处于关卡详情页、干员查看页、好友列表、基建深层子房间还是未知的卡顿中间层，该工具将依次安全点击返回键或导航锚点，直至状态机视觉锚点确认回到游戏主界面（Main Lobby）。
- **与 `maa_click` 的协同与分级回退策略**：
  - **第一级：微创关闭 (`maa_click`)**：Vision Agent 明确看到弹窗右上角关闭按钮（如 `(1200, 75)`），直接调用 `maa_click` 关闭；
  - **第二级：状态归位 (`maa_back_to_home`)**：弹窗已关闭但处于未知深层界面，或视觉无法判定局部点击点、状态机发生脱轨时，调用 `maa_back_to_home()` 强制一键归位回大厅；
  - **第三级：冷启动守护 (`maa_stop` + `maa_run_profile(force=true)`)**：若返回大厅仍失败（游戏卡死无响应），执行冷重启协议。
- **输入参数**：*无*。
- **安全边界**：
  - 本工具为幂等操作；若游戏当前已在主界面，执行后立即安全返回；
  - 耗时通常在 1~5 秒内（依赖当前所处界面深度），期间会自动处理可能弹出的二次确认离开提示；
  - 若底层 MaaCoreService 实例未就绪，将抛出明确的异常提示大模型先通过 `maa_run_profile` 恢复。
- **返回 JSON 结构示例**：
```json
{
  "content": [
    {
      "type": "text",
      "text": "Returned to home screen successfully"
    }
  ]
}
```
