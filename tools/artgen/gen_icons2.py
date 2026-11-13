"""アイコン一式の続き（gen_icons.py から読み込まれる）。記号・報酬・行動のアイコン。"""

import math

from gen_icons import (
    BLUE, GOLD, GREEN, INK, INK_COOL, K, PARCH, PURPLE, RED, STEEL, STONE, W, WOOD, g, gem_shape, sword,
)
from vec import Linear, Radial, Vec, circle, ellipse, hgrad, poly, rrect, star, vgrad


def lock():
    v = Vec()
    shackle = "M15,23 V16.5 a9,9 0 0,1 18,0 V23"
    v.path(shackle, None, INK_COOL, 7.4, cap="butt")
    v.path(shackle, None, hgrad(13, 35, STEEL[0], STEEL[1], STEEL[2]), 4.2, cap="butt")
    v.path(rrect(9.5, 21.5, 29, 21.5, 5), g(GOLD, 21, 43), INK, 2.0)
    v.line([(13, 25.4), (35, 25.4)], W, 1.3, salpha=0.6)
    v.path(circle(24, 30.4, 3.3), INK)
    v.path(rrect(22.4, 31, 3.2, 7, 1), INK)
    return v


def clock():
    v = Vec()
    v.path(circle(24, 25, 18.5), g(GOLD, 6, 44), INK, 2.0)
    v.path(circle(24, 25, 14.2), Radial(22, 22, 17, ["#FFFFFF", "#F4EEDC", "#D8CDB0"]), INK, 1.4)
    for i in range(12):
        a = math.radians(30 * i)
        r0 = 11.2 if i % 3 else 10.2
        v.line([(24 + r0 * math.sin(a), 25 - r0 * math.cos(a)), (24 + 12.6 * math.sin(a), 25 - 12.6 * math.cos(a))], INK, 1.2)
    v.line([(24, 25), (24, 15.6)], INK, 2.4)
    v.line([(24, 25), (30.6, 28.6)], INK, 2.4)
    v.path(circle(24, 25, 2), RED[1], INK, 1.0)
    v.path(rrect(21.4, 2.5, 5.2, 4.6, 1.6), g(GOLD, 2, 7), INK, 1.5)
    return v


def speedup():
    v = Vec()
    glass = "M13.5,9.5 L34.5,9.5 C34.5,18 27.6,21 26.4,24 C27.6,27 34.5,30 34.5,38.5 L13.5,38.5 C13.5,30 20.4,27 21.6,24 C20.4,21 13.5,18 13.5,9.5 Z"
    v.path(glass, "#DDF3FF", INK_COOL, 1.9, alpha=0.6)
    v.path("M16.6,14 L31.4,14 C30.4,18 26.8,20.6 24,22.6 C21.2,20.6 17.6,18 16.6,14 Z", vgrad(14, 23, GOLD[1], GOLD[2]))
    v.line([(24, 22.6), (24, 33)], GOLD[1], 1.4)
    v.path("M15.4,38 C16,33.6 20,31.6 24,31 C28,31.6 32,33.6 32.6,38 Z", vgrad(31, 38, GOLD[0], GOLD[1], GOLD[2]))
    v.path(glass, None, INK_COOL, 1.9)
    v.line([(17.4, 12), (17.6, 16.4)], W, 1.3, salpha=0.8)
    v.path(rrect(10, 4.5, 28, 5.6, 2.4), g(GOLD, 4, 10), INK, 1.7)
    v.path(rrect(10, 38, 28, 5.6, 2.4), g(GOLD, 38, 44), INK, 1.7)
    return v


def help_hands():
    v = Vec()
    v.path(poly([(1.5, 19), (14, 14.5), (18.5, 30.5), (4, 36.5)]), vgrad(14, 37, BLUE[0], BLUE[1], BLUE[2]), INK_COOL, 1.8)
    v.path(poly([(46.5, 19), (34, 14.5), (29.5, 30.5), (44, 36.5)]), vgrad(14, 37, RED[0], RED[1], RED[2]), "#5A0A18", 1.8)
    skin = ("#FFE4C4", "#F2C39A", "#D69C6E")
    v.path(rrect(12.5, 16.5, 23, 16.5, 6.5), vgrad(16, 33, skin[0], skin[1], skin[2]), INK, 1.8)
    v.path(rrect(15.5, 13.5, 12, 7.5, 3.7), vgrad(13, 21, skin[0], skin[1]), INK, 1.6)
    for x in (24.2, 27.8, 31.4):
        v.line([(x, 22.5), (x, 29.5)], "#A8683C", 1.3)
    v.path(star(24, 7, 5, 1.5, 4), GOLD[0])
    v.path(star(36.5, 40, 4, 1.2, 4), GOLD[0])
    return v


def gift():
    v = Vec()
    v.path(rrect(8, 20, 32, 23, 3), vgrad(20, 43, RED[0], RED[1], RED[2]), "#5A0A18", 1.9)
    v.path(rrect(20.6, 20, 6.8, 23, 0.5), g(GOLD, 20, 43), INK, 1.2)
    for s in (-1, 1):
        cx = 24 + s * 7
        loop = "M24,13.5 C%s,3 %s,3.5 %s,10 C%s,14.5 %s,15.5 24,13.5 Z" % (
            24 + s * 4, 24 + s * 13.5, 24 + s * 13, 24 + s * 12.6, 24 + s * 6,
        )
        v.path(loop, g(GOLD, 4, 15), INK, 1.6)
        v.path(ellipse(cx, 10, 2.6, 1.7), "#A86A0C", alpha=0.55)
    v.path(rrect(5.5, 13.5, 37, 8.4, 2.6), vgrad(13, 22, "#FFA897", RED[1]), "#5A0A18", 1.9)
    v.path(rrect(20.6, 13.5, 6.8, 8.4, 0.5), g(GOLD, 13, 22), INK, 1.2)
    v.path(circle(24, 13, 3.2), g(GOLD, 10, 16), INK, 1.4)
    v.line([(11, 23.6), (11, 39.5)], W, 1.3, salpha=0.35)
    return v


def ticket():
    v = Vec()
    v.begin(rotate=-14, px=24, py=24)
    body = (
        "M7,13 h34 a2.5,2.5 0 0,1 2.5,2.5 v4.6 a3.9,3.9 0 0,0 0,7.8 v4.6 a2.5,2.5 0 0,1 -2.5,2.5 h-34 "
        "a2.5,2.5 0 0,1 -2.5,-2.5 v-4.6 a3.9,3.9 0 0,0 0,-7.8 v-4.6 a2.5,2.5 0 0,1 2.5,-2.5 Z"
    )
    v.path(body, vgrad(13, 35, PURPLE[0], PURPLE[1], PURPLE[2]), "#2A1160", 1.9)
    v.path(rrect(10.5, 16, 27, 16, 1.5), None, GOLD[1], 1.2)
    v.path(star(24, 24, 6.4, 2.7, 5), g(GOLD, 17, 31), INK, 1.2)
    v.line([(8, 16.5), (21, 16.5)], W, 1.1, salpha=0.4)
    v.end()
    return v


def shard():
    v = Vec()
    c = ("#F6E6FF", "#D9B0FF", "#A066F0", "#7038C8", "#4B1F98")
    ink = "#2A1160"

    def crystal(pts, mid):
        v.path(poly(pts), c[2], ink, 1.9)
        v.path(poly([pts[0], pts[1], mid, pts[-1]]), c[1])
        v.path(poly([pts[1], pts[2], pts[3], mid]), c[3])
        v.path(poly([pts[0], pts[1], (pts[0][0] + 1.5, pts[1][1] + 6)]), c[0])
        v.path(poly(pts), None, ink, 1.9)

    crystal([(9, 43), (6, 25), (11.5, 17), (17, 26), (17.5, 43)], (11.6, 43))
    crystal([(30.5, 43), (30, 22), (36, 13.5), (42, 23), (41, 43)], (36, 43))
    crystal([(16, 43.5), (15, 15), (23, 4), (31, 15), (31, 43.5)], (23, 43.5))
    v.path(star(38, 7.5, 5, 1.4, 4), W)
    return v


def star_icon():
    v = Vec()
    v.path(star(24, 25, 20.5, 8.8, 5), g(GOLD, 4, 44), INK, 2.0)
    v.path(star(24, 25, 12.5, 5.4, 5), GOLD[0], alpha=0.75)
    return v


def vip():
    v = Vec()
    crown = "M6.5,36 L4.5,14.5 L15,24 L24,8.5 L33,24 L43.5,14.5 L41.5,36 Z"
    v.path(crown, g(GOLD, 8, 36), INK, 2.0)
    v.path("M9,31 L7.6,18.6 L15.4,26.4 L24,12.4 L24,31 Z", W, alpha=0.3)
    v.path(rrect(6, 33.5, 36, 8, 2.4), vgrad(33, 42, GOLD[1], GOLD[2], GOLD[3]), INK, 1.9)
    for x, col in ((13, BLUE), (24, RED), (35, BLUE)):
        v.path(circle(x, 37.5, 2.5), Radial(x - 0.8, 36.6, 3, [col[0], col[1], col[2]]), INK, 1.1)
    for x, y in ((4.5, 13.5), (24, 7.5), (43.5, 13.5)):
        v.path(circle(x, y, 3), g(GOLD, y - 3, y + 3), INK, 1.5)
    return v


def _badge(v, colors, ink):
    v.path(circle(24, 24, 19), Radial(20, 17, 26, [colors[0], colors[1], colors[2]]), ink, 2.0)
    v.path("M10.5,19 C12.5,12.5 18,8.6 24,8.2", None, W, 1.8, salpha=0.55)


def check():
    v = Vec()
    _badge(v, GREEN, "#0A3D1E")
    v.line([(14.5, 24.5), (21.5, 31.5), (34, 17)], "#0A3D1E", 7.6)
    v.line([(14.5, 24.5), (21.5, 31.5), (34, 17)], W, 4.4)
    return v


def plus():
    v = Vec()
    _badge(v, GREEN, "#0A3D1E")
    for pts in ([(24, 13.5), (24, 34.5)], [(13.5, 24), (34.5, 24)]):
        v.line(pts, "#0A3D1E", 8.2)
    for pts in ([(24, 13.5), (24, 34.5)], [(13.5, 24), (34.5, 24)]):
        v.line(pts, W, 4.8)
    return v


def close():
    v = Vec()
    _badge(v, RED, "#5A0A18")
    for pts in ([(16, 16), (32, 32)], [(32, 16), (16, 32)]):
        v.line(pts, "#5A0A18", 8.0)
    for pts in ([(16, 16), (32, 32)], [(32, 16), (16, 32)]):
        v.line(pts, W, 4.6)
    return v


def arrow_up():
    v = Vec()
    arrow = poly([(24, 4), (42, 23), (31.5, 23), (31.5, 43), (16.5, 43), (16.5, 23), (6, 23)])
    v.path(arrow, vgrad(4, 43, GREEN[0], GREEN[1], GREEN[2]), "#0A3D1E", 2.2)
    v.path(poly([(24, 8.6), (34.6, 20.4), (28.6, 20.4), (28.6, 23.5), (19.4, 23.5), (19.4, 20.4), (13.4, 20.4)]), W, alpha=0.32)
    return v


def info():
    v = Vec()
    _badge(v, BLUE, "#0F2A66")
    v.path(circle(24, 14.4, 3), W)
    v.path(rrect(21.2, 20, 5.6, 15.5, 2), W)
    return v


def alert():
    v = Vec()
    tri = "M24,5 Q26.6,5 28,7.6 L44,37 Q46,42 40.6,42.5 L7.4,42.5 Q2,42 4,37 L20,7.6 Q21.4,5 24,5 Z"
    v.path(tri, vgrad(5, 43, "#FFE27A", "#FFB52E", "#E0701A"), INK, 2.0)
    v.path("M24,9.6 L39.5,38 L8.5,38 Z", None, "#FFF6C8", 1.2, salpha=0.6)
    v.path(rrect(21.4, 16, 5.2, 14.5, 2.4), INK)
    v.path(circle(24, 35.4, 2.9), INK)
    return v


def trophy():
    v = Vec()
    for s in (-1, 1):
        h = "M%s,12 C%s,12 %s,24 %s,25.5" % (24 + s * 11, 24 + s * 22, 24 + s * 20, 24 + s * 9)
        v.path(h, None, INK, 6.2)
        v.path(h, None, GOLD[1], 3.0)
    cup = "M11.5,6.5 L36.5,6.5 C36.5,20 33,29.5 24,31 C15,29.5 11.5,20 11.5,6.5 Z"
    v.path(cup, hgrad(11, 37, GOLD[1], GOLD[0], GOLD[1], GOLD[2]), INK, 2.0)
    v.path(rrect(21, 30.5, 6, 6.5, 1), g(GOLD, 30, 37), INK, 1.5)
    v.path(rrect(13.5, 36.5, 21, 6.5, 2.2), vgrad(36, 43, GOLD[1], GOLD[2], GOLD[3]), INK, 1.9)
    v.path(star(24, 16.5, 5.6, 2.4, 5), "#FFFBE0")
    v.path(rrect(10, 4.5, 28, 4, 2), g(GOLD, 4, 9), INK, 1.6)
    return v


def medal():
    v = Vec()
    v.path(poly([(13, 3.5), (21.5, 3.5), (27.5, 22), (19, 22)]), vgrad(3, 22, BLUE[0], BLUE[1], BLUE[2]), INK_COOL, 1.7)
    v.path(poly([(35, 3.5), (26.5, 3.5), (20.5, 22), (29, 22)]), vgrad(3, 22, RED[0], RED[1], RED[2]), "#5A0A18", 1.7)
    v.path(circle(24, 30.5, 13.5), g(GOLD, 17, 44), INK, 2.0)
    v.path(circle(24, 30.5, 9.8), None, GOLD[3], 1.1, salpha=0.8)
    v.path(star(24, 30.6, 7, 3, 5), "#FFFBE0", GOLD[3], 0.9)
    return v


def flag():
    v = Vec()
    v.line([(11, 5), (11, 45)], INK, 4.6)
    v.line([(11, 5), (11, 45)], hgrad(9, 13, WOOD[0], WOOD[1]), 2.4)
    pennant = "M12.5,7.5 C22,3.5 28,12 42.5,8 L37.5,18.5 L42.5,29 C28,33 22,24.5 12.5,28.5 Z"
    v.path(pennant, vgrad(5, 31, RED[0], RED[1], RED[2]), "#5A0A18", 1.9)
    v.path("M14.5,10.5 C21,8 27,13.4 36,12", None, W, 1.4, salpha=0.45)
    v.path(circle(11, 4.6, 2.6), g(GOLD, 2, 7), INK, 1.4)
    return v


def scout():
    v = Vec()
    eye = "M3.5,24 C11,11.5 37,11.5 44.5,24 C37,36.5 11,36.5 3.5,24 Z"
    v.path(eye, vgrad(13, 35, "#FFFFFF", "#DCE6F5"), INK_COOL, 2.0)
    v.path(circle(24, 24, 9.4), Radial(22, 21, 11, [BLUE[0], BLUE[1], BLUE[2]]), INK_COOL, 1.6)
    v.path(circle(24, 24, 4.4), "#0B1226")
    v.path(circle(21.4, 21, 2.2), W)
    v.path(eye, None, INK_COOL, 2.0)
    for x0, y0, x1, y1 in ((10, 12.6, 8, 8.6), (24, 9.6, 24, 5), (38, 12.6, 40, 8.6)):
        v.line([(x0, y0), (x1, y1)], INK_COOL, 2.0)
    return v


def attack():
    v = Vec()
    slash = "M6,40 C10,22 24,9 43,6.5 C29,13 17.5,24 13.5,42 Z"
    v.path(slash, Linear(6, 40, 43, 6, ["#FFE27A", "#FF8A3D", "#E0352B"]), "#6B1508", 1.6)
    v.begin(rotate=42, px=24, py=24)
    sword(v, top=1.5, length=45, grip=RED)
    v.end()
    return v


def gather():
    v = Vec()
    v.begin(rotate=-38, px=24, py=24)
    v.path(rrect(21.6, 9, 4.8, 37, 2.2), hgrad(21, 27, WOOD[0], WOOD[1], WOOD[2]), INK, 1.7)
    head = "M2.5,17.5 C10,5.5 38,5.5 45.5,17.5 C36,11.5 30,11 28,11.5 L28,15.5 L20,15.5 L20,11.5 C18,11 12,11.5 2.5,17.5 Z"
    v.path(head, vgrad(6, 18, STEEL[0], STEEL[1], STEEL[2]), INK_COOL, 1.9)
    v.path(rrect(19.6, 8.6, 8.8, 7.4, 1.6), g(GOLD, 8, 16), INK, 1.4)
    v.end()
    return v


def rally():
    v = Vec()
    horn = "M5.5,29 C7.5,20 20,9.5 37.5,7.5 L42.5,18.5 C30,18.5 17.5,25 14.5,36.5 Z"
    v.path(horn, Linear(8, 30, 40, 8, ["#FFF7E0", "#F1D9A6", "#C9A066"]), INK, 2.0)
    v.path(ellipse(40.2, 12.8, 3.4, 6.4), "#6B4A22", INK, 1.6)
    v.begin(rotate=-25, px=10, py=32.5)
    v.path(rrect(5, 29, 10, 7.4, 2.4), g(GOLD, 29, 37), INK, 1.5)
    v.end()
    v.line([(18.5, 17.6), (23.5, 27)], GOLD[2], 2.4)
    v.line([(28, 12), (32, 20.5)], GOLD[2], 2.4)
    for r in (5, 9.5):
        v.path("M%s,%s a%s,%s 0 0,1 0,%s" % (40 + r * 0.25, 35 - r, r, r, 2 * r), None, GOLD[1], 2.0)
    return v


def wheel():
    v = Vec()
    v.path(circle(24, 26, 19), g(GOLD, 7, 45), INK, 2.0)
    cols = [RED[1], "#FFD34D", BLUE[1], GREEN[1], PURPLE[1], "#FF9A3D", "#35C9C2", "#F0569E"]
    r = 15.2
    for i, c in enumerate(cols):
        a0, a1 = math.radians(45 * i - 90), math.radians(45 * (i + 1) - 90)
        d = "M24,26 L%.2f,%.2f A%.2f,%.2f 0 0,1 %.2f,%.2f Z" % (
            24 + r * math.cos(a0), 26 + r * math.sin(a0), r, r, 24 + r * math.cos(a1), 26 + r * math.sin(a1),
        )
        v.path(d, c, INK, 0.9)
    v.path(circle(24, 26, 15.2), None, INK, 1.4)
    v.path(circle(24, 26, 4), g(GOLD, 22, 30), INK, 1.4)
    v.path(poly([(19, 2.5), (29, 2.5), (24, 13)]), vgrad(2, 13, RED[0], RED[1]), "#5A0A18", 1.6)
    return v


def piggy():
    v = Vec()
    pink = ("#FFD6E4", "#FF9DBD", "#E8608F")
    ink = "#6B1838"
    for x in (15, 29):
        v.path(rrect(x, 35, 6, 8.5, 2), pink[2], ink, 1.7)
    v.path(ellipse(23, 27, 17, 13), Radial(18, 20, 22, [pink[0], pink[1], pink[2]]), ink, 2.0)
    v.path(poly([(11, 13.5), (13.5, 21), (19.5, 17)]), pink[1], ink, 1.7)
    v.path(rrect(36, 22.5, 9, 9, 3.6), vgrad(22, 32, pink[0], pink[1]), ink, 1.8)
    v.path(circle(39.2, 27, 0.9), ink)
    v.path(circle(42, 27, 0.9), ink)
    v.path(circle(31.5, 21.6, 1.5), ink)
    v.path(rrect(18.5, 15.2, 9, 2.4, 1.2), ink)
    v.path("M6,26 C2.5,24 3.5,20 6.5,21.5", None, ink, 1.8)
    v.path(ellipse(23, 9.2, 6, 6), g(GOLD, 3, 15), INK, 1.6)
    v.path(ellipse(23, 9.2, 3.4, 3.4), None, GOLD[3], 1.0)
    return v


def calendar():
    v = Vec()
    v.path(rrect(6, 9, 36, 34, 4), vgrad(9, 43, "#FFFFFF", "#E9E1CC"), INK, 2.0)
    v.path("M6,19 V13 a4,4 0 0,1 4,-4 H38 a4,4 0 0,1 4,4 V19 Z", vgrad(9, 19, RED[0], RED[1]), "#5A0A18", 2.0)
    for x in (15, 33):
        v.path(rrect(x - 2, 4.5, 4, 9.5, 2), g(STEEL, 4, 14), INK_COOL, 1.4)
    v.line([(15.5, 30.5), (21.5, 36.5), (33, 24.5)], "#0A3D1E", 6.4)
    v.line([(15.5, 30.5), (21.5, 36.5), (33, 24.5)], GREEN[1], 3.6)
    return v


def skin():
    v = Vec()
    pal = "M24,5.5 C36,5.5 44.5,13.5 44.5,23.5 C44.5,31 38.5,33 33.5,31.5 C29,30.2 26.5,33.5 28.5,37 C30.6,40.8 28.5,43.5 23,43.5 C12,43.5 3.5,35 3.5,24.5 C3.5,14 12.5,5.5 24,5.5 Z"
    v.path(pal, Linear(6, 8, 40, 42, [WOOD[0], "#D49A5A", WOOD[1]]), INK, 2.0)
    for x, y, c in ((14, 17, RED), (24.5, 12.5, GOLD), (35, 18, GREEN), (11.5, 28.5, BLUE), (17.5, 37, PURPLE)):
        v.path(circle(x, y, 3.9), Radial(x - 1, y - 1.2, 4.6, [c[0], c[1], c[2]]), INK, 1.2)
    return v


def book():
    v = Vec()
    v.path(rrect(8, 6, 32, 37, 3), vgrad(6, 43, BLUE[0], BLUE[1], BLUE[2]), "#0F2A66", 2.0)
    v.path(rrect(11.5, 37.5, 28.5, 5.5, 1.2), vgrad(37, 43, "#FFFFFF", "#D9D2BE"), INK, 1.4)
    v.path(rrect(8, 6, 5.4, 37, 2), BLUE[2], alpha=0.6)
    v.path(rrect(16.5, 10.5, 20, 23.5, 1.5), None, GOLD[1], 1.3)
    v.path(star(26.5, 22, 6.6, 2.8, 5), g(GOLD, 15, 29), INK, 1.1)
    return v


def bag():
    v = Vec()
    v.path("M17,12 C17,4 31,4 31,12", None, INK, 5.6)
    v.path("M17,12 C17,4 31,4 31,12", None, WOOD[1], 2.6)
    v.path(rrect(7.5, 11.5, 33, 32, 8), vgrad(11, 44, WOOD[0], WOOD[1], WOOD[2]), INK, 2.0)
    v.path("M7.5,23 V19.5 a8,8 0 0,1 8,-8 H32.5 a8,8 0 0,1 8,8 V23 Q24,30.5 7.5,23 Z", vgrad(11, 28, "#EDB878", WOOD[1]), INK, 1.8)
    v.path(rrect(20.4, 22, 7.2, 8.6, 1.8), g(GOLD, 22, 31), INK, 1.5)
    v.path(rrect(13, 33.5, 22, 6.5, 2), WOOD[2], INK, 1.3, alpha=0.8)
    return v


def shield():
    v = Vec()
    sh = "M24,4 L41.5,10 V23 C41.5,33.5 34.5,40.5 24,44.5 C13.5,40.5 6.5,33.5 6.5,23 V10 Z"
    v.path(sh, g(GOLD, 4, 45), INK, 2.0)
    v.path("M24,8.4 L37.6,13 V23 C37.6,31.4 32,37 24,40.2 C16,37 10.4,31.4 10.4,23 V13 Z", vgrad(8, 40, BLUE[0], BLUE[1], BLUE[2]), "#0F2A66", 1.3)
    v.path("M24,8.4 V40.2 C16,37 10.4,31.4 10.4,23 V13 Z", W, alpha=0.18)
    v.line([(16.5, 23.5), (22, 29), (32, 17.5)], W, 4.2)
    return v


def key():
    v = Vec()
    v.begin(rotate=-40, px=24, py=24)
    v.path(rrect(21.6, 20, 4.8, 25, 1.6), g(GOLD, 20, 45), INK, 1.7)
    v.path(rrect(26, 34, 6.5, 4, 1), g(GOLD, 34, 38), INK, 1.5)
    v.path(rrect(26, 40, 5, 4, 1), g(GOLD, 40, 44), INK, 1.5)
    v.path(circle(24, 13, 10) + " " + circle(24, 12, 4), g(GOLD, 3, 23), INK, 1.9, evenodd=True)
    v.end()
    return v


def bell():
    v = Vec()
    body = "M24,6.5 C32.5,6.5 36.5,13 36.5,22 C36.5,30 39.5,33 42.5,36 L5.5,36 C8.5,33 11.5,30 11.5,22 C11.5,13 15.5,6.5 24,6.5 Z"
    v.path(circle(24, 39.5, 4.6), g(GOLD[1:], 36, 44), INK, 1.7)
    v.path(body, hgrad(8, 40, GOLD[1], GOLD[0], GOLD[1], GOLD[2]), INK, 2.0)
    v.path(rrect(4.5, 33.5, 39, 4.6, 2.3), g(GOLD, 33, 38), INK, 1.6)
    v.path(circle(24, 5.6, 2.6), g(GOLD, 3, 8), INK, 1.4)
    return v


def music():
    v = Vec()
    stem = [(17.5, 35.5), (17.5, 10.5), (38.5, 5.5), (38.5, 31)]
    v.line(stem[:2], INK_COOL, 5.4)
    v.line(stem[2:], INK_COOL, 5.4)
    beam = poly([(15.5, 8), (40.5, 2.6), (40.5, 12.5), (15.5, 17.9)])
    v.path(beam, PURPLE[1], INK_COOL, 1.8)
    v.line(stem[:2], PURPLE[1], 2.6)
    v.line(stem[2:], PURPLE[1], 2.6)
    for cx, cy in ((12.5, 37), (33.5, 32.5)):
        v.begin(rotate=-20, px=cx, py=cy)
        v.path(ellipse(cx, cy, 7, 5.2), Radial(cx - 2, cy - 2, 8, [PURPLE[0], PURPLE[1], PURPLE[2]]), INK_COOL, 1.8)
        v.end()
    return v


def sparkle():
    v = Vec()
    v.path(star(21, 25, 19.5, 7, 4), Radial(21, 25, 20, ["#FFFFFF", GOLD[0], GOLD[1]]), INK, 1.9)
    v.path(star(38.5, 9.5, 8, 2.8, 4), Radial(38.5, 9.5, 8, ["#FFFFFF", GOLD[0]]), INK, 1.5)
    v.path(star(39, 39, 5.5, 1.6, 4), "#FFFFFF", INK, 1.3)
    return v


def troops():
    v = Vec()

    def helm(cx, cy, s, col):
        v.begin(tx=cx - 24 * s, ty=cy - 24 * s, sx=s, sy=s)
        v.path("M10,30 C10,14 17,7 24,7 C31,7 38,14 38,30 L38,38 L28.5,38 L28.5,27 L19.5,27 L19.5,38 L10,38 Z", Linear(10, 8, 38, 38, [STEEL[0], STEEL[1], STEEL[2]]), INK_COOL, 2.2 / s * 0.8)
        v.path(poly([(24, 1), (27.4, 8), (20.6, 8)]), col[1], INK, 1.5)
        v.path("M24,8 V27", None, W, 1.6, salpha=0.5)
        v.end()

    helm(13.5, 19, 0.62, BLUE)
    helm(34.5, 19, 0.62, BLUE)
    helm(24, 28, 0.86, RED)
    return v


def back():
    v = Vec()
    arrow = poly([(4, 24), (22, 7), (22, 17), (43, 17), (43, 31), (22, 31), (22, 41)])
    v.path(arrow, vgrad(7, 41, GOLD[0], GOLD[1], GOLD[2]), INK, 2.2)
    v.path(poly([(9.5, 23), (19.4, 13.6), (19.4, 19.6), (40.4, 19.6), (40.4, 22.6)]), W, alpha=0.35)
    return v


def world():
    v = Vec()
    v.path(circle(24, 24, 19), Radial(19, 17, 26, [BLUE[0], BLUE[1], BLUE[2]]), "#0F2A66", 2.0)
    land = ("#B6F29A", GREEN[1])
    v.path("M13,11.5 C18,8.5 23.5,10.5 22,15.5 C20.5,20 16.5,19.5 15.5,24.5 C14.6,28.6 10.5,27.5 8,23.5 C6.6,18.6 9,14 13,11.5 Z", vgrad(9, 29, land[0], land[1]), "#0A3D1E", 1.1)
    v.path("M29,17.5 C33.5,15 39.5,18.5 41,24 C41.6,30 38.5,36 33,38.5 C29.5,36 31.5,32 28.5,29.5 C25.6,27 25.5,20 29,17.5 Z", vgrad(16, 39, land[0], land[1]), "#0A3D1E", 1.1)
    v.path("M16,34.5 C19,32.5 23.5,34 23,37.5 C22.6,40.4 19,41.4 16.5,39.6 Z", land[1], "#0A3D1E", 1.0)
    v.path("M10.5,17.5 C13,11.5 18.5,8.4 24,8", None, W, 1.8, salpha=0.5)
    v.path(circle(24, 24, 19), None, "#0F2A66", 2.0)
    return v


def tent():
    v = Vec()
    v.path(poly([(24, 6.5), (44.5, 41.5), (3.5, 41.5)]), vgrad(6, 42, RED[0], RED[1], RED[2]), "#5A0A18", 2.0)
    v.path(poly([(24, 6.5), (31.5, 41.5), (16.5, 41.5)]), "#3A1408")
    v.path(poly([(24, 6.5), (24, 41.5), (16.5, 41.5)]), "#5A2410")
    v.line([(24, 6.5), (24, 1.6)], INK, 1.8)
    v.path(poly([(24, 1.4), (31, 3.6), (24, 5.8)]), GOLD[1], INK, 1.1)
    v.line([(12.5, 27), (9, 41)], W, 1.2, salpha=0.3)
    return v


def skull():
    v = Vec()
    v.path("M24,5 C35,5 42,12.5 42,22.5 C42,28.5 39.5,32 36,34 L36,40.5 Q36,43 33.5,43 L14.5,43 Q12,43 12,40.5 L12,34 C8.5,32 6,28.5 6,22.5 C6,12.5 13,5 24,5 Z", vgrad(5, 43, "#FFFFFF", "#E8E2D0", "#B9AF96"), INK, 2.0)
    for x in (16.6, 31.4):
        v.path(ellipse(x, 23, 4.8, 5.4), "#231509")
    v.path(poly([(24, 27.5), (26.6, 33), (21.4, 33)]), "#231509")
    for x in (19, 24, 29):
        v.line([(x, 37.5), (x, 42.5)], INK, 1.5)
    return v


def exp():
    v = Vec()
    pts = [(24, 3.5), (29, 17), (43.5, 18), (32, 27), (36, 41.5), (24, 33), (12, 41.5), (16, 27), (4.5, 18), (19, 17)]
    v.path(poly(pts), vgrad(3, 42, GREEN[0], GREEN[1], GREEN[2]), "#0A3D1E", 2.0)
    v.path(poly([(24, 8), (27, 19), (19.4, 19.6)]), W, alpha=0.35)
    return v


ICONS = {
    "lock": lock,
    "clock": clock,
    "speedup": speedup,
    "help": help_hands,
    "gift": gift,
    "ticket": ticket,
    "shard": shard,
    "star": star_icon,
    "vip": vip,
    "check": check,
    "plus": plus,
    "close": close,
    "arrow_up": arrow_up,
    "info": info,
    "alert": alert,
    "trophy": trophy,
    "medal": medal,
    "flag": flag,
    "scout": scout,
    "attack": attack,
    "gather": gather,
    "rally": rally,
    "wheel": wheel,
    "piggy": piggy,
    "calendar": calendar,
    "skin": skin,
    "book": book,
    "bag": bag,
    "shield": shield,
    "key": key,
    "bell": bell,
    "music": music,
    "sparkle": sparkle,
    "troops": troops,
    "back": back,
    "world": world,
    "tent": tent,
    "skull": skull,
    "exp": exp,
}

_ = (K, PARCH, STONE)  # 共有パレット（未使用の警告を避ける）
