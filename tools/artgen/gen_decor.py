#!/usr/bin/env python3
"""領地マップの装飾スプライト（木・岩・小さな家・城壁・街灯・建設現場など）を VectorDrawable として生成する。

実行: python3 tools/artgen/gen_decor.py
出力: app/src/main/res/drawable/deco_*.xml と app/src/main/kotlin/.../ui/territory/SpriteIds.kt（建物 + 装飾の ID 表）
装飾は 64 x 96 の viewport（足元の中心が (32, 80)）。城壁の部品だけ 64 x 64（足元の中心が (32, 48)）。
"""

import math
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import iso  # noqa: E402
from iso import Scene, P, shade, mix  # noqa: E402
import gen_buildings  # noqa: E402

OUT = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"
KOTLIN = pathlib.Path(__file__).resolve().parents[2] / "app/src/main/kotlin/com/ttmrase/crownfall/ui/territory/SpriteIds.kt"


def around(sc_fn, w=64, h=96, ax=32.0, ay=80.0):
    """足元の中心を (ax, ay) にして Scene を作る。"""
    # P(0, 0, 0) が (ax, ay) に来るようにする（S * V だけ下げて置く）
    iso.set_anchor(ax, ay + iso.S * iso.V)
    sc = Scene(w, h)
    sc_fn(sc)
    iso.set_anchor(64, 120)
    return sc


def Q(x, y, z=0.0):
    """原点（スプライトの足元の中心）基準の座標。"""
    return P(x, y, z)


def blob(sc, x, y, z, rx, ry, c, hi=True):
    px, py = Q(x, y, z)
    sc.ellipse(px, py, rx, ry, shade(c, 0.78), shade(c, 0.5), 0.5, 1.0, 16)
    sc.ellipse(px - rx * 0.12, py - ry * 0.18, rx * 0.82, ry * 0.8, c, None, 0, 1.0, 16)
    if hi:
        sc.ellipse(px - rx * 0.28, py - ry * 0.4, rx * 0.4, ry * 0.32, shade(c, 1.28), None, 0, 0.9, 12)


def tree_oak(sc, size=1.0, leaf="#4F9A3E", trunk="#6B4A2B"):
    sc.ellipse(*Q(0.1, 0.1, 0), 11 * size, 5 * size, "#000000", None, 0, 0.22, 14)
    sc.box(-0.12, -0.12, 0.12, 0.12, 0, 16 * size, trunk, lf=1.0, rf=0.7)
    blob(sc, 0.0, 0.0, 22 * size, 12 * size, 11 * size, shade(leaf, 0.92))
    blob(sc, -0.15, 0.1, 30 * size, 10 * size, 9 * size, leaf)
    blob(sc, 0.2, -0.05, 36 * size, 8 * size, 7.5 * size, shade(leaf, 1.12))


def tree_pine(sc, size=1.0, leaf="#2F6B3A", trunk="#5A3F26"):
    sc.ellipse(*Q(0.1, 0.1, 0), 10 * size, 4.5 * size, "#000000", None, 0, 0.22, 14)
    sc.box(-0.1, -0.1, 0.1, 0.1, 0, 10 * size, trunk)
    for k, (r, z, h) in enumerate([(0.95, 8, 16), (0.75, 18, 15), (0.55, 28, 14)]):
        sc.cone(0.0, 0.0, r * size, z * size, h * size, shade(leaf, 0.95 + 0.07 * k), 0.0)


def tree_birch(sc):
    sc.ellipse(*Q(0.1, 0.1, 0), 9, 4, "#000000", None, 0, 0.22, 14)
    sc.cylinder(0.0, 0.0, 0.14, 0, 28, "#E7E2D5")
    for z in (6, 12, 19, 24):
        sc.line([Q(-0.14, 0.14, z), Q(0.14, -0.14, z)], "#4A4A44", 0.8)
    blob(sc, 0.0, 0.0, 30, 10, 10, "#79B84A")
    blob(sc, 0.12, -0.1, 38, 7, 7, "#8FCB5C")


def bush(sc):
    sc.ellipse(*Q(0, 0, 0), 8, 3.5, "#000000", None, 0, 0.2, 12)
    blob(sc, -0.1, 0.0, 4, 6, 4.5, "#4E9A42", hi=False)
    blob(sc, 0.15, 0.05, 5, 6, 5, "#5FAE4E")


def rock(sc, variant=0):
    sc.ellipse(*Q(0, 0, 0), 11, 4.5, "#000000", None, 0, 0.22, 12)
    c = "#8A8A86" if variant == 0 else "#9A958A"
    x, y = Q(0, 0, 0)
    sc.poly([(x - 9, y), (x - 6, y - 8), (x + 1, y - 11), (x + 8, y - 6), (x + 10, y + 1), (x + 2, y + 4), (x - 5, y + 3)], shade(c, 0.85), shade(c, 0.5), 0.5)
    sc.poly([(x - 6, y - 8), (x + 1, y - 11), (x + 8, y - 6), (x + 2, y - 3), (x - 4, y - 3)], shade(c, 1.15), None)
    if variant:
        sc.poly([(x + 6, y + 2), (x + 11, y - 3), (x + 14, y + 2), (x + 9, y + 5)], shade(c, 0.95), shade(c, 0.5), 0.5)


def hay(sc):
    sc.ellipse(*Q(0, 0, 0), 9, 4, "#000000", None, 0, 0.2, 12)
    sc.cylinder(0.0, 0.0, 0.5, 0, 9, "#D8B85A")
    sc.line([Q(-0.5, 0.5, 5), Q(0.5, -0.5, 5)], "#8A6A22", 0.7)


def lamp(sc):
    sc.ellipse(*Q(0, 0, 0), 5, 2.2, "#000000", None, 0, 0.2, 10)
    sc.line([Q(0, 0, 0), Q(0, 0, 22)], "#3A3A40", 1.6)
    x, y = Q(0, 0, 24)
    sc.ellipse(x, y, 9, 6, "#FFD36B", None, 0, 0.28, 12)
    sc.poly([(x - 3, y + 2), (x + 3, y + 2), (x + 2.5, y - 4), (x - 2.5, y - 4)], "#FFE7A0", "#3A3A40", 0.6)
    sc.poly([(x - 3.5, y - 4), (x + 3.5, y - 4), (x, y - 8)], "#3A3A40", None)


def well(sc):
    sc.ellipse(*Q(0, 0, 0), 11, 5, "#000000", None, 0, 0.22, 12)
    sc.cylinder(0.0, 0.0, 0.62, 0, 8, "#9C9A92")
    x, y = Q(0, 0, 8)
    sc.ellipse(x, y, 6.5, 3.2, "#3E6FA8", None, 0, 1.0, 14)
    sc.line([Q(-0.55, 0.55, 8), Q(-0.55, 0.55, 20), Q(0.55, -0.55, 20), Q(0.55, -0.55, 8)], "#5A4630", 1.2)
    sc.poly([Q(-0.8, 0.8, 20), Q(0.8, -0.8, 20), Q(0.0, 0.0, 26)], "#B8523D", shade("#B8523D", 0.5), 0.5)


def signpost(sc):
    sc.ellipse(*Q(0, 0, 0), 7, 3, "#000000", None, 0, 0.22, 10)
    sc.line([Q(0, 0, 0), Q(0, 0, 34)], "#5A4630", 2.2)
    x, y = Q(0, 0, 30)
    sc.poly([(x - 22, y - 12), (x + 22, y - 12), (x + 22, y + 4), (x - 22, y + 4)], "#9C7A4A", "#4A3622", 0.8)
    sc.line([(x - 20, y - 9), (x + 20, y - 9)], "#B8935A", 0.6)


def fountain(sc):
    sc.ellipse(*Q(0, 0, 0), 20, 9, "#000000", None, 0, 0.22, 16)
    sc.cylinder(0.0, 0.0, 1.5, 0, 7, "#D8D2C2")
    x, y = Q(0, 0, 7)
    sc.ellipse(x, y, 17, 8, "#4D8FD0", "#2E5E96", 0.6, 1.0, 20)
    sc.cylinder(0.0, 0.0, 0.3, 7, 22, "#E5DFCE")
    sc.ellipse(*Q(0, 0, 22), 7, 3.2, "#7FB8F2", "#2E5E96", 0.5, 1.0, 14)
    for k in range(6):
        a = math.radians(k * 60)
        sc.line([Q(0, 0, 22), Q(0.9 * math.cos(a), 0.9 * math.sin(a), 8)], "#BFE0FF", 0.9, 0.8)


def house(sc, kind):
    mats = {
        0: dict(wall="#CDB892", roof="#B5523B", trim="#7B5A3A"),
        1: dict(wall="#A8A59E", roof="#46588A", trim="#5E5E58"),
        2: dict(wall="#B18755", roof="#C2A95B", trim="#6B4E2E"),
    }[kind]
    sc.ellipse(*Q(0.1, 0.1, 0), 15, 6.5, "#000000", None, 0, 0.22, 14)
    x0, y0, x1, y1 = -0.9, -0.7, 0.9, 0.6
    h = 11 + kind * 2
    sc.box(x0, y0, x1, y1, 0, 3, "#8C8C84")
    sc.box(x0, y0, x1, y1, 3, h, mats["wall"])
    sc.win_left(-0.45, 8, 0.3, 4.5, y1, "#2B2230", "#F0CF7A", mats["trim"])
    sc.win_left(0.45, 8, 0.3, 4.5, y1, "#2B2230", None, mats["trim"])
    sc.win_right(-0.05, 8, 0.4, 4.5, x1, "#2B2230", "#F0CF7A", mats["trim"])
    sc.gable(x0, y0, x1, y1, h, 7 + kind, mats["roof"], axis="x" if kind != 1 else "y", over=0.2)
    if kind == 0:
        sc.box(0.3, -0.55, 0.65, -0.2, h, h + 8, "#8C8A82")


def wall_x(sc, tier):
    """城壁の部品（x 方向に 2 単位）。"""
    m = gen_buildings.MAT[tier]
    body = m["base"] if tier < 3 else m["wall"]
    sc.ellipse(*Q(0, 0, 0), 15, 4, "#000000", None, 0, 0.22, 12)
    sc.box(-1.0, -0.5, 1.0, 0.5, 0, 12, body)
    for i in range(3):
        a = -0.95 + i * 0.7
        sc.box(a, 0.28, a + 0.4, 0.5, 12, 15, shade(body, 1.05))
    if tier >= 5:
        sc.line([Q(-1.0, 0.5, 12.5), Q(1.0, 0.5, 12.5)], m["gold"], 0.9)


def wall_y(sc, tier):
    m = gen_buildings.MAT[tier]
    body = m["base"] if tier < 3 else m["wall"]
    sc.ellipse(*Q(0, 0, 0), 15, 4, "#000000", None, 0, 0.22, 12)
    sc.box(-0.5, -1.0, 0.5, 1.0, 0, 12, body)
    for i in range(3):
        a = -0.95 + i * 0.7
        sc.box(0.28, a, 0.5, a + 0.4, 12, 15, shade(body, 1.05))
    if tier >= 5:
        sc.line([Q(0.5, -1.0, 12.5), Q(0.5, 1.0, 12.5)], m["gold"], 0.9)


def wall_tower(sc, tier):
    m = gen_buildings.MAT[tier]
    sc.ellipse(*Q(0, 0, 0), 17, 6, "#000000", None, 0, 0.22, 14)
    h = 22 + tier * 3
    sc.cylinder(0.0, 0.0, 0.75, 0, h, m["base"] if tier < 3 else m["wall"])
    sc.cone(0.0, 0.0, 0.75, h, 11 + tier, m["roof"], 0.15)
    sc.flag(0.0, 0.0, h + 11 + tier, 9, m["banner"])


def gate(sc, tier):
    """前の角の門楼（ひとつ）。"""
    m = gen_buildings.MAT[tier]
    sc.ellipse(*Q(0, 0, 0), 22, 9, "#000000", None, 0, 0.22, 14)
    h = 26 + tier * 3
    sc.box(-0.9, -0.9, 0.9, 0.9, 0, h, m["base"] if tier < 3 else m["wall"])
    sc.door_left(0.0, 0.9, 16, 0.9, "#1E1610")
    sc.gable(-0.9, -0.9, 0.9, 0.9, h, 8 + tier, m["roof"], axis="x", over=0.15)
    sc.flag(0.0, 0.0, h + 8 + tier, 12, m["banner"])
    if tier >= 4:
        sc.box(-1.05, -1.05, -0.85, -0.85, 0, h + 2, m["trim"])


def site(sc):
    """建物のない区画（建設予定地）: 土のパッドと杭とロープ。128 x 160。"""
    sc.shadow(0.2, 0.2, 4.2, 4.2, 0.2)
    sc.pad(0.0, 0.0, 4.0, 4.0, "#7C6B47")
    sc.pad(0.5, 0.5, 3.5, 3.5, "#8C7B57", "#7C6B47")
    for (x, y) in [(0.3, 0.3), (3.7, 0.3), (0.3, 3.7), (3.7, 3.7)]:
        sc.line([P(x, y, 0), P(x, y, 9)], "#6B4E2E", 1.6)
        px, py = P(x, y, 9)
        sc.poly([(px, py), (px + 6, py + 1.6), (px + 1, py + 4)], "#D9453D", None)
    sc.line([P(0.3, 0.3, 7), P(3.7, 0.3, 7), P(3.7, 3.7, 7), P(0.3, 3.7, 7), P(0.3, 0.3, 7)], "#E8D9A8", 0.7, 0.8)
    for k, (x, y) in enumerate([(1.2, 1.2), (2.6, 2.2), (1.5, 2.8)]):
        sc.box(x, y, x + 0.5, y + 0.4, 0, 3, "#8C6A3C")


DECOR = [
    ("tree_oak_a", lambda sc: tree_oak(sc, 1.0)),
    ("tree_oak_b", lambda sc: tree_oak(sc, 0.8, "#6AAE45", "#7A5A38")),
    ("tree_oak_c", lambda sc: tree_oak(sc, 1.12, "#3F8A3A", "#5F4126")),
    ("tree_pine_a", lambda sc: tree_pine(sc, 1.0)),
    ("tree_pine_b", lambda sc: tree_pine(sc, 0.82, "#3A7A44")),
    ("tree_birch", tree_birch),
    ("bush", bush),
    ("rock_a", lambda sc: rock(sc, 0)),
    ("rock_b", lambda sc: rock(sc, 1)),
    ("hay", hay),
    ("lamp", lamp),
    ("well", well),
    ("signpost", signpost),
    ("fountain", fountain),
    ("house_a", lambda sc: house(sc, 0)),
    ("house_b", lambda sc: house(sc, 1)),
    ("house_c", lambda sc: house(sc, 2)),
]


def build_decor(name):
    fn = dict(DECOR)[name]
    return around(fn)


def build_wall(kind, tier):
    fn = {"x": wall_x, "y": wall_y, "tower": wall_tower, "gate": gate}[kind]
    return around(lambda sc: fn(sc, tier), 64, 64 if kind in ("x", "y") else 96, 32.0, 48.0 if kind in ("x", "y") else 80.0)


def build_site():
    sc = Scene()
    site(sc)
    return sc


def kotlin_ids():
    lines = []
    lines.append("package com.ttmrase.crownfall.ui.territory")
    lines.append("")
    lines.append("import com.ttmrase.crownfall.R")
    lines.append("import com.ttmrase.crownfall.core.model.BuildingType")
    lines.append("")
    lines.append("/** tools/artgen/gen_decor.py が生成する（手で編集しない）。建物・装飾のスプライトの ID 表。 */")
    lines.append("object SpriteIds {")
    lines.append("    /** 建物の見た目 [tier]（1..5）の drawable。 */")
    lines.append("    fun building(")
    lines.append("        type: BuildingType,")
    lines.append("        tier: Int,")
    lines.append("    ): Int = BUILDINGS.getValue(type)[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    /** 城壁の部品の drawable（[tier] は 1..5）。 */")
    lines.append("    fun wallX(tier: Int): Int = WALL_X[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    fun wallY(tier: Int): Int = WALL_Y[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    fun wallTower(tier: Int): Int = WALL_TOWER[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    lines.append("    fun gate(tier: Int): Int = GATE[(tier - 1).coerceIn(0, 4)]")
    lines.append("")
    for name, _ in DECOR:
        lines.append("    val %s: Int = R.drawable.deco_%s" % (name.upper(), name))
        lines.append("")
    lines.append("    val SITE: Int = R.drawable.deco_site")
    lines.append("")

    def arr(const, items):
        lines.append("    private val %s =" % const)
        lines.append("        intArrayOf(")
        for it in items:
            lines.append("            %s," % it)
        lines.append("        )")
        lines.append("")

    arr("WALL_X", ["R.drawable.deco_wall_x_t%d" % t for t in range(1, 6)])
    arr("WALL_Y", ["R.drawable.deco_wall_y_t%d" % t for t in range(1, 6)])
    arr("WALL_TOWER", ["R.drawable.deco_wall_tower_t%d" % t for t in range(1, 6)])
    arr("GATE", ["R.drawable.deco_gate_t%d" % t for t in range(1, 6)])
    lines.append("    private val BUILDINGS: Map<BuildingType, IntArray> =")
    lines.append("        mapOf(")
    for t in gen_buildings.TYPES:
        lines.append("            BuildingType.%s to" % t.upper())
        lines.append("                intArrayOf(")
        for tier in range(1, 6):
            lines.append("                    R.drawable.bld_%s_t%d," % (t, tier))
        lines.append("                ),")
    lines.append("        )")
    lines.append("")
    lines.append("    /** すべての建物の drawable（先読み用）。 */")
    lines.append("    val ALL_BUILDINGS: List<Int> get() = BUILDINGS.values.flatMap { it.toList() }")
    lines.append("}")
    # 末尾の空行を整える
    text = "\n".join(lines).replace("\n\n}\n", "\n}\n")
    return text.rstrip("\n") + "\n"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    n = 0
    for name, _ in DECOR:
        (OUT / ("deco_%s.xml" % name)).write_text(build_decor(name).to_xml("decor " + name), encoding="utf-8")
        n += 1
    for kind in ("x", "y", "tower", "gate"):
        for tier in range(1, 6):
            fname = "deco_wall_%s_t%d.xml" % (kind, tier) if kind != "gate" else "deco_gate_t%d.xml" % tier
            (OUT / fname).write_text(build_wall(kind, tier).to_xml("wall %s tier %d" % (kind, tier)), encoding="utf-8")
            n += 1
    (OUT / "deco_site.xml").write_text(build_site().to_xml("build site"), encoding="utf-8")
    n += 1
    KOTLIN.parent.mkdir(parents=True, exist_ok=True)
    KOTLIN.write_text(kotlin_ids(), encoding="utf-8")
    print("wrote %d decor drawables and %s" % (n, KOTLIN.name))


if __name__ == "__main__":
    main()
