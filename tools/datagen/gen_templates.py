#!/usr/bin/env python3
"""templates.json を tpl/part_*.py から組み立てる。実行: python3 tools/datagen/gen_templates.py"""
import json, pathlib, re, sys
sys.path.insert(0, str(pathlib.Path(__file__).parent / "tpl"))
import part_a, part_b, part_c, part_d, part_e

templates = {}
for part in (part_a, part_b, part_c, part_d, part_e):
    templates.update(part.T)

quizzes = {
 "q_storage": {"question": "あの、倉庫がいっぱいになったら、どうなるんですか？", "options": ["それ以上は生産が止まる", "資源が全部なくなる", "兵が減ってしまう"]},
 "q_hospital": {"question": "戦闘でケガした兵って、どこで治すんでしたっけ？", "options": ["城壁", "病院", "酒場"]},
 "q_research": {"question": "研究をするには、どの建物が必要なんですか？", "options": ["兵舎", "倉庫", "アカデミー"]},
 "q_scout": {"question": "偵察の精度を上げるには、何を建てればいいですか？", "options": ["物見の塔", "農場", "酒場"]},
 "q_wall": {"question": "襲撃から城を守るのに役立つ建物ってどれですか？", "options": ["市場", "城壁", "病院"]},
}
labels = {
 "resource.food": "食料", "resource.wood": "木材", "resource.stone": "石", "resource.gold": "金",
 "tech.tech_production": "生産テック", "tech.tech_gather": "採集テック", "tech.tech_attack": "攻撃テック", "tech.tech_hp": "体力テック",
 "sku.pack_daily": "本日限定パック", "sku.pack_growth": "成長パック", "sku.pack_speed": "加速パック",
 "sku.pack_hero": "英雄パック", "sku.pack_res_l": "資源パック",
 "sku.favor_1": "お礼の品", "sku.favor_2": "お礼の品", "sku.favor_3": "お礼の品", "sku.favor_4": "お礼の品",
 "topic.weather": "天気", "topic.event": "イベント", "topic.ranking": "ランキング", "topic.life": "日常", "topic.game": "ゲーム",
 "call.default": "領主さん",
}
out = {"templates": templates, "quizzes": quizzes, "labels": labels}
path = pathlib.Path(__file__).resolve().parents[2] / "core/src/main/resources/data/templates.json"
path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

# 簡易検査
personas = ["kaede", "marcus", "ponzu", "sato", "lin", "johnny", "yuki", "gen"]
bad = 0
for key, per in templates.items():
    if key in ("world_ambient", "yuki_return", "yuki_return_none", "ponzu_quiz"):
        continue
    for p in personas:
        n = len(per.get(p, []))
        if n < 5:
            print("short:", key, p, n); bad += 1
allowed = set("player nickname resource amount castleLv tile rank count lv fortLevel tech sku helpCount host vip".split())
for key, per in templates.items():
    for p, lines in per.items():
        for t in lines:
            for ph in re.findall(r"\{(\w+)\}", t):
                if ph not in allowed:
                    print("unknown placeholder", key, p, ph); bad += 1
total = sum(len(v) for per in templates.values() for v in per.values())
print("wrote", path, "keys", len(templates), "templates", total, "problems", bad)
