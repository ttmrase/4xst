#!/usr/bin/env python3
"""ゲーム用アイコン一式（res/drawable/ic_*.xml）を生成する。標準ライブラリのみ。

絵文字の代わりに使う、自作のアイコン。48 x 48 の viewport、暗い輪郭線 + グラデーションの塗り + 上からの光。
使い方:
  python3 tools/artgen/gen_icons.py                 # app/src/main/res/drawable/ic_*.xml を書き出す
  python3 tools/artgen/gen_icons.py --html out.html # 確認用の一覧（SVG）も書き出す
"""

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from vec import Linear, Radial, Vec, circle, ellipse, hgrad, poly, rot, rrect, star, vgrad  # noqa: E402

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "res", "drawable")

# ---- 色 ---------------------------------------------------------------------------
INK = "#2A1A0C"  # 暖色の輪郭
INK_COOL = "#141B33"  # 寒色の輪郭（鋼・宝石）
W = "#FFFFFF"
K = "#000000"

GOLD = ("#FFF4B8", "#FFD34D", "#E0A21F", "#A86A0C")
STEEL = ("#FFFFFF", "#D5DEEC", "#9AA8C2", "#5E6B88")
WOOD = ("#E2A869", "#B67838", "#7E4C20")
RED = ("#FF8D7A", "#E8324A", "#9C1230")
GREEN = ("#A8F28B", "#3DBE5B", "#14773A")
BLUE = ("#9FD6FF", "#3B8BEE", "#1B4BAE")
PURPLE = ("#E3BCFF", "#9B58EC", "#4D2599")
PARCH = ("#FFF6DA", "#F0D9A4", "#C9A769")
STONE = ("#F0F3F8", "#B4BECE", "#7B879D", "#4E586C")


def g(colors, y0=4, y1=44):
    return vgrad(y0, y1, *colors)


def sword(v, cx=24.0, top=3.0, length=44.0, blade_w=5.0, grip=PURPLE):
    """上向きの剣（柄が下）。group の中で回して使う。"""
    bw = blade_w / 2
    blade_end = top + length * 0.62
    v.path(
        poly([(cx - bw, blade_end), (cx - bw, top + 6), (cx, top), (cx + bw, top + 6), (cx + bw, blade_end)]),
        hgrad(cx - bw, cx + bw, STEEL[0], STEEL[1], STEEL[2]), INK_COOL, 1.6,
    )
    v.line([(cx, top + 5), (cx, blade_end - 1)], STEEL[3], 0.9, salpha=0.55)
    v.path(rrect(cx - 9, blade_end, 18, 4.2, 2.1), g(GOLD, blade_end, blade_end + 4.2), INK, 1.5)
    grip_y = blade_end + 4.2
    grip_h = top + length - grip_y - 4
    v.path(rrect(cx - 2.1, grip_y, 4.2, grip_h, 1), hgrad(cx - 2, cx + 2, grip[0], grip[1], grip[2]), INK, 1.4)
    v.path(circle(cx, top + length - 2.6, 2.8), g(GOLD, top + length - 5.4, top + length), INK, 1.4)


# ---- 資源 -------------------------------------------------------------------------
def res_food():
    v = Vec()

    def ear(tilt):
        v.begin(rotate=tilt, px=24, py=45)
        v.line([(24, 45), (24, 16)], INK, 4.2)
        v.line([(24, 45), (24, 16)], "#D9A12A", 2.0)
        for y in (30.5, 24.5, 18.5, 12.5):
            for s in (-1, 1):
                v.begin(rotate=s * 32, px=24 + s * 3.6, py=y)
                v.path(ellipse(24 + s * 3.6, y, 2.9, 5.0), vgrad(y - 5, y + 5, GOLD[0], GOLD[1], GOLD[2]), INK, 1.5)
                v.end()
        v.path(ellipse(24, 7.6, 2.9, 5.2), vgrad(2, 13, GOLD[0], GOLD[1], GOLD[2]), INK, 1.5)
        v.end()

    ear(-27)
    ear(27)
    ear(0)
    v.path(rrect(17, 35.5, 14, 5.6, 2.2), g(RED, 35, 41), INK, 1.5)
    v.path(poly([(19, 41), (15.5, 46), (21.5, 44.5)]), RED[1], INK, 1.3)
    v.path(poly([(29, 41), (32.5, 46), (26.5, 44.5)]), RED[2], INK, 1.3)
    v.line([(19, 37.2), (29, 37.2)], W, 1.1, salpha=0.45)
    return v


def res_wood():
    v = Vec()
    r = 8.4
    dx, dy = 7.5, -4.6

    def log(cx, cy):
        v.line([(cx, cy), (cx + dx, cy + dy)], INK, 2 * r + 3.2)
        v.line([(cx, cy), (cx + dx, cy + dy)], Linear(cx, cy - r, cx + dx, cy + r, [WOOD[1], WOOD[2]]), 2 * r)
        v.line([(cx + 2, cy - r * 0.55 + dy * 0.2), (cx + dx + 1, cy - r * 0.55 + dy)], WOOD[0], 1.4, salpha=0.7)
        v.path(circle(cx, cy, r), Radial(cx - 2, cy - 2, r * 1.3, ["#FFE9BD", "#F2CB8C", "#D9A45C"]), INK, 1.8)
        v.path(circle(cx, cy, r * 0.56), None, "#B9803F", 1.2)
        v.path(circle(cx, cy, r * 0.16), "#B9803F")

    log(12.6, 33.4)
    log(29.6, 34.2)
    log(21.0, 19.0)
    return v


def res_stone():
    v = Vec()
    big = [(5.5, 39), (8.5, 24), (18.5, 12.5), (31, 14.5), (38.5, 26.5), (36.5, 39.5)]
    v.path(poly(big), STONE[2], INK_COOL, 2.0)
    v.path(poly([(8.5, 24), (18.5, 12.5), (31, 14.5), (25.5, 24.5), (15, 27)]), vgrad(12, 27, STONE[0], STONE[1]))
    v.path(poly([(31, 14.5), (38.5, 26.5), (36.5, 39.5), (27, 39.5), (25.5, 24.5)]), STONE[3], alpha=0.75)
    v.path(poly([(8.5, 24), (15, 27), (25.5, 24.5), (27, 39.5), (5.5, 39)]), vgrad(24, 40, STONE[1], STONE[2]))
    v.path(poly(big), None, INK_COOL, 2.0)
    v.line([(15, 27), (13.5, 39)], STONE[3], 0.9, salpha=0.6)
    small = [(26.5, 42.5), (28.5, 32.5), (37, 29), (44, 34.5), (43, 42.5)]
    v.path(poly(small), STONE[2], INK_COOL, 2.0)
    v.path(poly([(28.5, 32.5), (37, 29), (44, 34.5), (36, 36.5)]), vgrad(29, 37, STONE[0], STONE[1]))
    v.path(poly([(36, 36.5), (44, 34.5), (43, 42.5), (35, 42.5)]), STONE[3], alpha=0.7)
    v.path(poly(small), None, INK_COOL, 2.0)
    return v


def _coin_stack(v, cx, base, n, rx=11.5, ry=4.3, th=4.6):
    for i in range(n):
        cy = base - i * th
        side = "M%.2f,%.2f v%.2f a%.2f,%.2f 0 0,0 %.2f,0 v%.2f Z" % (cx - rx, cy - th, th, rx, ry, 2 * rx, -th)
        v.path(side, hgrad(cx - rx, cx + rx, GOLD[2], GOLD[1], GOLD[3]), INK, 1.5)
        v.path(ellipse(cx, cy - th, rx, ry), vgrad(cy - th - ry, cy - th + ry, GOLD[0], GOLD[1]), INK, 1.5)
    top = base - n * th
    v.path(ellipse(cx, top, rx * 0.62, ry * 0.6), None, GOLD[2], 1.1)


def res_gold():
    v = Vec()
    _coin_stack(v, 18, 42, 5)
    _coin_stack(v, 32.5, 43, 2, rx=10.5)
    v.path(star(38, 12, 6.5, 1.8, 4), W)
    v.path(star(38, 12, 6.5, 1.8, 4), None, GOLD[1], 0.8)
    return v


def gem_shape(v, colors, ink, cx=24.0, top=7.0, w=34.0, h=36.0):
    """ブリリアントカットの宝石。colors = (最も明るい, 明, 中, 暗, 最も暗い)。"""
    x0, x1 = cx - w / 2, cx + w / 2
    gy = top + h * 0.3
    t0, t1 = (cx - w * 0.26, top), (cx + w * 0.26, top)
    g0, g1, g2, g3 = (x0, gy), (cx - w * 0.19, gy), (cx + w * 0.19, gy), (x1, gy)
    tip = (cx, top + h)
    outline = [g0, t0, t1, g3, tip]
    v.path(poly(outline), colors[2], ink, 2.0)
    v.path(poly([g0, t0, g1]), colors[1])
    v.path(poly([t0, t1, g2, g1]), colors[0])
    v.path(poly([t1, g3, g2]), colors[2])
    v.path(poly([g0, g1, tip]), colors[2])
    v.path(poly([g1, g2, tip]), colors[3])
    v.path(poly([g2, g3, tip]), colors[4])
    v.line([g0, g3], colors[0], 0.9, salpha=0.8)
    v.path(poly(outline), None, ink, 2.0)


def res_gem():
    v = Vec()
    gem_shape(v, ("#F2FDFF", "#A8EEFF", "#4CC3FA", "#2A8CE6", "#1B5DBB"), "#0C2757")
    v.path(star(38.5, 9.5, 6, 1.6, 4), W)
    return v


def power():
    v = Vec()
    pts = []
    for i in range(24):
        r = 22 if i % 2 == 0 else 15
        a = math.radians(-90 + 15 * i)
        pts.append((24 + r * math.cos(a), 24 + r * math.sin(a)))
    v.path(poly(pts), Radial(24, 24, 22, ["#FFE27A", "#FF9A3D", "#E0452B"]), "#6B1508", 1.6)
    # 握りこぶし（ガントレット）
    for x in (15.6, 21.2, 26.8, 32.4):
        v.path(rrect(x - 3.1, 13.5, 6.2, 12, 3), g(GOLD, 13, 26), INK, 1.5)
    v.path(rrect(12.5, 20, 23, 17, 5), g(GOLD, 20, 37), INK, 1.6)
    v.path(rrect(10.5, 25.5, 17, 7.6, 3.8), vgrad(25, 33, GOLD[0], GOLD[1], GOLD[2]), INK, 1.5)
    v.path(rrect(15, 37, 18, 5.5, 2), g(RED, 37, 43), INK, 1.5)
    v.line([(15, 23), (33, 23)], W, 1.0, salpha=0.45)
    return v


# ---- キュー・行動 ------------------------------------------------------------------
def build():
    v = Vec()
    v.begin(rotate=38, px=24, py=24)
    v.path(rrect(21.4, 14, 5.2, 32, 2.2), hgrad(21, 27, WOOD[0], WOOD[1], WOOD[2]), INK, 1.7)
    v.path(rrect(9.5, 3.5, 29, 13.5, 3.2), g(STEEL, 3, 17), INK_COOL, 1.9)
    v.path(rrect(9.5, 3.5, 6.5, 13.5, 3), STEEL[3], alpha=0.5)
    v.path(rrect(32, 3.5, 6.5, 13.5, 3), STEEL[3], alpha=0.35)
    v.line([(13, 6.6), (35, 6.6)], W, 1.3, salpha=0.75)
    v.path(rrect(20.6, 17, 6.8, 3.4, 1.2), g(GOLD, 17, 20.4), INK, 1.3)
    v.end()
    return v


def research():
    v = Vec()
    body = "M20,8 L20,18 L8.6,36.4 Q6,41.6 11.4,42.2 L36.6,42.2 Q42,41.6 39.4,36.4 L28,18 L28,8 Z"
    v.path(body, "#DDF3FF", INK_COOL, 2.0, alpha=0.55)
    liquid = "M14.2,27.5 Q19,25.4 24,27.5 T33.8,27.5 L39.4,36.4 Q42,41.6 36.6,42.2 L11.4,42.2 Q6,41.6 8.6,36.4 Z"
    v.path(liquid, vgrad(26, 42, "#8FF7D6", "#2CCB9B", "#0E8F73"))
    v.path(circle(19.5, 35, 2.2), W, alpha=0.75)
    v.path(circle(27.5, 32.4, 1.5), W, alpha=0.75)
    v.path(circle(30.5, 37.5, 1.2), W, alpha=0.6)
    v.path(circle(24.4, 21, 1.5), "#2CCB9B")
    v.path(circle(22.6, 14.6, 1.1), "#2CCB9B")
    v.line([(22, 10), (22, 17.4), (13.4, 31)], W, 1.2, salpha=0.8)
    v.path(body, None, INK_COOL, 2.0)
    v.path(rrect(17.4, 4, 13.2, 4.6, 2.2), g(STEEL, 4, 9), INK_COOL, 1.7)
    return v


def heal():
    v = Vec()
    heart = "M24,42 C9.5,31.5 4.5,22 8.2,14.2 C11.4,7.6 19.6,7.4 24,13.6 C28.4,7.4 36.6,7.6 39.8,14.2 C43.5,22 38.5,31.5 24,42 Z"
    v.path(heart, vgrad(8, 42, RED[0], RED[1], RED[2]), "#5A0A18", 2.0)
    v.path("M12,16.5 C13,12.6 16.6,11 19.6,12.6", None, W, 1.6, salpha=0.7)
    cross = "M21,15.5 h6 v6 h6 v6 h-6 v6 h-6 v-6 h-6 v-6 h6 Z"
    v.path(cross, W, "#5A0A18", 1.3)
    return v


def troop_infantry():
    v = Vec()
    shield = "M7,11.5 Q21,7 35,11.5 L35,24 Q35,36.5 21,43.5 Q7,36.5 7,24 Z"
    v.path(shield, g(GOLD, 8, 44), INK, 2.0)
    inner = "M10.4,14 Q21,10.6 31.6,14 L31.6,24 Q31.6,34 21,39.6 Q10.4,34 10.4,24 Z"
    v.path(inner, vgrad(11, 40, BLUE[0], BLUE[1], BLUE[2]), "#12306E", 1.2)
    v.path("M21,10.8 L21,39.6", None, W, 1.0, salpha=0.3)
    v.path("M12.6,16 Q16,14.4 19.4,13.9", None, W, 1.4, salpha=0.7)
    v.begin(rotate=38, px=28, py=25, tx=3, ty=0)
    sword(v, cx=28, top=1, length=45)
    v.end()
    return v


def troop_cavalry():
    v = Vec()
    mane = [
        (25.5, 4), (19.5, 8), (21.5, 10.5), (15.5, 14), (18.5, 16.5), (12.5, 21), (16, 23), (10.5, 28.5), (14.5, 30),
        (10, 36), (15, 44), (23, 30), (24, 14),
    ]
    v.path(poly(mane), vgrad(4, 44, "#6B4222", "#3A2210"), INK, 1.8)
    head = (
        "M14.5,44.5 L14.8,37 C15,29 18.5,24 20.5,18.5 C20,13.5 22,8.5 26.5,5.2 L29.2,10.4 "
        "C35,11.4 38.6,16 40.4,21.6 L43.6,28.6 C44.2,31.8 41.6,34.4 38.4,33.2 L33,30.4 "
        "C31,31.4 30.4,33.6 31.2,36.2 C32.2,39.6 34.6,42 36,44.5 Z"
    )
    v.path(head, Linear(16, 8, 40, 44, ["#E0A871", "#B97A43", "#8A5327"]), INK, 2.0)
    v.path("M26.5,5.2 L29.2,10.4 L25,11.5 Z", "#8A5327")
    v.path(circle(33.4, 18.6, 1.9), INK)
    v.path(circle(33.9, 18.1, 0.6), W)
    v.path(ellipse(41, 28.4, 1.0, 1.3), INK)
    v.line([(37.6, 20.8), (33.6, 30.8)], RED[1], 2.2)
    v.line([(33.6, 30.8), (24.5, 25.5)], RED[1], 2.0)
    v.path(circle(33.6, 30.8, 1.7), g(GOLD, 29, 33), INK, 1.0)
    v.path(head, None, INK, 2.0)
    return v


def troop_archer():
    v = Vec()
    v.begin(rotate=-40, px=24, py=24)
    v.line([(15.5, 4.5), (15.5, 43.5)], "#F6E7C1", 1.3)
    bow = "M15.5,4.5 C35,13 35,35 15.5,43.5"
    v.path(bow, None, INK, 6.6)
    v.path(bow, None, Linear(15, 4, 30, 44, [WOOD[0], WOOD[1], WOOD[2]]), 3.8)
    v.path(rrect(27.2, 20.4, 5.2, 7.2, 1.6), g(RED, 20, 28), INK, 1.2)
    v.line([(6.5, 24), (40, 24)], INK, 4.2)
    v.line([(6.5, 24), (40, 24)], WOOD[0], 2.0)
    v.path(poly([(46.5, 24), (37.5, 19), (39.5, 24), (37.5, 29)]), g(STEEL, 19, 29), INK_COOL, 1.5)
    v.path(poly([(4, 24), (7, 19.6), (13, 19.6), (10, 24)]), RED[1], INK, 1.2)
    v.path(poly([(4, 24), (7, 28.4), (13, 28.4), (10, 24)]), RED[2], INK, 1.2)
    v.end()
    return v


def troop_siege():
    v = Vec()
    # 投石機（横から）
    v.line([(13.5, 35), (24, 15.5), (33.5, 35)], INK, 6.0)
    v.line([(13.5, 35), (24, 15.5), (33.5, 35)], WOOD[1], 3.4)
    v.line([(37, 25.5), (9.5, 8.5)], INK, 5.8)
    v.line([(37, 25.5), (9.5, 8.5)], WOOD[0], 3.2)
    v.path(rrect(32.5, 23, 9.5, 9.5, 2), g(STEEL[1:], 23, 33), INK_COOL, 1.6)
    v.path(circle(8.8, 8.2, 5.0), Radial(7.4, 6.6, 6.5, [STONE[0], STONE[1], STONE[2]]), INK_COOL, 1.6)
    v.path(circle(24, 15.5, 2.4), g(GOLD, 13, 18), INK, 1.2)
    v.path(rrect(4.5, 33.5, 39, 5.4, 2.2), g(WOOD, 33, 39), INK, 1.7)
    for cx in (12.5, 35.5):
        v.path(circle(cx, 40.2, 5.4), WOOD[2], INK, 1.7)
        v.path(circle(cx, 40.2, 3.2), WOOD[0], INK, 1.0)
        v.path(circle(cx, 40.2, 1.1), INK)
    return v


# ---- クイックリンク ----------------------------------------------------------------
def mail():
    v = Vec()
    v.path(rrect(4.5, 10.5, 39, 28, 4), g(PARCH, 10, 39), INK, 2.0)
    v.path(poly([(5.5, 37.4), (19, 25), (29, 25), (42.5, 37.4)]), PARCH[2], alpha=0.55)
    v.path("M5.6,12.6 L24,28 L42.4,12.6", None, INK, 1.7)
    v.path(poly([(6.6, 11.6), (41.4, 11.6), (24, 26.4)]), vgrad(11, 27, "#FFFDF0", PARCH[1]))
    v.path("M5.6,12.6 L24,28 L42.4,12.6", None, INK, 1.7)
    v.path(circle(24, 27.6, 6), Radial(22.5, 26, 7, [RED[0], RED[1], RED[2]]), "#5A0A18", 1.6)
    v.path(star(24, 27.6, 3.2, 1.4, 5), GOLD[0])
    return v


def quest():
    v = Vec()
    v.path(rrect(10.5, 8, 27, 32, 2), g(PARCH, 8, 40), INK, 1.8)
    for y in (17.5, 22.5, 27.5):
        v.line([(15.5, y), (32.5, y)], "#9A7A45", 1.5, salpha=0.85)
    v.path(rrect(6, 4.5, 36, 7.4, 3.7), vgrad(4, 12, PARCH[0], PARCH[1], PARCH[2]), INK, 1.8)
    v.path(rrect(6, 36.5, 36, 7.4, 3.7), vgrad(36, 44, PARCH[0], PARCH[1], PARCH[2]), INK, 1.8)
    v.path(ellipse(8.6, 8.2, 1.6, 2.4), PARCH[2], INK, 1.0)
    v.path(ellipse(8.6, 40.2, 1.6, 2.4), PARCH[2], INK, 1.0)
    v.path(poly([(30.5, 33), (30.5, 44.5), (33.6, 42), (36.7, 44.5), (36.7, 33)]), RED[2], INK, 1.2)
    v.path(circle(33.6, 32.5, 5.2), Radial(32.4, 31, 6, [RED[0], RED[1], RED[2]]), "#5A0A18", 1.5)
    v.path(star(33.6, 32.5, 2.8, 1.2, 5), GOLD[0])
    return v


def barracks():
    v = Vec()
    v.begin(rotate=-42, px=24, py=24)
    sword(v, top=1.5, length=45, grip=BLUE)
    v.end()
    v.begin(rotate=42, px=24, py=24)
    sword(v, top=1.5, length=45, grip=RED)
    v.end()
    return v


def chat():
    v = Vec()
    back = [rrect(3.5, 5.5, 27, 19.5, 7), poly([(9.5, 22), (7, 31), (17, 23.5)])]
    for d in back:
        v.path(d, None, INK_COOL, 3.6)
    for d in back:
        v.path(d, vgrad(5, 30, BLUE[0], BLUE[1], BLUE[2]))
    front = [rrect(14.5, 17.5, 30, 21, 7.5), poly([(36, 36), (40.5, 45), (29, 37.5)])]
    for d in front:
        v.path(d, None, INK, 3.6)
    for d in front:
        v.path(d, vgrad(17, 44, "#FFFDF2", PARCH[1]))
    for x in (22.5, 29.5, 36.5):
        v.path(circle(x, 28, 2.3), "#8A6A3A")
    v.path("M8,11 Q9,8.4 13,8.2", None, W, 1.4, salpha=0.7)
    return v


def settings():
    v = Vec()
    n, ro, ri = 8, 20.0, 15.2
    pts = []
    for i in range(n):
        a0 = 360.0 / n * i
        for da, r in ((-13, ri), (-8.5, ro), (8.5, ro), (13, ri)):
            a = math.radians(a0 + da)
            pts.append((24 + r * math.cos(a), 24 + r * math.sin(a)))
    d = poly(pts) + " " + circle(24, 24, 6.2)
    v.path(d, Linear(8, 6, 40, 42, [STEEL[0], STEEL[1], STEEL[2], STEEL[3]]), INK_COOL, 2.0, evenodd=True)
    v.path(circle(24, 24, 10.6), None, STEEL[3], 1.1, salpha=0.7)
    v.path(circle(24, 24, 6.2), None, INK_COOL, 1.6)
    return v


# ---- 下部ナビ ----------------------------------------------------------------------
def nav_city():
    v = Vec()
    stone = ("#FFFBEF", "#E6DDC8", "#B8AC90")
    for x in (4.5, 32.5):
        v.path(rrect(x, 19.5, 11, 23, 1), hgrad(x, x + 11, stone[0], stone[1], stone[2]), INK, 1.8)
        v.path(poly([(x - 1.6, 20), (x + 5.5, 6.5), (x + 12.6, 20)]), hgrad(x, x + 11, BLUE[0], BLUE[1], BLUE[2]), "#12306E", 1.7)
        v.path(rrect(x + 4.2, 26, 2.6, 6, 1.3), "#4A3A2A")
    v.path(rrect(13.5, 23.5, 21, 19, 1), hgrad(13, 35, stone[0], stone[1], stone[2]), INK, 1.8)
    for x in (13.5, 19.2, 24.9, 30.6):
        v.path(rrect(x, 18.6, 3.9, 6, 0.6), stone[0], INK, 1.5)
    v.path("M19.6,42.5 L19.6,34 Q24,27.6 28.4,34 L28.4,42.5 Z", vgrad(29, 43, "#7A4A22", "#3E230E"), INK, 1.5)
    v.line([(24, 31.5), (24, 42)], "#2A1608", 1.0)
    v.line([(24, 18.5), (24, 5)], INK, 1.7)
    v.path(poly([(24, 4.6), (33.5, 8.2), (24, 11.8)]), g(RED, 4, 12), "#5A0A18", 1.4)
    return v


def nav_map():
    v = Vec()
    panels = [
        [(4.5, 13.5), (17, 9), (17, 38.5), (4.5, 43)],
        [(17, 9), (31, 13.5), (31, 43), (17, 38.5)],
        [(31, 13.5), (43.5, 9), (43.5, 38.5), (31, 43)],
    ]
    fills = [vgrad(9, 43, PARCH[0], PARCH[1]), vgrad(9, 43, PARCH[1], PARCH[2]), vgrad(9, 43, PARCH[0], PARCH[1])]
    for p, f in zip(panels, fills):
        v.path(poly(p), f, INK, 1.8)
    v.path(poly([(6.5, 30), (11, 24), (15.5, 31), (15.5, 36.5), (6.5, 39.6)]), GREEN[1], alpha=0.75)
    v.path("M32.5,36 Q37,29 42,32 L42,37.6 L32.5,41 Z", BLUE[1], alpha=0.6)
    for x, y in ((9.5, 22), (14, 19.5), (19, 21.5), (23, 26), (26.5, 30)):
        v.path(circle(x, y, 1.25), "#8A5A2B")
    pin = "M34,4 C39.2,4 42.6,7.8 42.6,12.4 C42.6,18 36.6,22.6 34,27.4 C31.4,22.6 25.4,18 25.4,12.4 C25.4,7.8 28.8,4 34,4 Z"
    v.path(pin, vgrad(4, 27, RED[0], RED[1], RED[2]), "#5A0A18", 1.8)
    v.path(circle(34, 12.4, 3.4), W)
    return v


def nav_alliance():
    v = Vec()
    v.line([(24, 9), (24, 4)], INK, 2.4)
    banner = "M10,9.5 L38,9.5 L38,38.5 L24,31.5 L10,38.5 Z"
    v.path(banner, vgrad(9, 39, RED[0], RED[1], RED[2]), "#5A0A18", 2.0)
    v.path("M13.2,12.6 L34.8,12.6 L34.8,33.4 L24,28 L13.2,33.4 Z", None, GOLD[1], 1.3)
    v.path(star(24, 20.4, 6.6, 2.8, 5), g(GOLD, 13, 27), INK, 1.2)
    v.path(rrect(5.5, 6.2, 37, 4.6, 2.3), g(GOLD, 6, 11), INK, 1.7)
    v.path(circle(5.6, 8.5, 2.9), g(GOLD, 5, 12), INK, 1.5)
    v.path(circle(42.4, 8.5, 2.9), g(GOLD, 5, 12), INK, 1.5)
    v.path(circle(24, 3.6, 2.2), g(GOLD, 1, 6), INK, 1.3)
    return v


def nav_heroes():
    v = Vec()
    plume = "M27,8 C20,-1.5 6.5,1.5 3.5,14.5 C8,9.6 13.6,9.4 17.4,12 C13,13.4 9.6,17.4 8.6,23 C13,16.6 20,12.4 27,8 Z"
    v.path(plume, vgrad(1, 23, RED[0], RED[1], RED[2]), "#5A0A18", 1.8)
    helm = "M10.5,27 C10.5,13.5 19.5,7 27.5,7 C35.5,7 41,13.5 41,24 L41,36 L31,43 L19.5,43 L10.5,36 Z"
    v.path(helm, Linear(12, 8, 40, 42, [STEEL[0], STEEL[1], STEEL[2], STEEL[3]]), INK_COOL, 2.0)
    v.path("M31.6,8 L31.6,42.4", None, W, 1.6, salpha=0.6)
    v.path(rrect(21.5, 21.5, 19.5, 4.2, 2.1), "#10162B")
    v.path("M26.5,25.7 L26.5,35.5 Q31.6,38.4 36.6,35.5 L36.6,25.7 Z", "#10162B", alpha=0.85)
    for x in (29, 31.6, 34.2):
        v.line([(x, 28.4), (x, 34.4)], STEEL[2], 1.0)
    v.path("M10.8,31 L41,31", None, GOLD[1], 1.5, salpha=0.9)
    v.path("M14.5,20 C15.5,14.6 19.4,11.2 24,10", None, W, 1.6, salpha=0.75)
    v.path(helm, None, INK_COOL, 2.0)
    return v


def chest_shape(v, opened=False):
    if opened:
        v.path("M7.5,21 Q5,6.5 16,5 L35,3.4 Q43.5,3.6 41.6,17 Z", vgrad(3, 21, "#8A5327", "#5A3415"), INK, 1.8)
        v.path(ellipse(24, 21.5, 15, 5.2), Radial(24, 20, 16, ["#FFF9D0", GOLD[1], GOLD[2]]), INK, 1.4)
        for x, y in ((16.5, 19.4), (24.5, 17.6), (31.6, 19.6), (20.4, 21.6), (28, 21.8)):
            v.path(ellipse(x, y, 4, 2.2), vgrad(y - 2, y + 2, GOLD[0], GOLD[1]), INK, 1.0)
        gem_shape(v, ("#FFE9F5", "#FFB4DA", "#F0569E", "#C22A78", "#8A1552"), "#4A0A2C", cx=30.5, top=8.5, w=11, h=11)
        v.path(star(14, 9.5, 4.5, 1.3, 4), W)
    else:
        v.path("M5.5,23 Q5.5,8.5 17,8.5 L31,8.5 Q42.5,8.5 42.5,23 Z", vgrad(8, 23, WOOD[0], WOOD[1], WOOD[2]), INK, 1.9)
        for x in (11, 32.5):
            v.path("M%s,9.6 h4.5 v13.4 h-4.5 Z" % x, g(GOLD, 9, 23), INK, 1.2)
    v.path(rrect(5.5, 22, 37, 20.5, 3), vgrad(22, 43, WOOD[1], WOOD[2]), INK, 1.9)
    v.line([(7.5, 31.5), (40.5, 31.5)], "#5A3415", 1.0, salpha=0.6)
    for x in (11, 32.5):
        v.path(rrect(x, 22, 4.5, 20.5, 0.8), g(GOLD, 22, 43), INK, 1.2)
    v.path(rrect(5.5, 21, 37, 4.4, 1.6), g(GOLD, 21, 25.4), INK, 1.5)
    v.path(rrect(20, 22.5, 8, 9.5, 2), g(GOLD, 22, 32), INK, 1.5)
    v.path(circle(24, 26.4, 1.5), INK)
    v.path(rrect(23.3, 26.4, 1.4, 3.6, 0.5), INK)


def nav_shop():
    v = Vec()
    chest_shape(v, opened=True)
    return v


def chest():
    v = Vec()
    chest_shape(v, opened=False)
    v.path(star(38.5, 9, 5.5, 1.5, 4), W)
    return v


ICONS = {
    "res_food": res_food,
    "res_wood": res_wood,
    "res_stone": res_stone,
    "res_gold": res_gold,
    "res_gem": res_gem,
    "power": power,
    "build": build,
    "research": research,
    "heal": heal,
    "troop_infantry": troop_infantry,
    "troop_cavalry": troop_cavalry,
    "troop_archer": troop_archer,
    "troop_siege": troop_siege,
    "mail": mail,
    "quest": quest,
    "barracks": barracks,
    "chat": chat,
    "settings": settings,
    "nav_city": nav_city,
    "nav_map": nav_map,
    "nav_alliance": nav_alliance,
    "nav_heroes": nav_heroes,
    "nav_shop": nav_shop,
    "chest": chest,
}


def all_icons():
    icons = dict(ICONS)
    try:
        import gen_icons2

        icons.update(gen_icons2.ICONS)
    except ImportError:
        pass
    return icons


def write_html(path, icons):
    cells = []
    for name, fn in icons.items():
        v = fn()
        row = "".join(
            '<div class="c s%d">%s</div>' % (size, v.to_svg(size, uid="%s_%d_" % (name, size))) for size in (72, 40, 24, 16)
        )
        cells.append('<div class="cell"><div class="row">%s</div><div class="n">%s</div></div>' % (row, name))
    html = (
        "<html><body style='margin:0;background:#101a36;font:11px sans-serif;color:#cfd6ee'>"
        "<style>.grid{display:grid;grid-template-columns:repeat(6,1fr);gap:6px;padding:8px}"
        ".cell{background:#17254a;border-radius:8px;padding:6px}.row{display:flex;align-items:center;gap:6px}"
        ".n{margin-top:2px}.c{display:flex}</style><div class='grid'>%s</div></body></html>" % "".join(cells)
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)


def main():
    icons = all_icons()
    if "--html" in sys.argv:
        write_html(sys.argv[sys.argv.index("--html") + 1], icons)
    if "--no-write" in sys.argv:
        return
    for name, fn in icons.items():
        with open(os.path.join(OUT_DIR, "ic_%s.xml" % name), "w", encoding="utf-8", newline="\n") as f:
            f.write(fn().to_xml())
    print("wrote %d icons" % len(icons))


if __name__ == "__main__":
    main()
