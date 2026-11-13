#!/usr/bin/env python3
"""gen_sprites3d.py - 3D models of Crownfall's map sprites, rendered with render3d.py.

Run:   python3 tools/artgen/gen_sprites3d.py                 # every sprite -> res/drawable-xxxhdpi/*.webp
       python3 tools/artgen/gen_sprites3d.py castle_t3 farm  # only names that start with these words
       python3 tools/artgen/gen_sprites3d.py --png DIR ...    # also write PNG copies into DIR
       python3 tools/artgen/gen_sprites3d.py --sheet out.png  # preview sheet (1x and 2x on grass, old vs new)
       python3 tools/artgen/gen_sprites3d.py --list
Output: app/src/main/res/drawable-xxxhdpi/<resource name>.webp, 4 px per viewport unit, lossy WebP + alpha.
Deterministic: no randomness, the same sources give the same bytes.

Every sprite is a function that returns a render3d.Scene.  Units are those of the old vector art (iso.py):
x, y in grid units (a plot is 0..4 x 0..4), z in viewport units straight up.  See README_3D.md.
"""

import math
import multiprocessing
import pathlib
import sys
import time

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import render3d as r3  # noqa: E402
from render3d import (G, ZS, Scene, box, capsule, cone, cyl, cyl_x, cyl_y, ellipsoid, flag, gable, hip, inter,  # noqa: E402,F401
                      lumpy, move, repeat, rotz, rounded, smooth, sphere, sub, torus, union)

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/res/drawable-xxxhdpi"
OLD = ROOT / "app/src/main/res/drawable"
LIP = 1.6          # thickness of a building's ground pad (its top is z = 0, the ground is z = -LIP)


# ---------------------------------------------------------------------------------------------
# tiers: 1 rough wood & thatch, 2 timber & clay tile, 3 cut stone & slate, 4 white stone & blue roofs,
#        5 marble & gold
# ---------------------------------------------------------------------------------------------

class Tier:
    def __init__(self, t):
        self.t = t
        self.banner = ["#C43A2E", "#2F66B8", "#C0303C", "#C8303E", "#7B2FB2"][t - 1]
        self.gold = r3.m_gold("#F2BE3C")
        self.iron = r3.m_metal("#5A5E6A")
        self.pole = r3.m_flat("#4A3524", 0.1)
        self.dark_wood = r3.m_planks("#6A4326", 0.22, seed=21)
        self.door = r3.m_planks(["#7C4F28", "#7C4F28", "#6C4728", "#603F26", "#6C3F22"][t - 1], 0.14, seed=22, var=0.1)
        self.glow = r3.m_glow(["#FFB84A", "#FFC052", "#FFC65A", "#FFD070", "#FFDC8A"][t - 1])
        self.slit = r3.m_glow("#FFC65A", bars=False)
        if t == 1:
            self.base = r3.m_rubble("#9C9486")
            self.roof = r3.m_thatch("#B0965C")
            self.trim = r3.m_planks("#6B4426", 0.2, seed=23)
            self.pad = r3.m_ground("dirt", "#8C7A60")
            self.curtain = r3.m_planks("#84583A", 0.14, seed=24, relief=0.5)
        elif t == 2:
            self.base = r3.m_rubble("#A59D90")
            self.roof = r3.m_tiles("#B94C30", kind="clay")
            self.trim = r3.m_planks("#74492A", 0.2, seed=23)
            self.pad = r3.m_ground("dirt", "#9C8A68")
            self.curtain = r3.m_rubble("#A39B8E", size=4.6)
        elif t == 3:
            self.base = r3.m_stone("#958E82", course=4.2)
            self.roof = r3.m_tiles("#4A6699", kind="slate", width=3.4)
            self.trim = r3.m_stone("#8F887B", course=3.0, length=1.6, seed=31)
            self.pad = r3.m_ground("paved", "#989282")
            self.curtain = r3.m_stone("#A8A190", course=4.2)
        elif t == 4:
            self.base = r3.m_stone("#AFA89A", course=4.6)
            self.roof = r3.m_tiles("#2452B4", kind="scale", width=3.2, spec=0.10)
            self.trim = self.gold
            self.pad = r3.m_ground("paved", "#B3AE9F")
            self.curtain = r3.m_stone("#D0C9B9", course=5.4, joint=0.32, var=0.07)
        else:
            self.base = r3.m_marble("#BDB29C", veins=0.25, seed=9)
            self.roof = r3.m_tiles("#D08E1C", kind="scale", width=3.2, spec=0.7, metal=0.16, var=0.12)
            self.trim = self.gold
            self.pad = r3.m_ground("marble", "#D9D2C0")
            self.curtain = r3.m_marble("#E6DFD0", seed=8)

    def wall(self, z0=4.0, z1=20.0, ox=0.0, oy=0.0, bay=0.45):
        """Wall material of the tier.  Timber framing (tier 2) needs the wall's extent to place its beams."""
        t = self.t
        if t == 1:
            return r3.m_planks("#A2643A", 0.26)
        if t == 2:
            return r3.m_plaster("#EDDDB4", "#6E4526", z0, z1, bay, ox, oy)
        if t == 3:
            return r3.m_stone("#BCB29C")
        if t == 4:
            return r3.m_stone("#DDD6C7", course=5.6, joint=0.3, var=0.06, mortar="#8F897D")
        return r3.m_marble("#EFE9DC", veins=0.42)

    @property
    def stone(self):
        return self.t >= 3


# ---------------------------------------------------------------------------------------------
# shared parts
# ---------------------------------------------------------------------------------------------

def building(t):
    """A building scene with the tier's ground pad (the 4 x 4 plot)."""
    T = Tier(t)
    sc = Scene("building", ground=-LIP)
    sc.add(box(0.0, 0.0, 4.0, 4.0, -LIP, 0.0, r_px=0.35), T.pad, name="pad")
    if t >= 5:   # gold inlay around the marble pad
        sc.add(sub(box(0.08, 0.08, 3.92, 3.92, -0.3, 0.12), box(0.16, 0.16, 3.84, 3.84, -1, 1)), T.gold, cast=False)
    return sc, T


def _slab(face, f, a0, a1, d0, d1, z0, z1, r_px=0.0):
    """A box on a wall face.  face 'L' = the left-front face (plane y = f, a runs along x);
    face 'R' = the right-front face (plane x = f, a runs along y).  d0..d1 = depth range, + is outward."""
    if face == "L":
        return box(a0, f + d0, a1, f + d1, z0, z1, r_px)
    return box(f + d0, a0, f + d1, a1, z0, z1, r_px)


def opening(face, f, a, z0, w, h, arch, d0, d1):
    """Window / door shape on a face: width w (grid units) centred at a, from z0 up to z0 + h."""
    if not arch:
        return _slab(face, f, a - w / 2, a + w / 2, d0, d1, z0, z0 + h)
    r = w * G / 2.0
    zc = z0 + h - r / ZS
    rod = cyl_y(f + d0, f + d1, a, zc, r) if face == "L" else cyl_x(f + d0, f + d1, a, zc, r)
    return union(_slab(face, f, a - w / 2, a + w / 2, d0, d1, z0, zc), rod)


def window(sc, T, face, f, a, zc, w=0.42, h=8.0, arch=None, frame=None, glow=None, fw=0.07, sill=True):
    """Lit window on a face.  Adds the glowing pane and its frame; returns the hole to subtract from the wall."""
    arch = T.stone if arch is None else arch
    z0 = zc - h / 2.0
    fz = fw * G / ZS
    hole = opening(face, f, a, z0, w, h, arch, -0.05, 0.3)
    sc.add(opening(face, f, a, z0, w, h, arch, -0.07, -0.035), glow or T.glow, cast=False, ao=False)
    fr = sub(opening(face, f, a, z0 - fz * 0.6, w + 2 * fw, h + fz * 1.6, arch, -0.02, 0.028), opening(face, f, a, z0, w, h, arch, -0.2, 0.2))
    if sill:
        fr = union(fr, _slab(face, f, a - w / 2 - fw * 1.4, a + w / 2 + fw * 1.4, 0.0, 0.055, z0 - fz * 1.3, z0 - fz * 0.2, 0.15))
    sc.add(fr, frame or T.trim)
    return hole


def door(sc, T, face, f, a, w=0.8, h=12.0, arch=True, frame=None, z0=0.0, leaf=None, fw=0.09):
    """Recessed plank door with a frame.  Returns the hole to subtract from the wall."""
    fz = fw * G / ZS
    hole = opening(face, f, a, z0, w, h, arch, -0.11, 0.3)
    sc.add(opening(face, f, a, z0, w, h, arch, -0.13, -0.075), leaf or T.door)
    fr = sub(opening(face, f, a, z0, w + 2 * fw, h + fz, arch, -0.02, 0.04), opening(face, f, a, z0 - 1, w, h + 1, arch, -0.3, 0.3))
    sc.add(fr, frame or T.trim)
    # iron ring handle / studs read as a dark dot at game size
    if face == "L":
        sc.add(sphere(a + w * 0.2, f - 0.07, z0 + h * 0.42, 0.028), T.iron, cast=False)
    else:
        sc.add(sphere(f - 0.07, a - w * 0.2, z0 + h * 0.42, 0.028), T.iron, cast=False)
    return hole


def merlon_ring(x0, y0, x1, y1, z, h=3.4, n=5, d=0.13, ny=None, sides="LRBF", mw=None):
    """Crenellations around the rectangle (x0, y0)-(x1, y1), standing on height z.  n merlons along x, ny along y.
    sides: L = left-front edge (y1), R = right-front edge (x1), B / F = the two back edges (y0 / x0)."""
    ny = ny or n
    parts = []

    def run(axis, lo, hi, cnt, other0, other1):
        w = mw or (hi - lo) / (cnt * 2 - 1) * 1.15
        step = (hi - lo - w) / (cnt - 1)
        if axis == "x":
            return repeat(box(lo, other0, lo + w, other1, z - 0.3, z + h, 0.18), "x", step, cnt)
        return repeat(box(other0, lo, other1, lo + w, z - 0.3, z + h, 0.18), "y", step, cnt)

    if "L" in sides:
        parts.append(run("x", x0, x1, n, y1 - d, y1))
    if "B" in sides:
        parts.append(run("x", x0, x1, n, y0, y0 + d))
    if "R" in sides:
        parts.append(run("y", y0, y1, ny, x1 - d, x1))
    if "F" in sides:
        parts.append(run("y", y0, y1, ny, x0, x0 + d))
    return union(*parts)


def flagpole(sc, T, x, y, z0, h, color=None, w=8.0, fh=6.0, finial=True, taper=0.8, phase=0.6):
    """Pole with a pennant flying to screen-right.  The pole runs from z0 to z0 + h."""
    sc.add(capsule((x, y, z0), (x, y, z0 + h), 0.42), T.pole)
    sc.add(flag(x, y, z0 + h - fh / 2.0 - 0.6, w, fh, taper=taper, phase=phase), r3.m_cloth(color or T.banner))
    if finial:
        sc.add(sphere(x, y, z0 + h + 0.6, 0.05), T.gold, cast=False)


def banner(sc, T, face, f, a, ztop, h, w=0.4, color=None):
    """Cloth banner hanging flat on a wall face, with a gold hem and a rod."""
    sc.add(_slab(face, f, a - w / 2, a + w / 2, 0.012, 0.035, ztop - h, ztop), r3.m_cloth(color or T.banner, trim="#F2C544", trim_h=h * 0.14))
    sc.add(_slab(face, f, a - w / 2 - 0.05, a + w / 2 + 0.05, 0.0, 0.06, ztop - 0.4, ztop + 0.9, 0.3), T.gold, cast=False)


def torch(sc, T, x, y, z, s=1.0):
    """Wall / post torch: a short stick and a flame that feeds the bloom.  (x, y, z) is the flame's centre -
    TerritoryLife.kt draws its animated flame on the same building coordinates, so keep them in sync."""
    sc.add(capsule((x, y, z - 4.4 * s), (x, y, z - 1.4 * s), 0.4), T.pole, cast=False)
    sc.add(ellipsoid(x, y, z, 0.05 * s, 0.05 * s, 1.9 * s), r3.m_glow("#FFA63A", power=4.5, bars=False), cast=False, ao=False)


def round_tower(sc, T, xc, yc, r, h, roof_h, z0=0.0, wall=None, roof=None, flag_h=0.0, win=True, crown=True, slit_deg=62.0,
                flag_w=7.0):
    """Round tower: body, flared head, lit slit windows, conical roof, optional finial and flag."""
    wall = wall or T.wall()
    cm = (xc, yc, r)
    hh = h - z0
    body = cyl(xc, yc, r, z0, h - 3.0)
    slits = []
    if win:
        for zf in ((0.62,) if hh < 34 else (0.36, 0.72)):
            zc = z0 + hh * zf
            cut = rotz(box(xc + r - 0.12, yc - 0.075, xc + r + 0.2, yc + 0.075, zc - 3.6, zc + 3.6), slit_deg, xc, yc)
            slits.append(cut)
            sc.add(rotz(box(xc + r - 0.13, yc - 0.075, xc + r - 0.07, yc + 0.075, zc - 3.6, zc + 3.6), slit_deg, xc, yc), T.slit,
                   cast=False, ao=False)
    sc.add(sub(body, *slits), wall, cyl=cm)
    if T.t >= 2 and hh > 20:      # string course
        sc.add(cyl(xc, yc, r + 0.035, z0 + hh * 0.5 - 0.9, z0 + hh * 0.5 + 0.9, 0.35), T.base if T.t < 4 else T.trim, cyl=cm, cast=False)
    # flared head with a corbel ring
    sc.add(union(cone(xc, yc, r, h - 6.5, h - 4.0, r + 0.09), cyl(xc, yc, r + 0.09, h - 4.0, h, 0.25)), wall, cyl=(xc, yc, r + 0.09))
    if T.t >= 4:
        sc.add(cyl(xc, yc, r + 0.11, h - 2.6, h - 1.3, 0.3), T.gold, cyl=cm, cast=False)
    roof = roof or T.roof
    sc.add(union(cone(xc, yc, r + 0.2, h, h + roof_h), cyl(xc, yc, r + 0.2, h - 0.9, h + 0.05, 0.2)), roof, cyl=(xc, yc, r + 0.2))
    top = h + roof_h
    if crown and T.t >= 4:
        sc.add(sphere(xc, yc, top + 0.3, 0.075), T.gold)
    if flag_h:
        flagpole(sc, T, xc, yc, top - 1.5, flag_h, w=flag_w, finial=T.t < 4)
    return top


# ---------------------------------------------------------------------------------------------
# castle
# ---------------------------------------------------------------------------------------------

def palisade(sc, T, x0, y0, x1, y1, h=10.0, r=0.085):
    """A row of pointed logs between two grid points (axis-aligned)."""
    along_x = abs(x1 - x0) > abs(y1 - y0)
    ln = abs(x1 - x0) if along_x else abs(y1 - y0)
    n = max(2, int(round(ln / (r * 2.0))) + 1)
    step = ln / (n - 1)
    xa, ya = min(x0, x1), min(y0, y1)
    log = union(cyl(xa, ya, r, -0.5, h - 2.4), cone(xa, ya, r, h - 2.4, h))
    sc.add(repeat(log, "x" if along_x else "y", step, n), T.curtain)
    # a lashing rail keeps the row from reading as a flat fence
    if along_x:
        sc.add(box(xa, ya + r * 0.7, xa + ln, ya + r * 1.25, h * 0.52, h * 0.52 + 1.3, 0.3), T.dark_wood, cast=False)
    else:
        sc.add(box(xa + r * 0.7, ya, xa + r * 1.25, ya + ln, h * 0.52, h * 0.52 + 1.3, 0.3), T.dark_wood, cast=False)


def castle(t):
    sc, T = building(t)
    towers = [0, 2, 4, 4, 4][t - 1]
    th = [0, 30, 38, 46, 56][t - 1]
    kh = [26, 34, 44, 52, 62][t - 1]
    cone_h = 10 + t * 2
    wall_h = 12.0

    # --- curtain wall -------------------------------------------------------------------------
    if t == 1:
        palisade(sc, T, 0.5, 0.5, 3.5, 0.5)
        palisade(sc, T, 0.5, 0.5, 0.5, 3.5)
        palisade(sc, T, 3.5, 0.5, 3.5, 3.5)
        palisade(sc, T, 0.5, 3.5, 1.5, 3.5)
        palisade(sc, T, 2.5, 3.5, 3.5, 3.5)
        for gx, tx in ((1.5, 1.53), (2.5, 2.47)):          # gate posts with torches
            sc.add(box(gx - 0.1, 3.4, gx + 0.1, 3.6, 0, 13.6, 0.3), T.trim)
            torch(sc, T, tx, 3.62, 15.5)
        sc.add(box(1.4, 3.42, 2.6, 3.58, 10.6, 12.4, 0.3), T.trim)
    else:
        cm = T.curtain
        gate = door(sc, T, "L", 3.74, 2.0, 0.68, 11.5, True, frame=T.base if t < 4 else T.trim, fw=0.07)
        ring = union(box(0.4, 0.4, 3.6, 0.8, 0, wall_h), box(0.4, 0.4, 0.8, 3.6, 0, wall_h),
                     box(0.4, 3.2, 3.6, 3.6, 0, wall_h), box(3.2, 0.4, 3.6, 3.6, 0, wall_h))
        house = box(1.38, 3.16, 2.62, 3.74, 0, 16.5)
        sc.add(sub(union(ring, house), gate), cm, name="curtain")
        sc.add(union(merlon_ring(0.4, 0.4, 3.6, 3.6, wall_h, 3.2, 9, 0.12, sides="LR"),
                     merlon_ring(0.4, 0.4, 3.6, 3.6, wall_h, 3.2, 9, 0.12, sides="BF")), cm)
        sc.add(merlon_ring(1.38, 3.16, 2.62, 3.74, 16.5, 3.0, 4, 0.12, ny=2, sides="LR"), cm)
        if t >= 4:   # gold string course along the two front walls
            sc.add(union(box(0.4, 3.6, 3.6, 3.625, wall_h - 2.4, wall_h - 1.2), box(3.6, 0.4, 3.625, 3.6, wall_h - 2.4, wall_h - 1.2)),
                   T.gold, cast=False)
        torch(sc, T, 1.56, 3.79, 11.5, 0.85)
        torch(sc, T, 2.44, 3.79, 11.5, 0.85)
        if t >= 5:      # ruby over the gate
            sc.add(sphere(2.0, 3.76, 14.4, 0.06), r3.m_glow("#FF3A58", power=0.9, bars=False), cast=False, ao=False)
            sc.add(torus(2.0, 3.76, 14.4, 0.06, 0.35), T.gold, cast=False)

    # --- corner towers (front one lower so the keep stays readable) ------------------------------
    if towers >= 2:
        round_tower(sc, T, 0.55, 0.55, 0.5, th + 4, cone_h, flag_h=0, win=False)
        round_tower(sc, T, 3.45, 3.45, 0.5, th * 0.74, cone_h * 0.9, flag_h=0 if towers >= 4 else 11)
    if towers >= 4:
        round_tower(sc, T, 3.45, 0.55, 0.5, th, cone_h, flag_h=12 if t < 4 else 14)
        round_tower(sc, T, 0.55, 3.45, 0.5, th, cone_h, flag_h=12 if t < 4 else 14)

    # --- keep -----------------------------------------------------------------------------------
    x0, y0, x1, y1 = 1.1, 1.1, 2.9, 2.9
    zb = 5.0
    wm = T.wall(zb, kh, x0, y0, bay=0.45)
    sc.add(box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, 0, zb, 0.4), T.base, name="keep base")
    hh = kh - zb
    rows = [zb + hh * 0.56] if hh < 36 else [zb + hh * 0.34, zb + hh * 0.74]
    wh = min(9.0, hh * 0.3)
    holes = []
    for zi, zz in enumerate(rows):
        for a in ((1.55, 2.45) if (zi == 0 or t >= 3) else (1.55, 2.0, 2.45)):
            holes.append(window(sc, T, "L", y1, a, zz, 0.36, wh))
            holes.append(window(sc, T, "R", x1, a, zz, 0.36, wh))
    if t == 1:
        holes.append(door(sc, T, "L", y1, 2.0, 0.6, 11.0, False, z0=0.0))
    if t == 1:
        sc.add(sub(box(x0, y0, x1, y1, zb - 1, kh), *holes), wm, name="keep")
        sc.add(gable(x0, y0, x1, y1, kh, 12, "x", over=0.24, thick=2.2), T.roof)
        # gable end infill and ridge pole
        sc.add(box(x0 - 0.3, 1.94, x1 + 0.3, 2.06, kh + 11.2, kh + 12.6, 0.5), T.trim, cast=False)
        flagpole(sc, T, 2.0, 2.0, kh + 11, 12, w=8.0)
        return sc
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, kh), *holes), wm, name="keep")
    if t >= 3:
        # corner pilasters, corbelled parapet with merlons, hipped roof rising inside it
        pil = union(*[box(px - 0.1, py - 0.1, px + 0.1, py + 0.1, zb, kh + 1.0, 0.3) for px, py in ((x0, y1), (x1, y1), (x1, y0))])
        sc.add(pil, T.base if t < 5 else T.wall(), name="pilasters")
        sc.add(box(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, kh - 3.2, kh + 0.8, 0.3), T.curtain if t < 5 else T.wall())
        if t >= 4:
            sc.add(box(x0 - 0.14, y0 - 0.14, x1 + 0.14, y1 + 0.14, kh - 2.2, kh - 0.9, 0.25), T.gold, cast=False)
        sc.add(merlon_ring(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, kh + 0.8, 3.2, 5, 0.13), T.curtain if t < 5 else T.wall())
        rh = 8 + t * 3
        sc.add(hip(x0 + 0.08, y0 + 0.08, x1 - 0.08, y1 - 0.08, kh + 1.6, rh, thick=1.0), T.roof)
        banner(sc, T, "L", y1, 2.0, kh - 6, kh * 0.30, 0.38)
        banner(sc, T, "R", x1, 2.0, kh - 6, kh * 0.30, 0.38)
        # central spire
        sz = kh + 1.6 + rh * 0.45
        sp = (9 + t * 2, 10 + t * 2, 14) if t < 5 else (11, 18, 11)      # tier 5 must still fit the viewport
        round_tower(sc, T, 2.0, 2.0, 0.36, sz + sp[0], sp[1], z0=sz - 4, win=False, flag_h=sp[2], flag_w=9.0)
        if t >= 5:   # four gilded turrets on the keep's corners
            for px, py in ((x0, y1), (x1, y1), (x1, y0), (x0, y0)):
                round_tower(sc, T, px, py, 0.2, kh + 9, 9, z0=kh - 6, win=False, crown=True)
    else:
        rh = 8 + t * 3
        sc.add(hip(x0, y0, x1, y1, kh, rh, over=0.22, thick=1.8), T.roof)
        flagpole(sc, T, 2.0, 2.0, kh + rh - 1.5, 13, w=8.0)
    return sc


# ---------------------------------------------------------------------------------------------
# farm
# ---------------------------------------------------------------------------------------------

SOIL = r3.m_ground("soil", "#6E4C2A")
STRAW = r3.m_thatch("#B8923F", row=2.2, seed=41)
SACK = r3.m_flat("#B49E72", 0.12, seed=42)
CRATE = r3.m_planks("#A87B45", 0.12, seed=43)


def crop_rows(sc, x0, y0, x1, y1, n, color, along="x", h=3.0, seed=0):
    """A ploughed bed with n leafy rows (real relief, so the rows catch light and shadow)."""
    sc.add(box(x0 - 0.14, y0 - 0.14, x1 + 0.14, y1 + 0.14, -0.4, 0.8, 0.5), SOIL, name="bed")
    if along == "x":
        step = (y1 - y0) / n
        w = step * 0.56
        rows = repeat(box(x0, y0 + (step - w) / 2, x1, y0 + (step + w) / 2, 0.2, h, 1.1), "y", step, n)
    else:
        step = (x1 - x0) / n
        w = step * 0.56
        rows = repeat(box(x0 + (step - w) / 2, y0, x0 + (step + w) / 2, y1, 0.2, h, 1.1), "x", step, n)
    sc.add(lumpy(rows, 0.75, 0.6, seed), r3.m_foliage(color, seed=50 + seed, warm=0.4), name="crops")


def fence(sc, T, pts, h=5.0):
    """Post-and-rail fence through axis-aligned grid points."""
    wood = r3.m_planks("#9A6E3E", 0.1, seed=44)
    parts = []
    for (xa, ya), (xb, yb) in zip(pts, pts[1:]):
        ln = math.hypot(xb - xa, yb - ya)
        n = max(2, int(round(ln / 0.46)) + 1)
        for k in range(n):
            f = k / (n - 1.0)
            px, py = xa + (xb - xa) * f, ya + (yb - ya) * f
            parts.append(box(px - 0.035, py - 0.035, px + 0.035, py + 0.035, -0.5, h, 0.25))
        for zz in (h * 0.42, h * 0.8):
            parts.append(capsule((xa, ya, zz), (xb, yb, zz), 0.42))
    sc.add(union(*parts), wood, name="fence")


def haybale(sc, x, y, ln=0.5, r_px=4.2):
    """Round straw bale lying on its side (axis along x), with a twine band."""
    zc = r_px / ZS - 0.2
    sc.add(cyl_x(x - ln / 2, x + ln / 2, y, zc, r_px, 0.5), STRAW, name="bale")
    sc.add(cyl_x(x - 0.04, x + 0.04, y, zc, r_px + 0.22, 0.1), r3.m_flat("#6E5220", 0.1), cast=False)


def windmill(sc, T, x, y, h):
    """Stone tower mill: tapered tower, conical cap, four cloth sails facing the left-front."""
    wall = T.wall()
    cm = (x, y, 0.4)
    zc = h * 0.5
    slit = rotz(box(x + 0.2, y - 0.07, x + 0.6, y + 0.07, zc - 3.0, zc + 3.0), 62, x, y)
    sc.add(sub(cone(x, y, 0.44, 0, h, 0.33), slit), wall, cyl=cm)
    sc.add(rotz(box(x + 0.28, y - 0.07, x + 0.335, y + 0.07, zc - 3.0, zc + 3.0), 62, x, y), T.slit, cast=False, ao=False)
    sc.add(union(cone(x, y, 0.46, h, h + 8.0), cyl(x, y, 0.46, h - 0.8, h + 0.05, 0.2)), T.roof, cyl=cm)
    hub = (x + 0.02, y + 0.47, h - 1.5)
    sc.add(capsule((x, y + 0.2, hub[2]), hub, 0.9), T.pole)
    ln, w = 13.2, 0.19
    spars, sails = [], []
    for k in range(4):
        spar = capsule((hub[0], hub[1], hub[2]), (hub[0], hub[1], hub[2] + ln), 0.36)
        sail = box(hub[0] + 0.012, hub[1] - 0.012, hub[0] + w, hub[1] + 0.006, hub[2] + 3.4, hub[2] + ln, 0.12)
        spars.append(r3.rot(spar, "y", 28 + 90 * k, hub))
        sails.append(r3.rot(sail, "y", 28 + 90 * k, hub))
    sc.add(union(*spars), T.pole, name="spars")
    sc.add(union(*sails), r3.m_cloth("#F1EAD6"), name="sails")
    sc.add(sphere(hub[0], hub[1] + 0.03, hub[2], 0.06), T.gold if T.t >= 4 else T.pole, cast=False)


def farm(t):
    sc, T = building(t)
    crop = ["#A8853C", "#8A9A38", "#64983A", "#549A42", "#B58E34"][t - 1]
    green = ["#55862F", "#55862F", "#478234", "#478234", "#6A9030"][t - 1]
    crop_rows(sc, 0.36, 2.26, 3.64, 3.72, 6, crop, "x", 3.2 if t in (1, 5) else 2.6, seed=1)
    crop_rows(sc, 2.36, 0.34, 3.72, 1.74, 5, green, "y", 2.4, seed=2)
    # barn
    x0, y0, x1, y1 = 0.4, 0.4, 2.0, 1.9
    h = 14.0 + t * 2
    zb = 3.5
    sc.add(box(x0 - 0.08, y0 - 0.08, x1 + 0.08, y1 + 0.08, 0, zb, 0.4), T.base, name="barn base")
    dh = min(h - 5.5, 10.5)
    holes = [door(sc, T, "L", y1, 1.25, 0.86, dh, T.stone, leaf=r3.m_planks("#8A5A30", 0.11, seed=45), fw=0.07),
             window(sc, T, "L", y1, 0.68, zb + (h - zb) * 0.6, 0.3, min(6.0, (h - zb) * 0.42)),
             window(sc, T, "R", x1, 1.15, zb + (h - zb) * 0.55, 0.36, min(6.5, (h - zb) * 0.44))]
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + (8 + t) * 0.9), *holes, _gable_cut(x0, y0, x1, y1, h, 8 + t, "x")),
           T.wall(zb, h, x0, y0, bay=0.4), name="barn")
    # door ledges
    sc.add(union(_slab("L", y1, 0.85, 1.65, -0.08, -0.05, dh * 0.22, dh * 0.22 + 1.1),
                 _slab("L", y1, 0.85, 1.65, -0.08, -0.05, dh * 0.68, dh * 0.68 + 1.1)), T.dark_wood, cast=False)
    sc.add(gable(x0, y0, x1, y1, h, 8 + t, "x", over=0.25, thick=2.2 if t == 1 else 1.5), T.roof, name="barn roof")
    if t >= 3:
        windmill(sc, T, 3.3, 0.9, 20.0 + t * 2)
    fence(sc, T, [(0.14, 3.9), (3.9, 3.9), (3.9, 2.1)])
    haybale(sc, 2.5, 1.98)
    if t >= 2:
        haybale(sc, 2.98, 2.0, 0.42, 3.6)
    for sx, sy in [(0.36, 2.1), (0.62, 2.14)][: 1 + (t >= 3)]:
        sc.add(ellipsoid(sx, sy, 2.2, 0.13, 0.11, 2.6), SACK)
    if t >= 4:
        sc.add(box(1.5, 1.96, 1.84, 2.2, 0, 4.6, 0.25), CRATE)
        flagpole(sc, T, 0.4, 0.4, h + 3, 13 + t, w=6.5, fh=5.0)
    return sc


def _gable_cut(x0, y0, x1, y1, z, h, axis):
    """Everything above a gable roof's slopes (subtract it from a wall box to get the gable ends)."""
    big = box(x0 - 1, y0 - 1, x1 + 1, y1 + 1, z, z + h * 2 + 20)
    return sub(big, gable(x0, y0, x1, y1, z, h, axis, over=0.0, thick=0.0))


# ---------------------------------------------------------------------------------------------
# barracks
# ---------------------------------------------------------------------------------------------

def shield(sc, T, face, f, a, z, r_px=2.6, color=None):
    """Round shield hung on a wall: gold rim, coloured field, gold boss."""
    rod = (lambda d0, d1, r: cyl_y(f + d0, f + d1, a, z, r)) if face == "L" else (lambda d0, d1, r: cyl_x(f + d0, f + d1, a, z, r))
    sc.add(rod(0.0, 0.035, r_px + 0.55), T.gold, cast=False)
    sc.add(rod(0.0, 0.055, r_px), r3.m_cloth(color or T.banner), cast=False)
    if face == "L":
        sc.add(sphere(a, f + 0.06, z, 0.045), T.gold, cast=False)
    else:
        sc.add(sphere(f + 0.06, a, z, 0.045), T.gold, cast=False)


def weapon_rack(sc, T, x, y, n, ln=1.2):
    """Spear rack: two posts, two rails and n leaning spears with steel heads."""
    wood = r3.m_planks("#8A6238", 0.1, seed=46)
    sc.add(union(capsule((x, y, 0), (x, y, 9.5), 0.6), capsule((x + ln, y, 0), (x + ln, y, 9.5), 0.6),
                 capsule((x, y, 8.0), (x + ln, y, 8.0), 0.5), capsule((x, y, 3.6), (x + ln, y, 3.6), 0.5)), wood, name="rack")
    shafts, heads = [], []
    for k in range(n):
        px = x + ln * (k + 0.5) / n
        shafts.append(capsule((px, y + 0.1, 0.3), (px, y - 0.04, 16.0), 0.3))
        heads.append(cone(px, y - 0.045, 0.05, 15.6, 19.6))
    sc.add(union(*shafts), T.pole, cast=False)
    sc.add(union(*heads), r3.m_metal("#C9D0D8"), cast=False)


def dummy(sc, T, x, y):
    """Training dummy: post, crossbar, straw body and head, red target."""
    sc.add(union(capsule((x, y, 0), (x, y, 11.0), 0.6), capsule((x - 0.2, y + 0.2, 8.4), (x + 0.2, y - 0.2, 8.4), 0.5)), T.pole)
    sc.add(union(ellipsoid(x, y, 7.2, 0.15, 0.15, 3.6), sphere(x, y, 13.0, 0.115)), r3.m_thatch("#B08C44", row=1.6, seed=47))
    sc.add(sphere(x + 0.1, y + 0.1, 7.4, 0.06), r3.m_flat("#C8382E"), cast=False)


def chest(sc, T, x, y, s=0.5, h=5.0):
    wood = r3.m_planks("#8A4A2A", 0.1, seed=48)
    d = s * 0.8
    lid = inter(cyl_x(x, x + s, y + d / 2, h * 0.62, d * G / 2), box(x, y, x + s, y + d, h * 0.62, h * 2))
    sc.add(union(box(x, y, x + s, y + d, 0, h * 0.62, 0.2), lid), wood)
    bands = [box(bx - 0.03, y - 0.01, bx + 0.03, y + d + 0.012, 0, h * 0.62 + d * G / 2 / ZS + 0.3, 0.2) for bx in (x + s * 0.2, x + s * 0.8)]
    sc.add(inter(union(*bands), union(box(x - 1, y - 1, x + s + 1, y + d + 1, 0, h * 0.62), rounded(lid, 0.3))), T.gold, cast=False)
    sc.add(sphere(x + s * 0.5, y + d + 0.01, h * 0.55, 0.04), T.gold, cast=False)


def barracks(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.6, 0.6, 3.4, 2.6
    h = 16.0 + t * 3
    zb = 4.0
    hh = h - zb
    sc.add(box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, 0, zb, 0.4), T.base, name="base")
    zw, wh = zb + hh * 0.54, min(9.0, hh * 0.46)
    dh = min(zb + hh * 0.55, 12.5)
    holes = [door(sc, T, "L", y1, 2.0, 0.8, dh, T.stone, fw=0.06)]
    for a in (1.2, 2.8):
        holes.append(window(sc, T, "L", y1, a, zw, 0.46, wh))
    for a in (1.15, 2.05):
        holes.append(window(sc, T, "R", x1, a, zw, 0.46, wh))
    wm = T.wall(zb, h, x0, y0, bay=0.4)
    if t < 3:
        rh = 8.0 + t
        sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + rh * 0.9), *holes, _gable_cut(x0, y0, x1, y1, h, rh, "x")), wm, name="hall")
        sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.25, thick=2.2 if t == 1 else 1.5), T.roof, name="roof")
        sc.add(box(x0 - 0.3, 1.55, x1 + 0.3, 1.65, h + rh - 0.9, h + rh + 0.5, 0.45), T.trim, cast=False)
        top = h + rh
    else:
        sc.add(sub(box(x0, y0, x1, y1, zb - 1, h), *holes), wm, name="hall")
        # corbelled parapet, merlons and a roof deck
        par = sub(box(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, h - 3.0, h + 2.6, 0.3),
                  box(x0 + 0.06, y0 + 0.06, x1 - 0.06, y1 - 0.06, h + 0.6, h + 9))
        sc.add(par, T.curtain if t < 5 else T.wall(), name="parapet")
        sc.add(merlon_ring(x0 - 0.12, y0 - 0.12, x1 + 0.12, y1 + 0.12, h + 2.6, 3.2, 7, 0.13, ny=5), T.curtain if t < 5 else T.wall())
        if t >= 4:
            sc.add(box(x0 - 0.14, y0 - 0.14, x1 + 0.14, y1 + 0.14, h - 2.0, h - 0.8, 0.25), T.gold, cast=False)
        pil = union(*[box(px - 0.09, py - 0.09, px + 0.09, py + 0.09, zb, h - 2.6, 0.3) for px, py in ((x0, y1), (x1, y1), (x1, y0))])
        sc.add(pil, T.base, name="pilasters")
        # a small roofed stair turret gives the flat roof a silhouette
        sc.add(box(0.9, 0.9, 1.7, 1.6, h + 0.6, h + 8.5), T.wall(), name="turret")
        sc.add(hip(0.9, 0.9, 1.7, 1.6, h + 8.5, 6.0 + t, over=0.12), T.roof)
        top = h + 2.6
    torch(sc, T, 1.52, y1 + 0.05, 13.5, 0.85)
    torch(sc, T, 2.48, y1 + 0.05, 13.5, 0.85)
    if t >= 2:
        banner(sc, T, "R", x1, 1.6, h - 3.4, hh * 0.40, 0.34)
    shield(sc, T, "L", y1, 2.0, dh + 4.4)
    flagpole(sc, T, 3.3, 0.7, top - (9 + t if t < 3 else 0), (9 + t if t < 3 else 0) + 14, w=8.0)
    flagpole(sc, T, 0.7, 2.5, h - (0 if t < 3 else -2.6) - 1, 12.5, w=7.0, fh=5.0)
    weapon_rack(sc, T, 0.5, 3.3, 2 + t // 2)
    dummy(sc, T, 3.3, 3.25)
    if t >= 4:
        chest(sc, T, 2.55, 3.0, 0.5, 5.0 + t * 0.4)
    return sc


# ---------------------------------------------------------------------------------------------
# decor: house, trees, wall pieces (grid (0, 0) is the sprite's ground anchor)
# ---------------------------------------------------------------------------------------------

def house_a():
    """Small cottage: field-stone base, timber-framed plaster walls, clay-tile roof, chimney, lit windows."""
    sc = Scene("decor")
    T = Tier(2)
    x0, y0, x1, y1 = -0.9, -0.7, 0.9, 0.6
    h, rh = 12.5, 7.5
    sc.add(box(x0 - 0.05, y0 - 0.05, x1 + 0.05, y1 + 0.05, 0, 3.0, 0.4), T.base)
    holes = [window(sc, T, "L", y1, -0.42, 7.8, 0.36, 5.4, fw=0.05), door(sc, T, "L", y1, 0.42, 0.44, 8.4, False, fw=0.05),
             window(sc, T, "R", x1, -0.05, 7.8, 0.42, 5.4, fw=0.05)]
    sc.add(sub(box(x0, y0, x1, y1, 2.0, h + rh), *holes, _gable_cut(x0, y0, x1, y1, h, rh, "x")),
           r3.m_plaster("#E9D9B0", "#6E4526", 3.0, h, 0.45, x0, y0, beam=1.3, mid=False, braces=False))
    sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.16, thick=1.4), r3.m_tiles("#B04A32", kind="clay", row=2.6, width=2.6))
    # chimney: TerritoryLife.kt starts the smoke at grid (0.47, -0.38), z = 23 (HOUSE_CHIMNEY)
    sc.add(box(0.31, -0.54, 0.63, -0.22, h, 21.6, 0.3), r3.m_rubble("#A39A8C", size=3.0))
    sc.add(box(0.28, -0.57, 0.66, -0.19, 21.3, 22.7, 0.3), r3.m_flat("#6E665C", 0.1))
    return sc


def tree_oak(size=1.0, leaf="#3A8230", trunk="#6B4A2B", seed=0):
    """Broadleaf tree: short tapered trunk and a canopy of leaf clumps (merged, slightly lumpy spheres)."""
    sc = Scene("decor")
    k = size
    bark = r3.m_planks(trunk, 0.05, seed=60, relief=0.5)
    sc.add(union(cone(0, 0, 0.12 * k, 0, 17 * k, 0.07 * k), cone(0, 0, 0.19 * k, -0.5, 3.0 * k, 0.1 * k)), bark, cyl=(0, 0, 0.12 * k), name="trunk")
    blobs = [(0.0, 0.0, 25.0, 0.46)]
    for i in range(5):                       # lower ring of clumps
        a = math.radians(20 + 72 * i + 13 * seed)
        blobs.append((0.36 * math.cos(a), 0.36 * math.sin(a), 21.0 + 2.2 * (i % 2), 0.32))
    for i in range(3):                       # upper ring
        a = math.radians(75 + 120 * i + 29 * seed)
        blobs.append((0.2 * math.cos(a), 0.2 * math.sin(a), 30.5 + 1.2 * (i % 2), 0.29))
    blobs.append((0.02, 0.02, 35.5, 0.26))
    canopy = smooth(1.7 * k, *[sphere(x * k, y * k, z * k, r * k) for x, y, z, r in blobs])
    sc.add(lumpy(canopy, 1.0 * k, 0.42 / k, seed), r3.m_foliage(leaf, seed=70 + seed), name="canopy")
    return sc


def tree_pine(size=1.0, leaf="#1F5434", trunk="#5A3F26", seed=0):
    """Conifer: three stacked, slightly ragged cones."""
    sc = Scene("decor")
    k = size
    sc.add(cone(0, 0, 0.1 * k, 0, 14 * k, 0.06 * k), r3.m_planks(trunk, 0.05, seed=61, relief=0.5), cyl=(0, 0, 0.1 * k))
    tiers = [(0.66, 7.0, 16.0), (0.52, 16.5, 14.5), (0.38, 25.5, 15.0)]
    for i, (r, z, h) in enumerate(tiers):
        skirt = union(cone(0, 0, r * k, z * k, (z + h) * k, 0.015 * k), cone(0, 0, r * k * 0.55, (z - 2.2) * k, z * k + 0.05, r * k))
        sc.add(lumpy(skirt, 1.2 * k, 0.5 / k, seed + i), r3.m_foliage(leaf, seed=80 + seed + i, warm=0.25), name="pine tier")
    return sc


def wall_piece(t, along="x"):
    """Territory wall segment, 2 grid units long.  along='x' runs right-down on screen, 'y' runs left-down.

    It tiles: TerritoryScene.kt puts a piece every 2 grid units.  So the ends are flat cuts, merlons and
    stone courses repeat every 2 units, there is no outline, and the piece is lit as part of an endless
    wall - its two neighbours are added as phantoms (visible=False) and the baked ground shadow is clipped
    to the piece's own 2-unit cell, so the shadows of adjacent sprites meet without overlapping."""
    cell = (-1.0, -50.0, 1.0, 50.0) if along == "x" else (-50.0, -1.0, 50.0, 1.0)
    sc = Scene("wall", clip=cell, outline=0.0)
    T = Tier(t)
    wh = 12.0
    course = 4.0
    mat = r3.m_stone("#B3AB98", course=course, length=(2.0 * G / 4.0) / (course * ZS)) if t == 3 else T.curtain
    if along == "x":
        body = box(-1.0, -0.5, 1.0, 0.5, 0, wh)
        mer = repeat(box(-1.0 + 1 / 3.0 - 0.19, 0.27, -1.0 + 1 / 3.0 + 0.19, 0.5, wh - 0.3, wh + 3.4, 0.18), "x", 2 / 3.0, 3)
    else:
        body = box(-0.5, -1.0, 0.5, 1.0, 0, wh)
        mer = repeat(box(0.27, -1.0 + 1 / 3.0 - 0.19, 0.5, -1.0 + 1 / 3.0 + 0.19, wh - 0.3, wh + 3.4, 0.18), "y", 2 / 3.0, 3)
    sc.add(body, mat, name="wall")
    sc.add(mer, mat, name="merlons")
    if t >= 5:
        sc.add(box(-1.0, 0.5, 1.0, 0.52, wh - 2.2, wh - 1.0) if along == "x" else box(0.5, -1.0, 0.52, 1.0, wh - 2.2, wh - 1.0), T.gold, cast=False)
    for k in (-2.0, 2.0):        # the neighbouring pieces: shadow and occlusion only
        sc.add(move(union(body, mer), k if along == "x" else 0.0, 0.0 if along == "x" else k), visible=False)
    return sc


# ---------------------------------------------------------------------------------------------
# registry and CLI
# ---------------------------------------------------------------------------------------------

SPRITES = {}
for _t in range(1, 6):
    SPRITES["bld_castle_t%d" % _t] = (lambda t=_t: castle(t))
for _t in (1, 3, 5):
    SPRITES["bld_farm_t%d" % _t] = (lambda t=_t: farm(t))
for _t in (1, 3, 5):
    SPRITES["bld_barracks_t%d" % _t] = (lambda t=_t: barracks(t))
SPRITES["deco_house_a"] = house_a
SPRITES["deco_tree_oak_a"] = tree_oak
SPRITES["deco_tree_pine_a"] = tree_pine
SPRITES["deco_wall_x_t3"] = lambda: wall_piece(3, "x")
SPRITES["deco_wall_y_t3"] = lambda: wall_piece(3, "y")


def discover():
    """Merge the extra model files next to this one: tools/artgen/sprites3d_*.py, each with a module-level
    SPRITES = {resource name: function returning a Scene}.  (Lets several people add sprites in parallel.)"""
    for mod in sorted(pathlib.Path(__file__).parent.glob("sprites3d_*.py")):
        SPRITES.update(__import__(mod.stem).SPRITES)
    return SPRITES


def render_one(args):
    name, png_dir = args
    if name not in SPRITES:
        discover()
    t0 = time.time()
    img = r3.render(SPRITES[name]())
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / (name + ".webp")
    r3.save_webp(img, path)
    if png_dir:
        pathlib.Path(png_dir).mkdir(parents=True, exist_ok=True)
        img.save(pathlib.Path(png_dir) / (name + ".png"))
    box_ = img.getchannel("A").point(lambda v: 255 if v > 200 else 0).getbbox()      # solid pixels only
    margin = min(box_[0], box_[1], img.width - box_[2], img.height - box_[3]) / 4.0
    return name, time.time() - t0, path.stat().st_size, margin


def main(argv):
    png_dir, sheet, words, jobs = None, None, [], min(4, multiprocessing.cpu_count())
    it = iter(argv)
    for a in it:
        if a == "--png":
            png_dir = next(it)
        elif a == "--sheet":
            sheet = next(it)
        elif a == "--jobs":
            jobs = int(next(it))
        elif a == "--list":
            print("\n".join(discover()))
            return 0
        elif a == "--selftest":
            return 0 if r3.selftest() else 1
        else:
            words.append(a)
    discover()
    names = [n for n in SPRITES if not words or any(n.startswith(w) or n.split("_", 1)[1].startswith(w) for w in words)]
    t0 = time.time()
    work = [(n, png_dir) for n in names]
    if jobs > 1 and len(work) > 1:
        with multiprocessing.Pool(jobs) as pool:
            res = pool.map(render_one, work, chunksize=1)
    else:
        res = [render_one(w) for w in work]
    for name, dt, size, margin in res:
        print("%-24s %5.1f s  %6.1f KB  margin %4.1f%s" % (name, dt, size / 1024.0, margin, "  <-- CLIPPED?" if margin < 1.0 else ""))
    print("%d sprites in %.1f s -> %s" % (len(res), time.time() - t0, OUT))
    if sheet:
        import sheet3d
        sheet3d.make(sheet, names, OUT, OLD)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
