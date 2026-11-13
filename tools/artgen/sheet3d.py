#!/usr/bin/env python3
"""sheet3d.py - review sheet for the 3D sprites: old vector art next to the new renders, on grass.

Run:  python3 tools/artgen/sheet3d.py out.png [name-prefix ...]
Each cell shows, at 2x game size, the current VectorDrawable (left) and the new WebP (right), and both
again at 1x game size (old above new).  Only reads files; needs Pillow + numpy.

The VectorDrawable rasteriser below understands just what tools/artgen writes: M / L / l / a / Z paths,
solid or gradient fills, strokes, and <group> scale-about-pivot.
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[2]
NEW = ROOT / "app/src/main/res/drawable-xxxhdpi"
OLD = ROOT / "app/src/main/res/drawable"
GRASS = "#5a9a3c"
A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"
_TOK = re.compile(r"[MmLlHhVvAaZz]|-?\d*\.?\d+(?:e-?\d+)?")


def _argb(c, a=1.0):
    c = c.lstrip("#")
    if len(c) == 8:
        a, c = a * int(c[0:2], 16) / 255.0, c[2:]
    return int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16), a


def _subpaths(d):
    """pathData -> list of (points, closed)."""
    toks = _TOK.findall(d)
    out, pts, i, cmd, x, y = [], [], 0, "M", 0.0, 0.0

    def num():
        nonlocal i
        i += 1
        return float(toks[i - 1])

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                if pts:
                    out.append((pts, True))
                pts = []
                continue
        if cmd in "Mm":
            if pts:
                out.append((pts, False))
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if cmd == "m" else (nx, ny)
            pts = [(x, y)]
            cmd = "l" if cmd == "m" else "L"
        elif cmd in "Ll":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if cmd == "l" else (nx, ny)
            pts.append((x, y))
        elif cmd in "Hh":
            nx = num()
            x = x + nx if cmd == "h" else nx
            pts.append((x, y))
        elif cmd in "Vv":
            ny = num()
            y = y + ny if cmd == "v" else ny
            pts.append((x, y))
        elif cmd in "Aa":      # half ellipses of an oval: "a rx,ry 0 1,0 dx,0"
            rx, ry = num(), num()
            num(), num(), num()
            nx, ny = num(), num()
            ex, ey = (x + nx, y + ny) if cmd == "a" else (nx, ny)
            cx, cy = (x + ex) / 2.0, (y + ey) / 2.0
            sgn = 1.0 if ex > x else -1.0
            for k in range(1, 13):
                th = np.pi * k / 12.0
                pts.append((cx - sgn * rx * np.cos(th), cy - sgn * ry * np.sin(th)))
            x, y = ex, ey
        else:
            i += 1
    if pts:
        out.append((pts, False))
    return out


def raster_vector(path, k=6):
    """Rasterise a tools/artgen VectorDrawable at k pixels per viewport unit -> RGBA image."""
    root = ET.parse(path).getroot()
    vw, vh = int(float(root.get(A + "viewportWidth"))), int(float(root.get(A + "viewportHeight")))
    W, H = vw * k, vh * k
    canvas = np.zeros((H, W, 4), np.float32)         # premultiplied

    def paint(mask, x0, y0, rgba_fn):
        m = np.asarray(mask, np.float32) / 255.0
        if not m.any():
            return
        h, w = m.shape
        col = rgba_fn(x0, y0, w, h)                   # (h, w, 4) straight alpha
        a = (col[..., 3] * m)[..., None]
        reg = canvas[y0:y0 + h, x0:x0 + w]
        reg[..., :3] = col[..., :3] * a + reg[..., :3] * (1.0 - a)
        reg[..., 3:] = a + reg[..., 3:] * (1.0 - a)

    def draw(el, xf):
        px, py, sx, sy = xf
        subs = [([(px + (x - px) * sx, py + (y - py) * sy) for x, y in pts], cl) for pts, cl in _subpaths(el.get(A + "pathData"))]
        subs = [s for s in subs if len(s[0]) >= 2]
        if not subs:
            return
        xs = [p[0] for s, _ in subs for p in s]
        ys = [p[1] for s, _ in subs for p in s]
        sw = float(el.get(A + "strokeWidth", "0"))
        x0, y0 = max(int((min(xs) - sw) * k) - 1, 0), max(int((min(ys) - sw) * k) - 1, 0)
        x1, y1 = min(int((max(xs) + sw) * k) + 2, W), min(int((max(ys) + sw) * k) + 2, H)
        if x1 <= x0 or y1 <= y0:
            return
        loc = [([((x * k - x0), (y * k - y0)) for x, y in pts], cl) for pts, cl in subs]
        grad = None
        for at in el.findall(AAPT + "attr"):
            grad = at.find("gradient")
        fill = el.get(A + "fillColor")
        if grad is not None or fill:
            mask = Image.new("L", (x1 - x0, y1 - y0), 0)
            dr = ImageDraw.Draw(mask)
            for pts, _ in loc:
                if len(pts) >= 3:
                    dr.polygon(pts, fill=255)
            fa = float(el.get(A + "fillAlpha", "1"))
            if grad is None:
                r, g, b, a = _argb(fill, fa)
                paint(mask, x0, y0, lambda *_: np.broadcast_to(np.array([r / 255.0, g / 255.0, b / 255.0, a], np.float32), (y1 - y0, x1 - x0, 4)))
            else:
                items = [(float(it.get(A + "offset")), _argb(it.get(A + "color"))) for it in grad.findall("item")]
                if not items:
                    items = [(0.0, _argb(grad.get(A + "startColor"))), (1.0, _argb(grad.get(A + "endColor")))]
                offs = np.array([o for o, _ in items])
                cols = np.array([[c[0] / 255.0, c[1] / 255.0, c[2] / 255.0, c[3]] for _, c in items], np.float32)

                def gfill(gx, gy, w, h, grad=grad, offs=offs, cols=cols):
                    yy, xx = np.mgrid[0:h, 0:w]
                    ux = px + ((xx + gx + 0.5) / k - px) / sx          # back to the group's local space
                    uy = py + ((yy + gy + 0.5) / k - py) / sy
                    if grad.get(A + "type") == "radial":
                        t = np.hypot(ux - float(grad.get(A + "centerX")), uy - float(grad.get(A + "centerY"))) / float(grad.get(A + "gradientRadius"))
                    else:
                        ax, ay = float(grad.get(A + "startX")), float(grad.get(A + "startY"))
                        bx, by = float(grad.get(A + "endX")) - ax, float(grad.get(A + "endY")) - ay
                        t = ((ux - ax) * bx + (uy - ay) * by) / max(bx * bx + by * by, 1e-9)
                    t = np.clip(t, 0.0, 1.0)
                    return np.stack([np.interp(t, offs, cols[:, c]) for c in range(4)], axis=-1).astype(np.float32)

                paint(mask, x0, y0, gfill)
        stroke = el.get(A + "strokeColor")
        if stroke and sw > 0:
            mask = Image.new("L", (x1 - x0, y1 - y0), 0)
            dr = ImageDraw.Draw(mask)
            wd = max(1, int(round(sw * k * (sx + sy) / 2.0)))
            for pts, cl in loc:
                dr.line(pts + ([pts[0]] if cl else []), fill=255, width=wd, joint="curve")
            r, g, b, a = _argb(stroke, float(el.get(A + "strokeAlpha", "1")))
            paint(mask, x0, y0, lambda *_: np.broadcast_to(np.array([r / 255.0, g / 255.0, b / 255.0, a], np.float32), (y1 - y0, x1 - x0, 4)))

    def walk(node, xf):
        for el in node:
            if el.tag == "group":
                walk(el, (float(el.get(A + "pivotX", "0")), float(el.get(A + "pivotY", "0")),
                          float(el.get(A + "scaleX", "1")), float(el.get(A + "scaleY", "1"))))
            elif el.tag == "path":
                draw(el, xf)

    walk(root, (0.0, 0.0, 1.0, 1.0))
    rgb = canvas[..., :3] / np.maximum(canvas[..., 3:], 1e-4)
    out = np.dstack([rgb, canvas[..., 3]])
    return Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


def _on_grass(img, w, h):
    img = img.resize((w, h), Image.LANCZOS)
    bg = Image.new("RGBA", (w, h), GRASS)
    bg.alpha_composite(img)
    return bg


def make(out, names, new_dir=NEW, old_dir=OLD, cols=None):
    """Write the review sheet for the given resource names."""
    cells = []
    for n in names:
        new = Image.open(pathlib.Path(new_dir) / (n + ".webp")).convert("RGBA")
        vw, vh = new.width // 4, new.height // 4
        oldp = pathlib.Path(old_dir) / (n + ".xml")
        old = raster_vector(oldp) if oldp.exists() else Image.new("RGBA", (vw, vh), (0, 0, 0, 0))
        cell = Image.new("RGBA", (vw * 5 + 16, vh * 2 + 18), GRASS)
        cell.paste(_on_grass(old, vw * 2, vh * 2), (4, 16))
        cell.paste(_on_grass(new, vw * 2, vh * 2), (vw * 2 + 8, 16))
        cell.paste(_on_grass(old, vw, vh), (vw * 4 + 12, 16))
        cell.paste(_on_grass(new, vw, vh), (vw * 4 + 12, 16 + vh))
        d = ImageDraw.Draw(cell)
        d.text((6, 3), "%s    old vector 2x | new 3D 2x | 1x old / new" % n, fill="#FFFFFF")
        d.rectangle([0, 0, cell.width - 1, cell.height - 1], outline="#3E7A28")
        cells.append(cell)
    # flow layout: rows of equal-height cells
    maxw = 1980
    rows, row, x = [], [], 0
    for c in cells:
        if row and (x + c.width > maxw or c.height != row[0].height):
            rows.append(row)
            row, x = [], 0
        row.append(c)
        x += c.width
    if row:
        rows.append(row)
    W = max(sum(c.width for c in r) for r in rows)
    H = sum(r[0].height for r in rows)
    sheet = Image.new("RGB", (W, H), GRASS)
    y = 0
    for r in rows:
        x = 0
        for c in r:
            sheet.paste(c.convert("RGB"), (x, y))
            x += c.width
        y += r[0].height
    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print("wrote %s %dx%d (%d sprites)" % (out, W, H, len(cells)))


if __name__ == "__main__":
    words = sys.argv[2:]
    have = sorted(p.stem for p in NEW.glob("*.webp"))
    make(sys.argv[1], [n for n in have if not words or any(n.startswith(w) for w in words)])
