#!/usr/bin/env python3
"""quests.json を生成する。実行: python3 tools/datagen/gen_quests.py
報酬の量は暫定値。条件の種類: building(type, lv) / power(value) / research(total) / heroes(count) / counter(key, target)。"""
import json, pathlib

root = pathlib.Path(__file__).resolve().parents[2] / "core/src/main/resources/data"


def res(f=0, w=0, s=0, g=0):
    return {"food": f, "wood": w, "stone": s, "gold": g}


def reward(gems=0, r=None, items=None):
    return {"gems": gems, "resources": r or res(), "items": items or {}}


def b(t, lv):
    return {"type": "building", "building": t, "lv": lv}


def power(v):
    return {"type": "power", "value": v}


def research(n):
    return {"type": "research", "total": n}


def heroes(n):
    return {"type": "heroes", "count": n}


def counter(k, n):
    return {"type": "counter", "key": k, "target": n}


# (タスク一覧)
CH = [
    [b("castle", 2), b("farm", 2), b("sawmill", 2)],
    [b("castle", 3), b("barracks", 1), b("warehouse", 2)],
    [b("farm", 3), b("quarry", 3), b("gold_mine", 2), counter("trainTroops", 20)],
    [b("castle", 4), b("academy", 1), research(1)],
    [b("castle", 5), b("hospital", 2), power(5000), counter("hunt", 3)],
    [b("barracks", 4), b("wall", 4), b("watchtower", 3), counter("trainTroops", 100)],
    [b("castle", 6), b("trading_post", 3), research(4), heroes(2)],
    [b("castle", 7), b("alliance_center", 3), b("tavern", 3), counter("gather", 5)],
    [b("castle", 8), power(15000), counter("hunt", 10), research(8)],
    [b("castle", 9), b("warehouse", 8), b("hospital", 6), counter("donate", 5)],
    [b("castle", 10), b("barracks", 8), b("academy", 8), power(40000)],
    [b("farm", 10), b("sawmill", 10), b("quarry", 10), b("gold_mine", 10)],
    [b("castle", 11), research(14), counter("trainTroops", 1000), heroes(4)],
    [b("castle", 12), b("wall", 12), b("watchtower", 10), counter("hunt", 30)],
    [b("castle", 13), b("builders_hut", 10), counter("summon", 10), power(100000)],
    [b("castle", 14), b("hospital", 12), b("trading_post", 12), research(20)],
    [b("castle", 15), power(250000), counter("rally", 5), counter("gather", 30)],
    [b("castle", 16), b("barracks", 15), b("academy", 15), heroes(6)],
    [b("castle", 17), b("alliance_center", 15), counter("hunt", 100), power(600000)],
    [b("castle", 18), b("warehouse", 18), research(28), counter("trainTroops", 10000)],
]
SPEED = ["speedup_general_5m", "speedup_general_15m", "speedup_general_60m", "speedup_general_3h"]
chapters = []
for i, tasks in enumerate(CH, start=1):
    k = i
    chapters.append({
        "id": f"ch{i}",
        "tasks": [{"id": f"ch{i}_{j}", "cond": c} for j, c in enumerate(tasks, start=1)],
        "reward": reward(
            gems=10 + 3 * k,
            r=res(1000 * k, 1000 * k, 500 * k, 500 * k),
            items={SPEED[min(3, k // 5)]: 2 + k // 4, **({"summon_ticket_normal": 1} if k % 5 == 0 else {})},
        ),
    })

dailies = [
    ("d_help", "help", 5), ("d_gather", "gather", 1), ("d_train", "train", 1), ("d_research", "research", 1),
    ("d_build", "build", 1), ("d_hunt", "hunt", 1), ("d_donate", "donate", 1), ("d_summon", "summon", 1),
    ("d_shop", "shop", 1), ("d_chat", "chat", 1),
]
daily_defs = [{"id": i, "key": k, "target": t, "points": 10} for i, k, t in dailies]
chests = [
    {"tier": 1, "points": 20, "reward": reward(5, res(2000, 2000, 1000, 1000), {"speedup_general_5m": 3})},
    {"tier": 2, "points": 40, "reward": reward(10, res(4000, 4000, 2000, 2000), {"speedup_general_15m": 3})},
    {"tier": 3, "points": 60, "reward": reward(15, res(6000, 6000, 3000, 3000), {"speedup_general_60m": 2})},
    {"tier": 4, "points": 80, "reward": reward(20, res(8000, 8000, 4000, 4000), {"speedup_general_60m": 3, "hero_exp_1k": 2})},
    {"tier": 5, "points": 100, "reward": reward(30, res(12000, 12000, 6000, 6000), {"speedup_general_3h": 2, "skill_book": 1})},
]

alliance = [
    {"id": "al_donate_1", "cond": counter("donate", 1), "reward": reward(10, res(2000, 2000, 1000, 1000))},
    {"id": "al_donate_10", "cond": counter("donate", 10), "reward": reward(30, items={"speedup_general_60m": 2})},
    {"id": "al_donate_50", "cond": counter("donate", 50), "reward": reward(80, items={"speedup_general_3h": 2})},
    {"id": "al_rally_1", "cond": counter("rally", 1), "reward": reward(15, items={"speedup_general_15m": 3})},
    {"id": "al_rally_5", "cond": counter("rally", 5), "reward": reward(40, items={"speedup_general_60m": 3})},
    {"id": "al_rally_20", "cond": counter("rally", 20), "reward": reward(120, items={"summon_ticket_normal": 3})},
    {"id": "al_help_20", "cond": counter("help", 20), "reward": reward(20, res(3000, 3000, 1500, 1500))},
    {"id": "al_help_100", "cond": counter("help", 100), "reward": reward(60, items={"speedup_general_3h": 1})},
]


def tiers(prefix, key, targets, base):
    out = []
    for n, t in enumerate(targets, start=1):
        out.append({"id": f"{prefix}_{n}", "cond": counter(key, t),
                    "reward": reward(base * n, res(1000 * n * base // 10, 1000 * n * base // 10, 500 * n * base // 10, 500 * n * base // 10))})
    return out


achievements = (
    tiers("ach_hunt", "hunt", [10, 50, 200, 1000], 10)
    + tiers("ach_gather", "gather", [10, 50, 200, 1000], 10)
    + tiers("ach_train", "trainTroops", [100, 1000, 10000, 100000], 10)
    + tiers("ach_summon", "summon", [10, 50, 100, 500], 15)
    + tiers("ach_build", "build", [10, 50, 150, 400], 10)
    + [
        {"id": "ach_castle_5", "cond": b("castle", 5), "reward": reward(30)},
        {"id": "ach_castle_10", "cond": b("castle", 10), "reward": reward(80)},
        {"id": "ach_castle_15", "cond": b("castle", 15), "reward": reward(200)},
        {"id": "ach_castle_20", "cond": b("castle", 20), "reward": reward(500)},
        {"id": "ach_power_1", "cond": power(10000), "reward": reward(30)},
        {"id": "ach_power_2", "cond": power(100000), "reward": reward(100)},
        {"id": "ach_power_3", "cond": power(1000000), "reward": reward(300)},
    ]
)

out = {"chapters": chapters, "dailies": daily_defs, "chests": chests, "alliance": alliance, "achievements": achievements}
(root / "quests.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print("ok", len(chapters), "chapters", len(achievements), "achievements")
