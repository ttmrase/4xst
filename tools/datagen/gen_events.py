#!/usr/bin/env python3
"""events.json を生成する。実行: python3 tools/datagen/gen_events.py（係数は暫定値）"""
import json, pathlib

root = pathlib.Path(__file__).resolve().parents[2] / "core/src/main/resources/data"


def res(f=0, w=0, s=0, g=0):
    return {"food": f, "wood": w, "stone": s, "gold": g}


def reward(gems=0, r=None, items=None):
    return {"gems": gems, "resources": r or res(), "items": items or {}}


events = [
    {"id": "hunt", "kind": "HUNT", "par": 30000, "perLevel": 100, "fortMultiplier": 2, "lootPerPoint": 0},
    {"id": "gather", "kind": "GATHER", "par": 40000, "perLevel": 0, "fortMultiplier": 1, "lootPerPoint": 50},
    {"id": "power", "kind": "POWER", "par": 50000, "perLevel": 0, "fortMultiplier": 1, "lootPerPoint": 0},
]
npcs = [
    ("Aurelia", "Silver Hawks"), ("Borys", "Iron Wolves"), ("Cassian", "Dawn Legion"), ("Dagny", "Frost Vale"),
    ("Elric", "Ember Crown"), ("Freya", "Storm Reach"), ("Gideon", "Gold Lions"), ("Hana", "Jade Order"),
    ("Ivo", "Night Owls"), ("Jora", "Red Bastion"),
]
out = {
    "rotation": ["hunt", "gather", "power"],
    "events": events,
    "npcs": [{"name": n, "alliance": a} for n, a in npcs],
    "leaders": {"min": 1, "max": 3, "followMinPermille": 30, "followMaxPermille": 120, "parMinPermille": 850, "parMaxPermille": 1000},
    "followers": {"parMinPermille": 200, "parMaxPermille": 800},
    "warnBeforeEndSec": 86400,
    "bonusMarginPermille": 20,
    "rewards": [
        {"from": 1, "to": 1, "reward": reward(300, res(30000, 30000, 15000, 15000), {"speedup_general_3h": 3, "summon_ticket_premium": 1})},
        {"from": 2, "to": 2, "reward": reward(200, res(20000, 20000, 10000, 10000), {"speedup_general_3h": 2})},
        {"from": 3, "to": 3, "reward": reward(150, res(15000, 15000, 7500, 7500), {"speedup_general_60m": 3})},
        {"from": 4, "to": 6, "reward": reward(80, res(8000, 8000, 4000, 4000), {"speedup_general_60m": 2})},
        {"from": 7, "to": 11, "reward": reward(40, res(4000, 4000, 2000, 2000), {"speedup_general_15m": 3})},
    ],
    "participation": {"minScore": 1},
}
(root / "events.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print("ok")
