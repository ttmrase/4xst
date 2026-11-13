#!/usr/bin/env python3
"""gen_ground.py - seamless ground textures for the territory view and the world map.

The buildings are pre-rendered 3D sprites (render3d.py).  This script paints what they stand on: tiling,
top-down ground materials lit by the same key light, in the same palette, so that the ground reads as part
of the same render and not as a flat fill.  Python 3 + numpy + Pillow only, no network, deterministic
(the same sources give byte-identical files).

    python3 tools/artgen/gen_ground.py                  # all -> app/src/main/res/drawable-nodpi/tex_*.webp
    python3 tools/artgen/gen_ground.py grass cobble     # only these
    python3 tools/artgen/gen_ground.py --png DIR        # also keep PNG copies in DIR
    python3 tools/artgen/gen_ground.py --preview out.png  # isometric sample of every material (look at it!)
    python3 tools/artgen/gen_ground.py --calibrate      # lit colours of render3d's m_ground pads (needs render3d)
    python3 tools/artgen/gen_ground.py --list

Conventions
    A texture is a square that tiles in both directions.  Pixel (px, py) is the ground point
    (gx, gy) = (px, py) * span / size: +x runs right-down on screen, +y left-down, exactly the grid of the
    sprites.  The game maps the bitmap in grid space and lets the isometric matrix foreshorten it
    (GroundArt.kt); the world map uses the same files top-down (MapGround.kt).
    Lighting: render3d's key light, direction (-0.22, 0.58, 0.78) towards the light.  Bumps (cobbles, slabs,
    pebbles, ripples) are shaded from a height map with that direction, warm where lit and cool in shade.
    Colours: mid-tones a little below the lit pads of the sprites (see --calibrate), so buildings stay the
    brightest things on screen.

Files (drawable-nodpi, lossy WebP)
    tex_grass   lush meadow                 tex_forest  forest floor (moss, litter, roots of shade)
    tex_dirt    worn earth path             tex_cobble  rounded cobblestones
    tex_flag    plaza flagstones            tex_marble  pale marble paving (top castle tiers)
    tex_water   river / lake surface        tex_snow    snow field (world map north, winter theme)
    tex_sand    desert sand with ripples    tex_rock    mountain rock
    tex_decals  4 x 2 atlas of ground details with alpha: flowers, tufts, pebbles, leaves
    tex_peaks   3 x 2 atlas of mountain massifs with alpha (world map), rock in the top row, snowy below
"""

import math
import os
import sys

import numpy as np
from PIL import Image

F = np.float32
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "res",
                       "drawable-nodpi")

# render3d's key light (towards the light) and light colours; flat ground is the reference (factor 1).
KEY_DIR = np.array([-0.22, 0.58, 0.78]) / math.sqrt(0.22 ** 2 + 0.58 ** 2 + 0.78 ** 2)
WARM = np.array([1.00, 0.93, 0.80])        # lit slopes lean warm
COOL = np.array([0.72, 0.80, 1.00])        # shaded slopes lean cool


# ---------------------------------------------------------------------------------------------
# helpers: colour
# ---------------------------------------------------------------------------------------------

def rgb(h):
    """'#RRGGBB' -> float rgb 0..1 (sRGB; the textures are painted directly in display space)."""
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)])


def sstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def mix(a, b, t):
    """Blend colours a -> b by t.  a, b are (3,) or (H, W, 3); t is (H, W)."""
    return a + (b - a) * t[..., None]


def ramp(t, stops):
    """Piecewise-linear colour ramp.  stops = [(position, '#RRGGBB'), ...] with rising positions."""
    out = np.zeros(t.shape + (3,))
    pos = [p for p, _ in stops]
    col = [rgb(c) for _, c in stops]
    for k in range(3):
        out[..., k] = np.interp(t, pos, [c[k] for c in col])
    return out


# ---------------------------------------------------------------------------------------------
# helpers: periodic noise (everything wraps at the tile edge)
# ---------------------------------------------------------------------------------------------

def _grid(n):
    v = np.arange(n, dtype=np.float64)
    return np.meshgrid(v, v)          # x, y with shape (n, n); x varies along columns


def pnoise(n, cells, seed, cells_y=None):
    """Periodic gradient noise, n x n, 'cells' lattice cells across the tile.  Roughly -1..1."""
    cy = cells_y or cells
    rs = np.random.RandomState(seed)
    ang = rs.rand(cy, cells) * 2.0 * math.pi
    gx, gy = np.cos(ang), np.sin(ang)
    x, y = _grid(n)
    x = x * (cells / n)
    y = y * (cy / n)
    x0 = np.floor(x).astype(int)
    y0 = np.floor(y).astype(int)
    fx, fy = x - x0, y - y0
    x1, y1 = (x0 + 1) % cells, (y0 + 1) % cy
    x0 %= cells
    y0 %= cy

    def dot(ix, iy, dx, dy):
        return gx[iy, ix] * dx + gy[iy, ix] * dy

    u = fx * fx * fx * (fx * (fx * 6 - 15) + 10)
    v = fy * fy * fy * (fy * (fy * 6 - 15) + 10)
    a = dot(x0, y0, fx, fy)
    b = dot(x1, y0, fx - 1, fy)
    c = dot(x0, y1, fx, fy - 1)
    d = dot(x1, y1, fx - 1, fy - 1)
    return ((a + (b - a) * u) + ((c + (d - c) * u) - (a + (b - a) * u)) * v) * 1.6


def fbm(n, cells, octaves, seed, gain=0.5):
    """Fractal periodic noise, 0..1 (mean 0.5)."""
    total = np.zeros((n, n))
    amp, norm = 1.0, 0.0
    for o in range(octaves):
        total += amp * pnoise(n, cells * (2 ** o), seed + 17 * o)
        norm += amp
        amp *= gain
    return np.clip(0.5 + 0.5 * total / norm, 0.0, 1.0)


def diag_noise(n, along, across, seed):
    """Periodic noise stretched along the texture diagonal (1, 1), i.e. straight up the screen once the
    isometric matrix has squashed it: grass blades, drifts.  'along' / 'across' = lattice cells per tile."""
    rs = np.random.RandomState(seed)
    lat = rs.rand(across, along)
    x, y = _grid(n)
    a = (x + y) * (along / n)
    b = (x - y) * (across / n)
    a0 = np.floor(a).astype(int)
    b0 = np.floor(b).astype(int)
    fa, fb = a - a0, b - b0
    fa = fa * fa * (3 - 2 * fa)
    fb = fb * fb * (3 - 2 * fb)
    a1, b1 = (a0 + 1) % along, (b0 + 1) % across
    a0 %= along
    b0 %= across
    top = lat[b0, a0] + (lat[b0, a1] - lat[b0, a0]) * fa
    bot = lat[b1, a0] + (lat[b1, a1] - lat[b1, a0]) * fa
    return top + (bot - top) * fb


def worley(n, cells, seed, jitter=0.85):
    """Periodic cellular noise.  Returns (f1, f2, ident, dx, dy): distance to the nearest and second nearest
    feature point in cell units, a 0..1 random number of the nearest point, and the offset to it."""
    rs = np.random.RandomState(seed)
    px = (rs.rand(cells, cells) - 0.5) * jitter + 0.5
    py = (rs.rand(cells, cells) - 0.5) * jitter + 0.5
    pid = rs.rand(cells, cells)
    x, y = _grid(n)
    x = x * (cells / n)
    y = y * (cells / n)
    cx = np.floor(x).astype(int)
    cy = np.floor(y).astype(int)
    f1 = np.full((n, n), 9.0)
    f2 = np.full((n, n), 9.0)
    ident = np.zeros((n, n))
    ox = np.zeros((n, n))
    oy = np.zeros((n, n))
    for j in (-1, 0, 1):
        for i in (-1, 0, 1):
            ix, iy = (cx + i) % cells, (cy + j) % cells
            dx = (cx + i + px[iy, ix]) - x
            dy = (cy + j + py[iy, ix]) - y
            d = np.sqrt(dx * dx + dy * dy)
            near = d < f1
            f2 = np.where(near, f1, np.minimum(f2, d))
            ident = np.where(near, pid[iy, ix], ident)
            ox = np.where(near, dx, ox)
            oy = np.where(near, dy, oy)
            f1 = np.where(near, d, f1)
    return f1, f2, ident, ox, oy


def blur(a, r):
    """Periodic box blur of radius r pixels, three passes (close to a Gaussian)."""
    if r <= 0:
        return a
    out = a.astype(np.float64)
    for _ in range(3):
        for axis in (0, 1):
            acc = np.zeros_like(out)
            for s in range(-r, r + 1):
                acc += np.roll(out, s, axis=axis)
            out = acc / (2 * r + 1)
    return out


def scatter(n, count, seed, r_lo, r_hi, soft=1.2):
    """Periodic field of 'count' soft discs.  Returns (coverage 0..1, ident 0..1, height 0..1 dome)."""
    rs = np.random.RandomState(seed)
    cov = np.zeros((n, n))
    ident = np.zeros((n, n))
    dome = np.zeros((n, n))
    x, y = _grid(n)
    for _ in range(count):
        cx, cy = rs.rand() * n, rs.rand() * n
        r = r_lo + (r_hi - r_lo) * rs.rand()
        sq = 0.75 + 0.5 * rs.rand()              # a little oval
        idv = rs.rand()
        dx = (x - cx + n / 2) % n - n / 2
        dy = ((y - cy + n / 2) % n - n / 2) * sq
        d = np.sqrt(dx * dx + dy * dy)
        c = 1.0 - sstep(r - soft, r, d)
        take = c > cov
        ident = np.where(take, idv, ident)
        dome = np.where(take, np.sqrt(np.clip(1.0 - (d / r) ** 2, 0.0, 1.0)), dome)
        cov = np.maximum(cov, c)
    return cov, ident, dome


def light(albedo, height, bump, ambient=0.42):
    """Shade a top-down albedo with a periodic height map: render3d's key light, flat ground stays as is.
    'bump' scales the slopes (height units per pixel)."""
    hx = (np.roll(height, -1, axis=1) - np.roll(height, 1, axis=1)) * 0.5 * bump
    hy = (np.roll(height, -1, axis=0) - np.roll(height, 1, axis=0)) * 0.5 * bump
    inv = 1.0 / np.sqrt(hx * hx + hy * hy + 1.0)
    ndl = (-hx * KEY_DIR[0] - hy * KEY_DIR[1] + KEY_DIR[2]) * inv
    rel = np.clip(ndl / KEY_DIR[2], 0.0, 1.9)                 # 1 on flat ground
    k = ambient + (1.0 - ambient) * rel
    tint = np.where((rel >= 1.0)[..., None], 1.0 + (WARM - 1.0) * np.clip(rel - 1.0, 0, 1)[..., None] * 1.4,
                    1.0 + (COOL - 1.0) * np.clip(1.0 - rel, 0, 1)[..., None] * 0.9)
    return albedo * k[..., None] * tint


def finish(a):
    return Image.fromarray((np.clip(a, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8), "RGB")


# ---------------------------------------------------------------------------------------------
# materials
# ---------------------------------------------------------------------------------------------

def grass(n=512):
    """Lush meadow.  One tile spans about 12 grid units in the city."""
    patch = fbm(n, 3, 3, 101)                       # large soft patches (kept gentle: the game adds the
    clump = fbm(n, 14, 3, 102)                      # non-repeating large-scale variation itself)
    fine = fbm(n, 56, 2, 103)
    blades = diag_noise(n, 46, 210, 104) * 0.6 + diag_noise(n, 30, 150, 105) * 0.4
    t = 0.5 + (patch - 0.5) * 0.55 + (clump - 0.5) * 0.75 + (fine - 0.5) * 0.35 + (blades - 0.5) * 0.42
    col = ramp(np.clip(t, 0, 1), [(0.0, "#2F6B2C"), (0.3, "#4A8C36"), (0.52, "#5FA23E"), (0.74, "#79B646"),
                                  (1.0, "#A2CD5A")])
    # hue drift: some patches lean yellow (sunlit, dry), some blue-green (lush)
    hue = fbm(n, 5, 2, 106) - 0.5
    col *= np.stack([1.0 + 0.16 * hue, 1.0 + 0.02 * hue, 1.0 - 0.22 * hue], axis=-1)
    # tips that catch the light, and small dark gaps between clumps
    tips = sstep(0.80, 0.95, diag_noise(n, 70, 260, 107) * 0.7 + fine * 0.3)
    col = mix(col, rgb("#B9DA6A"), tips * 0.45)
    gaps = sstep(0.60, 0.78, 1.0 - fbm(n, 40, 2, 108))
    col = mix(col, rgb("#2C5E2B"), gaps * 0.22)
    height = clump * 0.7 + blades * 0.5
    return finish(light(col, height, 7.0, ambient=0.62))


def forest(n=384):
    """Forest floor: dark moss, leaf litter, bare damp earth, a few twigs.  Reads as shade under the trees."""
    moss = fbm(n, 6, 4, 201)
    earth = fbm(n, 9, 3, 202)
    fine = fbm(n, 48, 2, 203)
    t = np.clip(0.5 + (moss - 0.5) * 1.1 + (fine - 0.5) * 0.4, 0, 1)
    col = ramp(t, [(0.0, "#1C3A22"), (0.4, "#27502A"), (0.7, "#346A30"), (1.0, "#4A8238")])
    bare = sstep(0.56, 0.72, earth)
    col = mix(col, rgb("#4A3B26") * (0.8 + 0.4 * fine)[..., None], bare * 0.75)
    leaves, ident, dome = scatter(n, 260, 204, 1.6, 3.6)
    leaf_col = ramp(ident, [(0.0, "#6A4A22"), (0.4, "#7C5A2A"), (0.7, "#8A6A30"), (1.0, "#5C6B2A")])
    col = mix(col, leaf_col, leaves * 0.6)
    height = moss * 0.8 + dome * leaves * 0.5 + fine * 0.3
    return finish(light(col, height, 6.0, ambient=0.6))


def dirt(n=384):
    """Worn earth path: packed tan soil, damp patches, grit and a scatter of small stones."""
    big = fbm(n, 4, 3, 301)
    mid = fbm(n, 16, 3, 302)
    grit = fbm(n, 96, 2, 303)
    t = np.clip(0.5 + (big - 0.5) * 0.8 + (mid - 0.5) * 0.6 + (grit - 0.5) * 0.5, 0, 1)
    col = ramp(t, [(0.0, "#6E5636"), (0.35, "#927548"), (0.6, "#AD8C58"), (0.85, "#C2A26A"), (1.0, "#D2B67E")])
    stones, ident, dome = scatter(n, 150, 304, 1.4, 3.8)
    stone_col = ramp(ident, [(0.0, "#8E8672"), (0.5, "#A9A08A"), (1.0, "#C2BAA4")])
    col = mix(col, stone_col, stones * 0.9)
    cracks = sstep(0.0, 0.06, np.abs(fbm(n, 10, 2, 305) - 0.5))
    col *= (0.86 + 0.14 * cracks)[..., None]
    height = mid * 0.6 + grit * 0.25 + dome * stones * 1.2
    return finish(light(col, height, 9.0, ambient=0.55))


def cobble(n=384, cells=22):
    """Rounded cobblestones in a dirt bed (streets of the mid tiers).  About 0.36 grid units per stone."""
    f1, f2, ident, ox, oy = worley(n, cells, 401, jitter=0.7)
    edge = f2 - f1                                        # 0 on the joints
    stone = sstep(0.03, 0.14, edge)
    dome = np.clip(1.0 - (f1 / 0.62) ** 2, 0.0, 1.0) ** 0.6
    grain = fbm(n, 64, 2, 402)
    base = ramp(ident, [(0.0, "#9A9382"), (0.3, "#ADA693"), (0.55, "#BDB6A2"), (0.8, "#CBC4AE"), (1.0, "#B4A88C")])
    base *= (0.92 + 0.16 * grain)[..., None]
    wear = fbm(n, 5, 2, 403)
    base *= (0.93 + 0.12 * wear)[..., None]
    joint = rgb("#7A6C52") * (0.85 + 0.3 * grain)[..., None]
    moss = sstep(0.62, 0.8, fbm(n, 7, 3, 404)) * (1.0 - stone)
    joint = mix(joint, rgb("#5F7A3C"), moss * 0.7)
    col = mix(joint, base, stone)
    height = stone * (0.6 + 0.4 * dome) + grain * 0.05
    return finish(light(col, height, 13.0, ambient=0.58))


def _slabs(n, rows, seed, lo=1.1, hi=2.2):
    """Periodic running-bond layout of rectangular slabs.  Returns (edge distance in px, ident 0..1)."""
    rs = np.random.RandomState(seed)
    row_h = n / rows
    x, y = _grid(n)
    edge = np.zeros((n, n))
    ident = np.zeros((n, n))
    for r in range(rows):
        cuts = [0.0]
        while True:
            w = (lo + (hi - lo) * rs.rand()) * row_h
            if cuts[-1] + w > n - lo * row_h * 0.6:
                break
            cuts.append(cuts[-1] + w)
        cuts.append(float(n))
        shift = rs.rand() * n
        band = (y >= r * row_h) & (y < (r + 1) * row_h)
        xs = (x + shift) % n
        dy = np.minimum(y - r * row_h, (r + 1) * row_h - y)
        for k in range(len(cuts) - 1):
            idv = rs.rand()
            inside = band & (xs >= cuts[k]) & (xs < cuts[k + 1])
            dx = np.minimum(xs - cuts[k], cuts[k + 1] - xs)
            edge = np.where(inside, np.minimum(dx, dy), edge)
            ident = np.where(inside, idv, ident)
    return edge, ident


def flag(n=384, rows=12):
    """Plaza flagstones: cut slabs in running bond, worn and slightly uneven.  0.67 grid units per row."""
    edge, ident = _slabs(n, rows, 501)
    grain = fbm(n, 48, 3, 502)
    stone = sstep(0.5, 2.0, edge)
    base = ramp(ident, [(0.0, "#A39C8A"), (0.35, "#B7B09C"), (0.6, "#C6BFAA"), (0.85, "#D2CAB4"), (1.0, "#BDAF94")])
    base *= (0.9 + 0.2 * grain)[..., None]
    stain = fbm(n, 6, 3, 503)
    base *= (0.9 + 0.18 * stain)[..., None]
    joint = rgb("#80745C") * (0.85 + 0.3 * grain)[..., None]
    col = mix(joint, base, stone)
    tilt = pnoise(n, 6, 504) * 0.25                        # slabs settle unevenly
    height = stone * (1.0 + (ident - 0.5) * 0.35) + tilt + grain * 0.05
    return finish(light(col, height, 11.0, ambient=0.56))


def marble(n=384, tiles=8):
    """Pale marble paving in a two-tone diagonal check with fine veins (top castle tiers)."""
    x, y = _grid(n)
    u = x * (tiles / n)
    v = y * (tiles / n)
    iu, iv = np.floor(u).astype(int), np.floor(v).astype(int)
    fu, fv = u - iu, v - iv
    edge = np.minimum(np.minimum(fu, 1 - fu), np.minimum(fv, 1 - fv)) * (n / tiles)
    check = ((iu + iv) % 2).astype(np.float64)
    rs = np.random.RandomState(601)
    tone = rs.rand(tiles, tiles)[iv % tiles, iu % tiles]
    vein = np.abs(fbm(n, 5, 4, 602) - 0.5)
    veins = 1.0 - sstep(0.0, 0.035, vein)
    cloud = fbm(n, 9, 3, 603)
    light_c = rgb("#E9E2D2")
    dark_c = rgb("#CFC8BC")
    col = mix(light_c, dark_c, check * 0.75)
    col = col * (0.94 + 0.10 * cloud + 0.05 * (tone - 0.5))[..., None]
    col = mix(col, rgb("#A9A4A0"), veins * 0.35)
    stone = sstep(0.4, 1.8, edge)
    col = mix(rgb("#9A917E")[None, None, :] * np.ones((n, n, 1)), col, stone)
    height = stone + cloud * 0.05
    return finish(light(col, height, 9.0, ambient=0.55))


def water(n=256):
    """River and lake surface: deep-to-light blue with thin, wavy ripple crests.  The game scrolls two copies
    over each other, so the crests are sparse and the base is calm."""
    swell = fbm(n, 3, 3, 701)
    r1 = 1.0 - np.abs(fbm(n, 5, 3, 702) - 0.5) * 2.0       # ridged noise: thin wandering lines
    r2 = 1.0 - np.abs(fbm(n, 9, 2, 703) - 0.5) * 2.0
    crest = sstep(0.86, 0.985, r1) * 0.65 + sstep(0.88, 0.985, r2) * 0.45
    small = fbm(n, 24, 2, 704)
    t = np.clip(0.45 + (swell - 0.5) * 0.85 + (small - 0.5) * 0.22, 0, 1)
    col = ramp(t, [(0.0, "#1F5CA2"), (0.4, "#2B74BC"), (0.7, "#3A8ACE"), (1.0, "#56A4DE")])
    col = mix(col, rgb("#C4E8FA"), np.clip(crest, 0, 1) * (0.35 + 0.3 * swell))
    height = swell * 0.8 + crest * 0.2 + small * 0.15
    return finish(light(col, height, 4.0, ambient=0.66))


def snow(n=256):
    """Snow field: soft blue-shadowed drifts and a faint sparkle."""
    drift = fbm(n, 3, 4, 801)
    mid = fbm(n, 9, 3, 802)
    fine = fbm(n, 40, 2, 803)
    t = np.clip(0.58 + (drift - 0.5) * 0.8 + (mid - 0.5) * 0.35 + (fine - 0.5) * 0.1, 0, 1)
    col = ramp(t, [(0.0, "#B9CEE2"), (0.4, "#D8E5EF"), (0.75, "#EEF3F7"), (1.0, "#FFFFFF")])
    height = drift * 1.3 + mid * 0.45
    out = light(col, height, 12.0, ambient=0.68)
    sparkle = (np.random.RandomState(804).rand(n, n) > 0.998).astype(np.float64)
    out = mix(out, np.array([1.0, 1.0, 1.0]), blur(sparkle, 1) * 4.0)
    return finish(out)


def sand(n=256):
    """Desert sand: wind ripples across the tile, dunes of light and shade."""
    dune = fbm(n, 3, 3, 901)
    x, y = _grid(n)
    warp = (fbm(n, 4, 2, 902) - 0.5) * 2.4
    ripple = 0.5 + 0.5 * np.sin((x + y) * (2 * math.pi * 14 / n) + warp * 2.5)
    fine = fbm(n, 64, 2, 903)
    t = np.clip(0.5 + (dune - 0.5) * 0.9 + (ripple - 0.5) * 0.16 + (fine - 0.5) * 0.22, 0, 1)
    col = ramp(t, [(0.0, "#B8935A"), (0.35, "#D0AD6E"), (0.65, "#E2C486"), (1.0, "#F2DCA4")])
    height = dune * 1.4 + ripple * 0.16 + fine * 0.05
    return finish(light(col, height, 10.0, ambient=0.6))


def rock(n=384):
    """Mountain rock: fractured grey-brown stone with ledges that catch the light."""
    f1, f2, ident, _, _ = worley(n, 7, 1001, jitter=1.0)
    g1, g2, _, _, _ = worley(n, 19, 1002, jitter=1.0)
    crack = sstep(0.0, 0.10, f2 - f1) * (0.6 + 0.4 * sstep(0.0, 0.14, g2 - g1))
    ridged = 1.0 - np.abs(fbm(n, 6, 4, 1003) - 0.5) * 2.0
    grain = fbm(n, 56, 2, 1004)
    t = np.clip(0.2 + ident * 0.45 + (ridged - 0.5) * 0.5 + (grain - 0.5) * 0.3, 0, 1)
    col = ramp(t, [(0.0, "#6F6A60"), (0.4, "#8B857A"), (0.7, "#A39C8E"), (1.0, "#BDB5A4")])
    col = mix(rgb("#4E4A44")[None, None, :] * np.ones((n, n, 1)), col, crack)
    lichen = sstep(0.66, 0.8, fbm(n, 9, 3, 1005))
    col = mix(col, rgb("#7E8A5A"), lichen * 0.3)
    height = ident * 1.3 + ridged * 0.9 + crack * 0.5 + grain * 0.12
    return finish(light(col, height, 13.0, ambient=0.5))


# ---------------------------------------------------------------------------------------------
# atlases with alpha
# ---------------------------------------------------------------------------------------------

DECAL = 64          # cell size of tex_decals (4 columns x 2 rows)


def _blob(cell, cx, cy, r, soft=1.0, squash=1.0):
    y, x = np.mgrid[0:cell, 0:cell].astype(np.float64)
    d = np.sqrt((x - cx) ** 2 + ((y - cy) * squash) ** 2)
    return 1.0 - sstep(r - soft, r + soft * 0.2, d)


def _paint(dst, cov, col):
    """Source-over of a flat colour with coverage onto a premultiplied-free RGBA float cell."""
    a = dst[..., 3]
    out_a = cov + a * (1.0 - cov)
    safe = np.where(out_a > 1e-6, out_a, 1.0)
    for k in range(3):
        dst[..., k] = (col[k] * cov + dst[..., k] * a * (1.0 - cov)) / safe
    dst[..., 3] = out_a


def _flowers(seed, petals, heart, count):
    rs = np.random.RandomState(seed)
    c = np.zeros((DECAL, DECAL, 4))
    pts = []
    while len(pts) < count:
        ang, rad = rs.rand() * 2 * math.pi, (rs.rand() ** 0.7) * 22.0
        p = (32 + math.cos(ang) * rad, 32 + math.sin(ang) * rad)
        if all((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2 > 30 for q in pts):
            pts.append(p)
    for (px, py) in pts:
        _paint(c, _blob(DECAL, px + 0.9, py - 0.9, 3.0, 1.4) * 0.3, rgb("#1F4A24"))       # leaf shadow
        r = 1.5 + rs.rand() * 0.9
        col = rgb(petals[rs.randint(len(petals))])
        for k in range(5):
            a = k * 2 * math.pi / 5 + rs.rand()
            _paint(c, _blob(DECAL, px + math.cos(a) * r, py + math.sin(a) * r, r * 0.95, 0.8), col)
        _paint(c, _blob(DECAL, px, py, r * 0.55, 0.6), rgb(heart))
    return c


def _tuft(seed, dark, lite):
    rs = np.random.RandomState(seed)
    c = np.zeros((DECAL, DECAL, 4))
    _paint(c, _blob(DECAL, 33, 31, 17, 9) * 0.5, rgb(dark))
    for _ in range(46):
        ang, rad = rs.rand() * 2 * math.pi, (rs.rand() ** 0.8) * 15.0
        bx, by = 32 + math.cos(ang) * rad, 32 + math.sin(ang) * rad
        ln = 5.0 + rs.rand() * 6.0
        col = mix(rgb(dark), rgb(lite), np.array(rs.rand()))
        for s in np.linspace(0.0, 1.0, 7):
            # blades lie along the (-1, -1) diagonal: straight up on screen after the isometric squash
            _paint(c, _blob(DECAL, bx - s * ln * 0.7, by - s * ln * 0.7, 1.25 - s * 0.6, 0.7) * 0.9, col)
    return c


def _pebbles(seed):
    rs = np.random.RandomState(seed)
    c = np.zeros((DECAL, DECAL, 4))
    for _ in range(9):
        px, py = 14 + rs.rand() * 36, 14 + rs.rand() * 36
        r = 2.2 + rs.rand() * 3.6
        _paint(c, _blob(DECAL, px + r * 0.5, py - r * 0.45, r * 1.05, 1.6) * 0.42, rgb("#1B2A1E"))   # cast shadow
        tone = rs.rand()
        _paint(c, _blob(DECAL, px, py, r, 0.9), mix(rgb("#8E887A"), rgb("#B7B0A0"), np.array(tone)))
        _paint(c, _blob(DECAL, px - r * 0.3, py + r * 0.3, r * 0.5, 1.2) * 0.55, rgb("#D8D2C2"))    # lit side
    return c


def _leaves(seed, cols):
    rs = np.random.RandomState(seed)
    c = np.zeros((DECAL, DECAL, 4))
    for _ in range(26):
        px, py = 8 + rs.rand() * 48, 8 + rs.rand() * 48
        r = 1.6 + rs.rand() * 1.6
        a = rs.rand() * math.pi
        col = rgb(cols[rs.randint(len(cols))])
        for s in (-0.8, 0.0, 0.8):
            _paint(c, _blob(DECAL, px + math.cos(a) * s * r, py + math.sin(a) * s * r, r * 0.8, 0.7) * 0.92, col)
    return c


def decals():
    """4 x 2 atlas of flat ground details (top-down, grid space, straight alpha):
    row 0: white daisies, yellow buttercups, red poppies, blue-violet flowers
    row 1: dark tuft, light tuft, pebbles, fallen leaves."""
    cells = [
        _flowers(11, ["#FFFFFF", "#F4F1E4"], "#F2C230", 9),
        _flowers(12, ["#FFE04A", "#FFD12E", "#FFF08A"], "#E08A1E", 10),
        _flowers(13, ["#E8402E", "#F0563A", "#FF8A6A"], "#3A2018", 7),
        _flowers(14, ["#8A7BE8", "#6FA0F0", "#C9A0F4"], "#FFF3B0", 9),
        _tuft(15, "#2E6A2C", "#5C9E3C"),
        _tuft(16, "#5E9E3A", "#A4D05E"),
        _pebbles(17),
        _leaves(18, ["#C8642A", "#D98A2E", "#B8442A", "#E0B040"]),
    ]
    sheet = np.zeros((DECAL * 2, DECAL * 4, 4))
    for i, c in enumerate(cells):
        sheet[(i // 4) * DECAL:(i // 4 + 1) * DECAL, (i % 4) * DECAL:(i % 4 + 1) * DECAL] = c
    return Image.fromarray((np.clip(sheet, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


PEAK = 192          # cell size of tex_peaks (3 columns x 2 rows)


def _peak(seed, snowline, snowy):
    """One mountain massif as a small oblique relief: a ridged height field drawn front to back
    (screen y = ground y * 0.5 - height), lit from the left-front like the sprites, with a snow cap and a
    soft ground shadow to the right.  Rendered at 2 x and scaled down."""
    n = PEAK * 2
    rs = np.random.RandomState(seed)
    y, x = np.mgrid[0:n, 0:n].astype(np.float64)
    h = np.zeros((n, n))
    for k in range(3 + rs.randint(3)):
        cx = n * (0.5 + (rs.rand() - 0.5) * (0.0 if k == 0 else 0.5))
        cy = n * (0.5 + (rs.rand() - 0.5) * (0.0 if k == 0 else 0.3))
        rad = n * (0.34 if k == 0 else 0.16 + 0.12 * rs.rand())
        top = 1.0 if k == 0 else 0.45 + 0.4 * rs.rand()
        d = np.sqrt((x - cx) ** 2 + ((y - cy) * 1.5) ** 2) / rad
        h = np.maximum(h, top * np.clip(1.0 - d, 0.0, 1.0) ** 1.15)
    ridge = 1.0 - np.abs(fbm(n, 4, 4, seed + 1) - 0.5) * 2.0
    crag = fbm(n, 12, 3, seed + 2)
    h = h * (0.55 + 0.6 * ridge + 0.2 * crag)
    h = h * sstep(0.0, 0.1, h)
    hz = h * n * 0.36                                          # height in pixels
    hx = (np.roll(hz, -1, 1) - np.roll(hz, 1, 1)) * 0.5
    hy = (np.roll(hz, -1, 0) - np.roll(hz, 1, 0)) * 0.5
    inv = 1.0 / np.sqrt(hx * hx + hy * hy + 1.0)
    lx, ly, lz = -0.62, 0.30, 0.72
    ndl = np.clip((-hx * lx - hy * ly + lz) * inv, 0.0, 1.0)
    rock_t = np.clip(0.2 + crag * 0.5 + ridge * 0.3, 0, 1)
    col = ramp(rock_t, [(0.0, "#716A5E"), (0.5, "#948C7C"), (1.0, "#B8AF9C")])
    grass_m = (1.0 - sstep(0.06, 0.22, h)) * (0.0 if snowy else 1.0)        # green foothills
    col = mix(col, rgb("#5E8E44"), grass_m * 0.8)
    slope = 1.0 - inv
    snow_m = sstep(snowline - 0.07, snowline + 0.07, h + (crag - 0.5) * 0.14) * (1.0 - sstep(0.62, 0.82, slope))
    if snowy:
        snow_m = np.maximum(snow_m, (1.0 - sstep(0.35, 0.7, slope)) * 0.92)
    col = mix(col, rgb("#F4F7FA"), snow_m)
    k = 0.42 + 0.85 * ndl
    warm = sstep(0.45, 0.8, ndl)[..., None]
    col = col * k[..., None] * (COOL * 0.96 + (WARM - COOL * 0.96) * warm)
    out = np.zeros((n, n, 4))
    base0 = n * 0.42
    sh = blur(np.roll(np.roll(sstep(0.0, 0.25, h), n // 14, axis=1), 0, axis=0), n // 40)
    rows = np.clip(np.round(y * 0.5 + base0).astype(int), 0, n - 1)
    cols_i = x.astype(int)
    np.maximum.at(out[..., 3], (rows, cols_i), sh * 0.4)
    out[..., :3] = rgb("#1B1730")
    top_y = np.full(n, n * 2, dtype=int)
    for row in range(n - 1, -1, -1):                           # near to far; keep the highest pixel drawn
        base = row * 0.5 + base0
        ys = np.round(base - hz[row]).astype(int)
        lim = np.minimum(top_y, int(round(base)) + 1)
        for c in np.nonzero((hz[row] > 0.5) & (ys < lim))[0]:
            y0, y1 = max(ys[c], 0), min(lim[c], n)
            if y1 > y0:
                out[y0:y1, c, :3] = col[row, c]
                out[y0:y1, c, 3] = 1.0
                top_y[c] = y0
    img = Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")
    # premultiplied downscale so the dark shadow colour does not bleed into the silhouette
    pm = np.asarray(img).astype(np.float64) / 255.0
    pm[..., :3] *= pm[..., 3:4]
    small = pm.reshape(PEAK, 2, PEAK, 2, 4).mean(axis=(1, 3))
    a = np.where(small[..., 3:4] > 1e-6, small[..., 3:4], 1.0)
    small[..., :3] /= a
    return small


def peaks():
    """3 x 2 atlas of mountain massifs with alpha: bare rock with a small snow cap (row 0), snowbound (row 1)."""
    sheet = np.zeros((PEAK * 2, PEAK * 3, 4))
    for i in range(3):
        sheet[0:PEAK, i * PEAK:(i + 1) * PEAK] = _peak(1100 + i * 7, 0.78, False)
        sheet[PEAK:2 * PEAK, i * PEAK:(i + 1) * PEAK] = _peak(1100 + i * 7, 0.5, True)
    return Image.fromarray((np.clip(sheet, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


TEXTURES = {
    "grass": grass, "forest": forest, "dirt": dirt, "cobble": cobble, "flag": flag, "marble": marble,
    "water": water, "snow": snow, "sand": sand, "rock": rock, "decals": decals, "peaks": peaks,
}
QUALITY = {"grass": 86, "forest": 84, "water": 84, "snow": 86, "sand": 86, "decals": 92, "peaks": 90}


# ---------------------------------------------------------------------------------------------
# preview / calibration / command line
# ---------------------------------------------------------------------------------------------

def iso(img, span, zoom=2.0):
    """Project a top-down tile (covering span x span grid units, tiled 2 x 2) the way the game does."""
    n = img.size[0]
    big = Image.new("RGB", (n * 2, n * 2))
    for j in range(2):
        for i in range(2):
            big.paste(img.convert("RGB"), (i * n, j * n))
    g = span * 2
    u, v = 14.0 * zoom, 7.0 * zoom
    w, h = int(2 * g * u), int(2 * g * v)
    # output (sx, sy) -> grid: gx = (sx' / u + sy / v) / 2, gy = (sy / v - sx' / u) / 2, sx' = sx - g * u
    k = n * 2 / g
    coef = (k / (2 * u), k / (2 * v), -k * g / 2, -k / (2 * u), k / (2 * v), k * g / 2)
    out = big.transform((w, h), Image.AFFINE, coef, resample=Image.BICUBIC)
    mask = Image.new("L", (n * 2, n * 2), 255).transform((w, h), Image.AFFINE, coef, resample=Image.NEAREST)
    out.putalpha(mask)
    return out


def preview(path, made):
    spans = {"grass": 12, "forest": 9, "dirt": 8, "cobble": 8, "flag": 8, "marble": 8, "water": 6, "snow": 8,
             "sand": 8, "rock": 8}
    names = [k for k in spans if k in made]
    tiles = [iso(made[k], spans[k]) for k in names]
    cw = max(t.size[0] for t in tiles) if tiles else 1
    ch = max(t.size[1] for t in tiles) if tiles else 1
    cols = 3
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGB", (cw * cols, ch * rows + 260), (27, 53, 36))
    for i, t in enumerate(tiles):
        sheet.paste(t, ((i % cols) * cw + (cw - t.size[0]) // 2, (i // cols) * ch + (ch - t.size[1]) // 2), t)
    x = 8
    for k in ("decals", "peaks"):
        if k in made:
            bg = Image.new("RGB", made[k].size, (95, 162, 62))
            bg.paste(made[k], (0, 0), made[k])
            bg = bg.resize((bg.size[0] * 250 // bg.size[1], 250))
            sheet.paste(bg, (x, ch * rows + 5))
            x += bg.size[0] + 12
    sheet.save(path)


def calibrate():
    """Print the lit colour of render3d's flat ground pads: the palette the textures are tuned against."""
    import render3d as r3
    for kind in ("grass", "dirt", "paved", "marble", "soil"):
        sc = r3.Scene("building", outline=0)
        sc.add(r3.box(0, 0, 4, 4, -1.5, 0), r3.m_ground(kind))
        a = np.asarray(r3.render(sc, ss=2, px=4)).astype(float)
        m = a[420:540, 136:376, :3].reshape(-1, 3).mean(0)
        print("m_ground(%-6s) lit top: #%02X%02X%02X" % ((kind,) + tuple(int(round(v)) for v in m)))


def main(argv):
    png_dir, preview_path, names = None, None, []
    it = iter(argv)
    for a in it:
        if a == "--png":
            png_dir = next(it)
        elif a == "--preview":
            preview_path = next(it)
        elif a == "--calibrate":
            return calibrate()
        elif a == "--list":
            print("\n".join("tex_" + k for k in TEXTURES))
            return None
        else:
            names.append(a.replace("tex_", ""))
    unknown = [k for k in names if k not in TEXTURES]
    if unknown:
        sys.exit("unknown texture: " + ", ".join(unknown))
    os.makedirs(OUT_DIR, exist_ok=True)
    made, total = {}, 0
    for key in (names or list(TEXTURES)):
        img = TEXTURES[key]()
        made[key] = img
        out = os.path.join(OUT_DIR, "tex_%s.webp" % key)
        img.save(out, "WEBP", quality=QUALITY.get(key, 88), method=6)
        size = os.path.getsize(out)
        total += size
        print("tex_%-7s %4d x %-4d %6.1f KB" % (key, img.size[0], img.size[1], size / 1024.0))
        if png_dir:
            os.makedirs(png_dir, exist_ok=True)
            img.save(os.path.join(png_dir, "tex_%s.png" % key))
    print("total %.1f KB" % (total / 1024.0))
    if preview_path:
        preview(preview_path, made)
    return None


if __name__ == "__main__":
    main(sys.argv[1:])
