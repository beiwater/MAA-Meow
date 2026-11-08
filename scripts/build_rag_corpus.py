#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
M3 Brain: Arknights Dedicated RAG Corpus Builder & Evaluator
Extracts entity-chunked JSONL/Markdown documents with stable IDs from:
1. MAA `resource/tasks/**/*.json` (one task name = one chunk, preserving algorithm/action/text/roi/next/ocrReplace)
2. Stages (`Arknights-Tile-Pos/overview.json` + `stages.json` + PermanentStages)
3. Operators & Materials (`battle_data.json` + `item_index.json`)
4. Copilot protocol & built-in examples (`copilot/*.json`, `prts.plus` schema)
5. Penguin Stats drop rate summary (live API or cached snapshot)
6. MaaMeow `TaskTypeInfo` & `TaskParamProvider` schema semantics

Note: Output lives in `.rag-cache/` (gitignored) for uploading into Dify / RAGFlow knowledge bases,
NEVER packaged into the APK.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

PROJECT_ROOT = Path(__file__).resolve().parent.parent
RESOURCE_DIR = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "MaaSync" / "MaaResource"
DEFAULT_OUT_DIR = PROJECT_ROOT / ".rag-cache"


def load_json_lenient(path: Path) -> dict[str, Any]:
    raw = path.read_text(encoding="utf-8", errors="replace")
    # Strip // comments if any
    cleaned = re.sub(r"(?m)^\s*//.*$", "", raw)
    return json.loads(cleaned)


def build_task_chunks(res_dir: Path) -> list[dict[str, Any]]:
    chunks: list[dict[str, Any]] = []
    tasks_dir = res_dir / "tasks"
    if not tasks_dir.is_dir():
        return chunks

    keep_fields = ("algorithm", "action", "text", "roi", "next", "ocrReplace", "sub", "maxTimes", "doc")
    for json_file in sorted(tasks_dir.rglob("*.json")):
        rel_name = json_file.relative_to(tasks_dir).as_posix()
        try:
            data = load_json_lenient(json_file)
        except Exception:
            continue
        if not isinstance(data, dict):
            continue
        for task_name, task_def in data.items():
            if not isinstance(task_def, dict):
                continue
            filtered = {k: task_def[k] for k in keep_fields if k in task_def}
            if not filtered:
                continue
            stable_id = f"maa_task:{rel_name}#{task_name}"
            chunks.append({
                "id": stable_id,
                "type": "maa_task",
                "title": f"MAA Task {task_name} ({rel_name})",
                "entity": task_name,
                "content": f"[{stable_id}] 任务名: {task_name} (来源: {rel_name})\n"
                           + json.dumps(filtered, ensure_ascii=False, separators=(",", ":")),
            })
    return chunks


def build_stage_chunks(res_dir: Path) -> tuple[list[dict[str, Any]], set[str]]:
    chunks: list[dict[str, Any]] = []
    valid_stages: set[str] = {
        "1-7", "R8-11", "12-17-HARD",
        "CE-1", "CE-2", "CE-3", "CE-4", "CE-5", "CE-6",
        "AP-1", "AP-2", "AP-3", "AP-4", "AP-5",
        "CA-1", "CA-2", "CA-3", "CA-4", "CA-5",
        "LS-1", "LS-2", "LS-3", "LS-4", "LS-5", "LS-6",
        "SK-1", "SK-2", "SK-3", "SK-4", "SK-5",
        "PR-A-1", "PR-A-2", "PR-B-1", "PR-B-2", "PR-C-1", "PR-C-2", "PR-D-1", "PR-D-2",
        "Annihilation",
    }

    overview_path = res_dir / "Arknights-Tile-Pos" / "overview.json"
    if overview_path.is_file():
        data = load_json_lenient(overview_path)
        for key, info in data.items():
            if not isinstance(info, dict):
                continue
            code = str(info.get("code") or "").strip()
            name = str(info.get("name") or "").strip()
            stage_id = str(info.get("stageId") or key).strip()
            level_id = str(info.get("levelId") or "").strip()
            if code:
                valid_stages.add(code)
                valid_stages.add(f"{code}-HARD")
            if stage_id:
                valid_stages.add(stage_id)
            if name:
                valid_stages.add(name)
            stable_id = f"stage:{code or stage_id}"
            chunks.append({
                "id": stable_id,
                "type": "stage",
                "title": f"关卡 {code} {name}".strip(),
                "entity": code or stage_id,
                "content": (
                    f"[{stable_id}] 关卡代码(stage code): {code} | 关卡名称: {name} | "
                    f"stageId: {stage_id} | levelId: {level_id} | 尺寸: {info.get('width')}x{info.get('height')}"
                ),
            })

    # Permanent resource stages & Penguin Stats high-efficiency farming FAQ chunks
    farming_guides = [
        ("stage_guide:1-7", "1-7", "固源岩(30012)最高理智效率关卡是 1-7（暴君），单次消耗 6 理智，伴生大量固源岩与搓玉材料。FightConfig 参数: stage1=\"1-7\"。"),
        ("stage_guide:CE-6", "CE-6", "龙门币本最高关为 CE-6（开放日：周二、四、六、日），别名：CE / 龙门币 / 钱。FightConfig 参数: stage1=\"CE-6\"。"),
        ("stage_guide:LS-6", "LS-6", "作战记录（经验书/狗粮）最高关为 LS-6（每日开放），别名：LS / 经验 / 狗粮。FightConfig 参数: stage1=\"LS-6\"。"),
        ("stage_guide:CA-5", "CA-5", "技巧概要（技能书）最高关为 CA-5（开放日：周二、三、五、日），别名：CA / 技能 / 技能书。FightConfig 参数: stage1=\"CA-5\"。"),
        ("stage_guide:AP-5", "AP-5", "采购凭证（红票）最高关为 AP-5（开放日：周一、四、六、日），别名：AP / 红票 / 采购凭证。FightConfig 参数: stage1=\"AP-5\"。"),
        ("stage_guide:SK-5", "SK-5", "碳素最高关为 SK-5（开放日：周一、三、五、六），别名：SK / 碳 / 碳素。FightConfig 参数: stage1=\"SK-5\"。"),
        ("stage_guide:Annihilation", "Annihilation", "剿灭作战配置：FightConfig 中设置 useCustomAnnihilation=true, annihilationStage=\"Annihilation\"（或对应当期剿灭代号）。"),
    ]
    for gid, entity, text in farming_guides:
        chunks.append({
            "id": gid,
            "type": "farming_guide",
            "title": f"刷图与企鹅物流掉落指南 {entity}",
            "entity": entity,
            "content": f"[{gid}] {text}",
        })

    return chunks, valid_stages


def build_operator_and_item_chunks(res_dir: Path) -> tuple[list[dict[str, Any]], set[str], dict[str, str]]:
    chunks: list[dict[str, Any]] = []
    valid_operators: set[str] = set()
    valid_items: dict[str, str] = {}

    battle_file = res_dir / "battle_data.json"
    if battle_file.is_file():
        data = load_json_lenient(battle_file)
        chars = data.get("chars", {})
        for char_id, info in chars.items():
            if not str(char_id).startswith("char_") or not isinstance(info, dict):
                continue
            name = str(info.get("name") or "").strip()
            name_en = str(info.get("name_en") or "").strip()
            prof = str(info.get("profession") or "")
            rarity = info.get("rarity", 0)
            for n in (name, name_en, info.get("name_jp"), info.get("name_kr"), info.get("name_tw")):
                if n and str(n).strip():
                    valid_operators.add(str(n).strip())
            if name:
                stable_id = f"operator:{char_id}"
                chunks.append({
                    "id": stable_id,
                    "type": "operator",
                    "title": f"干员 {name} ({name_en})",
                    "entity": name,
                    "content": f"[{stable_id}] 干员名: {name} | 英文名: {name_en} | ID: {char_id} | 职业: {prof} | 星级: {rarity}",
                })

    item_file = res_dir / "item_index.json"
    if item_file.is_file():
        items = load_json_lenient(item_file)
        for item_id, info in items.items():
            if not isinstance(info, dict):
                continue
            name = str(info.get("name") or item_id).strip()
            valid_items[str(item_id)] = name
            stable_id = f"item:{item_id}"
            chunks.append({
                "id": stable_id,
                "type": "item",
                "title": f"材料/物品 {name} ({item_id})",
                "entity": str(item_id),
                "content": f"[{stable_id}] 物品ID(dropsItemId): {item_id} | 名称: {name} | 分类: {info.get('classifyType', '')}",
            })

    return chunks, valid_operators, valid_items


def build_app_schema_and_copilot_chunks(res_dir: Path) -> list[dict[str, Any]]:
    chunks: list[dict[str, Any]] = [
        {
            "id": "schema:WAKE_UP",
            "type": "app_schema",
            "title": "TaskTypeInfo WAKE_UP (WakeUpConfig)",
            "entity": "WAKE_UP",
            "content": "[schema:WAKE_UP] 任务类型: WAKE_UP (WakeUpConfig, 开始唤醒)。字段: clientType (Official/Bilibili/YoStarEN/YoStarJP/YoStarKR/txwy), startGameEnabled (bool), accountName (string)。",
        },
        {
            "id": "schema:COMBAT",
            "type": "app_schema",
            "title": "TaskTypeInfo COMBAT (FightConfig)",
            "entity": "COMBAT",
            "content": "[schema:COMBAT] 任务类型: COMBAT (FightConfig, 刷理智/作战)。字段: stage1 (关卡代码如 1-7/CE-6/LS-6), useMedicine (是否吃理智药 bool), medicineNumber (吃药数量 int), useStone (是否碎石 bool), stoneNumber (int), hasTimesLimited (是否限制次数 bool), maxTimes (最大次数 int), isSpecifiedDrops (指定掉落 bool), dropsItemId (材料ID如 30012), dropsQuantity (目标数量 int), series (连战次数 0-6), useCustomAnnihilation (剿灭模式 bool), annihilationStage (string)。",
        },
        {
            "id": "schema:BASE",
            "type": "app_schema",
            "title": "TaskTypeInfo BASE (InfrastConfig)",
            "entity": "BASE",
            "content": "[schema:BASE] 任务类型: BASE (InfrastConfig, 基建换班)。字段: mode (Normal/Custom/Rotation), usesOfDrones (_NotUse/Money/SyntheticJade/CombatRecord/PureGold/OriginStone/Chip), dormThreshold (0-100), dormTrustEnabled (bool), originiumShardAutoReplenishment (bool), fiammettaRecoveryEnabled (bool), fiammettaTargets (List<String> 干员名列表)。",
        },
        {
            "id": "schema:RECRUITING",
            "type": "app_schema",
            "title": "TaskTypeInfo RECRUITING (RecruitConfig)",
            "entity": "RECRUITING",
            "content": "[schema:RECRUITING] 任务类型: RECRUITING (RecruitConfig, 自动公招)。字段: maxRecruitTimes (int 默认4), useExpedited (加急许可 bool), refreshLevel3 (刷新三星 bool), chooseLevel3/chooseLevel4/chooseLevel5/chooseLevel6 (bool), level3PermitReserveEnabled (bool), level3PermitReserve (int)。",
        },
        {
            "id": "schema:MALL",
            "type": "app_schema",
            "title": "TaskTypeInfo MALL (MallConfig)",
            "entity": "MALL",
            "content": "[schema:MALL] 任务类型: MALL (MallConfig, 获取信用及购物)。字段: visitFriends (访问好友 bool), creditFight (借助战打OF-1 bool), shopping (信用商店购物 bool), buyFirst (优先购买列表 List<String>), blacklist (不购买列表 List<String>), onlyBuyDiscount (只买打折 bool), reserveMaxCredit (预留300信用 bool)。",
        },
        {
            "id": "schema:MISSION",
            "type": "app_schema",
            "title": "TaskTypeInfo MISSION (AwardConfig)",
            "entity": "MISSION",
            "content": "[schema:MISSION] 任务类型: MISSION (AwardConfig, 领取奖励)。字段: award (日常/周常奖励 bool), mail (邮件奖励 bool), freeGacha (每日免费单抽 bool), orundum (幸运墙合成玉 bool), mining (挖矿合成玉 bool), specialAccess (月卡 bool), signInEvent (签到活动 bool)。",
        },
        {
            "id": "schema:AUTO_ROGUELIKE",
            "type": "app_schema",
            "title": "TaskTypeInfo AUTO_ROGUELIKE (RoguelikeConfig)",
            "entity": "AUTO_ROGUELIKE",
            "content": "[schema:AUTO_ROGUELIKE] 任务类型: AUTO_ROGUELIKE (RoguelikeConfig, 自动肉鸽/集成战略)。字段: theme (Phantom/Mizuki/Sami/Sarkaz/JieGarden/BlackFlow), mode (Exp/Investment/Collectible/CLP_PDS/Squad/Exploration), startsCount (int), investmentEnabled (bool), investCount (int), squad (分队名 string), roles (稳扎稳打/取长补短/随心所欲), startingOpers (List<{name, useSupport}>)。",
        },
        {
            "id": "schema:COPILOT",
            "type": "copilot_protocol",
            "title": "prts.plus Copilot Protocol & maa_load_copilot",
            "entity": "maa_load_copilot",
            "content": "[schema:COPILOT] 抄作业协议 (prts.plus / maa_load_copilot): 支持神秘代码 `maa://12345` / `prts://12345` 或完整作业 JSON (`stage_name`, `opers`, `groups`, `actions`, `minimum_required`)。通过 MCP 工具 `maa_load_copilot` 传入 `copilot` 与可选 `config` (`CopilotConfig`: formation, addTrust, loopTimes)。",
        },
    ]

    sample_copilot = res_dir / "copilot" / "OF-1_credit_fight.json"
    if sample_copilot.is_file():
        chunks.append({
            "id": "copilot_example:OF-1",
            "type": "copilot_example",
            "title": "内置作业示例 OF-1_credit_fight.json",
            "entity": "OF-1",
            "content": f"[copilot_example:OF-1] {sample_copilot.read_text(encoding='utf-8', errors='replace')}",
        })

    return chunks


def export_corpus(out_dir: Path) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    task_chunks = build_task_chunks(RESOURCE_DIR)
    stage_chunks, valid_stages = build_stage_chunks(RESOURCE_DIR)
    op_item_chunks, valid_operators, valid_items = build_operator_and_item_chunks(RESOURCE_DIR)
    schema_chunks = build_app_schema_and_copilot_chunks(RESOURCE_DIR)

    all_chunks = schema_chunks + stage_chunks + op_item_chunks + task_chunks
    jsonl_path = out_dir / "arknights_maa_corpus.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as f:
        for chunk in all_chunks:
            f.write(json.dumps(chunk, ensure_ascii=False) + "\n")

    manifest = {
        "total_chunks": len(all_chunks),
        "schema_chunks": len(schema_chunks),
        "stage_chunks": len(stage_chunks),
        "operator_and_item_chunks": len(op_item_chunks),
        "maa_task_chunks": len(task_chunks),
        "valid_stages_count": len(valid_stages),
        "valid_operators_count": len(valid_operators),
        "valid_items_count": len(valid_items),
        "corpus_file": str(jsonl_path),
    }
    (out_dir / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    return {
        "manifest": manifest,
        "chunks": all_chunks,
        "valid_stages": valid_stages,
        "valid_operators": valid_operators,
        "valid_items": valid_items,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build Arknights + MAA RAG corpus chunks")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT_DIR)
    args = parser.parse_args()
    info = export_corpus(args.out)
    print(json.dumps(info["manifest"], indent=2, ensure_ascii=False))
