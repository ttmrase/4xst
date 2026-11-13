"""開発用: 生成した図形の見本を作る（成果物の生成には不要）。
実行: python3 tools/artgen/preview.py out.png [type,type,...]        建物の PNG（Pillow が要る）
      python3 tools/artgen/preview.py skins|decor|portraits out.png   城のスキン / 装飾 / 胸像の PNG（Pillow が要る）
      python3 tools/artgen/preview.py html out.html [type,type,...]   建物・城のスキン・装飾の HTML（Pillow は不要）

PNG（Pillow）は単色の多角形しか描けないので近似になる: グラデーションは stops の平均の色で塗り、
放射グラデーションのにじみ（影・あかり）は不透明度を下げた単色にする。
正確な見本は html のほう: Scene.to_svg() の SVG を草色の地に並べる（2 倍と等倍）。ブラウザで開けば、
グラデーション・縁取り・落ち影まで VectorDrawable と同じに見える。
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
import iso, gen_buildings, gen_decor

try:
    from PIL import Image, ImageDraw
except ImportError:  # html の見本だけなら Pillow はいらない
    Image = ImageDraw = None

SS = 3


def _subpaths(sh):
    """図形の部分パス [(点の並び, 閉じるか), ...]（group の拡大縮小 xf も反映する）。"""
    subs = [(sh.pts, sh.closed)] + list(sh.subs or ())
    if sh.xf:
        px, py, sx, sy = sh.xf
        subs = [([(px + (x - px) * sx, py + (y - py) * sy) for x, y in pts], closed) for pts, closed in subs]
    return [(pts, closed) for pts, closed in subs if len(pts) >= 2]


def render(scene, bg="#5E8C45"):
    im = Image.new("RGB", (scene.w * SS, scene.h * SS), bg)
    shapes = scene.resolved() if hasattr(scene, "resolved") else scene.shapes
    for sh in shapes:
        a = int(255 * sh.alpha)
        fill = sh.fill
        if iso.is_grad(fill):
            # stops の不透明度も平均して、にじみ（中心が濃く外へ消える）を薄い単色にする
            al = [int(c.lstrip("#")[0:2], 16) / 255.0 if len(c.lstrip("#")) == 8 else 1.0 for _, c in fill.stops]
            a = int(a * sum(al) / len(al) * (0.6 if isinstance(fill, iso.Radial) and min(al) < 0.05 else 1.0))
            fill = iso.flat_of(fill)
        for pts, closed in _subpaths(sh):
            pts = [(x * SS, y * SS) for x, y in pts]
            if fill is not None and closed and len(pts) >= 3:
                r, g, b = iso.hex_to_rgb(fill)
                ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
                ImageDraw.Draw(ov).polygon(pts, fill=(r, g, b, a))
                im.paste(ov, (0, 0), ov)
            if sh.stroke is not None and sh.sw > 0:
                r, g, b = iso.hex_to_rgb(sh.stroke)
                ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
                d = ImageDraw.Draw(ov)
                seq = pts + ([pts[0]] if closed else [])
                sa = a if sh.fill is None else int(255 * getattr(sh, "salpha", 1.0))
                d.line(seq, fill=(r, g, b, sa), width=max(1, int(sh.sw * SS)), joint="curve")
                im.paste(ov, (0, 0), ov)
    return im.resize((scene.w * 2, scene.h * 2), Image.LANCZOS)


def decor_sheet(out):
    items = [(n, gen_decor.build_decor(n)) for n, _ in gen_decor.DECOR]
    for kind in ("x", "y", "tower", "gate"):
        for t in (1, 3, 5):
            items.append(("wall_%s_%d" % (kind, t), gen_decor.build_wall(kind, t)))
    items.append(("site", gen_decor.build_site()))
    cols = 8
    cw, ch = 128 * 2, 160 * 2
    rows = (len(items) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cw, rows * ch), "#444444")
    for k, (name, sc) in enumerate(items):
        im = render(sc)
        sheet.paste(im, ((k % cols) * cw + (cw - im.size[0]) // 2, (k // cols) * ch + (ch - im.size[1]) // 2))
    sheet.save(out)
    print("wrote", out, sheet.size)


def portraits_sheet(out):
    """英雄 12 + ペルソナ 8（個別）→ 名前だけのメンバー 22（汎用）→ プレイヤーのアバター 12 を並べる。"""
    import gen_portraits  # 胸像の見本のときだけ読み込む
    items = [(k, gen_portraits.make(v)) for k, v in gen_portraits.SPECS.items()]
    items += [(k, gen_portraits.make(v)) for k, v in gen_portraits.generic_items() + gen_portraits.avatar_items()]
    cols = 8
    cw, ch = gen_portraits.W * 3 + 12, gen_portraits.W * 3 + 30
    rows = (len(items) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * cw, rows * ch), "#303036")
    d = ImageDraw.Draw(sheet)
    for k, (name, sc) in enumerate(items):
        im = render(sc, "#303036").resize((gen_portraits.W * 3, gen_portraits.W * 3), Image.LANCZOS)
        x, y = (k % cols) * cw + 6, (k // cols) * ch + 4
        sheet.paste(im, (x, y))
        d.text((x + 2, y + gen_portraits.W * 3 + 4), name, fill="#DDDDDD")
    sheet.save(out)
    print("wrote", out, sheet.size)


def skins_sheet(out):
    """城のスキン見本: 行 = 標準 / ice / gold / sakura / dragon、列 = 段階 1..5。"""
    rows = [None] + list(gen_buildings.SKIN_PALETTES)
    cw, ch = iso.W_VP * 2, iso.H_VP * 2
    sheet = Image.new("RGB", (5 * cw, len(rows) * ch), "#444444")
    for r, skin in enumerate(rows):
        for t in range(1, 6):
            sheet.paste(render(gen_buildings.build("castle", t, skin)), ((t - 1) * cw, r * ch))
    sheet.save(out)
    print("wrote", out, sheet.size)


def html_sheet(out, only=None):
    """建物（5 段階）・城のスキン・装飾を SVG で並べた HTML。各行は 2 倍、続けて等倍。"""
    rows, k = [], 0

    def row(label, scenes):
        nonlocal k
        cells = []
        for scale in (2, 1):
            for sc in scenes:
                k += 1
                cells.append("<td>%s</td>" % sc.to_svg(sc.w * scale, "s%d_" % k))
        rows.append("<tr><th>%s</th>%s</tr>" % (label, "".join(cells)))

    for name in [n for n in gen_buildings.TYPES if (only is None or n in only)]:
        row(name, [gen_buildings.build(name, t) for t in range(1, 6)])
    if only is None:
        for skin in gen_buildings.SKIN_PALETTES:
            row("castle " + skin, [gen_buildings.build("castle", t, skin) for t in range(1, 6)])
        names = [n for n, _ in gen_decor.DECOR]
        for i in range(0, len(names), 6):
            row("decor", [gen_decor.build_decor(n) for n in names[i:i + 6]])
        for kind in ("x", "y", "tower", "gate"):
            row("wall " + kind, [gen_decor.build_wall(kind, t) for t in range(1, 6)])
        row("site", [gen_decor.build_site()])
    css = "body{margin:0;background:#5a9a3c;color:#fff;font:11px sans-serif}td{vertical-align:bottom}th{text-align:left;padding:4px}"
    pathlib.Path(out).write_text("<html><head><meta charset='utf-8'><style>%s</style></head><body><table>%s</table></body></html>"
                                 % (css, "".join(rows)), encoding="utf-8")
    print("wrote", out)


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "html":
        only = sys.argv[3].split(",") if len(sys.argv) > 3 else None
        return html_sheet(sys.argv[2] if len(sys.argv) > 2 else "/tmp/preview.html", only)
    if Image is None:
        sys.exit("Pillow がありません。html の見本を使ってください: preview.py html out.html")
    if len(sys.argv) > 1 and sys.argv[1] == "skins":
        return skins_sheet(sys.argv[2] if len(sys.argv) > 2 else "/tmp/castle_skins.png")
    if len(sys.argv) > 1 and sys.argv[1] == "portraits":
        return portraits_sheet(sys.argv[2] if len(sys.argv) > 2 else "/tmp/portraits.png")
    if len(sys.argv) > 1 and sys.argv[1] == "decor":
        return decor_sheet(sys.argv[2] if len(sys.argv) > 2 else "/tmp/decor.png")
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/preview.png"
    only = sys.argv[2].split(",") if len(sys.argv) > 2 else None
    names = [n for n in gen_buildings.TYPES if (only is None or n in only)]
    cols = 5
    cell_w, cell_h = iso.W_VP * 2, iso.H_VP * 2
    rows = len(names)
    sheet = Image.new("RGB", (cols * cell_w, rows * cell_h), "#444444")
    for r, name in enumerate(names):
        for t in range(1, 6):
            sc = gen_buildings.build(name, t)
            sheet.paste(render(sc), ((t - 1) * cell_w, r * cell_h))
    sheet.save(out)
    print("wrote", out, sheet.size)


if __name__ == "__main__":
    main()
