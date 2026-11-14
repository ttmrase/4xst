#!/usr/bin/env python3
"""sprites3d_military.py - military / civic buildings and the castle skins for the 3D sprite pipeline.

Picked up automatically by gen_sprites3d.py (module-level SPRITES).  Models follow README_3D.md: grid units
in plan, z units up, the 4 x 4 pad from building(t), tier materials from Tier(t).

Animated overlays (TerritoryLife.kt, buildingIdle) that these models are built around:
    barracks         torches (1.52 | 2.48, 2.6, z 13.5)                    -> gen_sprites3d.barracks
    siege workshop   chimney smoke (0.83, 0.93, z 28 + 3t), forge glow (1.4, 2.5, z 5)
    wall             torches (1.56 | 2.64, 3.9, z 13)  = both sides of the gate
    watchtower       beacon flame (2, 2, z H + 6), H = 34, 44, 54, 64, 76 (tier 2+)
    alliance centre  braziers (1.25 | 2.75, 3.6, z 5) (tier 2+)
    castle (+ skins) gate torches (1.53 | 2.47, 3.62, z 15.5) at tier 1, (1.56 | 2.44, 3.72, z 11.5) from tier 2
"""

import math

import numpy as np

import gen_sprites3d as g
import render3d as r3
from gen_sprites3d import (LIP, Tier, _gable_cut, _slab, banner, building, door, flagpole, merlon_ring, opening,  # noqa: F401
                           palisade, round_tower, torch, window)
from render3d import (G, ZS, Scene, box, capsule, cone, cyl, cyl_x, cyl_y, ellipsoid, flag, gable, hip, inter,  # noqa: F401
                      lumpy, move, repeat, rot, rotz, rounded, smooth, sphere, sub, torus, union)

F = np.float32


# ---------------------------------------------------------------------------------------------
# extra geometry: a polygon cut out of a standing sheet (emblems, wings, bows, crystals' facets)
# ---------------------------------------------------------------------------------------------

class _Prism(r3.Node):
    """A 2D polygon in a vertical plane, extruded to a thickness (exact distance).

    The plane goes through the world point o; its horizontal axis points along (cos ang, sin ang) and its
    normal is (-sin ang, cos ang).  pts = [(s, z), ...] in world units relative to o; th = half thickness."""

    def __init__(self, pts, o, ang, th):
        self.p = [(float(a), float(b)) for a, b in pts]
        self.o = [float(v) for v in o]
        self.ca, self.sa, self.th = math.cos(ang), math.sin(ang), float(th)
        cs = []
        for s, z in self.p:
            for q in (-th, th):
                cs.append((o[0] + s * self.ca - q * self.sa, o[1] + s * self.sa + q * self.ca, o[2] + z))
        cs = np.array(cs)
        self.lo, self.hi = cs.min(axis=0), cs.max(axis=0)

    def d(self, x, y, z):
        dx, dy = x - self.o[0], y - self.o[1]
        s = dx * self.ca + dy * self.sa
        q = np.abs(dy * self.ca - dx * self.sa) - self.th
        s, zz, q = np.broadcast_arrays(s, z - self.o[2], q)
        d2 = np.full(s.shape, 1e18, F)
        neg = np.zeros(s.shape, bool)
        P = self.p
        for i in range(len(P)):
            ax, az = P[i]
            bx, bz = P[i - 1]
            ex, ez = bx - ax, bz - az
            wx, wz = s - ax, zz - az
            t = np.clip((wx * ex + wz * ez) / (ex * ex + ez * ez), 0.0, 1.0)
            cx, cz = wx - ex * t, wz - ez * t
            d2 = np.minimum(d2, cx * cx + cz * cz)
            c1, c2, c3 = zz >= az, zz < bz, ex * wz > ez * wx
            neg ^= (c1 & c2 & c3) | ~(c1 | c2 | c3)
        dp = np.sqrt(d2) * np.where(neg, -1.0, 1.0)
        return (np.minimum(np.maximum(dp, q), 0.0) + np.hypot(np.maximum(dp, 0.0), np.maximum(q, 0.0))).astype(F)


_FACE = {"L": 0.0, "R": -90.0, "C": -45.0}


def prism(pts, x, y, z, face="L", th=0.4, k=1.0):
    """Polygon cut-out standing at the model point (x, y, z).  pts = [(a, b), ...]: a = horizontal offset in
    viewport units measured along the sheet, b = height in z units; k scales both.  face: 'L' (the sheet looks
    at +y, like a left-front wall), 'R' (looks at +x), 'C' (looks at the camera), or an angle in degrees of the
    sheet's horizontal axis.  th = half thickness (viewport units)."""
    ang = math.radians(_FACE.get(face, face))
    return _Prism([(a * k, b * k * ZS) for a, b in pts], (x * G, y * G, z * ZS), ang, th)


def ring_of(node, n, xc, yc, start=0.0, sweep=360.0):
    """n copies of a node turned about the vertical axis through (xc, yc)."""
    return union(*[rotz(node, start + sweep * k / n, xc, yc) for k in range(n)])


def round_merlons(xc, yc, r, z, h=3.2, n=10, d=0.13, w=0.2, start=0.0):
    """Crenellations around a round platform of radius r, standing on height z."""
    return ring_of(box(xc + r - d, yc - w / 2, xc + r, yc + w / 2, z - 0.3, z + h, 0.18), n, xc, yc, start)


def dome(xc, yc, r, z, h):
    """Upper half of an ellipsoid: radius r (grid units) at height z, top at z + h."""
    return inter(ellipsoid(xc, yc, z, r, r, h), box(xc - r - 0.1, yc - r - 0.1, xc + r + 0.1, yc + r + 0.1, z, z + h + 1))


# ---------------------------------------------------------------------------------------------
# materials and small props
# ---------------------------------------------------------------------------------------------

IRON = r3.m_metal("#4E525C")
STEEL = r3.m_metal("#C9D0D8")
DARK = r3.m_flat("#1C1412", 0.05)                    # the inside of an open door
WOOD = r3.m_planks("#8A6238", 0.1, seed=46)
WOOD_NEW = r3.m_planks("#C39A5E", 0.09, seed=52, var=0.1)
WOOD_DK = r3.m_planks("#5E4028", 0.1, seed=53)
ROPE = r3.m_flat("#CDBB8C", 0.08)
STONEBALL = r3.m_rubble("#A7A49C", size=5.0, var=0.08)
FLAME = r3.m_glow("#FFA63A", power=4.5, bars=False)
FLAME_CORE = r3.m_glow("#FFE28A", power=5.5, bars=False)
EMBER = r3.m_glow("#FF6A1E", power=3.2, bars=False)
BANNERS = ["#C8303E", "#2F66B8", "#3E9A4A", "#E0A32A", "#7B2FB2", "#1FA39A", "#E0702A"]


def m_target(x, z, r_px):
    """Archery target face: straw rim, white / red rings, gold centre.  (x, z) = centre in model units."""
    X, Z = x * G, z * ZS
    straw, white, red, gold = r3.srgb("#B8954A"), r3.srgb("#F2ECDC"), r3.srgb("#D2342E"), r3.srgb("#F0B93A")

    def albedo(c):
        rr = np.hypot(c.x - X, c.z - Z) / r_px
        out = np.broadcast_to(straw, c.x.shape + (3,)).copy()
        for lim, col in ((0.86, white), (0.64, red), (0.42, white), (0.22, gold)):
            out[(rr < lim) & (np.abs(c.ny) > 0.7)] = col
        return out * (0.92 + 0.16 * r3.vnoise2(c.x * 0.9, c.z * 0.9, 5))[..., None]

    return r3.Material(albedo)


def flame(sc, x, y, z, s=1.0):
    """Baked fire: (x, y, z) is the flame's centre (the animated overlay is drawn on the same point)."""
    sc.add(ellipsoid(x, y, z, 0.07 * s, 0.07 * s, 2.6 * s), FLAME, cast=False, ao=False)
    sc.add(ellipsoid(x, y, z - 0.6 * s, 0.035 * s, 0.035 * s, 1.5 * s), FLAME_CORE, cast=False, ao=False)


def brazier(sc, T, x, y, z, s=1.0, lit=True, metal=None):
    """Iron fire bowl on three legs standing on the ground; (x, y, z) = centre of the flame."""
    metal = metal or IRON
    top = z - 1.5 * s
    legs = [capsule((x + 0.13 * s * math.cos(a), y + 0.13 * s * math.sin(a), 0.0), (x + 0.03 * math.cos(a), y + 0.03 * math.sin(a), top - 1.0), 0.45)
            for a in (math.radians(v) for v in (80, 200, 320))]
    sc.add(union(cone(x, y, 0.06 * s, top - 2.2 * s, top, 0.17 * s), *legs), metal, name="brazier")
    sc.add(cyl(x, y, 0.14 * s, top - 0.5, top + 0.25, 0.2), EMBER, cast=False, ao=False)
    if lit:
        flame(sc, x, y, z, s)


def barrel(sc, x, y, r=0.2, h=6.5, color="#8E6038"):
    sc.add(union(cone(x, y, r * 0.86, 0, h * 0.5, r), cone(x, y, r, h * 0.5, h, r * 0.86)), r3.m_planks(color, 0.07, seed=51), cyl=(x, y, r),
           name="barrel")
    sc.add(union(cyl(x, y, r * 0.965, h * 0.2 - 0.45, h * 0.2 + 0.45), cyl(x, y, r * 0.965, h * 0.8 - 0.45, h * 0.8 + 0.45)), IRON, cyl=(x, y, r),
           cast=False)


def crate(sc, x, y, s=0.45, h=5.5):
    sc.add(box(x, y, x + s, y + s, 0, h, 0.2), r3.m_planks("#A87B45", 0.11, seed=43, horizontal=True), name="crate")
    e = 0.035
    sc.add(union(box(x - 0.01, y + s - e, x + s + 0.01, y + s + 0.012, 0, h + 0.05), box(x + s - e, y - 0.01, x + s + 0.012, y + s + 0.01, 0, h + 0.05)),
           WOOD_DK, cast=False)


def stone_pile(sc, x, y, r=0.13):
    """Three stone shot and one on top."""
    d = r * 1.05
    sc.add(union(sphere(x - d, y, r * 17.15, r), sphere(x + d, y, r * 17.15, r), sphere(x, y - d * 1.6, r * 17.15, r),
                 sphere(x, y - d * 0.55, r * 17.15 * 2.45, r)), STONEBALL, name="shot")


def gonfalon(sc, T, x, y, h, color, w=0.34, ln=9.5, face="L", z0=0.0):
    """A banner hanging from a crossbar on its own pole (the cloth looks at +y for 'L', at +x for 'R')."""
    sc.add(capsule((x, y, z0), (x, y, z0 + h), 0.5), T.pole)
    zt = z0 + h - 1.2
    cloth = r3.m_cloth(color, trim="#F2C544", trim_h=1.3)
    if face == "L":
        sc.add(capsule((x - w / 2 - 0.05, y, zt), (x + w / 2 + 0.05, y, zt), 0.4), T.gold, cast=False)
        sc.add(box(x - w / 2, y + 0.015, x + w / 2, y + 0.04, zt - ln, zt), cloth)
    else:
        sc.add(capsule((x, y - w / 2 - 0.05, zt), (x, y + w / 2 + 0.05, zt), 0.4), T.gold, cast=False)
        sc.add(box(x + 0.015, y - w / 2, x + 0.04, y + w / 2, zt - ln, zt), cloth)
    sc.add(sphere(x, y, z0 + h + 0.5, 0.05), T.gold, cast=False)


def heart_pts(n=22):
    """Outline of a heart, about 2 units wide and 2 units tall, centred on its middle."""
    pts = []
    for i in range(n):
        a = 2.0 * math.pi * i / n
        px = 16.0 * math.sin(a) ** 3
        py = 13.0 * math.cos(a) - 5.0 * math.cos(2 * a) - 2.0 * math.cos(3 * a) - math.cos(4 * a)
        pts.append((px / 16.0, (py + 2.5) / 14.5))
    return pts


def medallion(sc, T, face, f, a, z, r_px=4.4, rim=None):
    """The healers' sign: a white disc with a red heart (deliberately not a cross)."""
    rod = (lambda d0, d1, r: cyl_y(f + d0, f + d1, a, z, r)) if face == "L" else (lambda d0, d1, r: cyl_x(f + d0, f + d1, a, z, r))
    sc.add(rod(0.0, 0.03, r_px + 0.7), rim or r3.m_flat("#3E9A5A", 0.05), cast=False)
    sc.add(rod(0.0, 0.05, r_px), r3.m_flat("#F4F0E4", 0.03), cast=False)
    x, y = (a, f + 0.055) if face == "L" else (f + 0.055, a)
    sc.add(prism(heart_pts(), x, y, z, face, th=0.35, k=r_px * 0.62), r3.m_flat("#D8324A", 0.04, spec=0.2), cast=False)


def horse_coat(i):
    return [("#8A5630", "#2E1C10"), ("#5E3A22", "#1E1410"), ("#C9C2B4", "#8E877A"), ("#B5793A", "#EDE2C8"), ("#3A3236", "#17131A")][i % 5]


def horse(sc, x, y, hdg=-45.0, coat="#8A5630", mane="#2E1C10", s=1.0, blanket=None):
    """A standing horse built from a few primitives.  (x, y) = middle of the body on the ground, hdg = heading
    in degrees (0 = +x, -45 = screen-right)."""
    def cap(f0, s0, z0, f1, s1, z1, r):
        return capsule((x + f0 * s, y + s0 * s, z0 * s), (x + f1 * s, y + s1 * s, z1 * s), r * s)

    body = [ellipsoid(x, y, 8.3 * s, 0.36 * s, 0.16 * s, 3.2 * s), sphere(x + 0.2 * s, y, 8.6 * s, 0.17 * s), sphere(x - 0.22 * s, y, 8.7 * s, 0.175 * s),
            cap(0.26, 0, 9.4, 0.42, 0, 13.0, 2.1), cap(0.43, 0, 13.4, 0.64, 0, 11.0, 1.5)]
    for sd in (-0.085, 0.085):
        body += [cap(0.24, sd, 6.4, 0.25, sd, 0.5, 0.9), cap(-0.25, sd, 6.4, -0.28, sd, 0.5, 0.9),
                 cone(x + 0.4 * s, y + sd * 0.5 * s, 0.04 * s, 14.4 * s, 16.6 * s)]
    sc.add(rotz(smooth(0.8 * s, *body), hdg, x, y), r3.m_flat(coat, 0.07, spec=0.12), name="horse")
    hair = union(cap(-0.37, 0, 9.6, -0.5, 0, 3.8, 1.1), cap(0.19, 0, 10.6, 0.37, 0, 14.5, 1.0))
    sc.add(rotz(hair, hdg, x, y), r3.m_flat(mane, 0.06), cast=False)
    if blanket:
        rug = inter(ellipsoid(x, y, 8.4 * s, 0.39 * s, 0.19 * s, 3.6 * s), box(x - 0.15 * s, y - 1, x + 0.13 * s, y + 1, 7.2 * s, 13 * s))
        sc.add(rotz(rug, hdg, x, y), r3.m_cloth(blanket, trim="#F2C544", trim_h=0.9), cast=False)


def horse_head(sc, a, f, z, coat, mane, turn=0.06):
    """Head and neck looking out of a stall in a left-front wall (plane y = f) at x = a; z = the chin rail."""
    neck = capsule((a, f - 0.18, z + 1.0), (a + turn * 0.3, f + 0.1, z + 3.8), 2.1)
    head = capsule((a + turn * 0.3, f + 0.12, z + 4.2), (a + turn, f + 0.36, z + 1.6), 1.55)
    ears = union(cone(a - 0.05 + turn * 0.3, f + 0.1, 0.04, z + 5.3, z + 7.4), cone(a + 0.05 + turn * 0.3, f + 0.1, 0.04, z + 5.3, z + 7.4))
    sc.add(smooth(0.7, neck, head, ears), r3.m_flat(coat, 0.07, spec=0.12), name="horse head")
    sc.add(capsule((a - 0.04, f - 0.14, z + 3.0), (a - 0.03 + turn * 0.3, f + 0.07, z + 5.7), 1.1), r3.m_flat(mane, 0.06), cast=False)


def target(sc, T, x, y, zc=9.5, r_px=6.0):
    """Round straw target on a tripod, looking at +y (the lit side)."""
    wood = WOOD if T.t < 5 else T.gold
    sc.add(union(capsule((x - 0.2, y + 0.06, 0), (x - 0.02, y - 0.03, zc + 1), 0.55), capsule((x + 0.2, y + 0.06, 0), (x + 0.02, y - 0.03, zc + 1), 0.55),
                 capsule((x, y - 0.32, 0), (x, y - 0.03, zc + 1), 0.55)), wood, name="easel")
    sc.add(cyl_y(y - 0.035, y + 0.07, x, zc, r_px, 0.4), m_target(x, zc, r_px), name="target")
    arrow = capsule((x + 0.03, y + 0.07, zc + 0.6), (x + 0.1, y + 0.4, zc + 2.4), 0.26)
    sc.add(arrow, T.pole, cast=False)
    sc.add(capsule((x + 0.085, y + 0.34, zc + 2.1), (x + 0.1, y + 0.4, zc + 2.4), 0.6), r3.m_flat("#F1EAD6"), cast=False)


def bow_pts(r=5.2, w=0.75, n=7):
    """A recurve-ish bow as a thick arc bulging to the left; the string runs from tip to tip."""
    out, inn = [], []
    for i in range(n):
        a = math.radians(-66 + 132.0 * i / (n - 1))
        k = 1.0 - 0.10 * abs(math.sin(a)) ** 2
        out.append((-r * k * math.cos(a) + r * 0.42, r * math.sin(a) * 1.12))
        inn.append((-(r - w) * k * math.cos(a) + r * 0.42, (r - w * 0.3) * math.sin(a) * 1.12))
    return out + inn[::-1]


def bow_rack(sc, T, x, y, n=2, ln=0.95):
    """A rack of bows looking at +y: two posts, a rail, n big bows leaning on it."""
    sc.add(union(capsule((x, y, 0), (x, y, 12.5), 0.65), capsule((x + ln, y, 0), (x + ln, y, 12.5), 0.65), capsule((x, y, 11.0), (x + ln, y, 11.0), 0.55),
                 capsule((x, y, 2.6), (x + ln, y, 2.6), 0.55)), WOOD_DK, name="bow rack")
    bows, strings = [], []
    for k in range(n):
        px = x + ln * (k + 0.5) / n
        bows.append(prism(bow_pts(6.4, 1.0), px - 0.03, y + 0.08, 7.6, "L", th=0.55))
        strings.append(capsule((px + 0.105, y + 0.08, 1.1), (px + 0.105, y + 0.08, 14.1), 0.2))
    sc.add(union(*bows), r3.m_flat("#D9A860", 0.08, spec=0.15) if T.t < 5 else T.gold, name="bows")
    sc.add(union(*strings), ROPE, cast=False)


def bow_sign(sc, T, a, f, z, k=1.0):
    """A bow with a nocked arrow on a left-front wall: the fletchers' sign."""
    sc.add(prism(bow_pts(5.6, 1.1), a - 0.02, f + 0.03, z, "L", th=0.5, k=k), T.gold if T.t >= 4 else r3.m_flat("#E0B468", 0.06, spec=0.2), cast=False, name="sign")
    sc.add(capsule((a + 0.09 * k, f + 0.03, z - 6.0 * k), (a + 0.09 * k, f + 0.03, z + 6.0 * k), 0.2), ROPE, cast=False)
    sc.add(capsule((a - 0.24 * k, f + 0.05, z), (a + 0.2 * k, f + 0.05, z), 0.35), T.pole, cast=False)
    sc.add(prism([(-1.7, 0.0), (0.6, 1.3), (0.6, -1.3)], a - 0.27 * k, f + 0.05, z, "L", th=0.3, k=k), STEEL, cast=False)


def quiver(sc, T, x, y, r=0.17, h=5.5):
    """A tub of arrows."""
    barrel(sc, x, y, r, h)
    shafts, vanes = [], []
    for k in range(5):
        a = math.radians(72 * k + 20)
        px, py = x + 0.08 * math.cos(a), y + 0.08 * math.sin(a)
        qx, qy = x + 0.15 * math.cos(a), y + 0.15 * math.sin(a)
        shafts.append(capsule((px, py, h - 1), (qx, qy, h + 5.2), 0.22))
        vanes.append(capsule((px + (qx - px) * 0.72, py + (qy - py) * 0.72, h + 3.4), (qx, qy, h + 5.2), 0.55))
    sc.add(union(*shafts), T.pole, cast=False)
    sc.add(union(*vanes), r3.m_flat("#F1EAD6", 0.05), cast=False)


def bed(sc, x, y, blanket="#4FA868", ln=0.92, w=0.42):
    """A cot lying along y, head towards the back: frame, mattress, blanket, pillow."""
    sc.add(union(box(x, y, x + w, y + ln, 0.8, 2.3, 0.2), box(x, y - 0.03, x + w, y + 0.05, 0, 6.0, 0.3), box(x, y + ln - 0.05, x + w, y + ln, 0, 3.4, 0.3)), WOOD,
           name="cot")
    sc.add(box(x + 0.02, y + 0.05, x + w - 0.02, y + ln - 0.05, 2.2, 3.5, 0.5), r3.m_flat("#F2EEE4", 0.03), name="sheet")
    sc.add(box(x + 0.005, y + ln * 0.36, x + w - 0.005, y + ln - 0.055, 2.6, 4.0, 0.55), r3.m_cloth(blanket), cast=False)
    sc.add(box(x + 0.06, y + 0.08, x + w - 0.06, y + 0.27, 3.3, 4.5, 0.55), r3.m_flat("#FBF8F0", 0.02), cast=False)


def tent(sc, x0, y0, x1, y1, h, stripe="#3E9A5A"):
    """Ridge tent along y with its open end looking at +y."""
    canvas = r3.m_cloth("#E6DFCC", stripe=stripe) if stripe else r3.m_cloth("#E6DFCC")
    sc.add(gable(x0, y0, x1, y1, 0.2, h, "y", over=0.0, thick=0.2), canvas, name="tent")
    xm, hw = (x0 + x1) / 2.0, (x1 - x0) / 2.0 * G
    sc.add(prism([(-hw * 0.5, 0.0), (hw * 0.5, 0.0), (0.0, h * 0.62)], xm, y1 + 0.012, 0.1, "L", th=0.15), DARK, cast=False)
    sc.add(capsule((xm, y1 + 0.03, 0), (xm, y1 + 0.03, h + 1.6), 0.4), WOOD_DK, cast=False)
    sc.add(sphere(xm, y1 + 0.03, h + 2.0, 0.04), r3.m_flat(stripe or "#C8303E"), cast=False)


def anvil(sc, x, y):
    sc.add(cyl(x, y, 0.15, 0, 3.0, 0.3), WOOD_DK, cyl=(x, y, 0.15), name="stump")
    sc.add(union(box(x - 0.1, y - 0.07, x + 0.1, y + 0.07, 3.0, 4.2), box(x - 0.19, y - 0.085, x + 0.16, y + 0.085, 4.2, 5.8, 0.25),
                 cone(x + 0.16, y, 0.07, 4.6, 5.6, 0.0)), IRON, name="anvil")


def log_stack(sc, x, y, n=3, ln=0.9, r_px=1.5):
    """Timber stacked along x: n logs on the ground, n - 1 on top."""
    logs = []
    step = r_px * 2.0 / G * 1.02
    for k in range(n):
        logs.append(cyl_x(x, x + ln, y + k * step, r_px / ZS, r_px))
    for k in range(n - 1):
        logs.append(cyl_x(x + 0.05, x + ln - 0.03, y + (k + 0.5) * step, r_px / ZS * 2.72, r_px))
    sc.add(union(*logs), WOOD_NEW, name="timber")


def catapult(sc, T, x, y, s=1.0, heavy=False, d=1.0):
    """A siege engine being built, its arm in the x-z plane (seen from the lit side): wheeled frame, A-shaped
    trestle, throwing arm of fresh timber with a stone in the cup; heavy=True hangs a counterweight box.
    d = -1 throws towards -x (the cup is then on the +x side, clear of the buildings behind)."""
    def q(dx, dy, z):
        return (x + dx * s * d, y + dy * s, z * s)

    frame = [box(x - 0.66 * s, y + sd * s - 0.055, x + 0.66 * s, y + sd * s + 0.055, 2.2 * s, 4.0 * s, 0.25) for sd in (-0.31, 0.31)]
    frame += [box(x + dx * s - 0.05, y - 0.31 * s, x + dx * s + 0.05, y + 0.31 * s, 2.4 * s, 3.8 * s, 0.25) for dx in (-0.56, 0.56)]
    ph = 15.5 if not heavy else 17.5
    for sd in (-0.31, 0.31):
        frame += [capsule(q(-0.34, sd, 3.8), q(0.04, sd, ph), 0.95 * s), capsule(q(0.42, sd, 3.8), q(0.04, sd, ph), 0.95 * s)]
    frame.append(capsule(q(0.04, -0.36, ph), q(0.04, 0.36, ph), 0.8 * s))
    sc.add(union(*frame), WOOD, name="engine frame")
    wheels = [cyl_y(y + sd * s - 0.045, y + sd * s + 0.045, x + dx * s, 2.5 * s, 2.5 * s, 0.3) for sd in (-0.4, 0.4) for dx in (-0.45, 0.45)]
    sc.add(union(*wheels), WOOD_DK, name="wheels")
    sc.add(union(*[cyl_y(y + sd * s - 0.06, y + sd * s + 0.06, x + dx * s, 2.5 * s, 0.8 * s) for sd in (-0.4, 0.4) for dx in (-0.45, 0.45)]), IRON, cast=False)
    tail, tip = q(0.5, 0, ph - 6.5), q(-0.78, 0, ph + 10.5)
    sc.add(capsule(tail, tip, 1.35 * s), WOOD_NEW, name="arm")
    sc.add(union(torus(tip[0], tip[1], tip[2] + 0.4, 0.085 * s, 0.6 * s), cyl(tip[0], tip[1], 0.085 * s, tip[2] - 0.7, tip[2] + 0.2)), IRON, cast=False)
    sc.add(sphere(tip[0], tip[1], tip[2] + 2.0 * s, 0.125 * s), STONEBALL, name="stone")
    if heavy:
        sc.add(capsule(tail, (tail[0], tail[1], tail[2] - 2.6 * s), 0.4), IRON, cast=False)
        sc.add(box(tail[0] - 0.15 * s, y - 0.16 * s, tail[0] + 0.15 * s, y + 0.16 * s, tail[2] - 7.6 * s, tail[2] - 2.4 * s, 0.4), r3.m_planks("#5A4A3C", 0.08, seed=54),
               name="counterweight")
        sc.add(box(tail[0] - 0.16 * s, y - 0.17 * s, tail[0] + 0.16 * s, y + 0.17 * s, tail[2] - 5.6 * s, tail[2] - 4.6 * s), IRON, cast=False)
    else:
        sc.add(capsule(q(-0.34, -0.31, 9.5), q(-0.34, 0.31, 9.5), 0.85 * s), WOOD, name="stop beam")
        sc.add(cyl_y(y - 0.2 * s, y + 0.2 * s, x + 0.04 * s * d, 5.0 * s, 1.5 * s), ROPE, cast=False)


# ---------------------------------------------------------------------------------------------
# archery range: targets against a butt wall, three lanes, the bowyer's hut, a rack of bows
# ---------------------------------------------------------------------------------------------

def _lawn():
    """Mown turf: darker and bluer than the map's meadow, in faint stripes along the lanes."""
    base = r3.srgb("#42663C")

    def albedo(c):
        n = r3.fbm2(c.x * 0.09, c.y * 0.09, 3, 31)
        stripe = 0.94 + 0.08 * (np.floor(c.x / (0.39 * G)) % 2.0)
        return base[None, :] * ((0.82 + 0.3 * n + 0.1 * r3.vnoise2(c.x * 1.1, c.y * 1.1, 32)) * stripe)[..., None]

    return r3.Material(albedo)


LAWN = _lawn()


def archery_range(t):
    sc, T = building(t)
    # the lawn with its lanes and the shooting line
    sc.add(box(0.25, 0.85, 2.6, 3.8, -0.3, 0.45, 0.4), LAWN, name="lawn")
    n = 2 if t < 3 else 3
    lane = 2.35 / n
    marks = [box(0.25 + lane * k - 0.035, 1.7, 0.25 + lane * k + 0.035, 3.7, 0.3, 0.8, 0.2) for k in range(1, n)]
    marks.append(box(0.3, 3.4, 2.55, 3.52, 0.3, 0.85, 0.2))
    sc.add(union(*marks), r3.m_flat("#D8CFAE", 0.06), cast=False, name="lane marks")
    # butt wall behind the targets
    bh = 14.0 + t
    if t == 1:
        palisade(sc, T, 0.25, 0.6, 2.6, 0.6, h=bh + 1.5, r=0.09)
    else:
        sc.add(box(0.2, 0.42, 2.65, 0.74, 0, bh), T.curtain, name="butt wall")
        if t >= 3:
            sc.add(merlon_ring(0.2, 0.42, 2.65, 0.74, bh, 2.8, 6, 0.13, sides="L"), T.curtain)
            sc.add(box(0.2, 0.74, 2.65, 0.775, 0, 2.2, 0.2), T.base, cast=False)
        else:
            sc.add(box(0.16, 0.38, 2.69, 0.78, bh - 0.2, bh + 1.3, 0.3), T.trim, name="coping")
        if t >= 4:
            sc.add(box(0.2, 0.74, 2.65, 0.76, bh - 2.2, bh - 1.1), T.gold, cast=False)
    for k in range(n):
        target(sc, T, 0.25 + lane * (k + 0.5), 1.3, 11.0, 7.8 if n == 2 else 6.9)
    # the bowyer's hut on the right
    x0, y0, x1, y1 = 2.82, 0.4, 3.72, 1.86
    h, rh, zb = 14.0 + t * 2.0, 7.0 + t * 0.8, 3.0
    sc.add(box(x0 - 0.07, y0 - 0.07, x1 + 0.07, y1 + 0.07, 0, zb, 0.4), T.base, name="hut base")
    holes = [door(sc, T, "L", y1, 3.27, 0.48, 8.6, T.stone, fw=0.06),
             window(sc, T, "R", x1, 1.13, zb + (h - zb) * 0.56, 0.4, min(6.5, (h - zb) * 0.5))]
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + rh), *holes, _gable_cut(x0, y0, x1, y1, h, rh, "y")), T.wall(zb, h, x0, y0, bay=0.45), name="hut")
    sc.add(gable(x0, y0, x1, y1, h, rh, "y", over=0.2, thick=2.2 if t == 1 else 1.5), T.roof, name="hut roof")
    # the fletchers' sign on the gable end (the roof is solid, so it hangs on the roof's end face)
    sc.add(cyl_y(y1 + 0.2, y1 + 0.235, 3.27, h + rh * 0.3, 5.6), r3.m_planks("#6A4326", 0.2, seed=21) if t < 4 else T.wall(), cast=False, name="sign board")
    bow_sign(sc, T, 3.27, y1 + 0.235, h + rh * 0.3, 0.72)
    flagpole(sc, T, 3.27, 0.5, h + rh - 1.0, 11.0 + t, w=7.5)
    if t >= 4:
        banner(sc, T, "L", 0.775, 1.42, bh - 1.4, bh * 0.42, 0.32)
    # the shooting stand: bows, arrows, straw
    bow_rack(sc, T, 2.84, 2.62, 2, 0.86)
    quiver(sc, T, 3.5, 3.3, 0.18, 5.5)
    g.haybale(sc, 3.02, 3.58, 0.44, 3.6)
    return sc


# ---------------------------------------------------------------------------------------------
# stable: a long low barn with horses looking out of the stalls, a paddock with a horse, hay, a trough
# ---------------------------------------------------------------------------------------------

def stable(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.4, 0.35, 3.6, 1.8
    h, rh, zb = 13.5 + t * 1.6, 6.0 + t, 3.0
    sc.add(box(x0 - 0.07, y0 - 0.07, x1 + 0.07, y1 + 0.07, 0, zb, 0.4), T.base, name="base")
    n = 3 if t < 3 else 4
    top = min(11.5, h - 3.0)
    holes = []
    for k in range(n):
        a = x0 + (x1 - x0) * (k + 0.5) / n
        holes.append(opening("L", y1, a, 0.0, 0.56, top, T.stone, -0.3, 0.3))
        sc.add(opening("L", y1, a, 0.0, 0.56, top, T.stone, -0.34, -0.26), DARK, cast=False)
        sc.add(_slab("L", y1, a - 0.28, a + 0.28, -0.06, -0.01, 0.0, 5.6), r3.m_planks("#8A5A30", 0.1, seed=45), name="stall door")
        sc.add(union(_slab("L", y1, a - 0.3, a + 0.3, -0.07, 0.02, 5.2, 6.2, 0.25), _slab("L", y1, a - 0.3, a + 0.3, -0.07, 0.005, 0.3, 1.2)), T.dark_wood,
               cast=False)
        if T.stone:
            sc.add(sub(opening("L", y1, a, 0.0, 0.7, top + 1.2, True, -0.02, 0.035), opening("L", y1, a, -1.0, 0.56, top + 1.0, True, -0.3, 0.3)), T.base)
        if k != (1 if n == 3 else 2):
            coat, mane = horse_coat(k + (2 if t >= 4 and k == 0 else 0))
            horse_head(sc, a, y1, 5.6, coat, mane, turn=0.07 if k % 2 == 0 else -0.05)
    holes.append(window(sc, T, "R", x1, 1.08, zb + (h - zb) * 0.55, 0.42, min(6.5, (h - zb) * 0.5)))
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + rh), *holes, _gable_cut(x0, y0, x1, y1, h, rh, "x")), T.wall(zb, h, x0, y0, bay=0.4), name="barn")
    sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.17, thick=2.2 if t == 1 else 1.5), T.roof, name="roof")
    sc.add(box(x0 - 0.22, (y0 + y1) / 2 - 0.05, x1 + 0.22, (y0 + y1) / 2 + 0.05, h + rh - 0.9, h + rh + 0.5, 0.45), T.trim, cast=False)
    # hay loft hatch with a hoist beam on the right gable
    sc.add(_slab("R", x1, 0.9, 1.26, 0.0, 0.03, h + 0.4, h + rh * 0.52), T.door, cast=False)
    sc.add(capsule((x1 - 0.1, 1.08, h + rh * 0.66), (x1 + 0.34, 1.08, h + rh * 0.66), 0.6), T.dark_wood)
    if t >= 3:
        flagpole(sc, T, 0.55, 1.07, h + rh - 1.0, 11.0 + t, w=7.5)
    # paddock
    sc.add(box(0.22, 2.2, 3.8, 3.82, -0.3, 0.4, 0.4), r3.m_ground("dirt", "#A58C5E"), name="paddock")
    g.fence(sc, T, [(0.2, 2.12), (0.2, 3.84), (3.82, 3.84), (3.82, 2.12)], h=5.4)
    coat, mane = horse_coat(0 if t < 4 else 2)
    horse(sc, 1.95, 3.02, -45.0, coat, mane, 1.04 + 0.03 * t, blanket=None if t < 2 else T.banner)
    g.haybale(sc, 3.28, 2.55, 0.5, 4.0)
    if t >= 2:
        g.haybale(sc, 3.34, 3.02, 0.42, 3.4)
    # water trough
    sc.add(sub(box(0.42, 2.45, 1.16, 2.8, 0, 3.4, 0.25), box(0.47, 2.5, 1.11, 2.75, 1.4, 4.0)), WOOD, name="trough")
    sc.add(box(0.47, 2.5, 1.11, 2.75, 1.0, 2.7), r3.m_water(), cast=False)
    return sc


# ---------------------------------------------------------------------------------------------
# siege workshop: smithy with a glowing forge, a lean-to timber shed going up, a siege engine half built
# ---------------------------------------------------------------------------------------------

def siege_workshop(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.4, 0.4, 2.4, 2.4
    h, rh, zb = 15.0 + t * 2.0, 8.0 + t, 4.0
    sc.add(box(x0 - 0.08, y0 - 0.08, x1 + 0.08, y1 + 0.08, 0, zb, 0.4), T.base, name="base")
    # the forge mouth: TerritoryLife.kt pulses a glow and throws sparks at (1.4, 2.5, z 5)
    fh = min(10.0, h - 7.0)
    mouth = opening("L", y1, 1.4, 0.0, 0.92, fh, True, -0.36, 0.3)
    sc.add(opening("L", y1, 1.4, 0.0, 0.92, fh, True, -0.4, -0.3), r3.m_glow("#FF7A1E", power=2.6, bars=False), cast=False, ao=False)
    sc.add(box(1.02, y1 - 0.28, 1.78, y1 - 0.02, 0, 2.4, 0.3), r3.m_rubble("#5A5048", size=3.0), name="hearth")
    sc.add(lumpy(ellipsoid(1.4, y1 - 0.15, 2.6, 0.3, 0.1, 1.5), 0.5, 0.8, 3), r3.m_glow("#FF5A14", power=3.4, bars=False), cast=False, ao=False)
    sc.add(sub(opening("L", y1, 1.4, 0.0, 1.14, fh + 1.6, True, -0.03, 0.06), opening("L", y1, 1.4, -1.0, 0.92, fh + 1.0, True, -0.5, 0.5)),
           T.base if t < 4 else T.trim, name="forge arch")
    zw, wh = zb + (h - zb) * 0.6, min(6.5, (h - zb) * 0.42)
    holes = [mouth, window(sc, T, "L", y1, 0.72, zw, 0.34, wh), window(sc, T, "L", y1, 2.08, zw, 0.34, wh),
             window(sc, T, "R", x1, 1.95, zb + (h - zb) * 0.42, 0.4, wh),
             window(sc, T, "R", x1, 1.4, h + rh * 0.3, 0.36, 4.6, arch=False)]
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + rh), *holes, _gable_cut(x0, y0, x1, y1, h, rh, "x")), T.wall(zb, h, x0, y0, bay=0.4), name="hall")
    sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.24, thick=2.2 if t == 1 else 1.5), T.roof, name="roof")
    sc.add(box(x0 - 0.28, 1.35, x1 + 0.28, 1.45, h + rh - 0.9, h + rh + 0.5, 0.45), T.trim, cast=False)
    # chimney (smoke starts at (0.83, 0.93, z 28 + 3t))
    ct = 26.0 + 3.0 * t
    sc.add(box(0.63, 0.73, 1.03, 1.13, h - 2, ct, 0.3), T.base if t < 5 else r3.m_stone("#8F8672", course=3.4), name="chimney")
    sc.add(box(0.59, 0.69, 1.07, 1.17, ct - 0.4, ct + 1.3, 0.3), r3.m_flat("#4A4440", 0.1))
    sc.add(box(0.7, 0.8, 0.96, 1.06, ct + 1.0, ct + 1.5), r3.m_glow("#FF7A1E", power=1.2, bars=False), cast=False, ao=False)
    flagpole(sc, T, 2.25, 1.4, h + rh - 1.0, 11.0 + t, w=7.5)
    # lean-to timber shed against the right wall: planked at the back, bare rafters at the front
    lz, drop = h - 3.0, 5.6
    ang = math.degrees(math.atan2(drop * ZS, 1.32 * G))
    sc.add(rot(box(x1, 0.42, x1 + 1.36, 1.42, lz - 0.5, lz + 0.4), "y", ang, (x1, 1.0, lz)), WOOD_NEW, name="shed roof")
    raft = [capsule((x1, py, lz), (x1 + 1.32, py, lz - drop), 0.65) for py in (1.62, 1.86)]
    posts = [box(3.6, py - 0.055, 3.71, py + 0.055, 0, lz - drop + 0.2, 0.2) for py in (0.5, 1.86)]
    posts.append(capsule((3.655, 0.44, lz - drop + 0.1), (3.655, 1.92, lz - drop + 0.1), 0.8))
    sc.add(union(*posts, *raft), WOOD_DK, name="shed frame")
    log_stack(sc, 2.62, 0.72, 3, 0.85, 1.6)
    # the engine (its arm rises towards +x, clear of the hall), the anvil, the shot
    catapult(sc, T, 2.74, 3.1, 1.1 + 0.04 * t, heavy=t >= 3, d=-1.0)
    anvil(sc, 1.4, 2.98)
    stone_pile(sc, 0.72, 3.3, 0.13)
    if t >= 4:
        g.chest(sc, T, 0.42, 2.62, 0.48, 5.0)
    return sc


# ---------------------------------------------------------------------------------------------
# hospital: a clean hall whose arched front carries the heart sign, a field tent, cots in the yard
# ---------------------------------------------------------------------------------------------

GREEN = "#3E9A5A"


def hospital(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.5, 0.4, 3.5, 2.2
    h, rh, zb = 14.0 + t * 2.6, 8.0 + t, 4.0
    hh = h - zb
    sc.add(box(x0 - 0.09, y0 - 0.09, x1 + 0.09, y1 + 0.09, 0, zb, 0.4), T.base, name="base")
    zw, wh = zb + hh * 0.56, min(8.5, hh * 0.48)
    holes = [window(sc, T, "L", y1, a, zw, 0.42, wh) for a in (0.95, 3.05)]
    holes += [window(sc, T, "R", x1, a, zw, 0.42, wh) for a in (0.85, 1.7)]
    wm = T.wall(zb, h, x0, y0, bay=0.4)
    if t < 4:
        sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + rh), *holes, _gable_cut(x0, y0, x1, y1, h, rh, "x")), wm, name="hall")
        sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.24, thick=2.2 if t == 1 else 1.5), T.roof, name="roof")
        sc.add(box(x0 - 0.28, 1.25, x1 + 0.28, 1.35, h + rh - 0.9, h + rh + 0.5, 0.45), T.trim, cast=False)
    else:
        sc.add(sub(box(x0, y0, x1, y1, zb - 1, h), *holes), wm, name="hall")
        sc.add(box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, h - 2.4, h + 0.4, 0.3), T.base if t == 4 else T.wall(), name="cornice")
        sc.add(box(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, h - 1.5, h - 0.4, 0.25), T.gold, cast=False)
        sc.add(hip(x0, y0, x1, y1, h + 0.4, rh + 1.0, over=0.2, thick=1.4), T.roof, name="roof")
        # a small lantern dome on the ridge
        zc = h + 0.4 + (rh + 1.0) * 0.72
        sc.add(cyl(2.0, 1.3, 0.3, zc - 2, zc + 4.0), T.wall(), cyl=(2.0, 1.3, 0.3), name="lantern")
        sc.add(dome(2.0, 1.3, 0.36, zc + 4.0, 5.0), T.roof, cyl=(2.0, 1.3, 0.36))
        sc.add(sphere(2.0, 1.3, zc + 9.6, 0.06), T.gold, cast=False)
    # the front: a porch whose wall rises into a round-topped gable that carries the sign
    px0, px1, py1 = 1.34, 2.66, 2.74
    dh = 9.6
    rz = (px1 - px0) * G / 2.0 / ZS            # height of the round top (z units)
    zm = dh + 7.4                               # centre of the medallion
    ftop = zm + 7.2
    hole = door(sc, T, "L", py1, 2.0, 0.66, dh, True, fw=0.07, frame=T.base if t < 4 else T.trim)
    sc.add(sub(opening("L", py1, 2.0, 0.0, px1 - px0, ftop, True, -0.16, 0.0), hole), wm if t != 2 else r3.m_flat("#EDDDB4", 0.06), name="front")
    edge = sub(opening("L", py1, 2.0, 0.0, px1 - px0 + 0.1, ftop + 1.0, True, -0.2, 0.03), opening("L", py1, 2.0, -1.0, px1 - px0 - 0.08, ftop + 0.2, True, -0.5, 0.5))
    sc.add(edge, T.trim if t != 3 else T.base, name="front edge")
    ph = min(h - 1.0, ftop - rz + 1.0)
    sc.add(box(px0 + 0.04, y1 - 0.1, px1 - 0.04, py1 - 0.1, 0, ph), wm, name="porch")
    sc.add(gable(px0 + 0.04, y1 - 0.5, px1 - 0.04, py1 - 0.12, ph, 5.5, "y", over=0.0, thick=1.4), T.roof, name="porch roof")
    medallion(sc, T, "L", py1, 2.0, zm, 4.7, rim=T.gold if t >= 4 else None)
    if t >= 5:      # marble columns and a canopy in front of the door
        cols = [cyl(cx, py1 + 0.24, 0.085, 0.8, dh + 1.6) for cx in (1.5, 2.5)]
        sc.add(union(*cols, box(px0, py1, px1, py1 + 0.4, 0, 0.9, 0.2), box(px0, py1, px1, py1 + 0.4, dh + 1.6, dh + 3.0, 0.25)), T.wall(), name="portico")
        sc.add(union(*[union(cyl(cx, py1 + 0.24, 0.115, dh + 0.8, dh + 1.6, 0.2), cyl(cx, py1 + 0.24, 0.115, 0.8, 1.7, 0.2)) for cx in (1.5, 2.5)]), T.gold, cast=False)
    if t >= 2:
        banner(sc, T, "R", x1, 1.28, h - 2.6 if t < 4 else h - 3.4, hh * 0.36, 0.34, color=GREEN)
    if t >= 3:
        flagpole(sc, T, 3.38, 0.52, h + (rh * 0.2 if t < 4 else 0.4), 15.0 + t, color=GREEN, w=7.5)
    # the yard: cots on the left, a field tent on the right
    bed(sc, 0.36, 2.72, GREEN)
    if t >= 2:
        bed(sc, 0.86, 2.98, "#4C86C6")
    tent(sc, 2.76, 2.7 if t < 3 else 2.56, 3.76, 3.72, 8.5 + 0.5 * t)
    barrel(sc, 1.62, 3.5, 0.15, 5.0)
    return sc


# ---------------------------------------------------------------------------------------------
# wall: two lengths of rampart meeting at a corner tower, with the gatehouse in the lit one
# (torches at (1.56 | 2.64, 3.9, z 13))
# ---------------------------------------------------------------------------------------------

def wall_building(t):
    sc, T = building(t)
    wh = 13.0 + t * 2.4
    a0, a1 = 3.08, 3.7                    # the band both walls occupy
    cm = T.curtain
    if t == 1:
        # log palisade with a plank walk behind it
        palisade(sc, T, 0.2, a1 - 0.08, 1.42, a1 - 0.08, h=wh + 3.4, r=0.1)
        palisade(sc, T, a1 - 0.08, 0.95, a1 - 0.08, 3.0, h=wh + 3.4, r=0.1)
        walk = union(box(0.2, a0, 1.42, a1 - 0.2, wh - 4.6, wh - 3.3, 0.2), box(a0, 0.95, a1 - 0.2, 3.0, wh - 4.6, wh - 3.3, 0.2))
        sc.add(walk, r3.m_planks("#9A6E40", 0.16, seed=24), name="walk")
        legs = [box(px - 0.05, a0, px + 0.05, a0 + 0.1, 0, wh - 3.4, 0.2) for px in (0.3, 0.85, 1.36)]
        legs += [box(a0, py - 0.05, a0 + 0.1, py + 0.05, 0, wh - 3.4, 0.2) for py in (1.05, 1.7, 2.35, 2.95)]
        sc.add(union(*legs), T.dark_wood, name="walk posts")
    else:
        sc.add(union(box(0.2, a0, 3.4, a1, 0, wh), box(a0, 0.6, a1, 3.4, 0, wh)), cm, name="rampart")
        sc.add(union(merlon_ring(0.2, a0, 1.42, a1, wh, 3.3, 4, 0.13, sides="LB"), merlon_ring(a0, 0.98, a1, 2.96, wh, 3.3, 4, 0.13, ny=6, sides="RF")), cm,
               name="merlons")
        sc.add(union(box(0.2, a1, 1.4, a1 + 0.04, 0, 2.8, 0.2), box(a1, 0.6, a1 + 0.04, 3.0, 0, 2.8, 0.2)), T.base, cast=False, name="plinth")
        if t >= 4:
            sc.add(union(box(0.2, a1, 1.4, a1 + 0.022, wh - 2.4, wh - 1.2), box(a1, 0.9, a1 + 0.022, 3.0, wh - 2.4, wh - 1.2)), T.gold, cast=False)
        # the cut end of the lit wall gets a pier
        sc.add(box(0.12, a0 - 0.07, 0.4, a1 + 0.07, 0, wh + 3.0, 0.3), T.base if t < 5 else T.wall(), name="pier")
        sc.add(box(0.08, a0 - 0.11, 0.44, a1 + 0.11, wh + 2.6, wh + 4.0, 0.3), cm if t < 4 else T.gold)
    # towers: the corner, and the far end of the shaded wall
    ch = 8.0 + t
    round_tower(sc, T, 3.4, 3.4, 0.5, wh + 6.0, ch, flag_h=0, slit_deg=28.0)
    round_tower(sc, T, 3.39, 0.6, 0.47, wh + 7.5, ch, flag_h=11.0 if t >= 3 else 0, slit_deg=62.0)
    # gatehouse
    gx0, gy0, gx1, gy1 = 1.4, 2.9, 2.8, 3.9
    gh = wh + 9.0
    gw = T.wall(4.0, gh, gx0, gy0, bay=0.47)
    gate = opening("L", gy1, 2.1, 0.0, 0.8, 14.0, True, -0.4, 0.3)
    sc.add(opening("L", gy1, 2.1, 0.0, 0.8, 14.0, True, -0.45, -0.34), DARK, cast=False)
    if t >= 3:      # portcullis, half raised
        bars = [_slab("L", gy1, 2.1 + dx - 0.022, 2.1 + dx + 0.022, -0.16, -0.12, 4.6, 14.5) for dx in (-0.27, -0.09, 0.09, 0.27)]
        bars += [_slab("L", gy1, 1.7, 2.5, -0.17, -0.13, zz, zz + 0.8) for zz in (5.4, 8.6, 11.8)]
        sc.add(inter(union(*bars), opening("L", gy1, 2.1, 0.0, 0.8, 14.0, True, -0.4, 0.3)), IRON, name="portcullis")
    else:           # plank leaves, one ajar
        sc.add(inter(_slab("L", gy1, 1.7, 2.06, -0.18, -0.13, 0, 14.0), opening("L", gy1, 2.1, 0.0, 0.8, 14.0, True, -0.4, 0.3)), T.door, name="gate leaf")
    fr = sub(opening("L", gy1, 2.1, 0.0, 1.0, 15.2, True, -0.02, 0.05), opening("L", gy1, 2.1, -1.0, 0.8, 15.0, True, -0.5, 0.5))
    sc.add(fr, T.base if t < 4 else T.trim, name="gate arch")
    holes = [gate, window(sc, T, "L", gy1, 1.68, gh - 4.6, 0.3, 5.4), window(sc, T, "L", gy1, 2.52, gh - 4.6, 0.3, 5.4),
             window(sc, T, "R", gx1, 3.5, gh - 4.6, 0.32, 5.4)]
    sc.add(box(gx0 - 0.07, gy0, gx1 + 0.07, gy1 + 0.07, 0, 3.4, 0.4), T.base, name="gatehouse base")
    if t < 3:
        rh = 7.0 + t
        sc.add(sub(box(gx0, gy0, gx1, gy1, 0, gh + rh), *holes, _gable_cut(gx0, gy0, gx1, gy1, gh, rh, "x")), gw, name="gatehouse")
        sc.add(gable(gx0, gy0, gx1, gy1, gh, rh, "x", over=0.2, thick=2.2 if t == 1 else 1.5), T.roof, name="gate roof")
        sc.add(box(gx0 - 0.24, 3.35, gx1 + 0.24, 3.45, gh + rh - 0.9, gh + rh + 0.5, 0.45), T.trim, cast=False)
        top = gh + rh
    else:
        sc.add(sub(box(gx0, gy0, gx1, gy1, 0, gh), *holes), gw, name="gatehouse")
        sc.add(sub(box(gx0 - 0.11, gy0 - 0.11, gx1 + 0.11, gy1 + 0.11, gh - 3.0, gh + 0.9, 0.3), box(gx0 + 0.05, gy0 + 0.05, gx1 - 0.05, gy1 - 0.05, gh - 0.2, gh + 9)),
               cm if t < 5 else T.wall(), name="parapet")
        sc.add(merlon_ring(gx0 - 0.11, gy0 - 0.11, gx1 + 0.11, gy1 + 0.11, gh + 0.9, 3.2, 5, 0.13, ny=4), cm if t < 5 else T.wall())
        if t >= 4:
            sc.add(box(gx0 - 0.13, gy0 - 0.13, gx1 + 0.13, gy1 + 0.13, gh - 2.1, gh - 0.9, 0.25), T.gold, cast=False)
        rh = 6.0 + t * 1.5
        sc.add(hip(gx0 + 0.1, gy0 + 0.1, gx1 - 0.1, gy1 - 0.1, gh + 0.6, rh, thick=1.0), T.roof, name="gate roof")
        top = gh + 0.6 + rh
    g.shield(sc, T, "L", gy1, 2.1, 18.0, 2.4)
    torch(sc, T, 1.56, gy1 + 0.05, 13.0, 0.9)
    torch(sc, T, 2.64, gy1 + 0.05, 13.0, 0.9)
    if t >= 3:
        banner(sc, T, "L", a1 + 0.04, 0.9, wh - 2.8, wh * 0.4, 0.34)
        banner(sc, T, "R", a1 + 0.04, 1.97, wh - 2.8, wh * 0.4, 0.34)
    flagpole(sc, T, 2.1, 3.4, top - (1.5 if t >= 3 else 0.5), 12.0 + t, w=8.0)
    # the yard behind: the watch's tent and spears
    tent(sc, 0.75, 0.7, 1.65, 1.75, 8.5, stripe=T.banner)
    g.weapon_rack(sc, T, 0.3, 2.2, 3, 0.9)
    return sc


# ---------------------------------------------------------------------------------------------
# watchtower: a tall slender tower with a beacon on its very top (flame at (2, 2, z H + 6))
# ---------------------------------------------------------------------------------------------

TOWER_H = [34.0, 44.0, 54.0, 64.0, 76.0]


def watchtower(t):
    sc, T = building(t)
    h = TOWER_H[t - 1]
    xc = yc = 2.0
    if t == 1:
        # timber lookout: four splayed legs, braces, a railed platform, a thatched roof, an alarm bell
        legs, braces = [], []
        for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
            legs.append(capsule((xc + 0.82 * sx, yc + 0.82 * sy, 0), (xc + 0.5 * sx, yc + 0.5 * sy, h - 1.0), 1.25))
        for z0, z1, k0, k1 in ((1.5, 14.5, 0.8, 0.68), (14.5, 28.0, 0.68, 0.56)):
            braces += [capsule((xc - k0, yc + k0, z0), (xc + k1, yc + k1, z1), 0.7), capsule((xc + k0, yc + k0, z0), (xc - k1, yc + k1, z1), 0.7),
                       capsule((xc + k0, yc - k0, z0), (xc + k1, yc + k1, z1), 0.7), capsule((xc + k0, yc + k0, z0), (xc + k1, yc - k1, z1), 0.7)]
            braces += [capsule((xc - k1, yc + k1, z1), (xc + k1, yc + k1, z1), 0.7), capsule((xc + k1, yc - k1, z1), (xc + k1, yc + k1, z1), 0.7)]
        sc.add(union(*legs), T.curtain, name="legs")
        sc.add(union(*braces), T.dark_wood, name="braces")
        sc.add(box(xc - 0.9, yc - 0.9, xc + 0.9, yc + 0.9, h - 1.6, h, 0.25), r3.m_planks("#A2643A", 0.2, horizontal=True), name="deck")
        rail = [box(xc + 0.86 * sx - 0.05, yc + 0.86 * sy - 0.05, xc + 0.86 * sx + 0.05, yc + 0.86 * sy + 0.05, h, h + 13.0, 0.2) for sx in (-1, 1) for sy in (-1, 1)]
        for zz in (h + 2.4, h + 4.6):
            rail += [capsule((xc - 0.86, yc + 0.86, zz), (xc + 0.86, yc + 0.86, zz), 0.5), capsule((xc + 0.86, yc - 0.86, zz), (xc + 0.86, yc + 0.86, zz), 0.5),
                     capsule((xc - 0.86, yc - 0.86, zz), (xc + 0.86, yc - 0.86, zz), 0.5), capsule((xc - 0.86, yc - 0.86, zz), (xc - 0.86, yc + 0.86, zz), 0.5)]
        sc.add(union(*rail), T.trim, name="rail")
        sc.add(hip(xc - 0.9, yc - 0.9, xc + 0.9, yc + 0.9, h + 12.5, 9.0, over=0.22, thick=2.0), T.roof, name="roof")
        # ladder on the lit side
        lad = [capsule((xc - 0.2, yc + 1.32, 0), (xc - 0.2, yc + 0.93, h), 0.5), capsule((xc + 0.2, yc + 1.32, 0), (xc + 0.2, yc + 0.93, h), 0.5)]
        lad += [capsule((xc - 0.2, yc + 1.32 - 0.39 * f, h * f), (xc + 0.2, yc + 1.32 - 0.39 * f, h * f), 0.4) for f in (0.1, 0.24, 0.38, 0.52, 0.66, 0.8, 0.94)]
        sc.add(union(*lad), WOOD_NEW, name="ladder")
        # alarm bell under the roof
        sc.add(union(cone(xc, yc, 0.2, h + 6.2, h + 9.4, 0.07), sphere(xc, yc, h + 9.4, 0.07)), T.gold, name="bell")
        sc.add(capsule((xc, yc, h + 9.4), (xc, yc, h + 12.6), 0.3), T.pole, cast=False)
        flagpole(sc, T, xc, yc, h + 20.5, 11.0, w=8.0)
        barrel(sc, 3.2, 3.2, 0.2, 6.0)
        log_stack(sc, 0.5, 3.0, 2, 0.7, 1.4)
        return sc
    # stone / plaster tower.  From the bottom: guard room, shaft, corbelled gallery, lantern room, roof, cresset
    r = 0.52
    pz = 8.4
    hs = h - 13.5                      # top of the shaft = floor of the gallery
    he = h - 4.6                       # eave of the roof
    sc.add(box(1.3, 1.3, 2.7, 2.7, 0, pz, 0.4), T.base, name="guard room")
    sc.add(box(1.24, 1.24, 2.76, 2.76, pz - 0.4, pz + 1.0, 0.3), T.curtain if t < 5 else T.wall(), name="guard room cap")
    if t >= 4:
        sc.add(box(1.23, 1.23, 2.77, 2.77, pz - 0.1, pz + 0.6, 0.2), T.gold, cast=False)
    door(sc, T, "L", 2.7, 2.0, 0.48, 7.0, True, fw=0.06, frame=T.curtain if t < 4 else T.trim)
    cm = (xc, yc, r)
    slits = []
    for zf, deg in ((0.24, 100.0), (0.5, 28.0), (0.76, 100.0)) if t >= 3 else ((0.3, 100.0), (0.68, 28.0)):
        zc = pz + (hs - pz) * zf
        slits.append(rotz(box(xc + r - 0.15, yc - 0.085, xc + r + 0.2, yc + 0.085, zc - 3.6, zc + 3.6), deg, xc, yc))
        sc.add(rotz(box(xc + r - 0.16, yc - 0.085, xc + r - 0.09, yc + 0.085, zc - 3.6, zc + 3.6), deg, xc, yc), T.slit, cast=False, ao=False)
    sc.add(sub(cone(xc, yc, r + 0.06, pz, hs - 3.0, r - 0.02), *slits), T.wall(pz, hs - 3.0, 0.0, 0.0, bay=0.47), cyl=cm, name="shaft")
    for zf in ((0.5,) if t < 3 else (0.36, 0.7)):
        zz = pz + (hs - pz) * zf
        sc.add(cyl(xc, yc, r + 0.075 - 0.08 * zf, zz - 0.9, zz + 0.9, 0.35), T.base if t < 4 else T.gold, cyl=cm, cast=False)
    # gallery: corbelled deck with a parapet
    pr = 0.86
    stone = T.curtain if t < 5 else T.wall()
    sc.add(union(cone(xc, yc, r - 0.02, hs - 5.0, hs - 1.6, pr), cyl(xc, yc, pr, hs - 1.6, hs + 0.6, 0.25)), stone, cyl=(xc, yc, pr), name="deck")
    sc.add(sub(cyl(xc, yc, pr, hs + 0.4, hs + 3.2), cyl(xc, yc, pr - 0.13, hs + 0.6, hs + 9.0)), stone, cyl=(xc, yc, pr), name="parapet")
    sc.add(round_merlons(xc, yc, pr, hs + 3.2, 2.4, 9, 0.13, 0.2, start=9.0), stone, name="merlons")
    if t >= 4:
        sc.add(cyl(xc, yc, pr + 0.02, hs - 0.9, hs + 0.2, 0.3), T.gold, cyl=(xc, yc, pr), cast=False)
    # lantern room with lit windows all round
    lr = 0.55
    wins = [rotz(box(xc + lr - 0.12, yc - 0.11, xc + lr + 0.2, yc + 0.11, hs + 3.4, he - 1.4), deg, xc, yc) for deg in (-8, 45, 98)]
    sc.add(cyl(xc, yc, lr - 0.08, hs + 3.2, he - 1.2), T.glow, cast=False, ao=False)
    sc.add(sub(cyl(xc, yc, lr, hs, he), *wins), T.wall(hs, he, 0.0, 0.0, bay=0.52), cyl=(xc, yc, lr), name="lantern room")
    # roof with a flat top for the fire basket; the flame's centre is z = h + 6
    sc.add(union(cone(xc, yc, lr + 0.27, he, h + 2.2, 0.14), cyl(xc, yc, lr + 0.27, he - 0.9, he + 0.05, 0.2)), T.roof, cyl=(xc, yc, lr + 0.27), name="roof")
    metal = IRON if t < 4 else T.gold
    sc.add(union(cyl(xc, yc, 0.1, h + 1.8, h + 3.0), cone(xc, yc, 0.11, h + 2.6, h + 4.5, 0.3)), metal, name="cresset")
    sc.add(cyl(xc, yc, 0.26, h + 4.1, h + 4.8, 0.2), EMBER, cast=False, ao=False)
    flame(sc, xc, yc, h + 6.2, 1.45)
    if t >= 3:
        banner(sc, T, "L", 2.7, 2.46, pz - 0.8, 5.0, 0.24)
        sc.add(capsule((xc + 0.8, yc - 0.26, hs + 2.0), (xc + 1.05, yc - 0.45, hs + 12.0), 0.4), T.pole, cast=False)
        sc.add(flag(xc + 1.05, yc - 0.45, hs + 9.2, 7.5, 5.4), r3.m_cloth(T.banner))
    # at the foot: firewood for the beacon, a barrel
    barrel(sc, 3.25, 3.2, 0.2, 6.0)
    log_stack(sc, 0.45, 3.05, 3 if t >= 3 else 2, 0.75, 1.4)
    if t >= 3:
        crate(sc, 3.0, 0.75, 0.42, 5.5)
    return sc


# ---------------------------------------------------------------------------------------------
# alliance centre: a domed embassy rotunda, a big shield over the door, a banner for every ally
# ---------------------------------------------------------------------------------------------

def big_shield(sc, T, face, f, a, z, k=1.0, left="#2F66B8", right="#C8303E"):
    """Heater shield on a wall: gold rim, field parted in two colours, a gold boss."""
    out = [(-5.2, 5.6), (5.2, 5.6), (5.2, 0.4), (3.9, -3.2), (0.0, -6.6), (-3.9, -3.2), (-5.2, 0.4)]
    lf = [(-4.2, 4.7), (0.0, 4.7), (0.0, -5.3), (-3.1, -2.7), (-4.2, 0.3)]
    rt = [(0.0, 4.7), (4.2, 4.7), (4.2, 0.3), (3.1, -2.7), (0.0, -5.3)]
    x, y = (a, f) if face == "L" else (f, a)
    dx, dy = (0.0, 1.0) if face == "L" else (1.0, 0.0)
    sc.add(prism(out, x + dx * 0.02, y + dy * 0.02, z, face, th=0.5, k=k), T.gold, name="shield")
    sc.add(prism(lf, x + dx * 0.05, y + dy * 0.05, z, face, th=0.4, k=k), r3.m_flat(left, 0.05, spec=0.2), cast=False)
    sc.add(prism(rt, x + dx * 0.05, y + dy * 0.05, z, face, th=0.4, k=k), r3.m_flat(right, 0.05, spec=0.2), cast=False)
    sc.add(sphere(x + dx * 0.075, y + dy * 0.075, z + 0.3 * k, 0.075 * k), T.gold, cast=False)


def alliance_center(t):
    sc, T = building(t)
    xc = yc = 2.0
    h = 19.0 + t * 3.0
    r = 1.3
    zb = 4.5
    cm = (xc, yc, r)
    sc.add(cyl(xc, yc, r + 0.2, 0, zb, 0.4), T.base, cyl=(xc, yc, r + 0.2), name="base")
    # windows around the drum (angles: 90 = the lit left-front, 0 = the shaded right-front)
    wz, wh = zb + (h - zb) * 0.52, min(9.0, (h - zb) * 0.5)
    cuts = []
    for deg in (-12.0, 22.0, 52.0, 128.0):
        cuts.append(rotz(box(xc + r - 0.16, yc - 0.13, xc + r + 0.2, yc + 0.13, wz - wh / 2, wz + wh / 2), deg, xc, yc))
        sc.add(rotz(box(xc + r - 0.17, yc - 0.13, xc + r - 0.1, yc + 0.13, wz - wh / 2, wz + wh / 2), deg, xc, yc), T.glow, cast=False, ao=False)
        sc.add(rotz(sub(box(xc + r - 0.03, yc - 0.19, xc + r + 0.035, yc + 0.19, wz - wh / 2 - 0.9, wz + wh / 2 + 0.9),
                        box(xc + r - 0.2, yc - 0.13, xc + r + 0.2, yc + 0.13, wz - wh / 2, wz + wh / 2)), deg, xc, yc), T.trim, cast=False)
    sc.add(sub(cyl(xc, yc, r, zb - 1, h), *cuts), T.wall(zb, h, 0.0, 0.0, bay=0.51), cyl=cm, name="drum")
    # pilasters and cornice
    pil = union(*[rotz(box(xc + r - 0.03, yc - 0.075, xc + r + 0.075, yc + 0.075, zb, h - 2.4, 0.25), deg, xc, yc) for deg in (-30, 5, 37, 68, 112, 143)])
    sc.add(pil, T.base if t < 5 else T.wall(), name="pilasters")
    sc.add(cyl(xc, yc, r + 0.1, h - 3.0, h + 0.2, 0.3), T.curtain if t < 4 else T.gold, cyl=(xc, yc, r + 0.1), name="cornice")
    # roof: a cone at first, a dome from tier 3
    dh = 11.0 + t * 2.0
    if t < 3:
        sc.add(union(cone(xc, yc, r + 0.3, h, h + dh + 2.0), cyl(xc, yc, r + 0.3, h - 1.2, h + 0.05, 0.25)), T.roof, cyl=(xc, yc, r + 0.3), name="roof")
        top = h + dh + 2.0
    else:
        sc.add(dome(xc, yc, r + 0.08, h, dh), T.roof, cyl=(xc, yc, r + 0.08), name="dome")
        ribs = [rotz(inter(dome(xc, yc, r + 0.1, h, dh + 0.35), box(xc - 2, yc - 0.045, xc + 2, yc + 0.045, h, h + dh + 2)), deg, xc, yc) for deg in (0, 45, 90, 135)]
        sc.add(union(*ribs), T.gold if t >= 4 else T.base, name="ribs")
        # lantern
        sc.add(cyl(xc, yc, 0.24, h + dh - 1.5, h + dh + 3.6), T.wall(), cyl=(xc, yc, 0.24), name="lantern")
        sc.add(union(cone(xc, yc, 0.34, h + dh + 3.6, h + dh + 8.6), cyl(xc, yc, 0.34, h + dh + 2.9, h + dh + 3.65, 0.2)), T.roof if t < 5 else T.gold,
               cyl=(xc, yc, 0.34))
        top = h + dh + 8.6
    flagpole(sc, T, xc, yc, top - 1.5, 13.0, w=10.0, fh=7.0, taper=0.35)
    # the entrance: a porch whose round-topped front carries the alliance shield over the door
    py1 = 3.58
    pw = 1.16
    dh2 = 9.4
    k = 1.0 + 0.07 * t
    zs = dh2 + 2.2 + 6.6 * k                    # centre of the shield
    ftop = zs + 5.6 * k + 2.4
    hole = door(sc, T, "L", py1, 2.0, 0.66, dh2, True, fw=0.07, frame=T.base if t < 4 else T.trim)
    sc.add(sub(opening("L", py1, 2.0, 0.0, pw, ftop, True, -0.16, 0.0), hole), T.wall(zb, ftop - 6.0, 2.0 - pw / 2, 0.0, bay=pw / 2), name="front")
    edge = sub(opening("L", py1, 2.0, 0.0, pw + 0.1, ftop + 1.0, True, -0.2, 0.03), opening("L", py1, 2.0, -1.0, pw - 0.08, ftop + 0.2, True, -0.5, 0.5))
    sc.add(edge, T.trim if t != 3 else T.base, name="front edge")
    ph = min(h - 2.0, ftop - 8.0)
    sc.add(box(2.0 - pw / 2 + 0.04, 2.9, 2.0 + pw / 2 - 0.04, py1 - 0.1, 0, ph), T.wall(), name="porch")
    sc.add(gable(2.0 - pw / 2 + 0.04, 2.7, 2.0 + pw / 2 - 0.04, py1 - 0.12, ph, 4.5, "y", over=0.0, thick=1.4), T.roof, name="porch roof")
    big_shield(sc, T, "L", py1, 2.0, zs, k)
    # braziers (flames at z 5)
    for bx in (1.25, 2.75):
        brazier(sc, T, bx, 3.6, 5.0, 1.0, lit=t >= 2, metal=T.gold if t >= 5 else None)
    # a banner for every ally: on the drum and on poles around the forecourt
    for i, deg in enumerate((21.0, 53.0, 128.0 - 16.0)[: 1 + (t >= 2) + (t >= 3)]):
        bw = 0.3
        cloth = rotz(box(xc + r + 0.012, yc - bw / 2, xc + r + 0.04, yc + bw / 2, h - 5.0 - (h - zb) * 0.42, h - 4.0), deg + (16.0 if i < 2 else 0.0), xc, yc)
        sc.add(cloth, r3.m_cloth(BANNERS[(i + 2) % 7], trim="#F2C544", trim_h=1.2), name="wall banner")
    poles = [(0.42, 3.05, "L", 0), (3.62, 3.05, "R", 1), (0.42, 1.75, "L", 3), (3.62, 1.9, "R", 4), (3.62, 0.8, "R", 5), (0.9, 3.72, "L", 6)]
    for (px, py, fc, ci) in poles[: 1 + t]:
        gonfalon(sc, T, px, py, 17.0 + t, BANNERS[ci], w=0.36, ln=9.0 + t * 0.4, face=fc)
    return sc


# ---------------------------------------------------------------------------------------------
# castle skins (paid cosmetics): ice, gold, sakura, dragon.
# They keep the standard castle's footprint, its gate and the gate torches (the game draws animated flames
# there), but each has its own materials, roof shapes and set pieces.
# ---------------------------------------------------------------------------------------------

KEEP_H = [26.0, 34.0, 44.0, 52.0, 62.0]
TOWER_TH = [0.0, 30.0, 38.0, 46.0, 56.0]
CURTAIN_H = 12.0


def pyramid(xc, yc, r, z0, z1, n=6, start=0.0):
    """n-sided spike: base of circumradius r (grid units) at z0, apex at z1.  Flat facets, so it reads as cut
    crystal / horn rather than as a round cone."""
    X, Y, Z0, Z1, R = xc * G, yc * G, z0 * ZS, z1 * ZS, r * G
    inr, H = R * math.cos(math.pi / n), (z1 - z0) * ZS
    ln = math.hypot(H, inr)
    planes = [(0.0, 0.0, -1.0, -Z0)]
    for k in range(n):
        a = math.radians(start) + 2.0 * math.pi * (k + 0.5) / n
        nx, ny, nz = math.cos(a) * H / ln, math.sin(a) * H / ln, inr / ln
        planes.append((nx, ny, nz, nx * X + ny * Y + nz * Z1))
    return r3._Convex(planes, (X - R, Y - R, Z0), (X + R, Y + R, Z1))


def crystal(xc, yc, r, z0, z1, n=6, tip=0.34, start=0.0):
    """n-sided column with a pointed top; tip = the share of its height taken by the point."""
    X, Y, Z0, Z1, R = xc * G, yc * G, z0 * ZS, z1 * ZS, r * G
    inr, H = R * math.cos(math.pi / n), (z1 - z0) * ZS * tip
    ln = math.hypot(H, inr)
    planes = [(0.0, 0.0, -1.0, -Z0)]
    for k in range(n):
        a = math.radians(start) + 2.0 * math.pi * (k + 0.5) / n
        ca, sa = math.cos(a), math.sin(a)
        planes.append((ca, sa, 0.0, ca * X + sa * Y + inr))
        planes.append((ca * H / ln, sa * H / ln, inr / ln, (ca * X + sa * Y) * H / ln + inr / ln * Z1))
    return r3._Convex(planes, (X - R, Y - R, Z0), (X + R, Y + R, Z1))


def lean(node, deg, towards, x, y, z=0.0):
    """Tilt a node about the point (x, y, z) so that its top moves in the plan direction `towards` (degrees:
    0 = +x, 90 = +y, -45 = screen-right, 135 = screen-left, 45 = at the camera)."""
    return rotz(rot(node, "y", deg, (x, y, z)), towards, x, y)


def m_crystal(color="#8ED6F6", glow="#2F9BE0", power=0.32):
    """Glassy ice / gem crystal: mirror-like facets with a faint inner light."""
    base, gl = r3.srgb(color), r3.srgb(glow)

    def albedo(c):
        return base[None, :] * (0.9 + 0.2 * r3.vnoise2(c.u * 0.5, c.v * 0.5, 61))[..., None]

    def emis(c):
        return gl[None, :] * (power * (0.55 + 0.9 * r3.sstep(c.lo[2], c.hi[2], c.z)))[..., None]

    return r3.Material(albedo, spec=1.3, gloss=50.0, metal=0.42, emis=emis)


def _snow(color="#DCE6F0", seed=62):
    base = r3.srgb(color)
    return r3.Material(lambda c: base[None, :] * (0.92 + 0.12 * r3.fbm2(c.x * 0.21, c.y * 0.21, 2, seed))[..., None], wrap=0.3, spec=0.12, gloss=10.0)


def m_block(color, mortar, course=5.2, length=1.8, seed=74, spec=0.5, metal=0.1, gloss=38.0, var=0.1):
    """Cut blocks with a polished, slightly reflective face (ice blocks, gilded panels)."""
    m = r3.m_stone(color, course=course, length=length, mortar=mortar, var=var, joint=0.34, seed=seed, spec=spec)
    m.metal, m.gloss = metal, gloss
    return m


def m_basalt(color="#4C444E", course=4.4, length=2.0, seed=71, glow_top=8.0, power=1.5, lava="#FF6A1E"):
    """Dark volcanic blocks; near the ground the joints glow like cooling lava."""
    m = r3.m_stone(color, course=course, length=length, mortar="#19141A", var=0.17, joint=0.42, seed=seed, spec=0.14)
    lv = r3.srgb(lava)
    ch = course * ZS

    def emis(c):
        _, e = r3._blocks(c, ch, ch * length, seed)
        j = 1.0 - r3.sstep(0.08, 0.6, e)
        fade = 1.0 - r3.sstep(0.5 * ZS, glow_top * ZS, c.z)
        return lv[None, :] * (np.where(c.top, 0.0, j * fade) * power)[..., None]

    m.emis = emis
    return m


def m_lava_ground(seed=72, power=1.15):
    """Scorched rock crossed by glowing cracks."""
    rock, lv = r3.srgb("#3D363B"), r3.srgb("#FF6A1E")

    def crack(c):
        n1 = r3.fbm2(c.x * 0.105, c.y * 0.105, 3, seed)
        n2 = r3.fbm2(c.x * 0.19 + 7.0, c.y * 0.19, 2, seed + 3)
        d = np.minimum(np.abs(n1 - 0.5), np.abs(n2 - 0.5) * 1.5)
        return 1.0 - r3.sstep(0.004, 0.028, d)

    def albedo(c):
        k = 0.78 + 0.44 * r3.fbm2(c.x * 0.3, c.y * 0.3, 2, seed + 5)
        return rock[None, :] * (k * (1.0 - 0.55 * crack(c)))[..., None]

    def emis(c):
        return lv[None, :] * (crack(c) * np.where(c.top, power, 0.0))[..., None]

    return r3.Material(albedo, emis=emis, spec=0.1)


SNOW = _snow()
ICE = m_crystal()
ICE_DEEP = m_crystal("#6FBDEB", "#2A86D8", 0.42)
PINK = "#F08DB4"


def _blossom(seed=90):
    """Cherry blossom: clouds of pink that stay light and saturated in the shade."""
    deep, mid, pale = r3.srgb("#D9628F"), r3.srgb(PINK), r3.srgb("#FFD6E4")

    def albedo(c):
        n = r3.vnoise3(c.x * 0.2, c.y * 0.2, c.z * 0.2, seed)
        n2 = r3.vnoise3(c.x * 1.0, c.y * 1.0, c.z * 1.0, seed + 1)
        up = np.clip(c.nz * 0.5 + 0.5, 0.0, 1.0)
        a = r3.mixc(deep, mid, np.clip(0.25 + 0.9 * up + 0.4 * (n - 0.5), 0.0, 1.0))
        a = r3.mixc(a, pale, np.clip((n2 - 0.55) * 2.2, 0.0, 1.0) * up)
        return a * (0.84 + 0.3 * n)[..., None]

    def height(c):
        return r3.vnoise2(c.u * 0.8, c.v * 0.8, seed + 2) * 0.9 + r3.vnoise2(c.u * 2.1, c.v * 2.1, seed + 3) * 0.45

    return r3.Material(albedo, height, bump=0.8, wrap=0.4)


class Skin(Tier):
    """The material set of a castle skin, with the same fields as Tier."""

    def __init__(self, t, name):
        Tier.__init__(self, t)
        self.skin = name
        if name == "ice":
            self.banner = "#2F8FE0"
            self.gold = r3.m_metal("#DCEBF6")                       # frosted silver instead of gold
            self.glow = r3.m_glow("#B2F0FF", power=1.5, bar_col="#3A6A96")
            self.slit = r3.m_glow("#B2F0FF", bars=False)
            self.door = r3.m_planks("#3E5C7E", 0.14, seed=22, var=0.1)
            self.base = m_block("#6E9FCC", "#3F6EA0", course=4.6, seed=75, spec=0.4)
            self.roof = r3.m_tiles("#2B66B4", kind="scale", width=3.0, spec=0.5, metal=0.18)
            self.trim = m_block("#E3EEF7", "#9FBEDB", course=3.0, length=1.6, seed=76, spec=0.3, metal=0.0)
            self.pad = _snow("#D2DFEC", 63)
            self.curtain = m_block("#9CC3E3", "#5B8DBE", course=4.4, seed=77) if t >= 2 else ICE_DEEP
            self._wall = m_block("#C4DFF2", "#6C9CCB", seed=74)
        elif name == "gold":
            self.banner = "#B01E2C"
            self.gold = r3.m_gold("#F7C63E")
            self.glow = r3.m_glow("#FFE9A0", power=1.9)
            self.door = r3.m_planks("#6A2A1E", 0.14, seed=22, var=0.1)
            self.base = m_block("#8A5A1C", "#4E300A", course=4.4, seed=78, spec=0.8, metal=0.22)
            self.roof = r3.m_tiles("#C88214", kind="scale", width=2.8, row=2.8, spec=0.75, metal=0.22, var=0.1)
            self.trim = r3.m_marble("#F1E9D8", veins=0.2, seed=79)
            self.pad = r3.m_ground("marble", "#6B3A46")
            self.curtain = m_block("#B27A1E", "#6A420C", course=4.6, seed=80, spec=0.9, metal=0.22, var=0.08) if t >= 2 else r3.m_gold("#D29A28", 0.2)
            self._wall = m_block("#C68E2A", "#7A4E10", course=6.0, length=1.6, seed=73, spec=0.9, metal=0.22, var=0.07)
        elif name == "dragon":
            self.banner = "#B8202C"
            self.gold = r3.m_gold("#E0932E")
            self.glow = r3.m_glow("#FF8A2A", power=2.3, bar_col="#2A1210")
            self.slit = r3.m_glow("#FF7A1E", power=2.2, bars=False)
            self.door = r3.m_planks("#2E1E20", 0.14, seed=22, var=0.1)
            self.base = m_basalt("#3A343C", course=4.0, seed=81, glow_top=5.0, power=1.8)
            self.roof = r3.m_tiles("#4A2A33", kind="scale", width=3.0, spec=0.25, var=0.22)
            self.trim = r3.m_stone("#2C262E", course=3.0, length=1.6, seed=82, spec=0.2)
            self.pad = m_lava_ground()
            self.curtain = m_basalt("#514851", seed=83, glow_top=9.0) if t >= 2 else r3.m_planks("#2E2428", 0.14, seed=24, relief=0.5)
            self._wall = m_basalt("#5A5058", course=4.6, seed=71, glow_top=11.0, power=1.2)
        elif name == "sakura":
            self.banner = "#D8364A"
            self.glow = r3.m_glow("#FFD28A", power=1.6, bar_col="#4E3226")
            self.door = r3.m_planks("#B8322C", 0.16, seed=22, var=0.08)
            self.base = r3.m_rubble("#8D8985", size=4.4, seed=84)
            self.roof = r3.m_tiles("#3D4656", kind="clay", row=2.6, width=2.4, spec=0.22, var=0.1)
            self.trim = r3.m_planks("#4E3226", 0.2, seed=23)
            self.red = r3.m_flat("#C2362C", 0.06, spec=0.15)
            self.pad = r3.m_ground("paved", "#B4AC9A")
            self.curtain = r3.m_flat("#E9E0CF", 0.05)
            self._wall = None

    @property
    def stone(self):
        return self.skin != "sakura" and (self.t >= 2 or self.skin != "dragon")

    def wall(self, z0=4.0, z1=20.0, ox=0.0, oy=0.0, bay=0.45):
        if self.skin == "sakura":
            return r3.m_plaster("#EDE3D2", "#4E3226", z0, z1, bay, ox, oy, beam=1.25, mid=False, braces=False)
        return self._wall


def skin_scene(T):
    sc = Scene("building", ground=-LIP)
    sc.add(box(0.0, 0.0, 4.0, 4.0, -LIP, 0.0, r_px=0.35), T.pad, name="pad")
    return sc


def hang(sc, T, face, f, a, ztop, h, w=0.38, color=None, trim="#F2C544"):
    """Wall banner with a hem in a chosen colour (gen_sprites3d.banner always hems in gold)."""
    sc.add(_slab(face, f, a - w / 2, a + w / 2, 0.012, 0.035, ztop - h, ztop), r3.m_cloth(color or T.banner, trim=trim, trim_h=h * 0.14))
    sc.add(_slab(face, f, a - w / 2 - 0.05, a + w / 2 + 0.05, 0.0, 0.06, ztop - 0.4, ztop + 0.9, 0.3), T.gold, cast=False)


def skin_curtain(sc, T, t, frame=None, merlons=True, gate_h=11.5):
    """Curtain wall and gatehouse on the standard castle's plan (palisade at tier 1).  The gate stays at
    (2.0, 3.74) and the torches at the coordinates TerritoryLife.kt animates."""
    if t == 1:
        for seg in ((0.5, 0.5, 3.5, 0.5), (0.5, 0.5, 0.5, 3.5), (3.5, 0.5, 3.5, 3.5), (0.5, 3.5, 1.5, 3.5), (2.5, 3.5, 3.5, 3.5)):
            palisade(sc, T, *seg)
        for gx in (1.5, 2.5):
            sc.add(box(gx - 0.1, 3.4, gx + 0.1, 3.6, 0, 13.6, 0.3), T.trim, name="gate post")
        sc.add(box(1.4, 3.42, 2.6, 3.58, 10.6, 12.4, 0.3), T.trim)
        torch(sc, T, 1.53, 3.62, 15.5)
        torch(sc, T, 2.47, 3.62, 15.5)
        return 10.0
    cm, wh = T.curtain, CURTAIN_H
    gate = door(sc, T, "L", 3.74, 2.0, 0.68, gate_h, True, frame=frame or T.base, fw=0.07)
    ring = union(box(0.4, 0.4, 3.6, 0.8, 0, wh), box(0.4, 0.4, 0.8, 3.6, 0, wh), box(0.4, 3.2, 3.6, 3.6, 0, wh), box(3.2, 0.4, 3.6, 3.6, 0, wh))
    sc.add(sub(union(ring, box(1.38, 3.16, 2.62, 3.74, 0, 16.5)), gate), cm, name="curtain")
    if merlons:
        sc.add(union(merlon_ring(0.4, 0.4, 3.6, 3.6, wh, 3.2, 9, 0.12, sides="LR"), merlon_ring(0.4, 0.4, 3.6, 3.6, wh, 3.2, 9, 0.12, sides="BF")), cm)
        sc.add(merlon_ring(1.38, 3.16, 2.62, 3.74, 16.5, 3.0, 4, 0.12, ny=2, sides="LR"), cm)
    sc.add(union(box(0.4, 3.6, 1.38, 3.64, 0, 2.6, 0.2), box(2.62, 3.6, 3.6, 3.64, 0, 2.6, 0.2), box(3.6, 0.4, 3.64, 3.6, 0, 2.6, 0.2)), T.base, cast=False,
           name="plinth")
    torch(sc, T, 1.56, 3.79, 11.5, 0.85)
    torch(sc, T, 2.44, 3.79, 11.5, 0.85)
    return wh


def tower_shaft(sc, T, xc, yc, r, h, z0=0.0, wall=None, win=True, slit_deg=62.0, band=None):
    """A round tower up to its flared head, without a roof.  Returns the radius of the head."""
    wall = wall or T.wall()
    cm = (xc, yc, r)
    hh = h - z0
    slits = []
    if win:
        for zf in ((0.62,) if hh < 34 else (0.36, 0.72)):
            zc = z0 + hh * zf
            slits.append(rotz(box(xc + r - 0.12, yc - 0.075, xc + r + 0.2, yc + 0.075, zc - 3.6, zc + 3.6), slit_deg, xc, yc))
            sc.add(rotz(box(xc + r - 0.13, yc - 0.075, xc + r - 0.07, yc + 0.075, zc - 3.6, zc + 3.6), slit_deg, xc, yc), T.slit, cast=False, ao=False)
    sc.add(sub(cyl(xc, yc, r, z0, h - 3.0), *slits), wall, cyl=cm, name="tower")
    if band is not None and hh > 20:
        sc.add(cyl(xc, yc, r + 0.035, z0 + hh * 0.5 - 0.9, z0 + hh * 0.5 + 0.9, 0.35), band, cyl=cm, cast=False)
    sc.add(union(cone(xc, yc, r, h - 6.5, h - 4.0, r + 0.09), cyl(xc, yc, r + 0.09, h - 4.0, h, 0.25)), wall, cyl=(xc, yc, r + 0.09))
    return r + 0.09


def skin_keep(sc, T, t, kh, x0=1.1, y0=1.1, x1=2.9, y1=2.9, zb=5.0, ww=0.36):
    """The keep's body: plinth, walls, lit windows on both visible faces (and the hall door at tier 1)."""
    sc.add(box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, 0, zb, 0.4), T.base, name="keep base")
    hh = kh - zb
    rows = [zb + hh * 0.56] if hh < 36 else [zb + hh * 0.34, zb + hh * 0.74]
    wh = min(9.0, hh * 0.3)
    holes = []
    for zi, zz in enumerate(rows):
        for a in ((1.55, 2.45) if (zi == 0 or t >= 3) else (1.55, 2.0, 2.45)):
            holes.append(window(sc, T, "L", y1, a, zz, ww, wh))
            holes.append(window(sc, T, "R", x1, a, zz, ww, wh))
    if t == 1:
        holes.append(door(sc, T, "L", y1, 2.0, 0.6, 11.0, T.stone, z0=0.0))
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, kh), *holes), T.wall(zb, kh, x0, y0, bay=0.45), name="keep")


def keep_parapet(sc, T, kh, wall=None, cap=None, x0=1.1, y0=1.1, x1=2.9, y1=2.9, zb=5.0, pil=None):
    """Corner pilasters, corbelled parapet and merlons of a stone keep."""
    wall = wall or T.curtain
    sc.add(union(*[box(px - 0.1, py - 0.1, px + 0.1, py + 0.1, zb, kh + 1.0, 0.3) for px, py in ((x0, y1), (x1, y1), (x1, y0))]), pil or T.base, name="pilasters")
    sc.add(box(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, kh - 3.2, kh + 0.8, 0.3), wall, name="parapet")
    sc.add(merlon_ring(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, kh + 0.8, 3.2, 5, 0.13), cap or wall, name="merlons")


# ---- ice --------------------------------------------------------------------------------------

def icicles(axis, lo, hi, other, z, n, ln=5.0, r=0.042):
    """A row of n icicles hanging from height z along x (at y = other) or along y (at x = other); every second
    one is shorter."""
    step = (hi - lo) / max(n - 1, 1)
    parts = []
    for k, f in ((0, 1.0), (1, 0.6)):
        cnt = (n + 1 - k) // 2
        if cnt < 1:
            continue
        c0 = cone(lo + k * step, other, 0.0, z - ln * f, z + 0.3, r) if axis == "x" else cone(other, lo + k * step, 0.0, z - ln * f, z + 0.3, r)
        parts.append(repeat(c0, axis, step * 2.0, cnt) if cnt > 1 else c0)
    return union(*parts)


def icicle_ring(xc, yc, r, z, n=9, ln=5.0, start=0.0):
    a = ring_of(cone(xc + r, yc, 0.0, z - ln, z + 0.3, 0.042), n, xc, yc, start)
    b = ring_of(cone(xc + r, yc, 0.0, z - ln * 0.55, z + 0.3, 0.042), n, xc, yc, start + 180.0 / n)
    return union(a, b)


def crystal_cluster(sc, x, y, h, r=0.17, towards=-45.0, mat=None, seed=0):
    """A tall crystal with two shorter ones leaning away from it."""
    mat = mat or ICE
    sc.add(lean(crystal(x, y, r, -1.0, h, 6, 0.34, 15.0 * seed), 5.0, towards, x, y), mat, name="crystal")
    side = union(lean(crystal(x, y, r * 0.72, -1.0, h * 0.62, 6, 0.4, 20.0), 24.0, towards, x, y),
                 lean(crystal(x, y, r * 0.62, -1.0, h * 0.46, 6, 0.4, 5.0), 27.0, towards + 150.0, x, y))
    sc.add(side, ICE_DEEP if mat is ICE else mat, name="crystal")


def ice_tower(sc, T, xc, yc, r, h, spire, win=True, deg=62.0):
    hr = tower_shaft(sc, T, xc, yc, r, h, win=win, slit_deg=deg, band=T.base)
    sc.add(icicle_ring(xc, yc, hr + 0.01, h - 3.9, 9, 5.6, 10.0), ICE, cast=False, name="icicles")
    sc.add(lumpy(torus(xc, yc, h + 0.5, hr - 0.02, 1.9), 0.7, 0.45, int(xc * 7 + yc * 3)), SNOW, name="snow")
    sc.add(pyramid(xc, yc, hr + 0.02, h + 0.2, h + spire, 6, 30.0), ICE, name="spire")


def castle_ice(t):
    T = Skin(t, "ice")
    sc = skin_scene(T)
    kh, th = KEEP_H[t - 1], TOWER_TH[t - 1]
    x0, y0, x1, y1 = 1.1, 1.1, 2.9, 2.9
    wh = skin_curtain(sc, T, t, frame=T.trim)
    if t >= 2:
        # snow on the battlements, icicles along the two front walls
        caps = union(merlon_ring(0.4, 0.4, 3.6, 3.6, wh + 3.5, 0.75, 9, 0.12, sides="LR"), merlon_ring(0.4, 0.4, 3.6, 3.6, wh + 3.5, 0.75, 9, 0.12, sides="BF"),
                     merlon_ring(1.38, 3.16, 2.62, 3.74, 19.8, 0.75, 4, 0.12, ny=2, sides="LR"))
        sc.add(caps, SNOW, name="snow caps")
        sc.add(union(icicles("x", 0.55, 1.3, 3.615, wh - 0.2, 5, 5.2), icicles("x", 2.72, 3.0, 3.615, wh - 0.2, 3, 4.6),
                     icicles("y", 1.0, 2.95, 3.615, wh - 0.2, 9, 5.2), icicles("x", 1.5, 2.5, 3.755, 16.3, 6, 3.6)), ICE, cast=False, name="icicles")
        spire = 15.0 + 3.0 * t
        ice_tower(sc, T, 3.45, 0.55, 0.5, th, spire)
        ice_tower(sc, T, 0.55, 3.45, 0.5, th, spire, deg=28.0)
    if t >= 3:
        ice_tower(sc, T, 0.55, 0.55, 0.5, th + 4, spire, win=False)
        ice_tower(sc, T, 3.45, 3.45, 0.5, wh + 6 + t, spire * 0.62, win=False)
    skin_keep(sc, T, t, kh)
    if t == 1:
        sc.add(gable(x0, y0, x1, y1, kh, 12, "x", over=0.24, thick=2.2), T.roof, name="roof")
        sc.add(lumpy(gable(x0 - 0.1, y0 + 0.42, x1 + 0.1, y1 - 0.42, kh + 5.6, 7.4, "x", over=0.2, thick=0.6), 0.6, 0.5, 2), SNOW, name="snow")
        sc.add(icicles("x", 1.0, 3.0, y1 + 0.245, kh - 2.0, 10, 5.4), ICE, cast=False, name="icicles")
        sc.add(crystal(2.0, 2.0, 0.2, kh + 9.0, kh + 30.0, 6, 0.5, 30.0), ICE, name="spire")
    elif t == 2:
        rh = 14.0
        sc.add(hip(x0, y0, x1, y1, kh, rh, over=0.22, thick=1.8), T.roof, name="roof")
        sc.add(lumpy(hip(x0 + 0.34, y0 + 0.34, x1 - 0.34, y1 - 0.34, kh + rh * 0.42, rh * 0.6 + 0.9, thick=0.5), 0.6, 0.5, 3), SNOW, name="snow")
        sc.add(union(icicles("x", 1.0, 3.0, y1 + 0.225, kh - 1.6, 9, 5.0), icicles("y", 1.0, 3.0, x1 + 0.225, kh - 1.6, 9, 5.0)), ICE, cast=False, name="icicles")
        sc.add(crystal(2.0, 2.0, 0.22, kh + rh - 3.0, kh + rh + 16.0, 6, 0.5, 30.0), ICE, name="spire")
    else:
        keep_parapet(sc, T, kh, cap=T.curtain)
        sc.add(merlon_ring(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, kh + 4.3, 0.75, 5, 0.13), SNOW, name="snow caps")
        sc.add(union(icicles("x", 1.05, 2.95, y1 + 0.125, kh - 3.1, 9, 5.6), icicles("y", 1.05, 2.95, x1 + 0.125, kh - 3.1, 9, 5.6)), ICE, cast=False, name="icicles")
        sc.add(lumpy(ellipsoid(2.0, 2.0, kh + 0.6, 0.8, 0.8, 5.0), 0.7, 0.4, 4), SNOW, name="snow")
        # the crown: a great crystal spire with four leaning shards
        ch = 22.0 + 3.6 * t
        sc.add(crystal(2.0, 2.0, 0.3 + 0.015 * t, kh - 1.0, kh + ch, 6, 0.42, 30.0), ICE, name="spire")
        shards = [lean(crystal(2.0 + 0.36 * math.cos(math.radians(a)), 2.0 + 0.36 * math.sin(math.radians(a)), 0.17, kh - 1.0, kh + ch * 0.55, 6, 0.42, a),
                       19.0, a, 2.0 + 0.36 * math.cos(math.radians(a)), 2.0 + 0.36 * math.sin(math.radians(a)), kh) for a in (-45.0, 135.0, 45.0, 225.0)]
        sc.add(union(*shards), ICE_DEEP, name="shards")
        hang(sc, T, "L", y1, 2.0, kh - 6, kh * 0.30, 0.38, trim="#EAF4FB")
        hang(sc, T, "R", x1, 2.0, kh - 6, kh * 0.30, 0.38, trim="#EAF4FB")
    # crystal outcrops around the walls, drifts of snow
    crystal_cluster(sc, 3.84, 2.3, 17.0 + 3.0 * t, 0.16 + 0.008 * t, -45.0, seed=1)
    crystal_cluster(sc, 0.22, 3.8, 12.0 + 2.2 * t, 0.15 + 0.006 * t, 135.0, seed=2)
    if t >= 2:
        crystal_cluster(sc, 3.8, 0.26, 16.0 + 3.0 * t, 0.16, -45.0, seed=3)
        crystal_cluster(sc, 0.26, 0.95, 13.0 + 3.0 * t, 0.15, 135.0, seed=4)
    drifts = [ellipsoid(0.95, 3.74, 0.0, 0.42, 0.16, 2.6), ellipsoid(3.08, 3.76, 0.0, 0.3, 0.14, 2.2), ellipsoid(3.74, 1.5, 0.0, 0.16, 0.5, 2.6),
              ellipsoid(2.0, 3.86, 0.0, 0.5, 0.1, 1.2)]
    sc.add(lumpy(smooth(1.0, *drifts), 0.5, 0.4, 6), SNOW, cast=False, name="drifts")
    return sc


# ---- gold -------------------------------------------------------------------------------------

def gem(sc, T, face, f, a, z, color, r=0.075):
    """A big cabochon in a gold setting on a wall face."""
    rod = (lambda d0, d1, rr: cyl_y(f + d0, f + d1, a, z, rr)) if face == "L" else (lambda d0, d1, rr: cyl_x(f + d0, f + d1, a, z, rr))
    sc.add(rod(0.0, 0.04, r * G + 0.9), T.gold, cast=False)
    x, y = (a, f + 0.045) if face == "L" else (f + 0.045, a)
    sc.add(sphere(x, y, z, r), r3.m_glow(color, power=1.0, bars=False), cast=False, ao=False)


def onion(xc, yc, r, z, h):
    """Onion dome: a bulb wider than its drum, drawn up into a point."""
    return smooth(2.2, cyl(xc, yc, r * 0.92, z - 0.6, z + h * 0.2), ellipsoid(xc, yc, z + h * 0.36, r * 1.2, r * 1.2, h * 0.38),
                  cone(xc, yc, r * 0.8, z + h * 0.42, z + h, 0.0))


def gold_tower(sc, T, xc, yc, r, h, dh, win=True, deg=62.0, jewel="#FF3A58"):
    hr = tower_shaft(sc, T, xc, yc, r, h, win=win, slit_deg=deg, band=T.trim)
    sc.add(cyl(xc, yc, hr + 0.03, h - 1.6, h + 0.5, 0.3), T.trim, cyl=(xc, yc, hr), name="cornice")
    sc.add(onion(xc, yc, hr - 0.02, h + 0.5, dh), T.roof, cyl=(xc, yc, hr), name="dome")
    top = h + 0.5 + dh
    sc.add(capsule((xc, yc, top - 1.5), (xc, yc, top + 2.4), 0.45), T.gold, cast=False)
    sc.add(sphere(xc, yc, top + 3.2, 0.06), r3.m_glow(jewel, power=1.1, bars=False), cast=False, ao=False)


def coin_pile(sc, T, x, y, r=0.24, h=5.0, seed=0):
    coins = r3.m_gold("#F0B02A", 0.12)
    heap = smooth(1.2, cone(x, y, r, -0.5, h, r * 0.2), sphere(x + r * 0.45, y + r * 0.2, h * 0.18, r * 0.5), sphere(x - r * 0.4, y + r * 0.35, h * 0.2, r * 0.45))
    sc.add(lumpy(heap, 0.4, 1.3, seed), coins, name="coins")
    loose = [cyl(x + r * 1.3 * math.cos(a), y + r * 1.3 * math.sin(a), 0.05, 0.0, 0.5) for a in (math.radians(v + 40 * seed) for v in (20, 95, 170, 250, 310))]
    sc.add(union(*loose), coins, cast=False)
    sc.add(sphere(x - r * 0.2, y + r * 0.1, h + 0.5, 0.022), r3.m_glow("#FFF3B8", power=3.0, bars=False), cast=False, ao=False)


def castle_gold(t):
    T = Skin(t, "gold")
    sc = skin_scene(T)
    sc.add(sub(box(0.08, 0.08, 3.92, 3.92, -0.3, 0.12), box(0.16, 0.16, 3.84, 3.84, -1, 1)), T.gold, cast=False, name="inlay")
    kh, th = KEEP_H[t - 1], TOWER_TH[t - 1]
    x0, y0, x1, y1 = 1.1, 1.1, 2.9, 2.9
    wh = skin_curtain(sc, T, t, frame=T.trim)
    if t >= 2:
        # marble string course, and a ruby over the gate
        sc.add(union(box(0.4, 3.6, 1.38, 3.63, wh - 2.6, wh - 1.2), box(2.62, 3.6, 3.6, 3.63, wh - 2.6, wh - 1.2), box(3.6, 0.4, 3.63, 3.6, wh - 2.6, wh - 1.2)), T.trim,
               cast=False, name="string course")
        gem(sc, T, "L", 3.74, 2.0, 14.3, "#FF3A58", 0.07)
        dh = 11.0 + 1.6 * t
        gold_tower(sc, T, 3.45, 0.55, 0.5, th, dh, jewel="#5CF08A")
        gold_tower(sc, T, 0.55, 3.45, 0.5, th, dh, deg=28.0, jewel="#5CF08A")
    if t >= 3:
        gold_tower(sc, T, 0.55, 0.55, 0.5, th + 4, dh, win=False)
        gold_tower(sc, T, 3.45, 3.45, 0.5, wh + 6 + t, dh * 0.8, win=False, jewel="#4AD8FF")
    skin_keep(sc, T, t, kh)
    if t == 1:
        sc.add(gable(x0, y0, x1, y1, kh, 12, "x", over=0.24, thick=2.0), T.roof, name="roof")
        sc.add(box(x0 - 0.3, 1.94, x1 + 0.3, 2.06, kh + 11.2, kh + 12.8, 0.5), T.gold, cast=False)
        sc.add(union(*[box(px - 0.08, py - 0.08, px + 0.08, py + 0.08, 4.0, kh, 0.3) for px, py in ((x0, y1), (x1, y1), (x1, y0))]), T.trim, name="pilasters")
        for fx in (x0 - 0.2, x1 + 0.2):
            sc.add(sphere(fx, 2.0, kh + 14.0, 0.065), r3.m_glow("#FF3A58", power=1.1, bars=False), cast=False, ao=False)
            sc.add(capsule((fx, 2.0, kh + 11.5), (fx, 2.0, kh + 13.4), 0.5), T.gold, cast=False)
        gem(sc, T, "L", y1, 2.0, 15.4, "#4AD8FF", 0.06)
    else:
        keep_parapet(sc, T, kh, wall=T.trim, cap=T.gold, pil=T.trim)
        sc.add(box(x0 - 0.14, y0 - 0.14, x1 + 0.14, y1 + 0.14, kh - 2.3, kh - 1.0, 0.25), T.gold, cast=False, name="band")
        # drum and the great dome
        dr = 0.6 + 0.02 * t
        dz, dh2 = kh + 5.0 + t * 0.6, 13.0 + 2.8 * t
        sc.add(cyl(2.0, 2.0, dr, kh, dz), T.wall(), cyl=(2.0, 2.0, dr), name="drum")
        sc.add(cyl(2.0, 2.0, dr + 0.05, dz - 1.4, dz + 0.4, 0.3), T.trim, cyl=(2.0, 2.0, dr), name="drum cornice")
        sc.add(onion(2.0, 2.0, dr + 0.03, dz + 0.4, dh2), T.roof, cyl=(2.0, 2.0, dr), name="great dome")
        top = dz + 0.4 + dh2
        sc.add(capsule((2.0, 2.0, top - 2.0), (2.0, 2.0, top + 4.0), 0.55), T.gold, cast=False)
        sc.add(sphere(2.0, 2.0, top + 5.2, 0.085), r3.m_glow("#FF3A58", power=1.2, bars=False), cast=False, ao=False)
        if t >= 4:      # four small domes on the keep's corners
            for px, py in ((x0, y1), (x1, y1), (x1, y0), (x0, y0)):
                sc.add(cyl(px, py, 0.2, kh - 4.0, kh + 5.0), T.trim, cyl=(px, py, 0.2), name="turret")
                sc.add(onion(px, py, 0.21, kh + 5.0, 7.0 + t * 0.6), T.roof, cyl=(px, py, 0.21), name="turret dome")
        hang(sc, T, "L", y1, 2.0, kh - 6, kh * 0.30, 0.38)
        hang(sc, T, "R", x1, 2.0, kh - 6, kh * 0.30, 0.38)
        if t >= 3:
            for a, col in ((1.55, "#4AD8FF"), (2.45, "#5CF08A")):
                gem(sc, T, "L", y1, a, 5.0 + (kh - 5.0) * 0.54, col, 0.075)
                gem(sc, T, "R", x1, a, 5.0 + (kh - 5.0) * 0.54, col, 0.075)
    # treasure spilling out in front of the walls
    coin_pile(sc, T, 0.86, 3.84, 0.27, 5.0 + 0.4 * t, 1)
    coin_pile(sc, T, 3.84, 2.42, 0.24, 4.4 + 0.4 * t, 2)
    if t >= 3:
        coin_pile(sc, T, 3.84, 1.2, 0.2, 4.0, 3)
        g.chest(sc, T, 0.2, 3.5, 0.4, 4.6)
    return sc


# ---- dragon -----------------------------------------------------------------------------------

BONE = r3.m_flat("#E6D6B4", 0.06, spec=0.2)
OBSIDIAN = r3.m_flat("#2B2430", 0.08, spec=0.6, gloss=40.0)


def _wing_mat(color="#C2402A"):
    base, gl = r3.srgb(color), r3.srgb("#FF5A1E")
    return r3.Material(lambda c: base[None, :] * (0.85 + 0.3 * r3.vnoise2(c.u * 0.35, c.v * 0.35, 66))[..., None], wrap=0.45, spec=0.1,
                       emis=lambda c: gl[None, :] * 0.10 * np.ones(c.x.shape, F)[..., None])


WING = [(0.0, -1.0), (1.6, 4.4), (5.5, 12.0), (16.5, 18.5), (12.5, 10.5), (19.0, 7.5), (12.0, 3.2), (16.0, -4.0), (8.2, -1.6), (4.4, -6.6), (0.0, -3.8)]


def dragon(sc, x, y, z, k=1.0, hdg=45.0, body="#B41E2A"):
    """A dragon sitting at (x, y, z) and looking along hdg (45 = straight at the camera), wings spread.
    k scales it; at k = 1 it is about 40 viewport units from wing tip to wing tip and 26 tall."""
    c, s = math.cos(math.radians(hdg)), math.sin(math.radians(hdg))

    def P(f, sd, zz):          # forward and left in grid units, up in z units, all scaled by k
        return (x + (f * c - sd * s) * k, y + (f * s + sd * c) * k, z + zz * k)

    def cap(a, b, r):
        return capsule(P(*a), P(*b), r * k)

    def ball(a, r):
        p = P(*a)
        return sphere(p[0], p[1], p[2], r * k)

    t0 = P(-0.02, 0.0, 5.4)
    parts = [ellipsoid(t0[0], t0[1], t0[2], 0.25 * k, 0.25 * k, 5.0 * k), ball((0.1, 0.0, 6.8), 0.24),
             cap((0.1, 0.0, 8.4), (0.2, 0.0, 11.6), 2.2), cap((0.2, 0.0, 11.6), (0.27, 0.0, 13.8), 1.9), cap((0.26, 0.0, 14.4), (0.62, 0.0, 12.0), 1.85),
             cap((-0.14, 0.0, 3.0), (-0.3, -0.34, 0.6), 1.8), cap((-0.3, -0.34, 0.6), (-0.2, -0.68, -3.6), 1.35), cap((-0.2, -0.68, -3.6), (-0.02, -0.9, -7.2), 0.85)]
    for sd in (-1.0, 1.0):
        parts += [ball((-0.03, 0.24 * sd, 2.9), 0.2), cap((0.14, 0.19 * sd, 5.6), (0.27, 0.24 * sd, 0.7), 1.25)]
    sc.add(smooth(1.0 * k, *parts), r3.m_flat(body, 0.1, spec=0.4, gloss=28.0), name="dragon")
    horns = [cap((0.22, 0.09 * sd, 15.2), (0.04, 0.19 * sd, 19.6), 0.72) for sd in (-1.0, 1.0)]
    horns += [cap((0.29, 0.23 * sd, 0.3), (0.37, 0.24 * sd, -0.5), 0.65) for sd in (-1.0, 1.0)]              # claws
    horns += [cap((0.04 - 0.04 * i, 0.0, 12.6 - 1.9 * i), (-0.06 - 0.04 * i, 0.0, 13.8 - 1.9 * i), 0.5) for i in range(3)]       # crest down the neck
    sc.add(union(*horns), BONE, cast=False, name="horns")
    eyes = [ball((0.44, 0.09 * sd, 14.4), 0.03) for sd in (-1.0, 1.0)]
    sc.add(union(*eyes), r3.m_glow("#FFE45A", power=3.2, bars=False), cast=False, ao=False)
    m = P(0.67, 0.0, 11.5)
    sc.add(ellipsoid(m[0], m[1], m[2], 0.04 * k, 0.04 * k, 1.0 * k), r3.m_glow("#FF7A1E", power=3.6, bars=False), cast=False, ao=False)
    # wings: membranes that face the way the dragon looks, with dark finger bones
    wing = _wing_mat()
    for sd in (-1.0, 1.0):
        root = P(-0.07, 0.13 * sd, 9.0)
        pts = [(-a * sd, b) for a, b in WING]
        sc.add(_Prism([(a * k, b * k * ZS) for a, b in pts], (root[0] * G, root[1] * G, root[2] * ZS), math.radians(hdg - 90.0), 0.28 * k), wing, name="wing")

        def wp(a, b, fwd=0.035):
            return P(-0.07 + fwd, 0.13 * sd + a * sd / G, 9.0 + b)

        bones = [capsule(wp(0.0, -1.0), wp(5.5, 12.0), 0.8 * k)] + [capsule(wp(5.5, 12.0), wp(a, b), 0.55 * k) for a, b in ((16.5, 18.5), (19.0, 7.5), (16.0, -4.0))]
        sc.add(union(*bones), r3.m_flat("#5A1418", 0.08, spec=0.3), cast=False, name="wing bones")


def spike_ring(xc, yc, r, z, n=5, ln=7.0, start=0.0, out=24.0):
    """Horn-like spikes around a tower head, leaning outwards."""
    return union(*[lean(pyramid(xc + r * math.cos(math.radians(a)), yc + r * math.sin(math.radians(a)), 0.085, z - 1.0, z + ln, 4, 45.0 + a), out, a,
                        xc + r * math.cos(math.radians(a)), yc + r * math.sin(math.radians(a)), z) for a in (start + 360.0 * k / n for k in range(n))])


def dragon_tower(sc, T, xc, yc, r, h, roof_h, win=True, deg=62.0):
    hr = tower_shaft(sc, T, xc, yc, r, h, win=win, slit_deg=deg, band=T.base)
    sc.add(spike_ring(xc, yc, hr - 0.02, h - 0.6, 6, 6.6, 15.0), OBSIDIAN, name="spikes")
    sc.add(union(cone(xc, yc, hr + 0.1, h, h + roof_h, 0.03), cyl(xc, yc, hr + 0.1, h - 0.9, h + 0.05, 0.2)), T.roof, cyl=(xc, yc, hr + 0.1), name="roof")
    sc.add(sphere(xc, yc, h + roof_h + 0.6, 0.055), EMBER, cast=False, ao=False)


def castle_dragon(t):
    T = Skin(t, "dragon")
    sc = skin_scene(T)
    kh, th = KEEP_H[t - 1], TOWER_TH[t - 1]
    x0, y0, x1, y1 = 1.1, 1.1, 2.9, 2.9
    wh = skin_curtain(sc, T, t, frame=T.trim)
    if t >= 2:
        # an ember-lit arrow slit either side of the gate, fire bowls on the gatehouse
        for bx in (1.5, 2.5):
            sc.add(union(cone(bx, 3.45, 0.05, 16.5, 18.2, 0.13), cyl(bx, 3.45, 0.04, 16.3, 16.9)), IRON, name="fire bowl")
            sc.add(ellipsoid(bx, 3.45, 19.6, 0.06, 0.06, 2.0), FLAME, cast=False, ao=False)
        rh = 15.0 + 3.0 * t
        dragon_tower(sc, T, 3.45, 0.55, 0.5, th, rh)
        dragon_tower(sc, T, 0.55, 3.45, 0.5, th, rh, deg=28.0)
    if t >= 3:      # no back tower: its roof would stand right behind the dragon and spoil the silhouette
        dragon_tower(sc, T, 3.45, 3.45, 0.5, wh + 6 + t, rh * 0.6, win=False)
    skin_keep(sc, T, t, kh, ww=0.3)
    k = [0.72, 0.86, 0.98, 1.06, 1.14][t - 1]
    if t == 1:
        sc.add(gable(x0, y0, x1, y1, kh, 12, "x", over=0.24, thick=2.0), T.roof, name="roof")
        sc.add(box(x0 - 0.3, 1.9, x1 + 0.3, 2.1, kh + 10.6, kh + 12.4, 0.5), T.trim, name="ridge")
        sc.add(union(*[lean(pyramid(fx, 2.0, 0.1, kh + 10.0, kh + 19.0, 4, 45.0), 22.0, a, fx, 2.0, kh + 11.0) for fx, a in ((x0 - 0.22, 180.0), (x1 + 0.22, 0.0))]), OBSIDIAN,
               name="horns")
        dragon(sc, 2.0, 2.0, kh + 12.2, k)
    else:
        zt = kh + 1.6 + (8.0 + t * 1.4)
        if t == 2:
            sc.add(inter(hip(x0, y0, x1, y1, kh, 16.0, over=0.22, thick=1.8), box(0, 0, 4, 4, kh - 3, zt)), T.roof, name="roof")
        else:
            keep_parapet(sc, T, kh)
            sc.add(inter(hip(x0 + 0.08, y0 + 0.08, x1 - 0.08, y1 - 0.08, kh + 1.6, 22.0 + t, thick=1.0), box(0, 0, 4, 4, kh, zt)), T.roof, name="roof")
            sc.add(union(*[lean(pyramid(px, py, 0.11, kh + 2.0, kh + 12.0 + t, 4, 45.0 + a), 18.0, a, px, py, kh + 3.0)
                           for px, py, a in ((x0 - 0.02, y1 + 0.02, 135.0), (x1 + 0.02, y1 + 0.02, 45.0), (x1 + 0.02, y0 - 0.02, -45.0))]), OBSIDIAN, name="horns")
            hang(sc, T, "L", y1, 2.0, kh - 6, kh * 0.30, 0.38)
            hang(sc, T, "R", x1, 2.0, kh - 6, kh * 0.30, 0.38)
        sc.add(box(1.62, 1.62, 2.38, 2.38, zt - 1.0, zt + 0.6, 0.3), T.trim, name="perch")
        dragon(sc, 2.0, 2.0, zt + 0.6, k)
    # embers drifting up, obsidian shards in the corners
    for i, (ex, ey, ez) in enumerate(((0.5, 3.6, 22.0), (3.7, 2.2, 30.0), (1.3, 3.2, kh + 4.0), (3.2, 1.2, kh + 9.0), (0.9, 2.6, kh - 6.0), (2.9, 3.4, 34.0))):
        sc.add(sphere(ex, ey, ez, 0.022 + 0.004 * (i % 2)), EMBER, cast=False, ao=False)
    sc.add(union(lean(pyramid(3.82, 2.3, 0.15, -1.0, 13.0 + 2 * t, 5, 10.0), 14.0, -45.0, 3.82, 2.3), lean(pyramid(3.7, 2.62, 0.11, -1.0, 8.0 + t, 5, 40.0), 24.0, 0.0, 3.7, 2.62),
                 lean(pyramid(0.22, 3.8, 0.14, -1.0, 11.0 + 2 * t, 5, 30.0), 14.0, 135.0, 0.22, 3.8), lean(pyramid(0.5, 3.86, 0.1, -1.0, 7.0 + t, 5, 0.0), 22.0, 90.0, 0.5, 3.86)),
           OBSIDIAN, name="shards")
    return sc


# ---- sakura -----------------------------------------------------------------------------------

def cherry_tree(sc, x, y, s=1.0, seed=0):
    """Blossoming cherry: a short forked trunk under a cloud of pink."""
    bark = r3.m_planks("#5A3A30", 0.05, seed=63, relief=0.5)
    sc.add(union(cone(x, y, 0.105 * s, -0.5, 11.0 * s, 0.055 * s), capsule((x, y, 8.0 * s), (x + 0.22 * s, y - 0.1 * s, 14.0 * s), 1.0 * s),
                 capsule((x, y, 7.0 * s), (x - 0.2 * s, y + 0.14 * s, 13.5 * s), 0.9 * s)), bark, name="trunk")
    blobs = [(0.0, 0.0, 19.5, 0.43), (0.3, -0.14, 16.0, 0.3), (-0.28, 0.16, 16.2, 0.31), (0.12, 0.3, 15.6, 0.27), (-0.12, -0.3, 16.6, 0.27), (0.02, 0.02, 25.0, 0.27)]
    canopy = smooth(1.6 * s, *[sphere(x + bx * s, y + by * s, bz * s, br * s) for bx, by, bz, br in blobs])
    sc.add(lumpy(canopy, 0.9 * s, 0.5 / s, seed), _blossom(90 + seed), name="blossom")
    # petals on the ground
    sc.add(lumpy(ellipsoid(x + 0.1 * s, y + 0.1 * s, 0.0, 0.5 * s, 0.42 * s, 0.55), 0.3, 0.9, seed + 3), r3.m_flat("#F6C3D4", 0.1), cast=False, name="petals")


def curved_roof(sc, T, x0, y0, x1, y1, z, h, over=0.3, thick=0.9, tips=None):
    """Hipped roof with wide eaves whose corners sweep upwards."""
    sc.add(hip(x0, y0, x1, y1, z, h, over=over, thick=thick), T.roof, name="roof")
    sc.add(sub(box(x0 - over - 0.02, y0 - over - 0.02, x1 + over + 0.02, y1 + over + 0.02, z - thick - 0.5, z - thick + 0.25),
               box(x0 - over + 0.06, y0 - over + 0.06, x1 + over - 0.06, y1 + over - 0.06, z - thick - 1, z + 1)), tips or T.red, cast=False, name="eave line")
    horns = []
    for sx, cx in ((-1, x0 - over), (1, x1 + over)):
        for sy, cy in ((-1, y0 - over), (1, y1 + over)):
            horns.append(capsule((cx - 0.16 * sx, cy - 0.16 * sy, z - thick * 0.5 + 0.9), (cx + 0.07 * sx, cy + 0.07 * sy, z + 2.6), 1.0))
    sc.add(union(*horns), T.roof, name="upswept corners")
    if tips is not None:
        sc.add(union(*[sphere(cx + 0.07 * sx, cy + 0.07 * sy, z + 3.1, 0.045) for sx, cx in ((-1, x0 - over), (1, x1 + over)) for sy, cy in ((-1, y0 - over), (1, y1 + over))]),
               tips, cast=False)


def paper_lantern(sc, T, x, y, z, s=1.0):
    """Red paper lantern; (x, y, z) is its middle (the animated flame is drawn on the same point)."""
    sc.add(ellipsoid(x, y, z, 0.085 * s, 0.085 * s, 2.5 * s), r3.m_glow("#FF4A34", power=1.25, bars=False), cast=False, ao=False, name="lantern")
    sc.add(union(cyl(x, y, 0.05 * s, z + 2.2 * s, z + 3.0 * s), cyl(x, y, 0.05 * s, z - 3.0 * s, z - 2.2 * s)), r3.m_flat("#2E1E1A", 0.05), cast=False)


def battered_base(x0, y0, x1, y1, h, b=0.22):
    """Stone footing with sloping sides: (x0, y0)-(x1, y1) is its top, b the spread at the ground."""
    W = min(x1 - x0, y1 - y0) / 2.0 + b
    return inter(hip(x0 - b, y0 - b, x1 + b, y1 + b, 0.0, h * W / b, thick=0.6), box(x0 - b - 0.1, y0 - b - 0.1, x1 + b + 0.1, y1 + b + 0.1, -0.6, h))


def yagura(sc, T, xc, yc, w, zb, hs, rh, gold=None):
    """Small corner turret: stone footing, a white storey, a curved roof."""
    sc.add(battered_base(xc - w, yc - w, xc + w, yc + w, zb, 0.1), T.base, name="turret base")
    holes = [window(sc, T, "L", yc + w, xc, zb + hs * 0.55, 0.26, hs * 0.42, arch=False), window(sc, T, "R", xc + w, yc, zb + hs * 0.55, 0.26, hs * 0.42, arch=False)]
    sc.add(sub(box(xc - w, yc - w, xc + w, yc + w, zb - 0.5, zb + hs), *holes), T.wall(zb, zb + hs, xc - w, yc - w, bay=w), name="turret")
    curved_roof(sc, T, xc - w, yc - w, xc + w, yc + w, zb + hs, rh, over=0.2, thick=0.8, tips=gold)
    sc.add(sphere(xc, yc, zb + hs + rh + 0.4, 0.05), T.gold, cast=False)


def castle_sakura(t):
    T = Skin(t, "sakura")
    sc = skin_scene(T)
    gold = T.gold if t >= 4 else None
    xc = yc = 2.0
    if t == 1:
        # garden pavilion behind a low fence and a red gate with lanterns
        g.fence(sc, T, [(1.5, 3.52), (0.5, 3.52), (0.5, 0.5), (3.5, 0.5), (3.5, 3.52), (2.5, 3.52)], h=6.0)
        for gx in (1.5, 2.5):
            sc.add(box(gx - 0.075, 3.44, gx + 0.075, 3.6, 0, 13.0, 0.3), T.red, name="gate post")
        sc.add(union(box(1.26, 3.44, 2.74, 3.6, 11.4, 12.8, 0.4), box(1.4, 3.46, 2.6, 3.58, 8.6, 9.6, 0.3)), T.red, name="gate beams")
        paper_lantern(sc, T, 1.53, 3.62, 15.5, 0.9)
        paper_lantern(sc, T, 2.47, 3.62, 15.5, 0.9)
        sc.add(box(0.95, 0.95, 3.05, 3.05, 0, 3.0, 0.4), T.base, name="platform")
        w, kh = 0.8, 17.0
        holes = [door(sc, T, "L", yc + w, xc, 0.5, 9.5, False, z0=3.0), window(sc, T, "L", yc + w, 1.55, 11.0, 0.3, 5.5), window(sc, T, "L", yc + w, 2.45, 11.0, 0.3, 5.5),
                 window(sc, T, "R", xc + w, 1.62, 11.0, 0.3, 5.5), window(sc, T, "R", xc + w, 2.38, 11.0, 0.3, 5.5)]
        sc.add(sub(box(xc - w, yc - w, xc + w, yc + w, 2.5, kh), *holes), T.wall(3.0, kh, xc - w, yc - w, bay=0.4), name="hall")
        posts = [box(px - 0.06, py - 0.06, px + 0.06, py + 0.06, 3.0, kh, 0.25) for px in (xc - w - 0.2, xc + w + 0.2) for py in (yc - w - 0.2, yc + w + 0.2)]
        sc.add(union(*posts), T.red, name="veranda posts")
        curved_roof(sc, T, xc - w, yc - w, xc + w, yc + w, kh, 12.5, over=0.42, thick=1.1)
        sc.add(union(sphere(xc, yc, kh + 13.4, 0.07), cyl(xc, yc, 0.035, kh + 10.5, kh + 13.0)), T.gold, name="finial")
        cherry_tree(sc, 0.95, 2.85, 1.1, 1)
        cherry_tree(sc, 3.05, 1.0, 1.0, 2)
        return sc
    # outer wall: white plaster on a stone footing with a tiled coping
    wh = 9.6
    gate_h = 10.5
    gate = door(sc, T, "L", 3.74, 2.0, 0.72, gate_h, False, frame=T.red, fw=0.08)
    foot = union(box(0.38, 0.38, 3.62, 0.82, 0, 3.6), box(0.38, 0.38, 0.82, 3.62, 0, 3.6), box(0.38, 3.18, 3.62, 3.62, 0, 3.6), box(3.18, 0.38, 3.62, 3.62, 0, 3.6))
    sc.add(foot, T.base, name="wall footing")
    ring = union(box(0.42, 0.42, 3.58, 0.78, 3.0, wh), box(0.42, 0.42, 0.78, 3.58, 3.0, wh), box(0.42, 3.22, 3.58, 3.58, 3.0, wh), box(3.22, 0.42, 3.58, 3.58, 3.0, wh))
    sc.add(ring, T.curtain, name="wall")
    cope = union(gable(0.36, 0.36, 3.64, 0.84, wh, 2.6, "x", thick=0.7), gable(0.36, 0.36, 0.84, 3.64, wh, 2.6, "y", thick=0.7),
                 gable(0.36, 3.16, 3.64, 3.64, wh, 2.6, "x", thick=0.7), gable(3.16, 0.36, 3.64, 3.64, wh, 2.6, "y", thick=0.7))
    sc.add(cope, T.roof, name="coping")
    # gatehouse with a curved roof and two lanterns where the torches burn
    gh = 14.0
    sc.add(sub(box(1.38, 3.12, 2.62, 3.74, 0, gh), gate), T.trim, name="gatehouse")
    curved_roof(sc, T, 1.38, 3.12, 2.62, 3.74, gh, 5.5, over=0.2, thick=0.9, tips=gold)
    paper_lantern(sc, T, 1.56, 3.79, 11.5, 0.9)
    paper_lantern(sc, T, 2.44, 3.79, 11.5, 0.9)
    sc.add(union(capsule((1.56, 3.76, 14.3), (1.56, 3.79, 13.2), 0.3), capsule((2.44, 3.76, 14.3), (2.44, 3.79, 13.2), 0.3)), T.pole, cast=False)
    # the keep: a stone footing and one storey per tier, each under its own curved roof
    n = t
    hb = 5.0 + 1.5 * t
    w = 0.98
    dw = 0.17 if n <= 3 else 0.14
    sc.add(battered_base(xc - w, yc - w, xc + w, yc + w, hb, 0.22), T.base, name="footing")
    z = hb
    for i in range(n):
        hs = 12.4 - 0.6 * i
        aa = (xc - w * 0.5, xc + w * 0.5) if w > 0.6 else (xc,)
        holes = []
        for a in aa:
            holes.append(window(sc, T, "L", yc + w, a, z + hs * 0.56, min(0.34, w * 0.42), hs * 0.44, arch=False))
            holes.append(window(sc, T, "R", xc + w, a, z + hs * 0.56, min(0.34, w * 0.42), hs * 0.44, arch=False))
        sc.add(sub(box(xc - w, yc - w, xc + w, yc + w, z - 3.0, z + hs), *holes), T.wall(z, z + hs, xc - w, yc - w, bay=w / (2.0 if w > 0.6 else 1.0)), name="storey")
        if i < n - 1:
            w2 = w - dw
            curved_roof(sc, T, xc - w, yc - w, xc + w, yc + w, z + hs, 3.0 / (w + 0.26 - w2) * (w + 0.26), over=0.26, tips=gold)
            z, w = z + hs + 2.2, w2
        else:
            rh = 9.0 + t
            curved_roof(sc, T, xc - w, yc - w, xc + w, yc + w, z + hs, rh, over=0.32, thick=1.0, tips=gold)
            top = z + hs + rh
            sc.add(union(cyl(xc, yc, 0.04, top - 2.0, top + 3.0 + t), sphere(xc, yc, top + 3.6 + t, 0.065), torus(xc, yc, top + 1.2, 0.09, 0.45)), T.gold, name="finial")
            if t >= 3:      # red balcony round the top storey
                rail = sub(box(xc - w - 0.16, yc - w - 0.16, xc + w + 0.16, yc + w + 0.16, z + 0.2, z + 2.6), box(xc - w - 0.11, yc - w - 0.11, xc + w + 0.11, yc + w + 0.11, z - 1, z + 4))
                sc.add(rail, T.red, name="balcony")
    # corner turrets and trees
    if t >= 3:
        yagura(sc, T, 3.38, 3.38, 0.36, wh + 1.0, 7.0, 5.0, gold)
        yagura(sc, T, 0.62, 0.62, 0.38, wh + 1.0, 9.0 + t, 5.5, gold)
    cherry_tree(sc, 0.95, 2.98, 1.1 + 0.04 * t, 1)
    cherry_tree(sc, 3.0, 0.95, 1.02 + 0.04 * t, 2)
    if t >= 3:
        cherry_tree(sc, 3.84, 2.25, 0.7, 3)
    return sc


# ---------------------------------------------------------------------------------------------
# registry
# ---------------------------------------------------------------------------------------------

SPRITES = {}
for _t in (2, 4):
    SPRITES["bld_barracks_t%d" % _t] = (lambda t=_t: g.barracks(t))
for _name, _fn in (("archery_range", archery_range), ("stable", stable), ("siege_workshop", siege_workshop), ("hospital", hospital),
                   ("wall", wall_building), ("watchtower", watchtower), ("alliance_center", alliance_center)):
    for _t in range(1, 6):
        SPRITES["bld_%s_t%d" % (_name, _t)] = (lambda t=_t, fn=_fn: fn(t))
for _skin, _fn in (("ice", castle_ice), ("gold", castle_gold), ("sakura", castle_sakura), ("dragon", castle_dragon)):
    for _t in range(1, 6):
        SPRITES["bld_castle_%s_t%d" % (_skin, _t)] = (lambda t=_t, fn=_fn: fn(t))
