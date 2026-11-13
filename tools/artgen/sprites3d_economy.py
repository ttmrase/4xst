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

def gable_roof(x0, y0, x1, y1, z, h, axis="x", over=0.2, thick=1.4, shell=1.6):
    """Gabled roof as a shell with open ends (render3d.gable is a solid prism, so its end caps hide the gable
    walls).  Same arguments as gable(); shell = thickness of the roof, measured vertically."""
    outer = gable(x0, y0, x1, y1, z, h, axis, over, thick)
    if axis == "x":
        inner = gable(x0 - 2.0, y0, x1 + 2.0, y1, z - shell, h, axis, over, 80.0)
    else:
        inner = gable(x0, y0 - 2.0, x1, y1 + 2.0, z - shell, h, axis, over, 80.0)
    return sub(outer, inner)


def gable_cut(x0, y0, x1, y1, z, h, axis, over, shell=1.6):
    """Everything above the middle of a gable_roof() shell: subtract it from a wall box to get gable ends
    that run up into the roof."""
    big = box(x0 - 1, y0 - 1, x1 + 1, y1 + 1, z - 3.0, z + h * 2 + 30)
    return sub(big, gable(x0, y0, x1, y1, z - shell * 0.5, h, axis, over, 12.0))


def verge(x0, y0, x1, y1, z, h, axis, over, shell=1.6, w=0.05):
    """Barge boards along the rakes of the gable end that faces the camera."""
    if axis == "x":
        outer = gable(x0, y0, x1 + w, y1, z + 0.35, h, axis, over + 0.015, 0.0)
        inner = gable(x0 - 2.0, y0, x1 + 2.0, y1, z - shell - 0.5, h, axis, over, 80.0)
        slab = box(x1 + over - 0.02, y0 - 2, x1 + over + w + 0.02, y1 + 2, z - 20, z + h + 20)
    else:
        outer = gable(x0, y0, x1, y1 + w, z + 0.35, h, axis, over + 0.015, 0.0)
        inner = gable(x0, y0 - 2.0, x1, y1 + 2.0, z - shell - 0.5, h, axis, over, 80.0)
        slab = box(x0 - 2, y1 + over - 0.02, x1 + 2, y1 + over + w + 0.02, z - 20, z + h + 20)
    return inter(sub(outer, inner), slab)


def roofed(sc, T, x0, y0, x1, y1, h, rh, axis="x", over=0.2, roof=None, ridge=True, name="roof"):
    """Open gable roof with barge boards and a ridge beam; returns the cut for the walls below it."""
    shell = 2.3 if T.t == 1 else 1.5
    sc.add(gable_roof(x0, y0, x1, y1, h, rh, axis, over, shell, shell), roof or T.roof, name=name)
    sc.add(verge(x0, y0, x1, y1, h, rh, axis, over, shell), T.trim, cast=False, name=name + " verge")
    if ridge:
        if axis == "x":
            yc = (y0 + y1) / 2.0
            rb = box(x0 - over - 0.04, yc - 0.045, x1 + over + 0.07, yc + 0.045, h + rh - 0.8, h + rh + 0.5, 0.4)
        else:
            xc = (x0 + x1) / 2.0
            rb = box(xc - 0.045, y0 - over - 0.04, xc + 0.045, y1 + over + 0.07, h + rh - 0.8, h + rh + 0.5, 0.4)
        sc.add(rb, T.trim, cast=False)
    return gable_cut(x0, y0, x1, y1, h, rh, axis, over, shell)


def house(sc, T, x0, y0, x1, y1, h, rh, axis="x", holes=(), zb=3.5, over=0.2, wall=None, roof=None, ridge=True, bay=0.4,
          name="house"):
    """Gabled house: base course, walls with the given holes cut out, gable ends, open roof, ridge beam."""
    if zb > 0:
        sc.add(box(x0 - 0.07, y0 - 0.07, x1 + 0.07, y1 + 0.07, 0, zb, 0.4), T.base, name=name + " base")
    cut = roofed(sc, T, x0, y0, x1, y1, h, rh, axis, over, roof, ridge, name + " roof")
    wm = wall or T.wall(zb, h, x0, y0, bay=bay)
    sc.add(sub(box(x0, y0, x1, y1, max(zb - 1.0, 0.0), h + rh + 2.0), *holes, cut), wm, name=name)


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


def dormer(sc, T, a, b0, b1, z, w=0.66, wh=5.4, rh=3.4, face="L"):
    """Gabled dormer on a roof slope.  face 'L': the slope faces the left-front, the dormer runs from y = b0
    (inside the roof) to its front at y = b1 and is centred on x = a.  face 'R': the same on a slope that
    faces the right-front (x from b0 to b1, centred on y = a).  z = sill height of the little wall."""
    if face == "L":
        xa, ya, xb, yb, ax = a - w / 2.0, b0, a + w / 2.0, b1, "y"
    else:
        xa, ya, xb, yb, ax = b0, a - w / 2.0, b1, a + w / 2.0, "x"
    hole = window(sc, T, face, b1, a, z + wh * 0.52, w * 0.5, wh * 0.62, fw=0.045, sill=False)
    sc.add(sub(box(xa, ya, xb, yb, z - 3.0, z + wh + rh), hole, _gable_cut(xa, ya, xb, yb, z + wh, rh, ax)),
           T.wall(z, z + wh, xa, ya, bay=w), name="dormer")
    sc.add(gable(xa, ya, xb, yb, z + wh, rh, ax, over=0.08, thick=1.0), T.roof, name="dormer roof")


def chimney(sc, T, x, y, z0, z1, s=0.36, mat=None):
    """Square chimney stack centred on (x, y) with a cap; the smoke overlay starts just above z1."""
    sc.add(box(x - s / 2, y - s / 2, x + s / 2, y + s / 2, z0, z1 - 1.2, 0.25), mat or T.base, name="chimney")
    sc.add(box(x - s / 2 - 0.04, y - s / 2 - 0.04, x + s / 2 + 0.04, y + s / 2 + 0.04, z1 - 1.6, z1, 0.3),
           T.gold if T.t >= 5 else r3.m_flat("#6E665C", 0.1), name="chimney cap")
    sc.add(box(x - s / 2 + 0.06, y - s / 2 + 0.06, x + s / 2 - 0.06, y + s / 2 - 0.06, z1 - 0.4, z1 + 0.06), DARK, cast=False)


def gold_band(sc, T, x0, y0, x1, y1, z, hgt=1.1, out=0.022):
    """Gilded string course around a rectangular block (tiers 4 and 5)."""
    sc.add(box(x0 - out, y0 - out, x1 + out, y1 + out, z, z + hgt, 0.2), T.gold, cast=False, name="gold band")


def column(sc, T, x, y, z0, z1, r=0.1, mat=None, cap=None):
    """Round column with a base and a capital."""
    sc.add(cyl(x, y, r, z0, z1), mat or T.wall(), cyl=(x, y, r), name="column")
    sc.add(union(cyl(x, y, r + 0.035, z0, z0 + 1.1, 0.25), cyl(x, y, r + 0.04, z1 - 1.3, z1, 0.25)), cap or T.trim, cyl=(x, y, r + 0.04),
           cast=False)


def _lcg(seed):
    s = [(seed * 7919 + 104729) & 0x7FFFFFFF]

    def nxt():
        s[0] = (s[0] * 1103515245 + 12345) & 0x7FFFFFFF
        return (s[0] >> 8) / float(1 << 23)

    return nxt


def crag(x0, y0, x1, y1, z1, seed=0, z0=-0.6, lean=0.16, chips=7, bite=0.22):
    """Faceted rock: a block whose walls lean inwards and whose upper corners are chipped off by oblique
    planes (a convex polyhedron, so it is cheap and exact enough to march)."""
    X0, Y0, X1, Y1, Z0, Z1 = x0 * G, y0 * G, x1 * G, y1 * G, z0 * ZS, z1 * ZS
    rnd = _lcg(seed)
    planes = [(0.0, 0.0, 1.0, Z1), (0.0, 0.0, -1.0, -Z0)]
    for nx, ny, px, py in ((1, 0, X1, 0.0), (-1, 0, X0, 0.0), (0, 1, 0.0, Y1), (0, -1, 0.0, Y0)):
        a = lean * (0.4 + rnd())
        n = (nx * math.cos(a), ny * math.cos(a), math.sin(a))
        planes.append(n + (n[0] * px + n[1] * py + n[2] * Z0,))
    cx, cy, cz = (X0 + X1) / 2.0, (Y0 + Y1) / 2.0, (Z0 + Z1) / 2.0
    hx, hy, hz = (X1 - X0) / 2.0, (Y1 - Y0) / 2.0, (Z1 - Z0) / 2.0
    for k in range(chips):
        ang = 2.0 * math.pi * (k + 0.8 * rnd()) / chips
        el = 0.3 + 0.75 * rnd()
        n = (math.cos(ang) * math.cos(el), math.sin(ang) * math.cos(el), math.sin(el))
        ext = abs(n[0]) * hx + abs(n[1]) * hy + abs(n[2]) * hz
        planes.append(n + (n[0] * cx + n[1] * cy + n[2] * cz + ext * (1.0 - bite * (0.35 + 0.65 * rnd())),))
    return r3._Convex(planes, (X0, Y0, Z0), (X1, Y1, Z1))


def _hit(node, p, d, far=80.0):
    """First point of `node` on the ray from model point p along model direction d (None if it misses)."""
    x, y, z = p[0] * G, p[1] * G, p[2] * ZS
    dx, dy, dz = d[0] * G, d[1] * G, d[2] * ZS
    ln = math.sqrt(dx * dx + dy * dy + dz * dz)
    dx, dy, dz = dx / ln, dy / ln, dz / ln
    tt = 0.0
    for _ in range(200):
        dist = float(node.d(F(x + dx * tt), F(y + dy * tt), F(z + dz * tt)))
        if dist < 0.03:
            return ((x + dx * tt) / G, (y + dy * tt) / G, (z + dz * tt) / ZS)
        tt += dist
        if tt > far:
            break
    return None


def _mix(c, k, to="#FFFFFF"):
    """Blend an '#RRGGBB' colour towards another one by k (in sRGB)."""
    a, b = c.lstrip("#"), to.lstrip("#")
    return "#" + "".join("%02X" % int(round(int(a[i:i + 2], 16) * (1 - k) + int(b[i:i + 2], 16) * k)) for i in (0, 2, 4))


def stone_block(sc, x, y, sx, sy, h, mat, z0=0.0, deg=0.0):
    b = box(x, y, x + sx, y + sy, z0, z0 + h, 0.3)
    if deg:
        b = rotz(b, deg, x + sx / 2.0, y + sy / 2.0)
    sc.add(b, mat, name="block")
    return z0 + h


# ---------------------------------------------------------------------------------------------
# sawmill: log piles, a big circular saw, a mill house with a water wheel
# ---------------------------------------------------------------------------------------------

def sawmill(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 1.95, 0.4, 3.7, 1.9
    h, rh, zb = 14.0 + 2 * t, 8.5 + t, 3.5 if t < 5 else 4.6
    hh = h - zb
    holes = [window(sc, T, "R", x1, 1.15, zb + hh * 0.55, 0.42, min(7.5, hh * 0.5))]
    if t == 1:
        holes.append(door(sc, T, "L", y1, 2.45, 0.6, 10.0, False, fw=0.06))
        holes.append(window(sc, T, "L", y1, 3.2, zb + hh * 0.56, 0.36, 5.6))
    else:
        holes.append(door(sc, T, "L", y1, 2.25, 0.46, 10.0, T.stone, fw=0.055))
    house(sc, T, x0, y0, x1, y1, h, rh, "x", holes, zb, over=0.13 if t == 1 else 0.2)
    if t >= 2:        # water wheel on the lit face, standing in a stone-lined race
        R = 8.8 if t == 2 else 10.0
        water_wheel(sc, T, 3.06, 1.97, 0.3, R, hub=T.gold if t >= 4 else None,
                    wood=r3.m_planks("#8A5E30", 0.12, seed=32) if t < 5 else r3.m_planks("#A8742E", 0.12, seed=32))
        sc.add(sub(box(2.42, 1.92, 3.99, 2.4, 0, 1.8, 0.2), box(2.49, 1.99, 4.2, 2.33, 0.7, 3.0)), T.base, name="race")
        sc.add(box(2.49, 1.99, 3.98, 2.33, 0.5, 1.25), r3.m_water(), cast=False, name="water")
    if t >= 3:
        chimney(sc, T, 2.45, 0.75, h + 2.0, h + rh + 3.5, 0.34)
    if t >= 4:
        dormer(sc, T, 2.42, 1.2, 1.76, h + 1.2, 0.6, 5.0, 3.4)
        flagpole(sc, T, 3.55, 1.15, h + rh - 1.0, 12.0, w=7.5)
    if t >= 5:
        gold_band(sc, T, x0, y0, x1, y1, h - 2.4)
        banner(sc, T, "R", x1, 0.66, h - 3.0, hh * 0.45, 0.3)
        banner(sc, T, "R", x1, 1.64, h - 3.0, hh * 0.45, 0.3)
    # log pile (left): big logs, pale cut ends towards the camera
    log_pile(sc, 0.26, 0.8, 2 if t == 1 else 3, ln=1.5, r=3.3)
    if t >= 4:
        log_pile(sc, 1.36, 0.45, 2, ln=1.2, r=2.6, levels=1)
    # saw bench with the big blade and a log on it
    bx0, bx1, by0, by1, bz = 0.42, 2.2, 2.9, 3.42, 4.4
    legs = union(*[box(px, py, px + 0.12, py + 0.12, 0, bz - 1.0) for px in (bx0 + 0.04, bx1 - 0.16) for py in (by0 + 0.03, by1 - 0.15)])
    sc.add(union(box(bx0, by0, bx1, by1, bz - 1.2, bz, 0.2), legs), r3.m_planks("#9A6E3E", 0.12, seed=41), name="bench")
    yb = (by0 + by1) / 2.0
    saw_blade(sc, 1.74, yb, bz + 1.6, 9.4, hub=T.gold if t >= 4 else None)
    lr = 2.7
    sc.add(cyl_x(0.36, 1.42, yb, bz + lr / ZS, lr, 0.3), BARK, name="log")
    sc.add(cyl_x(0.36, 1.43, yb, bz + lr / ZS, lr * 0.86), m_logends([(yb, bz + lr / ZS)], lr * 0.86, "x"), cast=False)
    # sawn boards (front right)
    plank_stack(sc, 2.66, 2.84, 3.76, 3.4, 2 + (t >= 2) + (t >= 4))
    # chopping stump with an axe
    sx, sy = 1.72, 2.36
    sc.add(cyl(sx, sy, 0.19, 0, 3.4, 0.3), BARK, cyl=(sx, sy, 0.19))
    sc.add(cyl(sx, sy, 0.16, 3.3, 3.55), r3.m_flat("#D2AC72", 0.12), cast=False)
    sc.add(capsule((sx, sy, 3.4), (sx - 0.3, sy + 0.12, 10.0), 0.5), T.pole)
    sc.add(rotz(box(sx - 0.05, sy - 0.12, sx + 0.07, sy + 0.12, 3.2, 6.0, 0.2), -20, sx, sy), STEEL, cast=False)
    return sc


# ---------------------------------------------------------------------------------------------
# quarry: stepped rock face with turf on top, cut blocks, a hoist / crane
# ---------------------------------------------------------------------------------------------

def quarry(t):
    sc, T = building(t)
    rc = ["#8C877D", "#959086", "#A19C92", "#B0ABA0", "#D6CFC0"][t - 1]
    rock = m_rock(rc, seed=3)
    blockm = r3.m_flat(_mix(rc, 0.22), 0.07, spec=0.06, scale=0.8, seed=52) if t < 5 else r3.m_marble("#EAE4D6", veins=0.5, course=60.0)
    H = 18.0 + 2.5 * t
    top = [(0.22, 0.22, 1.55, 0.92, H), (1.55, 0.22, 2.75, 0.86, H * 0.84), (2.75, 0.22, 3.78, 0.92, H * 0.93),
           (0.22, 0.92, 0.92, 1.6, H * 0.96), (0.22, 1.6, 0.86, 2.75, H * 0.8), (0.22, 2.75, 0.92, 3.78, H * 0.64)]
    mid = [(0.22, 0.22, 3.4, 1.38, H * 0.5), (0.22, 0.22, 1.38, 3.3, H * 0.46)]
    low = [(0.22, 0.22, 2.9, 1.8, H * 0.22), (0.22, 0.22, 1.8, 2.6, H * 0.2)]
    sc.add(union(*[box(a, b, c, d, -0.5, z, 0.45) for a, b, c, d, z in top + mid + low]), rock, name="rock")
    turf = union(*[box(a + 0.03, b + 0.03, c - 0.03, d - 0.03, z - 0.3, z + 0.9, 0.6) for a, b, c, d, z in top])
    sc.add(lumpy(turf, 0.5, 0.6, 3), r3.m_ground("grass", "#4C8632"), name="turf")
    sc.add(union(*[box(a - 0.012, b - 0.012, c + 0.012, d + 0.012, z - 1.9, z - 0.2, 0.3) for a, b, c, d, z in top]),
           r3.m_flat("#6E5032", 0.14), cast=False, name="soil")
    wood = r3.m_planks("#9A6E3E", 0.1, seed=53)
    # cut blocks (front right)
    nb = 2 + t // 2
    for i in range(min(nb, 3)):
        stone_block(sc, 2.3 + i * 0.5, 3.12 + 0.03 * (i % 2), 0.45, 0.45, 6.0, blockm)
    if t >= 2:
        stone_block(sc, 2.56, 3.16, 0.44, 0.4, 5.6, blockm, 6.0)
    if t >= 4:
        stone_block(sc, 3.06, 3.14, 0.44, 0.42, 5.6, blockm, 6.0)
    if t >= 5:
        stone_block(sc, 2.8, 3.17, 0.42, 0.38, 5.2, blockm, 11.6)
    # the block being lifted, and what lifts it
    bx, by = 2.25, 2.5
    if t <= 2:
        ap = (bx, by, 19.0 + 3 * t)
        sc.add(union(*[capsule((fx, fy, 0), ap, 0.75) for fx, fy in ((bx - 0.55, by - 0.25), (bx + 0.55, by - 0.3), (bx + 0.02, by + 0.58))]),
               wood, name="tripod")
        sc.add(capsule(ap, (bx, by, 9.0), 0.3), ROPE, cast=False)
        stone_block(sc, bx - 0.22, by - 0.22, 0.44, 0.44, 5.4, blockm, 3.2)
    else:
        mx, my, mh = 3.3, 1.95, 28.0 + 2 * t
        tip = (bx, by, mh - 5.0)
        sc.add(union(capsule((mx, my, 0), (mx, my, mh), 1.2), capsule((mx, my, 9.0), tip, 1.0),
                     *[capsule((mx + ox, my + oy, 0), (mx, my, 10.0), 0.7) for ox, oy in ((-0.42, -0.3), (0.42, -0.1), (-0.05, 0.45))]),
               wood, name="crane")
        sc.add(union(capsule((mx, my, mh), tip, 0.3), capsule(tip, (bx, by, 12.0), 0.3)), ROPE, cast=False, name="ropes")
        sc.add(sphere(tip[0], tip[1], tip[2], 0.06), T.gold if t >= 5 else T.iron, cast=False)
        stone_block(sc, bx - 0.23, by - 0.23, 0.46, 0.46, 5.6, blockm, 6.4)
        if t >= 4:      # treadwheel beside the mast
            cart_wheel(sc, "y", mx - 0.02, my + 0.16, 6.0 / ZS + 0.4, 6.0, th=0.16, mat=wood, hub=T.gold if t >= 5 else None, spokes=3)
    if t >= 2:
        ladder(sc, (1.78, 2.72, 0), (1.42, 2.72, H * 0.46 + 1.6), 0.32, "y", rungs=4 + t // 2)
    # rubble
    for k, (rx, ry, rs, rz) in enumerate(((1.62, 3.32, 0.42, 4.2), (3.36, 2.62, 0.34, 3.4), (1.3, 3.56, 0.3, 2.8))):
        sc.add(crag(rx, ry, rx + rs, ry + rs * 0.9, rz, seed=20 + k, lean=0.35, bite=0.4), rock, name="boulder")
    if t >= 4:
        flagpole(sc, T, 3.5, 0.5, H * 0.93, 13.0, w=7.5)
    if t >= 5:          # a finished column, ready to be carted off
        column(sc, T, 1.86, 3.56, 0, 15.0, 0.13, mat=r3.m_marble("#EFE9DC", veins=0.4), cap=T.gold)
    return sc


# ---------------------------------------------------------------------------------------------
# gold mine: a crag with a timbered adit, rails, an ore cart heaped with gold
# ---------------------------------------------------------------------------------------------

def gold_heap(sc, x, y, z, rx, ry, rz, seed=0):
    sc.add(lumpy(ellipsoid(x, y, z, rx, ry, rz), 1.0, 0.85, seed), AMBER, name="gold")


def ingots(sc, x, y, n=3, bw=0.2, bl=0.5, bh=2.4):
    """Pyramid of gold bars lying along y; (x, y) = back corner."""
    parts = []
    for lvl in range(n):
        for i in range(n - lvl):
            xa = x + (i + lvl * 0.5) * (bw + 0.02)
            parts.append(box(xa, y + lvl * 0.03, xa + bw, y + bl - lvl * 0.03, lvl * bh, (lvl + 1) * bh - 0.15, 0.3))
    sc.add(union(*parts), AMBER, name="ingots")


def gold_mine(t):
    sc, T = building(t)
    rc = ["#84735F", "#8A7965", "#93826D", "#9C8B76", "#AC9D88"][t - 1]
    rock = m_rock(rc, seed=11, strata=5.2)
    hb, hl = 23.0 + 3 * t, 15.0 + 2 * t
    massif = union(crag(0.25, 0.25, 1.75, 1.45, hb * 0.84, 1), crag(1.3, 0.25, 3.0, 1.5, hb, 2), crag(2.6, 0.3, 3.78, 1.4, hb * 0.72, 3),
                   crag(0.25, 1.1, 1.4, 2.6, hl, 4), crag(0.3, 2.3, 1.3, 3.72, hl * 0.62, 5))
    sc.add(massif, rock, name="rock")
    # the adit: a small gabled porch against the rock, dark inside, a lantern in the tunnel
    ex, pb, pf = 2.3, 1.15, 1.95
    pw, ph, prh = 0.72, 12.5 + t, 5.0 + 0.5 * t
    wallm = r3.m_planks("#80562E", 0.2, seed=51) if t <= 2 else T.wall()
    hole = opening("L", pf, ex, 0.0, 0.86, 11.0, T.stone, -1.3, 0.3)
    cut = roofed(sc, T, ex - pw, pb, ex + pw, pf, ph, prh, "y", 0.13, ridge=False, name="adit roof")
    sc.add(sub(box(ex - pw, pb, ex + pw, pf, -0.3, ph + prh + 2), hole, cut), wallm, name="adit")
    sc.add(_slab("L", pf, ex - 0.5, ex + 0.5, -0.36, -0.31, 0, 12.5), DARK, cast=False, name="tunnel")
    if not T.stone:
        beam = r3.m_planks("#A87840", 0.2, seed=54)
        sc.add(union(box(ex - 0.6, pf - 0.02, ex - 0.42, pf + 0.08, 0, 12.4, 0.2), box(ex + 0.42, pf - 0.02, ex + 0.6, pf + 0.08, 0, 12.4, 0.2),
                     box(ex - 0.68, pf - 0.02, ex + 0.68, pf + 0.1, 11.0, 13.2, 0.2)), beam, name="timbering")
    else:
        fr = sub(opening("L", pf, ex, 0.0, 1.1, 12.3, True, -0.02, 0.07), opening("L", pf, ex, -1.0, 0.86, 12.0, True, -0.3, 0.3))
        sc.add(fr, T.base if t < 4 else T.gold, name="arch")
    sc.add(sphere(ex, pf - 0.3, 8.4, 0.075), r3.m_glow("#FFB040", power=3.2, bars=False), cast=False, ao=False, name="lantern")
    # rails and the ore cart
    sc.add(repeat(box(ex - 0.42, 2.02, ex + 0.42, 2.12, -0.2, 0.5, 0.2), "y", 0.31, 7), CRATE_FRAME, name="sleepers")
    sc.add(union(box(ex - 0.28, 1.6, ex - 0.21, 3.97, 0.3, 1.0, 0.2), box(ex + 0.21, 1.6, ex + 0.28, 3.97, 0.3, 1.0, 0.2)), r3.m_metal("#6E6A70"),
           name="rails")
    cy0, cy1, cz0, cz1 = 2.78, 3.46, 2.6, 7.8
    tub = sub(box(ex - 0.37, cy0, ex + 0.37, cy1, cz0, cz1, 0.25), box(ex - 0.3, cy0 + 0.07, ex + 0.3, cy1 - 0.07, cz0 + 1.5, cz1 + 3))
    sc.add(tub, r3.m_planks("#6E5038", 0.16, seed=55), name="cart")
    sc.add(union(box(ex - 0.385, cy0 - 0.015, ex + 0.385, cy1 + 0.015, cz1 - 1.3, cz1 + 0.1, 0.2),
                 box(ex - 0.385, cy0 - 0.015, ex + 0.385, cy1 + 0.015, cz0, cz0 + 1.0, 0.2)) if False else
           sub(box(ex - 0.385, cy0 - 0.015, ex + 0.385, cy1 + 0.015, cz1 - 1.2, cz1 + 0.1, 0.2), box(ex - 0.3, cy0 + 0.07, ex + 0.3, cy1 - 0.07, 0, 20)),
           T.gold if t >= 5 else HOOP, cast=False, name="cart rim")
    for wy in (cy0 + 0.16, cy1 - 0.16):
        sc.add(cyl_x(ex + 0.37, ex + 0.43, wy, 1.9, 1.9, 0.15), HOOP, name="cart wheel")
        sc.add(cyl_y(wy - 0.03, wy + 0.03, ex - 0.3, 1.9, 1.9), HOOP, cast=False)
    gold_heap(sc, ex, (cy0 + cy1) / 2.0, cz1 - 0.6, 0.29, 0.27, 2.2 + 0.5 * t, seed=3)
    # gold showing in the rock
    spots = [("L", 3.36, hb * 0.34), ("R", 1.86, hl * 0.52), ("L", 2.3, hb * 0.86), ("R", 3.05, hl * 0.3), ("L", 1.45, hb * 0.62),
             ("L", 3.1, hb * 0.58)][: 1 + t]
    for k, (face, a, z) in enumerate(spots):
        p = _hit(massif, (a, 3.9, z), (0, -1, 0)) if face == "L" else _hit(massif, (3.9, a, z), (-1, 0, 0))
        if p is None:
            continue
        r = 0.1 + 0.02 * (k % 2)
        dx, dy = (0.16, -0.03) if face == "L" else (-0.03, 0.16)
        lumps = [sphere(p[0], p[1], p[2], r), sphere(p[0] + dx, p[1] + dy, p[2] + 1.6, r * 0.7),
                 sphere(p[0] - dx * 0.9, p[1] + dy * 0.2, p[2] - 1.2, r * 0.6)]
        sc.add(lumpy(union(*lumps), 0.5, 1.0, 5 + k), AMBER, cast=False, name="vein")
    # yard
    if t >= 2:
        ztop = crate(sc, 3.05, 2.2, 0.5)
        gold_heap(sc, 3.3, 2.45, ztop - 0.4, 0.2, 0.2, 1.6, seed=4)
    if t >= 3:
        ingots(sc, 1.42, 3.12, 2 if t == 3 else 3)
        flagpole(sc, T, 3.3, 0.8, hb * 0.72 - 2.0, 14.0, w=7.5)
    if t >= 4:
        gold_heap(sc, 3.32, 3.2, 1.4, 0.3 + 0.06 * (t - 4), 0.28 + 0.05 * (t - 4), 3.2 + 1.2 * (t - 4), seed=6)
    if t >= 5:
        chest(sc, T, 2.95, 3.5, 0.5, 6.0)
        gold_band(sc, T, ex - pw, pb, ex + pw, pf, ph - 1.6)
    for k, (rx, ry, rs, rz) in enumerate(((1.42, 2.5, 0.34, 3.4), (3.5, 1.62, 0.3, 3.0))):
        sc.add(crag(rx, ry, rx + rs, ry + rs * 0.9, rz, seed=30 + k, lean=0.35, bite=0.4), rock, name="boulder")
    return sc


# ---------------------------------------------------------------------------------------------
# warehouse: a big barn with double doors, a loft hoist, crates, barrels and sacks
# ---------------------------------------------------------------------------------------------

def warehouse(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.45, 0.5, 3.45, 2.45
    h, rh, zb = 17.5 + 2.0 * t, 11.0 + 0.8 * t, 3.6 if t < 5 else 4.8
    hh = h - zb
    yc, xc = (y0 + y1) / 2.0, 1.95
    dw, dh = 1.1, min(h - 6.0, 13.0)
    zw, wh = zb + hh * 0.64, min(6.0, hh * 0.36)
    holes = [door(sc, T, "L", y1, xc, dw, dh, T.stone, fw=0.08, leaf=r3.m_planks("#8A5A30", 0.11, seed=45))]
    for a in (0.95, 2.95):
        holes.append(window(sc, T, "L", y1, a, zw, 0.36, wh))
    holes.append(window(sc, T, "R", x1, 0.95, zw, 0.38, wh))
    holes.append(window(sc, T, "R", x1, 2.0, zw, 0.38, wh))
    # loft hatch in the gable, under the hoist beam
    lz = h + 0.5
    holes.append(opening("R", x1, yc, lz, 0.5, rh * 0.5, False, -0.05, 0.3))
    sc.add(opening("R", x1, yc, lz, 0.5, rh * 0.5, False, -0.07, -0.03), T.door, cast=False, name="loft hatch")
    house(sc, T, x0, y0, x1, y1, h, rh, "x", holes, zb, over=0.13 if t == 1 else 0.2)
    # door leaves: centre split and ledges
    sc.add(union(_slab("L", y1, xc - 0.025, xc + 0.025, -0.085, -0.05, 0, dh - 0.6),
                 _slab("L", y1, xc - dw / 2, xc + dw / 2, -0.085, -0.05, dh * 0.22, dh * 0.22 + 1.1),
                 _slab("L", y1, xc - dw / 2, xc + dw / 2, -0.085, -0.05, dh * 0.62, dh * 0.62 + 1.1)), T.dark_wood, cast=False, name="ledges")
    # hoist: beam out of the gable, rope, a sack on the hook
    zt = lz + rh * 0.5 + 1.2
    sc.add(capsule((x1 - 0.2, yc, zt), (x1 + 0.5, yc, zt), 0.7), T.dark_wood, name="hoist beam")
    sc.add(capsule((x1 + 0.42, yc, zt), (x1 + 0.42, yc, 12.0), 0.3), ROPE, cast=False)
    sack(sc, x1 + 0.42, yc, 1.2, z0=6.6)
    if t >= 3:      # buttresses and a flag
        bs = [box(a - 0.1, y1 - 0.02, a + 0.1, y1 + 0.2, 0, h * 0.42, 0.25) for a in (0.56, 1.24, 2.66, 3.34)]
        bs += [box(a - 0.1, y1 - 0.02, a + 0.1, y1 + 0.12, 0, h * 0.72, 0.25) for a in (0.56, 1.24, 2.66, 3.34)]
        sc.add(union(*bs), T.base, name="buttresses")
        flagpole(sc, T, 0.6, yc, h + rh - 1.0, 12.5, w=7.5)
    if t >= 4:      # roof lantern
        cx0, cx1, cz = xc - 0.36, xc + 0.36, h + rh - 3.0
        ch = [window(sc, T, "L", yc + 0.32, xc, cz + 5.2, 0.3, 3.4, fw=0.04, sill=False),
              window(sc, T, "R", cx1, yc, cz + 5.2, 0.3, 3.4, fw=0.04, sill=False)]
        sc.add(sub(box(cx0, yc - 0.32, cx1, yc + 0.32, cz, cz + 7.6), *ch), T.wall(cz + 3, cz + 7.6, cx0, yc - 0.32, bay=0.36), name="lantern")
        sc.add(hip(cx0, yc - 0.32, cx1, yc + 0.32, cz + 7.6, 5.0, over=0.12, thick=1.0), T.roof, name="lantern roof")
        sc.add(sphere(xc, yc, cz + 13.2, 0.06), T.gold, cast=False)
        gold_band(sc, T, x0, y0, x1, y1, h - 2.3)
    if t >= 5:      # marble columns and a gilded lintel around the door, banners on the shaded side
        for a in (xc - 0.74, xc + 0.74):
            column(sc, T, a, y1 + 0.15, 0, dh + 3.0, 0.1, cap=T.gold)
        sc.add(box(xc - 0.92, y1 - 0.02, xc + 0.92, y1 + 0.3, dh + 3.0, dh + 4.8, 0.25), T.gold, name="lintel")
        banner(sc, T, "R", x1, 1.48, zw - wh * 0.5 + 1.0, hh * 0.36, 0.3)
    # goods in the yard
    crate(sc, 0.42, 2.92, 0.56)
    if t >= 2:
        crate(sc, 1.02, 3.2, 0.46, deg=12)
    if t >= 3:
        crate(sc, 0.5, 3.0, 0.4, z0=0.56 * G / ZS * 0.82, deg=-8)
    barrel(sc, 2.98, 3.02, 0.25, 8.4)
    if t >= 2:
        barrel(sc, 3.46, 3.3, 0.2, 7.0)
    sack(sc, 2.3, 3.52, 1.1)
    if t >= 2:
        sack(sc, 2.62, 3.62, 0.95)
    if t >= 4:
        chest(sc, T, 1.62, 3.42, 0.52, 5.4 + 0.5 * t)
    return sc


# ---------------------------------------------------------------------------------------------
# trading post: market stalls with striped awnings, a merchant's wagon, big scales
# ---------------------------------------------------------------------------------------------

CREAM = "#E8DEC6"


def stall(sc, T, x, y, w, d, c1, c2=CREAM, hgt=14.0, rise=5.0, goods=0):
    """Market stall: counter with goods, four posts, a striped awning that slopes towards the left-front.
    (x, y) = back corner of the w x d footprint."""
    wood = r3.m_planks("#946C3C", 0.12, seed=50)
    sc.add(union(box(x + 0.03, y + d * 0.46, x + w - 0.03, y + d - 0.03, 0, 5.3, 0.2), box(x + 0.03, y + 0.04, x + w - 0.03, y + d * 0.3, 0, 8.0, 0.2)),
           wood, name="counter")
    sc.add(box(x - 0.01, y + d * 0.43, x + w + 0.01, y + d + 0.01, 5.2, 6.1, 0.25), PALE_WOOD, name="counter top")
    gy, gz = y + d * 0.72, 6.1
    gx = [x + w * f for f in (0.2, 0.5, 0.8)]
    if goods == 0:      # fruit and vegetables
        for k, c in enumerate(("#C8402E", "#E0902A", "#6FA83A")):
            sc.add(lumpy(ellipsoid(gx[k], gy, gz + 0.9, w * 0.11, d * 0.16, 2.2), 0.6, 1.1, k), r3.m_flat(c, 0.16, scale=1.4, seed=60 + k), name="produce")
    elif goods == 1:    # bolts of cloth
        for k, c in enumerate(("#8A44C0", "#3A6CC4", "#D8C89A")):
            sc.add(cyl_y(y + d * 0.5, y + d * 0.96, gx[k], gz + 1.9 + 0.5 * (k == 1), 2.1, 0.4), r3.m_cloth(c), name="cloth")
    else:               # jars and a pile of coin
        for k in (0, 1):
            sc.add(union(ellipsoid(gx[k], gy, gz + 2.3, w * 0.085, w * 0.085, 2.5), cyl(gx[k], gy, w * 0.05, gz + 4.2, gz + 5.6, 0.2)),
                   r3.m_flat("#B8623A", 0.12, spec=0.2, seed=63), cyl=(gx[k], gy, w * 0.085), name="jar")
        sc.add(lumpy(ellipsoid(gx[2], gy, gz + 0.6, w * 0.1, d * 0.14, 1.7), 0.5, 1.2, 9), AMBER, name="coin")
    sc.add(union(*[capsule((px, py, 0), (px, py, hgt + (rise * 0.9 if py < y + d / 2 else 0.0)), 0.6)
                   for px in (x + 0.04, x + w - 0.04) for py in (y + 0.04, y + d - 0.04)]), r3.m_flat("#6E4A28", 0.1), name="posts")
    xa, xb, ya, yb = x - 0.12, x + w + 0.12, y - 0.08, y + d + 0.2
    n = max(3, int(round((xb - xa) / 0.25)) | 1)
    sw = (xb - xa) / n
    cloth = m_stripes(c1, c2, sw, "x", xa)
    sc.add(r3._roof(xa * G, ya * G, xb * G, yb * G, hgt * ZS, rise * ZS, [("y+", (yb - ya) * G)], 0.8), cloth, name="awning")
    r = sw * G / 2.0 - 0.12
    tab = union(box(xa + 0.006, yb - 0.03, xa + sw - 0.006, yb + 0.015, hgt - 2.2, hgt - 0.4), cyl_y(yb - 0.03, yb + 0.015, xa + sw / 2.0, hgt - 2.2, r))
    sc.add(repeat(tab, "x", sw, n), cloth, cast=False, name="valance")


def scales(sc, T, x, y, z0, h=10.0, s=1.0, metal=None, post=None):
    """Merchant's balance: a post from z0 with a beam that lies across the screen and two hanging pans."""
    metal = metal or r3.m_gold("#DDA22E")
    zt = z0 + h
    sc.add(capsule((x, y, z0), (x, y, zt), 0.8 * s), post or metal, name="scales post")
    a, tilt = 0.34 * s, 1.1 * s
    e0, e1 = (x - a, y + a, zt - 1.0 + tilt), (x + a, y - a, zt - 1.0 - tilt)
    sc.add(union(capsule(e0, e1, 0.62 * s), sphere(x, y, zt + 0.5, 0.07 * s)), metal, name="beam")
    for (px, py, pz), drop in ((e0, 5.0 * s), (e1, 5.6 * s)):
        zp = pz - drop
        k = 0.12 * s
        sc.add(union(capsule((px, py, pz), (px - k, py + k, zp), 0.24), capsule((px, py, pz), (px + k, py - k, zp), 0.24)), metal, cast=False)
        sc.add(cone(px, py, 0.05 * s, zp - 1.5 * s, zp, 0.19 * s), metal, name="pan")


def wagon(sc, T, x, y, ln=1.1, wd=0.56, load=1):
    """Two-wheeled merchant's cart, long side towards the left-front; (x, y) = back corner of the bed."""
    wood = r3.m_planks("#9A6E3E", 0.1, seed=56)
    R = 4.3
    zb = R / ZS + 0.9
    bed = sub(box(x, y, x + ln, y + wd, zb - 0.6, zb + 3.6, 0.2), box(x + 0.05, y + 0.05, x + ln - 0.05, y + wd - 0.05, zb + 0.6, zb + 9))
    sc.add(bed, wood, name="wagon")
    sc.add(union(capsule((x + ln, y + 0.07, zb), (x + ln + 0.55, y + 0.07, 0.8), 0.5), capsule((x + ln, y + wd - 0.07, zb), (x + ln + 0.55, y + wd - 0.07, 0.8), 0.5),
                 capsule((x + 0.3, y + wd * 0.5, zb - 0.6), (x + 0.2, y + wd * 0.5, 0.3), 0.5)), CRATE_FRAME, name="shafts")
    cart_wheel(sc, "y", x + ln * 0.5, y + wd + 0.01, R / ZS + 0.1, R, th=0.06)
    cart_wheel(sc, "y", x + ln * 0.5, y - 0.07, R / ZS + 0.1, R, th=0.06)
    zl = zb + 0.6
    if load >= 1:
        barrel(sc, x + 0.26, y + wd * 0.5, 0.17, 6.0, zl)
        sack(sc, x + 0.62, y + wd * 0.36, 0.95, zl)
        sack(sc, x + 0.9, y + wd * 0.6, 0.9, zl)
    if load >= 2:
        crate(sc, x + 0.5, y + 0.1, 0.32, z0=zl + 3.0)


def trading_post(t):
    sc, T = building(t)
    red, blue, green, purple, gold_c = "#C8402E", "#3A6CC4", "#3F9A58", "#8A44C0", "#DFA226"
    brass = r3.m_gold("#DDA22E")
    if t == 1:
        stall(sc, T, 0.6, 0.6, 1.65, 1.25, red, goods=0)
        scales(sc, T, 3.15, 0.95, 0.0, 22.0, 1.0, brass, post=T.dark_wood)
        sc.add(box(2.98, 0.78, 3.32, 1.12, 0, 2.2, 0.3), T.base)
    elif t == 2:
        stall(sc, T, 0.5, 0.5, 1.5, 1.2, red, goods=0)
        stall(sc, T, 0.4, 2.25, 1.3, 1.1, blue, goods=1, hgt=13.0)
        scales(sc, T, 3.15, 0.95, 0.0, 23.0, 1.05, brass, post=T.dark_wood)
        sc.add(box(2.96, 0.76, 3.34, 1.14, 0, 2.6, 0.3), T.base)
    else:
        # the merchants' hall (back left) carries the scales on its roof
        x0, y0, x1, y1 = 0.45, 0.45, 2.3, 1.72
        h, rh, zb = 13.0 + 2 * t, 7.6 + 0.5 * t, 3.4 if t < 5 else 4.4
        hh = h - zb
        yc = (y0 + y1) / 2.0
        holes = [door(sc, T, "L", y1, 1.72, 0.56, 10.5, True, fw=0.06), window(sc, T, "L", y1, 0.98, zb + hh * 0.58, 0.4, min(7.5, hh * 0.5)),
                 window(sc, T, "R", x1, yc, zb + hh * 0.58, 0.42, min(7.5, hh * 0.5))]
        house(sc, T, x0, y0, x1, y1, h, rh, "x", holes, zb, over=0.18)
        if t >= 4:      # corner tower
            tx0, ty0, tx1, ty1, th = 0.4, 0.4, 1.22, 1.22, h + rh + 4.0 + 2 * (t - 4)
            thole = [window(sc, T, "L", ty1, 0.81, th - 4.6, 0.3, 5.0), window(sc, T, "R", tx1, 0.81, th - 4.6, 0.3, 5.0)]
            sc.add(sub(box(tx0, ty0, tx1, ty1, 0, th), *thole), T.wall(), name="tower")
            gold_band(sc, T, tx0, ty0, tx1, ty1, th - 1.4)
            sc.add(hip(tx0, ty0, tx1, ty1, th, 8.0, over=0.12, thick=1.2), T.roof, name="tower roof")
            scales(sc, T, 0.81, 0.81, th + 7.0, 8.0, 1.0, T.gold)
            gold_band(sc, T, x0, y0, x1, y1, h - 2.2)
        else:
            scales(sc, T, 1.4, yc, h + rh - 0.5, 9.0, 1.0, brass)
        flagpole(sc, T, 2.2, yc, h + rh - 1.0, 11.0, w=7.0, fh=5.0)
        if t >= 5:
            banner(sc, T, "L", y1, 0.98, zb + hh * 0.3 - 0.6, hh * 0.22, 0.3)
        stall(sc, T, 2.58, 0.55, 1.2, 1.1, [green, purple, gold_c][t - 3], goods=2, hgt=13.0)
        stall(sc, T, 0.4, 2.25, 1.3, 1.1, [red, red, purple][t - 3], goods=0 if t < 5 else 1, hgt=13.0)
    # the yard: wagon, barrels, crates
    wagon(sc, T, 2.15, 2.75, 1.1, 0.56, load=1 + (t >= 2))
    if t == 1:
        crate(sc, 0.55, 2.5, 0.52)
        barrel(sc, 1.45, 2.85, 0.22, 7.4)
        sack(sc, 0.95, 3.35, 1.0)
        sack(sc, 2.75, 1.9, 1.0)
    elif t == 2:
        crate(sc, 2.45, 1.55, 0.5)
        barrel(sc, 3.35, 1.95, 0.22, 7.4)
        sack(sc, 2.2, 2.3, 1.0)
    else:
        barrel(sc, 2.05, 2.25, 0.2, 7.0)
        if t >= 4:
            chest(sc, T, 3.1, 2.05, 0.5, 5.6)
        if t >= 5:
            sc.add(lumpy(ellipsoid(2.75, 2.25, 1.0, 0.22, 0.2, 2.2), 0.7, 1.0, 12), AMBER, name="coin heap")
    return sc


# ---------------------------------------------------------------------------------------------
# builders' hut: a small workshop, scaffolding around a wall going up, timber, a big mallet
# ---------------------------------------------------------------------------------------------

def scaffold(sc, x0, y0, x1, y1, decks, top, mat=None):
    """Scaffold bay: six poles, plank decks at the given heights, cross braces on the two visible sides."""
    mat = mat or SCAFF
    ym = (y0 + y1) / 2.0
    poles = [capsule((px, py, 0), (px, py, top), 0.62) for px in (x0, x1) for py in (y0, ym, y1)]
    rails, braces = [], []
    zs = [0.0] + list(decks)
    for za, zb in zip(zs, zs[1:]):
        braces += [capsule((x0, y1, za + 0.8), (x1, y1, zb - 0.8), 0.42), capsule((x1, y0, za + 0.8), (x1, ym, zb - 0.8), 0.42),
                   capsule((x1, y1, za + 0.8), (x1, ym, zb - 0.8), 0.42)]
    for zd in decks:
        rails += [capsule((x0, y1, zd + 3.6), (x1, y1, zd + 3.6), 0.4), capsule((x1, y0, zd + 3.6), (x1, y1, zd + 3.6), 0.4)]
    sc.add(union(*poles, *rails), mat, name="scaffold poles")
    sc.add(union(*braces), r3.m_flat("#7A5530", 0.1), cast=False, name="braces")
    sc.add(union(*[box(x0 - 0.06, y0 - 0.06, x1 + 0.06, y1 + 0.06, zd - 0.7, zd, 0.15) for zd in decks]), BOARD, name="decks")


def mallet(sc, T, x, y, s=1.0, head=None):
    """Builder's mallet leaning on its head (the sign of the trade)."""
    tip = (x + 0.42 * s, y - 0.3 * s, 13.5 * s)
    sc.add(capsule((x, y, 0.4), tip, 0.75 * s), r3.m_flat("#8A6238", 0.12), name="mallet handle")
    hd = box(tip[0] - 0.26 * s, tip[1] - 0.13 * s, tip[0] + 0.26 * s, tip[1] + 0.13 * s, tip[2] - 2.6 * s, tip[2] + 2.6 * s, 0.5)
    sc.add(rotz(hd, -35, tip[0], tip[1]), head or r3.m_metal("#8E939C"), name="mallet head")


def builders_hut(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.5, 0.6, 2.3, 2.1
    h, rh, zb = 13.5 + 2 * t, 8.6 + 0.8 * t, 3.2 if t < 5 else 4.2
    hh = h - zb
    holes = [door(sc, T, "L", y1, 1.02, 0.56, 10.0, T.stone, fw=0.06), window(sc, T, "L", y1, 1.8, zb + hh * 0.58, 0.38, min(7.0, hh * 0.5)),
             window(sc, T, "R", x1, 1.35, zb + hh * 0.58, 0.42, min(7.0, hh * 0.5))]
    house(sc, T, x0, y0, x1, y1, h, rh, "x", holes, zb, over=0.13 if t == 1 else 0.18)
    if t >= 3:
        chimney(sc, T, 0.95, 0.95, h + 2.0, h + rh + 3.5, 0.34)
    if t >= 4:
        dormer(sc, T, 1.75, 1.42, 1.96, h + 1.2, 0.58, 4.8, 3.2)
        flagpole(sc, T, 2.15, 1.35, h + rh - 1.0, 12.0, w=7.5)
    if t >= 5:
        gold_band(sc, T, x0, y0, x1, y1, h - 2.3)
        banner(sc, T, "R", x1, 0.86, h - 3.2, hh * 0.4, 0.28)
    # scaffolding around the wall that is going up (back right)
    sx0, sy0, sx1, sy1 = 2.72, 0.42, 3.68, 2.02
    decks = [10.5, 20.5, 30.5][: 1 + (t >= 2) + (t >= 4)]
    scaffold(sc, sx0, sy0, sx1, sy1, decks, decks[-1] + 5.0)
    wm = [r3.m_planks("#A2643A", 0.2, seed=57), r3.m_rubble("#A59D90", size=3.6), r3.m_stone("#BCB29C", course=3.4), r3.m_stone("#DDD6C7", course=3.6, joint=0.3, var=0.06, mortar="#8F897D"),
          r3.m_marble("#EFE9DC", veins=0.42, course=5.0)][t - 1]
    wz = decks[-1] - 3.0
    wx0, wx1 = sx0 + 0.22, sx1 - 0.22
    wall = union(box(wx0, sy0 + 0.14, wx1, sy0 + 0.75, 0, wz + 4.0, 0.2), box(wx0, sy0 + 0.75, wx1, sy0 + 1.2, 0, wz, 0.2),
                 box(wx0, sy0 + 1.2, wx1, sy1 - 0.14, 0, wz - 4.5, 0.2))
    sc.add(wall, wm, name="new wall")
    ladder(sc, (sx0 + 0.1, sy1 + 0.5, 0), (sx0 + 0.1, sy1 + 0.04, decks[0] + 0.6), 0.34, "x", rungs=4)
    if t >= 3:      # hoist arm with a block on the hook
        zt = decks[-1] + 4.6
        sc.add(capsule((sx1 - 0.1, sy1 - 0.2, zt), (sx1 - 0.1, sy1 + 0.6, zt + 1.2), 0.75), SCAFF, name="hoist")
        sc.add(capsule((sx1 - 0.1, sy1 + 0.55, zt + 1.0), (sx1 - 0.1, sy1 + 0.55, 9.0), 0.28), ROPE, cast=False)
        stone_block(sc, sx1 - 0.3, sy1 + 0.36, 0.4, 0.38, 4.6, r3.m_flat(["#C9C1AE", "#DCD6C8", "#EFE9DC"][t - 3], 0.07, spec=0.05), 4.6)
    # the yard: mallet on a crate, boards, dressed stone
    crate(sc, 1.5, 2.95, 0.5)
    mallet(sc, T, 1.12, 3.4, 1.0, head=T.gold if t >= 5 else None)
    plank_stack(sc, 2.5, 3.1, 3.72, 3.56, 2 + (t >= 3))
    if t >= 2:
        bm = r3.m_flat(["#B5AD9C", "#C2BAA8", "#D6D0C2", "#EAE4D6"][t - 2], 0.07, spec=0.05, seed=58)
        stone_block(sc, 0.36, 2.72, 0.46, 0.42, 5.0, bm)
        if t >= 3:
            stone_block(sc, 0.4, 3.2, 0.44, 0.4, 5.0, bm, deg=10)
        if t >= 4:
            stone_block(sc, 0.4, 2.78, 0.4, 0.38, 4.6, bm, 5.0, deg=-8)
    if t >= 5:
        column(sc, T, 2.2, 2.62, 0, 12.0, 0.12, cap=T.gold)
    return sc


# ---------------------------------------------------------------------------------------------
# tavern: a cosy inn - lit windows, chimney, hanging sign, barrels; arcane touches at the high tiers
# ---------------------------------------------------------------------------------------------

ARCANE = r3.m_glow("#B478FF", power=2.4, bars=False)


def crystal(sc, x, y, z, r=0.1, up=6.0, down=3.0, mat=None):
    """Floating bipyramid crystal, glowing."""
    sc.add(union(cone(x, y, r, z, z + up), cone(x, y, 0.004, z - down, z, r)), mat or ARCANE, cast=False, ao=False, name="crystal")


def tavern(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.95, 0.6, 3.05, 2.8
    h, rh, zb = 11.0 + 3 * t, 18.0 + t, 3.6 if t < 5 else 4.6          # a steep roof: ridge at 29 + 4 t
    xc, fz, j, over = 2.0, 14.4, 0.07, 0.18
    ux0, ux1, uy1 = (x0, x1, y1) if t == 1 else (x0 - j, x1 + j, y1 + j)
    run = (ux1 - ux0) / 2.0 + over

    def slope(x):                                  # height of the roof above grid x
        return h + rh * (1.0 - abs(x - xc) / run)

    cut = roofed(sc, T, ux0, y0, ux1, uy1, h, rh, "y", over)
    # ground floor
    holes = [door(sc, T, "L", y1, xc, 0.7, 11.0, T.stone, fw=0.07), window(sc, T, "L", y1, 2.66, 8.4, 0.42, 6.2),
             window(sc, T, "L", y1, 1.34, 8.4, 0.42, 6.2), window(sc, T, "R", x1, 1.74, 8.4, 0.44, 6.2)]
    sc.add(box(x0 - 0.07, y0 - 0.07, x1 + 0.07, y1 + 0.07, 0, zb, 0.4), T.base, name="base")
    if t == 1:
        holes.append(window(sc, T, "L", y1, xc, h + rh * 0.42, 0.42, 5.6))
        sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + rh + 2), *holes, cut), T.wall(zb, h, x0, y0), name="inn")
    else:
        sc.add(sub(box(x0, y0, x1, y1, zb - 1, fz), *holes), T.wall(zb, fz, x0, y0, bay=0.42), name="ground floor")
        # jettied upper floor: plaster and timber over stone at tier 3, the tier's own wall otherwise
        sc.add(box(ux0 - 0.02, y0, ux1 + 0.02, uy1 + 0.02, fz - 1.0, fz + 0.3, 0.3), T.trim if t < 4 else T.base, name="jetty")
        uz, uw = fz + 4.8, 5.8
        up = [window(sc, T, "L", uy1, a, uz, 0.4, uw) for a in ((2.0, 2.62) if t >= 4 else (1.38, 2.0, 2.62))]
        up.append(window(sc, T, "L", uy1, xc, fz + 14.0 + 1.5 * (t - 2), 0.38, 5.0))
        if h - fz >= 8.0:
            up += [window(sc, T, "R", ux1, a, fz + (h - fz) * 0.46, 0.42, min(6.0, (h - fz) * 0.5)) for a in (1.74, 2.36)]
        um = r3.m_plaster("#EBDCB6", "#6E4526", fz, h + 5.0, 0.44, ux0, y0) if t <= 3 else T.wall(fz, h, ux0, y0, bay=0.44)
        sc.add(sub(box(ux0, y0, ux1, uy1, fz, h + rh + 2), *up, cut), um, name="upper floor")
        if h - fz < 8.0:      # low eaves: light the upper floor through a dormer
            df = xc + run * 0.6
            dormer(sc, T, 1.92, df - 0.62, df, slope(df) - 0.4, 0.66, 5.4, 3.6, face="R")
    # chimney: TerritoryLife.kt starts the smoke at grid (3.0, 0.9), z = 30 + 4 * tier
    ctop = 28.0 + 4 * t
    sc.add(box(2.8, 0.68, 3.26, 1.12, 0, ctop - 1.2, 0.25), T.base if t < 5 else T.wall(), name="chimney")
    sc.add(box(2.76, 0.64, 3.3, 1.16, ctop - 1.7, ctop, 0.3), T.gold if t >= 5 else r3.m_flat("#6E665C", 0.1), name="chimney cap")
    sc.add(box(2.88, 0.76, 3.18, 1.04, ctop - 0.4, ctop + 0.06), DARK, cast=False)
    if t >= 3:      # torches by the door (same coordinates as the animated flames)
        torch(sc, T, 1.55, y1 + 0.05, 12.5, 0.85)
        torch(sc, T, 2.45, y1 + 0.05, 12.5, 0.85)
    if t >= 4:
        gold_band(sc, T, ux0, y0, ux1, uy1, fz + 0.5, 0.9)
        # corbelled corner turret with arcane light in its windows
        tx, ty, tr = ux0 + 0.05, uy1 - 0.05, 0.4
        T.slit = ARCANE
        sc.add(cone(tx, ty, 0.06, fz - 6.0, fz + 0.4, tr), T.base, cyl=(tx, ty, tr), name="corbel")
        ttop = round_tower(sc, T, tx, ty, tr, h + 8.0 + 2 * (t - 4), 10.0 + t, z0=fz, crown=False, slit_deg=75.0)
        crystal(sc, tx, ty, ttop + 3.4 + (t - 4), 0.07 + 0.03 * (t - 4), 5.0 + 2 * (t - 4), 2.6 + (t - 4))
    if t >= 5:      # summoning circle in the yard
        cx, cy = 1.3, 3.42
        sc.add(union(torus(cx, cy, 0.25, 0.4, 0.42), torus(cx, cy, 0.25, 0.24, 0.3)), ARCANE, cast=False, ao=False, name="circle")
        sc.add(union(*[sphere(cx + 0.32 * math.cos(math.radians(72 * k + 18)), cy + 0.32 * math.sin(math.radians(72 * k + 18)), 0.3, 0.035) for k in range(5)]),
               ARCANE, cast=False, ao=False)
        crystal(sc, cx, cy, 7.0, 0.07, 4.2, 2.4)
        banner(sc, T, "R", ux1, 2.04, fz + (h - fz) * 0.46 - 4.4, 5.0, 0.26)
    # hanging sign on a bracket (right-hand wall, board towards the left-front)
    sy, zs = 2.5, min(h - 2.0, 16.0)
    bh = 7.6 if t == 1 else 9.0
    sc.add(union(capsule((ux1 - 0.02, sy, zs), (ux1 + 0.86, sy, zs), 0.55), capsule((ux1 - 0.02, sy, zs - 4.2), (ux1 + 0.42, sy, zs), 0.4)), T.iron, name="bracket")
    bx0, bx1 = ux1 + 0.14, ux1 + 0.84
    sc.add(box(bx0, sy - 0.035, bx1, sy + 0.035, zs - 1.2 - bh, zs - 1.2, 0.4), r3.m_planks("#7A4E2A", 0.4, seed=59), name="sign")
    sc.add(sub(box(bx0 - 0.02, sy - 0.045, bx1 + 0.02, sy + 0.045, zs - 1.6 - bh, zs - 0.8, 0.4), box(bx0 + 0.05, sy - 0.2, bx1 - 0.05, sy + 0.2, zs - 0.6 - bh, zs - 1.8)),
           T.gold if t >= 4 else T.iron, cast=False, name="sign rim")
    mx, mz = (bx0 + bx1) / 2.0 - 0.03, zs - 1.2 - bh * 0.5         # the mug on the board
    sc.add(box(mx - 0.16, sy + 0.03, mx + 0.12, sy + 0.065, mz - 2.6, mz + 1.4, 0.3), AMBER, cast=False, name="mug")
    sc.add(box(mx - 0.19, sy + 0.03, mx + 0.15, sy + 0.07, mz + 1.2, mz + 2.6, 0.5), r3.m_flat("#F2ECDC", 0.04), cast=False, name="foam")
    sc.add(sub(box(mx + 0.1, sy + 0.03, mx + 0.27, sy + 0.06, mz - 1.9, mz + 0.8, 0.4), box(mx + 0.1, sy, mx + 0.2, sy + 0.1, mz - 1.2, mz + 0.1)), AMBER, cast=False)
    # the yard: barrels, a bench
    barrel(sc, 2.78, 3.2, 0.24, 8.2)
    barrel(sc, 3.28, 3.42, 0.2, 7.0)
    if t >= 2:
        barrel_lying(sc, 2.42, 3.25, 3.8, 3.3)
    if 2 <= t <= 4:
        sc.add(union(box(0.95, 3.22, 1.75, 3.46, 2.6, 3.5, 0.2), box(1.0, 3.26, 1.1, 3.42, 0, 2.7), box(1.6, 3.26, 1.7, 3.42, 0, 2.7)),
               r3.m_planks("#9A6E3E", 0.12, seed=41), name="bench")
    if 3 <= t <= 4:
        sc.add(union(cyl(1.14, 3.34, 0.055, 3.5, 5.6), cyl(1.52, 3.34, 0.055, 3.5, 5.6)), AMBER, name="mugs")
    return sc


# ---------------------------------------------------------------------------------------------
# academy: a columned hall of learning under a dome, with a glowing orb / an armillary on top
# ---------------------------------------------------------------------------------------------

LORE = r3.m_glow("#8FE6FF", power=2.6, bars=False)


def dome(sc, T, x, y, r, z, hgt, mat, ribs=None, n=8):
    """Half-ellipsoid dome on (x, y), springing from height z; optional gilded ribs."""
    sc.add(inter(ellipsoid(x, y, z, r, r, hgt), box(x - r, y - r, x + r, y + r, z, z + hgt + 1)), mat, cyl=(x, y, r), name="dome")
    if ribs is not None:
        shell = sub(ellipsoid(x, y, z, r + 0.022, r + 0.022, hgt + 0.5), ellipsoid(x, y, z, r - 0.02, r - 0.02, hgt - 0.5))
        bars = union(*[rotz(box(x - 0.028, y - r - 0.1, x + 0.028, y + r + 0.1, z, z + hgt + 1), 180.0 * k / (n // 2) + 22.0, x, y) for k in range(n // 2)])
        sc.add(inter(shell, bars), ribs, cast=False, name="ribs")


def potted(sc, x, y, pot, s=1.0):
    sc.add(cone(x, y, 0.1 * s, 0, 3.6 * s, 0.15 * s), pot, cyl=(x, y, 0.15 * s), name="pot")
    sc.add(lumpy(ellipsoid(x, y, 6.6 * s, 0.19 * s, 0.19 * s, 3.6 * s), 0.8 * s, 0.7, 4), r3.m_foliage("#3F8A3E", seed=71), name="shrub")


def telescope(sc, T, x, y, z0=0.0, s=1.0, metal=None):
    """Brass telescope on a tripod, pointing up towards the right."""
    metal = metal or r3.m_gold("#C99232")
    hz = z0 + 8.5 * s
    sc.add(union(*[capsule((x + ox * s, y + oy * s, z0), (x, y, hz), 0.5) for ox, oy in ((-0.24, -0.1), (0.2, -0.16), (0.02, 0.26))]), T.pole, name="tripod")
    a, b = (x - 0.3 * s, y + 0.22 * s, hz - 2.4 * s), (x + 0.42 * s, y - 0.3 * s, hz + 5.6 * s)
    m = tuple(a[i] + (b[i] - a[i]) * 0.55 for i in range(3))
    sc.add(union(capsule(a, m, 1.0 * s), capsule(m, b, 1.45 * s)), metal, name="telescope")


def academy(t):
    sc, T = building(t)
    x0, y0, x1, y1 = 0.7, 0.6, 3.3, 2.75
    h, zb = 15.0 + 3 * t, 3.4 if t < 5 else 4.4
    hh = h - zb
    xc, dy = 2.0, 1.62
    # steps and podium of the porch
    sc.add(union(box(1.02, 2.7, 2.98, 3.7, 0, 1.2, 0.25), box(1.12, 2.7, 2.88, 3.56, 0, 2.4, 0.25)), T.base, name="steps")
    sc.add(box(x0 - 0.07, y0 - 0.07, x1 + 0.07, y1 + 0.07, 0, zb, 0.4), T.base, name="base")
    wz, wh = zb + hh * 0.54, min(11.0, hh * 0.58)
    holes = [door(sc, T, "L", y1, xc, 0.7, 11.5, True, fw=0.07, z0=2.4), window(sc, T, "L", y1, 1.0, wz, 0.4, wh, arch=True),
             window(sc, T, "L", y1, 3.0, wz, 0.4, wh, arch=True)]
    holes += [window(sc, T, "R", x1, a, wz, 0.42, wh, arch=True) for a in (1.0, 1.68, 2.36)]
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, h), *holes), T.wall(zb, h, x0, y0, bay=0.43), name="hall")
    if t >= 3:      # corner pilasters
        sc.add(union(*[box(px - 0.09, py - 0.09, px + 0.09, py + 0.09, zb, h - 2.0, 0.3) for px, py in ((x0, y1), (x1, y1), (x1, y0))]), T.base, name="pilasters")
    # porch: four columns, entablature, pediment
    pz = min(h - 7.0, 17.0 + t)
    px0, px1, py0, py1, prh = 1.22, 2.78, 2.75, 3.44, 7.4
    col_m = r3.m_planks("#8A5A30", 0.06, seed=72) if t == 1 else (r3.m_flat("#E4D6B4", 0.06) if t == 2 else None)
    for a in (1.34, 1.78, 2.22, 2.66):
        column(sc, T, a, 3.3, 2.4, pz, 0.095, mat=col_m, cap=T.gold if t >= 4 else T.trim)
    sc.add(box(px0, py0, px1, py1, pz, pz + 2.0, 0.2), T.trim if t <= 2 else T.wall(), name="entablature")
    if t >= 4:
        sc.add(box(px0 - 0.015, py0, px1 + 0.015, py1 + 0.015, pz + 0.5, pz + 1.4), T.gold, cast=False)
    cut = roofed(sc, T, px0, py0 - 0.3, px1, py1 - 0.02, pz + 2.0, prh, "y", 0.07, ridge=False, name="pediment")
    sc.add(sub(box(px0 + 0.03, py0, px1 - 0.03, py1 - 0.05, pz + 2.0, pz + 4.0 + prh), cut), T.wall(pz + 2.0, pz + 14.0, px0, py0, bay=0.78), name="tympanum")
    sc.add(sphere(xc, py1 - 0.05, pz + 2.0 + prh * 0.36, 0.085), T.gold if t >= 3 else T.trim, cast=False, name="boss")
    # flat roof behind a cornice, drum, and the dome (a thatched cone on the wooden hall)
    cm = [T.trim, T.trim, T.curtain, T.curtain, T.wall()][t - 1]
    sc.add(sub(box(x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, h - 2.4, h + 1.4, 0.3), box(x0 + 0.08, y0 + 0.08, x1 - 0.08, y1 - 0.08, h + 0.4, h + 9)), cm, name="cornice")
    if t <= 2:
        sc.add(box(x0 + 0.06, y0 + 0.06, x1 - 0.06, y1 - 0.06, h - 0.5, h + 0.45), BOARD, cast=False, name="roof deck")
    if t >= 4:
        gold_band(sc, T, x0 - 0.1, y0 - 0.1, x1 + 0.1, y1 + 0.1, h - 1.2, 1.0)
    dr = 0.7 + 0.04 * t
    dz = h + 4.6 + 0.4 * t
    sc.add(cyl(xc, dy, dr, h, dz), T.wall(h, dz, 0, 0, bay=0.4) if t != 2 else r3.m_flat("#E4D6B4", 0.06), cyl=(xc, dy, dr), name="drum")
    for k in range(6):
        sl = rotz(box(xc + dr - 0.03, dy - 0.065, xc + dr + 0.02, dy + 0.065, h + 1.6, dz - 0.9), -8.0 + 26.0 * k, xc, dy)
        sc.add(sl, T.slit, cast=False, ao=False)
    sc.add(cyl(xc, dy, dr + 0.06, dz - 0.5, dz + 0.5, 0.3), T.trim, cyl=(xc, dy, dr + 0.06), name="drum ring")
    if t == 1:
        dh = 12.0
        sc.add(union(cone(xc, dy, dr + 0.2, dz, dz + dh), cyl(xc, dy, dr + 0.2, dz - 0.9, dz + 0.05, 0.2)), T.roof, cyl=(xc, dy, dr + 0.2), name="cone")
        top = dz + dh
    else:
        dh = 9.0 + 2 * t
        dome(sc, T, xc, dy, dr + 0.02, dz + 0.4, dh, T.roof, ribs=T.gold if t >= 4 else None)
        top = dz + 0.4 + dh
    sc.add(union(capsule((xc, dy, top - 0.8), (xc, dy, top + 3.0), 0.5), cyl(xc, dy, 0.12, top - 0.6, top + 0.5, 0.2)), T.gold, name="spike")
    if t <= 2:
        sc.add(sphere(xc, dy, top + 3.6, 0.075), T.gold, name="finial")
    else:           # the orb of lore; armillary rings at the high tiers
        orb = 0.1 + 0.03 * (t - 3)
        oz = top + 3.4 + orb * 17.0
        sc.add(sphere(xc, dy, oz, orb), LORE, cast=False, ao=False, name="orb")
        if t >= 4:
            R = orb + 0.09
            rings = [torus(xc, dy, oz, R, 0.4)]
            if t >= 5:
                rings.append(r3.rot(torus(xc, dy, oz, R + 0.03, 0.4), "x", 62.0, (xc, dy, oz)))
                rings.append(r3.rot(torus(xc, dy, oz, R + 0.03, 0.4), "y", 62.0, (xc, dy, oz)))
            sc.add(union(*rings), T.gold, cast=False, name="armillary")
    # the stargazers' telescope on the roof, banners, shrubs in pots
    telescope(sc, T, 3.0, 0.95, h + 0.5, 0.85 + 0.05 * t, metal=T.gold if t >= 4 else None)
    if t >= 2:
        bc = "#2F5BB0" if t < 5 else None
        banner(sc, T, "L", y1, 1.0, wz - wh * 0.5 - 1.0, hh * 0.17, 0.3, color=bc)
        banner(sc, T, "L", y1, 3.0, wz - wh * 0.5 - 1.0, hh * 0.17, 0.3, color=bc)
    pot = T.gold if t >= 4 else r3.m_flat("#A6603C", 0.1)
    potted(sc, 0.5, 3.45, pot)
    if t >= 2:
        potted(sc, 3.5, 3.45, pot)
    return sc


# ---------------------------------------------------------------------------------------------
# registry
# ---------------------------------------------------------------------------------------------

SPRITES = {}
for _t in (2, 4):
    SPRITES["bld_farm_t%d" % _t] = (lambda t=_t: g.farm(t))
for _name, _fn in (("sawmill", sawmill), ("quarry", quarry), ("gold_mine", gold_mine), ("warehouse", warehouse), ("trading_post", trading_post),
                   ("builders_hut", builders_hut), ("tavern", tavern), ("academy", academy)):
    for _t in range(1, 6):
        SPRITES["bld_%s_t%d" % (_name, _t)] = (lambda t=_t, fn=_fn: fn(t))
