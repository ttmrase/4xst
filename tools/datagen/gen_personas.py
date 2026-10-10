#!/usr/bin/env python3
"""personas.json を生成する（03 §2）。実行: python3 tools/datagen/gen_personas.py"""
import json, pathlib

def hours(*ranges):
    return [{"start": a, "end": b} for a, b in ranges]

def pers(ext, care, aggr, meti, humor):
    return {"extraversion": ext, "caring": care, "aggression": aggr, "meticulous": meti, "humor": humor}

personas = [
    {"id": "kaede", "displayName": "Kaede", "rank": 5, "vipLv": 9, "region": "kansai",
     "activeHours": hours((20, 27)), "personality": pers(850, 950, 250, 500, 700),
     "speechStyle": {"endings": ["やで", "やんか", "やな", "なぁ"], "emojiPermille": 350, "punctuation": "！", "tilde": True,
                     "samples": ["みんなお疲れさん！今日もええ感じやで", "ヘルプありがとなぁ、助かるわ", "砦のラリー立てるで、集まってや！",
                                 "無理せんと、ゆっくりやってこな", "よう頑張っとるやん、えらいで"]},
     "specialty": "leader", "castleLvCurve": [[0, 22], [30, 27], [90, 30]], "spendingHabit": 80,
     "favorThresholds": [12, 40, 90, 160], "nicknameSuffix": "はん", "troopTier": 3},
    {"id": "marcus", "displayName": "Marcus_DE", "rank": 4, "vipLv": 11, "region": "de",
     "activeHours": hours((5, 10)), "personality": pers(600, 350, 950, 450, 300),
     "speechStyle": {"endings": ["だぜ", "ぞ", "な"], "emojiPermille": 150, "punctuation": "!", "tilde": False,
                     "samples": ["Good morning、今日は砦を落とすぞ", "Rally 立てた、Join してくれ", "Scout の情報が足りない、頼めるか？",
                                 "Nice job、その調子だ", "Attack は Timing が命だぜ"]},
     "specialty": "war", "castleLvCurve": [[0, 24], [30, 28], [90, 30]], "spendingHabit": 120,
     "favorThresholds": [15, 45, 100, 180], "nicknameSuffix": "-san", "troopTier": 4},
    {"id": "ponzu", "displayName": "ぽんず", "rank": 2, "vipLv": 1, "region": "jp",
     "activeHours": hours((16, 24)), "personality": pers(900, 600, 100, 150, 650),
     "speechStyle": {"endings": ["です", "ですぅ", "かな？", "ます"], "emojiPermille": 600, "punctuation": "！！", "tilde": False,
                     "samples": ["あの、これってどうやるんですか？", "資源ください……お願いします！", "ありがとうございます！！本当に助かります！",
                                 "また質問してもいいですか？", "わぁ、すごいですね！"]},
     "specialty": "newbie", "castleLvCurve": [[0, 6], [30, 12], [90, 18]], "spendingHabit": 10,
     "favorThresholds": [8, 25, 60, 120], "nicknameSuffix": "先輩", "troopTier": 1},
    {"id": "sato", "displayName": "Sato_Farm", "rank": 3, "vipLv": 4, "region": "jp",
     "activeHours": hours((6, 9), (12, 14), (21, 23)), "personality": pers(300, 550, 150, 850, 200),
     "speechStyle": {"endings": ["です", "ます", "だね"], "emojiPermille": 50, "punctuation": "。", "tilde": False,
                     "samples": ["本日の採集、順調です。", "石が余っています。必要なら言ってください。", "あの森は効率が良いですよ。",
                                 "ヘルプありがとうございます。", "淡々とやっていきます。"]},
     "specialty": "gather", "castleLvCurve": [[0, 16], [30, 21], [90, 26]], "spendingHabit": 30,
     "favorThresholds": [12, 40, 90, 160], "nicknameSuffix": "さん", "troopTier": 2},
    {"id": "lin", "displayName": "Lin", "rank": 4, "vipLv": 7, "region": "tw",
     "activeHours": hours((9, 13), (19, 22)), "personality": pers(450, 400, 300, 950, 350),
     "speechStyle": {"endings": ["ですね", "と思います", "ですよ"], "emojiPermille": 100, "punctuation": "。", "tilde": False,
                     "samples": ["研究は軍事の前に経済を上げると効率が良いですよ。", "戦力の伸びを計算してみました。", "ランキング上位との差は約 8% です。",
                                 "ヘルプ、助かります。", "データ上は採集バフが優先です。"]},
     "specialty": "analyst", "castleLvCurve": [[0, 21], [30, 26], [90, 30]], "spendingHabit": 60,
     "favorThresholds": [14, 42, 95, 170], "nicknameSuffix": "さん", "troopTier": 3},
    {"id": "johnny", "displayName": "ジョニー", "rank": 2, "vipLv": 13, "region": "us",
     "activeHours": hours((12, 16), (22, 26)), "personality": pers(950, 400, 350, 150, 900),
     "speechStyle": {"endings": ["だよ！", "でしょ！", "さ！"], "emojiPermille": 750, "punctuation": "！！", "tilde": True,
                     "samples": ["新しいパック買っちゃった！最高！", "ガチャ引いたら神引きだったよ！", "みんなにギフト配るよ〜！",
                                 "課金は正義でしょ！", "ヘルプ感謝！またよろしく！"]},
     "specialty": "spender", "castleLvCurve": [[0, 20], [30, 25], [90, 29]], "spendingHabit": 400,
     "favorThresholds": [10, 30, 80, 150], "nicknameSuffix": "ちゃん", "troopTier": 2},
    {"id": "yuki", "displayName": "Yuki", "rank": 3, "vipLv": 5, "region": "jp",
     "activeHours": hours((10, 12)), "personality": pers(500, 800, 100, 400, 450),
     "speechStyle": {"endings": ["です", "ね", "よ"], "emojiPermille": 450, "punctuation": "…", "tilde": False,
                     "samples": ["久しぶりです！みんな元気でしたか？", "ヘルプ、ありがとうございます…！", "ちょっとログインが空いちゃいました、ごめんなさい。",
                                 "今日はゆっくりできそうです。", "また頑張りますね。"]},
     "specialty": "returner", "castleLvCurve": [[0, 18], [30, 22], [90, 26]], "spendingHabit": 20,
     "favorThresholds": [8, 25, 60, 120], "nicknameSuffix": "さん", "troopTier": 2},
    {"id": "gen", "displayName": "老兵ゲン", "rank": 3, "vipLv": 6, "region": "jp",
     "activeHours": hours((4, 7), (18, 21)), "personality": pers(150, 500, 450, 700, 100),
     "speechStyle": {"endings": ["だ", "ぞ", "のう"], "emojiPermille": 0, "punctuation": "。", "tilde": False,
                     "samples": ["焦るな。城は一日にして成らず。", "備えを怠るな。", "若いの、よくやっておる。",
                                 "ふむ、悪くない。", "昔の戦ではな……いや、よそう。"]},
     "specialty": "veteran", "castleLvCurve": [[0, 25], [30, 28], [90, 30]], "spendingHabit": 15,
     "favorThresholds": [20, 55, 110, 200], "nicknameSuffix": "の", "troopTier": 4},
]

fill_names = ["Aoba", "Rin_88", "TakeBear", "Mizuki", "Hayato_X", "Sora", "Nanami", "KuroNeko", "Daichi", "Emi_Emi", "Toru",
              "Haruka", "Pepper", "Ryo_Z", "Mei", "BlueFox", "Kenta", "Yuzu", "Shin", "Noa", "Tomo", "Hikari"]
fillers = [{"name": n, "castleLv": 8 + (i * 7) % 17, "lastLoginHoursAgo": 1 + (i * 5) % 70} for i, n in enumerate(fill_names)]

out = {"personas": personas, "fillerMembers": fillers,
       "worldNpcNames": ["Aria_JP", "Boris", "Chen_Wei", "DarkLord88", "Elena", "Frost_K", "GrandMaster", "Hiro_T"],
       "allianceName": "Crown Wardens", "allianceTag": "CRW"}
path = pathlib.Path(__file__).resolve().parents[2] / "core/src/main/resources/data/personas.json"
path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print("wrote", path, len(personas), len(fillers))
