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
    for deg, ln, zz in ((193, 0.46, 20.0), (-13, 0.48, 19.0), (95, 0.22, 23.0), (268, 0.20, 18.0)):
        dx, dy = _ang(deg + 17 * seed, ln * k * spread)
        limbs.append(capsule((0, 0, fork - 2.5 * k), (dx, dy, zz * k * tall), 1.05 * k))
    sc.add(union(cone(0, 0, 0.10 * k, 0, fork + 1.5 * k, 0.07 * k), cone(0, 0, 0.17 * k, -0.5, 3.0 * k, 0.10 * k), *limbs), bark,
           cyl=(0, 0, 0.10 * k), name="trunk")
    # crown: (direction on screen, distance, height of the centre, plan radius, vertical squash)
    clumps = [(193, 0.54, 20.0, 0.30, 0.70), (150, 0.40, 23.5, 0.29, 0.74), (-13, 0.56, 19.0, 0.29, 0.70), (30, 0.42, 23.0, 0.29, 0.74),
              (95, 0.38, 22.0, 0.33, 0.74), (245, 0.36, 19.5, 0.27, 0.68), (297, 0.34, 20.5, 0.26, 0.68),
              (280, 0.08, 26.0, 0.40, 0.72), (140, 0.22, 29.5, 0.29, 0.74), (15, 0.20, 30.5, 0.26, 0.76)]
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


def m_bale(color, yc=0.0, zc=0.0):
    """Round straw bale lying along y: straw wound around the curved side, a spiral on the flat ends."""
    base = r3.srgb(color)

    def albedo(c):
        end = np.abs(c.ny) > 0.7
        rad = np.hypot(c.x, c.z - zc * ZS)
        ring = 0.5 + 0.5 * np.sin(rad * 2.6 + np.arctan2(c.z - zc * ZS, c.x) * 1.0)
        wound = r3.vnoise2(c.y * 2.2, np.arctan2(c.z - zc * ZS, c.x) * 2.0, 44)
        k = np.where(end, 0.74 + 0.30 * ring, 0.74 + 0.34 * wound) * (0.92 + 0.16 * r3.vnoise3(c.x * 0.3, c.y * 0.3, c.z * 0.3, 45))
        return base[None, :] * k[..., None]

    def height(c):
        return r3.vnoise2(c.u * 1.6, c.v * 0.5, 46) * 0.5

    return Material(albedo, height, bump=0.7, wrap=0.2)


def hay():
    """Hay: a big round bale on its side (spiral end towards the light) and a small one leaning on it."""
    sc = Scene("decor")
    r = 6.0
    zc = r / ZS - 0.3
    sc.add(cyl_y(-0.34, 0.30, 0.0, zc, r, 0.6), m_bale("#A88C4A", zc=zc), name="bale")
    sc.add(union(cyl_y(-0.16, -0.10, 0.0, zc, r + 0.2, 0.1), cyl_y(0.07, 0.13, 0.0, zc, r + 0.2, 0.1)), r3.m_flat("#6E5220", 0.1), cast=False, name="twine")
    sc.add(lumpy(ellipsoid(0.34, 0.36, 0.0, 0.26, 0.2, 2.4), 0.5, 0.5, 6), r3.m_thatch("#A88C4A", row=1.6, seed=47), name="loose hay")
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
    sc.add(union(capsule((0, 0, 0), (0, 0, 44.2), 1.25), cyl(0, 0, 0.10, 0, 1.6, 0.3)), wood, name="post")
    d = 0.075                               # the board hangs in front of the post (towards the camera)
    L = 22.0 / G
    zb = 26.0 + 2.0 * d * 7.0                # moving it towards the camera lowers it on screen: lift it back
    sc.add(move(rotz(box(-L, -0.035, L, 0.035, zb, zb + 16.0), -45), d, d), m_board(), cast=True, name="board")
    # two battens behind the board and four nail heads on it
    sc.add(move(rotz(union(box(-L + 0.1, -0.06, -L + 0.2, 0.0, zb - 0.6, zb + 16.6), box(L - 0.2, -0.06, L - 0.1, 0.0, zb - 0.6, zb + 16.6)), -45), d - 0.03, d - 0.03),
           wood, cast=False, name="battens")
    nails = [sphere(d + 0.03 + sx * (L - 0.12) * math.sqrt(0.5), d + 0.03 - sx * (L - 0.12) * math.sqrt(0.5), zz, 0.035)
             for sx in (-1, 1) for zz in (zb + 2.2, zb + 13.8)]
    sc.add(union(*nails), r3.m_metal("#4A4A54"), cast=False, name="nails")
    return sc


def fountain():
    """Town fountain.  TerritoryLife.kt animates drops from the upper bowl (z = 21, FOUNTAIN_TOP) down to
    the pool (z = 8, FOUNTAIN_BASIN, radius about 1) and glints on the pool."""
    sc = Scene("decor")
    st = r3.m_stone("#B4AC9A", course=3.0, length=1.8, joint=0.3)
    lt = r3.m_stone("#C6BEAC", course=3.0, length=1.6, joint=0.25, seed=33)
    basin = sub(union(cyl(0, 0, 1.41, 0, 6.4, 0.3), cyl(0, 0, 1.46, 0, 1.5, 0.4), cyl(0, 0, 1.46, 5.6, 7.8, 0.6)), cyl(0, 0, 1.24, 2.0, 9.0))
    sc.add(basin, st, cyl=(0, 0, 1.45), name="basin")
    sc.add(cyl(0, 0, 1.24, 1.0, 6.6), r3.m_water("#4A94D6", "#2A62AC"), cast=False, name="pool")
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
    sc.add(gable(x0, y0, x1, y1, h, rh, "x", over=0.22, thick=2.4), T.roof, name="roof")
    sc.add(box(x0 - 0.26, -0.07, x1 + 0.26, 0.07, h + rh - 1.0, h + rh + 0.6, 0.6), T.trim, cast=False, name="ridge")
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
        band = (np.abs(np.mod(k, 2.0) - 1.5) < 0.2).astype(F)          # a narrow pale stripe in every second panel
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
    # pediments: marble triangles standing just proud of the roof's two ends
    ped = union(box(o + 1.43, o - 1.0, o + 1.50, o + 1.0, 26.8, 38.0), box(o - 1.50, o - 1.0, o - 1.43, o + 1.0, 26.8, 38.0))
    sc.add(sub(ped, g._gable_cut(o - 1.6, o - 1.0, o + 1.6, o + 1.0, 27.0, 9.8, "x")), mar, name="pediments")
    sc.add(gable(o - 1.4, o - 1.1, o + 1.4, o + 1.1, 27.0, 11.0, "x", over=0.06, thick=1.4), T.roof, name="roof")
    sc.add(box(o - 1.5, o - 0.05, o + 1.5, o + 0.05, 37.2, 38.6, 0.4), T.gold, cast=False, name="ridge")
    # gem on the right-front pediment
    sc.add(octa(o + 1.52, o + 0.0, 31.0, 0.05, 0.12, 2.4), r3.m_glow("#49C2F2", power=1.3, bars=False), cast=False, ao=False, name="gem")
    sc.add(cyl_x(o + 1.495, o + 1.515, o, 31.0, 3.1), T.gold, cast=False, name="gem setting")
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
# city wall kit: materials that repeat with the piece length
#
# TerritoryScene.kt puts a wall piece every 2 grid units, so every pattern on a piece must be periodic
# with that length: block joints, the per-block shade and the slow noise all wrap (lattice indices are
# taken modulo the number of cells in a piece).  The stock materials only line up their joints.
# ---------------------------------------------------------------------------------------------

PER = 2.0 * G                      # length of a wall piece, world units


def _pnoise(u, v, nu, nv, seed):
    """Value noise on a lattice that wraps every nu cells in u and (when nv > 0) every nv cells in v."""
    iu, iv = np.floor(u), np.floor(v)
    fu, fv = u - iu, v - iv
    fu = fu * fu * (3.0 - 2.0 * fu)
    fv = fv * fv * (3.0 - 2.0 * fv)
    u0, u1 = np.mod(iu, nu), np.mod(iu + 1.0, nu)
    v0, v1 = (np.mod(iv, nv), np.mod(iv + 1.0, nv)) if nv else (iv, iv + 1.0)
    a, b = r3.hash2(u0, v0, seed), r3.hash2(u1, v0, seed)
    c, d = r3.hash2(u0, v1, seed), r3.hash2(u1, v1, seed)
    return a + (b - a) * fu + (c - a) * fv + (a - b - c + d) * fu * fv


def _slow(c, seed, cells=12):
    """Low-frequency tone variation, periodic along the wall (and both ways on the wall-walk)."""
    k = cells / PER
    side = _pnoise((c.u + G) * k, c.v * k, cells, 0, seed)
    top = _pnoise((c.u + G) * k, (c.v + G) * k, cells, cells, seed)
    return np.where(c.top, top, side)


def _lin(color):
    return r3.srgb(color) if isinstance(color, str) else np.asarray(color, F)


def _pblocks(c, ch, n, pave, seed):
    """Running-bond blocks on the faces (n per piece), square flags on tops (pave per piece, pave even).
    Returns the per-block random number and the distance to the nearest joint (world units)."""
    bl, cs = PER / n, PER / pave
    vv = c.v / ch
    row = np.floor(vv)
    fv = vv - row
    ri = row.astype(np.int32)
    uo = (c.u + G) / bl + 0.5 * (ri & 1)
    col = np.floor(uo)
    fu = uo - col
    rnd = r3.hash2(np.mod(col, n), ri, seed + 1)
    m = np.minimum(np.minimum(fu, 1.0 - fu) * bl, np.minimum(fv, 1.0 - fv) * ch)
    tv = (c.v + G) / cs
    trow = np.floor(tv)
    tfv = tv - trow
    tri = trow.astype(np.int32)
    tu = (c.u + G) / cs + 0.5 * (tri & 1)
    tcol = np.floor(tu)
    tfu = tu - tcol
    trnd = r3.hash2(np.mod(tcol, pave), np.mod(trow, pave), seed + 2)
    tm = np.minimum(np.minimum(tfu, 1.0 - tfu), np.minimum(tfv, 1.0 - tfv)) * cs
    return np.where(c.top, trnd, rnd), np.where(c.top, tm, m)


def p_stone(color, course=4.0, n=4, var=0.13, joint=0.4, seed=1, spec=0.05, pave=6):
    """Cut stone like r3.m_stone, periodic with the wall piece: n blocks per piece."""
    base = _lin(color)
    mort = base * 0.5
    ch = course * ZS

    def albedo(c):
        rnd, m = _pblocks(c, ch, n, pave, seed)
        a = r3._vary(base, rnd, var) * (0.90 + 0.2 * _slow(c, seed + 3))[..., None]
        a = a * (0.90 + 0.14 * sstep(joint, joint + 1.3, m))[..., None]
        return r3.mixc(a, mort, (1.0 - sstep(joint * 0.35, joint, m)) * 0.75)

    def height(c):
        _, m = _pblocks(c, ch, n, pave, seed)
        return sstep(joint * 0.3, joint * 1.5, m) * 0.32

    return Material(albedo, height, spec=spec, gloss=18.0)


def p_marble(color, vein="#8E88A6", course=9.0, n=2, seed=7, veins=0.3, pave=4):
    """White marble like r3.m_marble, periodic with the wall piece."""
    base, vc = _lin(color), r3.srgb(vein)
    ch = course * ZS

    def albedo(c):
        w = _slow(c, seed, 6)
        ph = (c.u + G) / PER * 2.0 * math.pi
        t1 = np.abs(np.sin(ph * 2.0 + c.v * 0.11 + w * 9.0))
        t2 = np.abs(np.sin(ph * 3.0 - c.v * 0.28 + w * 14.0 + 2.0))
        vm = (1.0 - sstep(0.0, 0.16, t1)) * veins + (1.0 - sstep(0.0, 0.10, t2)) * veins * 0.5
        a = base[None, :] * (0.93 + 0.09 * _slow(c, seed + 1, 12))[..., None]
        a = r3.mixc(a, vc, np.clip(vm, 0.0, 1.0))
        _, m = _pblocks(c, ch, n, pave, seed)
        return a * (0.70 + 0.30 * sstep(0.1, 0.42, m))[..., None]

    def height(c):
        _, m = _pblocks(c, ch, n, pave, seed)
        return sstep(0.1, 0.5, m) * 0.2

    return Material(albedo, height, spec=0.35, gloss=38.0)


def p_rubble(color, n=9, var=0.2, seed=2):
    """Field stone like r3.m_rubble, periodic with the wall piece: n Voronoi cells per piece."""
    base = _lin(color)
    size = PER / n

    def cells(c):
        u = (c.u + G) / size
        v = np.where(c.top, (c.v + G) / size, c.v / size)
        iu, iv = np.floor(u), np.floor(v)
        f1 = np.full(u.shape, 9.0, F)
        f2 = np.full(u.shape, 9.0, F)
        rid = np.zeros(u.shape, F)
        for dj in (-1.0, 0.0, 1.0):
            for di in (-1.0, 0.0, 1.0):
                cu, cv = iu + di, iv + dj
                wu, wv = np.mod(cu, n), np.where(c.top, np.mod(cv, n), cv)
                px = cu + 0.15 + 0.7 * r3.hash2(wu, wv, seed)
                py = cv + 0.15 + 0.7 * r3.hash2(wu, wv, seed + 1)
                dd = np.hypot(u - px, (v - py) * 1.25)
                near = dd < f1
                f2 = np.where(near, f1, np.minimum(f2, dd))
                rid = np.where(near, r3.hash2(wu, wv, seed + 2), rid)
                f1 = np.where(near, dd, f1)
        return rid, (f2 - f1) * size

    def albedo(c):
        rid, e = cells(c)
        a = r3._vary(base, rid, var, 0.07) * (0.9 + 0.2 * _slow(c, seed))[..., None]
        return r3.mixc(a, base * 0.5, (1.0 - sstep(0.2, 0.8, e)) * 0.75)

    def height(c):
        _, e = cells(c)
        return sstep(0.15, 1.6, e) * 0.5

    return Material(albedo, height, spec=0.03, gloss=14.0)


def p_flat(color, noise=0.07, spec=0.0, seed=12):
    """Plain surface with a little periodic noise (beams and bands that run the length of a piece)."""
    base = _lin(color)
    return Material(lambda c: base[None, :] * (1.0 - noise + 2.0 * noise * _slow(c, seed, 16))[..., None], spec=spec)


def m_logs(color, pitch, along="x", seed=0):
    """Palisade logs: one shade per log (taken from its position along the wall, so a log never changes
    tone across its round face) and bark furrows running up it."""
    base = r3.srgb(color)

    def albedo(c):
        a = c.x if along == "x" else c.y
        k = np.mod(np.floor(a / (pitch * G)), round(2.0 / pitch))
        rnd = r3.hash2(k, k * 0 + 3, seed)
        fur = r3.vnoise2((c.x + c.y) * 1.3 + rnd * 40.0, c.z * 0.09, seed + 1)
        tip = sstep(0.25, 0.7, c.nz)                                   # the axe-cut points are paler
        col = r3._vary(base, rnd, 0.17, 0.05) * (0.80 + 0.36 * fur)[..., None]
        return col * (1.0 + 0.45 * tip)[..., None]

    return Material(albedo, lambda c: r3.vnoise2((c.x + c.y) * 1.3, c.z * 0.09, seed + 1) * 0.5, bump=0.8, spec=0.03)


def _mean(mat):
    """Mean linear albedo of a material on a plain wall face (to copy a Tier colour into a periodic material)."""
    u, v = np.meshgrid(np.linspace(0.0, 80.0, 96, dtype=F), np.linspace(0.0, 40.0, 48, dtype=F))
    u, v = u.ravel(), v.ravel()
    z, o = np.zeros_like(u), np.ones_like(u)
    c = r3.Ctx(u, z, v, z, o, z, u, v, z.astype(bool), o.astype(bool), np.zeros(3), np.zeros(3))
    return np.asarray(mat.albedo(c), np.float64).reshape(-1, 3).mean(axis=0)


def _like(make, target):
    """make(linear base colour) -> material; returns the one whose mean albedo equals `target`'s.
    This is how the wall pieces follow gen_sprites3d.Tier when its palette is retuned."""
    return make((_mean(target) / _mean(make(np.ones(3, F)))).astype(F))


WALL_H = 12.0                      # height of the wall-walk (as in the old art)
MERLON_H = 3.4
EXT = 0.06                         # each piece runs this far past its cell at the far end (see wall_piece)


def _wall_half(t):
    """Half thickness of the wall: the palisade is one row of logs, the stone walls are 1 grid unit thick."""
    return 0.155 if t == 1 else 0.5


def _wall_mat(t, T):
    """The periodic material of a tier's curtain wall, matched to Tier(t).curtain."""
    if t == 2:
        return _like(lambda b: p_rubble(b), T.curtain)
    if t == 3:
        return _like(lambda b: p_stone(b, course=4.0, n=4), T.curtain)
    if t == 4:
        return _like(lambda b: p_stone(b, course=4.0, n=4, joint=0.32, var=0.07), T.curtain)
    return _like(lambda b: p_marble(b), T.curtain)


def wall_piece(t, along="x"):
    """Wall segment, 2 grid units long (64 x 64 sprite, the anchor is the middle of its base line).
    along = 'x' runs right-down on screen, 'y' runs left-down.

    Tiling (TerritoryScene.kt draws a piece every 2 units, the far ones first):
    - flat cut ends, every feature and every texture repeats with the piece length;
    - the body runs EXT past the far end, under the next piece, so that the next piece's anti-aliased
      edge blends with the same surface and not with a dark end cap (no hairline at the joints);
    - no outline; the two neighbours are phantoms (shadow and occlusion only) and the baked ground
      shadow is clipped to the piece's own cell, so the shadows of adjacent pieces meet without overlap."""
    cell = (-1.0, -50.0, 1.0, 50.0) if along == "x" else (-50.0, -1.0, 50.0, 1.0)
    sc = Scene("wall", clip=cell, outline=0.0)
    parts = wall_parts(t, along)
    for i, (node, mat, cast) in enumerate(parts):
        # only the body darkens its surroundings: with occlusion from the merlons the narrow wall-walk
        # between them turns dark at game size
        sc.add(node, mat, cast=cast, ao=(i == 0))
    solid = union(*[node for node, _, cast in parts if cast])
    for k in (-2.0, 2.0):                                    # the neighbouring pieces: shadow and occlusion only
        sc.add(move(solid, k if along == "x" else 0.0, 0.0 if along == "x" else k), visible=False)
    return sc


def wall_parts(t, along="x"):
    """The solids of one wall piece: [(node, material, casts a shadow)], the body first."""
    T = Tier(t)

    def B(a0, a1, c0, c1, z0, z1, r=0.0):
        """Box from a0..a1 along the wall and c0..c1 across it (+c is the side that faces the camera)."""
        return box(a0, c0, a1, c1, z0, z1, r) if along == "x" else box(c0, a0, c1, a1, z0, z1, r)

    def rep(node, step, n):
        return repeat(node, along, step, n)

    parts = []                                               # (node, material, casts a shadow)
    a1 = 1.0 + EXT
    if t == 1:
        r, n = 0.155, 6
        st = 2.0 / n
        a0 = -1.0 + st / 2
        log = union(cyl(a0, 0, r, -0.5, 10.8), cone(a0, 0, r, 10.8, 13.6)) if along == "x" else union(cyl(0, a0, r, -0.5, 10.8), cone(0, a0, r, 10.8, 13.6))
        parts.append((rep(log, st, n), m_logs("#7E5638", st, along, seed=24), True))
        parts.append((B(-1.0, a1, -0.05, 0.05, 0, 10.6), p_flat("#3E2A1A", 0.05), True))             # closes the chinks between logs
        parts.append((union(B(-1.0, a1, r * 0.78, r * 1.22, 6.3, 7.8, 0.3), B(-1.0, a1, -r * 1.22, -r * 0.78, 6.3, 7.8, 0.3)), p_flat("#4E3420", 0.08), False))
    else:
        wm = _wall_mat(t, T)
        parts.append((B(-1.0, a1, -0.5, 0.5, 0, WALL_H), wm, True))
        if t >= 3:                                           # darker plinth course, as on the castle's curtain
            pm = _like(lambda b: p_marble(b, seed=9), T.base) if t >= 5 else _like(lambda b: p_stone(b, course=2.6, n=5, seed=31), T.base)
            parts.append((union(B(-1.0, a1, 0.5, 0.54, 0, 2.6, 0.2), B(-1.0, a1, -0.54, -0.5, 0, 2.6, 0.2)), pm, False))
        m0 = -1.0 + 1.0 / 3.0
        if t == 2:
            # timber wall-plates and a parapet of plank mantlets
            beam = p_flat("#5E3F26", 0.08)
            parts.append((union(B(-1.0, a1, 0.42, 0.56, WALL_H - 1.5, WALL_H + 0.5, 0.3), B(-1.0, a1, -0.56, -0.42, WALL_H - 1.5, WALL_H + 0.5, 0.3)), beam, True))
            mant = union(rep(B(m0 - 0.2, m0 + 0.2, 0.40, 0.50, WALL_H, WALL_H + 3.8, 0.2), 2.0 / 3.0, 3),
                         rep(B(m0 - 0.2, m0 + 0.2, -0.50, -0.40, WALL_H, WALL_H + 3.8, 0.2), 2.0 / 3.0, 3))
            parts.append((mant, r3.m_planks("#8A5E38", 0.133, seed=25, relief=0.5), True))
        else:
            # merlons on both edges of the wall-walk (no continuous parapet: its shaded inner face would
            # close the walk into a dark slot on the walls that run left-down)
            mer = union(rep(B(m0 - 0.19, m0 + 0.19, 0.29, 0.5, WALL_H - 0.3, WALL_H + MERLON_H, 0.18), 2.0 / 3.0, 3),
                        rep(B(m0 - 0.19, m0 + 0.19, -0.5, -0.29, WALL_H - 0.3, WALL_H + MERLON_H, 0.18), 2.0 / 3.0, 3))
            parts.append((mer, wm, True))
            if t >= 4:                                       # gold string course on both faces
                parts.append((union(B(-1.0, a1, 0.5, 0.525, WALL_H - 2.6, WALL_H - 1.4), B(-1.0, a1, -0.525, -0.5, WALL_H - 2.6, WALL_H - 1.4)), T.gold, False))
            if t >= 5:                                       # a gilded rim around the top of every merlon
                zc0, zc1 = WALL_H + MERLON_H - 0.9, WALL_H + MERLON_H + 0.15
                rims = union(rep(sub(B(m0 - 0.215, m0 + 0.215, 0.265, 0.525, zc0, zc1, 0.15), B(m0 - 0.15, m0 + 0.15, 0.33, 0.46, zc0 - 1, zc1 + 1)), 2.0 / 3.0, 3),
                             rep(sub(B(m0 - 0.215, m0 + 0.215, -0.525, -0.265, zc0, zc1, 0.15), B(m0 - 0.15, m0 + 0.15, -0.46, -0.33, zc0 - 1, zc1 + 1)), 2.0 / 3.0, 3))
                parts.append((rims, T.gold, False))
    return parts


def _tower_centre(t, r):
    """Where the tower's axis stands relative to the anchor (the crossing of the wall centre lines).

    The game draws the walls that leave a tower towards +x / +y AFTER the tower (they are nearer to the
    camera) and the walls that arrive from -x / -y before it.  A tower centred on the anchor would have
    its front bulge painted over by the leaving walls.  Moving the axis back along the diagonal until
    the circle passes through the leaving walls' front corners, (0, w) and (w, 0), makes those walls
    emerge exactly from the tower's surface; the arriving walls simply run into its side."""
    w = _wall_half(t)
    d = (-w + math.sqrt(2.0 * r * r - w * w)) / 2.0
    return -d, -d


def wall_tower(t):
    """Round wall tower (64 x 96 sprite): log drum with a thatched cap, field stone with a timber-framed
    head and clay tiles, then cut stone / white stone / marble with slate, blue and gilded spires."""
    sc = Scene("decor")
    T = Tier(t)
    r = 0.66 if t == 1 else 0.75
    xc, yc = _tower_centre(t, r)
    h = 22.0 + 3.0 * t
    cone_h = 11.0 + t
    cm = (xc, yc, r)
    if t == 1:
        g.round_tower(sc, T, xc, yc, r, h, cone_h, wall=r3.m_planks("#84583A", 0.21, seed=24, relief=0.6), flag_h=9.5, flag_w=7.0)
        sc.add(union(cyl(xc, yc, r + 0.012, h * 0.36, h * 0.36 + 1.3, 0.3), cyl(xc, yc, r + 0.012, 1.2, 2.5, 0.3)), r3.m_flat("#3E2E22", 0.1), cyl=cm, cast=False, name="hoops")
    elif t == 2:
        zt = h - 8.5
        slit = rotz(box(xc + r - 0.12, yc - 0.075, xc + r + 0.2, yc + 0.075, zt * 0.52 - 3.4, zt * 0.52 + 3.4), 62.0, xc, yc)
        sc.add(rotz(box(xc + r - 0.13, yc - 0.075, xc + r - 0.07, yc + 0.075, zt * 0.52 - 3.4, zt * 0.52 + 3.4), 62.0, xc, yc), T.slit, cast=False, ao=False)
        sc.add(sub(cyl(xc, yc, r, 0, zt), slit), T.curtain, cyl=cm, name="drum")
        sc.add(union(cone(xc, yc, r, zt - 2.4, zt, r + 0.12), cyl(xc, yc, r + 0.12, zt, zt + 1.4, 0.3)), T.trim, cyl=cm, name="jetty")
        holes = [rotz(box(xc + r, yc - 0.11, xc + r + 0.3, yc + 0.11, zt + 2.6, zt + 6.2), deg, xc, yc) for deg in (20.0, 62.0, 104.0)]
        for deg in (20.0, 62.0, 104.0):
            sc.add(rotz(box(xc + r + 0.03, yc - 0.11, xc + r + 0.07, yc + 0.11, zt + 2.6, zt + 6.2), deg, xc, yc), T.glow, cast=False, ao=False)
        sc.add(sub(cyl(xc, yc, r + 0.1, zt + 1.2, h), *holes), r3.m_plaster("#E9D9B0", "#6E4526", zt + 1.4, h, 0.33, beam=1.3, mid=False, braces=False),
               cyl=(xc, yc, r + 0.1), name="head")
        sc.add(union(cone(xc, yc, r + 0.3, h, h + cone_h), cyl(xc, yc, r + 0.3, h - 0.9, h + 0.05, 0.2)), T.roof, cyl=(xc, yc, r + 0.3), name="roof")
        g.flagpole(sc, T, xc, yc, h + cone_h - 1.5, 9.5, w=7.0)
    else:
        g.round_tower(sc, T, xc, yc, r, h, cone_h, flag_h=9.5 if t < 5 else 10.5, flag_w=7.0)
        sc.add(cyl(xc, yc, r + 0.04, 0, 2.6, 0.3), T.base, cyl=cm, cast=False, name="plinth")
    return sc


def _gate_wood(sc, T, a):
    """Tier 1 gate: a plank gatehouse with a big iron-bound double door between log posts, and an open,
    thatched lookout on top."""
    zp, zr = 16.0, 25.5
    pw, ph = 1.04, 12.6
    hole = g.opening("L", a, 0.0, 0.0, pw, ph, False, -0.14, 0.3)
    sc.add(sub(box(-a, -a, a, a, 0, zp), hole), T.wall(), name="body")
    sc.add(g._slab("L", a, -pw / 2, pw / 2, -0.16, -0.10, 0.0, ph), r3.m_planks("#6A452A", 0.13, seed=22, var=0.12), name="doors")
    sc.add(union(g._slab("L", a, -pw / 2, pw / 2, -0.10, -0.07, 3.2, 4.3), g._slab("L", a, -pw / 2, pw / 2, -0.10, -0.07, 8.6, 9.7),
                 g._slab("L", a, -0.025, 0.025, -0.10, -0.07, 0.0, ph)), T.iron, cast=False, name="straps")
    sc.add(union(g._slab("L", a, -pw / 2 - 0.1, pw / 2 + 0.1, -0.02, 0.06, ph, ph + 1.6, 0.3),
                 g._slab("L", a, -pw / 2 - 0.1, -pw / 2, -0.02, 0.06, 0.0, ph, 0.3), g._slab("L", a, pw / 2, pw / 2 + 0.1, -0.02, 0.06, 0.0, ph, 0.3)),
           T.trim, name="door frame")
    # corner logs with axe-cut points, the platform, the lookout's posts, rail and thatch
    logs = [union(cyl(px, py, 0.11, -0.5, zp + 3.2), cone(px, py, 0.11, zp + 3.2, zp + 5.4)) for px, py in ((-a, a), (a, a), (a, -a), (-a, -a))]
    sc.add(union(*logs), m_logs("#7E5638", 0.3, "x", seed=26), name="corner logs")
    sc.add(box(-a - 0.07, -a - 0.07, a + 0.07, a + 0.07, zp - 0.6, zp + 0.9, 0.3), T.trim, name="platform")
    posts = [capsule((px, py, zp), (px, py, zr + 0.5), 0.8) for px, py in ((-a + 0.1, a - 0.1), (a - 0.1, a - 0.1), (a - 0.1, -a + 0.1), (-a + 0.1, -a + 0.1))]
    sc.add(union(*posts), T.dark_wood, name="posts")
    rail = sub(box(-a + 0.02, -a + 0.02, a - 0.02, a - 0.02, zp + 0.8, zp + 4.4), box(-a + 0.1, -a + 0.1, a - 0.1, a - 0.1, zp, zp + 9.0))
    sc.add(rail, r3.m_planks("#94603A", 0.15, seed=27), name="rail")
    sc.add(box(-0.1, -0.1, 0.1, 0.1, zr - 4.6, zr - 1.8), r3.m_glow("#FFB84A", power=1.8, bars=False), cast=False, ao=False, name="lantern")
    sc.add(hip(-a, -a, a, a, zr, 10.5, over=0.12, thick=2.2), T.roof, name="roof")
    g.flagpole(sc, T, 0.0, 0.0, zr + 9.0, 11.0, w=8.0)
    g.torch(sc, T, -0.68, a + 0.05, 12.5, 0.9)
    g.torch(sc, T, 0.68, a + 0.05, 12.5, 0.9)
    return sc


def gate(t):
    """Gatehouse on the front corner of the ring (64 x 96 sprite, footprint -0.9..0.9).  The portal is
    in the left-front face; its two torches burn at (-0.68, 0.9, 12.5) and (0.68, 0.9, 12.5) as before."""
    sc = Scene("decor")
    T = Tier(t)
    a = 0.9
    if t == 1:
        return _gate_wood(sc, T, a)
    h = 26.0 + 3.0 * t
    pw, ph = 0.84, 15.5
    wm = T.wall(4.0, h, -a, -a, bay=0.45)
    holes = []
    if t == 2:
        # closed plank gate, iron-bound
        holes.append(g.door(sc, T, "L", a, 0.0, pw, ph, True, frame=T.base, fw=0.08, leaf=r3.m_planks("#6A452A", 0.14, seed=22, var=0.12)))
        sc.add(union(g._slab("L", a, -pw / 2, pw / 2, -0.10, -0.06, 4.0, 5.0), g._slab("L", a, -pw / 2, pw / 2, -0.10, -0.06, 9.6, 10.6),
                     g._slab("L", a, -0.02, 0.02, -0.10, -0.06, 0.0, ph - 1.0)), T.iron, cast=False, name="straps")
    else:
        # open passage with a raised portcullis
        holes.append(g.opening("L", a, 0.0, 0.0, pw, ph, True, -0.62, 0.3))
        sc.add(g._slab("L", a, -pw / 2 - 0.05, pw / 2 + 0.05, -0.66, -0.6, 0.0, ph + 0.5), r3.m_flat("#17120F", 0.04), cast=False, name="passage")
        fz = 0.08 * G / ZS
        sc.add(sub(g.opening("L", a, 0.0, 0.0, pw + 0.16, ph + fz, True, -0.02, 0.045), g.opening("L", a, 0.0, -1.0, pw, ph + 1.0, True, -0.3, 0.3)),
               T.base if t < 4 else T.trim, name="arch")
        bars = [capsule((bx, a - 0.16, 6.2), (bx, a - 0.16, ph - 0.4), 0.34) for bx in (-0.28, -0.14, 0.0, 0.14, 0.28)]
        bars += [capsule((-pw / 2, a - 0.16, zz), (pw / 2, a - 0.16, zz), 0.34) for zz in (7.4, 10.6)]
        bars += [cone(bx, a - 0.16, 0.03, 4.6, 6.4, 0.012) for bx in (-0.28, -0.14, 0.0, 0.14, 0.28)]
        sc.add(inter(union(*bars), g.opening("L", a, 0.0, 0.0, pw, ph, True, -0.4, 0.1)), T.iron, cast=False, name="portcullis")
    holes.append(g.window(sc, T, "L", a, 0.0, h - 6.4, 0.32, 5.4, fw=0.05))
    for wa in (-0.42, 0.42):
        holes.append(g.window(sc, T, "R", a, wa, h - 8.6, 0.28, 5.8, fw=0.05))
    if t == 2:
        zt = h * 0.56
        sc.add(sub(box(-a, -a, a, a, 0, zt), *holes), T.curtain, name="base storey")
        sc.add(box(-a - 0.07, -a - 0.07, a + 0.07, a + 0.07, zt - 0.4, zt + 1.4, 0.3), T.trim, name="jetty")
        sc.add(sub(box(-a - 0.05, -a - 0.05, a + 0.05, a + 0.05, zt + 1.2, h + 12.0), *[move(hh, 0.05, 0.05) for hh in holes],
                   g._gable_cut(-a - 0.05, -a - 0.05, a + 0.05, a + 0.05, h, 10.0, "x")),
               r3.m_plaster("#EDDDB4", "#6E4526", zt + 1.4, h, 0.475, -a - 0.05, -a - 0.05, beam=1.4, mid=False), name="upper storey")
    else:
        sc.add(box(-a - 0.05, -a - 0.05, a + 0.05, a + 0.05, 0, 2.8, 0.3), T.base, name="plinth")
        sc.add(sub(box(-a, -a, a, a, 2.0, h), *holes), wm, name="body")
        par = T.curtain if t < 5 else T.wall()
        sc.add(box(-a - 0.1, -a - 0.1, a + 0.1, a + 0.1, h - 3.0, h + 0.8, 0.3), par, name="parapet")
        sc.add(g.merlon_ring(-a - 0.1, -a - 0.1, a + 0.1, a + 0.1, h + 0.8, 3.2, 5, 0.13), par, name="merlons")
        if t >= 4:
            sc.add(box(-a - 0.12, -a - 0.12, a + 0.12, a + 0.12, h - 2.1, h - 0.9, 0.25), T.gold, cast=False, name="band")
        sc.add(union(*[box(px - 0.09, py - 0.09, px + 0.09, py + 0.09, 2.6, h - 2.6, 0.3) for px, py in ((-a, a), (a, a), (a, -a))]), T.base if t < 5 else T.wall(), name="pilasters")
    # roof and flag
    if t == 2:
        rh, o = 10.0, 0.05
        sc.add(gable(-a - o, -a - o, a + o, a + o, h, rh, "x", over=0.13, thick=1.5), T.roof, name="roof")
        sc.add(box(-a - 0.2, -0.05, a + 0.2, 0.05, h + rh - 0.9, h + rh + 0.45, 0.45), T.trim, cast=False, name="ridge")
        g.flagpole(sc, T, 0.0, 0.0, h + rh - 1.0, 12.0, w=8.0)
    else:
        rh = 9.0 + t
        sc.add(hip(-a + 0.1, -a + 0.1, a - 0.1, a - 0.1, h + 1.4, rh, thick=1.0), T.roof, name="roof")
        if t >= 4:
            sc.add(sphere(0.0, 0.0, h + 1.4 + rh + 0.2, 0.07), T.gold, name="finial")
        g.flagpole(sc, T, 0.0, 0.0, h + rh, 11.0, w=8.0, finial=t < 4)
    g.banner(sc, T, "R", a, 0.0, h - 5.2, 9.0, 0.3)
    if t >= 5:   # ruby over the portal
        sc.add(sphere(0.0, a + 0.03, ph + 3.0, 0.055), r3.m_glow("#FF3A58", power=0.9, bars=False), cast=False, ao=False)
        sc.add(torus(0.0, a + 0.03, ph + 3.0, 0.055, 0.33), T.gold, cast=False)
    g.torch(sc, T, -0.68, a + 0.05, 12.5, 0.9)
    g.torch(sc, T, 0.68, a + 0.05, 12.5, 0.9)
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
for _t in range(1, 6):
    SPRITES["deco_wall_x_t%d" % _t] = (lambda t=_t: wall_piece(t, "x"))
    SPRITES["deco_wall_y_t%d" % _t] = (lambda t=_t: wall_piece(t, "y"))
    SPRITES["deco_wall_tower_t%d" % _t] = (lambda t=_t: wall_tower(t))
    SPRITES["deco_gate_t%d" % _t] = (lambda t=_t: gate(t))
