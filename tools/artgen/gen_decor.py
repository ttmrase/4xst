#!/usr/bin/env python3
"""領地マップの装飾スプライト（木・岩・小さな家・城壁・街灯・建設現場など）を VectorDrawable として生成する。

実行: python3 tools/artgen/gen_decor.py
出力: app/src/main/res/drawable/deco_*.xml と app/src/main/kotlin/.../ui/territory/SpriteIds.kt（建物 + 装飾の ID 表）
装飾は 64 x 96 の viewport（足元の中心が (32, 80)）。城壁の部品だけ 64 x 64（足元の中心が (32, 48)）。
絵柄は建物（gen_buildings.py）と同じ: グラデーションの面、太い外形の縁取り、足元の影と右下への落ち影。
城壁の部品（wall_x / wall_y）は横に並べてつなぐので、つなぎ目に縁取りを出さない（上下の縁だけ線を引く）。
"""

import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import iso  # noqa: E402
from iso import Scene, P, U, shade, mix, lit, shd, edge, vgrad, grad_perp, Linear, Radial, with_alpha  # noqa: E402
import gen_buildings  # noqa: E402
from gen_buildings import INK, GLOW, WOOD, STRAW, Lf, Rf, ring, boxes  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"
KOTLIN = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/kotlin/com/ttmrase/crownfall/ui/territory/SpriteIds.kt"


def around(sc_fn, w=64, h=96, ax=32.0, ay=80.0, outline=True):
    """足元の中心を (ax, ay) にして Scene を作る。"""
    # P(0, 0, 0) が (ax, ay) に来るようにする（S * V だけ下げて置く）
    iso.set_anchor(ax, ay + iso.S * iso.V)
    sc = Scene(w, h, outline=INK if outline else None, ow=1.1, cast=outline, compact=True)
    sc.cast_center = (ax, ay)
    sc.cast_fade = (w * 0.2, w / 2.0 - 1.0)
    sc_fn(sc)
    iso.set_anchor(64, 120)
    return sc


def Q(x, y, z=0.0):
    """原点（スプライトの足元の中心）基準の座標。"""
    return P(x, y, z)


def ground_shadow(sc, rx, ry, alpha=0.4, dx=1.0, dy=0.5):
    """足元のやわらかい影（接地）。"""
    x, y = Q(0, 0, 0)
    sc.glow(x + dx, y + dy, rx, ry, iso.SHADOW_INK, alpha, mid=(0.55, 0.8))
    sc.cast_here()


def canopy(sc, blobs, leaf, hi=True):
    """樹冠: 重なる楕円 [(cx, cy, rx, ry), ...] を 1 本にまとめ、左上が明るく右下が暗い放射グラデーションで塗る。"""
    x0 = min(b[0] - b[2] for b in blobs)
    x1 = max(b[0] + b[2] for b in blobs)
    y0 = min(b[1] - b[3] for b in blobs)
    y1 = max(b[1] + b[3] for b in blobs)
    r = max(x1 - x0, y1 - y0)
    g = Radial(x0 + (x1 - x0) * 0.32, y0 + (y1 - y0) * 0.22, r * 0.86,
               [(0.0, lit(leaf, 1.32, 0.18)), (0.42, leaf), (0.78, shd(leaf, 0.66)), (1.0, shd(leaf, 0.5))])
    sc.ovals(blobs, shd(leaf, 0.6), None, 0, sil=True)
    sc.ovals(blobs, g, None, 0)
    # 葉のかたまりの影（下側）と明るい房（上側）
    sc.ovals([(cx + rx * 0.12, cy + ry * 0.46, rx * 0.74, ry * 0.42) for cx, cy, rx, ry in blobs[:2]], shd(leaf, 0.52), None, 0, 0.26)
    if hi:
        sc.ovals([(cx - rx * 0.3, cy - ry * 0.36, rx * 0.42, ry * 0.3) for cx, cy, rx, ry in blobs], lit(leaf, 1.4, 0.2), None, 0, 0.55)


def tree_oak(sc, size=1.0, leaf="#4F9F3C", trunk="#6E4A2A"):
    ground_shadow(sc, 11 * size, 5 * size)
    sc.cast_round(0.0, 0.0, 0.55 * size, 14 * size, 34 * size)
    sc.cylinder(0.0, 0.0, 0.13 * size, 0, 17 * size, trunk, cap=False, cast=False)
    b = Q(0.0, 0.0, 12 * size)
    sc.lines([[(b[0], b[1]), (b[0] - 4.2 * size, b[1] - 6.5 * size)], [(b[0] + 0.4, b[1] - 1.5), (b[0] + 4.4 * size, b[1] - 7 * size)]],
             shade(trunk, 0.8), 1.5 * size, cap="round")
    cx, cy = Q(0.0, 0.0, 22 * size)
    s = size
    canopy(sc, [(cx, cy, 12 * s, 10.5 * s), (cx - 2.1 * s, cy - 8.5 * s, 9.6 * s, 8.6 * s), (cx + 3.6 * s, cy - 13.6 * s, 7.6 * s, 7 * s),
                (cx - 8.6 * s, cy - 1.6 * s, 5.6 * s, 5.2 * s), (cx + 8.8 * s, cy - 3.6 * s, 5.4 * s, 5 * s)], leaf)


def tree_pine(sc, size=1.0, leaf="#2F7040", trunk="#5E4026"):
    ground_shadow(sc, 10 * size, 4.5 * size)
    sc.cylinder(0.0, 0.0, 0.1 * size, 0, 10 * size, trunk, cap=False, cast=False)
    sc.cast_round(0.0, 0.0, 0.6 * size, 8 * size, 8 * size, apex=42 * size)
    for k, (r, z, h) in enumerate([(0.95, 8, 16), (0.76, 18, 15), (0.56, 28, 14)]):
        c = shade(leaf, 0.94 + 0.08 * k)
        sc.cone(0.0, 0.0, r * size, z * size, h * size, c, 0.0, cast=False)
        # 枝先のぎざぎざ（すそ）と、左肩のハイライト
        arc = ring(0.0, 0.0, r * size, z * size, 8)
        sc.lines([[arc[i], (arc[i][0] + 0.2, arc[i][1] + 1.6 * size), arc[i + 1]] for i in range(0, 8)], shd(c, 0.6), 0.7, 0.8)
        top = Q(0.0, 0.0, (z + h) * size)
        sc.line([(top[0] - 1.0 * size, top[1] + 3.0 * size), (arc[1][0] + 1.5 * size, arc[1][1] - 2.4 * size)], lit(c, 1.5, 0.2), 0.8, 0.5,
                cap="round")


def tree_birch(sc):
    ground_shadow(sc, 9, 4)
    sc.cast_round(0.0, 0.0, 0.5, 22, 42)
    sc.cylinder(0.0, 0.0, 0.14, 0, 28, "#EDE8DA", cap=False, cast=False)
    sc.lines([[Q(-0.14, 0.14, z), Q(-0.02, 0.02, z - 0.4)] for z in (6, 15, 22)] + [[Q(0.14, -0.14, z), Q(0.03, -0.03, z + 0.4)] for z in (10, 19)],
             "#4A4A44", 0.9, cap="round")
    cx, cy = Q(0.0, 0.0, 30)
    canopy(sc, [(cx, cy, 10, 9.6), (cx + 1.6, cy - 8, 7.2, 7), (cx - 6.4, cy - 3.2, 5, 4.8), (cx + 6.6, cy + 1.2, 4.6, 4.4)], "#7CBC48")


def bush(sc):
    ground_shadow(sc, 8, 3.5, 0.34)
    sc.cast_round(0.0, 0.0, 0.35, 0, 7)
    cx, cy = Q(0.0, 0.0, 4.5)
    canopy(sc, [(cx + 1.6, cy - 0.6, 6.2, 5.2), (cx - 3.4, cy + 0.6, 5, 4.2), (cx + 5.6, cy + 1.6, 3.4, 3)], "#56A646")
    sc.ovals([(cx - 3.6, cy + 1.6, 0.9, 0.9), (cx + 3.0, cy + 2.2, 0.9, 0.9), (cx + 0.2, cy - 1.8, 0.8, 0.8)], "#E8475A", "#7A1E2C", 0.3)


def rock(sc, variant=0):
    ground_shadow(sc, 11, 4.5, 0.36)
    sc.cast_round(0.0, 0.0, 0.5, 0, 9)
    c = "#96938C" if variant == 0 else "#A69E90"
    x, y = Q(0, 0, 0)
    body = [(x - 9, y), (x - 6, y - 8), (x + 1, y - 11), (x + 8, y - 6), (x + 10, y + 1), (x + 2, y + 4), (x - 5, y + 3)]
    sc.poly(body, vgrad(y - 11, y + 4, shd(c, 0.84), shd(c, 0.6)), edge(c), 0.5, sil=True)
    sc.poly([(x - 9, y), (x - 6, y - 8), (x - 4, y - 3), (x - 5, y + 3)], shade(c, 0.98), None, 0)
    sc.poly([(x - 6, y - 8), (x + 1, y - 11), (x + 8, y - 6), (x + 2, y - 3), (x - 4, y - 3)], lit(c, 1.2), None, 0)
    sc.lines([[(x + 2, y - 3), (x + 2, y + 4)], [(x - 4, y - 3), (x + 2, y - 3)]], shade(c, 0.6), 0.4, 0.5)
    if variant:
        sc.poly([(x + 6, y + 2), (x + 11, y - 3), (x + 14, y + 2), (x + 9, y + 5)], shd(c, 0.8), edge(c), 0.5, sil=True)
        sc.poly([(x + 6, y + 2), (x + 11, y - 3), (x + 12, y + 1), (x + 9, y + 2.4)], lit(c, 1.12), None, 0)
    # 苔
    sc.ovals([(x - 2.6, y - 7.6, 3.0, 1.3), (x + 3.6, y - 7.2, 2.2, 1.0)], "#6FAE4E", None, 0, 0.85)


def hay(sc):
    ground_shadow(sc, 9, 4, 0.34)
    gen_buildings.haybale(sc, 0.0, 0.0, 0.5, 9.0)
    p = Q(0.0, 0.0, 9)
    sc.oval(p[0], p[1], 3.6, 1.8, None, shade(STRAW, 0.7), 0.5, 0.7)
    sc.oval(p[0], p[1], 0.9, 0.5, shade(STRAW, 0.6), None, 0)


def lamp(sc):
    ground_shadow(sc, 5, 2.2, 0.34)
    b, t = Q(0, 0, 0), Q(0, 0, 21)
    sc.cast([(-0.06, 0, 0), (0.06, 0, 0), (0, 0, 24)])
    sc.lines([[b, t], [(b[0] - 1.6, b[1]), (b[0] + 1.6, b[1])]], INK, 3.4, cap="round")
    sc.line([b, t], "#4A4852", 1.8, cap="round")
    sc.line([(b[0] - 0.5, b[1] - 1), (t[0] - 0.5, t[1] + 1)], "#8A8894", 0.5, 0.7)
    sc.line([(b[0] - 1.6, b[1]), (b[0] + 1.6, b[1])], "#3A3840", 1.8, cap="round")
    x, y = Q(0, 0, 24)
    sc.glow(x, y - 1, 11, 9, "#FFD36B", 0.55)
    glass = [(x - 3.2, y + 2), (x + 3.2, y + 2), (x + 2.6, y - 4.2), (x - 2.6, y - 4.2)]
    sc.poly(glass, vgrad(y - 4.2, y + 2, "#FFF6C8", "#FFC94A"), "#33313A", 0.7, sil=True)
    sc.line([(x, y + 2), (x, y - 4.2)], "#33313A", 0.5, 0.8)
    sc.poly([(x - 3.9, y - 4.2), (x + 3.9, y - 4.2), (x, y - 8.4)], "#44424C", "#26242C", 0.5, sil=True)
    sc.oval(x, y - 8.8, 0.9, 0.9, "#E8C547", "#7A5A14", 0.3, sil=True)


def well(sc):
    ground_shadow(sc, 11, 5)
    stone = "#A8A49A"
    sc.cylinder(0.0, 0.0, 0.62, 0, 8, stone, top=lit(stone, 1.16))
    sc.lines([ring(0.0, 0.0, 0.62, 4.0)] + [[Q(0.62 * math.cos(a), 0.62 * math.sin(a), z0), Q(0.62 * math.cos(a), 0.62 * math.sin(a), z0 + 4)]
                                              for a, z0 in ((math.radians(110), 0), (math.radians(60), 4), (math.radians(20), 0), (math.radians(-20), 4))],
             shd(stone, 0.5), 0.4, 0.5)
    x, y = Q(0, 0, 8)
    sc.oval(x, y, 6.3, 3.1, Radial(x - 2, y - 1, 7, ["#6FB4E8", "#2E6AA8"]), "#2A4A6E", 0.5)
    posts = [[Q(-0.55, 0.55, 8), Q(-0.55, 0.55, 20)], [Q(0.55, -0.55, 8), Q(0.55, -0.55, 20)]]
    sc.lines(posts, INK, 2.6, cap="round")
    sc.lines(posts, "#7A5530", 1.4, cap="round")
    sc.line([Q(-0.55, 0.55, 16.5), Q(0.55, -0.55, 16.5)], "#5A4630", 1.0)
    sc.line([Q(0, 0, 16.5), Q(0, 0, 11.5)], "#D8C9A0", 0.5)
    bk = Q(0, 0, 10)
    sc.poly([(bk[0] - 1.6, bk[1] - 1.6), (bk[0] + 1.6, bk[1] - 1.6), (bk[0] + 1.2, bk[1] + 1.2), (bk[0] - 1.2, bk[1] + 1.2)], "#8E6038", "#3A2412", 0.4)
    roof = "#C4563A"
    a, b, top = Q(-0.85, 0.85, 19.5), Q(0.85, -0.85, 19.5), Q(0.0, 0.0, 26.5)
    f = Q(0.35, 0.35, 19.0)
    sc.sil([a, f, b, top])
    sc.poly([a, f, top], lit(roof, 1.1), edge(roof), 0.45)
    sc.poly([f, b, top], shd(roof, 0.74), edge(roof), 0.45)


def signpost(sc):
    """立て札（板の上にアプリが文字を重ねる。板の位置と大きさは変えない）。"""
    ground_shadow(sc, 7, 3, 0.34)
    b, t = Q(0, 0, 0), Q(0, 0, 34)
    sc.cast([(-0.1, 0, 0), (0.1, 0, 0), (-0.6, 0.6, 38), (0.6, -0.6, 38)])
    sc.line([b, t], INK, 4.4, cap="round")
    sc.line([b, t], "#7A5530", 2.4, cap="round")
    sc.line([(b[0] - 0.6, b[1] - 1), (t[0] - 0.6, t[1] + 14)], "#A67C4A", 0.6, 0.8)
    x, y = Q(0, 0, 30)
    board = [(x - 22, y - 12), (x + 22, y - 12), (x + 22, y + 4), (x - 22, y + 4)]
    sc.poly(board, vgrad(y - 12, y + 4, "#E2C48C", "#C9A566"), "#5A3E22", 0.9, sil=True)
    sc.lines([[(x - 22, y - 6.6), (x + 22, y - 6.6)], [(x - 22, y - 1.3), (x + 22, y - 1.3)]], "#A8824A", 0.4, 0.55)
    sc.line([(x - 21, y - 11), (x + 21, y - 11)], "#F4E2B8", 0.7, 0.8)
    sc.ovals([(x - 19.6, y - 9.6, 0.8, 0.8), (x + 19.6, y - 9.6, 0.8, 0.8), (x - 19.6, y + 1.6, 0.8, 0.8), (x + 19.6, y + 1.6, 0.8, 0.8)],
             "#5A5A64", None, 0)


def fountain(sc):
    ground_shadow(sc, 21, 9.5)
    stone = "#DDD6C4"
    sc.cylinder(0.0, 0.0, 1.5, 0, 7, stone, top=lit(stone, 1.12))
    sc.line(ring(0.0, 0.0, 1.5, 3.4, 10), shd(stone, 0.5), 0.4, 0.5)
    x, y = Q(0, 0, 7)
    sc.oval(x, y, 17.2, 8.2, Radial(x - 5, y - 3, 20, ["#8FD0F8", "#4D94D8", "#2E6AB0"]), "#28588E", 0.6)
    sc.ovals([(x - 6, y + 2.6, 4.2, 1.5), (x + 7.5, y - 0.6, 3.4, 1.2)], "#D6F0FF", None, 0, 0.55)
    sc.cylinder(0.0, 0.0, 0.3, 7, 21, lit(stone, 1.05), cap=False, cast=False)
    bx, by = Q(0, 0, 21)
    sc.poly([(bx - 7.4, by - 1.2), (bx + 7.4, by - 1.2), (bx + 3.2, by + 3.4), (bx - 3.2, by + 3.4)],
            Linear(bx - 7.4, 0, bx + 7.4, 0, [lit(stone, 1.12), shd(stone, 0.72)]), edge(stone), 0.45, sil=True)
    sc.oval(bx, by - 1.2, 7.4, 3.0, "#8FCBF6", "#28588E", 0.5, sil=True)
    # 水の筋としぶき
    jets = []
    for k in range(6):
        a = math.radians(k * 60 + 20)
        end = Q(0.95 * math.cos(a), 0.95 * math.sin(a), 8)
        jets.append([(bx + 5.6 * math.cos(a), by - 1.2 + 2.3 * math.sin(a)), ((bx + end[0]) / 2 + 3.4 * math.cos(a), by + 2), end])
    sc.lines(jets, "#DDF2FF", 0.9, 0.85, cap="round")
    sc.lines([[(bx, by - 1.6), (bx, by - 7.4)], [(bx, by - 7.4), (bx - 2.6, by - 4.2)], [(bx, by - 7.4), (bx + 2.6, by - 4.2)]], "#EAF7FF",
             1.0, 0.9, cap="round")
    sc.oval(bx, by - 8, 1.3, 1.3, "#FFFFFF", None, 0, 0.9)


def house(sc, kind):
    """町の小さな家。0 = 漆喰と瓦 / 1 = 石とスレート / 2 = 板壁と茅。"""
    tier = {0: 2, 1: 3, 2: 1}[kind]
    m = dict(gen_buildings.MAT[tier])
    x0, y0, x1, y1 = -0.9, -0.7, 0.9, 0.6
    h = 11 + kind * 2
    axis = "x" if kind != 1 else "y"
    gen_buildings.hall(sc, m, tier, x0, y0, x1, y1, h, roof="gable", axis=axis, rh=7 + kind, nl=0, nr=1, door=False, zb=3.0,
                       win=(0.34, 4.6), over=0.2, eave=2.2)
    lf = Lf(y1)
    sc.door_left(-0.38, 0.4, 6.8, y1, m["door"], frame=gen_buildings.door_frame(m), grad=False)
    gen_buildings.window_set(sc, m, lf, [(0.4, 3 + (h - 3) * 0.56)], 0.34, 4.6)
    if kind == 0:
        gen_buildings.chimney(sc, m, 0.3, -0.55, h + 1, h + 9, 0.34)
    elif kind == 2:
        gen_buildings.barrel(sc, 0.62, 0.86, 0.16, 4.6)
    else:
        gen_buildings.crate(sc, 0.5, 0.72, 0.3, 4.0)


def stall(sc):
    """市場の露店: 縞の天幕と商品。"""
    ground_shadow(sc, 15, 6.5, 0.34)
    gen_buildings.stall(sc, -0.82, -0.5, ("#D9453D", "#F6EFDC"), w=1.64, d=1.0, hgt=16.0)
    gen_buildings.sacks(sc, [(0.98, 0.9)], s=0.8)


def field(sc):
    """畑: 畝のある土の平面（作物が育つ）。"""
    a, b, c, d = Q(-1.0, -1.0), Q(1.0, -1.0), Q(1.0, 1.0), Q(-1.0, 1.0)
    sc.poly([d, c, b, (b[0], b[1] + 1.0), (c[0], c[1] + 1.0), (d[0], d[1] + 1.0)], "#4E3820", "#3A2A18", 0.5)
    sc.poly([a, b, c, d], vgrad(a[1], c[1], "#8A6638", "#6E4E2A"), "#4E3820", 0.5)
    gen_buildings.crops(sc, -0.86, -0.84, 0.86, 0.84, 4, "#86C24C", "y", 2.6)
    sc.ovals([(Q(-0.69 + 0.43 * k, 0.3 - 0.5 * (k % 2))[0], Q(-0.69 + 0.43 * k, 0.3 - 0.5 * (k % 2))[1] - 1.2, 1.1, 0.9) for k in range(4)],
             "#F2D24E", None, 0, 0.9)


def manor(sc):
    """屋敷: 二階建てで、屋根は青、金の飾りがつく（外側の帯ほど豪華な街並み）。"""
    m = dict(gen_buildings.MAT[4])
    m.update(wall="#E4D6B0", frame="#8A6A44", door="#5E3A22")
    x0, y0, x1, y1 = -1.0, -0.8, 1.0, 0.8
    gen_buildings.hall(sc, m, 4, x0, y0, x1, y1, 24, roof="gable", axis="x", rh=10, nl=0, nr=0, door=False, zb=4.0, over=0.14)
    lf, rf = Lf(y1), Rf(x1)
    gen_buildings.window_set(sc, m, rf, [(0.0, 9.5), (0.0, 17.5)], 0.4, 4.6, dark=True)
    gen_buildings.window_set(sc, m, lf, [(-0.5, 17.5), (0.5, 17.5), (-0.5, 9.5), (0.5, 9.5)], 0.3, 4.6)
    sc.lines([[lf(x0, 13.4), lf(x1, 13.4)]], m["gold"], 0.8)
    sc.lines([[rf(y0, 13.4), rf(y1, 13.4)]], shd(m["gold"], 0.8), 0.8)
    sc.door_left(0.0, 0.5, 8, y1, m["door"], frame=lit(m["base"], 1.2), grad=False)
    gen_buildings.chimney(sc, m, 0.4, -0.6, 27, 37, 0.34, smk=False)
    sc.flag(-0.6, 0.0, 34, 9, "#E8C547")


def statue(sc):
    """広場の像: 大理石の台座と金の像。"""
    ground_shadow(sc, 12, 5.2)
    sc.box(-0.6, -0.6, 0.6, 0.6, 0, 8, "#E8E1CF")
    sc.lines([[Q(-0.6, 0.6, 6.4), Q(0.6, 0.6, 6.4)], [Q(-0.6, 0.6, 1.6), Q(0.6, 0.6, 1.6)]], shade("#E8E1CF", 0.7), 0.4, 0.6)
    sc.box(-0.42, -0.42, 0.42, 0.42, 8, 10, "#D4CCB8", grad=False, cast=False)
    gold = "#EEC548"
    x, y = Q(0, 0, 10)
    # 剣を掲げる騎士（外套・頭・腕・剣）
    body = [(x - 3.6, y), (x - 2.6, y - 9), (x - 1.2, y - 12.5), (x + 1.4, y - 12.5), (x + 2.8, y - 9), (x + 4.4, y), (x + 1.6, y + 1.2), (x - 1.4, y + 1.2)]
    sc.cast([(-0.2, 0.2, 10), (0.2, -0.2, 10), (-0.2, 0.2, 26), (0.2, -0.2, 26)])
    sc.poly(body, Linear(x - 4, 0, x + 4.4, 0, [lit(gold, 1.3, 0.1), gold, shd(gold, 0.66)]), "#7A5A14", 0.5, sil=True)
    sc.line([(x + 1.8, y - 11), (x + 5.2, y - 15.5)], "#7A5A14", 2.2, cap="round")
    sc.line([(x + 1.8, y - 11), (x + 5.2, y - 15.5)], gold, 1.3, cap="round")
    sc.line([(x + 5.2, y - 15.5), (x + 6.4, y - 24)], "#7A5A14", 1.6, cap="round")
    sc.line([(x + 5.2, y - 15.5), (x + 6.4, y - 24)], "#FFF3B0", 0.8, cap="round")
    sc.line([(x + 3.8, y - 16.2), (x + 6.8, y - 15.2)], "#7A5A14", 1.2, cap="round")
    sc.oval(x + 0.1, y - 15.2, 2.7, 2.8, Radial(x - 0.8, y - 16.2, 3.6, [lit(gold, 1.4, 0.1), gold, shd(gold, 0.7)]), "#7A5A14", 0.5, sil=True)
    sc.line([(x - 2.4, y - 8), (x - 1.6, y - 1)], "#FFF3B0", 0.6, 0.7, cap="round")


def _wall_body(tier):
    m = gen_buildings.MAT[tier]
    return m, (m["base"] if tier < 3 else m["wall"])


def _wall_courses(sc, fn, lo, hi, body, dark):
    """城壁の石の目地（つなぎ目をまたいでも柄がそろうよう、部品の中で閉じた配置にする）。"""
    ink = shd(body, 0.5) if dark else shade(body, 0.66)
    segs = [[fn(lo, 4.0), fn(hi, 4.0)], [fn(lo, 8.0), fn(hi, 8.0)]]
    for k, zz in enumerate((0.0, 4.0, 8.0)):
        for j in range(3):
            a = lo + (hi - lo) * ((j + (0.25 if k % 2 else 0.6)) / 3.0)
            segs.append([fn(a, zz), fn(a, zz + 4.0)])
    sc.lines(segs, ink, 0.4, 0.5)


def wall_x(sc, tier):
    """城壁の部品（x 方向に 2 単位）。左右の端は隣の部品とつながるので縁取らない。"""
    m, body = _wall_body(tier)
    lf = Lf(0.5)
    sc.poly([Q(-1.0, 0.5, 0), Q(1.0, 0.5, 0), (Q(1.0, 0.5, 0)[0], Q(1.0, 0.5, 0)[1] + 2.4), (Q(-1.0, 0.5, 0)[0], Q(-1.0, 0.5, 0)[1] + 2.4)],
            vgrad(Q(0, 0.5, 0)[1] - 7, Q(0, 0.5, 0)[1] + 9.4, with_alpha(iso.SHADOW_INK, 0.34), with_alpha(iso.SHADOW_INK, 0.0)), None, 0)
    sc.box(-1.0, -0.5, 1.0, 0.5, 0, 12, body, grad="l", outline=False, top=lit(body, 1.12))
    _wall_courses(sc, lf, -1.0, 1.0, body, False)
    boxes(sc, [(-0.95 + i * 0.7, 0.28, -0.55 + i * 0.7, 0.5, 12, 15) for i in range(3)], lit(body, 1.03),
          m["gold"] if tier >= 5 else None)
    if tier >= 4:
        sc.line([Q(-1.0, 0.5, 11.2), Q(1.0, 0.5, 11.2)], m["gold"], 0.9)
    # 上下の縁（外形の線）
    sc.lines([[Q(-1.0, 0.5, 0), Q(1.0, 0.5, 0)], [Q(-1.0, -0.5, 12), Q(1.0, -0.5, 12)]], INK, 1.2)
    sc.line([Q(-1.0, 0.5, 12), Q(1.0, 0.5, 12)], edge(body), 0.5)


def wall_y(sc, tier):
    m, body = _wall_body(tier)
    rf = Rf(0.5)
    # 右前の地面へ落ちる影（部品どうしでつながる帯）
    sh = [Q(0.5, -1.0, 0), Q(0.5, 1.0, 0), Q(1.0, 1.0, 0), Q(1.0, -1.0, 0)]
    sc.poly(sh, grad_perp(sh[0], sh[1], sh[3], [with_alpha(iso.SHADOW_INK, 0.4), with_alpha(iso.SHADOW_INK, 0.0)]), None, 0)
    sc.box(-0.5, -1.0, 0.5, 1.0, 0, 12, body, grad="r", outline=False, top=lit(body, 1.12))
    _wall_courses(sc, rf, -1.0, 1.0, body, True)
    boxes(sc, [(0.28, -0.95 + i * 0.7, 0.5, -0.55 + i * 0.7, 12, 15) for i in range(3)], lit(body, 1.03),
          m["gold"] if tier >= 5 else None)
    if tier >= 4:
        sc.line([Q(0.5, -1.0, 11.2), Q(0.5, 1.0, 11.2)], shd(m["gold"], 0.84), 0.9)
    sc.lines([[Q(0.5, -1.0, 0), Q(0.5, 1.0, 0)], [Q(-0.5, -1.0, 12), Q(-0.5, 1.0, 12)]], INK, 1.2)
    sc.line([Q(0.5, -1.0, 12), Q(0.5, 1.0, 12)], edge(body), 0.5)


def wall_tower(sc, tier):
    m = dict(gen_buildings.MAT[tier])
    if tier < 3:
        m["style"] = "stone"
    ground_shadow(sc, 17, 7)
    h = 22 + tier * 3
    gen_buildings.tower(sc, m, tier, 0.0, 0.0, 0.75, h, 11 + tier, flag=True, wall=m["base"] if tier < 3 else m["wall"], fh=9)


def gate(sc, tier):
    """前の角の門楼（ひとつ）。"""
    m = dict(gen_buildings.MAT[tier])
    if tier < 3:
        m["style"] = "stone"
    ground_shadow(sc, 23, 10)
    h = 26 + tier * 3
    body = m["base"] if tier < 3 else m["wall"]
    if tier >= 4:
        boxes(sc, [(-1.05, -1.05, -0.85, -0.85, 0, h + 2)], m["trim"])
    sc.box(-0.9, -0.9, 0.9, 0.9, 0, h, body, eave=3.0, faces="rl")
    gen_buildings.wall_tex(sc, m, -0.9, -0.9, 0.9, 0.9, 0, h, body)
    lf, rf = Lf(0.9), Rf(0.9)
    sc.door_left(0.0, 0.9, 16, 0.9, "#2A1E16", frame=lit(m["base"], 1.22))
    sc.lines([[lf(d, 0.5), lf(d, 12.4 + (1.8 if d == 0 else 0))] for d in (-0.26, 0.0, 0.26)] + [[lf(-0.4, z), lf(0.4, z)] for z in (4.2, 8.4)],
             "#6A6E78", 0.6, 0.9)
    gen_buildings.window_set(sc, m, rf, [(-0.4, h - 9), (0.4, h - 9)], 0.26, 5.6, dark=True)
    gen_buildings.window_set(sc, m, lf, [(0.0, h - 6.6)], 0.3, 5.0)
    gen_buildings.torches(sc, [(-0.68, 0.9, 12.5), (0.68, 0.9, 12.5)])
    if tier >= 2:
        sc.banner(0.9, 0.0, h - 14, 8.5, m["banner"], m["gold"])
    gen_buildings.gable_roof(sc, m, -0.9, -0.9, 0.9, 0.9, h, 8 + tier, "x", over=0.15, wall=body, ornament=False)
    sc.flag(0.0, 0.0, h + 8 + tier, 12, m["banner"], 8.0)


def site(sc):
    """建物のない区画（建設予定地）: 土のパッドと杭とロープ。128 x 160。"""
    m = gen_buildings.MAT[1]
    gen_buildings.base_pad(sc, m, 1)
    gen_buildings.ground(sc, 0.5, 0.5, 3.5, 3.5, "#A08A5C", "#7C6B47")
    sc.lines([[P(0.5 + 0.5 * i, 0.5, 0), P(0.5 + 0.5 * i, 3.5, 0)] for i in range(1, 6)] + [[P(0.5, 0.5 + 0.5 * i, 0), P(3.5, 0.5 + 0.5 * i, 0)] for i in range(1, 6)],
             "#FFFFFF", 0.4, 0.22)
    sc.cast_here()
    # 資材（板・木箱・砂の山）
    boxes(sc, [(1.2, 1.2, 2.2, 1.5, 0, 1.8), (1.26, 1.24, 2.14, 1.46, 1.8, 3.4)], "#D9B27A", cast=True)
    gen_buildings.crate(sc, 2.5, 2.1, 0.5, 5.0)
    p = P(1.6, 2.9, 0)
    sc.poly([(p[0] - 7, p[1] + 0.6), (p[0] - 3, p[1] - 4.4), (p[0] + 1.6, p[1] - 5.4), (p[0] + 7, p[1] + 0.4), (p[0], p[1] + 3)],
            vgrad(p[1] - 5.4, p[1] + 3, "#E8D29A", "#B89A5E"), "#6E5A30", 0.5, sil=True)
    sc.line([(p[0] + 2, p[1] - 3.6), (p[0] + 8, p[1] - 12)], INK, 2.6, cap="round")
    sc.line([(p[0] + 2, p[1] - 3.6), (p[0] + 8, p[1] - 12)], "#8A6238", 1.4, cap="round")
    # 四隅の杭・赤い小旗・ロープ
    sc.layer()
    corners = [(0.3, 0.3), (3.7, 0.3), (3.7, 3.7), (0.3, 3.7)]
    rope = [P(x, y, 7) for x, y in corners] + [P(0.3, 0.3, 7)]
    sc.line(rope, "#4A3A22", 1.5, 0.5)
    sc.line(rope, "#F0E2B4", 0.8)
    posts = [[P(x, y, 0), P(x, y, 9.5)] for x, y in corners]
    sc.lines(posts, INK, 3.4, cap="round")
    sc.lines(posts, "#8A6238", 1.8, cap="round")
    sc.polys([[(P(x, y, 9.5)[0], P(x, y, 9.5)[1] - 0.6), (P(x, y, 9.5)[0] + 6.4, P(x, y, 9.5)[1] + 1.4), (P(x, y, 9.5)[0] + 0.6, P(x, y, 9.5)[1] + 4)]
              for x, y in corners], "#E0463C", "#6E1A16", 0.45, sil=True)


def tent(sc):
    """野営地のテント（赤茶の切妻。入口と旗つき）。"""
    ground_shadow(sc, 17, 7.5)
    c = "#B44E32"
    sc.gable(-0.9, -0.7, 0.9, 0.7, 0, 17, c, axis="y", over=0.0, gable_c=shade(c, 0.86), thick=0, ridge=lit(c, 1.3, 0.2))
    # 布の縞（右の面）と入口（左前の妻）
    r0, r1, e0, e1 = Q(0.0, -0.7, 17), Q(0.0, 0.7, 17), Q(0.9, -0.7, 0), Q(0.9, 0.7, 0)
    sc.lines([[(r0[0] + (r1[0] - r0[0]) * f, r0[1] + (r1[1] - r0[1]) * f), (e0[0] + (e1[0] - e0[0]) * f, e0[1] + (e1[1] - e0[1]) * f)]
              for f in (0.25, 0.5, 0.75)], "#F0D9A8", 1.1, 0.55)
    sc.poly([Q(-0.3, 0.7, 0), Q(0.3, 0.7, 0), Q(0.0, 0.7, 11)], vgrad(Q(0, 0.7, 11)[1], Q(0, 0.7, 0)[1], "#1E1210", "#4A2A1C"), "#2A1812", 0.5)
    sc.poly([Q(-0.3, 0.7, 0), Q(-0.02, 0.7, 10.6), Q(-0.42, 0.7, 4.5)], "#E8C99A", edge("#C9A56E"), 0.4)
    sc.lines([[Q(-0.9, 0.7, 0), (Q(-0.9, 0.7, 0)[0] - 3, Q(-0.9, 0.7, 0)[1] + 2.4)], [Q(0.9, 0.7, 0), (Q(0.9, 0.7, 0)[0] + 1, Q(0.9, 0.7, 0)[1] + 3.2)],
              [Q(0.9, -0.7, 0), (Q(0.9, -0.7, 0)[0] + 3.4, Q(0.9, -0.7, 0)[1] + 1.2)]], "#5A4630", 0.7)
    sc.flag(0.0, 0.0, 17, 12, "#D03A2E", 8.0)


def temple(sc):
    """聖地の神殿（段・円柱・金の切妻屋根）。"""
    ground_shadow(sc, 30, 13, 0.4)
    stone = "#EFE9DA"
    g = Q(0, 0, 14)
    sc.box(-1.7, -1.3, 1.7, 1.3, 0, 3, shade(stone, 0.92), grad=False)
    sc.box(-1.45, -1.1, 1.45, 1.1, 3, 5, stone, grad=False, cast=False)
    # 奥の壁（内陣）と、中のあかり
    sc.box(-0.9, -0.7, 0.9, 0.55, 5, 24, shade(stone, 0.9), eave=4.0, faces="rl", cast=False)
    dp = gen_buildings.pane(Lf(0.55), 0.0, 13.5, 0.62, 15.0, True)
    sc.poly(dp, vgrad(dp[4][1], dp[0][1], "#FFF3B0", "#FFC24A"), "#B8922A", 0.8)
    sc.glow(g[0] - 6, g[1] + 6, 13, 12, GLOW, 0.5)
    for (x, y) in ((1.1, -0.85), (-1.1, 0.85), (-0.37, 0.85), (0.37, 0.85), (1.1, 0.85), (1.1, 0.0)):
        sc.cylinder(x, y, 0.15, 5, 24, "#F8F3E6", cap=False, cast=False)
    sc.box(-1.3, -1.0, 1.3, 1.0, 24, 27, lit(stone, 1.02), grad=False)
    sc.lines([[Q(-1.3, 1.0, 25.5), Q(1.3, 1.0, 25.5)]], "#E3BB48", 0.9)
    sc.lines([[Q(1.3, -1.0, 25.5), Q(1.3, 1.0, 25.5)]], shd("#E3BB48", 0.84), 0.9)
    sc.gable(-1.4, -1.1, 1.4, 1.1, 27, 11, "#E6B93A", axis="x", over=0.0, wall=stone, tiles=4, ridge="#FFF6C0", rw=1.1)
    gx, gy = Q(1.4, 0.04, 30.6)
    gen_buildings.gem(sc, gx, gy, 2.4, "#3FB6E8")
    top = Q(0.0, 0.0, 38)
    sc.line([top, (top[0], top[1] - 7)], "#B8922A", 1.3, cap="round")
    sc.polys([gen_buildings._star_pts(top[0], top[1] - 9, 4.2)], "#FFE98A", "#B8922A", 0.4, sil=True)
    sc.glow(top[0], top[1] - 9, 9, 9, "#FFE98A", 0.5)


DECOR = [
    ("tree_oak_a", lambda sc: tree_oak(sc, 1.0)),
    ("tree_oak_b", lambda sc: tree_oak(sc, 0.8, "#6AAE45", "#7A5A38")),
    ("tree_oak_c", lambda sc: tree_oak(sc, 1.12, "#3F8A3A", "#5F4126")),
    ("tree_pine_a", lambda sc: tree_pine(sc, 1.0)),
    ("tree_pine_b", lambda sc: tree_pine(sc, 0.82, "#3A7A44")),
    ("tree_birch", tree_birch),
    ("bush", bush),
    ("rock_a", lambda sc: rock(sc, 0)),
    ("rock_b", lambda sc: rock(sc, 1)),
    ("hay", hay),
    ("lamp", lamp),
    ("well", well),
    ("signpost", signpost),
    ("fountain", fountain),
    ("house_a", lambda sc: house(sc, 0)),
    ("house_b", lambda sc: house(sc, 1)),
    ("house_c", lambda sc: house(sc, 2)),
    ("stall", stall),
    ("field", field),
    ("manor", manor),
    ("statue", statue),
    ("tent", tent),
    ("temple", temple),
]


def build_decor(name):
    fn = dict(DECOR)[name]
    if name == "temple":
        # 建物と同じ大きさの viewport（128 x 160、足元 (64, 120)）
        return around(fn, 128, 160, 64.0, 120.0)
    return around(fn)


def build_wall(kind, tier):
    fn = {"x": wall_x, "y": wall_y, "tower": wall_tower, "gate": gate}[kind]
    seg = kind in ("x", "y")
    return around(lambda sc: fn(sc, tier), 64, 64 if seg else 96, 32.0, 48.0 if seg else 80.0, outline=not seg)


def build_site():
    sc = gen_buildings.new_scene()
    site(sc)
    return sc


def kotlin_ids():
    lines = []
    lines.append("package com.ttmrase.crownfall.ui.territory")
    lines.append("")
    lines.append("import com.ttmrase.crownfall.R")
    lines.append("import com.ttmrase.crownfall.core.model.BuildingType")
    lines.append("")
    lines.append("/** tools/artgen/gen_decor.py が生成する（手で編集しない）。建物・装飾のスプライトの ID 表。 */")
    lines.append("object SpriteIds {")
    lines.append("    /** 建物の見た目 [tier]（1..5）の drawable。 */")
    lines.append("    fun building(")
    lines.append("        type: BuildingType,")
    lines.append("        tier: Int,")
    lines.append("    ): Int = BUILDINGS.getValue(type)[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    /** 城のスキン（[theme] は %s）の見た目 [tier]（1..5）。知らないテーマは標準の城。 */" % " / ".join(gen_buildings.SKIN_PALETTES))
    lines.append("    fun castleSkin(")
    lines.append("        theme: String,")
    lines.append("        tier: Int,")
    lines.append("    ): Int {")
    lines.append("        val i = (tier - 1).coerceIn(0, 4)")
    lines.append("        return when (theme) {")
    for skin in gen_buildings.SKIN_PALETTES:
        lines.append('            "%s" -> CASTLE_%s[i]' % (skin, skin.upper()))
    lines.append("            else -> building(BuildingType.CASTLE, tier)")
    lines.append("        }")
    lines.append("    }")
    lines.append("")
    lines.append("    /** 城壁の部品の drawable（[tier] は 1..5）。 */")
    lines.append("    fun wallX(tier: Int): Int = WALL_X[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    fun wallY(tier: Int): Int = WALL_Y[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    fun wallTower(tier: Int): Int = WALL_TOWER[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    fun gate(tier: Int): Int = GATE[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    for name, _ in DECOR:
        lines.append("    val %s: Int = R.drawable.deco_%s" % (name.upper(), name))
        lines.append("")
    lines.append("    val SITE: Int = R.drawable.deco_site")
    lines.append("")

    def arr(const, items):
        lines.append("    private val %s =" % const)
        lines.append("        intArrayOf(")
        for it in items:
            lines.append("            %s," % it)
        lines.append("        )")
        lines.append("")

    arr("WALL_X", ["R.drawable.deco_wall_x_t%d" % t for t in range(1, 6)])
    arr("WALL_Y", ["R.drawable.deco_wall_y_t%d" % t for t in range(1, 6)])
    arr("WALL_TOWER", ["R.drawable.deco_wall_tower_t%d" % t for t in range(1, 6)])
    arr("GATE", ["R.drawable.deco_gate_t%d" % t for t in range(1, 6)])
    for skin in gen_buildings.SKIN_PALETTES:
        arr("CASTLE_%s" % skin.upper(), ["R.drawable.bld_castle_%s_t%d" % (skin, t) for t in range(1, 6)])
    lines.append("    private val BUILDINGS: Map<BuildingType, IntArray> =")
    lines.append("        mapOf(")
    for t in gen_buildings.TYPES:
        lines.append("            BuildingType.%s to" % t.upper())
        lines.append("                intArrayOf(")
        for tier in range(1, 6):
            lines.append("                    R.drawable.bld_%s_t%d," % (t, tier))
        lines.append("                ),")
    lines.append("        )")
    lines.append("")
    lines.append("    /** すべての建物の drawable（先読み用）。 */")
    lines.append("    val ALL_BUILDINGS: List<Int> get() = BUILDINGS.values.flatMap { it.toList() }")
    lines.append("}")
    # 末尾の空行を整える
    text = "\n".join(lines).replace("\n\n}\n", "\n}\n")
    return text.rstrip("\n") + "\n"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    n = 0
    for name, _ in DECOR:
        (OUT / ("deco_%s.xml" % name)).write_text(build_decor(name).to_xml("decor " + name), encoding="utf-8")
        n += 1
    for kind in ("x", "y", "tower", "gate"):
        for tier in range(1, 6):
            fname = "deco_wall_%s_t%d.xml" % (kind, tier) if kind != "gate" else "deco_gate_t%d.xml" % tier
            (OUT / fname).write_text(build_wall(kind, tier).to_xml("wall %s tier %d" % (kind, tier)), encoding="utf-8")
            n += 1
    (OUT / "deco_site.xml").write_text(build_site().to_xml("build site"), encoding="utf-8")
    n += 1
    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN.write_text(kotlin_ids(), encoding="utf-8")
    print("wrote %d decor drawables and %s" % (n, KOTLIN.name))


if __name__ == "__main__":
    main()
