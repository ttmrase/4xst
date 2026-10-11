"""胸像（ポートレート）の生成: パーツ（輪郭・肌・髪・目・口・ひげ・兜や冠・鎧やローブ・背景の紋章）を組み合わせて
VectorDrawable を書き出す。標準ライブラリのみ。出力は決定論的。

- 英雄 12 とペルソナ 8（計 20）: 下の SPECS で 1 体ずつ設計する（heroes.json / personas.json の `portrait` と同じ指定）。
- 名前だけのメンバー 22 とプレイヤーのアバター 12: 同じパーツを種（ID）のハッシュで決定論に組み合わせる。
  見た目の系統 look（m / f / n）ごとに使えるパーツを絞る（f はひげなし、m は頬紅・サークレットなし、n は中性的な髪のみ）。
実行: python3 tools/artgen/gen_portraits.py [出力ディレクトリ]   既定は app/src/main/res/drawable
出力: pt_<id>.xml（英雄・ペルソナ）/ pt_gen_<look>_<nn>.xml（名前だけのメンバー）/ pt_avatar_<nn>.xml（プレイヤー）
絵柄: 平らな塗りと縁取りの胸像。頭身は低め（顔が大きい）。枠（レアリティ・VIP）はアプリ側で重ねる。
"""
import math
import pathlib
import sys
import zlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import iso  # noqa: E402
from iso import Scene, shade, mix  # noqa: E402

W = 96
LINE = "#2A2230"
GOLD = "#E8C547"

SKINS = {"pale": "#F6DCC8", "fair": "#F2C9A5", "warm": "#E0A878", "tan": "#C98B5E", "brown": "#9A6444"}
HAIRS = {
    "black": "#26202A", "brown": "#6B4426", "auburn": "#9A4A2A", "red": "#B8402A", "blond": "#E6C25A",
    "gold": "#F0CF6A", "grey": "#9A9AA6", "white": "#ECECF2", "silver": "#C6CCD8", "teal": "#3E8E8A",
    "purple": "#7A4EA8", "orange": "#E9893A", "iceblue": "#BFD8F0",
}
BGS = {
    "slate": "#4D6A66", "teal": "#2F7C7C", "brown": "#7A5636", "navy": "#27406E", "violet": "#4C3A7A", "red": "#8A2F2F",
    "gold": "#C79B2E", "grey": "#5E6672", "green": "#4A7A44", "orange": "#D17A2E", "yellow": "#D9B83A", "olive": "#6A6A38",
    "magenta": "#A53A7E", "ice": "#7FA9C9", "cyan": "#2F6E8A", "dark": "#2E2A3A",
}


def ell(sc, cx, cy, rx, ry, fill, stroke=LINE, sw=0.8, alpha=1.0, n=24):
    sc.ellipse(cx, cy, rx, ry, fill, stroke, sw, alpha, n)


def sup(cx, cy, rx, ry_top, ry_bot, p=2.0, n=36):
    """上半分は楕円、下半分は p（2=楕円、大きいほど四角い）の超楕円。顔の輪郭。"""
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        c, s = math.cos(t), math.sin(t)
        if s <= 0:
            pts.append((cx + rx * c, cy + ry_top * s))
        else:
            e = 2.0 / p
            pts.append((cx + rx * math.copysign(abs(c) ** e, c), cy + ry_bot * (abs(s) ** e)))
    return pts


# ───────── 背景と紋章 ─────────
def rrect(x0, y0, x1, y1, r, n=5):
    pts = []
    for cx, cy, a0 in ((x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for k in range(n + 1):
            a = math.radians(a0 + 90 * k / n)
            pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def star_pts(cx, cy, ro, ri, n=5, rot=-90):
    pts = []
    for i in range(n * 2):
        r = ro if i % 2 == 0 else ri
        a = math.radians(rot + 180 * i / n)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def crest(sc, kind, bg):
    c = shade(bg, 1.35)
    cx, cy = 48, 36
    a = 0.55
    if kind in ("shield", "tower", "sword", "crown"):
        a = 0.42
    if kind == "star":
        sc.poly(star_pts(cx, cy, 26, 11), c, None, 0, a)
    elif kind == "sun":
        sc.poly(star_pts(cx, cy, 30, 20, 12), c, None, 0, a)
        ell(sc, cx, cy, 15, 15, shade(bg, 1.6), None, 0, a)
    elif kind == "moon":
        ell(sc, cx, cy, 22, 22, c, None, 0, a)
        ell(sc, cx + 9, cy - 4, 18, 18, bg, None, 0, 1.0)
    elif kind == "shield":
        sc.poly([(cx - 20, cy - 20), (cx - 8, cy - 24), (cx, cy - 20), (cx + 8, cy - 24), (cx + 20, cy - 20), (cx + 20, cy + 4),
                 (cx, cy + 28), (cx - 20, cy + 4)], c, shade(bg, 1.7), 1.2, a)
        sc.line([(cx, cy - 18), (cx, cy + 20)], shade(bg, 1.7), 1.0, a)
        sc.line([(cx - 15, cy - 4), (cx + 15, cy - 4)], shade(bg, 1.7), 1.0, a)
    elif kind == "sword":
        sc.poly([(cx - 3, cy - 30), (cx + 3, cy - 30), (cx + 3, cy + 14), (cx - 3, cy + 14)], c, None, 0, a)
        sc.poly([(cx - 14, cy + 12), (cx + 14, cy + 12), (cx + 14, cy + 17), (cx - 14, cy + 17)], c, None, 0, a)
    elif kind == "crown":
        sc.poly([(cx - 24, cy + 14), (cx - 24, cy - 14), (cx - 12, cy), (cx, cy - 20), (cx + 12, cy), (cx + 24, cy - 14), (cx + 24, cy + 14)], c, None, 0, a)
    elif kind == "leaf":
        sc.poly([(cx, cy - 28), (cx + 20, cy - 6), (cx, cy + 26), (cx - 20, cy - 6)], c, None, 0, a)
    elif kind == "flame":
        sc.poly([(cx, cy - 30), (cx + 16, cy - 4), (cx + 8, cy + 24), (cx - 8, cy + 24), (cx - 16, cy - 4), (cx - 4, cy - 12)], c, None, 0, a)
    elif kind == "tower":
        sc.poly([(cx - 14, cy + 24), (cx - 14, cy - 10), (cx - 18, cy - 10), (cx - 18, cy - 24), (cx - 8, cy - 24), (cx - 8, cy - 18),
                 (cx + 8, cy - 18), (cx + 8, cy - 24), (cx + 18, cy - 24), (cx + 18, cy - 10), (cx + 14, cy - 10), (cx + 14, cy + 24)],
                c, shade(bg, 1.7), 1.2, a)
        sc.poly([(cx - 4, cy + 24), (cx - 4, cy + 12), (cx, cy + 8), (cx + 4, cy + 12), (cx + 4, cy + 24)], bg, None, 0, 0.9)
        sc.poly([(cx - 2, cy - 8), (cx + 2, cy - 8), (cx + 2, cy), (cx - 2, cy)], bg, None, 0, 0.9)
    elif kind == "wheel":
        ell(sc, cx, cy, 24, 24, c, None, 0, a)
        ell(sc, cx, cy, 14, 14, bg, None, 0, 1.0)
        ell(sc, cx, cy, 5, 5, c, None, 0, a)


def background(sc, spec):
    bg = BGS[spec["bg"]]
    sc.poly(rrect(1, 1, 95, 95, 11), shade(bg, 0.8), None, 0)
    ell(sc, 48, 40, 52, 46, bg, None, 0, 1.0, 32)
    ell(sc, 48, 38, 34, 32, shade(bg, 1.18), None, 0, 0.55, 28)
    if spec.get("halo"):
        sc.poly(star_pts(48, 40, 62, 30, 16), shade(bg, 1.7), None, 0, 0.35)
        ell(sc, 48, 40, 40, 38, shade(bg, 1.5), None, 0, 0.5, 32)
    crest(sc, spec.get("crest", "star"), bg)


# ───────── 体（鎧・ローブ・服） ─────────
def shoulders(top=70):
    return [(6, 97), (10, top + 6), (26, top - 2), (38, top - 8), (58, top - 8), (70, top - 2), (86, top + 6), (90, 97)]


def body(sc, spec):
    ac = spec.get("ac", "#6A7A90")
    kind = spec.get("armor", "plate")
    trim = spec.get("trim", GOLD)
    dk = shade(ac, 0.55)
    if spec.get("cloak"):
        cc = spec["cloak"]
        sc.poly([(0, 97), (4, 62), (22, 54), (74, 54), (92, 62), (96, 97)], cc, shade(cc, 0.5), 0.8)
    if kind in ("fur", "furplate"):
        sc.poly(shoulders(), shade(ac, 1.0), dk, 0.9)
    else:
        sc.poly(shoulders(), ac, dk, 0.9)
    # 首
    sk = SKINS[spec["skin"]]
    sc.poly([(41, 56), (55, 56), (57, 71), (48, 77), (39, 71)], shade(sk, 0.86), LINE, 0.8)
    if kind == "plate":
        for sx in (-1, 1):
            ell(sc, 48 + sx * 31, 77, 13, 9, shade(ac, 1.15), dk, 0.9)
            ell(sc, 48 + sx * 31, 75, 8, 4, shade(ac, 1.4), None, 0, 0.7)
        sc.poly([(36, 70), (60, 70), (66, 82), (48, 88), (30, 82)], shade(ac, 0.9), dk, 0.8)
        sc.line([(36, 70), (48, 77), (60, 70)], trim, 1.4)
        for rx in (24, 72):
            ell(sc, rx, 80, 1.3, 1.3, trim, None, 0)
    elif kind == "mail":
        for yy in range(78, 97, 5):
            for xx in range(12, 86, 6):
                sc.line([(xx + (yy % 2) * 3, yy), (xx + 3 + (yy % 2) * 3, yy + 2)], dk, 0.7, 0.6)
        sc.line([(36, 70), (48, 78), (60, 70)], dk, 1.2)
    elif kind == "robe":
        sc.poly([(38, 70), (48, 86), (58, 70), (48, 66)], shade(sk, 0.92), LINE, 0.7)
        sc.poly([(30, 68), (48, 90), (66, 68), (62, 66), (48, 82), (34, 66)], shade(ac, 1.25), dk, 0.8)
        sc.line([(12, 97), (40, 78)], trim, 1.6)
        sc.line([(84, 97), (56, 78)], trim, 1.6)
    elif kind == "leather":
        sc.line([(20, 72), (60, 97)], dk, 3.2)
        sc.line([(76, 72), (36, 97)], dk, 3.2)
        sc.poly([(44, 85), (52, 85), (52, 91), (44, 91)], trim, LINE, 0.6)
        sc.line([(36, 70), (48, 76), (60, 70)], dk, 1.2)
    elif kind == "noble":
        sc.poly([(34, 68), (44, 60), (48, 74)], shade(ac, 1.3), dk, 0.8)
        sc.poly([(62, 68), (52, 60), (48, 74)], shade(ac, 1.3), dk, 0.8)
        sc.line([(48, 74), (48, 97)], dk, 1.2)
        for yy in (80, 87, 94):
            ell(sc, 48, yy, 1.6, 1.6, trim, None, 0)
        for sx in (-1, 1):
            sc.poly([(48 + sx * 32, 74), (48 + sx * 40, 78), (48 + sx * 36, 84), (48 + sx * 26, 78)], trim, dk, 0.6)
    elif kind in ("fur", "furplate"):
        sc.poly([(6, 97), (10, 76), (22, 70), (30, 74), (38, 70), (48, 76), (58, 70), (66, 74), (74, 70), (86, 76), (90, 97)],
                "#E6E2DA", "#8A857C", 0.9)
        for xx in range(14, 84, 9):
            sc.line([(xx, 76), (xx + 2, 84)], "#B7B2A8", 1.0)
        sc.poly([(36, 70), (60, 70), (56, 78), (40, 78)], shade(ac, 0.9), dk, 0.8)
    elif kind == "farm":
        sc.poly([(30, 72), (66, 72), (66, 97), (30, 97)], shade(ac, 0.8), dk, 0.8)
        sc.line([(34, 72), (34, 66)], dk, 2.6)
        sc.line([(62, 72), (62, 66)], dk, 2.6)
        sc.poly([(40, 80), (56, 80), (56, 88), (40, 88)], shade(ac, 0.65), dk, 0.6)
    elif kind == "scarf":
        sc_c = spec.get("sc", "#D85A5A")
        sd = shade(sc_c, 0.5)
        sc.poly([(30, 68), (40, 64), (56, 64), (66, 68), (68, 74), (60, 79), (48, 81), (36, 79), (28, 74)], sc_c, sd, 0.8)
        sc.line([(32, 72), (48, 77), (64, 72)], shade(sc_c, 1.25), 1.2, 0.8)
        sc.poly([(56, 76), (65, 74), (68, 92), (63, 89), (60, 95), (55, 90)], sc_c, sd, 0.8)
        ell(sc, 59, 75, 4.2, 3.6, shade(sc_c, 0.85), sd, 0.7, 1.0, 12)
    if spec.get("cloak"):
        ell(sc, 48, 72, 3.5, 3.5, trim, LINE, 0.7)


# ───────── 髪 ─────────
def hair_back(sc, spec):
    hc = HAIRS[spec["hc"]]
    st = spec.get("hair", "short")
    ol = shade(hc, 0.55)
    if st in ("long", "ponytail", "braid"):
        sc.poly([(26, 30), (22, 52), (20, 84), (34, 84), (36, 56), (60, 56), (62, 84), (76, 84), (74, 52), (70, 30)], hc, ol, 0.9)
    if st == "bob":
        sc.poly([(26, 30), (24, 62), (36, 64), (60, 64), (72, 62), (70, 30)], hc, ol, 0.9)
    if st == "bun":
        ell(sc, 48, 14, 8, 7, hc, ol, 0.9)


def hair_front(sc, spec):
    hc = HAIRS[spec["hc"]]
    st = spec.get("hair", "short")
    ol = shade(hc, 0.55)
    top = [(29, 40), (28, 28), (34, 18), (48, 14), (62, 18), (68, 28), (67, 40)]
    if st in ("short", "bun", "long", "bob", "braid", "ponytail"):
        sc.poly(top + [(60, 30), (48, 26), (38, 30)], hc, ol, 0.9)
        if st in ("long", "bob"):
            sc.poly([(29, 40), (30, 52), (34, 48), (36, 32)], hc, ol, 0.7)
            sc.poly([(67, 40), (66, 52), (62, 48), (60, 32)], hc, ol, 0.7)
        if st == "braid":
            for k in range(4):
                ell(sc, 70, 56 + k * 7, 4.2, 4.6, hc, ol, 0.8)
        if st == "ponytail":
            sc.poly([(66, 24), (80, 28), (82, 50), (74, 60), (72, 40), (64, 32)], hc, ol, 0.9)
    elif st == "spiky":
        sc.poly([(29, 40), (26, 26), (32, 22), (32, 10), (40, 18), (46, 6), (52, 17), (60, 8), (62, 20), (70, 14), (68, 28), (68, 40),
                 (62, 30), (48, 26), (36, 30)], hc, ol, 0.9)
    elif st == "mohawk":
        sc.poly([(41, 20), (42, 4), (48, 0), (54, 4), (55, 20), (48, 24)], hc, ol, 0.9)
        sc.poly([(29, 40), (30, 30), (36, 26), (36, 36)], shade(hc, 0.8), ol, 0.7)
        sc.poly([(67, 40), (66, 30), (60, 26), (60, 36)], shade(hc, 0.8), ol, 0.7)
    elif st == "curly":
        for (cx, cy, r) in ((34, 28, 8), (44, 20, 9), (56, 20, 9), (64, 28, 8), (30, 38, 6), (66, 38, 6), (48, 24, 8)):
            ell(sc, cx, cy, r, r, hc, ol, 0.8)
    elif st == "pompadour":
        sc.poly([(29, 40), (28, 26), (36, 10), (52, 2), (68, 10), (68, 40), (62, 30), (48, 24), (36, 30)], hc, ol, 0.9)
        sc.line([(40, 10), (60, 8)], shade(hc, 1.3), 1.2)


# ───────── 顔のパーツ ─────────
def eyes(sc, spec):
    st = spec.get("eyes", "dot")
    ic = spec.get("ic", "#3A3050")
    for sx in (-1, 1):
        x = 48 + sx * 9
        y = 45
        if st == "dot":
            ell(sc, x, y, 2.2, 2.6, "#2A2030", None, 0, 1.0, 12)
            ell(sc, x - 0.7, y - 0.8, 0.8, 0.8, "#FFFFFF", None, 0, 1.0, 8)
        elif st == "round":
            ell(sc, x, y, 4.2, 4.6, "#FFFFFF", LINE, 0.7, 1.0, 16)
            ell(sc, x, y + 0.4, 2.6, 3.0, ic, None, 0, 1.0, 12)
            ell(sc, x - 0.8, y - 0.8, 0.9, 0.9, "#FFFFFF", None, 0, 1.0, 8)
        elif st == "big":
            ell(sc, x, y, 5.2, 6.2, "#FFFFFF", LINE, 0.7, 1.0, 16)
            ell(sc, x, y + 0.6, 3.6, 4.4, ic, None, 0, 1.0, 14)
            ell(sc, x - 1.2, y - 1.4, 1.3, 1.3, "#FFFFFF", None, 0, 1.0, 8)
        elif st == "sharp":
            sc.poly([(x - sx * 4.8, y + 1.6), (x - sx * 1.0, y - 1.8), (x + sx * 4.8, y - 0.8), (x + sx * 1.2, y + 2.2)],
                    "#FFFFFF", LINE, 0.7)
            ell(sc, x + sx * 0.2, y + 0.3, 1.9, 1.9, ic, None, 0, 1.0, 10)
            ell(sc, x - 0.2, y - 0.4, 0.6, 0.6, "#FFFFFF", None, 0, 1.0, 6)
        elif st == "happy":
            sc.line([(x - 4.2, y + 1.6), (x - 2, y - 1.4), (x, y - 2), (x + 2, y - 1.4), (x + 4.2, y + 1.6)], LINE, 1.5)
        elif st == "closed":
            sc.line([(x - 4, y + 0.4), (x, y + 2), (x + 4, y + 0.4)], LINE, 1.4)
        elif st == "soft":
            ell(sc, x, y, 3.6, 3.8, "#FFFFFF", LINE, 0.6, 1.0, 14)
            ell(sc, x, y + 0.6, 2.4, 2.6, ic, None, 0, 1.0, 12)
            sc.line([(x - 4.4, y - 2.4), (x, y - 4.2), (x + 4.4, y - 2.4)], LINE, 0.9)
            ell(sc, x - 0.7, y - 0.4, 0.8, 0.8, "#FFFFFF", None, 0, 1.0, 8)
    # まゆ
    bs = spec.get("brow", "flat")
    hc = shade(HAIRS[spec["hc"]], 0.9) if not spec.get("browc") else spec["browc"]
    for sx in (-1, 1):
        x = 48 + sx * 9
        if bs == "flat":
            sc.line([(x - 5, 39.5), (x + 5, 39.5)], hc, 1.6)
        elif bs == "angry":
            sc.line([(x - 5, 37 if sx == -1 else 41), (x + 5, 41 if sx == -1 else 37)], hc, 1.9)
        elif bs == "soft":
            sc.line([(x - 5, 40.5), (x, 38), (x + 5, 40.5)], hc, 1.3)
        elif bs == "thick":
            sc.line([(x - 5.5, 39.5), (x + 5.5, 39.5)], hc, 2.8)


def face_extras(sc, spec):
    ex = spec.get("extra", [])
    if "blush" in ex:
        for sx in (-1, 1):
            ell(sc, 48 + sx * 13, 52, 4, 2.4, "#F08A8A", None, 0, 0.45, 12)
    if "freckles" in ex:
        for sx in (-1, 1):
            for (dx, dy) in ((10, 50), (14, 52), (12, 54)):
                ell(sc, 48 + sx * dx, dy, 0.6, 0.6, "#B8744C", None, 0, 0.9, 6)
    if "scar" in ex:
        sc.line([(34, 36), (42, 54)], "#B04A4A", 1.4)
        sc.line([(35, 42), (39, 41)], "#B04A4A", 1.0)
        sc.line([(37, 48), (41, 47)], "#B04A4A", 1.0)
    if "earring" in ex:
        ell(sc, 30, 52, 1.6, 2.0, GOLD, LINE, 0.5, 1.0, 10)
    if "glasses" in ex:
        for sx in (-1, 1):
            ell(sc, 48 + sx * 9, 45, 6.4, 5.4, "#FFFFFF", "#33303A", 1.1, 0.25, 16)
        sc.line([(54.4, 45), (41.6, 45)], "#33303A", 1.0)
        sc.line([(25, 44), (35, 45)], "#33303A", 0.9)
        sc.line([(71, 44), (61, 45)], "#33303A", 0.9)
    if "shades" in ex:
        for sx in (-1, 1):
            sc.poly(rrect(48 + sx * 9 - 7, 41, 48 + sx * 9 + 7, 50, 3, 3), "#1C1A24", "#0E0C14", 0.8)
        sc.line([(41, 43), (55, 43)], "#0E0C14", 1.2)
        sc.line([(24, 43), (34, 43)], "#0E0C14", 1.0)
        sc.line([(72, 43), (62, 43)], "#0E0C14", 1.0)
    if "eyepatch" in ex:
        sc.poly([(36, 40), (48, 41), (47, 50), (37, 50)], "#2A2230", None, 0)
        sc.line([(28, 38), (36, 41)], "#2A2230", 1.0)


def mouth(sc, spec):
    st = spec.get("mouth", "smile")
    y = 57
    if st == "smile":
        sc.line([(41.5, y - 1), (45, y + 2.4), (51, y + 2.4), (54.5, y - 1)], "#8A3A3A", 1.4)
    elif st == "grin":
        sc.poly([(40, y - 1), (56, y - 1), (53, y + 5), (48, y + 6), (43, y + 5)], "#7A2A32", LINE, 0.8)
        sc.poly([(41.5, y - 0.4), (54.5, y - 0.4), (54, y + 1.6), (42, y + 1.6)], "#FFFFFF", None, 0)
    elif st == "flat":
        sc.line([(42.5, y + 1), (53.5, y + 1)], "#7A3A3A", 1.4)
    elif st == "smirk":
        sc.line([(42, y + 1.6), (49, y + 1.2), (55, y - 1.2)], "#8A3A3A", 1.4)
    elif st == "open":
        ell(sc, 48, y + 2.4, 3.8, 3.2, "#7A2A32", LINE, 0.7, 1.0, 14)
        ell(sc, 48, y + 3.6, 2.2, 1.2, "#E87A7A", None, 0, 1.0, 10)
    elif st == "frown":
        sc.line([(42, y + 3), (48, y), (54, y + 3)], "#7A3A3A", 1.4)


def beard(sc, spec, front=True):
    b = spec.get("beard")
    if not b:
        return
    hc = HAIRS[spec.get("bc", spec["hc"])]
    ol = shade(hc, 0.55)
    if b == "stubble" and front:
        sc.poly([(32, 50), (34, 62), (44, 68), (52, 68), (62, 62), (64, 50), (58, 56), (48, 60), (38, 56)], hc, None, 0, 0.35)
    elif b == "full" and front:
        sc.poly([(31, 46), (32, 62), (40, 72), (56, 72), (64, 62), (65, 46), (60, 54), (54, 53), (48, 55), (42, 53), (36, 54)], hc, ol, 0.9)
    elif b == "long" and front:
        sc.poly([(31, 46), (32, 64), (40, 90), (48, 94), (56, 90), (64, 64), (65, 46), (60, 54), (54, 53), (48, 55), (42, 53), (36, 54)], hc, ol, 0.9)
    elif b == "goatee" and front:
        sc.poly([(42, 60), (54, 60), (52, 70), (48, 73), (44, 70)], hc, ol, 0.8)
    elif b == "mustache" and front:
        sc.poly([(38, 54), (48, 52), (58, 54), (56, 58), (48, 56), (40, 58)], hc, ol, 0.8)


def head(sc, spec):
    sk = SKINS[spec["skin"]]
    shape = spec.get("face", "oval")
    ry_top, ry_bot, p = 21, 22, 2.2
    if shape == "round":
        ry_top, ry_bot, p = 21, 21, 2.0
    elif shape == "square":
        ry_top, ry_bot, p = 20, 21, 3.4
    elif shape == "long":
        ry_top, ry_bot, p = 21, 24, 2.4
    for sx in (-1, 1):
        ell(sc, 48 + sx * 18.5, 47, 3.2, 4.4, shade(sk, 0.95), LINE, 0.8, 1.0, 12)
    sc.poly(sup(48, 40, 18.5, ry_top, ry_bot, p), sk, LINE, 0.9)
    sc.poly([(48, 47), (46.5, 51.5), (49.5, 51.5)], shade(sk, 0.8), None, 0)
    sc.line([(46.2, 51.8), (48, 52.6), (50, 51.8)], shade(sk, 0.6), 0.8)


# ───────── 頭の装飾 ─────────
def gear(sc, spec):
    g = spec.get("gear")
    if not g:
        return
    gc = spec.get("gc", "#8C95A6")
    dk = shade(gc, 0.5)
    if g in ("helm", "horned", "winged"):
        outer = [(48 + 21 * math.cos(math.radians(a)), 29 + 19 * math.sin(math.radians(a))) for a in range(180, 361, 15)]
        inner = [(48 + 16.5 * math.cos(math.radians(a)), 36 + 13 * math.sin(math.radians(a))) for a in range(360, 179, -15)]
        sc.poly([(27, 42)] + outer + [(69, 42), (64, 42)] + inner + [(32, 42)], gc, dk, 0.9)
        sc.poly([(46.6, 24), (49.4, 24), (49.6, 38), (48, 47), (46.4, 38)], shade(gc, 1.1), dk, 0.6)
        sc.line([(30, 22), (66, 22)], shade(gc, 1.4), 1.0, 0.8)
        if g == "horned":
            for sx in (-1, 1):
                sc.poly([(48 + sx * 20, 20), (48 + sx * 30, 14), (48 + sx * 36, 2), (48 + sx * 26, 6), (48 + sx * 18, 12)], "#E8E0CC", "#8A8068", 0.8)
        if g == "winged":
            for sx in (-1, 1):
                sc.poly([(48 + sx * 20, 22), (48 + sx * 34, 20), (48 + sx * 44, 8), (48 + sx * 34, 12), (48 + sx * 40, 4),
                         (48 + sx * 28, 8), (48 + sx * 22, 14)], "#F4F4FA", "#8A8AA0", 0.8)
    elif g == "crown":
        sc.poly([(31, 24), (31, 6), (39, 16), (44, 4), (48, 16), (52, 4), (57, 16), (65, 6), (65, 24)], GOLD, "#8A6A1A", 0.9)
        ell(sc, 48, 12, 2, 2, "#D8404A", "#8A6A1A", 0.5, 1.0, 8)
        sc.line([(31, 22), (65, 22)], "#8A6A1A", 0.8)
    elif g == "circlet":
        sc.line([(29, 26), (36, 20), (48, 18), (60, 20), (67, 26)], GOLD, 2.2)
        ell(sc, 48, 18, 2.4, 2.4, spec.get("gem", "#4AA8D8"), "#2A5A7A", 0.6, 1.0, 8)
    elif g == "hood":
        sc.poly([(22, 60), (20, 30), (30, 10), (48, 4), (66, 10), (76, 30), (74, 60), (64, 40), (62, 28), (48, 22), (34, 28), (32, 40)], gc, dk, 0.9)
    elif g == "wizard":
        sc.poly([(24, 28), (72, 28), (66, 22), (56, 4), (50, -6), (46, 4), (38, 22)], gc, dk, 0.9)
        sc.poly([(20, 30), (76, 30), (70, 26), (26, 26)], shade(gc, 1.25), dk, 0.8)
        sc.line([(34, 24), (62, 24)], GOLD, 1.4)
    elif g == "beret":
        ell(sc, 52, 18, 20, 9, gc, dk, 0.9)
        ell(sc, 60, 11, 2.4, 2.4, gc, dk, 0.7, 1.0, 8)
    elif g == "straw":
        ell(sc, 48, 26, 36, 8, "#E2C46E", "#8A6A2A", 0.9)
        sc.poly([(32, 26), (34, 12), (48, 6), (62, 12), (64, 26)], "#EAD07E", "#8A6A2A", 0.9)
        sc.line([(32, 22), (64, 22)], "#B24A3A", 1.8)
    elif g == "headband":
        sc.line([(29, 29), (48, 25), (67, 29)], gc, 3.0)
        sc.poly([(66, 28), (74, 32), (72, 38), (66, 33)], gc, None, 0)
    elif g == "cap":
        sc.poly([(28, 34), (28, 26), (34, 16), (48, 12), (62, 16), (68, 26), (68, 34), (60, 28), (48, 24), (36, 28)], gc, dk, 0.9)
        sc.poly([(66, 30), (80, 34), (66, 36)], shade(gc, 0.8), dk, 0.7)


def make(spec):
    sc = Scene(W, W)
    background(sc, spec)
    body(sc, spec)
    hair_back(sc, spec)
    head(sc, spec)
    beard(sc, spec, front=False)
    eyes(sc, spec)
    face_extras(sc, spec)
    beard(sc, spec)
    mouth(sc, spec)
    if spec.get("hair") != "bald" and spec.get("gear") not in ("hood", "helm", "horned", "winged", "straw", "wizard"):
        hair_front(sc, spec)
    elif spec.get("hair") not in ("bald", None) and spec.get("gear") in ("hood", "straw", "wizard"):
        hair_front(sc, spec)
    gear(sc, spec)
    return sc


# ───────── 英雄 12 とペルソナ 8（個別の設計） ─────────
def S(**k):
    return k


SPECS = {
    # 英雄: N
    "hero_tobias": S(skin="fair", hair="short", hc="brown", eyes="dot", brow="soft", mouth="smile", armor="leather", ac="#7A5A3A",
                     gear="cap", gc="#8A6A44", bg="slate", crest="sword", extra=["freckles"]),
    "hero_mira": S(skin="warm", hair="ponytail", hc="auburn", eyes="round", ic="#2E6A4A", brow="soft", mouth="smile", armor="leather",
                   ac="#4A7A4E", gear="headband", gc="#D8C04A", bg="teal", crest="leaf", extra=["blush"]),
    "hero_dunstan": S(skin="tan", face="round", hair="bald", hc="grey", beard="full", bc="grey", eyes="dot", brow="thick", mouth="flat",
                      armor="mail", ac="#7C8494", gear="helm", gc="#8C95A6", bg="brown", crest="sun"),
    # 英雄: R
    "hero_aldric": S(skin="fair", hair="short", hc="blond", eyes="round", ic="#3A6AB8", brow="flat", mouth="smile", armor="plate",
                     ac="#B4BCCB", cloak="#2F5AA8", bg="navy", crest="sun"),
    "hero_seren": S(skin="pale", hair="long", hc="silver", eyes="big", ic="#4AA8B8", brow="soft", mouth="smile", armor="robe", ac="#3E8E8A",
                    gear="circlet", gem="#7AD8E8", bg="cyan", crest="moon", extra=["blush"]),
    "hero_brann": S(skin="tan", face="square", hair="spiky", hc="red", beard="full", eyes="sharp", ic="#4A3A2A", brow="angry", mouth="grin",
                    armor="fur", ac="#7A4A3A", bg="red", crest="flame"),
    "hero_ilsa": S(skin="fair", hair="braid", hc="black", eyes="sharp", ic="#3A5A9A", brow="flat", mouth="smirk", armor="mail", ac="#4A6A9A",
                   bg="grey", crest="sword", extra=["scar"]),
    # 英雄: SR
    "hero_corvin": S(skin="pale", face="long", hair="long", hc="black", eyes="sharp", ic="#B83A4A", brow="angry", mouth="smirk",
                     armor="leather", ac="#3A3048", gear="hood", gc="#3E3458", bg="violet", crest="moon", extra=["earring"]),
    "hero_yselda": S(skin="fair", hair="long", hc="purple", eyes="big", ic="#B86AD8", brow="soft", mouth="smile", armor="robe", ac="#6A3E9A",
                     trim=GOLD, gear="circlet", gem="#E86AB8", bg="violet", crest="star", extra=["blush"]),
    "hero_thorne": S(skin="tan", face="square", hair="short", hc="grey", beard="stubble", eyes="dot", brow="angry", mouth="frown",
                     armor="plate", ac="#5A6070", gear="helm", gc="#6A7080", bg="olive", crest="sun", extra=["scar"]),
    # 英雄: SSR
    "hero_valeria": S(halo=1, skin="warm", face="oval", hair="long", hc="gold", eyes="sharp", ic="#2E8AC8", brow="flat", mouth="smirk",
                      armor="plate", ac="#E4E8F2", trim=GOLD, cloak="#B8303A", gear="winged", gc="#E8D890", bg="gold", crest="sun"),
    "hero_ostrik": S(halo=1, skin="tan", face="square", hair="long", hc="white", beard="long", eyes="dot", brow="thick", mouth="flat",
                     armor="furplate", ac="#C9A23A", trim="#FFE58A", cloak="#7A1F2A", gear="crown", bg="red", crest="crown"),
    # ペルソナ
    "kaede": S(skin="warm", hair="spiky", hc="brown", eyes="happy", brow="soft", mouth="grin", armor="noble", ac="#3A6AB8", trim=GOLD,
               gear="circlet", gem="#E8A83A", bg="orange", crest="star", extra=["blush"]),
    "marcus": S(skin="tan", face="square", hair="mohawk", hc="black", eyes="sharp", ic="#B83A2A", brow="angry", mouth="smirk",
                armor="plate", ac="#8A2F2F", bg="dark", crest="flame", extra=["scar"]),
    "ponzu": S(skin="fair", face="round", hair="curly", hc="orange", eyes="big", ic="#7A4A2A", brow="soft", mouth="open", armor="robe",
               ac="#E87AA8", gear="beret", gc="#D84A6A", bg="yellow", crest="star", extra=["blush", "freckles"]),
    "sato": S(skin="tan", face="round", hair="short", hc="grey", beard="mustache", eyes="dot", brow="soft", mouth="smile", armor="farm",
              ac="#5A8A4A", gear="straw", bg="green", crest="leaf"),
    "lin": S(skin="fair", hair="bob", hc="black", eyes="dot", brow="flat", mouth="flat", armor="noble", ac="#2E6A7A", trim="#B8D8E0",
             bg="cyan", crest="wheel", extra=["glasses"]),
    "johnny": S(skin="warm", hair="pompadour", hc="blond", eyes="happy", brow="flat", mouth="grin", armor="leather", ac="#E8C03A",
                bg="magenta", crest="star", extra=["shades", "earring"]),
    "yuki": S(skin="pale", hair="long", hc="iceblue", eyes="soft", ic="#5A8AC8", brow="soft", mouth="smile", armor="scarf", ac="#3A4A7A",
              sc="#E8EEF8", bg="ice", crest="moon", extra=["blush"]),
    "gen": S(skin="tan", face="square", hair="bald", hc="grey", beard="long", bc="grey", eyes="dot", brow="thick", mouth="flat",
             armor="furplate", ac="#7A6240", gear="helm", gc="#7A7468", bg="olive", crest="sun", extra=["scar"]),
}

ARMOR_COLORS = ["#6A7A90", "#7A5A3A", "#3E6A4A", "#8A3A3A", "#4A4A7A", "#9A8A4A", "#5A5A60", "#3A6A7A"]
ARMOR_KINDS = ["plate", "mail", "robe", "leather", "noble", "farm", "scarf", "fur"]
HAIR_STYLES = ["short", "spiky", "long", "bun", "bald", "mohawk", "braid", "curly", "bob", "pompadour", "ponytail"]
EYES_ALL = ["dot", "round", "sharp", "happy", "big", "soft", "closed"]
BROWS = ["flat", "angry", "soft", "thick"]
MOUTHS = ["smile", "grin", "flat", "smirk", "open", "frown"]
BEARDS = [None, None, None, "stubble", "full", "goatee", "mustache", "long"]
GEARS = [None, None, None, "helm", "cap", "headband", "beret", "hood", "circlet"]
EXTRAS = [[], [], ["blush"], ["freckles"], ["scar"], ["glasses"], ["earring"]]
GC = ["#8C95A6", "#8A6A44", "#D8C04A", "#3E3458", "#B84A5A", "#4A7A9A"]
CRESTS = ["star", "sun", "moon", "sword", "leaf", "flame", "wheel", "crown"]


def pick(seed, key, options):
    h = zlib.crc32(("%s:%s" % (seed, key)).encode())
    return options[h % len(options)]


GENERIC_MOUTHS = ["smile", "smile", "grin", "flat", "smirk", "frown"]   # 驚き顔（open）は汎用では使わない
HAIR_BY_LOOK = {
    "m": ["short", "spiky", "short", "bald", "mohawk", "curly", "pompadour", "spiky"],
    "f": ["long", "bun", "braid", "bob", "ponytail", "long", "curly", "bob"],
    "n": ["bob", "short", "curly", "spiky", "short", "bob"],
}
GEARS_BY_LOOK = {
    "m": [None, None, None, "helm", "cap", "headband", "hood"],
    "f": [None, None, None, "beret", "circlet", "headband", "hood"],
    "n": [None, None, None, "cap", "beret", "headband", "hood"],
}
EXTRAS_BY_LOOK = {
    "m": [[], [], ["freckles"], ["scar"], ["glasses"], ["earring"]],
    "f": [[], [], ["blush"], ["freckles"], ["glasses"], ["earring"], ["blush"]],
    "n": [[], [], ["freckles"], ["glasses"], ["earring"]],
}
BEARDS_BY_LOOK = {"m": [None, None, "stubble", "full", "goatee", "mustache", "long"], "f": [None], "n": [None]}


def generic_spec(seed, look="n"):
    sp = S(skin=pick(seed, "skin", list(SKINS)), hc=pick(seed, "hc", list(HAIRS)), hair=pick(seed, "hair", HAIR_BY_LOOK[look]),
           eyes=pick(seed, "eyes", EYES_ALL), brow=pick(seed, "brow", BROWS), mouth=pick(seed, "mouth", GENERIC_MOUTHS),
           armor=pick(seed, "armor", ARMOR_KINDS), ac=pick(seed, "ac", ARMOR_COLORS), bg=pick(seed, "bg", list(BGS)),
           crest=pick(seed, "crest", CRESTS), face=pick(seed, "face", ["oval", "round", "square", "long"] if look == "m" else ["oval", "round"]),
           beard=pick(seed, "beard", BEARDS_BY_LOOK[look]), gear=pick(seed, "gear", GEARS_BY_LOOK[look]), gc=pick(seed, "gc", GC),
           extra=pick(seed, "extra", EXTRAS_BY_LOOK[look]), sc=pick(seed, "sc", ["#D85A5A", "#5A8AD8", "#E8C03A", "#5AB87A"]))
    if sp["hair"] == "bald" and sp["beard"] is None:
        sp["beard"] = "stubble"
    if "glasses" in sp["extra"] and sp["eyes"] in ("happy", "closed"):
        sp["eyes"] = "dot"
    if sp["gear"] in ("hood", "helm") and sp["hair"] in ("mohawk", "pompadour", "spiky"):
        sp["gear"] = None
    if look == "f":
        sp["brow"] = pick(seed, "brow", ["flat", "soft", "soft"])
    return sp


# 名前だけの同盟メンバー（personas.json の fillerMembers.look）ごとの枚数と、アバター 12 の配分（m 5 / f 5 / n 2）
GENERIC_LOOKS = {"m": 8, "f": 8, "n": 6}
AVATAR_LOOKS = ["m"] * 5 + ["f"] * 5 + ["n"] * 2
AVATAR_COUNT = len(AVATAR_LOOKS)


def generic_items():
    return [("gen_%s_%02d" % (lk, i), generic_spec("gen%s%d" % (lk, i), lk)) for lk, n in GENERIC_LOOKS.items() for i in range(n)]


def avatar_items():
    return [("avatar_%02d" % i, generic_spec("avatar%d" % i, lk)) for i, lk in enumerate(AVATAR_LOOKS)]


def all_portraits():
    out = [("pt_" + k, make(v)) for k, v in SPECS.items()]
    out += [("pt_" + k, make(v)) for k, v in generic_items() + avatar_items()]
    return out


def main():
    out = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"
    out.mkdir(parents=True, exist_ok=True)
    for name, sc in all_portraits():
        (out / (name + ".xml")).write_text(sc.to_xml(name), encoding="utf-8")
    print("wrote", len(all_portraits()), "portraits to", out)


if __name__ == "__main__":
    main()
