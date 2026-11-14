#!/usr/bin/env python3
"""sheet3d.py - review sheet for the 3D sprites, on grass.

Run:  python3 tools/artgen/sheet3d.py out.png [name-prefix ...]
Each cell shows a sprite at 2x game size and, beside it, at 1x game size (the size it has on a phone
at the default zoom).  Only reads the WebP files already on disk; needs Pillow.
"""

import pathlib
import sys

from PIL import Image, ImageDraw

ROOT = pathlib.Path(__file__).resolve().parents[2]
NEW = ROOT / "app/src/main/res/drawable-xxxhdpi"
GRASS = "#5a9a3c"
MAX_W = 1980


def _on_grass(img, w, h):
    img = img.resize((w, h), Image.LANCZOS)
    bg = Image.new("RGBA", (w, h), GRASS)
    bg.alpha_composite(img)
    return bg


def make(out, names, new_dir=NEW):
    """Write the review sheet for the given resource names."""
    cells = []
    for n in names:
        new = Image.open(pathlib.Path(new_dir) / (n + ".webp")).convert("RGBA")
        vw, vh = new.width // 4, new.height // 4
        cell = Image.new("RGBA", (vw * 3 + 12, vh * 2 + 18), GRASS)
        cell.paste(_on_grass(new, vw * 2, vh * 2), (4, 16))
        cell.paste(_on_grass(new, vw, vh), (vw * 2 + 8, 16 + vh))
        d = ImageDraw.Draw(cell)
        d.text((6, 3), "%s   2x | 1x" % n, fill="#FFFFFF")
        d.rectangle([0, 0, cell.width - 1, cell.height - 1], outline="#3E7A28")
        cells.append(cell)
    # flow layout: rows of equal-height cells
    rows, row, x = [], [], 0
    for c in cells:
        if row and (x + c.width > MAX_W or c.height != row[0].height):
            rows.append(row)
            row, x = [], 0
        row.append(c)
        x += c.width
    if row:
        rows.append(row)
    width = max(sum(c.width for c in r) for r in rows)
    height = sum(r[0].height for r in rows)
    sheet = Image.new("RGB", (width, height), GRASS)
    y = 0
    for r in rows:
        x = 0
        for c in r:
            sheet.paste(c.convert("RGB"), (x, y))
            x += c.width
        y += r[0].height
    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    sheet.save(out)
    print("wrote %s %dx%d (%d sprites)" % (out, width, height, len(cells)))


if __name__ == "__main__":
    words = sys.argv[2:]
    have = sorted(p.stem for p in NEW.glob("*.webp"))
    make(sys.argv[1], [n for n in have if not words or any(n.startswith(w) for w in words)])
