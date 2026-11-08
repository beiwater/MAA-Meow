# 明日方舟专用 RAG 语料库设计 (Arknights RAG Corpus)

在游戏自动化与策略编排场景中，常规通用的 RAG（按固定长度如 500 字符机械切割）会导致严重的**语义断裂**。例如，某个任务节点的参数说明被截断到下一块，或者关卡代号 `1-7` 与其掉落材料 `固源岩` 分离，进而引发大模型的灾难性幻觉。

为了支撑「大脑」（Dify / RAGFlow）的高精度检索，MaaMeow 设计了一套**按业务实体严格对齐的切块策略**与语料提取流水线。

---

## 1. 实体级切块架构原则 (Entity-Level Chunking)

每个 Chunk 必须是一个**完整自洽的实体单元**，具备全局唯一的稳定 ID (`stable_id`)，并聚合该实体所需的所有关联信息：

```text
+-------------------------------------------------------------------------------------------------------+
|                                      实体级分块策略 (Entity Chunking)                                   |
+-------------------------------------------------------------------------------------------------------+
|  [MAA Pipeline 任务]   --> maa_task:{file}#{taskName}   | 包含: algorithm, action, roi, next, ocrReplace|
|  [地图关卡坐标与参数] --> stage:{stage_code}           | 包含: levelId, stageId, zoneId, 地图宽高与方格   |
|  [关卡刷图与产出攻略] --> stage_guide:{stage_code}     | 包含: 掉落材料, 理智期望, 推荐队伍, FightConfig  |
|  [干员图鉴与属性定义] --> operator:{char_id}           | 包含: 真实ID, 职业, 星级, 中英文名, 基建技能     |
|  [掉落材料与物品索引] --> item:{item_id}               | 包含: 稳定数字ID, 道具名称, 分类, 合成配方       |
|  [Copilot 作业协议]    --> copilot_protocol:v1          | 包含: actions, oper/location/direction 动作语法  |
|  [MaaMeow 配置模式]    --> schema:{type_id}             | 包含: TaskTypeInfo, 字段说明, 默认值, 嵌套结构   |
+-------------------------------------------------------------------------------------------------------+
```

---

## 2. 六大语料数据源与提取逻辑

语料由 `scripts/build_rag_corpus.py` 自动化提取，不打入 Android APK，而是在服务端或 PC 构建生成并导入知识库：

### 2.1 MAA 核心 Pipeline 任务定义 (`resource/tasks/**/*.json`)
- **来源路径**：`app/src/main/assets/MaaSync/MaaResource/tasks/` 下的数十个 JSON 配置文件。
- **提取方式**：保留 `algorithm`、`action`、`text`、`roi`、`next`、`ocrReplace`、`sub`、`maxTimes` 等关键算法字段。
- **生成 Chunk 样例**：
  - `ID`: `maa_task:Fight/Fight.json#Fight_Start`
  - `Content`: 包含开始战斗按钮的 ROI 区域、匹配算法与后续跳转目标。

### 2.2 关卡实体与地图尺寸 (`Arknights-Tile-Pos/overview.json`)
- **来源路径**：MAA 瓦片坐标与关卡全景索引。
- **提取字段**：关卡代号（如 `1-7`、`R8-11`、`12-17-HARD`）、`levelId`、`stageId`、`zoneId`、地图瓦片尺寸（如 `12x6`）与合法部署格坐标。
- **作用**：当模型需要解析作业文件中的干员部署位置时，提供精准的坐标系基准。

### 2.3 干员与材料字典 (`battle_data.json` & `item_index.json`)
- **来源路径**：游戏解包数据与 MAA 识别字典。
- **干员实体**：收录包含星级、职业、英文名、拼音缩写及真实系统标识（如 `char_1035_wisdel` 对应 `维什戴尔`，`char_2025_shu` 对应 `黍`）。
- **材料实体**：建立掉落物品 ID（如 `30012` 对应 `固源岩`，`30013` 对应 `固源岩组`，`30043` 对应 `异铁组`）的双向映射。

### 2.4 企鹅物流掉落统计与刷图收益 (Penguin Stats)
- **来源**：企鹅物流开源 API 快照数据。
- **生成 Chunk**：针对日常高频关卡（`1-7` 固源岩、`CE-6` 龙门币、`LS-6` 作战记录、`CA-5` 技巧概要、`AP-5` 采购凭证、`SK-5` 碳素）生成 `stage_guide`，直接说明理智转化比与推荐的 `FightConfig` 设置项。

### 2.5 PRTS 抄作业协议与范例 (`prts.plus` / Copilot)
- **内容**：详述 `maa://` 作业码结构、JSON 动作序列规范（`Deploy`、`Skill`、`Retreat`、`SpeedUp`），并附带内置作业范例（如 `OF-1` 助战刷信用）。

### 2.6 MaaMeow 任务配置规范 (`TaskTypeInfo` & `TaskParamProvider`)
- **内容**：系统 11 类任务类型（`COMBAT`、`BASE`、`RECRUITING`、`MALL`、`MISSION`、`AUTO_ROGUELIKE`、`WAKE_UP` 等）的字段定义和业务语义，为大模型生成配置提供准确对照。

---

## 3. 构建流水线与输出格式

通过执行构建脚本：
```bash
python scripts/build_rag_corpus.py
```

脚本将在 `.rag-cache/` 目录下生成标准化的检索语料包：
- `arknights_maa_corpus.jsonl`：包含 2,000+ 条实体 Chunk 的 JSON Lines 文件。每行格式如下：
  ```json
  {
    "id": "stage_guide:1-7",
    "type": "farming_guide",
    "title": "关卡 1-7 (主线 第1章 黑暗时代·上 暴君)",
    "entity": "1-7",
    "content": "[stage_guide:1-7] 关卡: 1-7\n主产出材料: 固源岩 (itemId: 30012)\n单件理智期望: ~4.8 理智/个\n适用配置: FightConfig(stage1='1-7', hasTimesLimited=true, maxTimes=N)\n推荐打法: 自动战斗挂机，适合大批量消耗理智刷取岩石合成高级材料。"
  }
  ```
- `arknights_maa_corpus.md`：合并后的完整 Markdown 知识文档（带标准化实体 Anchor 锚点），可直接整份拖拽至 Dify 或 RAGFlow 进行解析。

---

## 4. Dify / RAGFlow 知识库最佳实践配置

将语料导入 Dify 知识库时，推荐采用以下策略以获得最佳召回精度：

1. **分段设置**：选择 **自定义分段**，设置分段标识符为 `---` 或 `\n\n`，最大分段长度设置为 `800`，分段重叠长度设置为 `0`（因为每个 Chunk 已经天然边界自闭合，无需跨块重叠）。
2. **索引方式**：选择 **高质量模式 (High Quality)**，选用强文本语义模型（如 `text-embedding-3-large`、`bge-m3` 或 `bce-embedding-base_v1`）。
3. **检索设置**：
   - 检索模式：推荐 **混合检索 (Hybrid Search)**，结合全文 BM25 关键词匹配与向量距离计算；
   - 权重调节：语义检索权重 `0.7`，全文检索权重 `0.3`；
   - 重排器 (Reranking)：开启 Rerank 模型（如 `bge-reranker-large` 或 `cohere-rerank-v3`），进一步过滤噪声；
   - Top-K：设置为 `3` ~ `5` 即可覆盖完整决策所需的上下文。
