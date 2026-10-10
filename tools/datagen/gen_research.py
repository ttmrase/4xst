#!/usr/bin/env python3
"""research.json 生成スクリプト（Python 3 標準ライブラリのみ）。

使い方（リポジトリルートから）:
    python3 tools/datagen/gen_research.py            # core/src/main/resources/data/research.json を再生成
    python3 tools/datagen/gen_research.py --check    # 検証のみ

3 系統（経済 / 軍事 / 発展）× 10 ノード × Lv1〜10。時間は Academy の同レベルの建設時間に揃えて伸ばす
（Academy の秒数は gen_buildings.py と同じ曲線を使う）。
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_buildings as gb  # noqa: E402

# ───────── パラメータ ─────────
MAX_LEVEL = 10
ACADEMY_SEC_FACTOR = gb.BUILDINGS["academy"][1]  # Academy の seconds 係数
SECONDS_MULTIPLIER = 1.5  # Academy 建設時間に対する研究時間の倍率
COST_BASE = 200.0
COST_GROWTH = 1.45
ADV_ACADEMY_OFFSET = 4  # 上位ノードが要求する Academy Lv の上乗せ
ADV_PREREQ_LEVEL = 5  # 上位ノードが要求する基礎ノードの Lv
POWER_BASE = 100
POWER_GROWTH = 1.45
# 資源の重み（食料, 木材, 石材, 金貨）。Lv が上がるほど金貨の比重が増す。
WEIGHTS = (0.5, 1.0, 0.8)
# ノード: (id, 系統, BuffKey, 1 Lv あたりの bp, 前提ノード or None)
NODES = [
    ("eco_food", "economy", "PRODUCTION_FOOD", 200, None),
    ("eco_wood", "economy", "PRODUCTION_WOOD", 200, None),
    ("eco_stone", "economy", "PRODUCTION_STONE", 200, None),
    ("eco_gold", "economy", "PRODUCTION_GOLD", 200, None),
    ("eco_prod_all", "economy", "PRODUCTION_ALL", 100, None),
    ("eco_gather", "economy", "GATHER_SPEED", 300, None),
    ("eco_storage", "economy", "STORAGE_PROTECT", 500, None),
    ("eco_food_adv", "economy", "PRODUCTION_FOOD", 150, "eco_food"),
    ("eco_stone_adv", "economy", "PRODUCTION_STONE", 150, "eco_stone"),
    ("eco_gold_adv", "economy", "PRODUCTION_GOLD", 150, "eco_gold"),
    ("mil_attack", "military", "TROOP_ATTACK", 250, None),
    ("mil_defense", "military", "TROOP_DEFENSE", 250, None),
    ("mil_hp", "military", "TROOP_HP", 250, None),
    ("mil_march", "military", "MARCH_SPEED", 300, None),
    ("mil_hospital", "military", "HOSPITAL_CAPACITY", 400, None),
    ("mil_upkeep", "military", "UPKEEP_REDUCTION", 400, None),
    ("mil_attack_adv", "military", "TROOP_ATTACK", 200, "mil_attack"),
    ("mil_defense_adv", "military", "TROOP_DEFENSE", 200, "mil_defense"),
    ("mil_hp_adv", "military", "TROOP_HP", 200, "mil_hp"),
    ("mil_march_adv", "military", "MARCH_SPEED", 200, "mil_march"),
    ("dev_build", "development", "BUILD_SPEED", 300, None),
    ("dev_research", "development", "RESEARCH_SPEED", 300, None),
    ("dev_train", "development", "TRAIN_SPEED", 300, None),
    ("dev_heal", "development", "HEAL_SPEED", 300, None),
    ("dev_prod_all", "development", "PRODUCTION_ALL", 100, None),
    ("dev_hospital", "development", "HOSPITAL_CAPACITY", 300, None),
    ("dev_build_adv", "development", "BUILD_SPEED", 200, "dev_build"),
    ("dev_research_adv", "development", "RESEARCH_SPEED", 200, "dev_research"),
    ("dev_train_adv", "development", "TRAIN_SPEED", 200, "dev_train"),
    ("dev_heal_adv", "development", "HEAL_SPEED", 200, "dev_heal"),
]
# ──────────────────────────────


def academy_seconds(level):
    return round(gb.castle_seconds(level) * ACADEMY_SEC_FACTOR)


def build():
    nodes = []
    for node_id, branch, key, bp_per_level, parent in NODES:
        levels = []
        prev_sec = 0
        for lv in range(1, MAX_LEVEL + 1):
            academy_lv = min(gb.MAX_LEVEL, 1 + 2 * (lv - 1) + (ADV_ACADEMY_OFFSET if parent else 0))
            unit = COST_BASE * COST_GROWTH ** (academy_lv - 1)
            gold_w = 0.0 if academy_lv < 5 else 0.05 * (academy_lv - 4)
            cost = {
                "food": max(1, round(unit * WEIGHTS[0])),
                "wood": max(1, round(unit * WEIGHTS[1])),
                "stone": max(1, round(unit * WEIGHTS[2])),
                "gold": round(unit * gold_w),
            }
            sec = max(prev_sec + 1, round(academy_seconds(academy_lv) * SECONDS_MULTIPLIER), 1)
            prev_sec = sec
            nodes_req = [{"id": parent, "level": ADV_PREREQ_LEVEL}] if parent else []
            levels.append({
                "level": lv,
                "cost": cost,
                "seconds": sec,
                "prereqs": {"academyLevel": academy_lv, "nodes": nodes_req},
                "effect": {"key": key, "bp": bp_per_level * lv},
                "power": round(POWER_BASE * POWER_GROWTH ** (lv - 1)),
            })
        nodes.append({"id": node_id, "branch": branch, "maxLevel": MAX_LEVEL, "levels": levels})
    return {"nodes": nodes}


def check(data):
    ok = True
    cap_by_castle = lambda lv: gb.effects_for("castle", lv)["storage_cap_base"] + gb.effects_for("warehouse", lv)["storage_cap"]  # noqa: E731
    for n in data["nodes"]:
        for lv in n["levels"]:
            need = lv["prereqs"]["academyLevel"]
            worst = max(lv["cost"].values())
            # Academy Lv は Castle >= 同 Lv を要求するので、その時点の上限と比べる
            if worst > cap_by_castle(need):
                print(f"cost exceeds cap: {n['id']} Lv{lv['level']} cost={worst} cap={cap_by_castle(need)}")
                ok = False
    branches = {}
    for n in data["nodes"]:
        branches[n["branch"]] = branches.get(n["branch"], 0) + 1
    print("nodes per branch:", branches)
    lv10 = max(n["levels"][-1]["seconds"] for n in data["nodes"])
    print(f"longest research: {lv10 / 86400:.1f} days")
    return ok and all(10 <= c <= 12 for c in branches.values()) and len(branches) == 3


def main():
    data = build()
    if not check(data):
        sys.exit(1)
    if "--check" in sys.argv:
        return
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    path = os.path.join(root, "core", "src", "main", "resources", "data", "research.json")
    with open(path, "w", encoding="utf-8") as f:
        f.write('{\n "nodes": [\n')
        for i, n in enumerate(data["nodes"]):
            f.write('  {"id": %s, "branch": %s, "maxLevel": %d, "levels": [\n' % (
                json.dumps(n["id"]), json.dumps(n["branch"]), n["maxLevel"]))
            f.write(",\n".join("   " + json.dumps(l, separators=(",", ":")) for l in n["levels"]))
            f.write("\n  ]}" + ("," if i < len(data["nodes"]) - 1 else "") + "\n")
        f.write(" ]\n}\n")
    print("wrote", path)


if __name__ == "__main__":
    main()
