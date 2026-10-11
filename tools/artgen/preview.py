"""開発用: 生成した図形を PNG の見本にする（Pillow があるときだけ。成果物の生成には不要）。
実行: python3 tools/artgen/preview.py out.png [cols]
"""
import sys, pathlib
sys.path.insert(0, str(pathlib.Path(__file__).parent))
from PIL import Image, ImageDraw
import iso, gen_buildings, gen_decor, gen_portraits

SS = 3


def render(scene, bg="#5E8C45"):
    im = Image.new("RGB", (scene.w * SS, scene.h * SS), bg)
    base = ImageDraw.Draw(im, "RGBA")
    for sh in scene.shapes:
        pts = [(x * SS, y * SS) for x, y in sh.pts]
        a = int(255 * sh.alpha)
        if sh.fill is not None:
            r, g, b = iso.hex_to_rgb(sh.fill)
            ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
            ImageDraw.Draw(ov).polygon(pts, fill=(r, g, b, a))
            im.paste(ov, (0, 0), ov)
        if sh.stroke is not None and sh.sw > 0:
            r, g, b = iso.hex_to_rgb(sh.stroke)
            ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
            d = ImageDraw.Draw(ov)
            seq = pts + ([pts[0]] if sh.closed else [])
            d.line(seq, fill=(r, g, b, a if sh.fill is None else 255), width=max(1, int(sh.sw * SS)), joint="curve")
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
    items = [(k, gen_portraits.make(v)) for k, v in gen_portraits.SPECS.items()]
    items += [("gen_%02d" % i, gen_portraits.make(gen_portraits.generic_spec("gen%d" % i))) for i in range(22)]
    items += [("avatar_%02d" % i, gen_portraits.make(gen_portraits.generic_spec("avatar%d" % i))) for i in range(gen_portraits.AVATAR_COUNT)]
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


def main():
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
