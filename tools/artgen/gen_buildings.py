#!/usr/bin/env python3
"""建物スプライト（18 種 x 5 段階）を VectorDrawable の XML として生成する（標準ライブラリのみ）。

実行: python3 tools/artgen/gen_buildings.py
出力: app/src/main/res/drawable/bld_<type>_t<tier>.xml（type は buildings.json の type、tier は 1..5）
段階: 1 = Lv1-5 / 2 = Lv6-10 / 3 = Lv11-15 / 4 = Lv16-20 / 5 = Lv21-25。段階が上がるほど
屋根・旗・塔・装飾が増え、材質が 木 → 漆喰と石 → 石 → 精緻な石 → 大理石と金 へ豪華になる。
出力は決定論的（乱数なし）。再実行しても同じ結果になる。
"""

import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from iso import Scene, P, shade, mix  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"

# 材質（段階ごと）
MAT = {
    1: dict(wall="#A57C4D", roof="#C2A95B", trim="#6B4E2E", base="#8C8C84", pad="#7C6B47", win="#2B2230", lit="#E8C770", gold="#C8A04A", banner="#9A3B3B"),
    2: dict(wall="#C9B48C", roof="#B8523D", trim="#7B5A3A", base="#8E8E86", pad="#8C7B57", win="#2B2230", lit="#F0CF7A", gold="#C8A04A", banner="#B23A48"),
    3: dict(wall="#A2A09A", roof="#47598A", trim="#5E5E58", base="#7C7C76", pad="#8C8D86", win="#272032", lit="#F5D783", gold="#D2AC4C", banner="#2F4F9A"),
    4: dict(wall="#B8B5AC", roof="#364C94", trim="#D8B54A", base="#8C8A84", pad="#A6A69C", win="#241C32", lit="#FFE08A", gold="#E0BA4E", banner="#2A3F8F"),
    5: dict(wall="#F1ECDD", roof="#E6BD3E", trim="#F7D768", base="#D8D2C2", pad="#E7E3D6", win="#2A2236", lit="#FFF0A8", gold="#F2CB55", banner="#6A2C9A"),
}

TYPES = [
    "castle", "farm", "sawmill", "quarry", "gold_mine", "warehouse", "barracks", "archery_range", "stable",
    "siege_workshop", "hospital", "academy", "wall", "watchtower", "trading_post", "alliance_center", "tavern",
    "builders_hut",
]


# ---- 共通部品 -----------------------------------------------------------------

def base_pad(sc, m, t, x0=0.0, y0=0.0, x1=4.0, y1=4.0):
    sc.shadow(x0 + 0.2, y0 + 0.2, x1 + 0.35, y1 + 0.35)
    sc.pad(x0, y0, x1, y1, m["pad"])
    if t >= 3:  # 敷石の目地
        for i in range(1, 4):
            a = x0 + (x1 - x0) * i / 4
            sc.line([P(a, y0, 0), P(a, y1, 0)], shade(m["pad"], 0.75), 0.4, 0.6)
            b = y0 + (y1 - y0) * i / 4
            sc.line([P(x0, b, 0), P(x1, b, 0)], shade(m["pad"], 0.75), 0.4, 0.6)
    if t >= 5:
        sc.line([P(x0, y0, 0), P(x1, y0, 0), P(x1, y1, 0), P(x0, y1, 0), P(x0, y0, 0)], m["gold"], 1.0)


def windows(sc, m, x0, y0, x1, y1, h, nl=2, nr=2, z=None, w=0.5, wh=9.0, door=True):
    z = z if z is not None else h * 0.55
    for i in range(nl):
        x = x0 + (x1 - x0) * (i + 1) / (nl + 1)
        if door and i == nl // 2 and nl % 2 == 1:
            continue
        sc.win_left(x, z, w, wh, y1, m["win"], m["lit"] if i % 2 == 0 else None, shade(m["trim"], 1.1))
    for i in range(nr):
        y = y0 + (y1 - y0) * (i + 1) / (nr + 1)
        sc.win_right(y, z, w, wh, x1, m["win"], m["lit"] if i % 2 == 1 else None, shade(m["trim"], 1.1))
    if door:
        sc.door_left((x0 + x1) / 2, 0.8, h * 0.6, y1, shade(m["trim"], 0.7))


def smoke(sc, x, y, z, n=3):
    px, py = P(x, y, z)
    for i in range(n):
        sc.ellipse(px + i * 2.0, py - 4 - i * 5, 2.4 + i * 0.8, 2.0 + i * 0.7, "#F2F2F2", None, 0, 0.55 - i * 0.12, 10)


def crenel(sc, x0, y0, x1, y1, z, c, n=4, h=3.0):
    """壁の上のきざみ（前面 2 辺）。"""
    for i in range(n):
        a = x0 + (x1 - x0) * (i + 0.2) / n
        b = x0 + (x1 - x0) * (i + 0.7) / n
        sc.box(a, y1 - 0.12, b, y1, z, z + h, c, outline=True)
    for i in range(n):
        a = y0 + (y1 - y0) * (i + 0.2) / n
        b = y0 + (y1 - y0) * (i + 0.7) / n
        sc.box(x1 - 0.12, a, x1, b, z, z + h, c, outline=True)


def hall(sc, m, t, x0, y0, x1, y1, h, roof="gable", axis="x", rh=None, wall=None, nl=2, nr=2, door=True):
    wall = wall or m["wall"]
    # 土台（石）
    sc.box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, 0, 4.0, m["base"])
    sc.box(x0, y0, x1, y1, 4.0, h, wall)
    if t >= 3:
        # 石積みの横目地
        zz = 8.0
        while zz < h - 2:
            sc.line([P(x0, y1, zz), P(x1, y1, zz)], shade(wall, 0.8), 0.35, 0.7)
            sc.line([P(x1, y0, zz), P(x1, y1, zz)], shade(wall, 0.6), 0.35, 0.7)
            zz += 5.0
    if t == 2 or t == 1:
        # 木組み（梁）
        sc.line([P(x0, y1, 4.0), P(x0, y1, h)], m["trim"], 0.9)
        sc.line([P(x1, y1, 4.0), P(x1, y1, h)], m["trim"], 0.9)
        sc.line([P(x1, y0, 4.0), P(x1, y0, h)], m["trim"], 0.9)
    windows(sc, m, x0, y0, x1, y1, h, nl, nr, door=door)
    rh = rh or (7 + 2 * t)
    if roof == "gable":
        sc.gable(x0, y0, x1, y1, h, rh, m["roof"], axis=axis)
    elif roof == "pyramid":
        sc.pyramid(x0, y0, x1, y1, h, rh, m["roof"])
    elif roof == "flat":
        sc.box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, h, h + 2.5, shade(wall, 0.9), top=shade(m["trim"], 1.2))
        crenel(sc, x0, y0, x1, y1, h + 2.5, shade(wall, 0.95), 4, 3.0)


def tower(sc, m, t, xc, yc, r, h, cone_h, flag=True, cone=None):
    sc.cylinder(xc, yc, r, 0, h, m["wall"] if t < 5 else m["wall"])
    if t >= 3:
        sc.line([P(xc - r, yc + r, h * 0.5), P(xc + r, yc - r, h * 0.5)], shade(m["wall"], 0.75), 0.3, 0.0)
    sc.win_left(xc, h * 0.6, 0.4, 8.0, yc + r * 0.7, m["win"], m["lit"] if t >= 3 else None, shade(m["trim"], 1.1))
    sc.cone(xc, yc, r, h, cone_h, cone or m["roof"], 0.18)
    if t >= 4:
        sc.ellipse(*P(xc, yc, h + cone_h + 1.0), 1.4, 1.4, m["gold"], None, 0, 1.0, 8)
    if flag:
        sc.flag(xc, yc, h + cone_h, 12 if t < 4 else 14, m["banner"])


# ---- 各建物 -------------------------------------------------------------------

def _star(sc, x, y, r, c, alpha=0.95):
    """四方向にとがった小さな星（きらめき・雪の結晶）。(x, y) は画面座標。"""
    k = r * 0.28
    sc.poly([(x, y - r), (x + k, y - k), (x + r, y), (x + k, y + k), (x, y + r), (x - k, y + k), (x - r, y),
             (x - k, y - k)], c, None, 0.0, alpha)


def _dots(sc, pts, c, rx, ry, alpha=0.9):
    for (x, y) in pts:
        sc.ellipse(x, y, rx, ry, c, None, 0, alpha, 8)


def skin_back(sc, m, t, skin):
    """スキン専用の装飾（建物より奥に置くもの）。氷: 水晶の尖塔 / 桜: 桜の木 / 竜: 竜の翼。"""
    kh = [26, 34, 44, 52, 62][t - 1]
    if skin == "ice":
        for (x, y, h) in ((3.75, 0.25, 22 + t * 4), (0.25, 0.9, 16 + t * 3)):
            sc.cone(x, y, 0.2, 0, h, "#BFE8FF", 0.0)
            sc.cone(x + 0.2, y + 0.15, 0.12, 0, h * 0.6, "#8FD0F5", 0.0)
    elif skin == "sakura":
        for (x, y) in ((0.35, 3.55), (3.65, 0.4)):
            sc.cylinder(x, y, 0.1, 0, 12 + t, "#6B4A3A")
            cx, cy = P(x, y, 18 + t * 2)
            sc.ellipse(cx, cy, 13, 9, "#F29BB5", "#B96A83", 0.4, 1.0, 14)
            sc.ellipse(cx - 6, cy - 3, 8, 6, "#F7B6CA", None, 0, 1.0, 12)
            sc.ellipse(cx + 6, cy + 1, 8, 6, "#E987A6", None, 0, 1.0, 12)
    elif skin == "dragon":
        # 主塔の左右に広がる竜の翼（暗い紅の膜と骨）
        for sgn in (-1, 1):
            a = P(2.0, 2.0, kh * 0.7)
            ax = a[0] + sgn * 8
            tip = (ax + sgn * (26 + t * 3), a[1] - 18 - t * 4)
            mid = (ax + sgn * (22 + t * 2), a[1] + 8)
            low = (ax + sgn * 10, a[1] + 14)
            sc.poly([(ax, a[1]), tip, (ax + sgn * 17, a[1] - 2), mid, (ax + sgn * 8, a[1] + 8), low], "#7A1626",
                    "#2A0E14", 0.7)
            sc.line([(ax, a[1]), tip], "#2A0E14", 1.4)
            sc.line([(ax, a[1]), mid], "#2A0E14", 1.0)


def skin_front(sc, m, t, skin):
    """スキン専用の装飾（建物より手前に置くもの）。"""
    kh = [26, 34, 44, 52, 62][t - 1]
    rh = 8 + t * 3
    if skin == "ice":
        # 軒のつらら（壁・主塔の縁から下がる）
        if t >= 2:
            edges = [(x, 3.6, 12) for x in (0.6, 1.0, 1.4, 1.8, 2.2, 2.6, 3.0, 3.4)]
            edges += [(3.6, y, 12) for y in (0.6, 1.0, 1.4, 1.8, 2.2, 2.6, 3.0)]
        else:
            edges = [(x, 2.9, kh) for x in (1.3, 1.7, 2.1, 2.5)]
        for i, (x, y, z) in enumerate(edges):
            ln = 5 + (i * 7) % 5
            sc.poly([P(x - 0.07, y, z), P(x + 0.07, y, z), P(x, y, z - ln)], "#E8F6FF", "#9CCBE6", 0.3)
        # 屋根の雪（主塔と塔の頂）
        top = P(2.0, 2.0, kh + (rh * 0.55 if t >= 2 else 5))
        sc.ellipse(top[0], top[1], 11, 4.2, "#FFFFFF", "#CFE4F2", 0.4, 0.95, 14)
        if t >= 2:
            for (x, y) in ((0.55, 0.55), (3.45, 0.55), (0.55, 3.45), (3.45, 3.45)):
                p = P(x, y, [0, 30, 38, 46, 56][t - 1] + 5)
                sc.ellipse(p[0], p[1], 6, 2.6, "#FFFFFF", None, 0, 0.95, 10)
        # 手前の水晶の尖塔と、舞う雪の結晶
        for (x, y, h) in ((3.8, 3.7, 20 + t * 3), (0.2, 3.9, 14 + t * 2)):
            sc.cone(x, y, 0.22, 0, h, "#CFF0FF", 0.0)
            sc.cone(x - 0.22, y + 0.1, 0.12, 0, h * 0.55, "#9AD6F7", 0.0)
        for (x, y, r) in ((14, 36, 3.2), (112, 52, 2.6), (22, 84, 2.2), (104, 18, 2.4)):
            _star(sc, x, y, r, "#FFFFFF", 0.9)
    elif skin == "gold":
        # 金の帯と宝石、塔の金の旗
        sc.box(1.08, 1.08, 2.92, 2.92, kh - 4, kh - 1, m["gold"], outline=False)
        for (x, y, z, c) in ((1.9, 2.92, kh * 0.55, "#7FE0F5"), (1.4, 2.92, kh * 0.55, "#F58FB0"),
                             (2.5, 2.92, kh * 0.55, "#9BE79B")):
            p = P(x, y, z)
            sc.poly([(p[0], p[1] - 3), (p[0] + 2.4, p[1]), (p[0], p[1] + 3), (p[0] - 2.4, p[1])], c, "#7A5A10", 0.3)
        if t >= 2:
            for (x, y) in ((0.55, 0.55), (3.45, 0.55), (0.55, 3.45), (3.45, 3.45)):
                sc.flag(x, y, [0, 30, 38, 46, 56][t - 1] + 10 + t * 2, 10, m["gold"], 6.0, "#8A6A1A")
        # 手前の金貨の山ときらめき
        for (x, y) in ((0.7, 3.8), (3.4, 3.8)):
            p = P(x, y, 0)
            sc.ellipse(p[0], p[1], 6, 2.8, "#E8B93A", "#8A6A1A", 0.4, 1.0, 10)
            sc.ellipse(p[0], p[1] - 2, 4.2, 2, "#FFE27A", None, 0, 1.0, 10)
        for (x, y, r) in ((18, 40, 4.2), (110, 46, 3.4), (28, 92, 2.8), (100, 84, 3.0), (64, 24, 3.4)):
            _star(sc, x, y, r, "#FFF3B0", 0.95)
    elif skin == "sakura":
        # 門の提灯と、そり上がった軒先
        for x in (1.55, 2.45):
            p = P(x, 3.75, 15)
            sc.line([(p[0], p[1] - 5), (p[0], p[1])], "#5A3A2A", 0.8)
            sc.box(x - 0.1, 3.7, x + 0.1, 3.9, 8, 14, "#E0405A", outline=True)
            sc.box(x - 0.12, 3.68, x + 0.12, 3.92, 14, 15, "#5A3A2A", outline=False)
        for (x, y) in ((1.1 - 0.25, 2.9 + 0.25), (2.9 + 0.25, 2.9 + 0.25), (2.9 + 0.25, 1.1 - 0.25)):
            p = P(x, y, kh)
            sc.poly([p, (p[0] + (4 if x > 2 else -4), p[1] - 6), (p[0], p[1] - 2)], m["roof"], shade(m["roof"], 0.5), 0.4)
        # 舞う花びら
        _dots(sc, [(20, 52), (36, 96), (96, 40), (108, 88), (76, 30), (50, 70), (88, 104)], "#FFC4D6", 2.4, 1.5)
        _dots(sc, [(26, 66), (100, 66), (60, 38)], "#F58FB0", 2.0, 1.3)
    elif skin == "dragon":
        # 屋根の竜の像（首をもたげた影）と、立ちのぼる火の粉
        ax, ay = P(2.0, 2.0, kh + (rh if t >= 2 else 9))
        sc.poly([(ax - 6, ay), (ax - 4, ay - 6), (ax - 5, ay - 12), (ax - 1, ay - 17), (ax + 3, ay - 16),
                 (ax + 7, ay - 18), (ax + 3, ay - 13), (ax + 1, ay - 9), (ax + 5, ay - 4), (ax + 7, ay)],
                "#1E1218", "#C0283A", 0.5)
        sc.poly([(ax - 2, ay - 8), (ax - 12, ay - 16), (ax - 8, ay - 6)], "#7A1626", "#2A0E14", 0.4)
        sc.poly([(ax + 1, ay - 8), (ax + 11, ay - 17), (ax + 7, ay - 6)], "#7A1626", "#2A0E14", 0.4)
        sc.poly([(ax + 3, ay - 16), (ax + 4, ay - 21), (ax + 5, ay - 16)], "#E8A33C", None, 0.0)
        sc.ellipse(ax + 4.5, ay - 15, 0.9, 0.9, "#FF5A3C", None, 0, 1.0, 6)
        if t >= 2:
            for (x, y) in ((0.55, 0.55), (3.45, 0.55), (0.55, 3.45), (3.45, 3.45)):
                p = P(x, y, [0, 30, 38, 46, 56][t - 1] + 4)
                sc.poly([(p[0] - 2.2, p[1]), (p[0], p[1] - 9), (p[0] + 2.2, p[1])], "#C0283A", "#2A0E14", 0.4)
        for (x, y, r, c) in ((18, 70, 1.7, "#FF8A2A"), (30, 40, 1.3, "#FFB84A"), (100, 56, 1.8, "#FF8A2A"),
                             (110, 90, 1.3, "#FFB84A"), (82, 26, 1.5, "#FF5A3C"), (46, 102, 1.4, "#FF8A2A"),
                             (92, 110, 1.2, "#FFB84A")):
            sc.ellipse(x, y, r, r, c, None, 0, 0.95, 6)


def castle(sc, m, t):
    base_pad(sc, m, t)
    skin = m.get("skin")
    if skin:
        skin_back(sc, m, t, skin)
    towers = [0, 2, 4, 4, 4][t - 1]
    th = [0, 30, 38, 46, 56][t - 1]
    kh = [26, 34, 44, 52, 62][t - 1]
    # 城壁（四辺の低い壁）
    if t >= 2:
        sc.box(0.4, 0.4, 3.6, 0.8, 0, 12, m["base"])
        sc.box(0.4, 0.4, 0.8, 3.6, 0, 12, m["base"])
    if towers >= 2:
        tower(sc, m, t, 0.55, 0.55, 0.5, th, 10 + t * 2)
    if towers >= 4:
        tower(sc, m, t, 3.45, 0.55, 0.5, th, 10 + t * 2)
        tower(sc, m, t, 0.55, 3.45, 0.5, th, 10 + t * 2)
    # 主塔
    hall(sc, m, t, 1.1, 1.1, 2.9, 2.9, kh, roof="pyramid" if t >= 2 else "gable", rh=8 + t * 3, nl=3, nr=3)
    if t >= 3:
        # 中央の尖塔
        sc.cylinder(2.0, 2.0, 0.42, kh, kh + 8 + t * 2, shade(m["wall"], 1.05))
        sc.cone(2.0, 2.0, 0.42, kh + 8 + t * 2, 10 + t * 2, m["roof"], 0.15)
        sc.flag(2.0, 2.0, kh + 18 + t * 4, 14, m["banner"])
    elif t >= 2:
        sc.flag(2.0, 2.0, kh + 8 + t * 3, 12, m["banner"])
    # 手前の壁と門
    if t >= 2:
        sc.box(0.4, 3.2, 3.6, 3.6, 0, 12, m["base"])
        sc.box(3.2, 0.4, 3.6, 3.6, 0, 12, m["base"])
        sc.door_left(2.0, 1.1, 12, 3.6, shade(m["trim"], 0.6))
        crenel(sc, 0.4, 0.4, 3.6, 3.6, 12, m["base"], 6, 3.0)
    if towers >= 4:
        tower(sc, m, t, 3.45, 3.45, 0.55, th, 10 + t * 2)
    elif towers >= 2:
        tower(sc, m, t, 3.45, 3.45, 0.5, th - 4, 10 + t * 2)
    if t == 1:
        sc.flag(2.0, 2.0, kh + 8, 11, m["banner"])
    if skin:
        skin_front(sc, m, t, skin)


def farm(sc, m, t):
    base_pad(sc, m, t)
    # 畑の畝
    soil = "#7A5A36" if t < 5 else "#8A6A3C"
    crop = ["#C9B24A", "#B5C54B", "#8DC050", "#79C25A", "#F0CF52"][t - 1]
    sc.pad(0.2, 2.1, 3.8, 3.85, soil)
    for i in range(7):
        y = 2.2 + i * 0.23
        sc.line([P(0.3, y, 0), P(3.7, y, 0)], crop, 1.3)
    sc.pad(2.2, 0.2, 3.85, 1.9, shade(soil, 1.1))
    for i in range(5):
        x = 2.3 + i * 0.3
        sc.line([P(x, 0.3, 0), P(x, 1.8, 0)], shade(crop, 0.9), 1.2)
    # 納屋
    hall(sc, m, t, 0.4, 0.4, 2.0, 1.9, 12 + t * 2, roof="gable", axis="x", rh=8 + t, nl=2, nr=1, door=False)
    if t >= 3:  # 風車
        sc.cylinder(3.3, 0.9, 0.4, 0, 18 + t * 2, shade(m["wall"], 1.05))
        sc.cone(3.3, 0.9, 0.4, 18 + t * 2, 6, m["roof"], 0.12)
        hub = P(3.3, 0.9, 20 + t * 2)
        for k in range(4):
            ang = math.radians(45 + 90 * k)
            end = (hub[0] + 15 * math.cos(ang), hub[1] + 15 * math.sin(ang))
            sc.line([hub, end], "#E9E4D4", 2.0)
            sc.line([hub, end], "#8C7B5A", 0.6)
    # 干し草
    sc.ellipse(*P(2.7, 1.6, 4), 5.5, 4.5, "#E0C060", shade("#E0C060", 0.5), 0.5, 1.0, 12)
    if t == 5:
        sc.ellipse(*P(2.7, 1.6, 4), 5.5, 4.5, m["gold"], None, 0, 0.35, 12)


def sawmill(sc, m, t):
    base_pad(sc, m, t)
    # 丸太の山
    for k in range(3 + t // 2):
        sc.ellipse(*P(0.6 + 0.0 * k, 2.6 + k * 0.35, 2 + (k % 2) * 3), 4, 2.4, "#8A5A32", "#4A2E18", 0.5, 1.0, 10)
    hall(sc, m, t, 1.6, 0.4, 3.6, 2.2, 14 + t * 2, roof="gable", axis="y", rh=8 + t, nl=1, nr=2, door=False)
    # 大きな丸のこ
    cx, cy = P(1.6, 3.1, 14)
    sc.ellipse(cx, cy, 8, 8, "#BFC3C8", "#4A4F57", 0.6, 1.0, 18)
    sc.ellipse(cx, cy, 2, 2, "#4A4F57", None, 0, 1.0, 8)
    for k in range(8):
        ang = math.radians(k * 45)
        sc.line([(cx, cy), (cx + 8 * math.cos(ang), cy + 8 * math.sin(ang))], "#7A7F88", 0.5)
    if t >= 3:  # 水車
        wx, wy = P(3.85, 1.4, 12)
        sc.ellipse(wx, wy, 9, 9, "#7A5A36", "#3A2A18", 0.8, 1.0, 14)
        for k in range(6):
            ang = math.radians(k * 60)
            sc.line([(wx, wy), (wx + 9 * math.cos(ang), wy + 9 * math.sin(ang))], "#3A2A18", 1.0)
    if t >= 4:
        sc.flag(3.2, 0.8, 26 + t * 2, 10, m["banner"])


def quarry(sc, m, t):
    base_pad(sc, m, t)
    rock = ["#8C8A82", "#94928A", "#A3A09A", "#B0ADA6", "#D5D0C4"][t - 1]
    # 掘り込み（階段状の穴）
    sc.pad(0.3, 0.3, 3.7, 3.7, shade(rock, 0.65))
    sc.pad(0.7, 0.7, 3.3, 3.3, shade(rock, 0.5))
    # 岩の壁（奥）
    sc.box(0.2, 0.2, 3.8, 0.9, 0, 16 + t * 2, rock)
    sc.box(0.2, 0.2, 0.9, 3.8, 0, 12 + t * 2, rock, lf=0.95)
    # 積まれた石材
    for i in range(2 + t // 2):
        sc.box(2.4 + i * 0.5, 2.6, 2.8 + i * 0.5, 3.1, 0, 5, shade(rock, 1.1))
    sc.box(1.6, 3.0, 2.1, 3.5, 0, 5 + t, shade(rock, 1.2))
    if t >= 3:  # クレーン
        sc.line([P(2.8, 1.2, 0), P(2.8, 1.2, 30 + t * 2)], "#5A4630", 2.0)
        sc.line([P(2.8, 1.2, 30 + t * 2), P(1.4, 1.9, 24 + t * 2)], "#5A4630", 1.6)
        sc.line([P(1.4, 1.9, 24 + t * 2), P(1.4, 1.9, 14)], "#2B2B2B", 0.6)
        sc.box(1.25, 1.75, 1.55, 2.05, 8, 14, shade(rock, 1.2))
    if t >= 4:
        sc.flag(3.4, 0.5, 18 + t * 2, 11, m["banner"])
    if t == 5:
        sc.ellipse(*P(3.0, 3.0, 8), 3.5, 2.5, m["gold"], shade(m["gold"], 0.6), 0.5, 1.0, 10)


def gold_mine(sc, m, t):
    base_pad(sc, m, t)
    rock = ["#7C746A", "#837A70", "#8C8278", "#968C80", "#B5AA98"][t - 1]
    sc.box(0.2, 0.2, 3.8, 1.4, 0, 22 + t * 3, rock)
    sc.box(0.2, 0.2, 1.4, 3.8, 0, 16 + t * 2, rock, lf=0.95)
    # 坑道の入り口
    ex = 2.3
    gate_col = [m["trim"], m["trim"], "#5A5A58", m["trim"], m["gold"]][t - 1]
    sc.box(ex - 0.9, 1.4, ex + 0.9, 1.7, 0, 18, gate_col)
    sc.poly([P(ex - 0.6, 1.7, 0), P(ex + 0.6, 1.7, 0), P(ex + 0.6, 1.7, 13), P(ex, 1.7, 17), P(ex - 0.6, 1.7, 13)], "#14100E", shade(gate_col, 0.5), 0.4)
    # 金のきらめき
    for k, (x, y, z) in enumerate([(1.0, 1.9, 6), (3.2, 1.0, 12), (0.9, 2.8, 5), (3.5, 1.9, 8)][: 1 + t]):
        px, py = P(x, y, z)
        sc.poly([(px, py - 3), (px + 1.5, py - 1), (px + 3, py), (px + 1.5, py + 1), (px, py + 3), (px - 1.5, py + 1), (px - 3, py), (px - 1.5, py - 1)], m["gold"], "#8A6A1E", 0.4)
    # トロッコと金の山
    sc.line([P(2.0, 1.9, 0), P(2.3, 3.8, 0)], "#3A3A3A", 0.8)
    sc.line([P(2.6, 1.9, 0), P(2.9, 3.8, 0)], "#3A3A3A", 0.8)
    sc.box(2.0, 2.8, 2.9, 3.4, 2, 8, "#5A4A3A")
    sc.ellipse(*P(2.45, 3.1, 9), 5, 3, m["gold"], "#8A6A1E", 0.5, 1.0, 10)
    if t >= 3:
        sc.flag(3.4, 0.9, 22 + t * 3, 11, m["banner"])
    if t >= 5:
        sc.ellipse(*P(2.3, 1.9, 24), 14, 8, m["gold"], None, 0, 0.18, 14)


def warehouse(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.5, 0.5, 3.5, 2.7, 14 + t * 2, roof="gable", axis="x", rh=8 + t, nl=3, nr=2, door=True)
    # 木箱と樽
    for k, (x, y) in enumerate([(0.5, 3.0), (1.1, 3.3), (3.4, 3.1)][: 1 + (t + 1) // 2]):
        sc.box(x, y, x + 0.5, y + 0.5, 0, 7, "#9C7A4A")
    sc.cylinder(2.7, 3.3, 0.25, 0, 7, "#7A5A3A")
    if t >= 4:
        sc.box(1.5, 3.0, 2.1, 3.5, 0, 6, m["gold"])
    if t >= 3:
        sc.flag(3.2, 0.8, 14 + t * 2 + 8 + t, 10, m["banner"])


def barracks(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.6, 0.6, 3.4, 2.6, 16 + t * 3, roof="gable" if t < 3 else "flat", axis="x", rh=8 + t, nl=3, nr=2)
    # 旗と武器架
    sc.flag(3.4, 0.6, 16 + t * 3 + (8 + t if t < 3 else 4), 14, m["banner"])
    sc.flag(0.6, 2.6, 16 + t * 3 + (8 + t if t < 3 else 4), 12, shade(m["banner"], 1.15))
    for k in range(2 + t // 2):
        a = P(0.5 + k * 0.45, 3.2, 0)
        b = P(0.5 + k * 0.45, 3.2, 14)
        sc.line([a, b], "#6A6A72", 1.0)
        sc.poly([b, (b[0] - 1.5, b[1] + 3), (b[0] + 1.5, b[1] + 3)], "#C8CDD4", None)
    if t >= 4:
        sc.box(3.5, 2.8, 3.9, 3.2, 0, 7 + t, m["gold"])


def archery_range(sc, m, t):
    base_pad(sc, m, t)
    # 射場の草と的
    sc.pad(0.3, 2.0, 3.7, 3.8, "#6F9B4A")
    for k in range(2 + t // 3):
        x = 0.9 + k * 1.1
        tx, ty = P(x, 3.6, 12)
        sc.line([P(x, 3.6, 0), P(x, 3.6, 12)], "#6B4E2E", 1.4)
        sc.ellipse(tx, ty, 6, 6, "#F2EBD8", "#5B4630", 0.6, 1.0, 14)
        sc.ellipse(tx, ty, 4, 4, "#C13A3A", None, 0, 1.0, 12)
        sc.ellipse(tx, ty, 1.8, 1.8, "#F2EBD8", None, 0, 1.0, 8)
    # 東屋
    for (x, y) in [(0.6, 0.6), (3.0, 0.6), (0.6, 1.9), (3.0, 1.9)]:
        sc.box(x, y, x + 0.35, y + 0.35, 0, 18 + t, m["trim"])
    sc.pyramid(0.45, 0.45, 3.55, 2.4, 18 + t, 9 + t, m["roof"], over=0.3)
    sc.flag(2.0, 1.4, 27 + 2 * t, 11, m["banner"])
    sc.box(0.8, 1.0, 3.2, 1.5, 0, 7, "#8C6A3C")  # 作業台
    for k in range(3):
        sc.line([P(1.0 + k * 0.7, 1.25, 7), P(1.0 + k * 0.7, 1.25, 17)], "#C8CDD4", 0.8)


def stable(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.5, 0.4, 3.2, 2.2, 13 + t * 2, roof="gable", axis="y", rh=7 + t, nl=2, nr=3, door=False)
    # 柵と馬
    sc.line([P(0.3, 3.2, 0), P(0.3, 3.2, 8), P(3.6, 3.2, 8), P(3.6, 3.2, 0)], "#7A5A36", 1.2)
    sc.line([P(0.3, 3.2, 4), P(3.6, 3.2, 4)], "#7A5A36", 1.2)
    hx, hy = P(2.0, 3.0, 8)
    sc.poly([(hx - 6, hy + 3), (hx - 5, hy - 3), (hx + 3, hy - 4), (hx + 5, hy - 8), (hx + 8, hy - 7), (hx + 6, hy - 2), (hx + 4, hy + 4), (hx + 2, hy + 8), (hx + 1, hy + 4), (hx - 4, hy + 4), (hx - 5, hy + 8)], "#7A4B27" if t < 4 else "#F2EEE4", "#3A2412", 0.5)
    if t >= 3:
        sc.flag(3.2, 0.4, 13 + t * 2 + 7 + t, 11, m["banner"])
    sc.box(3.4, 1.0, 3.9, 1.6, 0, 6, "#D4B060")  # 干し草


def siege_workshop(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.4, 0.4, 2.4, 2.4, 15 + t * 2, roof="gable", axis="x", rh=8 + t, nl=2, nr=2)
    # 投石機
    bx, by = P(3.0, 3.0, 4)
    sc.box(2.5, 2.6, 3.7, 3.7, 0, 4, "#7A5A36")
    sc.line([P(3.1, 3.1, 4), P(2.8, 2.7, 22)], "#5A4630", 2.0)
    sc.line([P(3.1, 3.1, 4), P(3.7, 3.7, 16)], "#5A4630", 2.0)
    ax, ay = P(2.8, 2.7, 22)
    sc.ellipse(ax, ay, 3, 3, "#8C8A82", "#3A3A3A", 0.5, 1.0, 8)
    sc.ellipse(*P(3.0, 3.4, 5), 2.2, 2.2, "#8C8A82", "#3A3A3A", 0.5, 1.0, 8)
    sc.flag(0.5, 0.5, 15 + t * 2 + 8 + t, 11, m["banner"])
    if t >= 4:
        sc.box(0.6, 3.0, 1.2, 3.6, 0, 8, m["gold"])


def hospital(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.5, 0.5, 3.5, 3.0, 16 + t * 3, roof="gable" if t < 4 else "flat", axis="x", rh=8 + t, nl=3, nr=2)
    # 緑の十字（薬草の印）
    cx, cy = P(2.0, 3.0, 16 + t * 3 - 2)
    green = "#3F9B5A"
    sc.poly([(cx - 2, cy - 7), (cx + 2, cy - 7), (cx + 2, cy - 2), (cx + 7, cy - 2), (cx + 7, cy + 2), (cx + 2, cy + 2), (cx + 2, cy + 7), (cx - 2, cy + 7), (cx - 2, cy + 2), (cx - 7, cy + 2), (cx - 7, cy - 2), (cx - 2, cy - 2)], green, "#1F5A32", 0.6)
    # 薬草園
    for k in range(3):
        sc.ellipse(*P(0.7 + k * 0.55, 3.45, 2), 3, 2.2, "#4FAE5C", "#2A6A38", 0.4, 1.0, 8)
    if t >= 3:
        sc.flag(3.4, 0.6, 16 + t * 3 + 6 + t, 11, "#3F9B5A")
    if t >= 5:
        sc.ellipse(*P(2.0, 3.0, 28), 14, 8, "#FFFFFF", None, 0, 0.18, 14)


def academy(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.6, 0.6, 3.4, 3.0, 18 + t * 3, roof="flat", nl=3, nr=2)
    # 柱
    for k in range(4):
        sc.box(0.8 + k * 0.7, 3.0, 1.05 + k * 0.7, 3.3, 0, 18 + t * 3 - 2, shade(m["wall"], 1.2))
    sc.box(0.6, 3.0, 3.4, 3.35, 18 + t * 3 - 3, 18 + t * 3 - 1, m["trim"])
    # ドーム
    sc.cylinder(2.0, 1.8, 0.9, 18 + t * 3 + 2.5, 22 + t * 3 + 2.5, shade(m["wall"], 1.05))
    sc.dome(2.0, 1.8, 0.9, 22 + t * 3 + 2.5, 8 + t * 2, m["roof"] if t < 5 else m["gold"])
    if t >= 4:
        sc.line([P(2.0, 1.8, 30 + t * 3 + 2 * t), P(2.0, 1.8, 36 + t * 3 + 2 * t)], m["gold"], 1.2)
        sc.ellipse(*P(2.0, 1.8, 37 + t * 3 + 2 * t), 1.4, 1.4, m["gold"], None, 0, 1.0, 8)
    if t >= 3:
        # 本の看板
        bx, by = P(0.5, 2.0, 12)
        sc.poly([(bx - 3, by - 3), (bx, by - 2), (bx + 3, by - 3), (bx + 3, by + 3), (bx, by + 4), (bx - 3, by + 3)], "#F1E6C8", "#6B4E2E", 0.5)


def wall_building(sc, m, t):
    base_pad(sc, m, t)
    wh = 14 + t * 3
    # 長い城壁（奥と左）と門
    sc.box(0.0, 0.0, 4.0, 0.9, 0, wh, m["base"] if t < 3 else m["wall"])
    crenel(sc, 0.0, 0.0, 4.0, 0.9, wh, m["wall"], 6, 3.5)
    sc.box(0.0, 0.0, 0.9, 4.0, 0, wh, m["base"] if t < 3 else m["wall"])
    crenel(sc, 0.0, 0.0, 0.9, 4.0, wh, m["wall"], 6, 3.5)
    # 門楼
    sc.box(1.4, 3.0, 2.8, 3.9, 0, wh + 8, m["wall"])
    sc.door_left(2.1, 1.0, 16, 3.9, "#2E2218")
    sc.gable(1.4, 3.0, 2.8, 3.9, wh + 8, 6 + t, m["roof"], axis="x", over=0.15)
    crenel(sc, 3.0, 3.0, 4.0, 3.9, 0, m["wall"], 3, 0.1)
    for (x, y) in [(0.45, 0.45), (3.55, 0.45), (0.45, 3.55)]:
        sc.cylinder(x, y, 0.55, 0, wh + 8, m["wall"])
        sc.cone(x, y, 0.55, wh + 8, 8 + t, m["roof"], 0.15)
    if t >= 2:
        sc.flag(2.1, 3.45, wh + 14 + t, 12, m["banner"])
    if t >= 5:
        sc.line([P(0, 0, wh + 1), P(4, 0, wh + 1)], m["gold"], 1.2)


def watchtower(sc, m, t):
    base_pad(sc, m, t)
    h = [34, 44, 54, 64, 76][t - 1]
    sc.box(1.1, 1.1, 2.9, 2.9, 0, 6, m["base"])
    sc.cylinder(2.0, 2.0, 0.75, 6, h, m["wall"])
    for k in range(3):
        z = 6 + (h - 6) * (k + 1) / 4
        sc.win_left(2.0, z, 0.35, 6.0, 2.0 + 0.75 * 0.9, m["win"], m["lit"] if t >= 3 else None, shade(m["trim"], 1.1))
    # 見張り台
    sc.cylinder(2.0, 2.0, 1.15, h, h + 5, shade(m["wall"], 1.05), top=shade(m["trim"], 1.2))
    for k in range(8):
        ang = math.radians(k * 45 + 22)
        x, y = 2.0 + 1.1 * math.cos(ang), 2.0 + 1.1 * math.sin(ang)
        if x + y > 3.6:
            sc.box(x - 0.14, y - 0.14, x + 0.14, y + 0.14, h + 5, h + 8.5, m["wall"])
    sc.cone(2.0, 2.0, 0.65, h + 5, 10 + t * 2, m["roof"], 0.12)
    sc.flag(2.0, 2.0, h + 15 + t * 2, 13, m["banner"])
    # 火の見（上のあかり）
    if t >= 2:
        sc.ellipse(*P(2.0, 2.0, h + 3), 4, 3, "#FFCC66", None, 0, 0.6 if t >= 4 else 0.35, 10)


def trading_post(sc, m, t):
    base_pad(sc, m, t)
    # 屋台（縞模様の日よけ）
    stripes = [("#C7473F", "#F2EBD8"), ("#3F6FC7", "#F2EBD8"), ("#3FA060", "#F2EBD8"), ("#8B3FC7", "#F2EBD8"), ("#E0B93C", "#F7F2E2")][t - 1]
    stalls = [(0.4, 0.4), (2.2, 0.4), (0.4, 2.2)][: 1 + (t + 1) // 2] + [(2.2, 2.2)] * (1 if t >= 4 else 0)
    for (x, y) in stalls:
        sc.box(x, y, x + 1.4, y + 1.2, 0, 6, "#8C6A3C")  # 台
        for k in range(4):
            c = stripes[k % 2]
            xa = x - 0.1 + k * 0.4
            sc.poly([P(xa, y - 0.1, 17), P(xa + 0.4, y - 0.1, 17), P(xa + 0.4, y + 1.3, 17), P(xa, y + 1.3, 17)], c, shade(c, 0.6), 0.3)
        for (px, py) in [(x, y), (x + 1.3, y), (x, y + 1.1), (x + 1.3, y + 1.1)]:
            sc.line([P(px, py, 0), P(px, py, 17)], "#5A4630", 1.0)
        sc.ellipse(*P(x + 0.5, y + 0.5, 7), 2.4, 1.6, "#E0B050", shade("#E0B050", 0.5), 0.3, 1.0, 8)
        sc.ellipse(*P(x + 0.95, y + 0.9, 7), 2.0, 1.4, "#C14E3F", shade("#C14E3F", 0.5), 0.3, 1.0, 8)
    sc.box(2.4, 1.7, 2.9, 2.2, 0, 6, "#9C7A4A")
    if t >= 3:
        sc.flag(2.8, 0.8, 22 + t, 11, m["banner"])
    if t >= 5:
        sc.ellipse(*P(1.6, 1.4, 20), 16, 8, m["gold"], None, 0, 0.16, 14)
    # 硬貨の看板
    cx, cy = P(1.0, 3.7, 14)
    sc.ellipse(cx, cy, 4.5, 4.5, m["gold"], "#8A6A1E", 0.8, 1.0, 12)
    sc.ellipse(cx, cy, 2.6, 2.6, shade(m["gold"], 1.15), None, 0, 1.0, 10)


def alliance_center(sc, m, t):
    base_pad(sc, m, t)
    h = 20 + t * 3
    sc.cylinder(2.0, 2.0, 1.55, 0, 5, m["base"])
    sc.cylinder(2.0, 2.0, 1.35, 5, h, m["wall"])
    for k in range(5):
        a = math.radians(30 + k * 36)
        x, y = 2.0 + 1.37 * math.cos(a), 2.0 + 1.37 * math.sin(a)
        sc.win_left(x, h * 0.55, 0.3, 8.0, y, m["win"], m["lit"] if k % 2 == 0 else None, shade(m["trim"], 1.1)) if y > 2.3 else None
    sc.dome(2.0, 2.0, 1.45, h, 11 + t * 2, m["roof"] if t < 5 else m["gold"])
    sc.door_left(2.0, 0.9, 14, 3.35, shade(m["trim"], 0.6))
    # 垂れ幕と大旗
    sc.banner(3.35, 2.0, h - 2, 12, m["banner"], m["gold"])
    sc.line([P(2.0, 2.0, h + 11 + t * 2), P(2.0, 2.0, h + 20 + t * 3)], "#4A3A2A", 1.2)
    px, py = P(2.0, 2.0, h + 20 + t * 3)
    sc.poly([(px, py), (px + 11, py + 3), (px + 9, py + 8), (px + 11, py + 12), (px, py + 10)], m["banner"], shade(m["banner"], 0.5), 0.5)
    sc.ellipse(px + 5, py + 6, 2.2, 2.2, m["gold"], None, 0, 1.0, 8)
    if t >= 3:
        for (x, y) in [(0.5, 1.0), (1.0, 3.3), (3.5, 0.6)]:
            sc.flag(x, y, 0, 18 + t, shade(m["banner"], 1.1))


def tavern(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.5, 0.5, 3.5, 2.8, 16 + t * 3, roof="gable", axis="y", rh=9 + t, nl=3, nr=3)
    # 煙突と煙
    sc.box(2.8, 0.7, 3.2, 1.1, 14 + t, 28 + t * 3, "#8C8A82")
    smoke(sc, 3.0, 0.9, 30 + t * 3)
    # ジョッキの看板
    sx, sy = P(0.5, 3.0, 22 + t * 2)
    sc.line([P(0.5, 2.8, 24 + t * 2), P(0.5, 3.25, 24 + t * 2)], "#3A2A1E", 1.4)
    sc.box(0.4, 3.2, 0.9, 3.7, 12 + t * 2, 20 + t * 2, "#D8A640")
    sc.box(0.45, 3.25, 0.85, 3.65, 18 + t * 2, 21 + t * 2, "#F5F1E6")
    if t >= 4:
        sc.flag(3.4, 0.6, 28 + t * 3, 11, m["banner"])
    for k in range(2):
        sc.cylinder(2.3 + k * 0.5, 3.3, 0.2, 0, 6, "#7A5A3A")


def builders_hut(sc, m, t):
    base_pad(sc, m, t)
    hall(sc, m, t, 0.7, 0.7, 2.6, 2.4, 12 + t * 2, roof="gable", axis="x", rh=6 + t, nl=1, nr=1)
    # 足場
    sc.line([P(2.9, 0.5, 0), P(2.9, 0.5, 28)], "#8C6A3C", 1.4)
    sc.line([P(2.9, 2.5, 0), P(2.9, 2.5, 28)], "#8C6A3C", 1.4)
    sc.line([P(2.9, 0.5, 14), P(2.9, 2.5, 14)], "#8C6A3C", 1.2)
    sc.line([P(2.9, 0.5, 28), P(2.9, 2.5, 28)], "#8C6A3C", 1.2)
    sc.line([P(2.9, 0.5, 0), P(2.9, 2.5, 14)], "#6B4E2E", 0.8)
    # 工具（槌と鋸）
    hx, hy = P(1.0, 3.3, 8)
    sc.line([(hx, hy), (hx + 5, hy - 9)], "#6B4E2E", 1.4)
    sc.poly([(hx + 2, hy - 12), (hx + 9, hy - 8), (hx + 7, hy - 5), (hx + 1, hy - 8)], "#8C8A82", "#3A3A3A", 0.5)
    sc.box(1.8, 3.1, 2.3, 3.6, 0, 5, "#9C7A4A")
    sc.box(2.0, 3.3, 3.4, 3.6, 0, 3, "#B58B5A")
    if t >= 4:
        sc.flag(2.6, 0.7, 12 + t * 2 + 6 + t, 10, m["banner"])


BUILDERS = {
    "castle": castle, "farm": farm, "sawmill": sawmill, "quarry": quarry, "gold_mine": gold_mine,
    "warehouse": warehouse, "barracks": barracks, "archery_range": archery_range, "stable": stable,
    "siege_workshop": siege_workshop, "hospital": hospital, "academy": academy, "wall": wall_building,
    "watchtower": watchtower, "trading_post": trading_post, "alliance_center": alliance_center,
    "tavern": tavern, "builders_hut": builders_hut,
}


# M11 T11.4: 城のスキン（配色の差し替え。形は同じ）。キーは MAT と同じ。
SKIN_PALETTES = {
    "ice": {"wall": "#DCEAF5", "roof": "#5F9BD0", "trim": "#BFD6EA", "banner": "#5AA8E8", "gold": "#CFE8F8",
            "pad": "#B9CCDC", "base": "#9FB4C8"},
    "gold": {"wall": "#E6D3A0", "roof": "#C9A227", "trim": "#B08A1E", "banner": "#B8222B", "gold": "#FFD84A",
             "pad": "#D8C48A", "base": "#B79E5E"},
    "dragon": {"wall": "#2E2630", "roof": "#9A1B2E", "trim": "#C0283A", "banner": "#C0283A", "gold": "#E8A33C",
               "pad": "#3A3238", "base": "#4A4048"},
    "sakura": {"wall": "#F3DDE3", "roof": "#E58FA8", "trim": "#C97A92", "banner": "#F06292", "gold": "#FFC1D3",
               "pad": "#E8CFD6", "base": "#C9A9B3"},
}


def build(name, tier, skin=None):
    sc = Scene()
    m = dict(MAT[tier])
    if skin:
        m.update({k: v for k, v in SKIN_PALETTES[skin].items() if k in m})
        m["skin"] = skin
    BUILDERS[name](sc, m, tier)
    return sc


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    n = 0
    for name in TYPES:
        for t in range(1, 6):
            sc = build(name, t)
            f = OUT / ("bld_%s_t%d.xml" % (name, t))
            f.write_text(sc.to_xml("%s tier %d" % (name, t)), encoding="utf-8")
            n += 1
    for skin in SKIN_PALETTES:
        for t in range(1, 6):
            f = OUT / ("bld_castle_%s_t%d.xml" % (skin, t))
            f.write_text(build("castle", t, skin).to_xml("castle %s tier %d" % (skin, t)), encoding="utf-8")
            n += 1
    print("wrote %d drawables to %s" % (n, OUT))


if __name__ == "__main__":
    main()
