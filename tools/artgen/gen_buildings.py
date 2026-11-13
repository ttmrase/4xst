#!/usr/bin/env python3
"""建物スプライト（18 種 x 5 段階）を VectorDrawable の XML として生成する（標準ライブラリのみ）。

実行: python3 tools/artgen/gen_buildings.py
出力: app/src/main/res/drawable/bld_<type>_t<tier>.xml（type は buildings.json の type、tier は 1..5）
段階: 1 = Lv1-5 / 2 = Lv6-10 / 3 = Lv11-15 / 4 = Lv16-20 / 5 = Lv21-25。段階が上がるほど
屋根・旗・塔・装飾が増え、材質が 木と茅 → 木組みと瓦 → 切り石とスレート → 白い石と青い屋根 → 大理石と金 へ豪華になる。
絵柄: 面はグラデーション（壁は上が明るく足元が暗い、屋根は棟が明るく軒が暗い）、外形は太い縁取り、
足元に接地の影と右下への落ち影、窓にはあかり。区画（4 x 4）と高さは段階ごとに固定で、マップ上の位置は変わらない。
出力は決定論的（乱数なし）。再実行しても同じ結果になる。
"""

import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from iso import Scene, P, U, shade, mix, lit, shd, edge, vgrad, grad_perp, Linear, Radial, with_alpha  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"

INK = "#2B1D2B"  # 外形の縁取り
GLOW = "#FFC14A"  # 窓あかりのにじみ

# 材質（段階ごと）。style: wood = 板壁と茅 / timber = 漆喰の木組みと瓦 / stone = 切り石とスレート /
# white = 白い石と青い瓦 / marble = 大理石と金。
MAT = {
    1: dict(style="wood", wall="#AD6E3E", roof="#E3C263", trim="#6B4426", base="#A0978A", pad="#947C4E",
            win="#3B2A22", lit="#FFD66B", gold="#D8A83C", banner="#C43A2E", frame="#5E3C20", door="#7C4F28"),
    2: dict(style="timber", wall="#EFDFB8", roof="#CE583A", trim="#74492A", base="#A59D90", pad="#A18D62",
            win="#3B2A22", lit="#FFDA72", gold="#D8A83C", banner="#2F66B8", frame="#6A4426", door="#7C4F28"),
    3: dict(style="stone", wall="#C0BBAE", roof="#4A6699", trim="#7C766C", base="#938C80", pad="#A4A195",
            win="#2B2436", lit="#FFDE7A", gold="#DDB04A", banner="#C0303C", frame="#8F887B", door="#6C4728"),
    4: dict(style="white", wall="#E9E3D5", roof="#2F60C4", trim="#DDB64C", base="#AFA89A", pad="#C0BCAE",
            win="#2A2238", lit="#FFE389", gold="#E6BC4C", banner="#C8303E", frame="#C7BFAC", door="#603F26"),
    5: dict(style="marble", wall="#FCF8EE", roof="#EFBE34", trim="#F4CE58", base="#E0D8C5", pad="#EDE7D7",
            win="#2E2440", lit="#FFF0A6", gold="#F5CB50", banner="#7B2FB2", frame="#E3BB48", door="#6C3F22"),
}

TYPES = [
    "castle", "farm", "sawmill", "quarry", "gold_mine", "warehouse", "barracks", "archery_range", "stable",
    "siege_workshop", "hospital", "academy", "wall", "watchtower", "trading_post", "alliance_center", "tavern",
    "builders_hut",
]

WOOD = "#A87B45"      # 木箱・板
WOOD_DK = "#7A5530"   # 丸太・杭
STRAW = "#E3C262"     # 干し草
IRON = "#5A5A64"


def new_scene(w=128, h=160):
    """建物・装飾の Scene（外形の縁取りと落ち影つき、XML は詰めて書く）。"""
    return Scene(w, h, outline=INK, ow=1.1, cast=True, compact=True)


# ---- 共通部品 -----------------------------------------------------------------

def Lf(y1):
    """左前面（y = y1）の上の点 (a, z) -> 画面座標。"""
    return lambda a, z: P(a, y1, z)


def Rf(x1):
    """右前面（x = x1）の上の点 (a, z) -> 画面座標。"""
    return lambda a, z: P(x1, a, z)


def ring(xc, yc, r, z, n=8):
    """円筒の手前の半円（帯・たが）。"""
    return [P(xc + r * math.cos(math.radians(135 - 180.0 * i / n)), yc + r * math.sin(math.radians(135 - 180.0 * i / n)), z)
            for i in range(n + 1)]


def _boxf(x0, y0, x1, y1, z0, z1):
    r = [P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1), P(x1, y0, z1)]
    lf = [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)]
    tp = [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)]
    hl = [P(x0, y1, z0), P(x1, y1, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1), P(x0, y1, z1)]
    return r, lf, tp, hl


def boxes(sc, items, c, top=None, sil=True, cast=False):
    """小さな直方体 [(x0, y0, x1, y1, z0, z1), ...] をまとめて 3 本のパス（右面・左面・上面）で描く。"""
    if not items:
        return
    rs, ls, ts = [], [], []
    for b in items:
        r, lf, tp, hl = _boxf(*b)
        rs.append(r)
        ls.append(lf)
        ts.append(tp)
        if sil:
            sc.sil(hl)
        if cast:
            sc.cast_box(*b)
    st = edge(c)
    sc.polys(rs, shd(c), st, 0.4)
    sc.polys(ls, c, st, 0.4)
    sc.polys(ts, top or lit(c, 1.15), st, 0.4)


def base_pad(sc, m, t, x0=0.0, y0=0.0, x1=4.0, y1=4.0, cast=True):
    """足元のパッド（土 → 石畳 → 大理石）。厚みのある縁と、右下へのうすい影つき。"""
    pad = m["pad"]
    a, b, c, d = P(x0, y0), P(x1, y0), P(x1, y1), P(x0, y1)  # 奥・右・手前・左の角
    lip = 1.8 if t >= 3 else 1.2
    sc.poly([(a[0] + 2.2, a[1] + 2.4), (b[0] + 3.0, b[1] + 2.4), (c[0] + 2.2, c[1] + 3.6), (d[0] + 1.0, d[1] + 2.4)],
            "#1B1430", None, 0, 0.24)
    sc.poly([d, c, b, (b[0], b[1] + lip), (c[0], c[1] + lip), (d[0], d[1] + lip)], shd(pad, 0.62), edge(pad, 0.5), 0.6)
    sc.poly([a, b, c, d], vgrad(a[1], c[1], lit(pad, 1.12), shade(pad, 0.9)), edge(pad, 0.55), 0.5)
    w, hh = x1 - x0, y1 - y0
    if t >= 3:  # 敷石の目地
        segs = []
        for i in range(1, 4):
            segs.append([P(x0 + w * i / 4, y0, 0), P(x0 + w * i / 4, y1, 0)])
            segs.append([P(x0, y0 + hh * i / 4, 0), P(x1, y0 + hh * i / 4, 0)])
        sc.lines(segs, shade(pad, 0.72), 0.4, 0.5)
    else:  # 土のむら・小石
        sc.ovals([(P(x0 + w * fx, y0 + hh * fy)[0], P(x0 + w * fx, y0 + hh * fy)[1], rx, rx * 0.5)
                  for fx, fy, rx in ((0.2, 0.86, 5.0), (0.88, 0.3, 4.0), (0.62, 0.92, 3.2), (0.1, 0.4, 3.0), (0.93, 0.74, 3.6))],
                 shade(pad, 0.78), None, 0, 0.4)
    k = 0.1
    inner = [P(x0 + k, y0 + k, 0), P(x1 - k, y0 + k, 0), P(x1 - k, y1 - k, 0), P(x0 + k, y1 - k, 0), P(x0 + k, y0 + k, 0)]
    if t >= 5:
        sc.line(inner, m["gold"], 1.1)
    elif t >= 4:
        sc.line(inner, lit(pad, 1.3), 0.7, 0.8)
    if cast:
        sc.cast_here()


def ground(sc, x0, y0, x1, y1, c, edge_c=None, grad=True):
    """地面の区画（畑・草地・掘り込み）。"""
    sc.pad(x0, y0, x1, y1, c, edge_c or shade(c, 0.62), grad=grad)


def contact(sc, x0, y0, x1, y1, alpha=0.34, grow=0.22):
    """立体の足元のやわらかい接地の影。"""
    sc.soft_shadow(x0, y0, x1, y1, alpha, grow)


def wall_tex(sc, m, x0, y0, x1, y1, z0, z1, wall=None, left=True, right=True):
    """壁の材質の細部（板目・木組み・石の目地・金の帯）。面ごとに 1〜2 本のパスにまとめる。"""
    style = m["style"]
    wall = wall or m["wall"]
    lf, rf = Lf(y1), Rf(x1)
    hh = z1 - z0
    faces = []
    if right:
        faces.append((rf, y0, y1, True))
    if left:
        faces.append((lf, x0, x1, False))
    for fn, lo, hi, dark in faces:
        span = hi - lo
        ink = shd(wall, 0.5) if dark else shade(wall, 0.68)
        trim = shd(m["trim"], 0.78) if dark else m["trim"]
        if style == "wood":
            n = max(2, int(round(span / 0.3)) - 1)
            sc.lines([[fn(lo + span * i / (n + 1.0), z0), fn(lo + span * i / (n + 1.0), z1)] for i in range(1, n + 1)],
                     ink, 0.4, 0.5)
            sc.lines([[fn(lo, z0), fn(lo, z1)], [fn(hi, z0), fn(hi, z1)]], trim, 1.0)
        elif style == "timber":
            zm = z0 + hh * 0.5
            k = min(0.34, span * 0.22)
            sc.lines([[fn(lo, z0), fn(lo, z1)], [fn(hi, z0), fn(hi, z1)], [fn(lo, zm), fn(hi, zm)],
                      [fn(lo, z0), fn(lo + k, zm)], [fn(hi, z0), fn(hi - k, zm)]], trim, 1.0)
        else:
            step = {"stone": 4.6, "white": 6.2, "marble": 8.5}[style]
            alpha = {"stone": 0.55, "white": 0.36, "marble": 0.24}[style]
            segs = []
            k = 1
            while z0 + k * step < z1 - 2.5:
                z = z0 + k * step
                segs.append([fn(lo, z), fn(hi, z)])
                if style == "stone":  # 縦の目地（段ごとにずらす）
                    nj = max(1, int(span / 0.9))
                    for j in range(nj):
                        a = lo + span * ((j + 0.5 + 0.37 * ((k * 7 + j * 3) % 3 - 1)) / nj)
                        segs.append([fn(a, z), fn(a, z - step)])
                k += 1
            sc.lines(segs, ink, 0.4, alpha)
            if style == "marble":
                sc.lines([[fn(lo, z0 + 1.4), fn(hi, z0 + 1.4)], [fn(lo, z1 - 4.4), fn(hi, z1 - 4.4)]],
                         shd(m["gold"], 0.86) if dark else m["gold"], 1.0)
            elif style == "white":
                sc.lines([[fn(lo, z1 - 4.2), fn(hi, z1 - 4.2)]], shd(m["gold"], 0.86) if dark else m["gold"], 0.8)


def pane(fn, a, z, w, h, arch=False):
    """窓の形（四角、または上が丸いアーチ）。"""
    if not arch:
        return [fn(a - w / 2, z - h / 2), fn(a + w / 2, z - h / 2), fn(a + w / 2, z + h / 2), fn(a - w / 2, z + h / 2)]
    rz = min(h * 0.4, w * U * 0.55)
    zt = z + h / 2 - rz
    pts = [fn(a - w / 2, z - h / 2), fn(a + w / 2, z - h / 2)]
    for i in range(5):
        th = math.pi * i / 4
        pts.append(fn(a + (w / 2) * math.cos(th), zt + rz * math.sin(th)))
    return pts


def window_set(sc, m, fn, items, w, wh, dark=False, arch=None, glow=True):
    """1 つの面の窓をまとめて描く（にじみ・窓・桟で 3 本のパス）。items は [(a, z), ...]。"""
    if not items:
        return
    arch = m["style"] in ("stone", "white", "marble") if arch is None else arch
    panes, glows, bars = [], [], []
    for a, z in items:
        panes.append(pane(fn, a, z, w, wh, arch))
        c = fn(a, z)
        glows.append((c[0], c[1], w * U * 0.5 + 2.6, wh * 0.5 + 2.6))
        bars.append([fn(a, z - wh / 2), fn(a, z + wh / 2)])
        bars.append([fn(a - w / 2, z + wh * 0.06), fn(a + w / 2, z + wh * 0.06)])
    fr = shd(m["frame"], 0.8) if dark else m["frame"]
    if glow:
        sc.ovals([(x, y, rx + 1.6, ry + 1.6) for x, y, rx, ry in glows], GLOW, None, 0, 0.14)
        sc.ovals([(x, y, rx - 0.8, ry - 0.8) for x, y, rx, ry in glows], GLOW, None, 0, 0.2 if dark else 0.16)
    sc.polys(panes, shade(m["lit"], 0.97) if dark else m["lit"], fr, 0.8)
    sc.lines(bars, fr, 0.45, 0.9)


def windows(sc, m, t, x0, y0, x1, y1, h, nl=2, nr=2, z=None, w=0.5, wh=9.0, door=True, zb=4.0):
    hh = h - zb
    zs = [z] if z is not None else ([zb + hh * 0.52] if hh < 36 else [zb + hh * 0.34, zb + hh * 0.72])
    wh = min(wh, hh * 0.44)
    left, right = [], []
    for zi, zz in enumerate(zs):
        for i in range(nl):
            if door and zi == 0 and nl % 2 == 1 and i == nl // 2:
                continue
            left.append((x0 + (x1 - x0) * (i + 1) / (nl + 1.0), zz))
        for i in range(nr):
            right.append((y0 + (y1 - y0) * (i + 1) / (nr + 1.0), zz))
    window_set(sc, m, Rf(x1), right, w, wh, dark=True)
    window_set(sc, m, Lf(y1), left, w, wh)
    if door:
        sc.door_left((x0 + x1) / 2, 0.8, min(zb + hh * 0.55, 12.5), y1, m["door"], frame=door_frame(m), grad=False)


def door_frame(m):
    return m["trim"] if m["style"] in ("wood", "timber") else lit(m["base"], 1.2)


def smoke(sc, x, y, z, n=3):
    px, py = P(x, y, z)
    puffs = [(px + 0.8 + i * 2.3, py - 3.6 - i * 5.0, 2.5 + i * 0.9, 2.1 + i * 0.75) for i in range(n)]
    sc.ovals(puffs[:2], "#FFFFFF", None, 0, 0.6)
    sc.ovals(puffs[2:], "#FFFFFF", None, 0, 0.36)


def chimney(sc, m, x, y, z0, z1, s=0.4, smk=True):
    c = {"wood": "#9C9288", "timber": "#B0624A"}.get(m["style"], shade(m["base"], 0.98))
    sc.box(x, y, x + s, y + s, z0, z1, c, grad=False, cast=False)
    sc.box(x - 0.05, y - 0.05, x + s + 0.05, y + s + 0.05, z1, z1 + 1.8, shade(c, 0.86), grad=False, cast=False,
           top="#3A2E2E")
    if smk:
        smoke(sc, x + s / 2, y + s / 2, z1 + 2.5)


def crenel(sc, x0, y0, x1, y1, z, c, n=4, h=3.0, d=0.12, top=None, back=False):
    """壁の上のきざみ（前面 2 辺。back=True なら奥の 2 辺）。3 本のパスにまとめる。"""
    items = []
    for i in range(n):
        a = x0 + (x1 - x0) * (i + 0.2) / n
        b = x0 + (x1 - x0) * (i + 0.7) / n
        items.append((a, y0, b, y0 + d, z, z + h) if back else (a, y1 - d, b, y1, z, z + h))
    for i in range(n):
        a = y0 + (y1 - y0) * (i + 0.2) / n
        b = y0 + (y1 - y0) * (i + 0.7) / n
        items.append((x0, a, x0 + d, b, z, z + h) if back else (x1 - d, a, x1, b, z, z + h))
    boxes(sc, items, c, top)


def gable_roof(sc, m, x0, y0, x1, y1, h, rh, axis="x", over=0.25, wall=None, ornament=True):
    """材質に合わせた切妻屋根（茅 / 瓦 / スレート / 金）と、妻壁の飾り。"""
    st = m["style"]
    rc = m["roof"]
    half = (y1 - y0 if axis == "x" else x1 - x0) / 2.0 + over
    slope = math.hypot(half * 10.0, rh)
    if st == "wood":
        kw = dict(tiles=2, thick=2.6, ridge=shade(rc, 0.7), rw=1.8)
    else:
        ridge = m["gold"] if st in ("white",) else ("#FFF6C0" if st == "marble" else None)
        kw = dict(tiles=max(3, int(slope / 3.4)), thick=1.7, ridge=ridge, rw=1.1 if ridge else 0.9)
    sc.gable(x0, y0, x1, y1, h, rh, rc, axis=axis, over=over, wall=wall or m["wall"], **kw)
    if not ornament:
        return
    if axis == "x":
        cx, cy = P(x1 + over, (y0 + y1) / 2.0 + 0.04, h + rh * 0.34)
    else:
        cx, cy = P((x0 + x1) / 2.0 + 0.04, y1 + over, h + rh * 0.34)
    if rh < 7:
        return
    if st == "wood":
        sc.oval(cx, cy, 1.3, 1.7, "#3A2A22", shade(m["trim"], 0.8), 0.5)
    elif st == "marble":
        gem(sc, cx, cy, 2.4, "#3FB6E8")
    elif st == "white":
        sc.oval(cx, cy, 1.9, 1.9, m["gold"], shade(m["gold"], 0.55), 0.4)
    else:
        sc.oval(cx, cy, 1.6, 1.9, m["lit"], m["frame"] if axis == "y" else shd(m["frame"], 0.8), 0.7)


def flat_roof(sc, m, t, x0, y0, x1, y1, h, wall, n=4):
    """胸壁つきの陸屋根。"""
    floor = shade(mix(wall, m["base"], 0.55), 0.9)
    a0, b0, a1, b1 = x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1
    sc.box(a0, b0, a1, b1, h, h + 2.5, shade(wall, 0.95), top=floor, grad=False)
    cc = shade(wall, 0.99)
    top = m["gold"] if t >= 5 else None
    crenel(sc, a0, b0, a1, b1, h + 2.5, cc, n, 3.0, 0.14, top, back=True)
    crenel(sc, a0, b0, a1, b1, h + 2.5, cc, n, 3.0, 0.14, top)
    if t >= 4:
        sc.line([P(a0, b1, h + 0.2), P(a1, b1, h + 0.2), P(a1, b0, h + 0.2)], m["gold"], 0.9)


def hall(sc, m, t, x0, y0, x1, y1, h, roof="gable", axis="x", rh=None, wall=None, nl=2, nr=2, door=True, zb=4.0,
         shadow=True):
    wall = wall or m["wall"]
    if shadow:
        contact(sc, x0, y0, x1, y1)
    # 土台（石）
    sc.box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, 0, zb, m["base"], grad=False)
    flat = roof == "flat"
    sc.box(x0, y0, x1, y1, zb, h, wall, eave=0.0 if flat else 3.2, faces="rl")
    wall_tex(sc, m, x0, y0, x1, y1, zb, h, wall)
    windows(sc, m, t, x0, y0, x1, y1, h, nl, nr, door=door, zb=zb)
    rh = rh or (7 + 2 * t)
    if roof == "gable":
        gable_roof(sc, m, x0, y0, x1, y1, h, rh, axis, wall=wall)
    elif roof == "pyramid":
        st = m["style"]
        sc.pyramid(x0, y0, x1, y1, h, rh, m["roof"], tiles=0 if st == "wood" else max(3, int(rh / 4.2)),
                   thick=2.4 if st == "wood" else 1.6)
    elif roof == "flat":
        flat_roof(sc, m, t, x0, y0, x1, y1, h, wall)


def tower(sc, m, t, xc, yc, r, h, cone_h, flag=True, cone=None, wall=None, z0=0.0, fw=7.0, fh=None):
    """円塔（石の帯・張り出した頭・あかりの窓・円錐屋根・旗）。"""
    wall = wall or m["wall"]
    sc.cylinder(xc, yc, r, z0, h, wall, cap=False)
    hh = h - z0
    if m["style"] != "wood":
        sc.lines([ring(xc, yc, r, z0 + hh * f) for f in ((0.3, 0.62) if hh > 30 else (0.45,))], shd(wall, 0.52), 0.4, 0.4)
    sc.cylinder(xc, yc, r + 0.07, h - 4.0, h, shade(wall, 1.03), cap=False, cast=False)
    if t >= 5:
        sc.line(ring(xc, yc, r + 0.07, h - 2.0), m["gold"], 1.0)
    sc.win_left(xc, z0 + hh * 0.6, 0.36, 7.0, yc + r * 0.72, m["win"], m["lit"], m["frame"])
    sc.cone(xc, yc, r, h, cone_h, cone or m["roof"], 0.18)
    if t >= 4:
        px, py = P(xc, yc, h + cone_h + 0.6)
        sc.oval(px, py, 1.5, 1.5, m["gold"], shade(m["gold"], 0.5), 0.4, sil=True)
    if flag:
        sc.flag(xc, yc, h + cone_h, fh or (12 if t < 4 else 14), m["banner"], fw)


def crate(sc, x, y, s=0.5, h=7.0, c=WOOD, z0=0.0):
    sc.box(x, y, x + s, y + s, z0, z0 + h, c, grad=False, cast=z0 == 0)
    sc.lines([[P(x, y + s, z0 + 0.7), P(x + s, y + s, z0 + h - 0.7)], [P(x + s, y, z0 + h - 0.7), P(x + s, y + s, z0 + 0.7)]],
             shade(c, 0.6), 0.6, 0.8)


def barrel(sc, x, y, r=0.22, h=7.0, c="#8E6038", z0=0.0):
    sc.cylinder(x, y, r, z0, z0 + h, c, top=shade(c, 0.78), cast=z0 == 0)
    sc.lines([ring(x, y, r + 0.005, z0 + h * 0.24, 6), ring(x, y, r + 0.005, z0 + h * 0.76, 6)], "#3E3A44", 0.6, 0.9)


def sacks(sc, pts, c="#DCC89C", s=1.0):
    """麻袋 [(x, y), ...]。"""
    body, tie = [], []
    for x, y in pts:
        px, py = P(x, y, 0)
        body.append((px, py - 3.0 * s, 3.1 * s, 3.4 * s))
        tie.append((px - 0.3 * s, py - 6.5 * s, 1.3 * s, 0.9 * s))
    sc.ovals(body, c, edge(c), 0.45, sil=True)
    sc.ovals(tie, shade(c, 0.78), edge(c), 0.4, sil=True)


def haybale(sc, x, y, r=0.36, h=6.0, c=STRAW):
    sc.cylinder(x, y, r, 0, h, c, top=lit(c, 1.22))
    sc.lines([ring(x, y, r + 0.005, h * 0.5, 6)], shade(c, 0.6), 0.5, 0.8)


def fence(sc, pts, h=6.0, c="#9A6E3E"):
    """杭と横木の柵。pts は杭の位置 [(x, y), ...]（順に横木でつなぐ）。"""
    posts = [[P(x, y, 0), P(x, y, h)] for x, y in pts]
    rails = [[P(x, y, h * f) for x, y in pts] for f in (0.42, 0.82)]
    sc.lines(posts + rails, INK, 2.4, cap="round")
    sc.lines(rails, shade(c, 0.86), 1.0)
    sc.lines(posts, lit(c, 1.1), 1.3, cap="round")


def gem(sc, px, py, r, c):
    """ひし形の宝石（画面座標）。"""
    sc.poly([(px, py - r), (px + r * 0.8, py), (px, py + r), (px - r * 0.8, py)], c, shade(c, 0.4), 0.4)
    sc.poly([(px, py - r), (px + r * 0.8, py), (px - r * 0.8, py)], lit(c, 1.5, 0.0), None, 0, 0.7)


def torches(sc, pts3, h=3.0):
    """壁のたいまつ [(x, y, z), ...]（にじみ・炎・芯）。"""
    glows, flames, cores, sticks = [], [], [], []
    for x, y, z in pts3:
        px, py = P(x, y, z)
        sticks.append([(px, py), (px, py + h)])
        glows.append((px, py - 1.5, 5.0, 5.0))
        flames.append((px, py - 1.6, 1.5, 2.3))
        cores.append((px, py - 1.2, 0.7, 1.2))
    sc.lines(sticks, "#3A2A20", 1.0)
    sc.ovals(glows, "#FFB23C", None, 0, 0.3)
    sc.ovals(flames, "#FF8A2A", None, 0)
    sc.ovals(cores, "#FFE58A", None, 0)


def rock(sc, x, y, s, c, z=0.0):
    """角ばった岩（画面座標の多角形。上面が明るい）。"""
    px, py = P(x, y, z)
    q = lambda dx, dy: (px + dx * s, py + dy * s)  # noqa: E731
    sc.poly([q(-5, 0), q(-3.6, -4.6), q(0.4, -6.4), q(4.4, -3.4), q(5.4, 0.6), q(1, 2.2), q(-3, 1.6)], shd(c, 0.78),
            edge(c), 0.45, sil=True)
    sc.poly([q(-5, 0), q(-3.6, -4.6), q(-2.2, -1.8), q(-3, 1.6)], shade(c, 0.96), None, 0)
    sc.poly([q(-3.6, -4.6), q(0.4, -6.4), q(4.4, -3.4), q(1.2, -1.4), q(-2.2, -1.8)], lit(c, 1.16), None, 0)


def capsule(a, b, r, n=5):
    """太い線分の輪郭（両端が丸い）。丸太などに使う。"""
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    pts = []
    for i in range(n + 1):
        th = ang + math.pi / 2 + math.pi * i / n
        pts.append((a[0] + r * math.cos(th), a[1] + r * math.sin(th)))
    for i in range(n + 1):
        th = ang - math.pi / 2 + math.pi * i / n
        pts.append((b[0] + r * math.cos(th), b[1] + r * math.sin(th)))
    return pts


def palisade(sc, x0, y0, x1, y1, h, c, n=None):
    """丸太の柵（先のとがった杭の列）。長い方向の見える面を、上端のぎざぎざつきで描く。"""
    along_x = abs(x1 - x0) >= abs(y1 - y0)
    lo, hi = (x0, x1) if along_x else (y0, y1)
    fn = Lf(y1) if along_x else Rf(x1)
    n = n or max(3, int(round((hi - lo) / 0.24)))
    top, seams = [], []
    for i in range(n):
        a = lo + (hi - lo) * i / float(n)
        b = lo + (hi - lo) * (i + 1) / float(n)
        top += [fn(a, h - 1.7), fn((a + b) / 2, h + 0.9), fn(b, h - 1.7)]
        if i:
            seams.append([fn(a, 0.4), fn(a, h - 1.7)])
    if not along_x:
        top = list(reversed(top))
        pts = [fn(lo, 0), fn(hi, 0)] + top
    else:
        pts = [fn(hi, 0), fn(lo, 0)] + top
    col = c if along_x else shd(c, 0.74)
    sc.poly(pts, col, edge(c), 0.45, sil=True)
    sc.lines(seams, shade(col, 0.66), 0.4, 0.7)
    sc.line([fn(lo, h * 0.55), fn(hi, h * 0.55)], shade(col, 0.6), 0.8, 0.7)
    sc.cast_box(x0, y0, x1, y1, 0, h)


def shield(sc, fn, a, z, s, c, rim):
    """壁に掛けた盾（面の上）。s は高さ。"""
    w = s / U * 0.62
    pts = [fn(a - w, z + s * 0.5), fn(a + w, z + s * 0.5), fn(a + w, z - s * 0.05), fn(a, z - s * 0.5), fn(a - w, z - s * 0.05)]
    sc.poly(pts, c, rim, 0.7)
    sc.line([fn(a, z + s * 0.5), fn(a, z - s * 0.5)], rim, 0.5, 0.9)


# ---- 城とスキン ---------------------------------------------------------------

def _star(sc, x, y, r, c, alpha=0.95):
    """四方向にとがった小さな星（きらめき・雪の結晶）。(x, y) は画面座標。"""
    sc.polys([_star_pts(x, y, r)], c, None, 0.0, alpha)


def _star_pts(x, y, r):
    k = r * 0.26
    return [(x, y - r), (x + k, y - k), (x + r, y), (x + k, y + k), (x, y + r), (x - k, y + k), (x - r, y), (x - k, y - k)]


def _dots(sc, pts, c, rx, ry, alpha=0.9):
    sc.ovals([(x, y, rx, ry) for (x, y) in pts], c, None, 0, alpha)


def crystal(sc, x, y, h, w, c):
    """氷の結晶の柱（左が明るく、右が暗い 2 面）。"""
    b = P(x, y, 0)
    tip = (b[0] + w * 0.15, b[1] - h)
    lf, rt, mid = (b[0] - w, b[1] - h * 0.12), (b[0] + w, b[1] - h * 0.2), (b[0] + w * 0.1, b[1] + w * 0.35)
    sc.sil([lf, mid, rt, tip])
    sc.poly([lf, mid, tip], vgrad(tip[1], b[1], "#FFFFFF", c), edge(c, 0.6), 0.4)
    sc.poly([mid, rt, tip], vgrad(tip[1], b[1], lit(c, 1.1), shd(c, 0.72, 0.1)), edge(c, 0.6), 0.4)
    sc.cast([(x, y, 0), (x + 0.15, y, 0), (x, y, h * 0.8)])


def cherry_tree(sc, x, y, t):
    """桜の木（幹と、陰影のある花の樹冠）。"""
    sc.cylinder(x, y, 0.09, 0, 13 + t, "#6B4A3A", cast=False)
    cx, cy = P(x, y, 19 + t * 2)
    sc.cast_round(x, y, 0.5, 12 + t, 24 + t * 2)
    sc.ovals([(cx, cy, 12.5, 9.0), (cx - 7, cy + 2.5, 7.0, 5.2), (cx + 7.5, cy + 2.0, 6.5, 5.0)],
             Radial(cx - 4, cy - 4, 17, ["#FFD3E0", "#F29BB5", "#D56C92"]), "#A2506E", 0.45, sil=True)
    sc.ovals([(cx - 5, cy - 3.5, 5.2, 3.4), (cx + 4, cy - 1, 3.6, 2.4), (cx - 1, cy + 3, 3.0, 1.9)], "#FFE4EC", None, 0, 0.75)


def skin_back(sc, m, t, skin):
    """スキン専用の装飾（建物より奥に置くもの）。氷: 水晶の尖塔 / 桜: 桜の木 / 竜: 竜の翼。"""
    kh = [26, 34, 44, 52, 62][t - 1]
    if skin == "ice":
        crystal(sc, 3.75, 0.25, 22 + t * 4, 3.4, "#9AD4F5")
        crystal(sc, 3.95, 0.4, (22 + t * 4) * 0.6, 2.2, "#7FC4EE")
        crystal(sc, 0.25, 0.9, 16 + t * 3, 3.0, "#9AD4F5")
        crystal(sc, 0.45, 1.05, (16 + t * 3) * 0.6, 2.0, "#7FC4EE")
    elif skin == "sakura":
        cherry_tree(sc, 0.35, 3.55, t)
        cherry_tree(sc, 3.65, 0.4, t)
    elif skin == "dragon":
        # 主塔の左右に広がる竜の翼（暗い紅の膜と骨）
        for sgn in (-1, 1):
            a = P(2.0, 2.0, kh * 0.7)
            ax = a[0] + sgn * 8
            tip = (ax + sgn * (26 + t * 3), a[1] - 18 - t * 4)
            mid = (ax + sgn * (22 + t * 2), a[1] + 8)
            low = (ax + sgn * 10, a[1] + 14)
            pts = [(ax, a[1]), tip, (ax + sgn * 17, a[1] - 2), mid, (ax + sgn * 8, a[1] + 8), low]
            sc.poly(pts, vgrad(tip[1], low[1], "#B02438", "#5A1020"), "#2A0E14", 0.7, sil=True)
            sc.lines([[(ax, a[1]), tip], [(ax, a[1]), mid], [(ax, a[1]), low]], "#2A0E14", 1.2, cap="round")
            sc.line([(ax + sgn * 2, a[1] - 1.5), (tip[0] - sgn * 2, tip[1] + 2.5)], "#E86A5A", 0.5, 0.7)


def skin_front(sc, m, t, skin):
    """スキン専用の装飾（建物より手前に置くもの）。"""
    kh = [26, 34, 44, 52, 62][t - 1]
    th = [0, 30, 38, 46, 56][t - 1]
    rh = 8 + t * 3
    corner = ((0.55, 0.55), (3.45, 0.55), (0.55, 3.45), (3.45, 3.45))
    if skin == "ice":
        # 軒のつらら（壁・主塔の縁から下がる）
        if t >= 2:
            edges = [(x, 3.6, 12) for x in (0.6, 1.0, 1.4, 2.6, 3.0, 3.4)]
            edges += [(3.6, y, 12) for y in (0.6, 1.0, 1.4, 1.8, 2.2, 2.6, 3.0)]
        else:
            edges = [(x, 2.9, kh) for x in (1.3, 1.7, 2.1, 2.5)]
        ice = []
        for i, (x, y, z) in enumerate(edges):
            ln = 5 + (i * 7) % 5
            ice.append([P(x - 0.07, y, z), P(x + 0.07, y, z), P(x, y, z - ln)])
        sc.polys(ice, "#EAF7FF", "#8FC3E2", 0.3)
        # 屋根の雪（屋根の面に沿った白い帽子。主塔は上半分、塔は円錐の上半分）
        white = "#F4FAFF"
        if t >= 2:
            sc.pyramid(1.45, 1.45, 2.55, 2.55, kh + rh * 0.5, rh * 0.5, white, over=0.0, cast=False, thick=0)
            snow_towers = corner if t >= 3 else ((0.55, 0.55), (3.45, 3.45))
            for (x, y) in snow_towers:
                h0 = th - 4 if ((x, y) == (3.45, 3.45) and t == 2) else th
                ch = 10 + t * 2
                sc.cone(x, y, 0.27, h0 + ch * 0.5, ch * 0.5, white, 0.0, cast=False)
        else:
            sc.gable(1.1, 1.55, 2.9, 2.45, kh + 5.5, 5.5, white, over=0.0, cast=False, thick=0)
        # 手前の水晶の尖塔と、舞う雪の結晶
        crystal(sc, 3.8, 3.7, 20 + t * 3, 3.4, "#A6DCF8")
        crystal(sc, 3.58, 3.8, (20 + t * 3) * 0.55, 2.2, "#86C8F0")
        crystal(sc, 0.2, 3.9, 14 + t * 2, 3.0, "#A6DCF8")
        crystal(sc, 0.0, 4.0, (14 + t * 2) * 0.55, 2.0, "#86C8F0")
        sc.polys([_star_pts(x, y, r) for (x, y, r) in ((14, 36, 3.2), (112, 52, 2.6), (22, 84, 2.2), (104, 18, 2.4))],
                 "#FFFFFF", None, 0, 0.92)
    elif skin == "gold":
        # 金の帯と宝石、塔の金の旗
        sc.box(1.08, 1.08, 2.92, 2.92, kh - 4, kh - 1, m["gold"], outline=False, grad=False, cast=False, sil=False,
               faces="rl")
        for (x, z, c) in ((2.0, kh * 0.58, "#59D2F2"), (1.45, kh * 0.58, "#F0608E"), (2.55, kh * 0.58, "#7FE08A")):
            p = P(x, 2.92, z)
            gem(sc, p[0], p[1], 3.0, c)
        if t >= 2:
            for (x, y) in corner:
                h0 = th - 4 if ((x, y) == (3.45, 3.45) and t == 2) else th
                if t == 2 and (x, y) in ((3.45, 0.55), (0.55, 3.45)):
                    continue
                sc.flag(x, y, h0 + 10 + t * 2, 10, "#FFD84A", 6.0, "#8A6A1A")
        # 手前の金貨の山ときらめき
        for (x, y) in ((0.7, 3.8), (3.4, 3.8)):
            p = P(x, y, 0)
            sc.oval(p[0], p[1], 6, 2.8, "#D9A52E", "#7A5A12", 0.45, sil=True)
            sc.oval(p[0], p[1] - 1.8, 4.4, 2.1, "#F5CD4E", "#9A7418", 0.4, sil=True)
            sc.oval(p[0] - 0.6, p[1] - 3.2, 2.4, 1.2, "#FFE88A", None, 0)
        sc.polys([_star_pts(x, y, r) for (x, y, r) in ((18, 40, 4.2), (110, 46, 3.4), (28, 92, 2.8), (100, 84, 3.0),
                                                      (64, 24, 3.4))], "#FFF6C0", None, 0, 0.95)
    elif skin == "sakura":
        # 門の提灯と、そり上がった軒先
        for x in (1.5, 2.5):
            p = P(x, 3.78, 15)
            sc.line([(p[0], p[1] - 4), (p[0], p[1] + 1)], "#5A3A2A", 0.8)
            sc.oval(p[0], p[1] + 4.2, 2.7, 3.4, "#E8405C", "#7A1E30", 0.45, sil=True)
            sc.oval(p[0] - 0.7, p[1] + 3.4, 1.0, 1.7, "#FF9AA8", None, 0, 0.8)
            sc.lines([[(p[0] - 1.8, p[1] + 1.2), (p[0] + 1.8, p[1] + 1.2)], [(p[0] - 1.8, p[1] + 7.2), (p[0] + 1.8, p[1] + 7.2)]],
                     "#4A2A20", 0.9)
        eaves = []
        for (x, y) in ((1.1 - 0.25, 2.9 + 0.25), (2.9 + 0.25, 2.9 + 0.25), (2.9 + 0.25, 1.1 - 0.25)):
            p = P(x, y, kh)
            eaves.append([(p[0], p[1] + 1.4), (p[0] + (4.5 if x > 2 else -4.5), p[1] - 6), (p[0], p[1] - 2.4)])
        sc.polys(eaves, m["roof"], edge(m["roof"]), 0.45, sil=True)
        # 舞う花びら
        _dots(sc, [(20, 52), (36, 96), (96, 40), (108, 88), (76, 30), (50, 70), (88, 104)], "#FFD0DE", 2.4, 1.5)
        _dots(sc, [(26, 66), (100, 66), (60, 38)], "#F58FB0", 2.0, 1.3)
    elif skin == "dragon":
        # 屋根の竜の像（首をもたげた影）と、立ちのぼる火の粉
        ax, ay = P(2.0, 2.0, kh + (rh if t >= 2 else 9))
        sc.polys([[(ax - 2, ay - 8), (ax - 12, ay - 16), (ax - 8, ay - 6)], [(ax + 1, ay - 8), (ax + 11, ay - 17), (ax + 7, ay - 6)]],
                 "#8A1A2C", "#2A0E14", 0.4, sil=True)
        sc.poly([(ax - 6, ay), (ax - 4, ay - 6), (ax - 5, ay - 12), (ax - 1, ay - 17), (ax + 3, ay - 16),
                 (ax + 7, ay - 18), (ax + 3, ay - 13), (ax + 1, ay - 9), (ax + 5, ay - 4), (ax + 7, ay)],
                vgrad(ay - 18, ay, "#4A2A34", "#1A1016"), "#C0283A", 0.5, sil=True)
        sc.poly([(ax + 3, ay - 16), (ax + 4, ay - 21), (ax + 5, ay - 16)], "#E8A33C", None, 0.0)
        sc.oval(ax + 4.2, ay - 15, 0.9, 0.9, "#FF5A3C", None, 0)
        if t >= 2:
            horns = []
            for (x, y) in corner:
                if t == 2 and (x, y) in ((3.45, 0.55), (0.55, 3.45)):
                    continue
                h0 = th - 4 if ((x, y) == (3.45, 3.45) and t == 2) else th
                p = P(x, y, h0 + 4)
                horns.append([(p[0] - 2.2, p[1]), (p[0], p[1] - 9), (p[0] + 2.2, p[1])])
            sc.polys(horns, "#C0283A", "#2A0E14", 0.4)
        # 門の奥の炎のあかりと、舞う火の粉
        embers = ((18, 70, 1.7), (30, 40, 1.3), (100, 56, 1.8), (110, 90, 1.3), (82, 26, 1.5), (46, 102, 1.4),
                  (92, 110, 1.2))
        sc.ovals([(x, y, r * 2.4, r * 2.4) for x, y, r in embers], "#FF7A2A", None, 0, 0.25)
        sc.ovals([(x, y, r, r) for x, y, r in embers[::2]], "#FF8A2A", None, 0, 0.95)
        sc.ovals([(x, y, r, r) for x, y, r in embers[1::2]], "#FFC85A", None, 0, 0.95)


def castle(sc, m, t):
    base_pad(sc, m, t)
    skin = m.get("skin")
    towers = [0, 2, 4, 4, 4][t - 1]
    th = [0, 30, 38, 46, 56][t - 1]
    kh = [26, 34, 44, 52, 62][t - 1]
    wc = m["base"] if t < 3 else shade(m["wall"], 0.92)
    contact(sc, 0.5, 0.5, 3.5, 3.5, 0.3, 0.12)
    if skin:
        skin_back(sc, m, t, skin)
    # 城壁（奥の 2 辺）。段階 1 は丸太の柵
    if t >= 2:
        sc.box(0.4, 0.4, 3.6, 0.8, 0, 12, wc, grad="l")
        sc.box(0.4, 0.4, 0.8, 3.6, 0, 12, wc, grad="r")
    else:
        palisade(sc, 0.45, 0.45, 3.55, 0.55, 9, WOOD_DK)
        palisade(sc, 0.45, 0.45, 0.55, 3.55, 9, WOOD_DK)
    if towers >= 2:
        tower(sc, m, t, 0.55, 0.55, 0.5, th, 10 + t * 2)
    if towers >= 4:
        tower(sc, m, t, 3.45, 0.55, 0.5, th, 10 + t * 2)
        tower(sc, m, t, 0.55, 3.45, 0.5, th, 10 + t * 2)
    # 主塔
    hall(sc, m, t, 1.1, 1.1, 2.9, 2.9, kh, roof="pyramid" if t >= 2 else "gable", rh=8 + t * 3,
         nl=3 if t < 3 else 2, nr=3 if t < 3 else 2, shadow=False)
    if t >= 3:
        # 主塔の垂れ幕（左前面と右前面の中央）
        sc.banner_left(2.0, 2.9, kh - 5, kh * 0.34, m["banner"], m["gold"], 0.2)
        sc.banner(2.9, 2.0, kh - 5, kh * 0.34, m["banner"], m["gold"])
        # 中央の尖塔
        sc.cylinder(2.0, 2.0, 0.42, kh + 4, kh + 8 + t * 2, shade(m["wall"], 1.03), cap=False, cast=False)
        sc.cone(2.0, 2.0, 0.42, kh + 8 + t * 2, 10 + t * 2, m["roof"], 0.15)
        if t >= 4:
            px, py = P(2.0, 2.0, kh + 18 + t * 4 + 0.6)
            sc.oval(px, py, 1.6, 1.6, m["gold"], shade(m["gold"], 0.5), 0.4, sil=True)
        sc.flag(2.0, 2.0, kh + 18 + t * 4, 14, m["banner"], 8.0)
    elif t >= 2:
        sc.flag(2.0, 2.0, kh + 8 + t * 3, 12, m["banner"], 8.0)
    # 手前の壁と門
    sc.layer()
    if t >= 2:
        sc.box(0.4, 3.2, 3.6, 3.6, 0, 12, wc, grad="l")
        sc.box(3.2, 0.4, 3.6, 3.6, 0, 12, wc, grad="r")
        if m["style"] in ("stone", "white", "marble"):
            wall_tex(sc, m, 0.4, 0.4, 3.6, 3.6, 0, 12.5, wc)
        gold_top = m["gold"] if t >= 5 else None
        crenel(sc, 0.4, 0.4, 3.6, 3.6, 12, wc, 6, 3.0, top=gold_top)
        # 門楼
        sc.box(1.4, 3.2, 2.6, 3.72, 0, 16, shade(wc, 1.04), grad="l", cast=False)
        boxes(sc, [(1.4 + 0.3 * i + 0.03, 3.6, 1.4 + 0.3 * i + 0.21, 3.72, 16, 18.6) for i in range(4)], shade(wc, 1.04),
              gold_top)
        sc.door_left(2.0, 0.72, 11.5, 3.72, m["door"], frame=lit(wc, 1.22))
        if t >= 3:
            torches(sc, [(1.56, 3.72, 11.5), (2.44, 3.72, 11.5)])
        if t >= 4:
            sc.line([P(0.4, 3.6, 12), P(3.6, 3.6, 12), P(3.6, 0.4, 12)], m["gold"], 0.9)
        if t >= 5:
            g = P(2.0, 3.72, 14.2)
            gem(sc, g[0], g[1], 2.0, "#E0405A")
    else:
        palisade(sc, 0.45, 3.45, 1.55, 3.55, 9, WOOD_DK)
        palisade(sc, 2.45, 3.45, 3.55, 3.55, 9, WOOD_DK)
        palisade(sc, 3.45, 0.45, 3.55, 3.55, 9, WOOD_DK)
        # 門柱とたいまつ
        boxes(sc, [(1.45, 3.4, 1.62, 3.6, 0, 13), (2.38, 3.4, 2.55, 3.6, 0, 13)], m["trim"], cast=True)
        torches(sc, [(1.53, 3.62, 15.5), (2.47, 3.62, 15.5)], 2.0)
    if towers >= 4:
        tower(sc, m, t, 3.45, 3.45, 0.55, th, 10 + t * 2)
    elif towers >= 2:
        tower(sc, m, t, 3.45, 3.45, 0.5, th - 4, 10 + t * 2)
    if t == 1:
        sc.flag(2.0, 2.0, kh + 8, 11, m["banner"], 8.0)
    if skin:
        skin_front(sc, m, t, skin)


# ---- 各建物 -------------------------------------------------------------------

def crops(sc, x0, y0, x1, y1, n, c, along="x", w=1.5):
    """畝に並ぶ作物（太い帯と、上の明るい筋）。"""
    segs = []
    for i in range(n):
        f = (i + 0.5) / n
        if along == "x":
            y = y0 + (y1 - y0) * f
            segs.append([P(x0, y, 0.6), P(x1, y, 0.6)])
        else:
            x = x0 + (x1 - x0) * f
            segs.append([P(x, y0, 0.6), P(x, y1, 0.6)])
    sc.lines(segs, shade(c, 0.74), w + 0.7, cap="round")
    sc.lines(segs, c, w, cap="round")
    sc.lines([[(a[0], a[1] - w * 0.3), (b[0], b[1] - w * 0.3)] for a, b in segs], lit(c, 1.35, 0.1), 0.45, 0.8)


def windmill(sc, m, t, x, y, h):
    """風車（石の円塔・円錐屋根・4 枚の羽根）。"""
    wall = shade(m["wall"], 1.03)
    sc.cylinder(x, y, 0.4, 0, h, wall, cap=False)
    sc.lines([ring(x, y, 0.4, h * 0.4)], shd(wall, 0.52), 0.4, 0.4)
    sc.win_left(x, h * 0.45, 0.3, 5.5, y + 0.4 * 0.72, m["win"], m["lit"], m["frame"])
    sc.cone(x, y, 0.4, h, 7, m["roof"], 0.14)
    hub = P(x + 0.1, y + 0.42, h + 1.0)
    sails, spars = [], []
    for k in range(4):
        ang = math.radians(28 + 90 * k)
        ca, sa = math.cos(ang), math.sin(ang)
        tip = (hub[0] + 15.5 * ca, hub[1] + 15.5 * sa)
        spars.append([hub, tip])
        nx, ny = -sa, ca
        a = (hub[0] + 4.0 * ca, hub[1] + 4.0 * sa)
        sails.append([a, tip, (tip[0] + 3.6 * nx, tip[1] + 3.6 * ny), (a[0] + 3.6 * nx, a[1] + 3.6 * ny)])
    sc.polys(sails, "#F4EEDC", "#8C7B5A", 0.5, sil=True)
    sc.lines(spars, "#6B5236", 1.0, cap="round")
    sc.oval(hub[0], hub[1], 1.5, 1.5, "#6B5236", "#3A2A1A", 0.4)


def farm(sc, m, t):
    base_pad(sc, m, t, cast=False)
    # 畑の畝
    soil = "#7C5830" if t < 5 else "#8A6436"
    crop = ["#D2B548", "#B9CC4A", "#8FC64E", "#78C85A", "#F2CE4E"][t - 1]
    ground(sc, 0.2, 2.1, 3.8, 3.85, soil)
    crops(sc, 0.36, 2.22, 3.64, 3.74, 6, crop)
    ground(sc, 2.2, 0.2, 3.85, 1.9, shade(soil, 1.08))
    crops(sc, 2.34, 0.34, 3.72, 1.76, 5, ["#7FB24A", "#7FB24A", "#6FAE4E", "#6FAE4E", "#9AC04A"][t - 1], "y", 1.3)
    sc.cast_here()
    # 納屋
    h = 12 + t * 2
    hall(sc, m, t, 0.4, 0.4, 2.0, 1.9, h, roof="gable", axis="x", rh=8 + t, nl=0, nr=1, door=False)
    # 納屋の大きな扉（左前面）
    lf = Lf(1.9)
    dw, dh = 0.86, min(h - 5.5, 10.5)
    dpts = [lf(1.2 - dw / 2, 0), lf(1.2 + dw / 2, 0), lf(1.2 + dw / 2, dh), lf(1.2 - dw / 2, dh)]
    sc.poly(dpts, m["door"], door_frame(m), 0.9)
    sc.lines([[dpts[0], dpts[2]], [dpts[1], dpts[3]], [lf(1.2, 0), lf(1.2, dh)]], shade(m["door"], 0.55), 0.5, 0.9)
    window_set(sc, m, lf, [(0.62, 4 + (h - 4) * 0.55)], 0.3, min(6.0, (h - 4) * 0.42))
    if t >= 3:  # 風車
        windmill(sc, m, t, 3.3, 0.9, 18 + t * 2)
    # 柵・干し草・麻袋
    sc.layer()
    fence(sc, [(0.2, 3.92), (1.1, 3.92), (2.0, 3.92), (2.9, 3.92), (3.86, 3.92), (3.92, 3.0), (3.92, 2.1)], 5.0)
    haybale(sc, 2.7, 1.62, 0.34, 6.0, STRAW if t < 5 else m["gold"])
    if t >= 2:
        haybale(sc, 2.36, 1.95, 0.28, 5.0)
    sacks(sc, [(0.42, 2.12), (0.7, 2.22)][: 1 + (t >= 3)])
    if t >= 4:
        crate(sc, 1.55, 2.0, 0.34, 4.5)
        sc.flag(0.4, 0.4, h + 8 + t, 9, m["banner"], 6.0)


def log_pile(sc, x, y, n, ln=1.1, r=2.3):
    """積んだ丸太（x 方向に寝かせ、手前の切り口が見える）。"""
    ends, bodies = [], []
    k = 0
    row, lvl = n, 0
    pos = []
    while row > 0 and k < 9:
        for i in range(row):
            pos.append((y + (i + lvl * 0.5) * 0.36, r + lvl * r * 1.7))
        row -= 1
        lvl += 1
    for yy, zz in pos:
        a, b = P(x, yy, zz), P(x + ln, yy, zz)
        bodies.append(capsule(a, b, r))
        ends.append((b[0], b[1], r * 0.86, r))
    sc.polys(bodies, "#9A6B3C", "#4A2E18", 0.45, sil=True)
    sc.ovals(ends, "#DDB57A", "#6B4424", 0.45)
    sc.ovals([(e[0], e[1], e[2] * 0.45, e[3] * 0.45) for e in ends], "#B98A52", None, 0)
    sc.cast_box(x, y - 0.1, x + ln, y + n * 0.36, 0, r * 2 * lvl * 0.8)


def saw_blade(sc, cx, cy, r):
    """大きな丸のこ（歯つきの円盤）。"""
    pts = []
    n = 14
    for i in range(n):
        a0 = 2 * math.pi * i / n
        a1 = 2 * math.pi * (i + 0.55) / n
        pts.append((cx + r * math.cos(a0), cy + r * math.sin(a0)))
        pts.append((cx + r * 0.84 * math.cos(a1), cy + r * 0.84 * math.sin(a1)))
    sc.poly(pts, Linear(cx - r, cy - r, cx + r, cy + r, ["#F2F5F8", "#AEB6C0", "#7C8590"]), "#3E444C", 0.5, sil=True)
    sc.oval(cx, cy, r * 0.55, r * 0.55, None, "#6A727C", 0.5, salpha=0.8)
    sc.oval(cx, cy, r * 0.22, r * 0.22, "#4A4F57", "#2A2E34", 0.4)


def water_wheel(sc, cx, cy, r, c="#8A6238"):
    sc.sil([(cx + (r + 0.6) * math.cos(2 * math.pi * i / 16), cy + (r + 0.6) * math.sin(2 * math.pi * i / 16)) for i in range(16)])
    sc.oval(cx, cy, r - 0.9, r - 0.9, None, c, 1.8)
    sc.oval(cx, cy, r * 0.55, r * 0.55, None, shade(c, 0.8), 1.0)
    spokes, pads = [], []
    for k in range(8):
        ang = math.radians(k * 45 + 10)
        ca, sa = math.cos(ang), math.sin(ang)
        spokes.append([(cx, cy), (cx + (r - 1) * ca, cy + (r - 1) * sa)])
        pads.append([(cx + (r - 2.4) * ca + 1.2 * sa, cy + (r - 2.4) * sa - 1.2 * ca),
                     (cx + (r + 0.6) * ca + 1.2 * sa, cy + (r + 0.6) * sa - 1.2 * ca),
                     (cx + (r + 0.6) * ca - 1.2 * sa, cy + (r + 0.6) * sa + 1.2 * ca),
                     (cx + (r - 2.4) * ca - 1.2 * sa, cy + (r - 2.4) * sa + 1.2 * ca)])
    sc.lines(spokes, shade(c, 0.62), 0.9)
    sc.polys(pads, shade(c, 0.9), shade(c, 0.45), 0.35)
    sc.oval(cx, cy, 1.6, 1.6, shade(c, 0.6), "#2A1C12", 0.4)


def sawmill(sc, m, t):
    base_pad(sc, m, t)
    h = 14 + t * 2
    # 丸太の山（奥）と切り株
    log_pile(sc, 0.25, 0.5, 2 + (t >= 3), 1.0)
    hall(sc, m, t, 1.6, 0.4, 3.6, 2.2, h, roof="gable", axis="y", rh=8 + t, nl=1, nr=2, door=False)
    if t >= 3:  # 水車（右前面）
        wx, wy = P(3.62, 1.3, 9.5)
        sc.oval(wx + 1, wy + 9.5, 9, 3.2, "#5AA6DC", "#2E6E9E", 0.4, 0.9)
        water_wheel(sc, wx, wy, 9.0)
    if t >= 4:
        sc.flag(3.2, 0.8, 26 + t * 2, 10, m["banner"])
    sc.layer()
    # 製材台と大きな丸のこ
    boxes(sc, [(0.9, 2.75, 2.4, 3.3, 0, 5.5)], WOOD, cast=True)
    sc.lines([[P(0.9, 3.3, 2.8), P(2.4, 3.3, 2.8)]], shade(WOOD, 0.6), 0.5, 0.8)
    cx, cy = P(1.62, 3.05, 13)
    saw_blade(sc, cx, cy, 8.0)
    # 板の束と、手前の丸太
    boxes(sc, [(2.7, 2.7, 3.7, 3.05, 0, 2.2), (2.76, 2.74, 3.66, 3.02, 2.2, 4.2)] + ([(2.8, 2.76, 3.6, 3.0, 4.2, 6.0)] if t >= 2 else []),
          "#D9B27A", cast=True)
    log_pile(sc, 0.3, 3.3, 1 + (t >= 2) + (t >= 4), 0.9, 2.1)


def crag(sc, x0, y0, x1, y1, h, c, seed=0):
    """岩の壁（上端がでこぼこの箱）。"""
    sc.box(x0, y0, x1, y1, 0, h, c, top=lit(c, 1.12))
    # 割れ目と明るい面
    lf, rf = Lf(y1), Rf(x1)
    segs = []
    for i, f in enumerate((0.22, 0.5, 0.78)):
        a = x0 + (x1 - x0) * f
        segs.append([lf(a, h), lf(a - 0.12, h * (0.55 - 0.1 * ((i + seed) % 2))), lf(a + 0.06, h * 0.2)])
    sc.lines(segs, shade(c, 0.6), 0.5, 0.55)
    segs = []
    for i, f in enumerate((0.3, 0.68)):
        a = y0 + (y1 - y0) * f
        segs.append([rf(a, h), rf(a + 0.1, h * (0.5 + 0.1 * ((i + seed) % 2))), rf(a - 0.05, h * 0.18)])
    sc.lines(segs, shd(c, 0.42), 0.5, 0.6)


def quarry(sc, m, t):
    base_pad(sc, m, t, cast=False)
    rock_c = ["#928E84", "#9A968C", "#A9A59C", "#B8B4AA", "#DAD4C6"][t - 1]
    # 掘り込み（階段状の穴）
    ground(sc, 0.3, 0.3, 3.7, 3.7, shade(rock_c, 0.7), grad=False)
    ground(sc, 0.75, 0.75, 3.3, 3.3, shade(rock_c, 0.54))
    sc.cast_here()
    # 岩の壁（奥）
    crag(sc, 0.2, 0.2, 3.8, 0.9, 16 + t * 2, rock_c)
    crag(sc, 0.2, 0.2, 0.9, 3.8, 12 + t * 2, rock_c, 1)
    rock(sc, 1.2, 1.25, 0.9, rock_c)
    if t >= 3:  # クレーン
        top = P(2.8, 1.2, 30 + t * 2)
        jib = P(1.4, 1.9, 24 + t * 2)
        sc.lines([[P(2.8, 1.2, 0), top], [top, jib], [P(2.8, 1.2, 12), P(2.45, 1.38, 26 + t * 2)], [P(2.5, 1.0, 0), P(2.8, 1.2, 9)],
                  [P(3.1, 1.4, 0), P(2.8, 1.2, 9)]], INK, 3.4, cap="round")
        sc.lines([[P(2.8, 1.2, 0), top], [top, jib]], "#8A6238", 2.0, cap="round")
        sc.lines([[P(2.8, 1.2, 12), P(2.45, 1.38, 26 + t * 2)], [P(2.5, 1.0, 0), P(2.8, 1.2, 9)], [P(3.1, 1.4, 0), P(2.8, 1.2, 9)]],
                 "#6E4C2A", 1.4, cap="round")
        sc.line([jib, P(1.4, 1.9, 14)], "#2B2B2B", 0.6)
        sc.box(1.22, 1.72, 1.58, 2.08, 8, 14, lit(rock_c, 1.16), grad=False, cast=False)
    # 積まれた石材
    sc.layer()
    blocks = [(2.4 + i * 0.5, 2.6, 2.8 + i * 0.5, 3.1, 0, 5) for i in range(2 + t // 2)]
    blocks += [(2.62 + i * 0.5, 2.66, 3.0 + i * 0.5, 3.06, 5, 9.5) for i in range(max(0, t // 2))]
    boxes(sc, blocks, lit(rock_c, 1.12), cast=True)
    sc.box(1.55, 2.95, 2.1, 3.5, 0, 5 + t, lit(rock_c, 1.2), grad=False)
    rock(sc, 0.75, 3.45, 0.8, rock_c)
    rock(sc, 3.3, 1.75, 0.6, rock_c)
    # つるはし
    hx, hy = P(1.15, 3.2, 0)
    sc.line([(hx, hy), (hx + 5.5, hy - 9.5)], "#7A5530", 1.3, cap="round")
    sc.line([(hx + 1.6, hy - 10.4), (hx + 5.6, hy - 9.6), (hx + 8.6, hy - 6.4)], "#5E6670", 1.5, cap="round")
    if t >= 4:
        sc.flag(3.4, 0.5, 18 + t * 2, 11, m["banner"])
    if t == 5:
        p = P(3.0, 3.0, 9.5)
        sc.oval(p[0], p[1], 3.6, 2.4, m["gold"], shade(m["gold"], 0.55), 0.45, sil=True)
        sc.oval(p[0] - 0.8, p[1] - 0.7, 1.5, 0.9, "#FFF3B0", None, 0, 0.9)


def nugget(px, py, r):
    return [(px, py - r), (px + r * 0.5, py - r * 0.33), (px + r, py), (px + r * 0.5, py + r * 0.33), (px, py + r),
            (px - r * 0.5, py + r * 0.33), (px - r, py), (px - r * 0.5, py - r * 0.33)]


def gold_mine(sc, m, t):
    base_pad(sc, m, t)
    rock_c = ["#857B6E", "#8C8274", "#958A7C", "#A09484", "#BDB19C"][t - 1]
    crag(sc, 0.2, 0.2, 3.8, 1.4, 22 + t * 3, rock_c)
    crag(sc, 0.2, 0.2, 1.4, 3.8, 16 + t * 2, rock_c, 1)
    # 岩肌の金の鉱脈
    veins = [(1.0, 1.9, 6), (3.2, 1.0, 12), (0.9, 2.8, 5), (3.5, 1.9, 8), (2.9, 1.4, 20)][: 1 + t]
    lf = Lf(1.4)
    sc.lines([[lf(0.5, 10 + t), lf(0.9, 14 + t), lf(1.25, 12 + t)], [lf(3.0, 16 + t * 2), lf(3.35, 19 + t * 2), lf(3.7, 17 + t * 2)]],
             m["gold"], 1.0, 0.85)
    # 坑道の入り口（木の枠。上の段階は石と金）
    ex = 2.3
    gate_col = [m["trim"], m["trim"], "#6A655E", lit(m["base"], 1.1), m["gold"]][t - 1]
    sc.box(ex - 0.9, 1.4, ex + 0.9, 1.7, 0, 18, gate_col, grad=False)
    op = [P(ex - 0.6, 1.7, 0), P(ex + 0.6, 1.7, 0), P(ex + 0.6, 1.7, 13), P(ex, 1.7, 16.5), P(ex - 0.6, 1.7, 13)]
    sc.poly(op, vgrad(op[3][1], op[0][1], "#0E0A0C", "#3A2616"), shade(gate_col, 0.5), 0.5)
    lamp = P(ex, 1.72, 13.6)
    sc.oval(lamp[0], lamp[1] + 2, 4.6, 4.6, "#FFB23C", None, 0, 0.3)
    sc.oval(lamp[0], lamp[1] + 2, 1.5, 1.8, "#FFE58A", "#7A4A14", 0.4)
    # レール・トロッコ・金の山
    sc.lines([[P(2.0, 1.9, 0), P(2.3, 3.8, 0)], [P(2.6, 1.9, 0), P(2.9, 3.8, 0)]], "#4A4650", 0.9)
    sc.lines([[P(2.02 + 0.05 * i, 2.1 + 0.34 * i, 0), P(2.62 + 0.05 * i, 2.1 + 0.34 * i, 0)] for i in range(5)], "#6B4A2A", 0.9, 0.9)
    sc.layer()
    sc.box(2.0, 2.8, 2.9, 3.4, 2, 8, "#6A5644", grad=False)
    sc.ovals([(P(2.1, 3.42, 1.6)[0], P(2.1, 3.42, 1.6)[1], 1.6, 1.6), (P(2.78, 3.42, 1.6)[0], P(2.78, 3.42, 1.6)[1], 1.6, 1.6)],
             "#3A3640", "#1A181E", 0.4)
    p = P(2.45, 3.1, 9)
    sc.oval(p[0], p[1], 5.2, 3.0, m["gold"], shade(m["gold"], 0.5), 0.45, sil=True)
    sc.oval(p[0] - 1, p[1] - 1, 2.4, 1.2, "#FFF3B0", None, 0, 0.9)
    sc.polys([nugget(*P(x, y, z), 2.6) for (x, y, z) in veins], m["gold"], shade(m["gold"], 0.5), 0.4)
    sc.polys([nugget(P(x, y, z)[0] - 0.5, P(x, y, z)[1] - 0.5, 1.0) for (x, y, z) in veins], "#FFF6C8", None, 0, 0.9)
    rock(sc, 3.4, 3.3, 0.75, rock_c)
    if t >= 2:
        crate(sc, 1.05, 3.2, 0.42, 5.5)
    if t >= 3:
        sc.flag(3.4, 0.9, 22 + t * 3, 11, m["banner"])
    if t >= 5:
        g = P(2.3, 1.9, 24)
        sc.glow(g[0], g[1], 16, 9, m["gold"], 0.3)


def warehouse(sc, m, t):
    base_pad(sc, m, t)
    h = 14 + t * 2
    hall(sc, m, t, 0.5, 0.5, 3.5, 2.7, h, roof="gable", axis="x", rh=8 + t, nl=2, nr=2, door=False)
    # 大きな両開きの扉
    lf = Lf(2.7)
    dw, dh = 1.0, min(h - 5.0, 11.0)
    dpts = [lf(2.0 - dw / 2, 0), lf(2.0 + dw / 2, 0), lf(2.0 + dw / 2, dh), lf(2.0 - dw / 2, dh)]
    sc.poly(dpts, m["door"], door_frame(m), 1.0)
    sc.lines([[lf(2.0, 0), lf(2.0, dh)], [lf(2.0 - dw / 2, dh * 0.5), lf(2.0 + dw / 2, dh * 0.5)]], shade(m["door"], 0.55), 0.5, 0.9)
    if t >= 3:
        sc.flag(3.2, 0.8, h + 8 + t, 10, m["banner"])
    # 木箱・樽・麻袋
    sc.layer()
    spots = [(0.5, 3.0), (1.05, 3.25), (3.3, 3.0)][: 1 + (t + 1) // 2]
    for (x, y) in spots:
        crate(sc, x, y, 0.5, 7.0)
    if t >= 3:
        crate(sc, 0.6, 3.08, 0.4, 5.0, shade(WOOD, 1.1), 7.0)
    barrel(sc, 2.7, 3.3, 0.25, 7.5)
    if t >= 2:
        barrel(sc, 3.1, 3.55, 0.2, 6.0)
    sacks(sc, [(1.85, 3.5), (2.15, 3.62)][: 1 + (t >= 2)])
    if t >= 4:
        chest(sc, m, 1.5, 3.0)


def chest(sc, m, x, y, s=0.6, h=5.0):
    """金の縁の宝箱。"""
    c = "#8A4A2A"
    sc.box(x, y, x + s, y + s * 0.8, 0, h, c, grad=False, top=lit(m["gold"], 1.1))
    lf = Lf(y + s * 0.8)
    sc.lines([[lf(x, h - 1.4), lf(x + s, h - 1.4)], [lf(x + s * 0.5, h), lf(x + s * 0.5, h - 2.8)]], m["gold"], 0.9)
    sc.lines([[P(x + s, y, h - 1.4), P(x + s, y + s * 0.8, h - 1.4)]], shd(m["gold"], 0.8), 0.9)


def weapon_rack(sc, x, y, n, ln=1.2):
    """槍の架台。"""
    a, b = P(x, y, 0), P(x + ln, y, 0)
    sc.lines([[a, (a[0], a[1] - 9)], [b, (b[0], b[1] - 9)], [(a[0], a[1] - 7.5), (b[0], b[1] - 7.5)], [(a[0], a[1] - 3.5), (b[0], b[1] - 3.5)]],
             INK, 2.6, cap="round")
    sc.lines([[a, (a[0], a[1] - 9)], [b, (b[0], b[1] - 9)], [(a[0], a[1] - 7.5), (b[0], b[1] - 7.5)], [(a[0], a[1] - 3.5), (b[0], b[1] - 3.5)]],
             "#8A6238", 1.4, cap="round")
    shafts, heads = [], []
    for k in range(n):
        p = P(x + ln * (k + 0.5) / n, y, 0)
        shafts.append([(p[0], p[1] - 1), (p[0] + 1.2, p[1] - 15)])
        heads.append([(p[0] + 1.2 - 1.3, p[1] - 14.6), (p[0] + 1.6, p[1] - 19), (p[0] + 1.2 + 1.5, p[1] - 14.4)])
    sc.lines(shafts, "#5A4630", 0.9, cap="round")
    sc.polys(heads, "#E2E7EC", "#4A4F57", 0.4)


def barracks(sc, m, t):
    base_pad(sc, m, t)
    h = 16 + t * 3
    hall(sc, m, t, 0.6, 0.6, 3.4, 2.6, h, roof="gable" if t < 3 else "flat", axis="x", rh=8 + t, nl=3, nr=2)
    # 壁の盾と垂れ幕
    lf = Lf(2.6)
    zz = 4 + (h - 4) * 0.52
    if t >= 2:
        sc.banner(3.4, 1.6, h - 3, (h - 4) * 0.36, m["banner"], m["gold"])
    shield(sc, lf, 2.0, min(4 + (h - 4) * 0.55, 12.5) + 5.2, 4.6, m["banner"], m["gold"])
    # 旗
    fz = h + (8 + t if t < 3 else 4)
    sc.flag(3.4, 0.6, fz, 14, m["banner"], 8.0)
    sc.flag(0.6, 2.6, fz - (8 + t if t < 3 else 0), 12, lit(m["banner"], 1.15, 0.0), 7.0)
    # 武器架・訓練用のかかし
    sc.layer()
    weapon_rack(sc, 0.45, 3.25, 2 + t // 2)
    dx, dy = P(3.3, 3.25, 0)
    sc.lines([[(dx, dy), (dx, dy - 11)], [(dx - 4, dy - 8), (dx + 4, dy - 8)]], INK, 2.8, cap="round")
    sc.lines([[(dx, dy), (dx, dy - 11)], [(dx - 4, dy - 8), (dx + 4, dy - 8)]], "#8A6238", 1.5, cap="round")
    sc.oval(dx, dy - 7.2, 2.9, 3.6, STRAW, "#7A5A1E", 0.45, sil=True)
    sc.oval(dx, dy - 12.6, 2.1, 2.1, lit(STRAW, 1.1), "#7A5A1E", 0.45, sil=True)
    sc.oval(dx, dy - 7.2, 1.3, 1.6, "#C8382E", None, 0)
    if t >= 4:
        chest(sc, m, 3.4, 2.85, 0.5, 5.0 + t * 0.4)
    if t >= 3:
        torches(sc, [(1.52, 2.6, 13.5), (2.48, 2.6, 13.5)])


def target(sc, x, y, z=12.0, r=6.0):
    tx, ty = P(x, y, z)
    b = P(x, y, 0)
    sc.lines([[(b[0] - 3.5, b[1] + 0.8), (tx, ty)], [(b[0] + 3.5, b[1] - 0.8), (tx, ty)]], "#6B4E2E", 1.3, cap="round")
    sc.oval(tx, ty, r * 0.86, r, "#F4ECD8", "#5B4630", 0.6, sil=True)
    sc.oval(tx, ty, r * 0.6, r * 0.7, "#D03A34", None, 0)
    sc.oval(tx, ty, r * 0.36, r * 0.42, "#F4ECD8", None, 0)
    sc.oval(tx, ty, r * 0.15, r * 0.18, "#D03A34", None, 0)
    sc.line([(tx + 0.6, ty - 0.6), (tx + 6.5, ty - 4.4)], "#4A3A2A", 0.7, cap="round")
    sc.cast([(x, y - 0.2, 0), (x, y + 0.2, 0), (x, y - 0.25, z + r), (x, y + 0.25, z + r)])


def archery_range(sc, m, t):
    base_pad(sc, m, t, cast=False)
    # 射場の草
    ground(sc, 0.3, 2.0, 3.7, 3.8, "#74A84C")
    sc.lines([[P(0.5 + 0.8 * i, 2.15, 0), P(0.5 + 0.8 * i, 3.65, 0)] for i in range(5)], "#8FC062", 0.6, 0.5)
    sc.cast_here()
    hp = 18 + t
    # 東屋（奥の柱 → 作業台 → 手前の柱 → 屋根）
    pc = m["trim"] if t < 4 else m["wall"]
    boxes(sc, [(0.6, 0.6, 0.9, 0.9, 0, hp), (3.0, 0.6, 3.3, 0.9, 0, hp)], pc, cast=True)
    sc.box(0.95, 1.0, 3.0, 1.5, 0, 7, "#946C3C", grad=False)  # 作業台
    bows = []
    for k in range(3):
        a = P(1.25 + k * 0.6, 1.5, 7.5)
        bows.append([(a[0] - 2.4, a[1] - 0.5), (a[0] - 1.0, a[1] - 6), (a[0] + 2.4, a[1] - 9.5)])
    sc.lines(bows, "#5A3A1E", 1.0, cap="round")
    sc.lines([[b[0], b[2]] for b in bows], "#EFE8D6", 0.4, 0.9)
    boxes(sc, [(0.6, 1.9, 0.9, 2.2, 0, hp), (3.0, 1.9, 3.3, 2.2, 0, hp)], pc, cast=True)
    sc.pyramid(0.45, 0.45, 3.45, 2.35, hp, 9 + t, m["roof"], over=0.3, tiles=0 if t < 2 else 3,
               thick=2.4 if t < 2 else 1.6)
    sc.flag(1.95, 1.4, hp + 9 + t, 11, m["banner"])
    # 的
    sc.layer()
    for k in range(2 + t // 3):
        target(sc, 0.9 + k * 1.1, 3.55)
    haybale(sc, 3.42, 2.45, 0.3, 5.5)
    if t >= 3:
        barrel(sc, 0.5, 2.5, 0.18, 6.0)
        sc.lines([[(P(0.5, 2.5, 6)[0] + d, P(0.5, 2.5, 6)[1]), (P(0.5, 2.5, 6)[0] + d * 1.6, P(0.5, 2.5, 6)[1] - 6)] for d in (-1.2, 0, 1.2)],
                 "#EFE8D6", 0.6, cap="round")


def horse(sc, hx, hy, c, s=1.0):
    """柵の中の馬（横向きの影絵に、たてがみと鞍）。"""
    q = lambda dx, dy: (hx + dx * s, hy + dy * s)  # noqa: E731
    body = [q(-7, -3.5), q(-6.5, -6.5), q(-2, -7.5), q(2.5, -7), q(4.2, -10.5), q(5.2, -13.5), q(6.6, -13.2), q(7.2, -11),
            q(9.6, -9.2), q(9.2, -7.6), q(6.8, -8), q(5.6, -4.5), q(4.6, -2.5), q(4.8, 3.5), q(3.4, 3.5), q(3, -1.5),
            q(-3, -1.5), q(-3.6, 3.5), q(-5, 3.5), q(-5.4, -2), q(-7.6, 0.5), q(-8.2, -0.5)]
    sc.poly(body, vgrad(hy - 13 * s, hy + 3 * s, lit(c, 1.12), shade(c, 0.82)), edge(c, 0.4), 0.5, sil=True)
    mane = shade(c, 0.45) if c != "#F4F0E6" else "#C9C2B4"
    sc.poly([q(4.0, -10.2), q(5.0, -13.6), q(6.2, -13.4), q(5.0, -9.5), q(3.4, -7.0)], mane, None, 0)
    sc.poly([q(-1.6, -7.4), q(1.6, -7.2), q(1.4, -4.6), q(-1.4, -4.8)], "#B8323A", "#5A1620", 0.35)


def stable(sc, m, t):
    base_pad(sc, m, t)
    h = 13 + t * 2
    hall(sc, m, t, 0.5, 0.4, 3.2, 2.2, h, roof="gable", axis="y", rh=7 + t, nl=3, nr=0, door=False)
    # 馬房の扉（右前面。上半分が開いている）
    rf = Rf(3.2)
    stalls, tops = [], []
    for k in range(3):
        y = 0.75 + k * 0.56
        stalls.append([rf(y - 0.2, 0), rf(y + 0.2, 0), rf(y + 0.2, 5), rf(y - 0.2, 5)])
        tops.append([rf(y - 0.2, 5), rf(y + 0.2, 5), rf(y + 0.2, min(10.5, h - 4)), rf(y - 0.2, min(10.5, h - 4))])
    sc.polys(tops, "#1E1418", shd(m["frame"], 0.7), 0.7)
    sc.polys(stalls, shd(m["door"], 0.82), shd(m["frame"], 0.7), 0.7)
    if t >= 3:
        sc.flag(3.2, 0.4, h + 7 + t, 11, m["banner"])
    # 柵と馬・干し草・水桶
    sc.layer()
    horse(sc, *P(2.0, 3.0, 8), "#8A5630" if t < 4 else "#F4F0E6")
    fence(sc, [(0.3, 3.3), (1.1, 3.3), (1.9, 3.3), (2.8, 3.3), (3.6, 3.3), (3.6, 2.5)], 7.0)
    haybale(sc, 3.62, 1.3, 0.3, 5.5)
    if t >= 2:
        haybale(sc, 3.4, 1.72, 0.26, 4.6)
    boxes(sc, [(0.35, 2.5, 1.05, 2.8, 0, 3.4)], "#8E6038", top="#6FB4E0", cast=True)
    if t >= 4:
        sacks(sc, [(0.42, 3.72), (0.72, 3.8)])


def siege_workshop(sc, m, t):
    base_pad(sc, m, t)
    h = 15 + t * 2
    hall(sc, m, t, 0.4, 0.4, 2.4, 2.4, h, roof="gable", axis="x", rh=8 + t, nl=2, nr=2)
    chimney(sc, m, 0.65, 0.75, h + 2, h + 11 + t, 0.36, smk=t >= 2)
    sc.flag(0.5, 0.5, h + 8 + t, 11, m["banner"])
    # 投石機
    sc.layer()
    wood = "#8A6238"
    boxes(sc, [(2.5, 2.6, 3.7, 2.82, 0, 3.4), (2.5, 3.48, 3.7, 3.7, 0, 3.4)], wood, cast=True)
    sc.ovals([(P(x, y, 2.4)[0], P(x, y, 2.4)[1], 2.3, 2.7) for (x, y) in ((2.62, 3.72), (3.58, 3.72), (3.72, 2.72))], "#6E4C2A",
             "#2E1E10", 0.5, sil=True)
    piv = P(3.1, 3.15, 15)
    tip = P(2.55, 2.55, 25)
    tail = P(3.55, 3.6, 8)
    frame = [[P(2.9, 2.72, 3.4), piv], [P(3.3, 2.72, 3.4), piv], [P(2.9, 3.6, 3.4), piv], [P(3.3, 3.6, 3.4), piv]]
    sc.lines(frame + [[tail, tip]], INK, 3.6, cap="round")
    sc.lines(frame, shade(wood, 0.82), 1.6, cap="round")
    sc.line([tail, tip], lit(wood, 1.12), 2.0, cap="round")
    sc.oval(tail[0], tail[1] + 1.5, 3.2, 3.4, "#6A6E78", "#2E3038", 0.5, sil=True)
    sc.oval(tip[0], tip[1] - 0.5, 2.9, 2.9, "#A8A49C", "#3A3A3A", 0.5, sil=True)
    sc.oval(tip[0] - 0.8, tip[1] - 1.3, 1.1, 1.0, "#D8D4CC", None, 0, 0.8)
    # 石の弾の山
    balls = [(P(2.2, 3.55, 2)[0] + dx, P(2.2, 3.55, 2)[1] + dy, 2.1, 2.1) for dx, dy in ((-2.2, 0), (2.2, 0.4), (0, -3.2))]
    sc.ovals(balls, "#9C988F", "#3E3C3A", 0.45, sil=True)
    sc.ovals([(x - 0.6, y - 0.7, 0.8, 0.7) for x, y, _, _ in balls], "#D2CEC6", None, 0, 0.8)
    if t >= 3:
        crate(sc, 0.5, 2.85, 0.46, 6.0)
    if t >= 4:
        chest(sc, m, 1.2, 3.1, 0.55, 5.0)


def hospital(sc, m, t):
    base_pad(sc, m, t)
    h = 16 + t * 3
    hall(sc, m, t, 0.5, 0.5, 3.5, 3.0, h, roof="gable" if t < 4 else "flat", axis="x", rh=8 + t, nl=3, nr=2)
    # 緑の十字（薬草の印）: 白い円盤にのせる
    cx, cy = P(2.0, 3.0, h - 3.2) if t >= 4 else P(2.0, 3.25, h + 1.5)
    green = "#35A35C"
    sc.oval(cx, cy, 6.6, 6.6, "#FFFDF4", "#1F6A3C", 0.8, sil=True)
    a, b = 1.5, 4.6
    sc.poly([(cx - a, cy - b), (cx + a, cy - b), (cx + a, cy - a), (cx + b, cy - a), (cx + b, cy + a), (cx + a, cy + a),
             (cx + a, cy + b), (cx - a, cy + b), (cx - a, cy + a), (cx - b, cy + a), (cx - b, cy - a), (cx - a, cy - a)],
            green, "#1F6A3C", 0.4)
    if t >= 3:
        sc.flag(3.4, 0.6, h + 6 + t, 11, green)
    if t >= 2:
        sc.banner(3.5, 1.75, h - 3, (h - 4) * 0.34, "#F7F3E6", green)
    # 薬草園と鉢
    sc.layer()
    ground(sc, 0.3, 3.2, 1.9, 3.8, "#6B4A2A")
    herbs = [(P(0.5 + k * 0.42, 3.5, 2.2)[0], P(0.5 + k * 0.42, 3.5, 2.2)[1], 3.0, 2.4) for k in range(4)]
    sc.ovals(herbs, "#4FAE5C", "#1F6A3C", 0.45, sil=True)
    sc.ovals([(x - 0.8, y - 0.8, 1.2, 0.9) for x, y, _, _ in herbs], "#9FE08A", None, 0, 0.9)
    sc.ovals([(x + 1.0, y - 0.2, 0.7, 0.7) for x, y, _, _ in herbs[::2]], "#F26D8A", None, 0)
    barrel(sc, 2.75, 3.55, 0.2, 5.5, "#8E6038")
    if t >= 3:
        boxes(sc, [(3.0, 3.3, 3.7, 3.56, 0, 3.0)], WOOD, cast=True)  # 長いす
    if t >= 5:
        g = P(2.0, 3.0, 28)
        sc.glow(g[0], g[1], 16, 10, "#FFFFFF", 0.3)


def academy(sc, m, t):
    base_pad(sc, m, t)
    h = 18 + t * 3
    contact(sc, 0.6, 0.6, 3.4, 3.35)
    # 入口の段
    sc.box(0.55, 2.9, 3.45, 3.5, 0, 2.0, lit(m["base"], 1.1), grad=False)
    hall(sc, m, t, 0.6, 0.6, 3.4, 3.0, h, roof="flat", nl=3, nr=2, shadow=False)
    # 列柱と、その上の梁（青い垂れ幕つき）
    col = lit(m["wall"], 1.12)
    boxes(sc, [(0.78 + k * 0.72, 3.02, 1.02 + k * 0.72, 3.3, 2.0, h - 3) for k in range(4)], col)
    sc.lines([[P(0.9 + k * 0.72, 3.3, 3), P(0.9 + k * 0.72, 3.3, h - 4)] for k in range(4)], shade(col, 0.8), 0.4, 0.6)
    sc.box(0.6, 3.0, 3.4, 3.35, h - 3, h - 0.5, lit(m["trim"], 1.05) if t >= 4 else m["trim"], grad=False, cast=False)
    # ドーム（鼓胴 + 半球 + 金の頂）
    dz = h + 2.5
    sc.cylinder(2.0, 1.8, 0.9, dz, dz + 4.5, shade(m["wall"], 1.03), cap=False, cast=False)
    sc.lines([[P(2.0 + 0.9 * math.cos(a), 1.8 + 0.9 * math.sin(a), dz + 0.8), P(2.0 + 0.9 * math.cos(a), 1.8 + 0.9 * math.sin(a), dz + 3.7)]
              for a in (math.radians(d) for d in (112, 84, 56, 28, 0, -28))], m["win"], 1.1, 0.75)
    dome_c = m["roof"] if t < 5 else m["gold"]
    dh = 8 + t * 2
    sc.dome(2.0, 1.8, 0.9, dz + 4.5, dh, dome_c, 0.06)
    cx, cy = P(2.0, 1.8, dz + 4.5)
    ax = 0.96 * U * math.sqrt(2)
    sc.lines([[(cx + ax * f, cy + 6.5 * (1 - f * f) ** 0.5 * 0.5), (cx + ax * f * 0.2, cy - dh)] for f in (-0.55, 0.0, 0.55)],
             lit(dome_c, 1.35) if t < 4 else m["gold"], 0.5, 0.7)
    top = P(2.0, 1.8, dz + 4.5 + dh)
    if t >= 4:
        sc.line([top, (top[0], top[1] - 6)], m["gold"], 1.2, cap="round")
        sc.oval(top[0], top[1] - 7, 1.6, 1.6, m["gold"], shade(m["gold"], 0.5), 0.4, sil=True)
    else:
        sc.oval(top[0], top[1] - 0.6, 1.3, 1.3, m["gold"], shade(m["gold"], 0.5), 0.4, sil=True)
    if t >= 2:
        sc.banner(3.4, 1.8, h - 2, (h - 4) * 0.36, "#2F5BB0" if t < 5 else m["banner"], m["gold"])
    if t >= 3:
        # 本の看板
        bx, by = P(0.5, 2.0, 12)
        sc.poly([(bx - 3.6, by - 3), (bx, by - 1.8), (bx + 3.6, by - 3), (bx + 3.6, by + 3), (bx, by + 4.2), (bx - 3.6, by + 3)],
                "#FBF1D4", "#6B4E2E", 0.6, sil=True)
        sc.lines([[(bx, by - 1.8), (bx, by + 4.2)], [(bx - 2.6, by - 0.6), (bx - 0.8, by)], [(bx + 0.8, by), (bx + 2.6, by - 0.6)]],
                 "#6B4E2E", 0.5, 0.9)
    # 入口の両脇の植木（上の段階は金の壺）
    sc.layer()
    for (x, y) in ((0.42, 3.62), (3.56, 3.62)):
        p = P(x, y, 0)
        pot = m["gold"] if t >= 4 else "#A6603C"
        sc.poly([(p[0] - 2.2, p[1] - 4), (p[0] + 2.2, p[1] - 4), (p[0] + 1.5, p[1] + 0.6), (p[0] - 1.5, p[1] + 0.6)], pot,
                edge(pot), 0.45, sil=True)
        sc.oval(p[0], p[1] - 6.6, 3.2, 3.4, "#3F9A4E", "#1F5A30", 0.45, sil=True)
        sc.oval(p[0] - 0.9, p[1] - 7.6, 1.4, 1.2, "#8FD67A", None, 0, 0.9)


def wall_building(sc, m, t):
    base_pad(sc, m, t)
    wh = 14 + t * 3
    wc = m["base"] if t < 3 else m["wall"]
    stone = dict(m, style=m["style"] if t >= 3 else "stone")
    gold_top = m["gold"] if t >= 5 else None
    # 長い城壁（奥と左）
    sc.box(0.0, 0.0, 4.0, 0.9, 0, wh, wc)
    wall_tex(sc, stone, 0.0, 0.0, 4.0, 0.9, 0, wh + 2, wc)
    crenel(sc, 0.0, 0.0, 4.0, 0.9, wh, m["wall"], 6, 3.5, top=gold_top)
    sc.box(0.0, 0.0, 0.9, 4.0, 0, wh, wc)
    wall_tex(sc, stone, 0.0, 0.0, 0.9, 4.0, 0, wh + 2, wc)
    crenel(sc, 0.0, 0.0, 0.9, 4.0, wh, m["wall"], 6, 3.5, top=gold_top)
    if t >= 5:
        sc.line([P(0, 0.9, wh - 1), P(4, 0.9, wh - 1), P(4, 0, wh - 1)], m["gold"], 1.1)
    for (x, y) in [(0.45, 0.45), (3.55, 0.45)]:
        tower(sc, m, t, x, y, 0.55, wh + 8, 8 + t, flag=False, wall=m["wall"])
    # 門楼
    sc.layer()
    contact(sc, 1.4, 3.0, 2.8, 3.9, 0.3)
    gw = m["wall"]
    sc.box(1.4, 3.0, 2.8, 3.9, 0, wh + 8, gw, eave=3.0, faces="rl")
    wall_tex(sc, stone, 1.4, 3.0, 2.8, 3.9, 0, wh + 8, gw)
    sc.door_left(2.1, 0.9, 15, 3.9, "#33241A", frame=lit(m["base"], 1.2))
    # 落とし格子
    lf = Lf(3.9)
    sc.lines([[lf(2.1 + d, 0.5), lf(2.1 + d, 11.5 + (1.6 if d == 0 else 0))] for d in (-0.26, 0.0, 0.26)]
             + [[lf(1.7, z), lf(2.5, z)] for z in (4.0, 8.0)], "#6A6E78", 0.6, 0.9)
    window_set(sc, m, Rf(2.8), [(3.45, wh + 2.5)], 0.26, 6.0, dark=True)
    gable_roof(sc, m, 1.4, 3.0, 2.8, 3.9, wh + 8, 6 + t, "x", over=0.15, wall=gw, ornament=False)
    torches(sc, [(1.56, 3.9, 13.0), (2.64, 3.9, 13.0)])
    if t >= 3:
        sc.banner(2.8, 3.45, wh - 1.5, wh * 0.36, m["banner"], m["gold"])
    tower(sc, m, t, 0.45, 3.55, 0.55, wh + 8, 8 + t, flag=t >= 3, wall=m["wall"], fh=10)
    boxes(sc, [(3.0 + 0.34 * i, 3.72, 3.2 + 0.34 * i, 3.9, 0, 3.0) for i in range(3)], shade(wc, 0.96), cast=True)
    if t >= 2:
        sc.flag(2.1, 3.45, wh + 14 + t, 12, m["banner"], 8.0)


def watchtower(sc, m, t):
    base_pad(sc, m, t)
    h = [34, 44, 54, 64, 76][t - 1]
    contact(sc, 1.0, 1.0, 3.0, 3.0, 0.36)
    sc.box(1.1, 1.1, 2.9, 2.9, 0, 6, m["base"], grad=False)
    wall = m["wall"]
    sc.cylinder(2.0, 2.0, 0.75, 6, h, wall, cap=False)
    if m["style"] == "wood":
        sc.lines([[P(2.0 + 0.75 * math.cos(a), 2.0 + 0.75 * math.sin(a), 6), P(2.0 + 0.75 * math.cos(a), 2.0 + 0.75 * math.sin(a), h)]
                  for a in (math.radians(d) for d in (118, 96, 74, 52, 30, 8, -14, -32))], shade(wall, 0.62), 0.4, 0.5)
        sc.lines([ring(2.0, 2.0, 0.755, 6 + (h - 6) * f) for f in (0.3, 0.66)], m["trim"], 1.1)
    else:
        n = max(3, int((h - 6) / 9))
        sc.lines([ring(2.0, 2.0, 0.75, 6 + (h - 6) * (k + 1) / (n + 1.0)) for k in range(n)], shd(wall, 0.52), 0.4, 0.45)
    # 入口と窓
    sc.door_left(2.0, 0.5, 9.5, 2.0 + 0.75 * 0.96, m["door"], frame=door_frame(m), z0=6, grad=False)
    window_set(sc, m, Lf(2.0 + 0.75 * 0.9), [(2.0, 6 + (h - 6) * (k + 1) / 4.0) for k in range(1, 3)], 0.32, 6.0)
    # 見張り台（張り出しと胸壁）
    sc.cylinder(2.0, 2.0, 0.95, h - 3.5, h, shade(wall, 0.96), cap=False, cast=False)
    sc.cylinder(2.0, 2.0, 1.15, h, h + 5, shade(wall, 1.04), top=shade(mix(wall, m["base"], 0.5), 0.86))
    if t >= 4:
        sc.line(ring(2.0, 2.0, 1.15, h + 2.5), m["gold"], 1.0)
    # 火の見（上のあかり）
    fx, fy = P(2.0, 2.0, h + 6)
    if t >= 2:
        sc.glow(fx, fy - 2, 10, 8, "#FFB23C", 0.55 if t >= 4 else 0.4)
        sc.ovals([(fx, fy - 2.2, 2.2, 3.2)], "#FF8A2A", None, 0)
        sc.ovals([(fx, fy - 1.6, 1.0, 1.7)], "#FFE58A", None, 0)
    merl = []
    for k in range(8):
        ang = math.radians(k * 45 + 22)
        x, y = 2.0 + 1.1 * math.cos(ang), 2.0 + 1.1 * math.sin(ang)
        if x + y > 3.6:
            merl.append((x - 0.14, y - 0.14, x + 0.14, y + 0.14, h + 5, h + 8.5))
    boxes(sc, merl, shade(wall, 1.02), m["gold"] if t >= 5 else None)
    # 屋根（4 本の柱に載る円錐）
    posts = [[P(2.0 + 0.6 * math.cos(a), 2.0 + 0.6 * math.sin(a), h + 5), P(2.0 + 0.6 * math.cos(a), 2.0 + 0.6 * math.sin(a), h + 12)]
             for a in (math.radians(d) for d in (135, 45, -45))]
    sc.lines(posts, INK, 2.2)
    sc.lines(posts, m["trim"] if t < 4 else m["wall"], 1.1)
    sc.cone(2.0, 2.0, 0.65, h + 11, 10 + t * 2 - 6, m["roof"], 0.2)
    if t >= 4:
        px, py = P(2.0, 2.0, h + 15 + t * 2 + 0.6)
        sc.oval(px, py, 1.5, 1.5, m["gold"], shade(m["gold"], 0.5), 0.4, sil=True)
    sc.flag(2.0, 2.0, h + 15 + t * 2, 13, m["banner"], 8.0)
    # 足元の樽と木箱
    sc.layer()
    barrel(sc, 3.2, 3.2, 0.2, 6.0)
    if t >= 3:
        crate(sc, 0.75, 3.05, 0.42, 5.5)


def stall(sc, x, y, stripes, goods=True, w=1.4, d=1.2, hgt=17.0):
    """屋台（台・商品・4 本の柱・縞の日よけ）。"""
    boxes(sc, [(x, y, x + w, y + d, 0, 6)], "#946C3C", cast=True)
    if goods:
        g = [(P(x + 0.32, y + 0.45, 7)[0], P(x + 0.32, y + 0.45, 7)[1], 2.4, 1.7), (P(x + 0.8, y + 0.85, 7)[0], P(x + 0.8, y + 0.85, 7)[1], 2.2, 1.5),
             (P(x + 1.12, y + 0.4, 7)[0], P(x + 1.12, y + 0.4, 7)[1], 1.9, 1.4)]
        sc.ovals(g[:1], "#E8B84A", "#7A5A1E", 0.35)
        sc.ovals(g[1:2], "#D2503E", "#6A2018", 0.35)
        sc.ovals(g[2:], "#6FB24A", "#2E5A20", 0.35)
    posts = [[P(px, py, 0), P(px, py, hgt)] for (px, py) in ((x, y), (x + w, y), (x, y + d), (x + w, y + d))]
    sc.lines(posts, INK, 2.2)
    sc.lines(posts, "#7A5530", 1.1)
    # 日よけ（手前へ下がる片流れ + 垂れ）
    zb, zf = hgt + 3.0, hgt - 0.5
    a0, a1 = x - 0.12, x + w + 0.12
    b0, b1 = y - 0.12, y + d + 0.14
    sc.sil([P(a0, b0, zb), P(a1, b0, zb), P(a1, b1, zf), P(a1, b1, zf - 2.6), P(a0, b1, zf - 2.6), P(a0, b1, zf)])
    sc.cast([(a0, b0, zb), (a1, b0, zb), (a1, b1, zf), (a0, b1, zf)])
    n = 5
    cols = ([], [])
    val = ([], [])
    for k in range(n):
        xa, xb = a0 + (a1 - a0) * k / n, a0 + (a1 - a0) * (k + 1) / n
        cols[k % 2].append([P(xa, b0, zb), P(xb, b0, zb), P(xb, b1, zf), P(xa, b1, zf)])
        val[k % 2].append([P(xa, b1, zf), P(xb, b1, zf), P(xb, b1, zf - 2.2), P((xa + xb) / 2, b1, zf - 3.2), P(xa, b1, zf - 2.2)])
    for i in (0, 1):
        c = stripes[i]
        sc.polys(cols[i], grad_perp(P(a0, b0, zb), P(a1, b0, zb), P(a0, b1, zf), [lit(c, 1.1), shade(c, 0.88)]), edge(c, 0.5), 0.35)
        sc.polys(val[i], shade(c, 0.8), edge(c, 0.5), 0.35)
    sc.poly([P(a1, b0, zb), P(a1, b1, zf), P(a1, b1, zf - 2.2), P(a1, b0, zb - 2.2)], shd(stripes[0], 0.7), edge(stripes[0], 0.5), 0.35)


def trading_post(sc, m, t):
    base_pad(sc, m, t)
    # 屋台（縞模様の日よけ）
    stripes = [("#D04A40", "#F6EFDC"), ("#3F76D0", "#F6EFDC"), ("#3FA862", "#F6EFDC"), ("#9146D0", "#F6EFDC"),
               ("#EBBE3A", "#FBF6E6")][t - 1]
    spots = [(0.4, 0.4), (2.2, 0.4), (0.4, 2.2)][: 1 + (t + 1) // 2] + [(2.2, 2.2)] * (1 if t >= 4 else 0)
    spots.sort(key=lambda p: p[0] + p[1])
    for i, (x, y) in enumerate(spots):
        if i:
            sc.layer()
        stall(sc, x, y, stripes)
    if t >= 3:
        sc.flag(2.8, 0.8, 22 + t, 11, m["banner"])
    sc.layer()
    if len(spots) < 4:
        crate(sc, 2.4, 1.75, 0.5, 6.0)
        barrel(sc, 3.2, 2.4, 0.22, 6.5)
        sacks(sc, [(2.5, 3.3), (2.8, 3.45)])
    if len(spots) < 2:
        crate(sc, 2.6, 0.8, 0.5, 6.0)
        barrel(sc, 0.9, 2.5, 0.24, 7.0)
    if t >= 5:
        g = P(1.6, 1.4, 20)
        sc.glow(g[0], g[1], 20, 11, m["gold"], 0.26)
    # 硬貨の看板
    cx, cy = P(1.0, 3.7, 14)
    b = P(1.0, 3.7, 0)
    sc.line([b, (cx, cy + 4)], "#5A4630", 1.4, cap="round")
    sc.oval(cx, cy, 4.8, 4.8, Radial(cx - 1.5, cy - 1.5, 6.5, [lit(m["gold"], 1.35), m["gold"], shade(m["gold"], 0.7)]),
            "#7A5A14", 0.7, sil=True)
    sc.oval(cx, cy, 3.0, 3.0, None, "#9A7418", 0.5, salpha=0.9)
    sc.line([(cx, cy - 1.8), (cx, cy + 1.8)], "#9A7418", 0.8, cap="round")


def alliance_center(sc, m, t):
    base_pad(sc, m, t)
    h = 20 + t * 3
    contact(sc, 0.5, 0.5, 3.5, 3.5, 0.34, 0.1)
    if t >= 3:
        for (x, y) in [(0.5, 1.0), (3.5, 0.6)]:
            sc.flag(x, y, 0, 18 + t, lit(m["banner"], 1.12, 0.0))
    sc.cylinder(2.0, 2.0, 1.55, 0, 5, m["base"], top=lit(m["base"], 1.1))
    wall = m["wall"]
    sc.cylinder(2.0, 2.0, 1.35, 5, h, wall, cap=False)
    # 壁の付け柱と帯
    pil = [[P(2.0 + 1.35 * math.cos(a), 2.0 + 1.35 * math.sin(a), 5), P(2.0 + 1.35 * math.cos(a), 2.0 + 1.35 * math.sin(a), h - 3)]
           for a in (math.radians(d) for d in (126, 104, 62, 28, 6, -16, -36))]
    sc.lines(pil, m["trim"] if m["style"] in ("wood", "timber") else shd(wall, 0.6), 1.0 if m["style"] in ("wood", "timber") else 0.5,
             1.0 if m["style"] in ("wood", "timber") else 0.5)
    sc.cylinder(2.0, 2.0, 1.42, h - 3.5, h, shade(wall, 1.03) if t < 4 else m["gold"], cap=False, cast=False)
    # 窓（手前の左右）と扉
    wz = 5 + (h - 5) * 0.55
    win = []
    for d in (118, 100, 64, 46):
        a = math.radians(d)
        win.append((2.0 + 1.37 * math.cos(a), 2.0 + 1.37 * math.sin(a)))
    glows = [(P(x, y, wz)[0], P(x, y, wz)[1], 4.2, 6.0) for x, y in win]
    sc.ovals(glows, GLOW, None, 0, 0.22)
    sc.polys([[P(x - 0.12, y, wz - 4), P(x + 0.12, y, wz - 4), P(x + 0.12, y, wz + 3), P(x, y, wz + 4.6), P(x - 0.12, y, wz + 3)]
              for x, y in win], m["lit"], m["frame"], 0.7)
    sc.door_left(2.0, 0.9, 13.5, 3.36, m["door"], frame=door_frame(m))
    # 垂れ幕
    sc.banner(3.36, 2.0, h - 4.5, 12, m["banner"], m["gold"])
    # ドーム
    dc = m["roof"] if t < 5 else m["gold"]
    dh = 11 + t * 2
    sc.dome(2.0, 2.0, 1.45, h, dh, dc)
    cx, cy = P(2.0, 2.0, h)
    ax = 1.45 * U * math.sqrt(2)
    sc.lines([[(cx + ax * f, cy + ax * 0.5 * (1 - f * f) ** 0.5), (cx + ax * f * 0.15, cy - dh)] for f in (-0.62, -0.2, 0.3, 0.7)],
             lit(dc, 1.3) if t < 4 else lit(m["gold"], 1.1), 0.5, 0.6)
    # 大旗（紋章つき）
    top = P(2.0, 2.0, h + dh)
    px, py = P(2.0, 2.0, h + 20 + t * 3)
    sc.line([top, (px, py)], "#4A3A2A", 1.3, cap="round")
    fl = [(px, py), (px + 11, py + 3), (px + 9, py + 8), (px + 11, py + 12), (px, py + 10)]
    sc.poly(fl, Linear(px, 0, px + 11, 0, [lit(m["banner"], 1.12), shade(m["banner"], 0.8)]), edge(m["banner"], 0.5), 0.5, sil=True)
    sc.oval(px + 5, py + 6, 2.3, 2.3, m["gold"], shade(m["gold"], 0.5), 0.4)
    sc.oval(px, py - 0.6, 1.2, 1.2, m["gold"], shade(m["gold"], 0.5), 0.3, sil=True)
    # 手前の旗とかがり火
    sc.layer()
    if t >= 3:
        sc.flag(1.0, 3.3, 0, 18 + t, lit(m["banner"], 1.12, 0.0))
    for (x, y) in ((1.25, 3.6), (2.75, 3.6))[: 2 if t >= 2 else 0]:
        p = P(x, y, 0)
        sc.poly([(p[0] - 2.4, p[1] - 5), (p[0] + 2.4, p[1] - 5), (p[0] + 1.2, p[1] - 2.4), (p[0] + 1.6, p[1] + 0.6), (p[0] - 1.6, p[1] + 0.6),
                 (p[0] - 1.2, p[1] - 2.4)], "#5A5660", "#2A2830", 0.45, sil=True)
        sc.oval(p[0], p[1] - 7, 5.5, 5.5, "#FFB23C", None, 0, 0.28)
        sc.oval(p[0], p[1] - 7, 1.9, 2.8, "#FF8A2A", None, 0)
        sc.oval(p[0], p[1] - 6.4, 0.9, 1.5, "#FFE58A", None, 0)


def tavern(sc, m, t):
    base_pad(sc, m, t)
    h = 16 + t * 3
    hall(sc, m, t, 0.5, 0.5, 3.5, 2.8, h, roof="gable", axis="y", rh=9 + t, nl=3, nr=3)
    # 煙突と煙
    chimney(sc, m, 2.8, 0.7, h + 2, h + 12 + t, 0.4)
    if t >= 4:
        sc.flag(3.4, 0.6, 28 + t * 3, 11, m["banner"])
    # 入口のあかり
    d = P(2.0, 2.82, 5)
    sc.glow(d[0], d[1] + 2, 9, 6, GLOW, 0.4)
    # ジョッキの看板（腕木から下がる）
    sc.layer()
    z = 21 + t * 2
    a, b = P(0.5, 2.8, z), P(0.5, 3.45, z)
    sc.line([a, b], "#3A2A1E", 1.5, cap="round")
    s = P(0.5, 3.25, z - 1)
    sc.line([(s[0], s[1]), (s[0], s[1] + 2)], "#3A2A1E", 0.6)
    board = [(s[0] - 4.6, s[1] + 2), (s[0] + 4.6, s[1] + 2), (s[0] + 4.6, s[1] + 10), (s[0], s[1] + 12), (s[0] - 4.6, s[1] + 10)]
    sc.poly(board, "#6E4526", "#2E1C10", 0.6, sil=True)
    sc.poly([(s[0] - 2.2, s[1] + 4.6), (s[0] + 1.4, s[1] + 4.6), (s[0] + 1.4, s[1] + 9.4), (s[0] - 2.2, s[1] + 9.4)], "#E9B44A", "#7A5210", 0.4)
    sc.poly([(s[0] - 2.4, s[1] + 3.6), (s[0] + 1.6, s[1] + 3.6), (s[0] + 1.6, s[1] + 5.2), (s[0] - 2.4, s[1] + 5.2)], "#FFFDF4", None, 0)
    sc.line([(s[0] + 1.6, s[1] + 5.6), (s[0] + 3.0, s[1] + 6.4), (s[0] + 1.6, s[1] + 8.2)], "#7A5210", 0.7)
    # 樽・長いす
    barrel(sc, 2.3, 3.3, 0.22, 6.5)
    barrel(sc, 2.82, 3.36, 0.2, 6.0)
    if t >= 2:
        barrel(sc, 2.55, 3.3, 0.18, 5.0, "#9A6A3E", 6.5)
        boxes(sc, [(0.9, 3.25, 1.7, 3.5, 0, 3.0)], WOOD, cast=True)
    if t >= 3:
        torches(sc, [(1.55, 2.8, 12.5), (2.45, 2.8, 12.5)])


def builders_hut(sc, m, t):
    base_pad(sc, m, t)
    h = 12 + t * 2
    hall(sc, m, t, 0.7, 0.7, 2.6, 2.4, h, roof="gable", axis="x", rh=6 + t, nl=1, nr=1)
    # 足場（柱・筋交い・足場板）
    a0, a1, b0, b1 = P(2.95, 0.5, 0), P(2.95, 0.5, 28), P(2.95, 2.5, 0), P(2.95, 2.5, 28)
    c0, c1 = P(3.45, 0.5, 0), P(3.45, 2.5, 0)
    segs = [[a0, a1], [b0, b1], [P(2.95, 0.5, 14), P(2.95, 2.5, 14)], [a1, b1], [a0, P(2.95, 2.5, 14)], [P(2.95, 0.5, 14), b1]]
    back = [[c0, P(3.45, 0.5, 28)], [P(3.45, 0.5, 28), a1]]
    sc.lines(segs + back, INK, 2.6, cap="round")
    sc.lines(back, "#7A5530", 1.2, cap="round")
    front = [[c1, P(3.45, 2.5, 28)], [P(3.45, 2.5, 28), b1]]
    plank = [P(2.95, 0.5, 14), P(3.45, 0.5, 14), P(3.45, 2.5, 14), P(2.95, 2.5, 14)]
    plank2 = [P(2.95, 0.5, 28), P(3.45, 0.5, 28), P(3.45, 2.5, 28), P(2.95, 2.5, 28)]
    sc.polys([plank, plank2], "#D9B27A", "#6B4A2A", 0.45, sil=True)
    sc.lines(segs[:4], "#9A6E3E", 1.3, cap="round")
    sc.lines(segs[4:], "#7A5530", 0.9, cap="round")
    sc.lines(front, INK, 2.6, cap="round")
    sc.lines(front, "#9A6E3E", 1.3, cap="round")
    sc.cast_box(2.95, 0.5, 3.45, 2.5, 0, 28)
    if t >= 4:
        sc.flag(2.6, 0.7, h + 6 + t, 10, m["banner"])
    # 工具（槌）・木箱・板・石材
    sc.layer()
    hx, hy = P(1.0, 3.3, 8)
    sc.line([(hx, hy + 6), (hx + 5, hy - 9)], INK, 2.8, cap="round")
    sc.line([(hx, hy + 6), (hx + 5, hy - 9)], "#8A6238", 1.5, cap="round")
    sc.poly([(hx + 1.2, hy - 12.5), (hx + 9.2, hy - 8.4), (hx + 7.6, hy - 5), (hx - 0.4, hy - 9)], "#9A9EA8", "#33363C", 0.5, sil=True)
    sc.line([(hx + 1.6, hy - 11.4), (hx + 8.2, hy - 8.0)], "#D8DCE2", 0.6, 0.8)
    crate(sc, 1.8, 3.1, 0.5, 5.0)
    boxes(sc, [(2.5, 3.3, 3.7, 3.56, 0, 1.6), (2.56, 3.34, 3.64, 3.52, 1.6, 3.2)], "#D9B27A", cast=True)
    if t >= 2:
        boxes(sc, [(0.3, 2.7, 0.7, 3.0, 0, 3.2)] + ([(0.36, 2.74, 0.66, 2.98, 3.2, 6.0)] if t >= 3 else []), lit(m["base"], 1.12), cast=True)


BUILDERS = {
    "castle": castle, "farm": farm, "sawmill": sawmill, "quarry": quarry, "gold_mine": gold_mine,
    "warehouse": warehouse, "barracks": barracks, "archery_range": archery_range, "stable": stable,
    "siege_workshop": siege_workshop, "hospital": hospital, "academy": academy, "wall": wall_building,
    "watchtower": watchtower, "trading_post": trading_post, "alliance_center": alliance_center,
    "tavern": tavern, "builders_hut": builders_hut,
}


# M11 T11.4: 城のスキン（配色の差し替え。形は同じ）。キーは MAT と同じ。
SKIN_PALETTES = {
    "ice": {"wall": "#DDECF8", "roof": "#4F97D8", "trim": "#BFD6EA", "banner": "#3F9AE8", "gold": "#CFEAFA",
            "pad": "#BCD0E0", "base": "#A3B9CE", "frame": "#8FB4D2", "lit": "#E6FAFF", "door": "#4A6A8A"},
    "gold": {"wall": "#EBD9A4", "roof": "#D4A522", "trim": "#B08A1E", "banner": "#C0222C", "gold": "#FFD84A",
             "pad": "#DCC88C", "base": "#BCA25E", "frame": "#B08A1E", "door": "#7A4A1E"},
    "dragon": {"wall": "#3A3038", "roof": "#A81C30", "trim": "#C0283A", "banner": "#D02A3C", "gold": "#E8A33C",
               "pad": "#453C42", "base": "#554A52", "frame": "#7A2030", "lit": "#FF9A3C", "door": "#2A1A1E"},
    "sakura": {"wall": "#F7E3E8", "roof": "#E57A9C", "trim": "#C97A92", "banner": "#F0508A", "gold": "#FFC1D3",
               "pad": "#EBD3DA", "base": "#CFAFB9", "frame": "#B8607E", "door": "#8A3A54"},
}


def build(name, tier, skin=None):
    sc = new_scene()
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
