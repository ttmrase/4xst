#!/usr/bin/env python3
"""sprites3d_economy.py - 3D models of the economy buildings (phase 2, group A).

farm (tiers 2 and 4 of the phase-1 model), sawmill, quarry, gold mine, warehouse, trading post, builders' hut,
tavern and academy, five tiers each.  Picked up automatically by gen_sprites3d.py (module-level SPRITES).

Units and conventions are those of gen_sprites3d.py / README_3D.md: x, y in grid units on the 4 x 4 plot,
z in viewport units, the camera sees the +y face ("L", lit) and the +x face ("R", in shade).
"""

import math

import numpy as np

import gen_sprites3d as g
import render3d as r3
from gen_sprites3d import (Tier, _gable_cut, _slab, banner, building, chest, door, flagpole, merlon_ring,  # noqa: F401
                           opening, round_tower, torch, window)
from render3d import (G, ZS, box, capsule, cone, cyl, cyl_x, cyl_y, ellipsoid, flag, gable, hip, inter,  # noqa: F401
                      lumpy, move, repeat, rotz, rounded, smooth, sphere, sub, torus, union)

F = np.float32


# ---------------------------------------------------------------------------------------------
# materials of this file
# ---------------------------------------------------------------------------------------------

def m_rock(color="#928E84", seed=0, strata=4.6):
    """Quarried / natural rock: warped horizontal strata with joints and cracks; plain noise on flat tops."""
    base = r3.srgb(color)
    sh = strata * ZS

    def lay(c):
        warp = r3.fbm2(c.u * 0.06, c.v * 0.05, 3, seed) * 2.2
        vv = c.v / sh + warp
        k = np.floor(vv)
        f = vv - k
        return k.astype(np.int32), np.minimum(f, 1.0 - f) * sh

    def albedo(c):
        k, e = lay(c)
        rnd = r3.hash2(k, k * 0 + 5, seed)
        n = r3.fbm2(c.u * 0.33, c.v * 0.33, 3, seed + 2)
        a = r3._vary(base, rnd, 0.13, 0.05) * (0.80 + 0.40 * n)[..., None]
        uo = c.u / (sh * 2.6) + rnd * 7.0
        fu = uo - np.floor(uo)
        cr = np.minimum(fu, 1.0 - fu) * sh * 2.6
        line = np.minimum(e, cr + 0.15)
        a = r3.mixc(a, base * 0.38, (1.0 - r3.sstep(0.12, 0.5, line)) * 0.7)
        flat = base[None, :] * (0.84 + 0.36 * n)[..., None]
        return np.where(c.top[..., None], flat, a)

    def height(c):
        _, e = lay(c)
        return np.where(c.top, 0.0, r3.sstep(0.1, 0.9, e) * 0.5) + r3.vnoise2(c.u * 0.5, c.v * 0.5, seed + 7) * 0.4

    return r3.Material(albedo, height, spec=0.04, gloss=14.0)


def m_stripes(c1, c2, w, axis="x", origin=0.0):
    """Awning cloth with stripes w grid units wide, counted along world x (or y) from `origin`."""
    a, b = r3.srgb(c1), r3.srgb(c2)
    ww = w * G

    def albedo(c):
        p = c.x if axis == "x" else c.y
        k = np.floor((p - origin * G) / ww)
        fold = 0.95 + 0.05 * np.sin(c.u * 0.9 + c.v * 0.5) + 0.05 * r3.vnoise2(c.u * 1.5, c.v * 1.5, 11)
        col = np.where((np.mod(k, 2.0) == 0.0)[..., None], a[None, :], b[None, :])
        return col * fold[..., None]

    return r3.Material(albedo, wrap=0.3, spec=0.04)


def m_logends(centres, r, axis="y", wood="#DDB57A", bark="#6B4424"):
    """Cut ends of logs: pale wood with growth rings and a bark rim.  centres = [(a, z)] in model units
    (a = grid x for logs lying along y, grid y for logs along x), r = log radius in viewport units."""
    wc, bc = r3.srgb(wood), r3.srgb(bark)
    cs = [(a * G, z * ZS) for a, z in centres]

    def albedo(c):
        p = c.x if axis == "y" else c.y
        d = np.full(c.x.shape, 99.0, F)
        for a, z in cs:
            d = np.minimum(d, np.hypot(p - F(a), c.z - F(z)))
        ring = 0.86 + 0.14 * np.cos(d * (2.0 * math.pi / max(r * 0.42, 0.6)))
        col = wc[None, :] * ring[..., None]
        col = r3.mixc(col, wc * 0.62, 1.0 - r3.sstep(0.0, r * 0.22, d))           # darker heart
        return r3.mixc(col, bc, r3.sstep(r * 0.78, r * 0.9, d))

    return r3.Material(albedo, spec=0.03)


AMBER = r3.m_gold("#E8A52A")                         # warm gold for ore, coins, ingots
STEEL = r3.m_metal("#C4CBD4")
DARK = r3.m_flat("#16100E", 0.05)                    # the inside of doorways and tunnels
BARK = r3.m_flat("#7A5230", 0.2, scale=0.9, seed=31)
WET_WOOD = r3.m_planks("#7C5630", 0.12, seed=32, var=0.14)
PALE_WOOD = r3.m_planks("#D2A869", 0.16, seed=33, var=0.12)
BOARD = r3.m_planks("#C99C5E", 0.13, horizontal=True, seed=34, var=0.14, relief=0.5)
CRATE = r3.m_planks("#B8894E", 0.11, seed=35)
CRATE_FRAME = r3.m_flat("#7A5530", 0.12, seed=36)
BARREL = r3.m_planks("#94683C", 0.075, seed=37, var=0.18)
HOOP = r3.m_metal("#4E4A4C")
SACK = r3.m_flat("#B8A274", 0.14, seed=38)
ROPE = r3.m_flat("#3C3026", 0.1)
SCAFF = r3.m_planks("#A87A44", 0.1, seed=39)


# ---------------------------------------------------------------------------------------------
# props
# ---------------------------------------------------------------------------------------------

def house(sc, T, x0, y0, x1, y1, h, rh, axis="x", holes=(), zb=3.5, over=0.2, wall=None, roof=None, thick=None,
          ridge=True, bay=0.4, name="house"):
    """Gabled house: base course, walls with the given holes cut out, gable ends, roof and ridge beam."""
    if zb > 0:
        sc.add(box(x0 - 0.07, y0 - 0.07, x1 + 0.07, y1 + 0.07, 0, zb, 0.4), T.base, name=name + " base")
    wm = wall or T.wall(zb, h, x0, y0, bay=bay)
    sc.add(sub(box(x0, y0, x1, y1, max(zb - 1.0, 0.0), h + rh), *holes, _gable_cut(x0, y0, x1, y1, h, rh, axis)), wm, name=name)
    sc.add(gable(x0, y0, x1, y1, h, rh, axis, over=over, thick=thick or (2.0 if T.t == 1 else 1.4)), roof or T.roof,
           name=name + " roof")
    if ridge:
        if axis == "x":
            yc = (y0 + y1) / 2.0
            rb = box(x0 - over - 0.04, yc - 0.045, x1 + over + 0.04, yc + 0.045, h + rh - 0.8, h + rh + 0.5, 0.4)
        else:
            xc = (x0 + x1) / 2.0
            rb = box(xc - 0.045, y0 - over - 0.04, xc + 0.045, y1 + over + 0.04, h + rh - 0.8, h + rh + 0.5, 0.4)
        sc.add(rb, T.trim, cast=False)


def crate(sc, x, y, s=0.5, h=None, z0=0.0, deg=0.0, mat=None):
    """Wooden crate: plank panels inside a darker edge frame.  (x, y) = back corner, s = side in grid units."""
    h = h or s * G / ZS * 0.82
    e, ez, o = s * 0.14, s * 0.14 * G / ZS, 0.012
    body = box(x, y, x + s, y + s, z0, z0 + h, 0.1)
    frame = sub(box(x - o, y - o, x + s + o, y + s + o, z0, z0 + h + 0.25, 0.15),
                box(x + e, y - 1, x + s - e, y + s + 1, z0 + ez, z0 + h - ez),
                box(x - 1, y + e, x + s + 1, y + s - e, z0 + ez, z0 + h - ez),
                box(x + e, y + e, x + s - e, y + s - e, z0 + ez, z0 + h + 5))
    if deg:
        body, frame = rotz(body, deg, x + s / 2, y + s / 2), rotz(frame, deg, x + s / 2, y + s / 2)
    sc.add(body, mat or CRATE, name="crate")
    sc.add(frame, CRATE_FRAME, name="crate frame")
    return z0 + h


def barrel(sc, x, y, r=0.22, h=7.5, z0=0.0, wood=None, hoop=None):
    """Upright barrel: bulging staves and two iron hoops."""
    body = inter(ellipsoid(x, y, z0 + h / 2.0, r, r, h * 0.8), box(x - r, y - r, x + r, y + r, z0, z0 + h))
    sc.add(body, wood or BARREL, cyl=(x, y, r), name="barrel")
    hoops = union(torus(x, y, z0 + h * 0.22, r * 0.93, 0.5), torus(x, y, z0 + h * 0.78, r * 0.93, 0.5))
    sc.add(hoops, hoop or HOOP, cast=False)
    sc.add(cyl(x, y, r * 0.66, z0 + h - 0.3, z0 + h + 0.12), r3.m_flat("#6E4C2A", 0.12), cast=False)   # sunk lid


def barrel_lying(sc, x, y0, y1, r=3.6, z0=0.0, wood=None):
    """Barrel on its side, its round end facing the left-front (axis along y), on two chocks."""
    zc = z0 + r / ZS + 0.6
    body = inter(cyl_y(y0, y1, x, zc, r), ellipsoid(x, (y0 + y1) / 2.0, zc, r * 1.0 / G * 1.02, (y1 - y0) * 0.8, r * 1.02 / ZS))
    sc.add(body, wood or BARREL, name="barrel")
    sc.add(union(*[cyl_y(yy - 0.025, yy + 0.025, x, zc, r * (0.95 if k in (0, 3) else 1.03))
                   for k, yy in enumerate((y0 + 0.04, y0 + (y1 - y0) * 0.3, y0 + (y1 - y0) * 0.7, y1 - 0.04))]), HOOP, cast=False)
    sc.add(cyl_y(y1 - 0.02, y1 + 0.012, x, zc, r * 0.72), r3.m_flat("#B08A54", 0.12), cast=False)
    sc.add(union(box(x - r / G, y0 + 0.05, x + r / G, y0 + 0.14, z0, z0 + 1.6), box(x - r / G, y1 - 0.14, x + r / G, y1 - 0.05, z0, z0 + 1.6)),
           CRATE_FRAME)
    return zc


def sack(sc, x, y, s=1.0, z0=0.0, mat=None):
    sc.add(union(ellipsoid(x, y, z0 + 2.3 * s, 0.15 * s, 0.13 * s, 2.7 * s), sphere(x, y, z0 + 5.0 * s, 0.045 * s)), mat or SACK, name="sack")


def log_pile(sc, x, y, n, ln=1.3, r=3.0, axis="y", levels=None):
    """Pyramid of logs lying along `axis`, pale cut ends towards the camera.  (x, y) = the back corner of the
    pile's footprint, n = logs in the bottom row, r = log radius in viewport units."""
    step = 2.0 * r / G
    pos = []
    for lvl in range(levels or n):
        for i in range(n - lvl):
            pos.append(((i + 0.5 + lvl * 0.5) * step, (r + lvl * r * 1.74) / ZS + 0.1))
    logs, ends, cs = [], [], []
    for k, (a, zc) in enumerate(pos):
        e = ln + ((k * 37) % 7 - 3) * 0.014
        if axis == "y":
            logs.append(cyl_y(y, y + e, x + a, zc, r, 0.3))
            ends.append(cyl_y(y + e - 0.02, y + e + 0.012, x + a, zc, r * 0.9))
            cs.append((x + a, zc))
        else:
            logs.append(cyl_x(x, x + e, y + a, zc, r, 0.3))
            ends.append(cyl_x(x + e - 0.02, x + e + 0.012, y + a, zc, r * 0.9))
            cs.append((y + a, zc))
    sc.add(union(*logs), BARK, name="logs")
    sc.add(union(*ends), m_logends(cs, r * 0.9, axis), cast=False, name="log ends")
    # wedges that keep the bottom row from rolling
    if axis == "y":
        sc.add(union(box(x - 0.07, y + 0.15, x + 0.02, y + 0.3, 0, 2.4, 0.2), box(x + n * step - 0.02, y + 0.15, x + n * step + 0.07, y + 0.3, 0, 2.4, 0.2)),
               CRATE_FRAME)


class _Saw(r3.Node):
    """Circular saw blade: a toothed disc in the x-z plane (axle along y).  World units."""
    relax = 0.6

    def __init__(self, cx, cy, cz, r, th, teeth, depth):
        self.cx, self.cy, self.cz, self.r, self.th, self.n, self.dp = cx, cy, cz, r, th, teeth, depth
        self.lo, self.hi = np.array([cx - r, cy - th, cz - r]), np.array([cx + r, cy + th, cz + r])

    def d(self, x, y, z):
        dx, dz = x - self.cx, z - self.cz
        rr = np.hypot(dx, dz)
        f = np.mod(np.arctan2(dz, dx) * (self.n / (2.0 * math.pi)), 1.0)
        return np.maximum(rr - (self.r - self.dp * f), np.abs(y - self.cy) - self.th)


def saw_blade(sc, x, y, z, r=7.4, hub=None, teeth=14):
    """Big circular saw blade facing the left-front; (x, y, z) = its axle."""
    sc.add(_Saw(x * G, y * G, z * ZS, r, 0.28, teeth, r * 0.2), STEEL, name="saw blade")
    sc.add(cyl_y(y - 0.035, y + 0.035, x, z, r * 0.26, 0.15), hub or HOOP, cast=False)


def water_wheel(sc, T, xc, y0, wd, R, wood=None, hub=None, n=10):
    """Undershot water wheel in the x-z plane (axle along y) between y0 and y0 + wd; R = radius in viewport
    units.  Returns the axle height."""
    zc = R / ZS + 0.9
    wood = wood or WET_WOOD
    piv = (xc, y0, zc)
    rw = 0.065

    def ring(ya, yb, ra, rb):
        return sub(cyl_y(ya, yb, xc, zc, ra), cyl_y(ya - 0.1, yb + 0.1, xc, zc, rb))

    sc.add(union(ring(y0, y0 + rw, R, R - 1.7), ring(y0 + wd - rw, y0 + wd, R, R - 1.7), ring(y0 + 0.02, y0 + wd - 0.02, R - 2.4, R - 3.0)),
           wood, name="wheel rims")
    pad = [r3.rot(box(xc - 0.03, y0 + 0.012, xc + 0.03, y0 + wd - 0.012, zc + (R - 2.8) / ZS, zc + (R + 0.7) / ZS), "y", 360.0 * k / n + 7.0, piv)
           for k in range(n)]
    sc.add(union(*pad), r3.m_planks("#9A6E3E", 0.2, seed=40), name="paddles")
    yf = y0 + wd - rw / 2.0
    spokes = [r3.rot(capsule((xc, yf, zc - (R - 1.0) / ZS), (xc, yf, zc + (R - 1.0) / ZS), 0.6), "y", 45.0 * k + 7.0, piv) for k in range(4)]
    sc.add(union(*spokes), wood, name="spokes")
    sc.add(cyl_y(y0 - 0.08, y0 + wd + 0.06, xc, zc, 1.5, 0.2), hub or T.iron, name="axle")
    return zc


def cart_wheel(sc, axis, a, b, zc, R, th=0.05, mat=None, hub=None, spokes=4):
    """Spoked wheel.  axis 'y': in the x-z plane, centre x = a, between y = b and b + th (faces the left-front);
    axis 'x': in the y-z plane, centre y = a, between x = b and b + th (faces the right-front)."""
    mat = mat or CRATE_FRAME
    if axis == "y":
        rim = sub(cyl_y(b, b + th, a, zc, R), cyl_y(b - 0.1, b + th + 0.1, a, zc, R - 1.1))
        piv = (a, b, zc)
        sp = [r3.rot(capsule((a, b + th / 2, zc - (R - 0.6) / ZS), (a, b + th / 2, zc + (R - 0.6) / ZS), 0.42), "y", 180.0 * k / spokes + 20, piv)
              for k in range(spokes)]
        hb = cyl_y(b - 0.015, b + th + 0.03, a, zc, 1.0, 0.2)
    else:
        rim = sub(cyl_x(b, b + th, a, zc, R), cyl_x(b - 0.1, b + th + 0.1, a, zc, R - 1.1))
        piv = (b, a, zc)
        sp = [r3.rot(capsule((b + th / 2, a, zc - (R - 0.6) / ZS), (b + th / 2, a, zc + (R - 0.6) / ZS), 0.42), "x", 180.0 * k / spokes + 20, piv)
              for k in range(spokes)]
        hb = cyl_x(b - 0.015, b + th + 0.03, a, zc, 1.0, 0.2)
    sc.add(union(rim, *sp), mat, name="wheel")
    sc.add(hb, hub or HOOP, cast=False)


def plank_stack(sc, x0, y0, x1, y1, layers, lh=1.9):
    """Stack of sawn boards (each layer a little shorter, with a sticker gap)."""
    parts = []
    for k in range(layers):
        d = 0.03 * k
        parts.append(box(x0 + d, y0 + d * 0.6, x1 - d * (1 + (k % 2)), y1 - d * 0.6, k * lh + 0.25 * (k > 0), (k + 1) * lh, 0.2))
    sc.add(union(*parts), BOARD, name="planks")


def ladder(sc, p0, p1, w=0.34, along="y", rungs=5, mat=None):
    """Ladder from p0 (foot) to p1 (top); the two rails are w apart along grid axis `along`."""
    dx, dy = (0.0, w) if along == "y" else (w, 0.0)
    a0, a1 = p0, p1
    b0, b1 = (p0[0] + dx, p0[1] + dy, p0[2]), (p1[0] + dx, p1[1] + dy, p1[2])
    parts = [capsule(a0, a1, 0.5), capsule(b0, b1, 0.5)]
    for k in range(rungs):
        f = (k + 0.7) / (rungs + 0.4)
        parts.append(capsule(tuple(a0[i] + (a1[i] - a0[i]) * f for i in range(3)), tuple(b0[i] + (b1[i] - b0[i]) * f for i in range(3)), 0.38))
    sc.add(union(*parts), mat or SCAFF, name="ladder")


# ---------------------------------------------------------------------------------------------
# sawmill: log piles, a big circular saw, a mill house with a water wheel
# ---------------------------------------------------------------------------------------------

def sawmill(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 1.8, 0.45, 3.62, 1.95
    h, rh, zb = 13.0 + 2 * t, 7.0 + t, 3.5
    hh = h - zb
    holes = [window(sc, T, "R", x1, 1.2, zb + hh * 0.55, 0.42, min(7.5, hh * 0.5))]
    if t == 1:
        holes.append(door(sc, T, "L", y1, 2.7, 0.62, 10.0, False, fw=0.06))
    else:
        holes.append(door(sc, T, "L", y1, 2.12, 0.5, 10.0, T.stone, fw=0.06))
    house(sc, T, x0, y0, x1, y1, h, rh, "x", holes, zb, over=0.2)
    if t >= 2:        # water wheel on the lit face, standing in a stone-lined race
        R = 8.6 if t == 2 else 9.8
        water_wheel(sc, T, 2.98, 2.02, 0.3, R, hub=T.gold if t >= 4 else None)
        sc.add(sub(box(2.3, 1.97, 3.99, 2.44, 0, 1.8, 0.2), box(2.37, 2.04, 4.2, 2.37, 0.7, 3.0)), T.base, name="race")
        sc.add(box(2.37, 2.04, 3.98, 2.37, 0.5, 1.25), r3.m_water(), cast=False, name="water")
    # log pile (back left)
    log_pile(sc, 0.32, 0.5, 2 if t == 1 else 3, ln=1.3, r=3.0)
    # saw bench with the big blade and a log on it
    bx0, bx1, by0, by1, bz = 0.5, 2.12, 2.86, 3.36, 4.2
    legs = union(*[box(px, py, px + 0.11, py + 0.11, 0, bz - 1.0) for px in (bx0 + 0.04, bx1 - 0.15) for py in (by0 + 0.03, by1 - 0.14)])
    sc.add(union(box(bx0, by0, bx1, by1, bz - 1.1, bz, 0.2), legs), r3.m_planks("#9A6E3E", 0.12, seed=41), name="bench")
    yb = (by0 + by1) / 2.0
    saw_blade(sc, 1.72, yb, bz + 0.8, 7.4, hub=T.gold if t >= 5 else None)
    lr = 2.5
    sc.add(cyl_x(0.42, 1.5, yb, bz + lr / ZS, lr, 0.3), BARK, name="log")
    sc.add(cyl_x(1.48, 1.512, yb, bz + lr / ZS, lr * 0.88), m_logends([(yb, bz + lr / ZS)], lr * 0.88, "x"), cast=False)
    # sawn boards (front right)
    plank_stack(sc, 2.72, 2.8, 3.74, 3.32, 2 + (t >= 2) + (t >= 4))
    # chopping stump with an axe
    sc.add(cyl(0.72, 2.3, 0.2, 0, 3.6, 0.3), BARK, cyl=(0.72, 2.3, 0.2))
    sc.add(cyl(0.72, 2.3, 0.17, 3.5, 3.75), r3.m_flat("#D2AC72", 0.12), cast=False)
    sc.add(capsule((0.72, 2.3, 3.6), (0.98, 2.14, 10.5), 0.5), T.pole)
    sc.add(rotz(box(0.68, 2.2, 0.8, 2.42, 3.4, 6.2, 0.2), 30, 0.72, 2.3), STEEL, cast=False)
    if t >= 4:
        flagpole(sc, T, 3.45, 1.2, h + rh - 1.0, 12.0, w=7.5)
    if t >= 5:
        banner(sc, T, "R", x1, 0.72, h - 1.0, hh * 0.5, 0.3)
    return sc


# ---------------------------------------------------------------------------------------------
# registry
# ---------------------------------------------------------------------------------------------

SPRITES = {}
for _t in (2, 4):
    SPRITES["bld_farm_t%d" % _t] = (lambda t=_t: g.farm(t))
for _name, _fn in (("sawmill", sawmill),):
    for _t in range(1, 6):
        SPRITES["bld_%s_t%d" % (_name, _t)] = (lambda t=_t, fn=_fn: fn(t))
