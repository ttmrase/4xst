#!/usr/bin/env python3
"""offers.json / shop_rules.json / vip.json を生成する。実行: python3 tools/datagen/gen_shop.py
価格は量産型の端数（120 / 160 / 610 / 1,220 / 3,060 / 6,100 / 12,000）。確率・重みは暫定値。"""
import json, pathlib

root = pathlib.Path(__file__).resolve().parents[2] / "core/src/main/resources/data"


def res(food=0, wood=0, stone=0, gold=0):
    return {"food": food, "wood": wood, "stone": stone, "gold": gold}


def contents(gems=0, r=None, items=None, shards=None, builder_days=0):
    c = {"gems": gems, "resources": r or res(), "items": items or {}, "heroShards": shards or {}}
    if builder_days:
        c["secondBuilderDays"] = builder_days
    return c


gem_skus = [
    {"sku": "gems_s", "priceYen": 160, "gems": 100, "bonus": 0, "firstDouble": True},
    {"sku": "gems_m", "priceYen": 610, "gems": 500, "bonus": 50, "firstDouble": True},
    {"sku": "gems_l", "priceYen": 1220, "gems": 1000, "bonus": 150, "firstDouble": True},
    {"sku": "gems_xl", "priceYen": 3060, "gems": 2500, "bonus": 500, "firstDouble": True},
    {"sku": "gems_xxl", "priceYen": 6100, "gems": 5000, "bonus": 1200, "firstDouble": True},
    {"sku": "gems_max", "priceYen": 12000, "gems": 10000, "bonus": 3000, "firstDouble": True},
]

H = 3600
D = 86400


def pack(id, price, list_value, c, badge="NONE", duration=D, stock=3, cooldown=1800, trigger=None,
         shelf=False, lv_scale=False, tier=0, deal=None):
    p = {"id": id, "priceYen": price, "listValueYen": list_value, "badge": badge, "durationSec": duration,
         "dailyStock": stock, "cooldownSec": cooldown, "contents": c, "shelf": shelf, "lvScale": lv_scale,
         "tier": tier}
    if trigger:
        p["trigger"] = trigger
    if deal:
        p["deal"] = deal
    return p


packs = [
    # ログイン（1 日 1 回の本日限定）
    pack("pack_daily", 610, 4800, contents(300, res(20000, 20000, 10000, 10000), {"speedup_general_60m": 3}),
         "RECOMMENDED", D, 1, 3600, {"when": "login"}),
    # 城 Lv 記念（Lv に比例して豪華に）
    pack("pack_castle", 1220, 12800, contents(40, res(8000, 8000, 4000, 4000), {"speedup_build_60m": 1}),
         "BEST_VALUE", 2 * D, 1, 3600, {"when": "castleUp", "minLv": 2}, lv_scale=True),
    # 成長（棚）
    pack("pack_growth", 1220, 9800, contents(250, res(30000, 30000, 15000, 15000), {"speedup_build_3h": 2}),
         "POPULAR", 3 * D, 3, 1800, shelf=True),
    # 資源（棚）
    pack("pack_res_s", 160, 980, contents(0, res(6000, 6000, 3000, 3000)), "NONE", D, 5, 1800, shelf=True),
    pack("pack_res_l", 610, 4900, contents(0, res(40000, 40000, 20000, 20000)), "RECOMMENDED", D, 3, 1800, shelf=True),
    # 加速（棚）と長時間タイマー
    pack("pack_speed", 610, 5400, contents(0, None, {"speedup_general_3h": 3, "speedup_general_60m": 4}),
         "POPULAR", D, 3, 1800, shelf=True),
    pack("pack_speed_bundle", 1220, 15800, contents(0, None, {"speedup_general_8h": 2, "speedup_general_3h": 4}),
         "BEST_VALUE", 4 * H, 2, 3600, {"when": "longTimer", "minSec": 8 * H}),
    # 英雄の欠片（棚）
    pack("pack_hero", 1220, 9800, contents(0, None, {"summon_ticket_premium": 2},
                                              {"hero_valeria": 10, "hero_aldric": 10}),
         "POPULAR", 3 * D, 3, 1800, shelf=True),
    # 復讐（敗北）
    pack("pack_revenge", 610, 7800, contents(100, res(15000, 15000, 8000, 8000), {"speedup_train_3h": 3,
                                                                                     "speedup_heal_60m": 3}),
         "RECOMMENDED", 6 * H, 2, 1800, {"when": "defeated"}),
    # ランキング逆転
    pack("pack_ranking", 1220, 16800, contents(300, res(20000, 20000, 10000, 10000),
                                                  {"speedup_general_3h": 3}),
         "BEST_VALUE", 4 * H, 1, 3600, {"when": "rankingNear"}),
    # 復帰
    pack("pack_comeback", 610, 8800, contents(200, res(30000, 30000, 15000, 15000), {"speedup_build_3h": 2,
                                                                                        "summon_ticket_normal": 5}),
         "RECOMMENDED", 2 * D, 1, 3600, {"when": "comeback", "minAwaySec": 2 * D}),
    # VIP 昇格間近
    pack("pack_vip", 610, 3600, contents(60, res(10000, 10000, 5000, 5000)), "RECOMMENDED", 6 * H, 2, 3600,
         {"when": "vipNear", "withinPt": 700}),
    # 退出前
    pack("pack_wait", 160, 4200, contents(150, res(10000, 10000, 5000, 5000)), "BEST_VALUE", H, 1, 1800,
         {"when": "exitIntent"}),
    # 2 本目ビルダー（常設ビルダーの勧誘）
    pack("pack_builder", 610, 3800, contents(100, None, {"speedup_build_60m": 3}, builder_days=7),
         "RECOMMENDED", 3 * D, 3, 1800, shelf=True),
    # 連鎖 Tier1〜3
    pack("pack_chain_1", 160, 1800, contents(80, res(5000, 5000, 2500, 2500)), "NONE", 30 * 60, 1, 600,
         {"when": "afterPurchase", "chainOf": "any"}, tier=1),
    pack("pack_chain_2", 610, 6800, contents(250, res(20000, 20000, 10000, 10000), {"speedup_general_60m": 3}),
         "POPULAR", 30 * 60, 1, 600, {"when": "afterPurchase", "chainOf": "pack_chain_1"}, tier=2),
    pack("pack_chain_3", 1220, 15800, contents(600, res(40000, 40000, 20000, 20000), {"speedup_general_3h": 3},
                                                  {"hero_valeria": 5}),
         "BEST_VALUE", 30 * 60, 1, 600, {"when": "afterPurchase", "chainOf": "pack_chain_2"}, tier=3),
    # ピギーバンク満杯
    pack("pack_piggy_nudge", 160, 980, contents(50), "NONE", 2 * H, 1, 3600, {"when": "piggyFull"}),
    # ── デイリーディール候補 ──
    pack("deal_gems", 610, 9800, contents(1200), "POPULAR", D, 1, 0, deal="normal"),
    pack("deal_res", 610, 7800, contents(0, res(60000, 60000, 30000, 30000)), "NONE", D, 1, 0, deal="normal"),
    pack("deal_speed", 1220, 18800, contents(0, None, {"speedup_general_8h": 2, "speedup_general_60m": 6}),
         "BEST_VALUE", D, 1, 0, deal="normal"),
    pack("deal_hero", 1220, 14800, contents(0, None, {"summon_ticket_premium": 3}, {"hero_thorne": 10}),
         "NONE", D, 1, 0, deal="normal"),
    pack("deal_super_a", 120, 12000, contents(300), "BEST_VALUE", D, 1, 0, deal="super"),
    pack("deal_super_b", 120, 12000, contents(0, res(40000, 40000, 20000, 20000)), "BEST_VALUE", D, 1, 0,
         deal="super"),
    pack("deal_super_c", 120, 12000, contents(0, None, {"speedup_general_8h": 1, "speedup_general_3h": 2}),
         "BEST_VALUE", D, 1, 0, deal="super"),
]

offers = {"gemSkus": gem_skus, "packs": packs}
(root / "offers.json").write_text(json.dumps(offers, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

rules = {
    "yenPerVipPoint": 1,
    "frequency": {
        "sessionCapLow": 2, "sessionCapStandard": 5, "defaultCooldownSec": 1800, "chainMax": 3,
        "queueCap": 12, "reviveDurationSec": 3600, "reviveLabelMass": True,
    },
    "login": {"pointsByStreak": [50, 100, 150, 200, 250, 300]},
    "loginPackSku": "pack_daily",
    "exitPackSku": "pack_wait",
    "socialProofSkus": ["pack_daily", "pack_growth", "pack_speed", "pack_hero", "pack_res_l"],
    "shortage": {
        "resourceYenTable": [[3000, 120], [10000, 160], [40000, 610], [150000, 1220], [600000, 3060],
                             [2500000, 6100], [10000000, 12000]],
        "durationSec": 2 * H, "cooldownSec": 600,
    },
    "dailyDeals": {"normalCount": 2, "superCount": 1, "superOffPct": 99},
    "monthPass": {"sku": "month_pass", "priceYen": 610, "days": 30, "dailyGems": 100,
                  "dailyItems": {"speedup_general_60m": 2}, "listValueYen": 6800},
    "growthFund": {"sku": "growth_fund", "priceYen": 1220, "listValueYen": 9800,
                   "milestones": [[3, 100], [5, 200], [8, 300], [10, 400], [15, 600], [20, 800]]},
    "piggy": {"sku": "piggy_open", "openYen": 610, "cap": 1000, "gemsPerBuild": 8, "gemsPerResearch": 8,
              "gemsPerDefense": 5, "gemsPerMarch": 3},
    "wheel": {
        "freePerDay": 1, "extraSku": "wheel_extra", "extraYen": 120, "extraGems": 100,
        "segments": [
            {"id": "gems_small", "weight": 22, "gems": 10},
            {"id": "food", "weight": 18, "resources": res(food=5000)},
            {"id": "wood", "weight": 14, "resources": res(wood=5000)},
            {"id": "speedup", "weight": 14, "items": {"speedup_general_5m": 3}},
            {"id": "gems_mid", "weight": 12, "gems": 50},
            {"id": "shard_ssr", "weight": 3, "heroShards": {"hero_valeria": 5}, "tease": True},
            {"id": "ticket", "weight": 9, "items": {"summon_ticket_normal": 1}},
            {"id": "gems_big", "weight": 8, "gems": 200},
        ],
    },
    "ledgerCap": 5000,
    "stats": {"vipNearWithinPt": 700},
}
(root / "shop_rules.json").write_text(json.dumps(rules, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

cum = [100, 300, 700, 1500, 3000, 6000, 10000, 16000, 24000, 36000, 52000, 75000, 105000, 150000, 220000]
levels = []
for i, pt in enumerate(cum, start=1):
    levels.append({
        "lv": i, "pt": pt,
        "gatherBp": 100 * i + (100 if i >= 10 else 0),
        "offlineBonusSec": 0 if i < 3 else min(24 * H, (i - 2) * 2 * H),
        "gemSkipBp": 0 if i < 4 else min(3000, (i - 3) * 250),
        "secondBuilder": i >= 6,
        "chest": {"gems": 10 * i, "resources": res(1000 * i, 1000 * i, 500 * i, 500 * i)},
    })
(root / "vip.json").write_text(json.dumps({"levels": levels}, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print("ok", len(packs), "packs")
