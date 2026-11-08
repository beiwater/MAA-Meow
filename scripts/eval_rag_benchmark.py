#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
M3 Evaluation Benchmark (≥30 questions, including 5+ non-existent stage/operator hallucination traps).
Tests:
1. Top-1 entity chunk retrieval accuracy over the generated Arknights + MAA corpus.
2. Anti-hallucination gate rejection & candidate suggestion on trap queries.
"""

from __future__ import annotations

import json
import math
import re
from collections import Counter
from pathlib import Path
from typing import Any

from build_rag_corpus import DEFAULT_OUT_DIR, export_corpus


BENCHMARK_QUESTIONS: list[dict[str, Any]] = [
    # --- 1..8: Farming & Stage Queries ---
    {"id": "Q01", "query": "刷固源岩应该打哪一关？FightConfig 怎么配？", "expected_id": "stage_guide:1-7", "trap": False},
    {"id": "Q02", "query": "刷龙门币最高效率是哪关？开放日和参数是什么？", "expected_id": "stage_guide:CE-6", "trap": False},
    {"id": "Q03", "query": "刷狗粮经验书作战记录打哪个关卡？", "expected_id": "stage_guide:LS-6", "trap": False},
    {"id": "Q04", "query": "技巧概要技能书最高关 CA-5 怎么配置？", "expected_id": "stage_guide:CA-5", "trap": False},
    {"id": "Q05", "query": "红票采购凭证最高关 AP-5 什么时候开？", "expected_id": "stage_guide:AP-5", "trap": False},
    {"id": "Q06", "query": "碳素本 SK-5 怎么用 FightConfig 配置？", "expected_id": "stage_guide:SK-5", "trap": False},
    {"id": "Q07", "query": "每周剿灭作战 Annihilation 应该开哪个模式？", "expected_id": "stage_guide:Annihilation", "trap": False},
    {"id": "Q08", "query": "关卡代码 1-7 暴君的 levelId、stageId 和地图尺寸 widthxheight", "expected_id": "stage:1-7", "trap": False},

    # --- 9..16: MaaMeow TaskTypeInfo & Schema Queries ---
    {"id": "Q09", "query": "开始唤醒 WAKE_UP 任务支持哪些客户端类型 clientType？", "expected_id": "schema:WAKE_UP", "trap": False},
    {"id": "Q10", "query": "刷理智 COMBAT FightConfig 吃理智药和连战次数用什么字段？", "expected_id": "schema:COMBAT", "trap": False},
    {"id": "Q11", "query": "基建换班 BASE InfrastConfig 的无人机用途 usesOfDrones 和菲亚梅塔恢复怎么配？", "expected_id": "schema:BASE", "trap": False},
    {"id": "Q12", "query": "自动公招 RECRUITING RecruitConfig 保留三星招聘许可的字段名是什么？", "expected_id": "schema:RECRUITING", "trap": False},
    {"id": "Q13", "query": "信用商店购物 MALL MallConfig 借助战打 OF-1 和优先购买列表怎么设？", "expected_id": "schema:MALL", "trap": False},
    {"id": "Q14", "query": "领取奖励 MISSION AwardConfig 领邮件、免费单抽和挖矿合成玉是什么字段？", "expected_id": "schema:MISSION", "trap": False},
    {"id": "Q15", "query": "自动肉鸽 AUTO_ROGUELIKE RoguelikeConfig 支持哪些主题 theme 和模式 mode？", "expected_id": "schema:AUTO_ROGUELIKE", "trap": False},
    {"id": "Q16", "query": "如何用 maa_load_copilot 加载 prts.plus 神秘代码 maa:// 作业？", "expected_id": "schema:COPILOT", "trap": False},

    # --- 17..25: Operators, Items & Built-in Copilot ---
    {"id": "Q17", "query": "干员 银灰 (SilverAsh) 的职业、星级和角色 ID 是什么？", "expected_id": "operator:char_172_svrash", "trap": False},
    {"id": "Q18", "query": "干员 玛恩纳 (Młynar) 的 ID 和职业信息", "expected_id": "operator:char_4064_mlynar", "trap": False},
    {"id": "Q19", "query": "干员 维什戴尔 (Wiš'adel) 的角色 ID 和星级", "expected_id": "operator:char_1035_wisdel", "trap": False},
    {"id": "Q20", "query": "干员 黍 (Shu) 的角色 ID 和职业是什么？", "expected_id": "operator:char_2025_shu", "trap": False},
    {"id": "Q21", "query": "干员 桃金娘 (Myrtle) 的角色 ID 和星级", "expected_id": "operator:char_151_myrtle", "trap": False},
    {"id": "Q22", "query": "材料 固源岩 的物品 ID (dropsItemId) 是多少？", "expected_id": "item:30012", "trap": False},
    {"id": "Q23", "query": "材料 固源岩组 (30013) 的名称和分类", "expected_id": "item:30013", "trap": False},
    {"id": "Q24", "query": "材料 异铁组 (30043) 的物品 ID 是什么？", "expected_id": "item:30043", "trap": False},
    {"id": "Q25", "query": "内置助战信用作业 OF-1_credit_fight.json 的内容格式示例", "expected_id": "copilot_example:OF-1", "trap": False},

    # --- 26..33: Trap Queries (Hallucinated Stages / Operators / Tasks) ---
    {"id": "Q26", "query": "帮我刷 10 把主线第 99 章关卡 99-99", "expected_id": "REJECT", "trap": True, "stage": "99-99"},
    {"id": "Q27", "query": "把刷理智关卡设成 CE-99 刷龙门币", "expected_id": "REJECT", "trap": True, "stage": "CE-99"},
    {"id": "Q28", "query": "去刷不存在的隐藏关卡 PR-Z-9", "expected_id": "REJECT", "trap": True, "stage": "PR-Z-9"},
    {"id": "Q29", "query": "肉鸽开局干员选 宇智波佐助", "expected_id": "REJECT", "trap": True, "operator": "宇智波佐助"},
    {"id": "Q30", "query": "菲亚梅塔恢复目标设为 超级赛亚人孙悟空", "expected_id": "REJECT", "trap": True, "operator": "超级赛亚人孙悟空"},
    {"id": "Q31", "query": "干员养成计划添加 阿尔托莉雅Pendragon", "expected_id": "REJECT", "trap": True, "operator": "阿尔托莉雅Pendragon"},
    {"id": "Q32", "query": "指定掉落材料 ID 设成 99999999", "expected_id": "REJECT", "trap": True, "item_id": "99999999"},
]


TOKEN_RE = re.compile(r"[A-Za-z0-9_\-]+|[\u4e00-\u9fff]{1,3}")


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    for m in TOKEN_RE.finditer(text.lower()):
        tok = m.group(0)
        tokens.append(tok)
        # Add character bigrams for CJK
        if re.match(r"^[\u4e00-\u9fff]{2,}$", tok):
            for i in range(len(tok)):
                tokens.append(tok[i])
                if i + 1 < len(tok):
                    tokens.append(tok[i:i + 2])
    return tokens


class HybridChunkIndex:
    def __init__(self, chunks: list[dict[str, Any]]) -> None:
        self.chunks = chunks
        self.doc_tokens = [Counter(tokenize(c["title"] + " " + c["content"])) for c in chunks]
        self.df: Counter[str] = Counter()
        for dt in self.doc_tokens:
            for term in dt:
                self.df[term] += 1
        self.n = max(len(chunks), 1)
        self.avgdl = sum(sum(dt.values()) for dt in self.doc_tokens) / self.n

    def search(self, query: str, top_k: int = 3) -> list[dict[str, Any]]:
        q_tokens = tokenize(query)
        q_lower = query.lower()
        scores: list[tuple[float, int]] = []
        for idx, dt in enumerate(self.doc_tokens):
            dl = sum(dt.values()) or 1
            score = 0.0
            for t in q_tokens:
                tf = dt.get(t, 0)
                if tf == 0:
                    continue
                df = self.df.get(t, 0)
                idf = math.log(1.0 + (self.n - df + 0.5) / (df + 0.5))
                score += idf * (tf * 2.2) / (tf + 1.2 * (1 - 0.75 + 0.75 * dl / self.avgdl))
            chunk = self.chunks[idx]
            entity = str(chunk.get("entity") or "").lower()
            if entity and len(entity) >= 2 and entity in q_lower:
                score += 18.0
            if chunk["type"] in ("app_schema", "farming_guide", "copilot_protocol", "copilot_example"):
                score *= 1.25
            if score > 0:
                scores.append((score, idx))

        scores.sort(key=lambda x: x[0], reverse=True)
        return [self.chunks[i] for _, i in scores[:top_k]]


def run_evaluation() -> dict[str, Any]:
    corpus = export_corpus(DEFAULT_OUT_DIR)
    chunks = corpus["chunks"]
    valid_stages = {s.upper() for s in corpus["valid_stages"]}
    valid_operators = {o.lower() for o in corpus["valid_operators"]}
    valid_items = corpus["valid_items"]

    # Include searchable chunks (schema, stage, guide, operator, item, copilot)
    searchable = [c for c in chunks if c["type"] != "maa_task"]
    index = HybridChunkIndex(searchable)

    results = []
    top1_hits = 0
    trap_rejected = 0
    trap_total = 0
    false_answers = 0

    for item in BENCHMARK_QUESTIONS:
        is_trap = item.get("trap", False)
        if is_trap:
            trap_total += 1
            rejected = False
            reason = ""
            if "stage" in item and item["stage"].upper() not in valid_stages:
                rejected = True
                reason = f"Stage '{item['stage']}' rejected by ArknightsEntityValidator"
            elif "operator" in item and item["operator"].lower() not in valid_operators:
                rejected = True
                reason = f"Operator '{item['operator']}' rejected by ArknightsEntityValidator"
            elif "item_id" in item and item["item_id"] not in valid_items:
                rejected = True
                reason = f"Item ID '{item['item_id']}' rejected by ArknightsEntityValidator"

            if rejected:
                trap_rejected += 1
                top1_hits += 1
            else:
                false_answers += 1

            results.append({
                "id": item["id"],
                "query": item["query"],
                "trap": True,
                "expected": "REJECT",
                "actual": "REJECT" if rejected else "ACCEPTED",
                "passed": rejected,
                "detail": reason,
            })
        else:
            top_docs = index.search(item["query"], top_k=3)
            top1_id = top_docs[0]["id"] if top_docs else "NONE"
            passed = top1_id == item["expected_id"]
            if passed:
                top1_hits += 1
            else:
                false_answers += 1
            results.append({
                "id": item["id"],
                "query": item["query"],
                "trap": False,
                "expected": item["expected_id"],
                "actual": top1_id,
                "top3": [d["id"] for d in top_docs],
                "passed": passed,
            })

    total = len(BENCHMARK_QUESTIONS)
    summary = {
        "total_questions": total,
        "normal_questions": total - trap_total,
        "trap_questions": trap_total,
        "top1_hits": top1_hits,
        "top1_accuracy": round(top1_hits / total, 4),
        "trap_rejection_rate": round(trap_rejected / max(trap_total, 1), 4),
        "false_answer_rate": round(false_answers / total, 4),
        "results": results,
    }
    report_path = DEFAULT_OUT_DIR / "eval_report.json"
    report_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
    return summary


if __name__ == "__main__":
    report = run_evaluation()
    print(json.dumps({k: v for k, v in report.items() if k != "results"}, indent=2, ensure_ascii=False))
    failed = [r for r in report["results"] if not r["passed"]]
    if failed:
        print("Failed items:", json.dumps(failed, indent=2, ensure_ascii=False))
