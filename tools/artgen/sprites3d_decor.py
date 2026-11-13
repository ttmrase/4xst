#!/usr/bin/env python3
"""sprites3d_decor.py - 3D models of every decor sprite (deco_*): trees, rocks, town houses, street
furniture, the world-map camp and temple, the construction site and the city wall kit (5 tiers).

Picked up automatically by gen_sprites3d.py (see README_3D.md):
    python3 tools/artgen/gen_sprites3d.py deco_ --png DIR --jobs 4

Units are those of gen_decor.py: grid (0, 0) is the sprite's ground anchor, z is viewport units up.
Everything here is deterministic (hash noise only, no random module).
"""

import math
import pathlib
import sys

import numpy as np

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import render3d as r3  # noqa: E402
import gen_sprites3d as g  # noqa: E402
from render3d import (F, G, ZS, Material, Scene, box, capsule, cone, cyl, cyl_x, cyl_y, ellipsoid, gable, hip,  # noqa: E402,F401
                      inter, lumpy, move, repeat, rot, rotz, rounded, smooth, sphere, sstep, sub, torus, union)
from gen_sprites3d import Tier  # noqa: E402


# ---------------------------------------------------------------------------------------------
# extra SDF nodes
# ---------------------------------------------------------------------------------------------

class _Star(r3.Node):
    """Angular scallops around a vertical axis: pushes the surface out by amp * (0.5 + 0.5 cos(n * angle)).
    Turns a cone into a ring of drooping boughs.  amp fades to 0 between z0 and z1 (world units)."""

    def __init__(self, a, cx, cy, n, amp, phase=0.0, z0=None, z1=None):
        self.a, self.cx, self.cy, self.n, self.amp, self.ph, self.z0, self.z1 = a, cx, cy, n, amp, phase, z0, z1
        self.lo, self.hi = a.lo - [amp, amp, 0.0], a.hi + [amp, amp, 0.0]
        self.relax = min(a.relax, 0.55)

    def d(self, x, y, z):
        k = 0.5 + 0.5 * np.cos(self.n * np.arctan2(y - self.cy, x - self.cx) + self.ph)
        if self.z0 is not None:
            k = k * np.clip((self.z1 - z) / (self.z1 - self.z0), 0.0, 1.0)
        return self.a.d(x, y, z) - self.amp * k


def star(a, xc, yc, n, amp_px, phase=0.0, z0=None, z1=None):
    """Scalloped version of `a` around the vertical axis through grid (xc, yc); amp in viewport units,
    fading out from height z0 to z1 (z units) when given."""
    return _Star(a, xc * G, yc * G, n, amp_px, phase, None if z0 is None else z0 * ZS, None if z1 is None else z1 * ZS)


class _Octa(r3.Node):
    """Octahedron |x|/a + |y|/b + |z|/c <= 1 (world units).  A lower bound of the distance."""

    def __init__(self, c, r):
        self.c, self.r = [float(v) for v in c], [float(v) for v in r]
        self.lo, self.hi = np.array(c, float) - np.array(r, float), np.array(c, float) + np.array(r, float)
        self.k = 1.0 / math.sqrt(sum(1.0 / (v * v) for v in self.r))

    def d(self, x, y, z):
        s = np.abs(x - self.c[0]) / self.r[0] + np.abs(y - self.c[1]) / self.r[1] + np.abs(z - self.c[2]) / self.r[2]
        return (s - 1.0) * self.k


def octa(x, y, z, rx, ry, rz):
    """Octahedron (double pyramid): rx, ry in grid units, rz in z units."""
    return _Octa((x * G, y * G, z * ZS), (rx * G, ry * G, rz * ZS))


def _h(i, seed=0, salt=0):
    """Deterministic scalar hash -> [0, 1)."""
    v = (i * 374761393 + seed * 668265263 + salt * 2246822519 + 3266489917) & 0xFFFFFFFF
    v = ((v ^ (v >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((v ^ (v >> 16)) >> 8) / 16777216.0


def facets(x, y, z, r, rz, n=11, seed=0, squash=0.0):
    """A faceted boulder: the intersection of n half-spaces around (x, y, z).  r = plan radius (grid units),
    rz = height above the centre (z units).  Deterministic pseudo-random facet directions."""
    cx, cy, cz, rr, rh = x * G, y * G, z * ZS, r * G, rz * ZS
    planes = [(0.0, 0.0, 1.0, cz + rh), (0.0, 0.0, -1.0, -cz), (1.0, 0.0, 0.0, cx + rr), (-1.0, 0.0, 0.0, rr - cx),
              (0.0, 1.0, 0.0, cy + rr), (0.0, -1.0, 0.0, rr - cy)]
    for i in range(n):
        h1, h2, h3 = _h(i, seed, 91), _h(i, seed, 92), _h(i, seed, 93)
        a = 2.0 * math.pi * (i + 0.7 * h1) / n * (2.0 if i % 2 else 1.0)
        el = math.radians(8.0 + 62.0 * h2 * (1.0 if i % 3 else 0.35))
        nx, ny, nz = math.cos(a) * math.cos(el), math.sin(a) * math.cos(el), math.sin(el)
        dist = (0.72 + 0.28 * h3) * (rr * math.cos(el) + rh * math.sin(el) * (1.0 - squash))
        planes.append((nx, ny, nz, nx * cx + ny * cy + nz * cz + dist))
    return r3._Convex(planes, (cx - rr, cy - rr, cz), (cx + rr, cy + rr, cz + rh))


# ---------------------------------------------------------------------------------------------
# extra materials
# ---------------------------------------------------------------------------------------------

def m_leaf(color, seed=0, warm=0.35, dark=0.42, dapple=0.20, bump=0.9):
    """Foliage for trees seen small: big soft value changes (light warm tops, cool dark undersides) and a
    medium-size dapple that reads as leaf clusters; nothing finer, it would turn to mush at game size."""
    base = r3.srgb(color)

    def albedo(c):
        n = r3.vnoise3(c.x * 0.10, c.y * 0.10, c.z * 0.10, seed)                 # clump-size variation
        n2 = r3.vnoise3(c.x * 0.55, c.y * 0.55, c.z * 0.55, seed + 1)            # leaf clusters
        up = np.clip(c.nz * 0.5 + 0.5, 0.0, 1.0)
        k = (0.82 + 0.30 * n) * (1.0 - dapple + 2.0 * dapple * n2) * (dark + (1.05 - dark) * sstep(0.1, 0.9, up))
        tint = np.stack([1.0 + 0.28 * warm * up, 1.0 + 0.04 * up, 1.0 - 0.28 * warm * up], axis=-1)
        return base[None, :] * k[..., None] * tint

    def height(c):
        return r3.vnoise2(c.u * 0.6, c.v * 0.6, seed + 2) * 1.2 + r3.vnoise2(c.u * 1.4, c.v * 1.4, seed + 3) * 0.5

    return Material(albedo, height, bump=bump, wrap=0.3)


def leafy(node, k=1.0, seed=0, big=1.3, small=0.55):
    """Two sizes of lumps on a leaf mass: lobes, and leaf clusters on the lobes."""
    return lumpy(lumpy(node, big * k, 0.22 / k, seed), small * k, 0.62 / k, seed + 9)


def m_bark(color, seed=0, light=None, marks=0.0):
    """Tree bark on a trunk (cylindrical mapping): vertical furrows.  light + marks > 0 gives a birch:
    pale bark with dark horizontal dashes."""
    base = r3.srgb(color)
    lt = r3.srgb(light) if light else None

    def albedo(c):
        fur = r3.vnoise2(c.u * 1.5, c.v * 0.10, seed)
        a = base[None, :] * (0.74 + 0.5 * fur)[..., None]
        if lt is not None:
            a = lt[None, :] * (0.90 + 0.14 * r3.vnoise2(c.u * 0.5, c.v * 0.2, seed + 1))[..., None]
            row = np.floor(c.v / 3.1)
            f = c.v / 3.1 - row
            h = r3.hash2(row, row * 0 + 5, seed + 2)
            dash = (np.abs(f - 0.5) < 0.13) & (r3.vnoise2(c.u * 0.45 + h * 20.0, row, seed + 3) > 1.0 - marks)
            a = r3.mixc(a, base, dash.astype(F) * 0.9)
        return a

    return Material(albedo, lambda c: r3.vnoise2(c.u * 1.5, c.v * 0.10, seed) * 0.5, bump=0.8, spec=0.02)


def m_rock(color="#6F6C66", moss="#4A7A30", seed=0, moss_amount=0.5):
    """Boulder: grey stone with a little grain, moss on the faces that look up."""
    base, ms = r3.srgb(color), r3.srgb(moss)

    def albedo(c):
        n = r3.vnoise3(c.x * 0.5, c.y * 0.5, c.z * 0.5, seed)
        a = base[None, :] * (0.86 + 0.26 * n)[..., None]
        m = sstep(0.55, 0.9, c.nz) * sstep(1.0 - moss_amount, 1.0 - moss_amount + 0.25, r3.vnoise3(c.x * 0.16, c.y * 0.16, c.z * 0.16, seed + 3) + 0.25 * n)
        return r3.mixc(a, ms * (0.8 + 0.4 * n)[..., None], m * 0.9)

    return Material(albedo, lambda c: r3.vnoise2(c.u * 0.9, c.v * 0.9, seed + 1) * 0.35, spec=0.04, gloss=14.0)


# ---------------------------------------------------------------------------------------------
# trees and bushes
# ---------------------------------------------------------------------------------------------

def _veg():
    """Scene for plants: more sky and bounce light than buildings get, so that crowns keep their colour
    in the shade instead of going black (they are drawn on a dark forest floor as often as on the lawn)."""
    return Scene("decor", sky=0.50, gnd=0.38, fill=0.32)


def _ang(deg, r):
    """Offset of length r in the direction deg, measured on screen: 0 = screen right, 90 = away from the
    camera (up the screen), 180 = screen left, 270 = towards the camera."""
    a = math.radians(deg)
    sx, sy = math.cos(a) * r, math.sin(a) * r           # screen right, into the scene
    return (sx - sy) * math.sqrt(0.5), (-sx - sy) * math.sqrt(0.5)


def tree_oak(size=1.0, leaf="#3C6A38", trunk="#4A3C30", seed=0, spread=1.0, tall=1.0):
    """Broadleaf tree: a flared trunk that forks into boughs, and a wide crown built from separate leaf
    masses (a ring of low clumps, a dome above, a small cap) so that light, shadow and gaps show its form."""
    sc = _veg()
    k = size
    bark = m_bark(trunk, seed=60 + seed)
    fork = 12.0 * k * tall
    limbs = []
    for deg, ln, zz in ((195, 0.40, 20.5), (-15, 0.42, 19.5), (95, 0.22, 24.0), (268, 0.20, 18.5)):
        dx, dy = _ang(deg + 17 * seed, ln * k * spread)
        limbs.append(capsule((0, 0, fork - 2.5 * k), (dx, dy, zz * k * tall), 1.05 * k))
    sc.add(union(cone(0, 0, 0.10 * k, 0, fork + 1.5 * k, 0.07 * k), cone(0, 0, 0.17 * k, -0.5, 3.0 * k, 0.10 * k), *limbs), bark,
           cyl=(0, 0, 0.10 * k), name="trunk")
    # crown: (direction on screen, distance, height of the centre, plan radius, vertical squash)
    clumps = [(195, 0.47, 20.5, 0.30, 0.72), (150, 0.36, 24.5, 0.29, 0.76), (-15, 0.48, 19.5, 0.29, 0.72), (30, 0.38, 24.0, 0.29, 0.76),
              (95, 0.36, 22.5, 0.33, 0.76), (250, 0.33, 20.0, 0.27, 0.70), (295, 0.30, 21.5, 0.26, 0.70),
              (280, 0.06, 27.0, 0.38, 0.78), (130, 0.16, 31.0, 0.29, 0.78), (20, 0.14, 32.5, 0.25, 0.8)]
    parts = []
    for i, (deg, dist, zz, r, sq) in enumerate(clumps):
        dx, dy = _ang(deg + 17 * seed, dist * k * spread)
        rr = r * k * (0.92 + 0.16 * _h(i, seed, 61))
        parts.append(ellipsoid(dx, dy, zz * k * tall, rr * spread ** 0.5, rr * spread ** 0.5, rr * G / ZS * sq))
    lm = m_leaf(leaf, seed=70 + seed)
    # two solids keep the smooth unions small; the step between the low and the high masses reads as depth
    sc.add(leafy(smooth(0.9 * k, *parts[:7]), k, seed), lm, name="crown low")
    sc.add(leafy(smooth(0.9 * k, *parts[7:]), k, seed + 3), lm, name="crown high")
    return sc


def tree_pine(size=1.0, leaf="#2A5644", trunk="#463A2E", seed=0, tiers=4):
    """Conifer: a bare foot of trunk and tiers of drooping boughs (scalloped cones), each tier turned a little."""
    sc = _veg()
    k = size
    sc.add(union(cone(0, 0, 0.08 * k, 0, 16 * k, 0.05 * k), cone(0, 0, 0.13 * k, -0.5, 2.6 * k, 0.08 * k)), m_bark(trunk, seed=61),
           cyl=(0, 0, 0.08 * k), name="trunk")
    top = 43.0
    z = 6.0
    for i in range(tiers):
        f = i / (tiers - 1.0)
        r = (0.60 - 0.36 * f) * k
        h = (15.0 - 3.5 * f) * k if i < tiers - 1 else (top - z) * k
        z0 = z * k
        skirt = union(cone(0, 0, r, z0, z0 + h, 0.012 * k), cone(0, 0, r * 0.45, z0 - 2.6 * k, z0 + 0.05, r))
        skirt = star(skirt, 0, 0, 7 if i < 2 else 6, 2.3 * k * (1.0 - 0.45 * f), phase=1.9 * i + seed, z0=z0 + h * 0.15, z1=z0 + h * 0.95)
        sc.add(lumpy(lumpy(skirt, 0.7 * k, 0.33 / k, seed + i), 0.4 * k, 0.8 / k, seed + i + 5),
               m_leaf(leaf, seed=80 + seed + i, warm=0.15, dark=0.36, dapple=0.16), name="pine tier")
        z += (9.6 - 1.3 * f)
    return sc


def tree_birch():
    """Birch: a slender white trunk with dark marks, showing through a tall, airy, yellow-green crown."""
    sc = _veg()
    bark = m_bark("#3A3A36", seed=62, light="#CFCBBE", marks=0.42)
    limbs = [capsule((0, 0, 22), (*_ang(170, 0.26), 31), 0.6), capsule((0, 0, 19), (*_ang(10, 0.24), 27), 0.6)]
    sc.add(union(cone(0, 0, 0.07, 0, 38, 0.03), cone(0, 0, 0.10, -0.5, 2.4, 0.07), *limbs), bark, cyl=(0, 0, 0.07), name="trunk")
    clumps = [(180, 0.31, 25.0, 0.20), (0, 0.31, 23.5, 0.19), (95, 0.24, 27.0, 0.21), (255, 0.22, 29.5, 0.19), (150, 0.21, 33.0, 0.20),
              (20, 0.21, 31.0, 0.20), (210, 0.34, 30.0, 0.15), (330, 0.33, 28.0, 0.15), (300, 0.05, 36.5, 0.21), (90, 0.06, 40.5, 0.15)]
    parts = [ellipsoid(*_ang(deg, dist), zz, r, r, r * G / ZS * 0.9) for deg, dist, zz, r in clumps]
    lm = m_leaf("#5F8444", seed=75, warm=0.45, dark=0.5)
    sc.add(leafy(smooth(0.6, *parts[:4]), 0.8, 7, big=1.1), lm, name="crown low")
    sc.add(leafy(smooth(0.6, *parts[4:]), 0.8, 8, big=1.1), lm, name="crown high")
    return sc


def bush():
    """Low berry bush."""
    sc = _veg()
    parts = [ellipsoid(0.08, -0.05, 4.4, 0.30, 0.30, 4.6), ellipsoid(*_ang(175, 0.26), 3.2, 0.24, 0.24, 3.6),
             ellipsoid(*_ang(-5, 0.30), 2.8, 0.20, 0.20, 3.0)]
    sc.add(leafy(smooth(1.0, *parts), 0.8, 4, big=1.0), m_leaf("#3F6C38", seed=77, dark=0.5), name="bush")
    berries = [sphere(*_ang(200, 0.26), 4.2, 0.045), sphere(*_ang(280, 0.24), 3.4, 0.045), sphere(*_ang(330, 0.3), 3.6, 0.045),
               sphere(*_ang(250, 0.06), 6.6, 0.045), sphere(*_ang(150, 0.14), 6.4, 0.04)]
    sc.add(union(*berries), r3.m_flat("#C42A40", 0.06, spec=0.4), cast=False, name="berries")
    return sc


def rock(variant=0):
    """Mossy boulder; variant 1 has a smaller stone leaning on it."""
    sc = Scene("decor")
    c = "#6F6C66" if variant == 0 else "#7A7368"
    sc.add(rounded(facets(0.0, 0.0, 0.0, 0.42, 9.6, n=11, seed=3 + variant * 4), 0.5), m_rock(c, seed=variant), name="rock")
    if variant:
        sc.add(rounded(facets(*_ang(-28, 0.52), 0.0, 0.2, 4.6, n=9, seed=12), 0.4), m_rock("#857D70", seed=5, moss_amount=0.3), name="stone")
    return sc


# ---------------------------------------------------------------------------------------------
# small props: hay, lamp, well, signpost, fountain
# ---------------------------------------------------------------------------------------------

def _inv_tone(color, light=None):
    """Linear albedo that a shadeless material needs so that the finished sprite shows `color` exactly
    (undoes the renderer's exposure, tone curve and grade)."""
    L = light or r3.LIGHT
    c = np.array([int(color.lstrip("#")[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])
    c = (c - 0.5) / L["contrast"] + 0.5
    lum = float(c @ np.array([0.2126, 0.7152, 0.0722]))
    c = np.clip(lum + (c - lum) / L["saturation"], 0.0, 1.0)
    lin = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    out = []
    for v in lin:                      # invert the ACES-style curve by bisection
        lo, hi = 0.0, 8.0
        for _ in range(48):
            mid = (lo + hi) / 2.0
            if float(r3._aces(np.array(mid))) < v:
                lo = mid
            else:
                hi = mid
        out.append(lo)
    return (np.array(out) / (0.55 * L["exposure"])).astype(F)


def m_board(face="#E0C48E", edge="#B08A52"):
    """The sign board: a flat, evenly light surface (the game draws its label on it), unaffected by the
    lighting.  A slightly darker rim and two faint plank lines keep it from looking like a sticker."""
    fc, ec = _inv_tone(face), _inv_tone(edge)

    def albedo(c):
        w = (c.x - c.y) * math.sqrt(0.5)                     # across the board, world units
        du = np.minimum(w - (-22.0), 22.0 - w)
        dv = np.minimum(c.z - c.lo[2], c.hi[2] - c.z)
        rim = 1.0 - sstep(0.9, 1.3, np.minimum(du, dv))
        zr = (c.z - c.lo[2]) / (c.hi[2] - c.lo[2])
        line = (1.0 - sstep(0.012, 0.03, np.minimum(np.abs(zr - 0.335), np.abs(zr - 0.665)))) * 0.35
        k = 0.97 + 0.06 * r3.vnoise2(w * 0.16, c.z * 1.2, 31)
        return r3.mixc(fc[None, :] * k[..., None], ec, np.maximum(rim, line))

    return Material(albedo, shadeless=True)


def hay():
    """Round straw bale standing on end, tied with twine."""
    sc = Scene("decor")
    straw = r3.m_thatch("#B8923F", row=2.2, seed=41)
    sc.add(union(cyl(0, 0, 0.5, 0, 9.0, 1.1), torus(0, 0, 8.7, 0.30, 0.55), torus(0, 0, 8.8, 0.12, 0.5)), straw, cyl=(0, 0, 0.5), name="bale")
    sc.add(union(cyl(0, 0, 0.507, 2.5, 3.1, 0.2), cyl(0, 0, 0.507, 6.0, 6.6, 0.2)), r3.m_flat("#7A5A22", 0.1), cast=False, name="twine")
    return sc


def lamp():
    """Street lamp.  TerritoryLife.kt pulses its glow at z = 24 (LAMP_HEIGHT), the middle of the lantern."""
    sc = Scene("decor")
    iron = r3.m_metal("#3C3A44")
    sc.add(union(cyl(0, 0, 0.10, 0, 1.4, 0.3), cyl(0, 0, 0.065, 1.4, 3.2, 0.3), capsule((0, 0, 2.5), (0, 0, 21.0), 0.8),
                 cyl(0, 0, 0.06, 12.6, 13.8, 0.3), cyl(0, 0, 0.13, 20.4, 21.4, 0.3)), iron, name="post")
    a = 0.105
    sc.add(box(-a, -a, a, a, 21.4, 27.2), r3.m_glow("#FFD066", power=2.4, bars=False), cast=False, ao=False, name="glass")
    bars = [capsule((sx * a, sy * a, 21.2), (sx * a, sy * a, 27.4), 0.3) for sx in (-1, 1) for sy in (-1, 1)]
    sc.add(union(*bars, hip(-a, -a, a, a, 27.2, 4.4, over=0.045, thick=0.7)), iron, name="cage")
    sc.add(sphere(0, 0, 32.1, 0.04), r3.m_gold(), cast=False)
    return sc


def well():
    """Village well: field-stone drum, water, windlass with a bucket and a small tiled roof."""
    sc = Scene("decor")
    drum = sub(cyl(0, 0, 0.62, 0, 8.0), cyl(0, 0, 0.46, 2.0, 9.0))
    sc.add(drum, r3.m_rubble("#8C8478", size=3.2), cyl=(0, 0, 0.62), name="drum")
    sc.add(torus(0, 0, 8.0, 0.54, 1.3), r3.m_stone("#7C766C", course=2.5), cyl=(0, 0, 0.54), name="rim")
    sc.add(cyl(0, 0, 0.46, 1.0, 5.6), r3.m_water(), cast=False, name="water")
    wood = r3.m_planks("#6A4A30", 0.1, seed=51)
    sc.add(union(capsule((-0.56, 0, 0), (-0.56, 0, 21), 0.75), capsule((0.56, 0, 0), (0.56, 0, 21), 0.75),
                 capsule((-0.56, 0, 17), (0.70, 0, 17), 0.55), capsule((0.70, 0, 17), (0.70, 0.02, 14.6), 0.4)), wood, name="frame")
    sc.add(capsule((0, 0, 17), (0, 0, 12.4), 0.22), r3.m_flat("#CDBE96", 0.08), cast=False, name="rope")
    sc.add(cone(0, 0, 0.09, 9.6, 12.4, 0.115), r3.m_planks("#7A5632", 0.06, seed=52), name="bucket")
    sc.add(gable(-0.78, -0.44, 0.78, 0.44, 20, 7, axis="x", over=0.06, thick=1.2),
           r3.m_tiles("#A8452F", kind="clay", row=2.4, width=2.4), name="roof")
    return sc


def signpost():
    """Sign: the board stands exactly where the old art had it (x +-22, z 26..42 around the anchor) and
    faces the camera, because TerritoryView.kt writes the next castle level on it."""
    sc = Scene("decor")
    wood = r3.m_planks("#6E4E30", 0.08, seed=53)
    sc.add(union(capsule((0, 0, 0), (0, 0, 43.4), 1.25), cyl(0, 0, 0.10, 0, 1.6, 0.3)), wood, name="post")
    d = 0.075                               # the board hangs in front of the post (towards the camera)
    L = 22.0 / G
    sc.add(move(rotz(box(-L, -0.035, L, 0.035, 26, 42), -45), d, d), m_board(), cast=True, name="board")
    # two battens behind the board and four nail heads on it
    sc.add(move(rotz(union(box(-L + 0.1, -0.06, -L + 0.2, 0.0, 25.4, 42.6), box(L - 0.2, -0.06, L - 0.1, 0.0, 25.4, 42.6)), -45), d - 0.03, d - 0.03),
           wood, cast=False, name="battens")
    nails = [sphere(d + 0.03 + sx * (L - 0.12) * math.sqrt(0.5), d + 0.03 - sx * (L - 0.12) * math.sqrt(0.5), zz, 0.035)
             for sx in (-1, 1) for zz in (28.2, 39.8)]
    sc.add(union(*nails), r3.m_metal("#4A4A54"), cast=False, name="nails")
    return sc


def fountain():
    """Town fountain.  TerritoryLife.kt animates drops from the upper bowl (z = 21, FOUNTAIN_TOP) down to
    the pool (z = 8, FOUNTAIN_BASIN, radius about 1) and glints on the pool."""
    sc = Scene("decor")
    st = r3.m_stone("#B4AC9A", course=3.0, length=1.8, joint=0.3)
    lt = r3.m_stone("#C6BEAC", course=3.0, length=1.6, joint=0.25, seed=33)
    basin = sub(union(cyl(0, 0, 1.46, 0, 6.4, 0.3), cyl(0, 0, 1.52, 0, 1.5, 0.4), cyl(0, 0, 1.52, 5.6, 7.8, 0.6)), cyl(0, 0, 1.28, 2.0, 9.0))
    sc.add(basin, st, cyl=(0, 0, 1.5), name="basin")
    sc.add(cyl(0, 0, 1.28, 1.0, 6.6), r3.m_water("#4A94D6", "#2A62AC"), cast=False, name="pool")
    col = union(cyl(0, 0, 0.36, 2.0, 8.6, 0.4), cone(0, 0, 0.24, 8.6, 10.0, 0.17), cone(0, 0, 0.17, 10.0, 17.4, 0.13), cone(0, 0, 0.13, 17.4, 19.6, 0.40))
    sc.add(col, lt, cyl=(0, 0, 0.3), name="column")
    sc.add(sub(cyl(0, 0, 0.46, 19.4, 21.2, 0.5), cyl(0, 0, 0.37, 20.2, 22.0)), lt, cyl=(0, 0, 0.46), name="bowl")
    sc.add(cyl(0, 0, 0.37, 19.8, 20.9), r3.m_water("#6FB4EA", "#3C82C8"), cast=False, name="bowl water")
    sc.add(union(cone(0, 0, 0.075, 20.6, 24.2, 0.045), sphere(0, 0, 25.0, 0.06)), r3.m_gold("#D9A53A"), cast=False, name="spout")
    # a low dome of white water where the jet falls back into the bowl
    sc.add(ellipsoid(0, 0, 20.9, 0.2, 0.2, 1.3), r3.m_flat("#DCEFFB", 0.04, spec=0.6, gloss=40.0), cast=False, ao=False, name="splash")
    return sc


# ---------------------------------------------------------------------------------------------
# town: houses, manor, market stall, field, statue
# ---------------------------------------------------------------------------------------------

def _crate(sc, x, y, s=0.3, h=4.0, seed=43):
    sc.add(box(x, y, x + s, y + s, 0, h, 0.2), r3.m_planks("#A87B45", 0.1, seed=seed), name="crate")
    sc.add(union(box(x - 0.01, y - 0.01, x + s + 0.01, y + s + 0.01, h - 0.9, h - 0.2, 0.15),
                 box(x - 0.01, y - 0.01, x + s + 0.01, y + s + 0.01, 0.3, 1.0, 0.15)), r3.m_planks("#7A5632", 0.3, seed=seed + 1), cast=False)


def _barrel(sc, x, y, r=0.16, h=5.0):
    sc.add(union(cone(x, y, r * 0.86, 0, h * 0.5, r), cone(x, y, r, h * 0.5, h, r * 0.86)), r3.m_planks("#8A5E36", 0.07, seed=54), cyl=(x, y, r), name="barrel")
    sc.add(union(cyl(x, y, r * 0.95, h * 0.2, h * 0.2 + 0.6, 0.2), cyl(x, y, r * 0.95, h * 0.74, h * 0.74 + 0.6, 0.2)), r3.m_metal("#4E4E56"), cyl=(x, y, r), cast=False)


def house(kind):
    """Town house.  0 = timber frame & clay tiles (smoking chimney), 1 = stone & slate, turned the other
    way, 2 = plank walls & thatch."""
    sc = Scene("decor")
    x0, y0, x1, y1 = -0.9, -0.7, 0.9, 0.6
    if kind == 0:
        T = Tier(2)
        h, rh = 12.5, 7.5
        sc.add(box(x0 - 0.05, y0 - 0.05, x1 + 0.05, y1 + 0.05, 0, 3.0, 0.4), T.base, name="base")
        holes = [g.window(sc, T, "L", y1, -0.42, 7.8, 0.36, 5.4, fw=0.05), g.door(sc, T, "L", y1, 0.42, 0.44, 8.4, False, fw=0.05),
                 g.window(sc, T, "R", x1, -0.05, 7.8, 0.42, 5.4, fw=0.05)]
        sc.add(sub(box(x0, y0, x1, y1, 2.0, h + rh), *holes, g._gable_cut(x0, y0, x1, y1, h, rh, "x")),
               r3.m_plaster("#E9D9B0", "#6E4526", 3.0, h, 0.45, x0, y0, beam=1.3, mid=False, braces=False), name="walls")
        sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.16, thick=1.4), r3.m_tiles("#B04A32", kind="clay", row=2.6, width=2.6), name="roof")
        sc.add(box(x0 - 0.2, -0.11, x1 + 0.2, 0.01, h + rh - 0.9, h + rh + 0.35, 0.4), r3.m_tiles("#9A3E2A", kind="clay", row=9, width=2.6), cast=False, name="ridge")
        # chimney: TerritoryLife.kt starts the smoke at grid (0.47, -0.38), z = 23 (HOUSE_CHIMNEY)
        sc.add(box(0.31, -0.54, 0.63, -0.22, h, 21.6, 0.3), r3.m_rubble("#A39A8C", size=3.0), name="chimney")
        sc.add(box(0.28, -0.57, 0.66, -0.19, 21.3, 22.7, 0.3), r3.m_flat("#6E665C", 0.1), name="chimney cap")
        return sc
    if kind == 1:
        T = Tier(3)
        h, rh = 12.0, 9.0
        sc.add(box(x0 - 0.06, y0 - 0.06, x1 + 0.06, y1 + 0.06, 0, 3.0, 0.4), T.base, name="base")
        holes = [g.door(sc, T, "L", y1, -0.36, 0.46, 8.6, True, fw=0.05), g.window(sc, T, "L", y1, 0.42, 7.4, 0.34, 5.2, fw=0.05),
                 g.window(sc, T, "L", y1, 0.0, 15.2, 0.3, 4.0, fw=0.045, sill=False),
                 g.window(sc, T, "R", x1, -0.36, 7.6, 0.34, 5.2, fw=0.05), g.window(sc, T, "R", x1, 0.2, 7.6, 0.34, 5.2, fw=0.05)]
        sc.add(sub(box(x0, y0, x1, y1, 2.0, h + rh), *holes, g._gable_cut(x0, y0, x1, y1, h, rh, "y")),
               r3.m_stone("#B4AA94", course=3.4, length=1.9), name="walls")
        sc.add(gable(x0, y0, x1, y1, h, rh, "y", over=0.15, thick=1.3), r3.m_tiles("#4A6699", kind="slate", row=2.8, width=3.0), name="roof")
        sc.add(box(-0.06, y0 - 0.19, 0.06, y1 + 0.19, h + rh - 0.9, h + rh + 0.3, 0.4), r3.m_flat("#3C5078", 0.08), cast=False, name="ridge")
        _crate(sc, 0.52, y1 + 0.1, 0.3, 4.0)
        return sc
    T = Tier(1)
    h, rh = 11.0, 9.5
    sc.add(box(x0 - 0.05, y0 - 0.05, x1 + 0.05, y1 + 0.05, 0, 2.6, 0.4), T.base, name="base")
    holes = [g.door(sc, T, "L", y1, -0.4, 0.46, 8.0, False, fw=0.05), g.window(sc, T, "L", y1, 0.4, 7.0, 0.36, 4.8, fw=0.05, arch=False),
             g.window(sc, T, "R", x1, -0.05, 7.0, 0.4, 4.8, fw=0.05, arch=False)]
    sc.add(sub(box(x0, y0, x1, y1, 1.8, h + rh), *holes, g._gable_cut(x0, y0, x1, y1, h, rh, "x")), r3.m_planks("#9A6038", 0.2, seed=55), name="walls")
    sc.add(union(*[box(px - 0.06, py - 0.06, px + 0.06, py + 0.06, 0, h, 0.3) for px, py in ((x0, y1), (x1, y1), (x1, y0))]), T.trim, name="posts")
    sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.22, thick=2.4), r3.m_thatch("#B0965C", row=3.4), name="roof")
    sc.add(box(x0 - 0.26, -0.07, x1 + 0.26, 0.07, h + rh - 1.0, h + rh + 0.6, 0.6), r3.m_thatch("#9C8450", row=2.0, seed=7), cast=False, name="ridge")
    _barrel(sc, 0.62, y1 + 0.26, 0.16, 5.0)
    return sc


def manor():
    """Two-storey manor: pale ashlar, gold string course, blue roof, tall chimney (smoke at grid
    (0.57, -0.43), z = 40: MANOR_CHIMNEY), gold pennant."""
    sc = Scene("decor")
    T = Tier(4)
    x0, y0, x1, y1 = -1.0, -0.8, 1.0, 0.8
    h, rh, zb = 24.0, 10.0, 4.0
    wall = r3.m_stone("#D6CAAC", course=4.4, length=2.2, joint=0.26, var=0.06, mortar="#8F897D")
    frame = r3.m_flat("#7A5C3A", 0.08)
    sc.add(box(x0 - 0.08, y0 - 0.08, x1 + 0.08, y1 + 0.08, 0, zb, 0.4), T.base, name="base")
    holes = [g.door(sc, T, "L", y1, 0.0, 0.5, 9.4, True, frame=T.base, fw=0.06)]
    for a in (-0.56, 0.56):
        holes.append(g.window(sc, T, "L", y1, a, 9.4, 0.32, 5.2, frame=frame, fw=0.045))
    for a in (-0.56, 0.0, 0.56):
        holes.append(g.window(sc, T, "L", y1, a, 18.6, 0.32, 5.4, frame=frame, fw=0.045))
    for zz in (9.4, 18.6):
        for a in (-0.38, 0.38):
            holes.append(g.window(sc, T, "R", x1, a, zz, 0.34, 5.4 if zz > 12 else 5.2, frame=frame, fw=0.045))
    sc.add(sub(box(x0, y0, x1, y1, zb - 1, h + rh), *holes, g._gable_cut(x0, y0, x1, y1, h, rh, "x")), wall, name="walls")
    sc.add(union(*[box(px - 0.08, py - 0.08, px + 0.08, py + 0.08, zb, h, 0.3) for px, py in ((x0, y1), (x1, y1), (x1, y0))]), T.base, name="quoins")
    sc.add(union(box(x0 - 0.02, y1, x1 + 0.02, y1 + 0.03, 13.3, 14.5), box(x1, y0 - 0.02, x1 + 0.03, y1 + 0.02, 13.3, 14.5)), T.gold, cast=False, name="band")
    sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.14, thick=1.5), T.roof, name="roof")
    sc.add(box(x0 - 0.18, -0.05, x1 + 0.18, 0.05, h + rh - 0.8, h + rh + 0.4, 0.4), T.gold, cast=False, name="ridge")
    sc.add(box(0.40, -0.60, 0.74, -0.26, h, 37.6, 0.3), r3.m_stone("#A8A090", course=2.6, length=1.4, seed=34), name="chimney")
    sc.add(box(0.36, -0.64, 0.78, -0.22, 37.2, 38.8, 0.3), r3.m_flat("#6E665C", 0.1), name="chimney cap")
    g.flagpole(sc, T, -0.6, 0.0, h + rh - 1.0, 10.0, color="#E8C547", w=7.0, fh=4.6)
    return sc


def m_stripes(c1, c2, width, origin=0.0):
    """Awning cloth: stripes of `width` grid units across x, starting at grid x = origin."""
    a, b = r3.srgb(c1), r3.srgb(c2)

    def albedo(c):
        k = np.floor((c.x - origin * G) / (width * G))
        fold = 0.93 + 0.10 * r3.vnoise2(c.x * 0.5, c.y * 0.5 + c.z * 0.5, 23)
        return r3.mixc(a, b, (np.mod(k, 2.0) == 1.0).astype(F)) * fold[..., None]

    return Material(albedo, wrap=0.35, spec=0.04)


def stall():
    """Market stall: plank counter with produce, four posts, a striped awning with a scalloped valance, a sack."""
    sc = Scene("decor")
    wood = r3.m_planks("#8E6638", 0.16, seed=56)
    sc.add(union(box(-0.82, -0.5, 0.82, 0.5, 0, 5.6, 0.2), box(-0.86, -0.54, 0.86, 0.54, 5.4, 6.3, 0.25)), wood, name="counter")
    goods = [(union(sphere(-0.56, 0.02, 7.3, 0.085), sphere(-0.42, -0.08, 7.3, 0.085), sphere(-0.47, 0.12, 7.3, 0.085), sphere(-0.49, 0.02, 8.5, 0.08)), "#D9A93C"),
             (union(sphere(-0.1, 0.24, 7.2, 0.075), sphere(0.03, 0.14, 7.2, 0.075), sphere(0.05, 0.32, 7.2, 0.075), sphere(0.0, 0.23, 8.3, 0.07)), "#C23A2E"),
             (union(sphere(0.34, -0.12, 7.5, 0.10), sphere(0.52, 0.0, 7.4, 0.095), sphere(0.38, 0.1, 7.3, 0.085)), "#5E9A3A")]
    for node, col in goods:
        sc.add(node, r3.m_flat(col, 0.1, spec=0.25), name="goods")
    sc.add(box(0.1, -0.42, 0.72, -0.22, 6.3, 8.2, 0.2), r3.m_planks("#A87B45", 0.1, seed=57), name="tray")
    posts = [capsule((px, py, 0), (px, py, 16.8 if py < 0 else 14.4), 0.6) for px in (-0.8, 0.8) for py in (-0.48, 0.48)]
    sc.add(union(*posts), r3.m_planks("#6E4E2E", 0.05, seed=58), name="posts")
    a0, a1, b0, b1 = -0.94, 0.94, -0.62, 0.64
    cloth = m_stripes("#C2352C", "#EDE4CE", (a1 - a0) / 5.0, a0)
    ang = math.degrees(math.atan2(3.5 * ZS, (b1 - b0) * G))
    sc.add(rot(box(a0, b0, a1, b1, 16.9, 17.6, 0.2), "x", -ang, (0, (b0 + b1) / 2, 17.25)), cloth, name="awning")
    zf = 15.5
    st = (a1 - a0) / 5.0
    val = union(box(a0, b1 - 0.03, a1, b1 + 0.03, zf - 1.9, zf + 0.2), repeat(cyl_y(b1 - 0.03, b1 + 0.03, a0 + st / 2, zf - 1.9, st * G / 2 - 0.15), "x", st, 5))
    sc.add(val, cloth, name="valance")
    sc.add(union(ellipsoid(0.98, 0.9, 2.3, 0.15, 0.13, 2.9), sphere(0.98, 0.9, 5.0, 0.05)), r3.m_flat("#B49E72", 0.12, seed=42), name="sack")
    return sc


def field():
    """Vegetable plot: a ploughed bed with four leafy rows and a few pumpkins."""
    sc = Scene("decor")
    g.crop_rows(sc, -0.86, -0.84, 0.86, 0.84, 4, "#5E9638", along="y", h=2.8, seed=3)
    pump = [sphere(-0.69 + 0.43 * k + 0.2, 0.3 - 0.5 * (k % 2), 1.6, 0.07) for k in range(4)]
    sc.add(union(*pump), r3.m_flat("#E0A030", 0.08, spec=0.3), name="pumpkins")
    return sc


def statue():
    """Square statue: a gilded knight raising a sword, on a marble pedestal."""
    sc = Scene("decor")
    T = Tier(5)
    mar = r3.m_marble("#D8D0BE", veins=0.3, course=20.0)
    sc.add(union(box(-0.62, -0.62, 0.62, 0.62, 0, 1.8, 0.3), box(-0.52, -0.52, 0.52, 0.52, 1.8, 7.0, 0.25), box(-0.6, -0.6, 0.6, 0.6, 7.0, 8.4, 0.3),
                 box(-0.42, -0.42, 0.42, 0.42, 8.4, 10.0, 0.25)), mar, name="pedestal")
    sc.add(box(-0.3, 0.52, 0.3, 0.545, 3.0, 5.8, 0.1), T.gold, cast=False, name="plaque")
    gold = r3.m_gold("#E9B93A")
    hx, hy = _ang(8, 0.34)                                    # the raised hand, to screen right
    sx, sy = _ang(18, 0.40)                                   # the sword tip
    body = union(cone(0, 0, 0.21, 10.0, 18.5, 0.115), ellipsoid(0, 0, 19.2, 0.15, 0.15, 3.4), sphere(0, 0, 24.2, 0.095),
                 cone(0, 0, 0.10, 25.0, 26.8, 0.02), capsule((_ang(0, 0.12)[0], _ang(0, 0.12)[1], 21.2), (hx, hy, 25.4), 0.85),
                 capsule((_ang(180, 0.13)[0], _ang(180, 0.13)[1], 21.0), (_ang(190, 0.2)[0], _ang(190, 0.2)[1], 17.0), 0.85))
    sc.add(body, gold, name="knight")
    sc.add(move(rotz(octa(0, 0, 17.4, 0.12, 0.03, 3.0), -45), *_ang(200, 0.21)), gold, name="shield")
    sc.add(union(capsule((hx, hy, 25.0), (sx, sy, 35.0), 0.42), capsule((hx - 0.06, hy + 0.06, 26.4), (hx + 0.08, hy - 0.08, 26.9), 0.4)),
           r3.m_gold("#F6DE8A"), name="sword")
    return sc


# ---------------------------------------------------------------------------------------------
# world map: barbarian camp tent, holy-land temple;  territory: the empty building plot
# ---------------------------------------------------------------------------------------------

def m_canvas(color, stripe, n=3, x0=-0.9, x1=0.9, y0=-0.7, y1=0.7):
    """Tent cloth: seams running from the ridge to the ground, a few pale stripes, soft folds."""
    base, st = r3.srgb(color), r3.srgb(stripe)

    def albedo(c):
        f = (c.y - y0 * G) / ((y1 - y0) * G)                   # along the ridge
        k = f * (n * 2 + 1)
        band = (np.mod(np.floor(k), 2.0) == 1.0).astype(F)
        seam = 1.0 - sstep(0.03, 0.09, np.abs(k - np.rint(k)))
        fold = 0.9 + 0.14 * r3.vnoise2(c.y * 0.45, c.z * 0.2, 27)
        a = r3.mixc(base, st, band * 0.85) * fold[..., None]
        return a * (1.0 - 0.35 * seam)[..., None]

    return Material(albedo, lambda c: r3.vnoise2(c.y * 0.45, c.z * 0.2, 27) * 0.6, bump=0.6, wrap=0.3, spec=0.03)


def tent():
    """Barbarian camp tent (world map): red-brown ridge tent, dark doorway with a tied-back flap, crossed
    poles, guy ropes and a red pennant."""
    sc = Scene("decor")
    x0, y0, x1, y1, h = -0.9, -0.7, 0.9, 0.7, 17.0
    body = gable(x0, y0, x1, y1, 0.0, h, axis="y", over=0.0, thick=0.0)
    door = gable(-0.34, 0.2, 0.34, y1 + 0.2, -0.2, 11.6, axis="y", over=0.0, thick=0.0)
    sc.add(sub(body, door), m_canvas("#A8472E", "#E6CFA0"), name="tent")
    sc.add(gable(-0.34, 0.25, 0.34, y1 - 0.2, 0.0, 11.6, axis="y", over=0.0, thick=0.0), r3.m_flat("#1E1410", 0.05), cast=False, name="inside")
    # the flap, folded back to the left of the doorway
    sc.add(rot(box(-0.36, y1 - 0.01, -0.06, y1 + 0.035, 0.0, 10.4, 0.15), "y", 14.0, (-0.36, y1, 0.0)), r3.m_cloth("#E2C898"), cast=False, name="flap")
    pole = r3.m_planks("#5E4630", 0.05, seed=59)
    sc.add(union(capsule((-0.2, y1 + 0.02, 0), (0.07, y1 + 0.02, h + 3.2), 0.5), capsule((0.2, y1 + 0.02, 0), (-0.07, y1 + 0.02, h + 3.2), 0.5),
                 capsule((0.0, y0 - 0.02, h - 1.0), (0.0, y0 - 0.02, h + 2.4), 0.5)), pole, name="poles")
    ropes = [capsule((0.0, y1, h - 0.6), (0.0, y1 + 0.75, 0.2), 0.2), capsule((x1 * 0.5, 0.0, h * 0.5), (x1 + 0.42, 0.0, 0.2), 0.2),
             capsule((x1 * 0.5, -0.5, h * 0.5), (x1 + 0.42, -0.5, 0.2), 0.2), capsule((x0 * 0.5, 0.45, h * 0.5), (x0 - 0.42, 0.45, 0.2), 0.2)]
    sc.add(union(*ropes), r3.m_flat("#CDBE96", 0.08), cast=False, name="ropes")
    T = Tier(1)
    g.flagpole(sc, T, 0.0, 0.0, h - 1.0, 12.5, color="#C8342A", w=8.0, fh=5.4)
    return sc


def temple():
    """Holy-land temple (world map, building-size sprite: 128 x 160, the anchor is the centre of the
    stylobate).  Marble steps and columns, a lit doorway, gilded roof, a glowing star."""
    sc = Scene("building")
    T = Tier(5)
    o = 2.0                                                  # grid (2, 2) of a building scene is the anchor
    mar = T.wall()
    sc.add(box(o - 1.7, o - 1.3, o + 1.7, o + 1.3, 0, 3.0, 0.3), r3.m_marble("#D8D1C0", seed=9, veins=0.2), name="step 1")
    sc.add(box(o - 1.45, o - 1.1, o + 1.45, o + 1.1, 3.0, 5.0, 0.3), r3.m_marble("#E6DFD0", seed=8, veins=0.2), name="step 2")
    # cella with the glowing doorway on the left-front face
    f = o + 0.55
    hole = g.opening("L", f, o, 5.0, 0.62, 15.0, True, -0.2, 0.3)
    sc.add(sub(box(o - 0.9, o - 0.7, o + 0.9, f, 5.0, 24.0), hole), r3.m_marble("#DDD6C6", veins=0.25), name="cella")
    sc.add(g.opening("L", f, o, 5.0, 0.62, 15.0, True, -0.16, -0.1), r3.m_glow("#FFD98A", power=2.0, bars=False), cast=False, ao=False, name="door light")
    sc.add(sub(g.opening("L", f, o, 5.0, 0.78, 16.2, True, -0.02, 0.035), g.opening("L", f, o, 4.0, 0.62, 16.0, True, -0.3, 0.3)), T.gold, name="door frame")
    cols = [(1.1, -0.85), (-1.1, 0.85), (-0.37, 0.85), (0.37, 0.85), (1.1, 0.85), (1.1, 0.0)]
    shafts, caps = [], []
    for cx, cy in cols:
        shafts.append(cone(o + cx, o + cy, 0.16, 5.0, 23.0, 0.135))
        caps.append(union(cyl(o + cx, o + cy, 0.2, 5.0, 6.2, 0.3), cyl(o + cx, o + cy, 0.21, 22.6, 24.0, 0.3)))
    for i, sh in enumerate(shafts):
        sc.add(star(sh, o + cols[i][0], o + cols[i][1], 10, 0.35), mar, cyl=(o + cols[i][0], o + cols[i][1], 0.16), name="column")
    sc.add(union(*caps), r3.m_marble("#E6DFD0", seed=8, veins=0.1), name="capitals")
    sc.add(box(o - 1.3, o - 1.0, o + 1.3, o + 1.0, 24.0, 27.0, 0.25), mar, name="entablature")
    sc.add(union(box(o - 1.3, o + 1.0, o + 1.3, o + 1.03, 25.0, 26.2), box(o + 1.3, o - 1.0, o + 1.33, o + 1.0, 25.0, 26.2)), T.gold, cast=False, name="frieze")
    sc.add(sub(box(o - 1.34, o - 1.04, o + 1.34, o + 1.04, 26.6, 38.4), g._gable_cut(o - 1.34, o - 1.04, o + 1.34, o + 1.04, 27.0, 10.6, "x")), mar, name="pediments")
    sc.add(gable(o - 1.4, o - 1.1, o + 1.4, o + 1.1, 27.0, 11.0, "x", over=0.06, thick=1.4), T.roof, name="roof")
    sc.add(box(o - 1.5, o - 0.05, o + 1.5, o + 0.05, 37.2, 38.6, 0.4), T.gold, cast=False, name="ridge")
    # gem on the right-front pediment
    sc.add(octa(o + 1.37, o + 0.0, 31.2, 0.05, 0.12, 2.6), r3.m_glow("#49C2F2", power=1.3, bars=False), cast=False, ao=False, name="gem")
    sc.add(cyl_x(o + 1.345, o + 1.365, o, 31.2, 3.3), T.gold, cast=False, name="gem setting")
    # star finial
    sc.add(capsule((o, o, 38.0), (o, o, 45.0), 0.5), T.gold, name="finial")
    glow = r3.m_glow("#FFE27A", power=1.9, bars=False)
    sc.add(union(octa(o, o, 48.4, 0.07, 0.07, 5.2), rotz(octa(o, o, 48.4, 0.26, 0.05, 1.4), -45, o, o)), glow, cast=False, ao=False, name="star")
    return sc


def site():
    """Empty building plot under construction: dirt pad, corner stakes with pennants and a rope, a pole
    scaffold along the back, a small treadwheel crane, timber, cut stone, sand and a crate.  The game draws
    a ghost of the future building over the middle, so the tall things stand at the back edges."""
    sc, T = g.building(1)
    wood = r3.m_planks("#9A7444", 0.08, seed=62)
    pale = r3.m_planks("#C9A66C", 0.12, seed=63)
    dark = r3.m_planks("#6A4C2E", 0.08, seed=64)
    # corner stakes, rope, pennants
    corners = [(0.3, 0.3), (3.7, 0.3), (3.7, 3.7), (0.3, 3.7)]
    sc.add(union(*[capsule((x, y, -0.5), (x, y, 9.5), 0.65) for x, y in corners]), dark, name="stakes")
    rope = [capsule((corners[i][0], corners[i][1], 7.0), (corners[(i + 1) % 4][0], corners[(i + 1) % 4][1], 7.0), 0.26) for i in range(4)]
    sc.add(union(*rope), r3.m_flat("#D8C9A0", 0.08), cast=False, name="rope")
    for x, y in corners:
        sc.add(r3.flag(x, y, 8.0, 5.6, 4.2, taper=0.9, wave=0.5), r3.m_cloth("#D03A30"), cast=False, name="pennant")
    # scaffold along the back-left edge (x = 0.85), two bays, a plank deck and a ladder
    px = 0.85
    ys = (0.9, 1.95, 3.0)
    poles = [capsule((px, y, -0.5), (px, y, 25.0), 0.6) for y in ys] + [capsule((px + 0.5, y, -0.5), (px + 0.5, y, 25.0), 0.6) for y in ys]
    rails = []
    for zz in (9.0, 17.0, 23.5):
        rails += [capsule((px, ys[0], zz), (px, ys[-1], zz), 0.45), capsule((px + 0.5, ys[0], zz), (px + 0.5, ys[-1], zz), 0.45)]
        rails += [capsule((px, y, zz), (px + 0.5, y, zz), 0.45) for y in ys]
    rails += [capsule((px + 0.5, ys[0], 0.3), (px + 0.5, ys[1], 9.0), 0.4), capsule((px + 0.5, ys[2], 0.3), (px + 0.5, ys[1], 9.0), 0.4),
              capsule((px + 0.5, ys[0], 17.0), (px + 0.5, ys[1], 9.0), 0.4), capsule((px + 0.5, ys[2], 17.0), (px + 0.5, ys[1], 9.0), 0.4)]
    sc.add(union(*poles, *rails), wood, name="scaffold")
    sc.add(union(box(px - 0.08, ys[0] - 0.1, px + 0.58, ys[-1] + 0.1, 17.4, 18.2, 0.2), box(px - 0.08, ys[0] - 0.1, px + 0.58, ys[1] + 0.2, 9.4, 10.2, 0.2)), pale, name="decks")
    lad = [capsule((px + 0.62, 3.25, 0.0), (px + 0.56, 3.05, 17.6), 0.35), capsule((px + 0.62, 3.55, 0.0), (px + 0.56, 3.35, 17.6), 0.35)]
    lad += [capsule((px + 0.62 - 0.06 * t, 3.25 - 0.2 * t, 17.6 * t), (px + 0.62 - 0.06 * t, 3.55 - 0.2 * t, 17.6 * t), 0.28) for t in (0.14, 0.3, 0.46, 0.62, 0.78, 0.94)]
    sc.add(union(*lad), pale, cast=False, name="ladder")
    # treadwheel crane at the back-right
    cx, cy = 3.05, 0.95
    mast = [capsule((cx, cy, 0.0), (cx, cy, 30.0), 0.95), capsule((cx - 0.55, cy - 0.1, 0.0), (cx, cy, 14.0), 0.6), capsule((cx + 0.1, cy + 0.6, 0.0), (cx, cy, 14.0), 0.6),
            capsule((cx + 0.45, cy - 0.45, 0.0), (cx, cy, 14.0), 0.6)]
    jx, jy, jz = cx - 0.95, cy + 0.85, 35.0                    # the jib's tip, over the plot
    mast += [capsule((cx + 0.2, cy - 0.18, 25.0), (jx, jy, jz), 0.75), capsule((cx, cy, 30.0), (cx - 0.4, cy + 0.36, 32.4), 0.5)]
    sc.add(union(*mast), dark, name="crane")
    wheel = sub(cyl_x(cx + 0.36, cx + 0.62, cy - 0.02, 6.4, 5.6), cyl_x(cx + 0.3, cx + 0.7, cy - 0.02, 6.4, 4.5))
    spokes = union(box(cx + 0.44, cy - 0.3, cx + 0.54, cy + 0.26, 6.0, 6.8, 0.2), box(cx + 0.44, cy - 0.06, cx + 0.54, cy + 0.02, 1.4, 11.4, 0.2),
                   cyl_x(cx - 0.05, cx + 0.66, cy - 0.02, 6.4, 0.9))
    sc.add(union(wheel, spokes), wood, name="treadwheel")
    sc.add(union(capsule((jx, jy, jz), (jx, jy, 17.5), 0.22), capsule((cx + 0.2, cy - 0.18, 25.0), (cx + 0.5, cy - 0.02, 11.5), 0.22)), r3.m_flat("#CDBE96", 0.08), cast=False, name="ropes")
    block = r3.m_stone("#B4AA94", course=4.0, length=1.6, seed=36)
    sc.add(box(jx - 0.22, jy - 0.22, jx + 0.22, jy + 0.22, 12.0, 16.4, 0.3), block, name="load")
    sc.add(union(capsule((jx - 0.2, jy, 16.4), (jx, jy, 17.8), 0.2), capsule((jx + 0.2, jy, 16.4), (jx, jy, 17.8), 0.2)), r3.m_flat("#CDBE96", 0.08), cast=False)
    # materials on the ground: boards, cut stone, sand with a shovel, a crate
    sc.add(union(box(1.9, 2.95, 3.0, 3.3, 0, 1.5, 0.15), box(1.96, 2.99, 2.94, 3.26, 1.5, 2.9, 0.15), rotz(box(1.9, 3.0, 3.0, 3.12, 2.9, 3.9, 0.15), 6, 2.4, 3.1)), pale, name="boards")
    sc.add(union(box(3.0, 2.05, 3.42, 2.47, 0, 3.4, 0.25), box(3.05, 2.52, 3.47, 2.94, 0, 3.4, 0.25), box(3.02, 2.2, 3.44, 2.62, 3.4, 6.6, 0.25)), block, name="stone")
    sc.add(lumpy(ellipsoid(1.75, 2.25, 0.0, 0.42, 0.42, 5.0), 0.5, 0.4, 2), r3.m_ground("dirt", "#C9B078"), name="sand")
    sc.add(capsule((1.84, 2.2, 3.4), (2.3, 1.88, 11.0), 0.45), dark, name="shovel")
    sc.add(rot(box(1.72, 2.12, 1.92, 2.3, 1.6, 4.6, 0.2), "x", -18, (1.82, 2.2, 3.4)), r3.m_metal("#7A7E88"), cast=False, name="blade")
    _crate(sc, 2.35, 1.2, 0.46, 4.6, seed=65)
    return sc


# ---------------------------------------------------------------------------------------------
# registry
# ---------------------------------------------------------------------------------------------

SPRITES = {
    "deco_tree_oak_a": lambda: tree_oak(1.0, "#3C6A38", "#4A3C30", seed=0),
    "deco_tree_oak_b": lambda: tree_oak(0.8, "#4E7A3A", "#54443A", seed=1, spread=0.95, tall=1.04),
    "deco_tree_oak_c": lambda: tree_oak(1.12, "#33603A", "#43362C", seed=2, spread=1.06, tall=0.98),
    "deco_tree_pine_a": lambda: tree_pine(1.0, "#2A5644"),
    "deco_tree_pine_b": lambda: tree_pine(0.82, "#35624A", seed=3),
    "deco_tree_birch": tree_birch,
    "deco_bush": bush,
    "deco_rock_a": lambda: rock(0),
    "deco_rock_b": lambda: rock(1),
    "deco_hay": hay,
    "deco_lamp": lamp,
    "deco_well": well,
    "deco_signpost": signpost,
    "deco_fountain": fountain,
    "deco_house_a": lambda: house(0),
    "deco_house_b": lambda: house(1),
    "deco_house_c": lambda: house(2),
    "deco_stall": stall,
    "deco_field": field,
    "deco_manor": manor,
    "deco_statue": statue,
    "deco_tent": tent,
    "deco_temple": temple,
    "deco_site": site,
}
