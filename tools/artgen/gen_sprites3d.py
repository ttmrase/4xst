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
            self.roof = r3.m_thatch("#D9B552")
            self.trim = r3.m_planks("#6B4426", 0.2, seed=23)
            self.pad = r3.m_ground("dirt", "#94794A")
            self.curtain = r3.m_planks("#8A5F36", 0.14, seed=24, relief=0.5)
        elif t == 2:
            self.base = r3.m_rubble("#A59D90")
            self.roof = r3.m_tiles("#C9553A", kind="clay")
            self.trim = r3.m_planks("#74492A", 0.2, seed=23)
            self.pad = r3.m_ground("dirt", "#A18D62")
            self.curtain = r3.m_rubble("#A39B8E", size=4.6)
        elif t == 3:
            self.base = r3.m_stone("#958E82", course=4.2)
            self.roof = r3.m_tiles("#4A6699", kind="slate", width=3.4)
            self.trim = r3.m_stone("#8F887B", course=3.0, length=1.6, seed=31)
            self.pad = r3.m_ground("paved", "#B9B3A2")
            self.curtain = r3.m_stone("#ADA799", course=4.2)
        elif t == 4:
            self.base = r3.m_stone("#AFA89A", course=4.6)
            self.roof = r3.m_tiles("#2F60C4", kind="scale", width=3.2, spec=0.25)
            self.trim = self.gold
            self.pad = r3.m_ground("paved", "#C0BCAE")
            self.curtain = r3.m_stone("#DDD6C6", course=5.4, joint=0.32, var=0.07)
        else:
            self.base = r3.m_marble("#DDD4BF", veins=0.2)
            self.roof = r3.m_tiles("#F0B830", kind="scale", width=3.2, spec=0.9, metal=0.35, var=0.08)
            self.trim = self.gold
            self.pad = r3.m_ground("marble", "#EDE6D4")
            self.curtain = r3.m_marble("#F1EBDD")

    def wall(self, z0=4.0, z1=20.0, ox=0.0, oy=0.0, bay=0.45):
        """Wall material of the tier.  Timber framing (tier 2) needs the wall's extent to place its beams."""
        t = self.t
        if t == 1:
            return r3.m_planks("#AD6E3E", 0.26)
        if t == 2:
            return r3.m_plaster("#EDDDB4", "#6E4526", z0, z1, bay, ox, oy)
        if t == 3:
            return r3.m_stone("#CFC5AE")
        if t == 4:
            return r3.m_stone("#EAE4D6", course=5.6, joint=0.3, var=0.06, mortar="#9C968A")
        return r3.m_marble("#FAF5EA")

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
    """Wall / post torch: a short stick and a flame that feeds the bloom."""
    sc.add(capsule((x, y, z - 3.0 * s), (x, y, z), 0.4), T.pole, cast=False)
    sc.add(ellipsoid(x, y, z + 1.5 * s, 0.05 * s, 0.05 * s, 1.9 * s), r3.m_glow("#FFA63A", power=4.5, bars=False), cast=False, ao=False)


def round_tower(sc, T, xc, yc, r, h, roof_h, z0=0.0, wall=None, roof=None, flag_h=0.0, win=True, crown=True, slit_deg=62.0,
                flag_w=7.0):
    """Round tower: body, flared head, lit slit windows, conical roof, optional finial and flag."""
    wall = wall or T.wall()
    cm = (xc, yc, r)
    hh = h - z0
    body = cyl(xc, yc, r, z0, h - 3.0)
    slits = []
    if win:
        for k, zf in enumerate((0.62,) if hh < 34 else (0.36, 0.72)):
            zc = z0 + hh * zf
            cut = rotz(box(xc + r - 0.12, yc - 0.075, xc + r + 0.2, yc + 0.075, zc - 3.6, zc + 3.6), slit_deg, xc, yc)
            slits.append(cut)
            sc.add(rotz(box(xc + r - 0.13, yc - 0.075, xc + r - 0.07, yc + 0.075, zc - 3.6, zc + 3.6), slit_deg, xc, yc), T.slit, cast=False, ao=False)
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
        for gx in (1.5, 2.5):          # gate posts with torches
            sc.add(box(gx - 0.1, 3.4, gx + 0.1, 3.6, 0, 14.5, 0.3), T.trim)
            torch(sc, T, gx, 3.5, 17.5)
        sc.add(box(1.4, 3.42, 2.6, 3.58, 12.2, 14.0, 0.3), T.trim)
    else:
        cm = T.curtain
        gate = door(sc, T, "L", 3.74, 2.0, 0.74, 11.5, True, frame=T.base if t < 4 else T.trim)
        ring = union(box(0.4, 0.4, 3.6, 0.8, 0, wall_h), box(0.4, 0.4, 0.8, 3.6, 0, wall_h),
                     box(0.4, 3.2, 3.6, 3.6, 0, wall_h), box(3.2, 0.4, 3.6, 3.6, 0, wall_h))
        house = box(1.38, 3.16, 2.62, 3.74, 0, 16.5)
        sc.add(sub(union(ring, house), gate), cm, name="curtain")
        sc.add(union(merlon_ring(0.4, 0.4, 3.6, 3.6, wall_h, 3.2, 9, 0.12, sides="LR"),
                     merlon_ring(0.4, 0.4, 3.6, 3.6, wall_h, 3.2, 9, 0.12, sides="BF")), cm)
        sc.add(merlon_ring(1.38, 3.16, 2.62, 3.74, 16.5, 3.0, 4, 0.12, ny=2, sides="LR"), cm)
        if t >= 4:   # gold string course along the two front walls
            sc.add(union(box(0.4, 3.6, 3.6, 3.625, wall_h - 2.4, wall_h - 1.2), box(3.6, 0.4, 3.625, 3.6, wall_h - 2.4, wall_h - 1.2)), T.gold, cast=False)
        if t >= 3:
            torch(sc, T, 1.5, 3.8, 12.5, 0.9)
            torch(sc, T, 2.5, 3.8, 12.5, 0.9)
        if t >= 5:
            sc.add(sphere(2.0, 3.76, 14.6, 0.07), r3.m_glow("#FF4A6A", power=1.6, bars=False), cast=False)

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
        top = round_tower(sc, T, 2.0, 2.0, 0.36, sz + 9 + t * 2, 10 + t * 2, z0=sz - 4, win=False, flag_h=14, flag_w=9.0)
        if t >= 5:   # four gilded turrets on the keep's corners
            for px, py in ((x0, y1), (x1, y1), (x1, y0), (x0, y0)):
                round_tower(sc, T, px, py, 0.2, kh + 9, 9, z0=kh - 6, win=False, crown=True)
    else:
        rh = 8 + t * 3
        sc.add(hip(x0, y0, x1, y1, kh, rh, over=0.22, thick=1.8), T.roof)
        flagpole(sc, T, 2.0, 2.0, kh + rh - 1.5, 13, w=8.0)
    return sc


# ---------------------------------------------------------------------------------------------
# registry and CLI
# ---------------------------------------------------------------------------------------------

SPRITES = {}
for _t in range(1, 6):
    SPRITES["bld_castle_t%d" % _t] = (lambda t=_t: castle(t))


def render_one(args):
    name, png_dir = args
    t0 = time.time()
    img = r3.render(SPRITES[name]())
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / (name + ".webp")
    r3.save_webp(img, path)
    if png_dir:
        pathlib.Path(png_dir).mkdir(parents=True, exist_ok=True)
        img.save(pathlib.Path(png_dir) / (name + ".png"))
    return name, time.time() - t0, path.stat().st_size


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
            print("\n".join(SPRITES))
            return 0
        elif a == "--selftest":
            return 0 if r3.selftest() else 1
        else:
            words.append(a)
    names = [n for n in SPRITES if not words or any(n.startswith(w) or n.split("_", 1)[1].startswith(w) for w in words)]
    t0 = time.time()
    work = [(n, png_dir) for n in names]
    if jobs > 1 and len(work) > 1:
        with multiprocessing.Pool(jobs) as pool:
            res = pool.map(render_one, work, chunksize=1)
    else:
        res = [render_one(w) for w in work]
    for name, dt, size in res:
        print("%-24s %5.1f s  %6.1f KB" % (name, dt, size / 1024.0))
    print("%d sprites in %.1f s -> %s" % (len(res), time.time() - t0, OUT))
    if sheet:
        import sheet3d
        sheet3d.make(sheet, names, OUT, OLD)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
