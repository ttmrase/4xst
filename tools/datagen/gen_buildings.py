#!/usr/bin/env python3
"""buildings.json 生成スクリプト（Python 3 標準ライブラリのみ）。

使い方（リポジトリルートから）:
    python3 tools/datagen/gen_buildings.py            # core/src/main/resources/data/buildings.json を再生成
    python3 tools/datagen/gen_buildings.py --check    # 目標曲線の検証だけ（ファイルは書かない）

曲線パラメータはすべて下の「パラメータ」節。ゲームが読むのは生成済み JSON だけ。
"""
import json
import math
import os
import sys

# ───────── パラメータ ─────────
MAX_LEVEL = 25
# Castle の seconds（L-1→L にかかる秒）のアンカー。間は幾何補間。
CASTLE_SECONDS_ANCHORS = {1: 5, 2: 10, 5: 300, 10: 3600, 15: 43200, 20: 172800, 25: 604800}
COST_BASE = 100.0
COST_GROWTH = 1.5
POWER_GROWTH = 1.42
POWER_L15_TARGET = 400_000  # 全スロット Lv15 の合計戦力（目標 300k〜500k の中央付近）

# type: (count, seconds係数, cost係数, power重み)
BUILDINGS = {
    "castle": (1, 1.00, 1.00, 3.0),
    "farm": (4, 0.30, 0.50, 1.0),
    "sawmill": (4, 0.30, 0.50, 1.0),
    "quarry": (3, 0.35, 0.50, 1.0),
    "gold_mine": (2, 0.40, 0.55, 1.0),
    "warehouse": (1, 0.45, 0.60, 1.0),
    "barracks": (1, 0.60, 0.80, 1.3),
    "archery_range": (1, 0.60, 0.80, 1.3),
    "stable": (1, 0.60, 0.80, 1.3),
    "siege_workshop": (1, 0.65, 0.85, 1.3),
    "hospital": (1, 0.55, 0.70, 1.0),
    "academy": (1, 0.80, 0.90, 1.0),
    "wall": (1, 0.75, 0.70, 1.2),
    "watchtower": (1, 0.50, 0.60, 1.0),
    "trading_post": (1, 0.40, 0.60, 1.0),
    "alliance_center": (1, 0.50, 0.60, 1.0),
    "tavern": (1, 0.55, 0.60, 1.0),
    "builders_hut": (1, 0.40, 0.60, 1.0),
}
PRODUCTION_BASE = {"farm": 300, "sawmill": 300, "quarry": 250, "gold_mine": 150}
PRODUCTION_GROWTH = 1.22
STORAGE_GROWTH = 1.3
# ──────────────────────────────


def castle_seconds(level):
    anchors = sorted(CASTLE_SECONDS_ANCHORS.items())
    for (l0, s0), (l1, s1) in zip(anchors, anchors[1:]):
        if l0 <= level <= l1:
            return round(s0 * (s1 / s0) ** ((level - l0) / (l1 - l0)))
    raise ValueError(level)


def cost_for(level, factor):
    unit = COST_BASE * COST_GROWTH ** (level - 1) * factor
    stone_w = 0.6 + 0.9 * min(1.0, max(0.0, (level - 5) / 15))
    gold_w = 0.0 if level < 5 else 0.05 * (level - 4)
    return {
        "food": max(1, round(unit)),
        "wood": max(1, round(unit)),
        "stone": max(1, round(unit * stone_w)),
        "gold": round(unit * gold_w),
    }


def effects_for(t, level):
    g = lambda base, growth: round(base * growth ** (level - 1))  # noqa: E731
    if t in PRODUCTION_BASE:
        return {"production_per_hour": g(PRODUCTION_BASE[t], PRODUCTION_GROWTH)}
    if t == "castle":
        return {
            "storage_cap_base": g(20000, STORAGE_GROWTH),
            "troop_capacity": g(1000, 1.25),
            "march_slots": 1 + level // 6,
        }
    if t == "warehouse":
        return {"storage_cap": g(10000, STORAGE_GROWTH), "protect": g(5000, STORAGE_GROWTH)}
    if t in ("barracks", "archery_range", "stable", "siege_workshop"):
        return {"train_capacity": g(100, 1.25)}
    if t == "hospital":
        return {"hospital_capacity": g(500, 1.28)}
    if t == "academy":
        return {"research_tier": level}
    if t == "wall":
        return {"wall_hp": g(1000, STORAGE_GROWTH)}
    if t == "watchtower":
        return {"scout_accuracy_bp": min(10000, 2000 + 300 * level)}
    if t == "trading_post":
        return {"trade_rate_bp": 5000 + 100 * level}
    if t == "alliance_center":
        return {"help_count": 3 + level // 3}
    if t == "tavern":
        return {"hero_cap": 3 + level // 3}
    if t == "builders_hut":
        return {"builder_hut": 1}
    raise ValueError(t)


UNLOCK_CASTLE_LV = {
    "barracks": 2,
    "hospital": 2,
    "builders_hut": 2,
    "archery_range": 3,
    "stable": 3,
    "trading_post": 3,
    "alliance_center": 4,
    "siege_workshop": 5,
    "watchtower": 5,
    "academy": 6,
    "tavern": 7,
}


def prereqs_for(t, level):
    if t == "castle":
        if level >= 2:
            return [{"type": "wall", "level": level - 1}, {"type": "warehouse", "level": level - 1}]
        return []
    # 段階解放（M11 T11.1）: Lv1 を建てるのに必要な城 Lv。2 Lv 目以降は建物 Lv と同じ城 Lv
    return [{"type": "castle", "level": max(level, UNLOCK_CASTLE_LV.get(t, 1)) if level == 1 else level}]


def build():
    slots_total = sum(c for c, _, _, _ in BUILDINGS.values())
    weight_sum = sum(c * w for c, _, _, w in BUILDINGS.values())
    power_a = POWER_L15_TARGET / (weight_sum * POWER_GROWTH ** 14)
    out = []
    for t, (count, sec_f, cost_f, power_w) in BUILDINGS.items():
        levels = []
        prev_sec = 0
        for level in range(1, MAX_LEVEL + 1):
            sec = max(prev_sec + 1, round(castle_seconds(level) * sec_f), 1)
            prev_sec = sec
            levels.append({
                "level": level,
                "cost": cost_for(level, cost_f),
                "seconds": sec,
                "prereqs": prereqs_for(t, level),
                "effects": effects_for(t, level),
                "power": round(power_a * power_w * POWER_GROWTH ** (level - 1)),
            })
        out.append({"type": t, "count": count, "maxLevel": MAX_LEVEL, "levels": levels})
    return {"buildings": out}, slots_total


def check(data):
    def total_power(level):
        return sum(b["count"] * b["levels"][level - 1]["power"] for b in data["buildings"])

    ok = True
    for lv, lo, hi in ((8, 25_000, 40_000), (15, 300_000, 500_000), (22, 3_000_000, 6_000_000)):
        p = total_power(lv)
        good = lo <= p <= hi
        ok &= good
        print(f"power all-slots Lv{lv}: {p:,} (target {lo:,}-{hi:,}) {'OK' if good else 'NG'}")
    castle = next(b for b in data["buildings"] if b["type"] == "castle")
    for lv, target in CASTLE_SECONDS_ANCHORS.items():
        got = castle["levels"][lv - 1]["seconds"]
        good = abs(got - target) <= target * 0.1
        ok &= good
        print(f"castle seconds Lv{lv}: {got} (target {target}) {'OK' if good else 'NG'}")
    return ok


def main():
    data, _ = build()
    ok = check(data)
    if not ok:
        sys.exit(1)
    if "--check" in sys.argv:
        return
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(root, "core", "src", "main", "resources", "data", "buildings.json")
    with open(path, "w", encoding="utf-8") as f:
        f.write('{\n "buildings": [\n')
        for i, b in enumerate(data["buildings"]):
            f.write('  {"type": %s, "count": %d, "maxLevel": %d, "levels": [\n' % (
                json.dumps(b["type"]), b["count"], b["maxLevel"]))
            f.write(",\n".join("   " + json.dumps(l, separators=(",", ":")) for l in b["levels"]))
            f.write("\n  ]}" + ("," if i < len(data["buildings"]) - 1 else "") + "\n")
        f.write(" ]\n}\n")
    print("wrote", path)


if __name__ == "__main__":
    main()
