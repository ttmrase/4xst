#!/usr/bin/env python3
"""render3d.py - a small software renderer for Crownfall's pre-rendered building sprites.

Signed-distance-field (SDF) ray marching with an orthographic camera, vectorised with numpy.
Python 3 + numpy + Pillow only.  See tools/artgen/README_3D.md for the full guide.

Quick tour
----------
    import render3d as r3
    sc = r3.Scene("building")                       # 128 x 160 viewport, anchor (64, 120)
    sc.add(r3.box(1, 1, 3, 3, 0, 30), r3.m_stone()) # x0, y0, x1, y1 in grid units; z0, z1 in z units
    sc.add(r3.hip(1, 1, 3, 3, 30, 14, over=0.2), r3.m_tiles("#4A6699", kind="slate"))
    img = r3.render(sc)                             # PIL RGBA, 4 px per viewport unit
    r3.save_webp(img, "bld_example.webp")

Model units (the same as the old vector art in iso.py)
    x, y : grid units.  x runs right-down on screen, y runs left-down.  A building plot is 0..4 x 0..4.
    z    : "z units" = viewport units straight up the screen.
    Radii of round things in plan (cyl, cone, sphere, torus R) are grid units.
    Small radii / thicknesses named *_px (bevels, capsule radius, torus tube) are viewport units.
Internally everything is converted to an isotropic world space (1 world unit = 1 viewport unit seen
face-on): X = x * G, Y = y * G, Z = z * ZS with G = 14 * sqrt(2) and ZS = 1 / cos(30 deg).

Camera: orthographic, 45 deg yaw, 30 deg elevation, which reproduces iso.py exactly:
    sx = CX + (x - y) * 14,   sy = CY + (x + y - S) * 7 - z
"""

import math

import numpy as np
from PIL import Image

F = np.float32
G = 14.0 * math.sqrt(2.0)          # world units per grid unit
ZS = 2.0 / math.sqrt(3.0)          # world units per z unit (1 / cos 30)
EPS = 0.02                         # hit tolerance of the ray marcher (world units)
MAX_STEPS = 110

# kind -> viewport w, h, anchor x, y, grid coordinates that land on the anchor
KINDS = {
    "building": (128, 160, 64.0, 120.0, 2.0, 2.0),
    "decor": (64, 96, 32.0, 80.0, 0.0, 0.0),
    "wall": (64, 64, 32.0, 48.0, 0.0, 0.0),
}

_S2 = math.sqrt(0.5)
_C30, _S30 = math.cos(math.radians(30.0)), 0.5
CAM_R = (_S2, -_S2, 0.0)                              # screen right
CAM_U = (-_S30 * _S2, -_S30 * _S2, _C30)              # screen up
CAM_F = (-_C30 * _S2, -_C30 * _S2, -_S30)             # viewing direction (into the scene)

# Lighting (world space; directions point towards the light).  The key light comes from the upper
# left-front: faces looking at +y (left-front on screen) are lit, faces looking at +x are in shade.
LIGHT = dict(
    key_dir=(-0.40, 0.58, 0.71), key_col=(1.00, 0.89, 0.70), key=2.35,
    sky_col=(0.52, 0.68, 1.00), sky=0.62,             # cool fill from above, scaled by ambient occlusion
    gnd_col=(0.62, 0.60, 0.38), gnd=0.30,             # warm bounce from the ground
    fill_dir=(0.80, 0.15, 0.45), fill_col=(0.62, 0.66, 1.00), fill=0.42,   # keeps shaded sides alive
    rim=0.35,                                          # grazing-angle sheen on upward silhouettes
    exposure=0.92, saturation=1.14, contrast=1.06,
    shadow_col="#17122B", shadow=0.52, contact=0.55,   # baked ground shadow (cast + contact)
    ao=1.0, bloom=1.0, outline=0.0,
)


# ---------------------------------------------------------------------------------------------
# small helpers
# ---------------------------------------------------------------------------------------------

def srgb(h):
    """'#RRGGBB' (or an (r, g, b) 0..1 sRGB triple) -> linear float32 rgb."""
    if isinstance(h, str):
        h = h.lstrip("#")
        c = np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])
    else:
        c = np.asarray(h, dtype=np.float64)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4).astype(F)


def _to_srgb(l):
    l = np.clip(l, 0.0, 1.0)
    return np.where(l <= 0.0031308, l * 12.92, 1.055 * np.power(l, 1.0 / 2.4) - 0.055)


def sstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def mixc(a, b, t):
    """Blend colours a -> b by t (N,).  a, b are (3,) or (N, 3)."""
    return a + (b - a) * t[..., None]


def hash2(ix, iy, seed=0):
    """Integer lattice hash -> float32 in [0, 1)."""
    a = np.asarray(ix).astype(np.int32).astype(np.uint32)
    b = np.asarray(iy).astype(np.int32).astype(np.uint32)
    h = a * np.uint32(374761393) + b * np.uint32(668265263) + np.uint32((seed * 2246822519 + 3266489917) & 0xFFFFFFFF)
    h = (h ^ (h >> np.uint32(13))) * np.uint32(1274126177)
    h = h ^ (h >> np.uint32(16))
    return (h >> np.uint32(8)).astype(F) * F(1.0 / 16777216.0)


def vnoise2(u, v, seed=0):
    """2D value noise in [0, 1]."""
    iu, iv = np.floor(u), np.floor(v)
    fu, fv = u - iu, v - iv
    fu = fu * fu * (3.0 - 2.0 * fu)
    fv = fv * fv * (3.0 - 2.0 * fv)
    iu, iv = iu.astype(np.int32), iv.astype(np.int32)
    a, b = hash2(iu, iv, seed), hash2(iu + 1, iv, seed)
    c, d = hash2(iu, iv + 1, seed), hash2(iu + 1, iv + 1, seed)
    return a + (b - a) * fu + (c - a) * fv + (a - b - c + d) * fu * fv


def fbm2(u, v, octaves=3, seed=0):
    s, amp, tot = 0.0, 0.5, 0.0
    for k in range(octaves):
        s = s + amp * vnoise2(u, v, seed + k * 17)
        tot += amp
        u, v, amp = u * 2.03 + 11.3, v * 2.03 + 5.7, amp * 0.5
    return s / tot


def vnoise3(x, y, z, seed=0):
    """3D value noise in [0, 1] (used for lumpy foliage and rock)."""
    x, y, z = np.broadcast_arrays(x, y, z)
    ix, iy, iz = np.floor(x), np.floor(y), np.floor(z)
    fx, fy, fz = x - ix, y - iy, z - iz
    fx = fx * fx * (3.0 - 2.0 * fx)
    fy = fy * fy * (3.0 - 2.0 * fy)
    fz = fz * fz * (3.0 - 2.0 * fz)
    ix, iy, iz = ix.astype(np.int32), iy.astype(np.int32), iz.astype(np.int32)

    def lay(k):
        a, b = hash2(ix, iy + 57 * (iz + k), seed), hash2(ix + 1, iy + 57 * (iz + k), seed)
        c, d = hash2(ix, iy + 1 + 57 * (iz + k), seed), hash2(ix + 1, iy + 1 + 57 * (iz + k), seed)
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy

    l0 = lay(0)
    return l0 + (lay(1) - l0) * fz


# ---------------------------------------------------------------------------------------------
# SDF nodes (world units).  d(x, y, z) must broadcast (it is also evaluated on a voxel grid).
# ---------------------------------------------------------------------------------------------

class Node:
    relax = 1.0      # step safety factor for the ray marcher (< 1 for inexact distances)
    lo = hi = None   # world-space bounding box

    def d(self, x, y, z):
        raise NotImplementedError


class _Box(Node):
    def __init__(self, lo, hi, r=0.0):
        self.lo, self.hi = np.minimum(lo, hi).astype(float), np.maximum(lo, hi).astype(float)
        self.c = [float(v) for v in (self.lo + self.hi) / 2.0]
        r = min(r, float(np.min(self.hi - self.lo)) / 2.0 - 1e-3)
        self.h = [float(v) - r for v in (self.hi - self.lo) / 2.0]
        self.r = r

    def d(self, x, y, z):
        qx = np.abs(x - self.c[0]) - self.h[0]
        qy = np.abs(y - self.c[1]) - self.h[1]
        qz = np.abs(z - self.c[2]) - self.h[2]
        out = np.sqrt(np.maximum(qx, 0.0) ** 2 + np.maximum(qy, 0.0) ** 2 + np.maximum(qz, 0.0) ** 2)
        return out + np.minimum(np.maximum(qx, np.maximum(qy, qz)), 0.0) - self.r


class _Cyl(Node):
    """Capped cylinder along axis 0 / 1 / 2."""

    def __init__(self, axis, a0, a1, cp, cq, r, rnd=0.0):
        self.axis, self.cp, self.cq, self.rnd = axis, float(cp), float(cq), rnd
        self.ac, self.hh, self.r = (a0 + a1) / 2.0, abs(a1 - a0) / 2.0 - rnd, r - rnd
        lo, hi = [0.0] * 3, [0.0] * 3
        o = [k for k in range(3) if k != axis]
        lo[axis], hi[axis] = min(a0, a1), max(a0, a1)
        lo[o[0]], hi[o[0]], lo[o[1]], hi[o[1]] = cp - r, cp + r, cq - r, cq + r
        self.lo, self.hi = np.array(lo), np.array(hi)

    def d(self, x, y, z):
        a, p, q = ((x, y, z), (y, x, z), (z, x, y))[self.axis]
        dr = np.hypot(p - self.cp, q - self.cq) - self.r
        da = np.abs(a - self.ac) - self.hh
        return np.minimum(np.maximum(dr, da), 0.0) + np.hypot(np.maximum(dr, 0.0), np.maximum(da, 0.0)) - self.rnd


class _Cone(Node):
    """Vertical capped cone: radius r0 at z0, r1 at z1 (exact distance)."""

    def __init__(self, cx, cy, z0, z1, r0, r1):
        self.cx, self.cy, self.zc, self.h, self.r0, self.r1 = cx, cy, (z0 + z1) / 2.0, (z1 - z0) / 2.0, r0, r1
        rm = max(r0, r1)
        self.lo, self.hi = np.array([cx - rm, cy - rm, z0]), np.array([cx + rm, cy + rm, z1])

    def d(self, x, y, z):
        qx = np.hypot(x - self.cx, y - self.cy)
        qy = z - self.zc
        h, r0, r1 = self.h, self.r0, self.r1
        k2x, k2y = r1 - r0, 2.0 * h
        cax = qx - np.minimum(qx, np.where(qy < 0.0, F(r0), F(r1)))
        cay = np.abs(qy) - h
        t = np.clip(((r1 - qx) * k2x + (h - qy) * k2y) / (k2x * k2x + k2y * k2y), 0.0, 1.0)
        cbx = qx - r1 + k2x * t
        cby = qy - h + k2y * t
        s = np.where((cbx < 0.0) & (cay < 0.0), F(-1.0), F(1.0))
        return (s * np.sqrt(np.minimum(cax * cax + cay * cay, cbx * cbx + cby * cby))).astype(F)


class _Convex(Node):
    """Intersection of half-spaces n . p <= d (roofs).  A lower bound of the distance."""

    def __init__(self, planes, lo, hi):
        self.planes = [tuple(float(v) for v in p) for p in planes]
        self.lo, self.hi = np.array(lo, float), np.array(hi, float)

    def d(self, x, y, z):
        out = None
        for nx, ny, nz, dd in self.planes:
            v = -dd
            if nx:
                v = v + x * nx
            if ny:
                v = v + y * ny
            if nz:
                v = v + z * nz
            out = v if out is None else np.maximum(out, v)
        return out


class _Ellipsoid(Node):
    def __init__(self, c, r):
        self.c, self.r = [float(v) for v in c], [float(v) for v in r]
        self.lo, self.hi = np.array(c) - np.array(r), np.array(c) + np.array(r)
        self.sphere = abs(r[0] - r[1]) < 1e-6 and abs(r[0] - r[2]) < 1e-6
        self.relax = 1.0 if self.sphere else 0.8

    def d(self, x, y, z):
        dx, dy, dz = x - self.c[0], y - self.c[1], z - self.c[2]
        if self.sphere:
            return np.sqrt(dx * dx + dy * dy + dz * dz) - self.r[0]
        rx, ry, rz = self.r
        k0 = np.sqrt((dx / rx) ** 2 + (dy / ry) ** 2 + (dz / rz) ** 2)
        k1 = np.sqrt((dx / (rx * rx)) ** 2 + (dy / (ry * ry)) ** 2 + (dz / (rz * rz)) ** 2)
        return np.where(k1 > 1e-6, k0 * (k0 - 1.0) / np.maximum(k1, 1e-6), -min(rx, ry, rz))


class _Torus(Node):
    def __init__(self, cx, cy, cz, R, r):
        self.cx, self.cy, self.cz, self.R, self.r = cx, cy, cz, R, r
        self.lo, self.hi = np.array([cx - R - r, cy - R - r, cz - r]), np.array([cx + R + r, cy + R + r, cz + r])

    def d(self, x, y, z):
        return np.hypot(np.hypot(x - self.cx, y - self.cy) - self.R, z - self.cz) - self.r


class _Capsule(Node):
    def __init__(self, a, b, r):
        self.a, self.b, self.r = [float(v) for v in a], [float(v) for v in b], r
        self.ba = [self.b[k] - self.a[k] for k in range(3)]
        self.bb = max(sum(v * v for v in self.ba), 1e-9)
        self.lo, self.hi = np.minimum(a, b) - r, np.maximum(a, b) + r

    def d(self, x, y, z):
        px, py, pz = x - self.a[0], y - self.a[1], z - self.a[2]
        bx, by, bz = self.ba
        h = np.clip((px * bx + py * by + pz * bz) / self.bb, 0.0, 1.0)
        return np.sqrt((px - bx * h) ** 2 + (py - by * h) ** 2 + (pz - bz * h) ** 2) - self.r


class _Union(Node):
    def __init__(self, kids):
        self.kids = list(kids)
        self.lo = np.min([k.lo for k in self.kids], axis=0)
        self.hi = np.max([k.hi for k in self.kids], axis=0)
        self.relax = min(k.relax for k in self.kids)

    def d(self, x, y, z):
        out = self.kids[0].d(x, y, z)
        for k in self.kids[1:]:
            out = np.minimum(out, k.d(x, y, z))
        return out


class _Smooth(_Union):
    """Smooth union (blobby joins; foliage, rocks)."""

    def __init__(self, kids, k):
        super().__init__(kids)
        self.k = k
        self.lo, self.hi = self.lo - k * 0.25, self.hi + k * 0.25
        self.relax = min(self.relax, 0.9)

    def d(self, x, y, z):
        out = self.kids[0].d(x, y, z)
        for kid in self.kids[1:]:
            b = kid.d(x, y, z)
            h = np.maximum(self.k - np.abs(out - b), 0.0) / self.k
            out = np.minimum(out, b) - h * h * self.k * 0.25
        return out


class _Sub(Node):
    def __init__(self, a, cuts):
        self.a, self.cuts = a, list(cuts)
        self.lo, self.hi, self.relax = a.lo, a.hi, min([a.relax] + [c.relax for c in self.cuts])

    def d(self, x, y, z):
        out = self.a.d(x, y, z)
        for c in self.cuts:
            out = np.maximum(out, -c.d(x, y, z))
        return out


class _Inter(Node):
    def __init__(self, kids):
        self.kids = list(kids)
        self.lo = np.max([k.lo for k in self.kids], axis=0)
        self.hi = np.min([k.hi for k in self.kids], axis=0)
        self.relax = min(k.relax for k in self.kids)

    def d(self, x, y, z):
        out = self.kids[0].d(x, y, z)
        for k in self.kids[1:]:
            out = np.maximum(out, k.d(x, y, z))
        return out


class _Round(Node):
    def __init__(self, a, r):
        self.a, self.r, self.relax = a, r, a.relax
        self.lo, self.hi = a.lo - r, a.hi + r

    def d(self, x, y, z):
        return self.a.d(x, y, z) - self.r


class _Move(Node):
    def __init__(self, a, off):
        self.a, self.off, self.relax = a, [float(v) for v in off], a.relax
        self.lo, self.hi = a.lo + np.array(off), a.hi + np.array(off)

    def d(self, x, y, z):
        return self.a.d(x - self.off[0], y - self.off[1], z - self.off[2])


class _RotZ(Node):
    def __init__(self, a, ang, px, py):
        self.a, self.px, self.py, self.relax = a, px, py, a.relax
        self.c, self.s = math.cos(ang), math.sin(ang)
        xs, ys = [], []
        for cx in (a.lo[0], a.hi[0]):
            for cy in (a.lo[1], a.hi[1]):
                xs.append(px + self.c * (cx - px) - self.s * (cy - py))
                ys.append(py + self.s * (cx - px) + self.c * (cy - py))
        self.lo, self.hi = np.array([min(xs), min(ys), a.lo[2]]), np.array([max(xs), max(ys), a.hi[2]])

    def d(self, x, y, z):
        dx, dy = x - self.px, y - self.py
        return self.a.d(self.px + self.c * dx + self.s * dy, self.py - self.s * dx + self.c * dy, z)


class _Repeat(Node):
    """n copies of a node, step apart along one axis (copy 0 is the node itself)."""

    def __init__(self, a, axis, step, n):
        self.a, self.axis, self.step, self.n, self.relax = a, axis, float(step), n, a.relax
        self.ref = float(a.lo[axis] + a.hi[axis]) / 2.0
        ext = step * (n - 1)
        self.lo, self.hi = a.lo.copy(), a.hi.copy()
        self.lo[axis] += min(ext, 0.0)
        self.hi[axis] += max(ext, 0.0)

    def d(self, x, y, z):
        p = [x, y, z]
        a = p[self.axis]
        p[self.axis] = a - self.step * np.clip(np.rint((a - self.ref) / self.step), 0, self.n - 1)
        return self.a.d(*p)


class _Displace(Node):
    """Lumpy surface: d - amp * (noise - 0.5)."""

    def __init__(self, a, amp, freq, seed=0):
        self.a, self.amp, self.freq, self.seed = a, amp, freq, seed
        self.lo, self.hi = a.lo - amp, a.hi + amp
        self.relax = min(a.relax, 1.0 / (1.0 + 1.6 * amp * freq))

    def d(self, x, y, z):
        f = self.freq
        n = vnoise3(x * f, y * f, z * f, self.seed) + 0.5 * vnoise3(x * f * 2.1, y * f * 2.1, z * f * 2.1, self.seed + 5)
        return self.a.d(x, y, z) - self.amp * (n - 0.75) * 1.33


class _Flag(Node):
    """A waving flag or pennant, flying towards screen-right from a hoist at point a."""

    def __init__(self, a, w, h, taper, thick, wave, wavelen, phase):
        self.a, self.w, self.hh, self.taper = [float(v) for v in a], w, h / 2.0, taper
        self.th, self.wave, self.k, self.phase = thick, wave, 2.0 * math.pi / wavelen, phase
        self.relax = 0.55
        m = wave + thick + 0.5
        p0, p1 = np.array(a), np.array(a) + np.array([w * _S2, -w * _S2, 0.0])
        self.lo = np.minimum(p0, p1) - [m, m, self.hh + 0.5]
        self.hi = np.maximum(p0, p1) + [m, m, self.hh + 0.5]

    def d(self, x, y, z):
        dx, dy = x - self.a[0], y - self.a[1]
        s = (dx - dy) * _S2                       # along the flag
        q = (dx + dy) * _S2                       # towards the camera
        zz = np.abs(z - self.a[2] - 0.6 * self.wave * np.sin(s * self.k * 0.5 + self.phase) * (s / self.w))
        f = np.clip(s / self.w, 0.0, 1.0)
        off = self.wave * np.sin(s * self.k + self.phase) * np.minimum(f * 2.0, 1.0)
        d1 = np.abs(q - off) - self.th
        d2 = np.maximum(-s, s - self.w)
        d3 = (zz - self.hh * (1.0 - self.taper * f)) * 0.9
        return np.maximum(d1, np.maximum(d2, d3))


# ---------------------------------------------------------------------------------------------
# public constructors (model units: x, y grid units; z z-units)
# ---------------------------------------------------------------------------------------------

def box(x0, y0, x1, y1, z0, z1, r_px=0.0):
    """Axis-aligned box.  r_px rounds the edges (viewport units)."""
    return _Box(np.array([x0 * G, y0 * G, z0 * ZS]), np.array([x1 * G, y1 * G, z1 * ZS]), r_px)


def cyl(xc, yc, r, z0, z1, r_px=0.0):
    """Vertical cylinder, radius r (grid units)."""
    return _Cyl(2, z0 * ZS, z1 * ZS, xc * G, yc * G, r * G, r_px)


def cyl_x(x0, x1, yc, zc, r_px):
    """Horizontal cylinder along x (radius in viewport units).  For arches cut into +x faces."""
    return _Cyl(0, x0 * G, x1 * G, yc * G, zc * ZS, r_px)


def cyl_y(y0, y1, xc, zc, r_px):
    """Horizontal cylinder along y (radius in viewport units).  For arches cut into +y faces."""
    return _Cyl(1, y0 * G, y1 * G, xc * G, zc * ZS, r_px)


def cone(xc, yc, r0, z0, z1, r1=0.0):
    """Vertical cone / frustum: radius r0 at z0 and r1 at z1 (grid units)."""
    return _Cone(xc * G, yc * G, z0 * ZS, z1 * ZS, r0 * G, r1 * G)


def sphere(x, y, z, r):
    """Sphere of radius r grid units (it is r * 17.15 z-units tall from centre to top)."""
    return _Ellipsoid((x * G, y * G, z * ZS), (r * G, r * G, r * G))


def ellipsoid(x, y, z, rx, ry, rz):
    """Ellipsoid: rx, ry in grid units, rz in z units."""
    return _Ellipsoid((x * G, y * G, z * ZS), (rx * G, ry * G, rz * ZS))


def torus(xc, yc, z, R, r_px):
    """Horizontal ring: major radius R (grid units), tube radius r_px (viewport units)."""
    return _Torus(xc * G, yc * G, z * ZS, R * G, r_px)


def capsule(p0, p1, r_px):
    """Round-ended rod between two model-space points (x, y, z); radius in viewport units."""
    return _Capsule((p0[0] * G, p0[1] * G, p0[2] * ZS), (p1[0] * G, p1[1] * G, p1[2] * ZS), r_px)


def _roof(x0, y0, x1, y1, z, h, slopes, thick):
    """Convex roof from eave rectangle (world), eave height z, list of ('x+'|'x-'|'y+'|'y-', run) slopes."""
    planes = [(0, 0, -1, -(z - thick)), (1, 0, 0, x1), (-1, 0, 0, -x0), (0, 1, 0, y1), (0, -1, 0, -y0)]
    for side, run in slopes:
        ln = math.hypot(h, run)
        a, b = h / ln, run / ln
        if side == "x+":
            planes.append((a, 0, b, a * x1 + b * z))
        elif side == "x-":
            planes.append((-a, 0, b, -a * x0 + b * z))
        elif side == "y+":
            planes.append((0, a, b, a * y1 + b * z))
        else:
            planes.append((0, -a, b, -a * y0 + b * z))
    return _Convex(planes, (x0, y0, z - thick), (x1, y1, z + h))


def gable(x0, y0, x1, y1, z, h, axis="x", over=0.0, thick=1.4):
    """Gabled roof: eaves at height z, ridge at z + h running along `axis`.  over = eave overhang (grid units),
    thick = height of the vertical fascia below the eave line (z units)."""
    x0, y0, x1, y1 = (x0 - over) * G, (y0 - over) * G, (x1 + over) * G, (y1 + over) * G
    if axis == "x":
        run = (y1 - y0) / 2.0
        sl = [("y+", run), ("y-", run)]
    else:
        run = (x1 - x0) / 2.0
        sl = [("x+", run), ("x-", run)]
    return _roof(x0, y0, x1, y1, z * ZS, h * ZS, sl, thick * ZS)


def hip(x0, y0, x1, y1, z, h, over=0.0, thick=1.4):
    """Hipped roof (a pyramid when the plan is square): four equal-pitch slopes, top at z + h."""
    x0, y0, x1, y1 = (x0 - over) * G, (y0 - over) * G, (x1 + over) * G, (y1 + over) * G
    run = min(x1 - x0, y1 - y0) / 2.0
    return _roof(x0, y0, x1, y1, z * ZS, h * ZS, [("x+", run), ("x-", run), ("y+", run), ("y-", run)], thick * ZS)


def flag(x, y, z, w=8.0, h=6.0, taper=0.8, wave=0.9, wavelen=7.0, phase=0.6, thick=0.22):
    """Cloth flying to screen-right from a hoist centred at (x, y, z).  w = length (viewport units),
    h = height at the hoist (z units), taper 0 = rectangle .. 1 = pennant."""
    return _Flag((x * G, y * G, z * ZS), w, h * ZS, taper, thick, wave, wavelen, phase)


def union(*kids):
    return kids[0] if len(kids) == 1 else _Union(kids)


def smooth(k_px, *kids):
    """Smooth union with blend radius k_px (viewport units)."""
    return _Smooth(kids, k_px)


def sub(a, *cuts):
    """a minus every cut (window and door recesses, crenel gaps)."""
    return _Sub(a, cuts) if cuts else a


def inter(*kids):
    return _Inter(kids)


def rounded(a, r_px):
    return _Round(a, r_px)


def move(a, dx=0.0, dy=0.0, dz=0.0):
    return _Move(a, (dx * G, dy * G, dz * ZS))


def rotz(a, deg, ox=0.0, oy=0.0):
    """Rotate about the vertical axis through grid point (ox, oy)."""
    return _RotZ(a, math.radians(deg), ox * G, oy * G)


def repeat(a, axis, step, n):
    """n copies of `a`, `step` apart along axis 'x' | 'y' (grid units) or 'z' (z units).  The copy must be
    narrower than the step."""
    k = "xyz".index(axis)
    return _Repeat(a, k, step * (ZS if k == 2 else G), n)


def lumpy(a, amp_px, freq=0.25, seed=0):
    """Noise-displaced surface (foliage, rocks).  amp in viewport units."""
    return _Displace(a, amp_px, freq, seed)


# ---------------------------------------------------------------------------------------------
# materials
# ---------------------------------------------------------------------------------------------

class Material:
    """albedo(c) -> (N, 3) linear rgb.  height(c) -> (N,) relief in world units (bump mapping).
    emis(c) -> (N, 3) emitted light.  spec / gloss: highlight strength and tightness.
    metal: 0..1 mirror-like reflection of a fake sky.  wrap: light wrap-around (cloth, foliage)."""

    def __init__(self, albedo, height=None, bump=1.0, spec=0.0, gloss=24.0, emis=None, metal=0.0, wrap=0.0,
                 shadeless=False):
        self.albedo, self.height, self.bump, self.spec, self.gloss = albedo, height, bump, spec, gloss
        self.emis, self.metal, self.wrap, self.shadeless = emis, metal, wrap, shadeless


class Ctx:
    """What a material sees: world position x, y, z, normal nx, ny, nz, and 2D texture coordinates u, v
    (world units).  On walls and roof slopes u runs horizontally along the face and v = height; on flat
    tops (u, v) = (x, y).  ux tells whether u is x (True) or y.  lo / hi = the solid's bounding box."""

    def __init__(self, x, y, z, nx, ny, nz, u, v, top, ux, lo, hi):
        self.x, self.y, self.z, self.nx, self.ny, self.nz = x, y, z, nx, ny, nz
        self.u, self.v, self.top, self.ux, self.lo, self.hi = u, v, top, ux, lo, hi

    def shift(self, du, dv):
        return Ctx(self.x, self.y, self.z, self.nx, self.ny, self.nz, self.u + du, self.v + dv, self.top, self.ux,
                   self.lo, self.hi)


def _vary(base, rnd, amount, tint=0.04):
    """Per-cell brightness and a slight warm/cool shift."""
    k = 1.0 - amount + 2.0 * amount * rnd
    t = (rnd * 7.31 % 1.0 - 0.5) * 2.0 * tint
    return np.stack([base[0] * k * (1.0 + t), base[1] * k, base[2] * k * (1.0 - t)], axis=-1)


def _blocks(c, ch, bl, seed):
    """Running-bond block layout.  Returns per-block random and distance to the nearest joint."""
    vv = c.v / ch
    row = np.floor(vv)
    fv = vv - row
    ri = row.astype(np.int32)
    uo = c.u / bl + 0.5 * (ri & 1) + 0.3 * hash2(ri, ri * 0 + 7, seed)
    col = np.floor(uo)
    fu = uo - col
    rnd = hash2(col.astype(np.int32), ri, seed + 1)
    m = np.minimum(np.minimum(fu, 1.0 - fu) * bl, np.minimum(fv, 1.0 - fv) * ch)
    return rnd, m


def m_stone(color="#BDB7A8", course=4.6, length=2.0, mortar=None, var=0.13, joint=0.4, seed=1, spec=0.05):
    """Cut stone blocks in running bond.  course = block height in z units, length = block length / height."""
    base = srgb(color)
    mort = srgb(mortar) if mortar else base * 0.42
    ch = course * ZS
    bl = ch * length

    def albedo(c):
        rnd, m = _blocks(c, ch, bl, seed)
        a = _vary(base, rnd, var) * (0.90 + 0.2 * fbm2(c.u * 0.3, c.v * 0.3, 2, seed + 3))[..., None]
        a = a * (0.90 + 0.14 * sstep(joint, joint + 1.3, m))[..., None]            # lighter face centre
        return mixc(a, mort, (1.0 - sstep(joint * 0.35, joint, m)) * 0.85)

    def height(c):
        _, m = _blocks(c, ch, bl, seed)
        return sstep(joint * 0.3, joint * 1.5, m) * 0.32

    return Material(albedo, height, spec=spec, gloss=18.0)


def m_rubble(color="#A39A8C", size=4.2, var=0.2, seed=2):
    """Irregular field stone (Voronoi cells) - rough foundations and early walls."""
    base = srgb(color)

    def cells(c):
        u, v = c.u / size, c.v / size
        iu, iv = np.floor(u).astype(np.int32), np.floor(v).astype(np.int32)
        f1 = np.full(u.shape, 9.0, F)
        f2 = np.full(u.shape, 9.0, F)
        rid = np.zeros(u.shape, F)
        for dj in (-1, 0, 1):
            for di in (-1, 0, 1):
                cu, cv = iu + di, iv + dj
                px = cu + 0.15 + 0.7 * hash2(cu, cv, seed)
                py = cv + 0.15 + 0.7 * hash2(cu, cv, seed + 1)
                dd = np.hypot(u - px, (v - py) * 1.25)
                near = dd < f1
                f2 = np.where(near, f1, np.minimum(f2, dd))
                rid = np.where(near, hash2(cu, cv, seed + 2), rid)
                f1 = np.where(near, dd, f1)
        return rid, (f2 - f1) * size

    def albedo(c):
        rid, e = cells(c)
        a = _vary(base, rid, var, 0.07) * (0.9 + 0.2 * fbm2(c.u * 0.4, c.v * 0.4, 2, seed))[..., None]
        return mixc(a, base * 0.36, (1.0 - sstep(0.25, 0.9, e)) * 0.9)

    def height(c):
        _, e = cells(c)
        return sstep(0.15, 1.6, e) * 0.5

    return Material(albedo, height, spec=0.03, gloss=14.0)


def m_planks(color="#A56A3A", width=0.3, horizontal=False, var=0.16, seed=3, relief=0.3):
    """Wooden planks (vertical by default) or stacked logs/boards (horizontal=True).  width in grid units."""
    base = srgb(color)
    ww = width * G

    def lay(c):
        a = np.where(c.top, c.v, c.v if horizontal else c.u)
        b = np.where(c.top, c.u, c.u if horizontal else c.v)
        k = np.floor(a / ww)
        f = a / ww - k
        return k.astype(np.int32), np.minimum(f, 1.0 - f) * ww, a, b

    def albedo(c):
        k, e, a, b = lay(c)
        rnd = hash2(k, k * 0 + 3, seed)
        grain = vnoise2(b * 0.16 + rnd * 40.0, a * 1.1, seed + 1)
        col = _vary(base, rnd, var, 0.05) * (0.86 + 0.26 * grain)[..., None]
        return mixc(col, base * 0.3, (1.0 - sstep(0.1, 0.42, e)) * 0.9)

    def height(c):
        _, e, _, _ = lay(c)
        return sstep(0.05, 0.9 if horizontal else 0.5, e) * relief

    return Material(albedo, height, spec=0.04, gloss=16.0)


def m_thatch(color="#DDB957", row=4.2, seed=4):
    """Straw thatch in ragged layers.  row = layer height in z units."""
    base = srgb(color)
    rh = row * ZS

    def lay(c):
        jag = vnoise2(c.u * 0.8, c.u * 0.0, seed) * 0.6 + vnoise2(c.u * 0.27, c.u * 0.0 + 3.0, seed) * 0.4
        vv = c.v / rh + jag * 0.5
        return vv - np.floor(vv)

    def albedo(c):
        f = lay(c)
        strand = vnoise2(c.u * 1.9, c.v * 0.22, seed + 1)
        k = (0.80 + 0.30 * strand) * (0.66 + 0.40 * (1.0 - sstep(0.5, 1.0, f))) * (1.0 + 0.10 * (1.0 - sstep(0.0, 0.25, f)))
        k = k * (0.92 + 0.16 * fbm2(c.u * 0.12, c.v * 0.12, 2, seed + 2))
        return base[None, :] * k[..., None] * np.array([1.0, 0.98, 0.9], F) ** ((1.0 - strand) * 0.8)[..., None]

    def height(c):
        f = lay(c)
        return (1.0 - f) * 0.7 + vnoise2(c.u * 1.9, c.v * 0.22, seed + 1) * 0.15

    return Material(albedo, height, bump=0.8, wrap=0.15)


def m_tiles(color="#C8563A", row=3.4, width=3.0, kind="clay", var=0.14, seed=5, spec=0.06, metal=0.0):
    """Roof tiles in rows.  kind: 'clay' (rounded pantiles), 'slate' (flat, staggered), 'scale' (fish-scale).
    row = row height in z units, width = tile width in viewport units."""
    base = srgb(color)
    rh = row * ZS

    def lay(c):
        vv = c.v / rh
        r = np.floor(vv)
        f = vv - r
        ri = r.astype(np.int32)
        uu = c.u / width + 0.5 * (ri & 1)
        k = np.floor(uu)
        g = uu - k
        if kind == "scale":      # rounded lower edge
            f = f - 0.35 * (1.0 - np.sqrt(np.maximum(1.0 - (2.0 * g - 1.0) ** 2, 0.0)))
        return k.astype(np.int32), ri, f, g

    def albedo(c):
        k, ri, f, g = lay(c)
        rnd = hash2(k, ri, seed)
        shade = 0.60 + 0.40 * (1.0 - sstep(0.62, 1.0, f))            # shadow under the row above
        shade = shade * (1.0 + 0.10 * (1.0 - sstep(0.0, 0.3, f)))    # bright lower lip
        shade = np.where(f < 0.0, 0.55, shade)
        gap = sstep(0.0, 0.14, np.minimum(g, 1.0 - g))
        return _vary(base, rnd, var, 0.05) * (shade * (0.72 + 0.28 * gap))[..., None]

    def height(c):
        _, _, f, g = lay(c)
        h = (1.0 - np.clip(f, 0.0, 1.0)) * 0.6
        if kind == "clay":
            h = h + np.sin(g * math.pi) * 0.35
        return h

    return Material(albedo, height, bump=0.8, spec=spec, gloss=30.0, metal=metal)


def m_plaster(plaster="#EBDcB6", timber="#6E4526", z0=4.0, z1=20.0, bay=0.45, ox=0.0, oy=0.0, beam=1.5, mid=True,
              braces=True, seed=6):
    """Plaster wall with a dark timber frame.  z0 / z1 = sill and top-plate heights (z units); posts every
    `bay` grid units measured from grid (ox, oy); beam = timber width (viewport units)."""
    pl, tb = srgb(plaster), srgb(timber)
    bw, za, zb = bay * G, z0 * ZS, z1 * ZS
    zm = (za + zb) / 2.0

    def frame(c):
        uo = c.u - np.where(c.ux, ox * G, oy * G)
        t = uo / bw
        du = np.abs(t - np.rint(t)) * bw
        dz = np.minimum(np.abs(c.v - za - beam * 0.5), np.abs(c.v - zb + beam * 0.5))
        if mid:
            dz = np.minimum(dz, np.abs(c.v - zm))
        dist = np.minimum(du, dz)
        if braces:
            kb = np.floor(t)
            fu = t - kb
            fu = np.where((kb.astype(np.int32) & 1) == 1, 1.0 - fu, fu)
            s = (c.v - za) / (zm - za)
            dd = np.abs(fu - s) * bw * 0.7
            dist = np.minimum(dist, np.where((s > 0.0) & (s < 1.0), dd, 9.0))
        return np.where(c.top, 9.0, dist)

    def albedo(c):
        fr = 1.0 - sstep(beam * 0.5 - 0.12, beam * 0.5 + 0.12, frame(c))
        blot = fbm2(c.u * 0.11, c.v * 0.11, 3, seed)
        p = pl[None, :] * (0.88 + 0.16 * blot)[..., None] * (0.90 + 0.10 * sstep(za, za + 5.0, c.v))[..., None]
        t = tb[None, :] * (0.8 + 0.4 * vnoise2(c.u * 0.9, c.v * 0.9, seed + 1))[..., None]
        return mixc(p, t, fr)

    def height(c):
        return (1.0 - sstep(beam * 0.5 - 0.2, beam * 0.5 + 0.2, frame(c))) * 0.35

    return Material(albedo, height, spec=0.02)


def m_marble(color="#F6F1E6", vein="#8E88A6", course=9.0, seed=7, veins=0.3):
    """Polished white marble with faint veins and large ashlar joints."""
    base, vc = srgb(color), srgb(vein)
    ch = course * ZS

    def albedo(c):
        w = fbm2(c.u * 0.045, c.v * 0.045, 3, seed)
        t1 = np.abs(np.sin((c.u * 0.8 + c.v * 0.55) * 0.20 + w * 9.0))
        t2 = np.abs(np.sin((c.u * 0.5 - c.v * 0.9) * 0.31 + w * 14.0 + 2.0))
        vm = (1.0 - sstep(0.0, 0.16, t1)) * veins + (1.0 - sstep(0.0, 0.10, t2)) * veins * 0.5
        a = base[None, :] * (0.93 + 0.09 * fbm2(c.u * 0.2, c.v * 0.2, 2, seed + 1))[..., None]
        a = mixc(a, vc, np.clip(vm, 0.0, 1.0))
        _, m = _blocks(c, ch, ch * 2.4, seed)
        return a * (0.80 + 0.20 * sstep(0.1, 0.38, m))[..., None]

    def height(c):
        _, m = _blocks(c, ch, ch * 2.4, seed)
        return sstep(0.1, 0.5, m) * 0.2

    return Material(albedo, height, spec=0.35, gloss=38.0)


def m_gold(color="#F2BE3C", rough=0.0):
    """Polished gold trim (metallic, strong highlight)."""
    base = srgb(color)
    return Material(lambda c: np.broadcast_to(base, c.x.shape + (3,)) * (0.94 + 0.12 * vnoise2(c.u * 0.7, c.v * 0.7, 9))[..., None],
                    spec=1.3, gloss=42.0, metal=0.62 - rough)


def m_metal(color="#6B6F7A"):
    """Iron / steel."""
    base = srgb(color)
    return Material(lambda c: np.broadcast_to(base, c.x.shape + (3,)) * (0.9 + 0.2 * vnoise2(c.u * 0.9, c.v * 0.9, 10))[..., None],
                    spec=0.8, gloss=30.0, metal=0.4)


def m_cloth(color="#C0303C", trim=None, trim_h=1.6, stripe=None):
    """Banner cloth.  trim = colour of a band along the bottom edge (trim_h z units); stripe = second colour
    for vertical stripes."""
    base = srgb(color)
    tr = srgb(trim) if trim else None
    st = srgb(stripe) if stripe else None

    def albedo(c):
        a = np.broadcast_to(base, c.x.shape + (3,)) * (0.90 + 0.12 * np.sin(c.u * 1.3 + c.v * 0.35) + 0.06 * vnoise2(c.u * 2.0, c.v * 2.0, 11))[..., None]
        if st is not None:
            a = mixc(a, st, (np.floor(c.u / 2.4) % 2.0 == 0.0).astype(F))
        if tr is not None:
            a = mixc(a, tr, (c.z < c.lo[2] + trim_h * ZS).astype(F))
        return a

    return Material(albedo, wrap=0.35, spec=0.05)


def m_flat(color="#888888", noise=0.08, spec=0.0, gloss=20.0, scale=0.5, seed=12):
    """Plain coloured surface with a little value noise (props, trim, poles)."""
    base = srgb(color)
    return Material(lambda c: base[None, :] * (1.0 - noise + 2.0 * noise * fbm2(c.u * scale, c.v * scale, 2, seed))[..., None],
                    spec=spec, gloss=gloss)


def m_ground(kind="dirt", color=None, seed=13):
    """Ground pad: 'dirt', 'grass', 'paved' (flagstones), 'marble' (pale paving), 'soil' (ploughed rows)."""
    defaults = {"dirt": "#9A7F4E", "grass": "#5E9E3E", "paved": "#A9A597", "marble": "#EDE6D4", "soil": "#7A5630"}
    base = srgb(color or defaults[kind])

    def albedo(c):
        if kind in ("paved", "marble"):
            cc = Ctx(c.x, c.y, c.z, c.nx, c.ny, c.nz, c.x + 0.35 * c.y, c.y - 0.35 * c.x, c.top, c.ux, c.lo, c.hi)
            rnd, m = _blocks(cc, 8.5 if kind == "paved" else 12.0, 12.0 if kind == "paved" else 12.0, seed)
            a = _vary(base, rnd, 0.10 if kind == "paved" else 0.05, 0.03)
            a = a * (0.9 + 0.2 * fbm2(c.x * 0.2, c.y * 0.2, 2, seed))[..., None]
            return mixc(a, base * 0.5, (1.0 - sstep(0.15, 0.5, m)) * (0.7 if kind == "paved" else 0.45))
        n = fbm2(c.x * 0.07, c.y * 0.07, 3, seed)
        fine = vnoise2(c.x * 0.9, c.y * 0.9, seed + 4)
        a = base[None, :] * (0.78 + 0.34 * n + 0.12 * fine)[..., None]
        if kind == "soil":
            a = a * (0.8 + 0.25 * np.abs(np.sin(c.y * 0.9)))[..., None]
        if kind == "grass":
            a = a * np.array([0.95, 1.0, 0.8], F) ** ((n - 0.5) * 2.0)[..., None]
        return a

    return Material(albedo, spec=0.25 if kind == "marble" else 0.0, gloss=30.0)


def m_water(color="#3C82C8", deep="#1F4E94"):
    a, b = srgb(color), srgb(deep)
    return Material(lambda c: mixc(b, a, fbm2(c.x * 0.25 + c.y * 0.1, c.y * 0.3, 3, 14)), spec=1.2, gloss=60.0, metal=0.35,
                    height=lambda c: fbm2(c.u * 0.5, c.v * 0.9, 2, 15) * 0.4)


def m_foliage(color="#4F9A3E", seed=16, warm=1.0):
    """Tree and bush leaves: clumpy colour variation, lighter warm tops, dark cool undersides."""
    base = srgb(color)

    def albedo(c):
        n = vnoise3(c.x * 0.22, c.y * 0.22, c.z * 0.22, seed)
        n2 = vnoise3(c.x * 0.7, c.y * 0.7, c.z * 0.7, seed + 1)
        k = 0.70 + 0.42 * n + 0.16 * n2
        up = np.clip(c.nz * 0.5 + 0.5, 0.0, 1.0)
        tint = np.stack([1.0 + 0.30 * warm * up * n, 1.0 + 0.10 * up, 1.0 - 0.25 * up], axis=-1)
        return base[None, :] * k[..., None] * tint * (0.72 + 0.36 * up)[..., None]

    return Material(albedo, wrap=0.45, spec=0.05, gloss=10.0)


def m_glow(color="#FFC65A", power=3.2, bars=True, bar_col="#4A2E18"):
    """Lit window / lantern: emits warm light and feeds the bloom pass.  bars draws a dark cross (mullions)
    through the centre of the solid's bounding box."""
    base, bc = srgb(color), srgb(bar_col)

    def mask(c):
        if not bars:
            return np.ones(c.x.shape, F)
        mid_u = np.where(c.ux, (c.lo[0] + c.hi[0]) * 0.5, (c.lo[1] + c.hi[1]) * 0.5)
        mid_v = c.lo[2] + (c.hi[2] - c.lo[2]) * 0.58
        dd = np.minimum(np.abs(c.u - mid_u), np.abs(c.z - mid_v))
        return sstep(0.22, 0.45, dd)

    def emis(c):
        m = mask(c)
        g = 0.75 + 0.5 * sstep(c.lo[2], c.hi[2], c.z)                    # brighter towards the top
        return base[None, :] * (power * m * g)[..., None]

    return Material(lambda c: mixc(bc, base, mask(c)), emis=emis, shadeless=True)


# ---------------------------------------------------------------------------------------------
# scene
# ---------------------------------------------------------------------------------------------

class Solid:
    __slots__ = ("node", "mat", "cast", "cyl", "ao", "name")

    def __init__(self, node, mat, cast, cyl, ao, name):
        self.node, self.mat, self.cast, self.cyl, self.ao, self.name = node, mat, cast, cyl, ao, name


class Scene:
    """A sprite: its kind fixes the viewport and anchor, solids are added in any order.

    kind     'building' (128 x 160, anchor (64, 120), grid origin so that (2, 2) is the anchor),
             'decor' (64 x 96, anchor (32, 80), grid (0, 0) is the anchor) or
             'wall'  (64 x 64, anchor (32, 48), grid (0, 0) is the anchor).
    ground   height of the shadow-catching ground plane in z units (0, or just below a pad's top).
    light    overrides for entries of LIGHT.
    """

    def __init__(self, kind="building", ground=0.0, **light):
        self.kind, self.ground, self.solids = kind, ground, []
        self.light = dict(LIGHT)
        self.light.update(light)

    def add(self, node, mat, cast=True, cyl=None, ao=True, name=""):
        """Add a solid.  cyl=(xc, yc, r) switches the texture mapping to cylindrical around that vertical
        axis (round towers, cones).  cast=False: no shadow.  ao=False: does not darken its neighbours."""
        if cyl is not None:
            cyl = (cyl[0] * G, cyl[1] * G, cyl[2] * G)
        self.solids.append(Solid(node, mat, cast, cyl, ao, name))
        return node


# ---------------------------------------------------------------------------------------------
# views and tracing
# ---------------------------------------------------------------------------------------------

class View:
    """Orthographic view.  project() maps world points to pixel coordinates + depth along fwd."""

    def __init__(self, right, up, fwd, origin, scale, cx, cy):
        self.r, self.u, self.f, self.o = tuple(right), tuple(up), tuple(fwd), tuple(origin)
        self.scale, self.cx, self.cy = float(scale), float(cx), float(cy)

    def project(self, x, y, z):
        dx, dy, dz = x - self.o[0], y - self.o[1], z - self.o[2]
        sx = self.cx + (dx * self.r[0] + dy * self.r[1] + dz * self.r[2]) * self.scale
        sy = self.cy - (dx * self.u[0] + dy * self.u[1] + dz * self.u[2]) * self.scale
        return sx, sy, dx * self.f[0] + dy * self.f[1] + dz * self.f[2]

    def plane(self, px, py):
        a = (px - self.cx) / self.scale
        b = (self.cy - py) / self.scale
        return (self.o[0] + a * self.r[0] + b * self.u[0], self.o[1] + a * self.r[1] + b * self.u[1],
                self.o[2] + a * self.r[2] + b * self.u[2])


def camera(kind, scale):
    """The game's camera for a sprite kind at `scale` pixels per viewport unit."""
    w, h, ax, ay, gx, gy = KINDS[kind]
    return View(CAM_R, CAM_U, CAM_F, (gx * G, gy * G, 0.0), scale, ax * scale, ay * scale)


def project_grid(kind, x, y, z=0.0, scale=1.0):
    """Model point (grid x, y, z units) -> sprite pixel coordinates.  Used to verify the alignment."""
    sx, sy, _ = camera(kind, scale).project(x * G, y * G, z * ZS)
    return sx, sy


def _corners(lo, hi):
    return np.array([[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])])


def _trace(view, solids, W, H, shadow_pass=False):
    """Per-solid sphere tracing inside each solid's screen rectangle, merged by a z-buffer.
    Returns depth (inf = nothing) and solid index (-1 = nothing), both flat."""
    zbuf = np.full(W * H, np.inf, F)
    ids = np.full(W * H, -1, np.int32)
    fx, fy, fz = view.f
    for si, s in enumerate(solids):
        if shadow_pass and not s.cast:
            continue
        node = s.node
        cs = _corners(node.lo - 0.05, node.hi + 0.05)
        sx, sy, _ = view.project(cs[:, 0], cs[:, 1], cs[:, 2])
        x0, x1 = max(int(math.floor(sx.min())), 0), min(int(math.ceil(sx.max())), W)
        y0, y1 = max(int(math.floor(sy.min())), 0), min(int(math.ceil(sy.max())), H)
        if x0 >= x1 or y0 >= y1:
            continue
        px, py = np.meshgrid(np.arange(x0, x1, dtype=F) + F(0.5), np.arange(y0, y1, dtype=F) + F(0.5))
        px, py = px.ravel(), py.ravel()
        gi = (np.arange(y0, y1, dtype=np.int64)[:, None] * W + np.arange(x0, x1, dtype=np.int64)[None, :]).ravel()
        ox, oy, oz = view.plane(px, py)
        tn = np.full(px.shape, -1e9, F)
        tf = np.full(px.shape, 1e9, F)
        for o, f, l, h in ((ox, fx, node.lo[0], node.hi[0]), (oy, fy, node.lo[1], node.hi[1]), (oz, fz, node.lo[2], node.hi[2])):
            if abs(f) > 1e-7:
                ta, tb = (F(l - 0.05) - o) / F(f), (F(h + 0.05) - o) / F(f)
                tn = np.maximum(tn, np.minimum(ta, tb))
                tf = np.minimum(tf, np.maximum(ta, tb))
            else:
                tf = np.where((o < l) | (o > h), -1e9, tf)
        act = np.nonzero(tn < tf)[0]
        if act.size == 0:
            continue
        gi = gi[act]
        ox, oy, oz, t, t1 = ox[act].astype(F), oy[act].astype(F), oz[act].astype(F), tn[act], tf[act]
        thit = np.full(act.size, np.inf, F)
        act = np.arange(act.size)
        relax = F(node.relax)
        for it in range(MAX_STEPS):
            d = node.d(ox + t * F(fx), oy + t * F(fy), oz + t * F(fz)).astype(F)
            hit = d < EPS
            last = it == MAX_STEPS - 1
            if last:
                hit = d < EPS * 8.0
            if hit.any():
                thit[act[hit]] = t[hit]
            t = t + np.maximum(d, F(EPS * 0.5)) * relax
            keep = ~hit & (t < t1)
            if last or not keep.any():
                break
            if keep.sum() < keep.size:
                act, ox, oy, oz, t, t1 = act[keep], ox[keep], oy[keep], oz[keep], t[keep], t1[keep]
        better = thit < zbuf[gi]
        zbuf[gi[better]] = thit[better]
        ids[gi[better]] = si
    return zbuf, ids


def _normals(node, x, y, z, e=0.07):
    """Tetrahedron finite differences."""
    e = F(e)
    a = node.d(x + e, y - e, z - e)
    b = node.d(x - e, y - e, z + e)
    c = node.d(x - e, y + e, z - e)
    dd = node.d(x + e, y + e, z + e)
    nx, ny, nz = a - b - c + dd, -a - b + c + dd, -a + b - c + dd
    ln = np.sqrt(nx * nx + ny * ny + nz * nz) + F(1e-9)
    return (nx / ln).astype(F), (ny / ln).astype(F), (nz / ln).astype(F)


class _Grid:
    """Truncated distance field of the whole scene on a coarse voxel grid (ambient occlusion)."""

    def __init__(self, solids, ground_z, vs=1.0, R=7.0):
        lo = np.min([s.node.lo for s in solids], axis=0) - R
        hi = np.max([s.node.hi for s in solids], axis=0) + R
        lo[2] = min(lo[2], ground_z - vs)
        self.lo, self.vs = lo, vs
        self.n = np.maximum(np.ceil((hi - lo) / vs).astype(int), 2)
        ax = [(lo[k] + (np.arange(self.n[k]) + 0.5) * vs).astype(F) for k in range(3)]
        g = np.full(tuple(self.n), R, F)
        for s in solids:
            if not s.ao:
                continue
            i0 = np.clip(np.floor((s.node.lo - R - lo) / vs).astype(int), 0, self.n)
            i1 = np.clip(np.ceil((s.node.hi + R - lo) / vs).astype(int), 0, self.n)
            if np.any(i1 <= i0):
                continue
            blk = g[i0[0]:i1[0], i0[1]:i1[1], i0[2]:i1[2]]
            d = s.node.d(ax[0][i0[0]:i1[0], None, None], ax[1][None, i0[1]:i1[1], None], ax[2][None, None, i0[2]:i1[2]])
            np.minimum(blk, d, out=blk)
        np.minimum(g, (ax[2] - F(ground_z))[None, None, :], out=g)
        self.g = g.ravel()

    def sample(self, x, y, z):
        nx, ny, nz = (int(v) for v in self.n)
        gx = np.clip((x - F(self.lo[0])) / F(self.vs) - F(0.5), 0.0, nx - 1.001)
        gy = np.clip((y - F(self.lo[1])) / F(self.vs) - F(0.5), 0.0, ny - 1.001)
        gz = np.clip((z - F(self.lo[2])) / F(self.vs) - F(0.5), 0.0, nz - 1.001)
        ix, iy, iz = gx.astype(np.int64), gy.astype(np.int64), gz.astype(np.int64)
        fx, fy, fz = gx - ix, gy - iy, gz - iz
        b = (ix * ny + iy) * nz + iz
        g = self.g
        c00 = g[b] + (g[b + 1] - g[b]) * fz
        c01 = g[b + nz] + (g[b + nz + 1] - g[b + nz]) * fz
        b2 = b + ny * nz
        c10 = g[b2] + (g[b2 + 1] - g[b2]) * fz
        c11 = g[b2 + nz] + (g[b2 + nz + 1] - g[b2 + nz]) * fz
        c0 = c00 + (c01 - c00) * fy
        c1 = c10 + (c11 - c10) * fy
        return c0 + (c1 - c0) * fx

    def ao(self, x, y, z, nx, ny, nz):
        occ = 0.0
        for h, w in ((1.1, 0.34), (2.4, 0.30), (4.2, 0.22), (6.4, 0.14)):
            d = self.sample(x + nx * F(h), y + ny * F(h), z + nz * F(h))
            occ = occ + F(w) * np.clip((F(h) - d) / F(h), 0.0, 1.0)
        return np.clip(1.0 - occ, 0.0, 1.0)


def _vogel(n):
    return [(math.sqrt((i + 0.5) / n) * math.cos(i * 2.39996323), math.sqrt((i + 0.5) / n) * math.sin(i * 2.39996323))
            for i in range(n)]


_TAPS_B, _TAPS_F = _vogel(10), _vogel(18)


def _shadow(lv, sm, sw, sh, x, y, z, rot, soft=0.11, rmin=0.22, rmax=2.6, bias=0.32):
    """Percentage-closer soft shadows from the light's depth map.  Returns 1 = fully lit."""
    lx, ly, ld = lv.project(x, y, z)
    ld = ld - F(bias)
    ca, sa = np.cos(rot), np.sin(rot)
    rs = F(rmax * lv.scale)
    cnt = np.zeros(lx.shape, F)
    dsum = np.zeros(lx.shape, F)
    for tx, ty in _TAPS_B:
        ix = np.clip((lx + (tx * ca - ty * sa) * rs).astype(np.int64), 0, sw - 1)
        iy = np.clip((ly + (tx * sa + ty * ca) * rs).astype(np.int64), 0, sh - 1)
        zl = sm[iy * sw + ix]
        b = zl < ld
        cnt += b
        dsum += np.where(b, zl, 0.0)
    out = np.ones(lx.shape, F)
    idx = np.nonzero(cnt > 0)[0]
    if idx.size:
        lx, ly, ld, ca, sa = lx[idx], ly[idx], ld[idx], ca[idx], sa[idx]
        pen = np.clip((ld - dsum[idx] / cnt[idx]) * F(soft), rmin, rmax) * F(lv.scale)
        lit = np.zeros(idx.size, F)
        for tx, ty in _TAPS_F:
            ix = np.clip((lx + (tx * ca - ty * sa) * pen).astype(np.int64), 0, sw - 1)
            iy = np.clip((ly + (tx * sa + ty * ca) * pen).astype(np.int64), 0, sh - 1)
            lit += sm[iy * sw + ix] >= ld
        out[idx] = lit / F(len(_TAPS_F))
    return out


def _box_blur(a, r):
    """Box blur of a (H, W, C) or (H, W) array along both axes, radius r pixels (edge-clamped)."""
    if r < 1:
        return a
    for axis in (0, 1):
        pad = [(0, 0)] * a.ndim
        pad[axis] = (r + 1, r)
        c = np.cumsum(np.pad(a, pad, mode="edge"), axis=axis, dtype=np.float64)
        n = a.shape[axis]
        hi = np.take(c, np.arange(2 * r + 1, 2 * r + 1 + n), axis=axis)
        lo = np.take(c, np.arange(0, n), axis=axis)
        a = ((hi - lo) / (2 * r + 1)).astype(F)
    return a


def _gauss(a, sigma):
    r = max(1, int(round(sigma * 0.9)))
    for _ in range(3):
        a = _box_blur(a, r)
    return a


def _aces(x):
    return np.clip(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0)


# ---------------------------------------------------------------------------------------------
# render
# ---------------------------------------------------------------------------------------------

def render(scene, ss=2, px=4, stats=None):
    """Render a Scene to a PIL RGBA image of (viewport * px) pixels.  ss = supersampling factor."""
    L = scene.light
    vw, vh, ax, ay, gx0, gy0 = KINDS[scene.kind]
    scale = px * ss
    W, H = vw * scale, vh * scale
    cam = camera(scene.kind, scale)
    solids = scene.solids
    gz = scene.ground * ZS
    fx, fy, fz = (F(v) for v in cam.f)

    zbuf, ids = _trace(cam, solids, W, H)
    hit = np.nonzero(ids >= 0)[0]
    order = hit[np.argsort(ids[hit], kind="stable")]
    sid = ids[order]
    ox, oy, oz = cam.plane((order % W).astype(F) + F(0.5), (order // W).astype(F) + F(0.5))
    t = zbuf[order]
    X, Y, Z = (ox + t * fx).astype(F), (oy + t * fy).astype(F), (oz + t * fz).astype(F)
    n = order.size
    NX, NY, NZ = np.zeros(n, F), np.zeros(n, F), np.zeros(n, F)          # geometric normals
    BX, BY, BZ = np.zeros(n, F), np.zeros(n, F), np.zeros(n, F)          # bumped normals
    ALB, EMI = np.zeros((n, 3), F), np.zeros((n, 3), F)
    SPEC, GLOSS, METAL, WRAP = np.zeros(n, F), np.full(n, 20.0, F), np.zeros(n, F), np.zeros(n, F)
    SHADELESS = np.zeros(n, bool)

    bounds = np.searchsorted(sid, np.arange(len(solids) + 1))
    for si, s in enumerate(solids):
        a, b = int(bounds[si]), int(bounds[si + 1])
        if a == b:
            continue
        x, y, z = X[a:b], Y[a:b], Z[a:b]
        nx, ny, nz = _normals(s.node, x, y, z)
        NX[a:b], NY[a:b], NZ[a:b] = nx, ny, nz
        m = s.mat
        top = nz > 0.9
        if s.cyl is not None:
            ux = np.ones(x.shape, bool)
            u = np.arctan2(y - F(s.cyl[1]), x - F(s.cyl[0])) * F(s.cyl[2])
            rr = np.hypot(x - F(s.cyl[0]), y - F(s.cyl[1])) + F(1e-6)
            tx, ty = -(y - F(s.cyl[1])) / rr, (x - F(s.cyl[0])) / rr
            top = np.zeros(x.shape, bool)
        else:
            ux = np.abs(ny) >= np.abs(nx)
            u = np.where(ux, x, y)
            tx, ty = ux.astype(F), (~ux).astype(F)
        v = np.where(top, y, z)
        u = np.where(top, x, u)
        c = Ctx(x, y, z, nx, ny, nz, u.astype(F), v.astype(F), top, ux, s.node.lo, s.node.hi)
        ALB[a:b] = m.albedo(c)
        if m.emis is not None:
            EMI[a:b] = m.emis(c)
        SPEC[a:b], GLOSS[a:b], METAL[a:b], WRAP[a:b], SHADELESS[a:b] = m.spec, m.gloss, m.metal, m.wrap, m.shadeless
        bx, by, bz = nx, ny, nz
        if m.height is not None and m.bump:
            e = F(0.16)
            h0 = m.height(c)
            hu = (m.height(c.shift(e, 0.0)) - h0) / e * F(m.bump)
            hv = (m.height(c.shift(0.0, e)) - h0) / e * F(m.bump)
            # surface gradients of u and v
            tx = np.where(top, 1.0, tx)
            ty = np.where(top, 0.0, ty)
            tn = tx * nx + ty * ny
            gux, guy, guz = tx - tn * nx, ty - tn * ny, -tn * nz
            gvx = np.where(top, -ny * nx, -nz * nx)
            gvy = np.where(top, 1.0 - ny * ny, -nz * ny)
            gvz = np.where(top, -ny * nz, 1.0 - nz * nz)
            bx, by, bz = nx - hu * gux - hv * gvx, ny - hu * guy - hv * gvy, nz - hu * guz - hv * gvz
            ln = np.sqrt(bx * bx + by * by + bz * bz) + F(1e-9)
            bx, by, bz = bx / ln, by / ln, bz / ln
        BX[a:b], BY[a:b], BZ[a:b] = bx, by, bz

    # --- light: shadow map + voxel AO -----------------------------------------------------------
    kd = np.array(L["key_dir"], float)
    kd /= np.linalg.norm(kd)
    lf = -kd
    lr = np.cross(lf, [0.0, 0.0, 1.0])
    lr /= np.linalg.norm(lr)
    lu = np.cross(lr, lf)
    lo = np.min([s.node.lo for s in solids], axis=0)
    hi = np.max([s.node.hi for s in solids], axis=0)
    ctr = (lo + hi) / 2.0
    sm_scale = 0.75 * scale
    lv0 = View(lr, lu, lf, ctr, sm_scale, 0.0, 0.0)
    cs = _corners(lo, hi)
    sx, sy, _ = lv0.project(cs[:, 0], cs[:, 1], cs[:, 2])
    sw, sh = int(math.ceil(sx.max() - sx.min())) + 8, int(math.ceil(sy.max() - sy.min())) + 8
    lv = View(lr, lu, lf, ctr, sm_scale, 4.0 - sx.min(), 4.0 - sy.min())
    sm, _ = _trace(lv, solids, sw, sh, shadow_pass=True)

    grid = _Grid(solids, gz, vs=1.0 if scale >= 8 else 1.25)
    AO = grid.ao(X, Y, Z, NX, NY, NZ)
    AO = 1.0 - (1.0 - AO) * F(L["ao"])
    rot = hash2(order % W, order // W, 77) * F(2.0 * math.pi)

    kx, ky, kz = (F(v) for v in kd)
    ndl_g = NX * kx + NY * ky + NZ * kz
    ndl = BX * kx + BY * ky + BZ * kz
    SH = np.zeros(n, F)
    fac = np.nonzero((ndl_g > -0.05) | (WRAP > 0))[0]
    if fac.size:
        o = F(0.3)
        SH[fac] = _shadow(lv, sm, sw, sh, X[fac] + NX[fac] * o, Y[fac] + NY[fac] * o, Z[fac] + NZ[fac] * o, rot[fac])
    diff = np.clip((ndl + WRAP) / (1.0 + WRAP), 0.0, 1.0)
    diff = diff * np.clip(ndl_g * 6.0 + 0.6 + WRAP * 2.0, 0.0, 1.0)      # bump must not light back faces
    key = diff * SH * (0.45 + 0.55 * AO)

    kc = np.array(L["key_col"], F) * F(L["key"])
    skc = np.array(L["sky_col"], F) * F(L["sky"])
    gc = np.array(L["gnd_col"], F) * F(L["gnd"])
    fd = np.array(L["fill_dir"], float)
    fd /= np.linalg.norm(fd)
    fl = np.clip(BX * F(fd[0]) + BY * F(fd[1]) + BZ * F(fd[2]), 0.0, 1.0)
    flc = np.array(L["fill_col"], F) * F(L["fill"])
    up = BZ * 0.5 + 0.5
    light = key[:, None] * kc + (AO * up)[:, None] * skc + (AO * (1.0 - up))[:, None] * gc + (AO * fl)[:, None] * flc
    # view-dependent terms
    vx, vy, vz = -fx, -fy, -fz
    ndv = np.clip(BX * vx + BY * vy + BZ * vz, 0.0, 1.0)
    rim = (1.0 - ndv) ** 3 * np.clip(BZ + 0.3, 0.0, 1.0) * F(L["rim"]) * AO
    light = light + rim[:, None] * skc
    col = ALB * light
    # specular (Blinn-Phong) and fake environment reflection for metals
    hx, hy, hz = kx + vx, ky + vy, kz + vz
    hl = math.sqrt(float(hx * hx + hy * hy + hz * hz))
    ndh = np.clip((BX * hx + BY * hy + BZ * hz) / F(hl), 0.0, 1.0)
    sp = SPEC * np.power(ndh, GLOSS) * SH * F(L["key"])
    col = col + sp[:, None] * kc * np.where(METAL[:, None] > 0, ALB * 1.6 + 0.2, 1.0)
    if (METAL > 0).any():
        mi = np.nonzero(METAL > 0)[0]
        rz = 2.0 * ndv[mi] * BZ[mi] - vz                                    # z of the reflected view ray
        rk = 2.0 * ndv[mi] * (BX[mi] * kx + BY[mi] * ky + BZ[mi] * kz) - (vx * kx + vy * ky + vz * kz)
        env = mixc(np.array([0.30, 0.22, 0.14], F), np.array([1.25, 1.2, 1.1], F), sstep(-0.35, 0.55, rz))
        env = env + (sstep(0.55, 0.95, rk) * 2.2 * SH[mi])[:, None] * kc
        refl = ALB[mi] * env * (0.35 + 0.65 * AO[mi])[:, None] * 1.5
        col[mi] = col[mi] * (1.0 - METAL[mi])[:, None] + refl * METAL[mi][:, None]
    col = np.where(SHADELESS[:, None], ALB * 0.55, col) + EMI

    # --- compose at supersampled resolution -----------------------------------------------------
    rgb = np.zeros((W * H, 3), F)
    alpha = np.zeros(W * H, F)
    emi = np.zeros((W * H, 3), F)
    rgb[order] = col * F(L["exposure"])
    alpha[order] = 1.0
    emi[order] = EMI

    # ground: transparent shadow catcher
    gnd = np.nonzero(ids < 0)[0]
    gpx, gpy = (gnd % W).astype(F) + F(0.5), (gnd // W).astype(F) + F(0.5)
    gox, goy, goz = cam.plane(gpx, gpy)
    tg = (F(gz) - goz) / fz
    gxw, gyw = gox + tg * fx, goy + tg * fy
    # fade: distance outside the solids' footprint, and distance to the image border
    dout = np.hypot(np.maximum(np.maximum(F(lo[0]) - gxw, gxw - F(hi[0])), 0.0),
                    np.maximum(np.maximum(F(lo[1]) - gyw, gyw - F(hi[1])), 0.0))
    reach = 1.5 * G if scene.kind == "building" else 1.1 * G
    fade = 1.0 - sstep(0.25 * reach, reach, dout)
    edge = np.minimum(np.minimum(gpx, W - gpx), np.minimum(gpy, H - gpy)) / F(scale)
    fade = fade * sstep(0.5, 5.0, edge)
    sel = np.nonzero(fade > 0.003)[0]
    if sel.size:
        gxs, gys = gxw[sel], gyw[sel]
        gzs = np.full(sel.size, gz, F)
        zero, one = np.zeros(sel.size, F), np.ones(sel.size, F)
        gsh = _shadow(lv, sm, sw, sh, gxs, gys, gzs + F(0.3), hash2(gnd[sel] % W, gnd[sel] // W, 78) * F(2.0 * math.pi))
        gao = grid.ao(gxs, gys, gzs, zero, zero, one)
        dark = 1.0 - (1.0 - F(L["shadow"]) * (1.0 - gsh)) * (1.0 - F(L["contact"]) * (1.0 - gao) ** 1.2 * 1.6)
        a = np.clip(dark, 0.0, 0.9) * fade[sel]
        gi = gnd[sel]
        alpha[gi] = a
        rgb[gi] = srgb(L["shadow_col"])[None, :] * a[:, None]             # premultiplied

    def down(a):
        a = a.reshape(H // ss, ss, W // ss, ss, -1).mean(axis=(1, 3))
        return a

    rgb, alpha, emi = down(rgb), down(alpha)[..., 0], down(emi)
    # bloom from emissive pixels
    if L["bloom"] > 0 and float(emi.max()) > 0.0:
        glow = (_gauss(emi, 1.1 * px) * 0.55 + _gauss(emi, 3.2 * px) * 0.60) * F(L["bloom"])
        rgb = rgb + glow
        alpha = np.maximum(alpha, np.clip(glow.max(axis=2) * 1.1, 0.0, 0.85))
    # tone map (on un-premultiplied colour), grade, encode
    un = rgb / np.maximum(alpha, 1e-4)[..., None]
    un = _aces(un)
    un = _to_srgb(un)
    lum = (un * np.array([0.2126, 0.7152, 0.0722], F)).sum(axis=2, keepdims=True)
    un = lum + (un - lum) * F(L["saturation"])
    un = (un - 0.5) * F(L["contrast"]) + 0.5
    un = np.clip(un, 0.0, 1.0)
    if L["outline"] > 0:
        solid = (alpha > 0.97).astype(F)
        spread = np.clip(_gauss(solid, 0.45 * px) * 2.2, 0.0, 1.0)
        ring = np.clip(spread - alpha, 0.0, 1.0) * F(L["outline"])
        ink = srgb(L["shadow_col"])
        ink_s = _to_srgb(ink)
        na = alpha + ring * (1.0 - alpha)
        un = (un * alpha[..., None] + ink_s[None, None, :] * (ring * (1.0 - alpha))[..., None]) / np.maximum(na, 1e-4)[..., None]
        alpha = na
    out = np.dstack([un, np.clip(alpha, 0.0, 1.0)])
    out = (out * 255.0 + 0.5).astype(np.uint8)
    out[out[..., 3] == 0] = 0
    if stats is not None:
        stats.update(solids=len(solids), hit=int(n), shadow_map=(sw, sh), grid=tuple(int(v) for v in grid.n))
    return Image.fromarray(out, "RGBA")


def save_webp(img, path, quality=90):
    """Lossy WebP with alpha (the format shipped in res/drawable-xxxhdpi)."""
    img.save(path, "WEBP", quality=quality, method=6)


def selftest():
    """Check that the camera reproduces iso.py's projection: analytically, and by rendering small spheres
    and locating them in the image."""
    worst = 0.0
    for kind, (w, h, ax, ay, gx, gy) in KINDS.items():
        s = 2.0 * gx                                  # iso.S for this kind (4 for buildings, 0 for decor)
        for (x, y, z) in ((0, 0, 0), (4, 0, 0), (0, 4, 0), (4, 4, 0), (2, 2, 60), (1.1, 2.9, 44), (-0.7, 0.4, 13.5)):
            sx, sy = project_grid(kind, x, y, z)
            ex, ey = ax + (x - y) * 14.0, ay + (x + y - s) * 7.0 - z
            worst = max(worst, abs(sx - ex), abs(sy - ey))
    print("analytic: max |camera - iso.P| = %.2e viewport units" % worst)
    ok = worst < 1e-6
    pts = [(0.5, 0.5, 6.0), (3.5, 0.5, 6.0), (0.5, 3.5, 6.0), (3.5, 3.5, 6.0), (2.0, 2.0, 70.0)]
    for (x, y, z) in pts:
        sc = Scene("building")
        sc.add(sphere(x, y, z, 0.12), m_flat("#FFFFFF"))
        a = np.asarray(render(sc, ss=2))[..., 3] > 200
        ys, xs = np.nonzero(a)
        cx, cy = (xs.mean() + 0.5) / 4.0, (ys.mean() + 0.5) / 4.0
        ex, ey = 64.0 + (x - y) * 14.0, 120.0 + (x + y - 4.0) * 7.0 - z
        print("rendered sphere at grid (%.1f, %.1f, z %.0f): centroid (%.2f, %.2f), expected (%.2f, %.2f)" % (x, y, z, cx, cy, ex, ey))
        ok = ok and abs(cx - ex) < 0.2 and abs(cy - ey) < 0.2
    print("alignment OK" if ok else "ALIGNMENT FAILED")
    return ok


if __name__ == "__main__":
    import sys
    sys.exit(0 if selftest() else 1)
