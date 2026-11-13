"""胸像（ポートレート）の生成: グラデーションつきのベクター（vec.py）で、陰影のある顔・髪・衣装・背景を組み合わせて
VectorDrawable を書き出す。標準ライブラリのみ。出力は決定論的。

- 英雄 12 とペルソナ 8（計 20）: 下の SPECS で 1 体ずつ設計する（heroes.json / personas.json の人物像に合わせる）。
- 名前だけのメンバー 22 とプレイヤーのアバター 12: 同じパーツを種（ID）のハッシュで決定論に組み合わせる。
実行: python3 tools/artgen/gen_portraits.py [出力ディレクトリ] [--html 見本.html]   既定は app/src/main/res/drawable
出力: pt_<id>.xml（英雄・ペルソナ）/ pt_gen_<look>_<nn>.xml（名前だけのメンバー）/ pt_avatar_<nn>.xml（プレイヤー）
絵柄: 96 x 96 の胸像。光は左上から。暗い暖色の輪郭線、肌・髪・金属はグラデーション、影側（右）に細いリムライト。
      枠（レアリティ・VIP）と角丸の切り抜きはアプリ側（PortraitView）で重ねる。clipPath・ぼかしは使わない。
"""
import math
import pathlib
import sys
import zlib

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from vec import (  # noqa: E402
    Linear, Radial, Vec, circle, clip_convex, darken, ellipse, ellipse_pts, lighten, mirror_pts, mix, poly1,
    quad_pts, rgba, ribbon, spline, spline_pts, star,
)

W = 96
CX = 48.0          # 頭の中心
TOP = 13.0         # 頭頂（髪を除く）
EYE_Y = 40.0       # 目の高さ
INK = "#2A1A20"    # 輪郭（暗い暖色）
WHITE = "#FFFFFF"

# 肌: lt 明 / base / sh 影 / dp 深い影 / line 線 / blush 頬 / lip 唇
SKINS = {
    "pale": dict(lt="#FFF3E8", base="#F8DDCA", sh="#E0AE9A", dp="#BC8272", line="#7C463E", blush="#F59A9E", lip="#DB6F7C"),
    "fair": dict(lt="#FFE9D2", base="#F4CBA8", sh="#D89C7A", dp="#B07054", line="#703C2C", blush="#F08C7C", lip="#CE6660"),
    "warm": dict(lt="#FAD4AA", base="#E6AE80", sh="#C48256", dp="#9A5C38", line="#62341E", blush="#E67C60", lip="#BC5A4C"),
    "tan": dict(lt="#ECB98C", base="#D09264", sh="#AA6A42", dp="#80482A", line="#522A14", blush="#CC6A4C", lip="#A64E3E"),
    "brown": dict(lt="#C8926A", base="#A66E4A", sh="#7E4C30", dp="#5C331E", line="#3A1C0E", blush="#AC5640", lip="#8C4034"),
    "deep": dict(lt="#A07054", base="#7C4E36", sh="#5A3422", dp="#3E2114", line="#27120A", blush="#8E4634", lip="#6E2E2A"),
}
# 髪: (明, 基本, 暗)
HAIRS = {
    "black": ("#565470", "#2E2A3C", "#16131E"),
    "darkbrown": ("#7A5440", "#4E3024", "#2A1810"),
    "brown": ("#B07A48", "#7E4E28", "#4C2C14"),
    "auburn": ("#D87844", "#A24A26", "#602612"),
    "red": ("#F0703E", "#BE3E22", "#741E12"),
    "orange": ("#FBAE5C", "#E47E2A", "#9C4810"),
    "blond": ("#FBE596", "#E4BE56", "#A87C26"),
    "gold": ("#FFF2AC", "#F4CE5A", "#B8842A"),
    "grey": ("#CCCED8", "#9A9CAC", "#5E6074"),
    "white": ("#FFFFFF", "#E6E8F2", "#A4A8C0"),
    "silver": ("#F4F7FD", "#C8D0E4", "#8088A8"),
    "teal": ("#78CEC4", "#3E918C", "#1C5456"),
    "purple": ("#BE92E8", "#8250B6", "#482472"),
    "iceblue": ("#E8F4FF", "#B6D4F2", "#6C94C6"),
    "pink": ("#FFBCD2", "#EA7EA4", "#A4446C"),
}
# 金属: (ハイライト, 明, 基本, 暗, 深い影)
STEEL = ("#FFFFFF", "#DCE4F0", "#A4B0C8", "#66728E", "#3A4460")
IRON = ("#E8ECF2", "#A8B0BE", "#747C8E", "#484E60", "#2A2E3C")
GOLDM = ("#FFFBE0", "#FFE27A", "#EDB32C", "#B0720E", "#6E420A")
BRONZE = ("#FFE8C0", "#E8AE6C", "#B87838", "#7C4A1C", "#4A2A10")
DARKM = ("#B8B4CC", "#6E6A88", "#46425C", "#2A2638", "#18141F")
GOLD = "#F2C340"


def _n(v):
    s = "%.1f" % v
    return s[:-2] if s.endswith(".0") else s


def lg(x0, y0, x1, y1, *stops):
    return Linear(x0, y0, x1, y1, list(stops))


def rg(cx, cy, r, *stops):
    return Radial(cx, cy, r, list(stops))


def fade(color, a0, a1=0.0, mid=None):
    """同じ色で不透明度だけ変わる stop の列。"""
    if mid is None:
        return [(0, rgba(color, a0)), (1, rgba(color, a1))]
    return [(0, rgba(color, a0)), (mid[0], rgba(color, mid[1])), (1, rgba(color, a1))]


def metal(m, x0, y0, x1, y1):
    """金属の面のグラデーション（明 → 基本 → 暗 → 反射光）。"""
    return Linear(x0, y0, x1, y1, [(0, m[1]), (0.35, m[2]), (0.8, m[3]), (1, mix(m[3], m[2], 0.6))])


FACES = {
    #          頬幅  あご角幅 あご角y あご先幅 あご先y 角の強さ
    "oval": (17.0, 13.4, 52.5, 5.0, 61.0, 1.0),
    "heart": (17.0, 12.2, 52.0, 3.8, 61.0, 1.0),
    "round": (17.9, 15.0, 52.8, 6.4, 60.2, 1.0),
    "square": (17.5, 15.8, 54.0, 7.6, 61.6, 0.7),
    "long": (16.5, 13.2, 54.0, 5.2, 63.0, 0.9),
    "angular": (17.0, 14.0, 53.4, 4.6, 62.0, 0.6),
}


class Ctx:
    def __init__(self, spec):
        self.s = spec
        self.v = Vec(W, W)
        self.sk = SKINS[spec.get("skin", "fair")]
        self.hc = HAIRS[spec.get("hc", "brown")]
        self.look = spec.get("look", "n")
        self.fem = self.look == "f"
        self.turn = spec.get("turn", 1.0)
        self.fx = CX - 1.5 * self.turn
        self.sr = 1.0 + 0.02 * self.turn
        self.sl = 1.0 - 0.05 * self.turn
        self.face = FACES[spec.get("face", "oval")]
        self.nw = spec.get("nw", {"m": 7.4, "f": 5.6, "n": 6.4}[self.look])
        self.sw = spec.get("sw", {"m": 38.0, "f": 31.0, "n": 34.0}[self.look])
        self.skline = mix(self.sk["line"], INK, 0.35)
        self.hline = mix(self.hc[2], INK, 0.55)
        self.bc = HAIRS[spec.get("bc", spec.get("hc", "brown"))]

    def get(self, k, d=None):
        return self.s.get(k, d)


# ───────── 背景 ─────────
BGS = {
    #          中心        中間       端        飾りの色
    "slate": ("#8FB0B4", "#4E7078", "#22343E", "#D8F0F0"),
    "forest": ("#9CCB7C", "#4C8A4C", "#1E4428", "#E4FFC8"),
    "umber": ("#D8A66C", "#8E5E34", "#3E2414", "#FFE4B8"),
    "royal": ("#7CA8F4", "#3458B4", "#141E58", "#D4E6FF"),
    "teal": ("#7CE0D4", "#2A8E94", "#0C3C4C", "#D8FFF8"),
    "ember": ("#FF9A5C", "#B8382A", "#4A1014", "#FFE0A8"),
    "steel": ("#A8BCD8", "#586C94", "#202A48", "#E4F0FF"),
    "violet": ("#A884F0", "#5A36A8", "#200E4C", "#ECD8FF"),
    "orchid": ("#F09CE0", "#9C3CA8", "#3C1050", "#FFE0F8"),
    "moss": ("#B8C070", "#5E7036", "#22300E", "#F4F8C0"),
    "gold": ("#FFF4B0", "#F0B436", "#8C4A0C", "#FFFFFF"),
    "crimson": ("#FF8C6C", "#B8242C", "#480A18", "#FFE49C"),
    "sun": ("#FFE08C", "#F08A2C", "#8C3410", "#FFF8D0"),
    "iron": ("#8C7C8C", "#4A3A4C", "#18101C", "#FFB8A0"),
    "candy": ("#FFF0A0", "#FFB648", "#C45C28", "#FFFFFF"),
    "field": ("#C8E088", "#6CA83C", "#28541C", "#FCFFD0"),
    "aqua": ("#9CE8F0", "#3498B8", "#103C5C", "#E4FCFF"),
    "flash": ("#FF9CD8", "#C8388C", "#4C0C44", "#FFF4B0"),
    "ice": ("#F0FAFF", "#94C4EC", "#3C6CA8", "#FFFFFF"),
    "olive": ("#C0B078", "#74683C", "#2C2612", "#F4ECC0"),
    "rose": ("#FFB8B0", "#D05C6C", "#581C34", "#FFF0E4"),
    "sky": ("#B8E4FF", "#5C9CE0", "#1C3C80", "#FFFFFF"),
    "plum": ("#C894C8", "#7C447C", "#2C1230", "#FFE4FF"),
    "sand": ("#F4DCA8", "#C09458", "#5C3C1C", "#FFFCE4"),
}

SPARK_SLOTS = [(13, 14, 3.4), (82, 20, 2.8), (9, 44, 2.0), (88, 46, 2.4), (22, 6, 1.8), (74, 7, 2.0), (6, 26, 1.5), (90, 32, 1.6)]


def sparkle(cx, cy, r):
    c = "%s,%s" % (_n(cx), _n(cy))
    return "M%s,%s Q%s %s,%s Q%s %s,%s Q%s %s,%s Q%s %s,%s Z" % (_n(cx), _n(cy - r), c, _n(cx + r), _n(cy), c, _n(cx), _n(cy + r), c, _n(cx - r), _n(cy), c, _n(cx), _n(cy - r))


def background(c):
    v, s = c.v, c.s
    lt, mid, dk, deco = BGS[s.get("bg", "slate")]
    v.path("M0,0 H96 V96 H0 Z", rg(44, 36, 74, lt, mid, dk))
    motif = s.get("motif", "ring")
    hx, hy = 48, 38
    if s.get("rays"):
        n = 12
        d = []
        for i in range(n):
            a0 = math.radians(i * 360 / n - 97)
            a1 = a0 + math.radians(360 / n * 0.46)
            d.append(poly1([(hx, hy), (hx + 90 * math.cos(a0), hy + 90 * math.sin(a0)), (hx + 90 * math.cos(a1), hy + 90 * math.sin(a1))]))
        v.path(" ".join(d), rg(hx, hy, 74, *fade(deco, 0.42, 0.0)))
    if motif == "ring":
        v.path(circle(hx, hy, 33), None, deco, 1.4, salpha=0.3)
        v.path(circle(hx, hy, 29.5), rg(hx, hy, 30, *fade(deco, 0.0, 0.2, (0.6, 0.02))))
    elif motif == "sun":
        v.path(star(hx, hy, 40, 33, 16), deco, alpha=0.2)
        v.path(circle(hx, hy, 31), None, deco, 1.2, salpha=0.4)
    elif motif == "moon":
        outer = ellipse_pts(54, 34, 34, 34, 28)
        v.path(poly1(outer) + " " + poly1(list(reversed(ellipse_pts(63, 28, 30, 30, 24)))), deco, alpha=0.22, evenodd=True)
    elif motif == "shield":
        v.path(spline([(18, 8, 0), (48, 3), (78, 8, 0), (78, 40), (66, 62), (48, 74, 0.6), (30, 62), (18, 40)]), deco, deco, 1.4, alpha=0.14, salpha=0.35)
    elif motif == "star":
        v.path(star(hx, hy, 46, 30, 8, -90), deco, alpha=0.2)
        v.path(star(hx, hy, 40, 33, 8, -67.5), deco, alpha=0.14)
    elif motif == "diamond":
        d = ["M%d,-2 L%d,98 M%d,-2 L%d,98" % (x, x + 60, x + 60, x) for x in range(-54, 96, 18)]
        v.path(" ".join(d), None, deco, 0.9, salpha=0.14)
        v.path(circle(hx, hy, 32), None, deco, 1.2, salpha=0.3)
    elif motif == "flame":
        d = []
        for (x, y, hgt, wd) in ((12, 70, 34, 9), (27, 52, 30, 8), (70, 50, 32, 8), (85, 68, 36, 9), (48, 30, 30, 10)):
            d.append(spline([(x, y - hgt, 0), (x + wd * 0.5, y - hgt * 0.55), (x + wd, y - hgt * 0.2), (x + wd * 0.6, y + 8), (x - wd * 0.6, y + 8),
                             (x - wd, y - hgt * 0.15), (x - wd * 0.2, y - hgt * 0.5)]))
        v.path(" ".join(d), lg(0, 10, 0, 80, *fade(deco, 0.34, 0.04)))
    elif motif == "snow":
        d = []
        for (x, y, r) in ((14, 16, 7), (82, 12, 5.5), (86, 44, 6.5), (9, 48, 5), (70, 4, 3.5), (30, 5, 3.5)):
            for k in range(3):
                a = math.radians(k * 60 + 15)
                dx, dy = r * math.cos(a), r * math.sin(a)
                d.append("M%.1f,%.1f L%.1f,%.1f" % (x - dx, y - dy, x + dx, y + dy))
        v.path(" ".join(d), None, deco, 1.1, salpha=0.6)
        v.path(circle(hx, hy, 32), rg(hx, hy, 32, *fade(deco, 0.0, 0.3, (0.6, 0.03))))
    elif motif == "leaf":
        d = []
        for (x, y, r, a) in ((14, 20, 11, 35), (83, 18, 10, -40), (88, 48, 8, -70), (8, 50, 8, 70), (30, 6, 7, 20), (66, 5, 7, -20)):
            ca, sa = math.cos(math.radians(a - 90)), math.sin(math.radians(a - 90))
            nx, ny = -sa, ca
            d.append(spline([(x - ca * r, y - sa * r, 0), (x + nx * r * 0.42, y + ny * r * 0.42), (x + ca * r, y + sa * r, 0), (x - nx * r * 0.42, y - ny * r * 0.42)]))
        v.path(" ".join(d), deco, alpha=0.26)
        v.path(circle(hx, hy, 32), None, deco, 1.2, salpha=0.25)
    elif motif == "gear":
        pts = []
        for i in range(24):
            r = 37 if i % 2 == 0 else 32.5
            for da in (-4.5, 4.5):
                a = math.radians(i * 15 + da)
                pts.append((hx + r * math.cos(a), hy + r * math.sin(a)))
        v.path(poly1(pts) + " " + circle(hx, hy, 27), deco, alpha=0.18, evenodd=True)
    elif motif == "burst":
        d = []
        for i in range(10):
            a = math.radians(i * 36 - 90)
            d.append(sparkle(hx + 37 * math.cos(a), hy + 37 * math.sin(a), 3.2 if i % 2 else 5))
        v.path(" ".join(d), deco, alpha=0.4)
        v.path(circle(hx, hy, 31), None, deco, 1.2, salpha=0.3)
    # 周辺を落とす
    v.path("M0,0 H96 V96 H0 Z", rg(48, 42, 70, (0, rgba(dk, 0.0)), (0.62, rgba(dk, 0.0)), (1, rgba(darken(dk, 0.35), 0.72))))
    n = s.get("sparks", 0)
    if n:
        off = zlib.crc32(str(s.get("bg")).encode()) % len(SPARK_SLOTS)
        d = [sparkle(*SPARK_SLOTS[(off + i * 3) % len(SPARK_SLOTS)]) for i in range(n)]
        v.path(" ".join(d), WHITE, alpha=0.85)


# ───────── 体 ─────────
def bust_pts(c, grow=0.0):
    sw, nw = c.sw, c.nw + 1.2
    return [
        (CX - sw - 6 - grow, 97, 0), (CX - sw - 3 - grow, 87), (CX - sw + 1.5 - grow, 77.5), (CX - sw + 10, 71 - grow),
        (CX - nw - 7, 67.4 - grow), (CX - nw - 0.5, 63.8 - grow), (CX + nw + 0.5, 63.8 - grow), (CX + nw + 7, 67.4 - grow),
        (CX + sw - 10, 71 - grow), (CX + sw - 1.5 + grow, 77.5), (CX + sw + 3 + grow, 87), (CX + sw + 6 + grow, 97, 0),
    ]


def cloth(col, x0=22, y0=62, x1=80, y1=100):
    return Linear(x0, y0, x1, y1, [lighten(col, 0.26), col, darken(col, 0.42)])


def leather(col="#7A4E2C", x0=22, y0=62, x1=80, y1=100):
    return Linear(x0, y0, x1, y1, [lighten(col, 0.3), col, darken(col, 0.5)])


def rim_right(c, pts, color="#FFF2D0", a=0.6, sw=0.8):
    """影側（右）の縁に沿う細い光。"""
    c.v.path(spline(pts, closed=False), None, color, sw, salpha=a)


def bust_base(c, fill, shade=0.26):
    """肩から胸の土台。塗り → 右側の影 → 輪郭 → リムライト。"""
    v = c.v
    pts = bust_pts(c)
    v.path(spline(pts), fill, INK, 1.25)
    sw = c.sw
    if shade:
        v.path(spline([pts[7], pts[8], pts[9], pts[10], pts[11], (CX + sw - 14, 97, 0), (CX + sw - 13, 84), (CX + sw - 17, 75)]),
               lg(CX + sw - 18, 0, CX + sw + 2, 0, *fade(INK, 0.0, shade + 0.2)))
    c.rim = [(CX + sw - 10, 71.9), (CX + sw - 2.2, 78), (CX + sw + 2.2, 87), (CX + sw + 4.4, 95.5)]


def bust_rim(c):
    rim_right(c, c.rim, c.get("rimc", "#FFF2D0"))


def head_shadow(c, a=0.34):
    """頭が胸に落とす影。"""
    spot(c, CX + 1.5, 67.5, 13, INK, a, 0.5)


def fold_lines(c, col, xs=((-24, 74), (-11, 81), (12, 80), (25, 75)), a=0.5):
    d = []
    for dx, y0 in xs:
        x = CX + dx * c.sw / 38.0
        d.append(ribbon([(x, y0), (x + (1.5 if dx < 0 else -1.5), (y0 + 97) / 2), (x + (0.5 if dx < 0 else -0.5), 97)], [0.1, 1.0, 0.6]))
    c.v.path(" ".join(d), darken(col, 0.5), alpha=a)


def vneck(c, depth=77.0, trim=None, tw=2.2, skin=True, width=4.6):
    """V 字の襟。肌（鎖骨）を見せて、縁取りをつける。"""
    v, sk = c.v, c.sk
    nw = c.nw
    L, R = (CX - nw - width, 65.2), (CX + nw + width, 65.2)
    if skin:
        v.path(spline([(L[0], L[1], 0), (CX - nw * 0.5, 71.5), (CX, depth, 0), (CX + nw * 0.5, 71.5), (R[0], R[1], 0), (CX, 62)]),
               lg(0, 63, 0, depth, sk["sh"], sk["base"]))
        v.path(spline([(CX - nw - 1, 66.5), (CX - 2.6, 69.2), (CX + 3, 69.2), (CX + nw + 1, 66.5), (CX + 3.5, 70.8), (CX - 3, 70.8)]), sk["dp"], alpha=0.45)
    if trim:
        for a, b, sgn in ((L, (CX, depth), -1), ((CX, depth), R, 1)):
            mid = ((a[0] + b[0]) / 2 + sgn * 0.6, (a[1] + b[1]) / 2 + 0.8)
            v.path(ribbon([a, mid, b], [tw, tw, tw]), lg(0, 64, 0, depth + 2, lighten(trim, 0.35), trim, darken(trim, 0.25)), INK, 0.7)


def strap(c, a, b, w=4.6, col="#6E4426", buckle=None, studs=0):
    v = c.v
    dx, dy = b[0] - a[0], b[1] - a[1]
    ln = math.hypot(dx, dy)
    nx, ny = -dy / ln * w / 2, dx / ln * w / 2
    v.path(poly1([(a[0] + nx, a[1] + ny), (b[0] + nx, b[1] + ny), (b[0] - nx, b[1] - ny), (a[0] - nx, a[1] - ny)]),
           lg(a[0] + nx, a[1] + ny, a[0] - nx, a[1] - ny, lighten(col, 0.3), col, darken(col, 0.4)), INK, 0.9)
    v.path(poly1([(a[0] + nx * 0.55, a[1] + ny * 0.55), (b[0] + nx * 0.55, b[1] + ny * 0.55)], False), None, lighten(col, 0.45), 0.5, salpha=0.6)
    if studs:
        d = [circle(a[0] + dx * (i + 0.5) / studs, a[1] + dy * (i + 0.5) / studs, 0.8) for i in range(studs)]
        v.path(" ".join(d), GOLDM[1], INK, 0.3)
    if buckle is not None:
        bx, by = a[0] + dx * buckle, a[1] + dy * buckle
        ang = math.degrees(math.atan2(dy, dx))
        v.begin(rotate=ang, px=bx, py=by)
        v.path("M%.1f,%.1f h6.4 v%.1f h-6.4 Z M%.1f,%.1f h3.4 v%.1f h-3.4 Z" % (bx - 3.2, by - w / 2 - 1.3, w + 2.6, bx - 1.7, by - w / 2 + 0.2, w - 0.4),
               lg(0, by - 4, 0, by + 4, GOLDM[1], GOLDM[2], GOLDM[3]), INK, 0.7, evenodd=True)
        v.end()


def gem(c, x, y, r, col, setting=GOLDM, shape="diamond"):
    """金の台座つきの宝石。"""
    v = c.v
    if shape == "round":
        v.path(circle(x, y, r + 1.1), setting[2], INK, 0.7)
        v.path(circle(x, y, r), rg(x - r * 0.3, y - r * 0.3, r * 1.5, (0, lighten(col, 0.55)), (0.5, col), (1, darken(col, 0.45))), darken(col, 0.6), 0.4)
    else:
        v.path(poly1([(x, y - r - 1.4), (x + r + 1.1, y), (x, y + r + 1.4), (x - r - 1.1, y)]), setting[2], INK, 0.7)
        v.path(poly1([(x, y - r), (x + r * 0.78, y), (x, y + r), (x - r * 0.78, y)]), lg(x - r, y - r, x + r, y + r, lighten(col, 0.55), col, darken(col, 0.4)), darken(col, 0.6), 0.4)
    v.path(circle(x - r * 0.3, y - r * 0.35, r * 0.26), WHITE, alpha=0.9)


def pauldron(c, side, m=STEEL, trim=None, lames=1, spikes=0, k=None, dy=0.0):
    """肩当て。side = -1 左 / 1 右。光は常に左上から。"""
    v = c.v
    if k is None:
        k = 1.0 if c.look == "m" else (0.84 if c.look == "f" else 0.92)
    px, py = CX + side * (c.sw - 11.5 * k), 76.5 + dy

    def P(pts):
        return [((px + side * x * k, py + y * k) + tuple(r)) for (x, y, *r) in pts]

    def R(pts):   # 向きをそろえる
        q = P(pts)
        return q if side > 0 else list(reversed(q))

    dome = [(-15, 3), (-13.5, -6), (-6, -10.8), (4, -10.2), (12.5, -5), (17, 4), (16, 12), (8, 14.8), (-4, 12.6), (-12, 8.8)]
    grad = rg(px - 5 * k, py - 8 * k, 27 * k, m[1], m[2], mix(m[3], m[4], 0.5))
    lam_d, lam_t = [], []
    for i in range(lames, 0, -1):
        off = 6.5 * i
        lam_d.append(spline(R([(-12 + i, 6 + off), (-2, 2 + off), (10, 5 + off), (17 - i, 12 + off), (9, 15.5 + off), (-5, 13 + off)])))
        lam_t.append(ribbon(P([(-10, 10.5 + off), (2, 13.6 + off), (13, 11 + off)]), [1.2 * k, 1.5 * k, 1.2 * k]))
    if lam_d:
        for dd in lam_d:   # 重なりの順に 1 枚ずつ（輪郭を出すため）。塗りは同じグラデーション
            v.path(dd, lg(px - 12 * k, py + 6 * k, px + 14 * k, py + 26 * k, m[1], m[2], m[4]), INK, 1.0)
        if trim:
            v.path(" ".join(lam_t), trim[2], alpha=0.9)
    if spikes:
        for (sx_, sy_, ang) in ((-3, -9.5, -12), (7, -8, 22))[:spikes]:
            bx, by = px + side * sx_ * k, py + sy_ * k
            a = math.radians(ang * side - 90)
            tip = (bx + 9 * k * math.cos(a), by + 9 * k * math.sin(a))
            v.path(poly1([(bx - 3 * k, by + 1.5), tip, (bx + 3 * k, by + 1.5)]), lg(bx - 3, by, bx + 3, by, m[1], m[3]), INK, 0.9)
    v.path(spline(R(dome)), grad, INK, 1.25)
    if trim:
        rim_pts = P([(-13.2, 6.2), (-4, 10.4), (8, 12.4), (15.4, 9.6)])
        v.path(ribbon(rim_pts, [2.4 * k] * 4), lg(0, py + 4 * k, 0, py + 14 * k, trim[1], trim[2], trim[3]), INK, 0.6)
        v.path(" ".join(circle(px + side * x * k, py + y * k, 0.75 * k) for x, y in ((-9, 8.2), (-1, 11), (8, 12.2))), trim[0], trim[4], 0.3)
    # ハイライトと反射光（左上が光源）
    v.path(ribbon([(px - 11.5 * k, py - 1.5 * k), (px - 8.5 * k, py - 7.2 * k), (px - 1 * k, py - 9 * k)], [0.3, 2.0 * k, 0.3]) + " "
           + ribbon([(px - 2 * k, py - 5.4 * k), (px + 4 * k, py - 6 * k), (px + 9 * k, py - 3 * k)], [0.2, 0.7 * k, 0.2]), m[0], alpha=0.8)
    if side > 0:
        rim_right(c, P([(8, -8.2), (13.2, -4), (16.2, 4), (15.4, 11)]), a=0.75, sw=0.9)


def gorget(c, m=STEEL, trim=None, gemc=None, high=False):
    """首まわりの板金。"""
    v = c.v
    nw = c.nw
    top = 60.5 if high else 62.6
    pts = [(CX - nw - 4.2, top, 0), (CX - nw * 0.4, top + 4.6), (CX + nw * 0.6, top + 4.6), (CX + nw + 4.4, top, 0), (CX + nw + 8.5, 68.6), (CX + 0.5, 75.2, 0.7), (CX - nw - 8.5, 68.6)]
    v.path(spline(pts), lg(CX - 14, 60, CX + 14, 76, m[1], m[2], m[3]), INK, 1.1)
    v.path(ribbon([(CX - nw - 5.4, 65.2), (CX - nw - 3.4, 69.2), (CX - 1, 72.4)], [0.3, 1.5, 0.3]), m[0], alpha=0.75)
    if trim:
        v.path(ribbon([(CX - nw - 7.4, 68.6), (CX + 0.5, 74), (CX + nw + 7.4, 68.6)], [1.5, 1.9, 1.5]), lg(0, 68, 0, 75, trim[1], trim[3]), INK, 0.5)
    if gemc:
        gem(c, CX + 0.3, 70.2, 2.3, gemc)


def cloak_back(c, col, collar=False, wide=4):
    v = c.v
    sw = c.sw
    pts = [(CX - sw - 8 - wide, 97, 0), (CX - sw - 2 - wide, 80), (CX - sw + 6, 68), (CX - 13, 58.5 if collar else 62), (CX + 13, 58.5 if collar else 62),
           (CX + sw - 6, 68), (CX + sw + 2 + wide, 80), (CX + sw + 8 + wide, 97, 0)]
    v.path(spline(pts), lg(20, 58, 84, 98, lighten(col, 0.12), darken(col, 0.3), darken(col, 0.6)), INK, 1.2)
    if c.get("cloak_fur"):
        base = [(CX - sw + 2, 73), (CX - sw + 9, 66), (CX - 13, 57.5), (CX + 13, 57.5), (CX + sw - 9, 66), (CX + sw - 2, 73)]
        low = [(CX + sw - 5, 77), (CX + sw - 12, 71.5), (CX + 12, 63.5), (CX - 12, 63.5), (CX - sw + 12, 71.5), (CX - sw + 5, 77)]
        v.path(spline(fur_outline(base, 2.6, 6.0) + low), lg(20, 56, 80, 78, "#FFFFFF", "#F0E8DC", "#B8AC98"), INK, 1.1)
        v.path(" ".join(spline([(x, y - 1.8, 0), (x + 1, y), (x, y + 2, 0), (x - 1, y)]) for x, y in ((CX - sw + 9, 71), (CX - 24, 64.5), (CX + 24, 64.5), (CX + sw - 9, 71))), "#2A2230")


def fur_outline(base, amp=3.0, step=5.0, jitter=0.35, inward=False):
    """基準線に沿って毛束の先を並べた点列（先は角）。"""
    pts = spline_pts(base, closed=False, n=8)
    out = []
    acc = 0.0
    tog = 0
    out.append((pts[0][0], pts[0][1], 0))
    for i in range(1, len(pts)):
        acc += math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1])
        if acc >= step / 2:
            acc = 0.0
            dx, dy = pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]
            ln = math.hypot(dx, dy) or 1.0
            nx, ny = dy / ln, -dx / ln
            if inward:
                nx, ny = -nx, -ny
            if tog % 2 == 0:
                k = amp * (1.0 + jitter * math.sin(i * 2.3))
                out.append((pts[i][0] + nx * k + dx / ln * amp * 0.5, pts[i][1] + ny * k + dy / ln * amp * 0.5, 0))
            else:
                out.append((pts[i][0], pts[i][1]))
            tog += 1
    out.append((pts[-1][0], pts[-1][1], 0))
    return out


def fur_mantle(c, col="#E8E0D0", ermine=False, big=1.0, low=0.0):
    """肩にかける毛皮。上縁と下縁に毛束、内側に毛並みの線。"""
    v = c.v
    sw = min(c.sw, 40) * big
    nw = c.nw
    dk = darken(col, 0.42)
    lower = [(CX - sw + 1, 70), (CX - sw + 3, 80 + low), (CX - 20, 85 + low), (CX - 6, 82.5 + low), (CX, 87 + low), (CX + 6, 82.5 + low), (CX + 20, 85 + low), (CX + sw - 3, 80 + low), (CX + sw - 1, 70)]
    upper = [(CX + sw - 1, 70), (CX + sw - 9, 65.5), (CX + nw + 9, 62.5), (CX + nw + 2.5, 62), (CX, 66.5), (CX - nw - 2.5, 62), (CX - nw - 9, 62.5), (CX - sw + 9, 65.5), (CX - sw + 1, 70)]
    pts = fur_outline(lower, 3.2, 6.0) [:-1] + fur_outline(upper, 2.4, 6.5)[:-1]
    v.path(spline(pts), lg(18, 60, 80, 90, lighten(col, 0.5), col, dk), INK, 1.15)
    tufts = []
    for i in range(11):
        x = CX - sw + 6 + i * (2 * sw - 12) / 10.0
        y = 70.5 + 6.5 * math.sin(i * 1.9) ** 2 + (2.5 if abs(x - CX) < 9 else 0)
        tufts.append(ribbon([(x, y), (x + 0.8, y + 2.6), (x + 0.2, y + 5.2)], [0.2, 1.1, 0.1]))
    v.path(" ".join(tufts), dk, alpha=0.5)
    if ermine:
        spots = []
        for i in range(7):
            x = CX - sw + 9 + i * (2 * sw - 18) / 6.0
            y = 76.5 + 3.5 * ((i * 7) % 3 - 1) + (3 if abs(x - CX) < 9 else 0)
            spots.append(spline([(x, y - 2.2, 0), (x + 1.1, y), (x, y + 2.4, 0), (x - 1.1, y)]))
        v.path(" ".join(spots), "#2A2230")
    rim_right(c, [(CX + sw - 12, 65.4), (CX + sw - 2, 69.4), (CX + sw - 1.5, 77)], a=0.6)


def cowl(c, col, knot=False, tail=False):
    """首に巻いた布（下ろしたフード・襟巻き）。"""
    v = c.v
    nw = c.nw
    pts = [(CX - nw - 6.5, 61.5), (CX - nw - 1.5, 65.5), (CX, 68.2), (CX + nw + 1.5, 65.5), (CX + nw + 6.5, 61.5), (CX + nw + 11, 67), (CX + nw + 7, 73.5),
           (CX + 1, 77.5), (CX - nw - 7, 73.5), (CX - nw - 11, 67)]
    if tail:
        v.path(spline([(CX + 4, 73), (CX + 10.5, 74), (CX + 12.5, 86), (CX + 10.5, 96.5, 0), (CX + 6.5, 92), (CX + 3.6, 97, 0), (CX + 3, 84)]), cloth(col, 44, 70, 62, 98), INK, 1.0)
        v.path(ribbon([(CX + 7.5, 78), (CX + 8.6, 86), (CX + 7.4, 93)], [0.2, 0.9, 0.2]), darken(col, 0.4), alpha=0.5)
    v.path(spline(pts), cloth(col, 32, 60, 66, 80), INK, 1.1)
    v.path(" ".join([ribbon([(CX - nw - 8.5, 66.5), (CX - 3, 72.2), (CX + 5, 72.6)], [0.2, 1.2, 0.2]),
                     ribbon([(CX + nw + 9, 66), (CX + 6, 70.6), (CX + 1, 71.6)], [0.2, 1.0, 0.2]),
                     ribbon([(CX - nw - 5, 71.6), (CX - 1, 75.2), (CX + 6, 74.4)], [0.2, 0.9, 0.2])]), darken(col, 0.45), alpha=0.55)
    v.path(ribbon([(CX - nw - 7.5, 63.5), (CX - nw - 3.5, 67), (CX - 2, 69.6)], [0.2, 1.3, 0.2]), lighten(col, 0.5), alpha=0.6)
    if knot:
        v.path(spline([(CX + 3.2, 71.4), (CX + 8.2, 70.6), (CX + 9.8, 74.6), (CX + 6.2, 77.6), (CX + 2.8, 75.6)]), cloth(col, 50, 70, 58, 78), INK, 0.9)


def body_tunic(c):
    s = c.s
    col = s.get("ac", "#5A7A9A")
    bust_base(c, cloth(col))
    fold_lines(c, col)
    head_shadow(c)
    vneck(c, s.get("neck_d", 76.5), s.get("trim", GOLD))
    bust_rim(c)


def body_gambeson(c):
    """キルトの布鎧と、斜めの革帯（歩兵の新兵）。"""
    v, s = c.v, c.s
    col = s.get("ac", "#8A6E4A")
    bust_base(c, cloth(col))
    d, h = [], []
    for i in range(-5, 6):
        x = CX + i * 7.4
        y0 = 69.5 + abs(i) * 1.1
        d.append("M%.1f,%.1f Q%.1f,%.1f %.1f,97" % (x, y0, x + (1.2 if i < 0 else -1.2), 84, x + (0.6 if i < 0 else -0.6)))
        h.append("M%.1f,%.1f Q%.1f,%.1f %.1f,97" % (x - 1.1, y0 + 1, x - 1.1 + (1.2 if i < 0 else -1.2), 84, x - 1.1 + (0.6 if i < 0 else -0.6)))
    v.path(" ".join(d), None, darken(col, 0.5), 0.75, salpha=0.6)
    v.path(" ".join(h), None, lighten(col, 0.45), 0.6, salpha=0.4)
    head_shadow(c)
    nw = c.nw
    collar = [(CX - nw - 5, 63.6), (CX - nw * 0.5, 68.4), (CX + nw * 0.5, 68.4), (CX + nw + 5, 63.6)]
    v.path(ribbon(collar, [3.6, 4.2, 4.2, 3.6]), cloth(lighten(col, 0.18), 36, 62, 60, 72), INK, 0.9)
    strap(c, (CX - c.sw + 13, 68.5), (CX + 15, 98), 5.2, s.get("strapc", "#6E4426"), buckle=0.62)
    bust_rim(c)


def body_leather(c):
    """革の胴着。肩の縫い目、鋲、斜めの帯。"""
    v, s = c.v, c.s
    col = s.get("ac", "#7A5234")
    under = s.get("under", "#C8B48C")
    bust_base(c, leather(col))
    sw = c.sw
    # 肩の当て革
    for side in (-1, 1):
        x = CX + side * (sw - 13)
        pts = [(x - side * 11, 69.5), (x, 67.4), (x + side * 10, 72.5), (x + side * 13, 80), (x + side * 4, 82.5), (x - side * 9, 78)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), leather(lighten(col, 0.1), x - 10, 66, x + 10, 84), INK, 0.9)
        v.path(" ".join(circle(x + side * dx, y, 0.7) for dx, y in ((-6, 76.6), (1, 79.4), (8, 79))), GOLDM[1], INK, 0.3)
    head_shadow(c)
    vneck(c, s.get("neck_d", 75.5), None)
    nw = c.nw
    # 襟の折り返しと、胸のひも
    for side in (-1, 1):
        pts = [(CX + side * (nw + 5), 64.6, 0), (CX + side * 1.2, 76, 0), (CX + side * 6.5, 77.5), (CX + side * (nw + 9.5), 68.4)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), cloth(under, 36, 62, 60, 80), INK, 0.8)
    v.path("M%.1f,79 L%.1f,82 M%.1f,79 L%.1f,82 M%.1f,84 L%.1f,87 M%.1f,84 L%.1f,87" % (CX - 2, CX + 2, CX + 2, CX - 2, CX - 2, CX + 2, CX + 2, CX - 2),
           None, darken(under, 0.3), 0.7)
    v.path("M%.1f,77.5 V97" % CX, None, darken(col, 0.6), 0.8, salpha=0.7)
    if s.get("strap", True):
        strap(c, (CX + sw - 14, 68.6), (CX - 16, 98), 4.6, s.get("strapc", "#4E3020"), buckle=0.55, studs=0)
    bust_rim(c)
    if s.get("pauldron_r"):
        pauldron(c, 1, s["pauldron_r"], IRON, 1, k=0.8, dy=-1.0)
    if s.get("cowl"):
        cowl(c, s["cowl"])


def mail_rows(x0, x1, y0, y1, step=4.0):
    """鎖かたびらの輪の列（1 行 = 1 本の波線）。"""
    d = []
    row = 0
    y = y0
    while y < y1:
        x = x0 + (step / 2 if row % 2 else 0)
        n = int((x1 - x) / step)
        d.append("M%s,%s q" % (_n(x), _n(y)) + " ".join(["%s,%s %s,0" % (_n(step / 2), _n(step * 0.8), _n(step))] * n))
        y += step * 0.72
        row += 1
    return " ".join(d)


def body_mail(c):
    """鎖かたびらの襟（肩まで）と、その下の陣羽織。"""
    v, s = c.v, c.s
    col = s.get("ac", "#8A4E34")
    bust_base(c, cloth(col))
    fold_lines(c, col)
    sw, nw = c.sw, c.nw
    # 鎖の肩掛け
    low = [(CX - sw + 4, 73.5), (CX - 24, 80), (CX - 12, 78), (CX, 83.5), (CX + 12, 78), (CX + 24, 80), (CX + sw - 4, 73.5)]
    top = [(CX + sw - 9, 70.4), (CX + nw + 7, 66.6), (CX + nw + 1, 63), (CX, 66), (CX - nw - 1, 63), (CX - nw - 7, 66.6), (CX - sw + 9, 70.4)]
    pts = [(p[0], p[1]) for p in low] + top
    pts[0] = pts[0] + (0.5,)
    v.path(spline(pts), lg(20, 62, 78, 86, IRON[1], IRON[2], IRON[3]), INK, 1.1)
    v.path(mail_rows(CX - 26, CX + 26, 68.4, 80), None, IRON[4], 0.6, salpha=0.6)
    head_shadow(c, 0.4)
    if s.get("strap", True):
        strap(c, (CX - sw + 14, 70), (CX + 13, 98), 4.6, "#5E3A20", buckle=0.6)
    bust_rim(c)


def body_plate(c):
    """板金鎧。ornate 0 = 質素 / 1 = 金の縁 / 2 = 金の縁 + 宝石 + 肩当ての段。"""
    v, s = c.v, c.s
    m = s.get("metal", STEEL)
    orn = s.get("ornate", 0)
    trim = s.get("trimm", GOLDM if orn else None)
    bust_base(c, lg(22, 62, 78, 100, m[1], m[2], mix(m[3], m[4], 0.4)), shade=0.12)
    sw = c.sw
    # 胸の稜線と上端の板
    v.path("M%.1f,74 V97" % (CX + 0.4), None, m[4], 0.9, salpha=0.7)
    v.path("M%.1f,74 V97" % (CX - 0.6), None, m[0], 0.7, salpha=0.6)
    arc = [(CX - sw + 16, 73.5), (CX - 12, 80.5), (CX, 82.6), (CX + 12, 80.5), (CX + sw - 16, 73.5)]
    if trim:
        v.path(ribbon(arc, [1.6, 2.2, 2.4, 2.2, 1.6]), lg(0, 73, 0, 84, trim[1], trim[2], trim[3]), INK, 0.6)
    else:
        v.path(spline(arc, False), None, m[4], 0.9, salpha=0.75)
        v.path(spline([(x, y + 0.9) for x, y in arc], False), None, m[0], 0.6, salpha=0.5)
    v.path(ribbon([(CX - 20, 84), (CX - 15, 90), (CX - 13, 97)], [0.3, 3.2, 2.2]), m[0], alpha=0.4)
    if orn >= 2:
        v.path(ribbon([(CX - 12, 97), (CX - 6, 90), (CX, 87.4), (CX + 6, 90), (CX + 12, 97)], [1.2, 1.5, 1.6, 1.5, 1.2]), trim[2], INK, 0.5)
    head_shadow(c, 0.42)
    lames = s.get("lames", 2 if orn >= 2 else 1)
    if s.get("gear") != "hood":
        plate_pauldrons(c)
    gorget(c, m, trim, s.get("gem") if orn >= 1 else None, high=orn >= 2)
    c.rim = None


def plate_pauldrons(c):
    s = c.s
    m = s.get("metal", STEEL)
    orn = s.get("ornate", 0)
    trim = s.get("trimm", GOLDM if orn else None)
    for side in (-1, 1):
        pauldron(c, side, s.get("pmetal", m), trim, s.get("lames", 2 if orn >= 2 else 1), s.get("spikes", 0))


def body_fur(c):
    """毛皮の肩掛け（蛮族・古参兵）。"""
    v, s = c.v, c.s
    col = s.get("ac", "#6E4A32")
    bust_base(c, leather(col))
    sw = c.sw
    if s.get("bare", False):
        vneck(c, 82.0, None, width=7.5)
    head_shadow(c)
    strap(c, (CX - sw + 16, 72), (CX + 14, 98), 5.0, "#4E2E1A", studs=5)
    if s.get("strap2", True):
        strap(c, (CX + sw - 16, 72), (CX - 14, 98), 5.0, "#4E2E1A", studs=5)
    fur_mantle(c, s.get("furc", "#D8CDB8"), big=s.get("fur_big", 1.0))
    if s.get("teeth", False):
        d = []
        for i in range(-2, 3):
            x, y = CX + i * 4.4, 70.2 - abs(i) * 1.5
            d.append(spline([(x - 1.2, y, 0), (x + 1.2, y, 0), (x + 0.2, y + 4.6, 0)]))
        v.path(spline([(CX - c.nw - 3, 64.5), (CX, 70.5), (CX + c.nw + 3, 64.5)], False), None, "#4E2E1A", 0.8)
        v.path(" ".join(d), "#F8F0DC", INK, 0.5)
    c.rim = None


def body_cloak(c):
    """肩からかける外套（留め具つき）と、内側の服。"""
    v, s = c.v, c.s
    col = s.get("ac", "#2F7C7C")
    inner = s.get("under", "#E8E4DC")
    trim = s.get("trim", GOLD)
    bust_base(c, cloth(inner))
    v.path("M%.1f,76 V97" % CX, None, darken(inner, 0.4), 0.7, salpha=0.6)
    head_shadow(c)
    vneck(c, s.get("neck_d", 75.5), trim, tw=1.8)
    sw, nw = c.sw, c.nw
    for side in (-1, 1):
        pts = [(CX + side * (nw + 3.5), 63.6), (CX + side * 4.5, 72.5), (CX + side * 7.5, 83), (CX + side * 9.5, 97, 0), (CX + side * (sw + 6), 97, 0),
               (CX + side * (sw + 3), 87), (CX + side * (sw - 1.5), 77.5), (CX + side * (sw - 10), 70.6), (CX + side * (nw + 9), 66.2)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), cloth(col), INK, 1.15)
        edge = [(CX + side * (nw + 4.2), 64.6), (CX + side * 5.6, 72.5), (CX + side * 8.6, 83), (CX + side * 10.6, 96.5)]
        v.path(ribbon(edge, [1.6, 1.9, 1.9, 1.9]), lg(0, 64, 0, 97, lighten(trim, 0.3), trim, darken(trim, 0.3)), INK, 0.45)
        v.path(" ".join([ribbon([(CX + side * 22, 73), (CX + side * 19, 85), (CX + side * 21, 97)], [0.2, 1.2, 0.8]),
                         ribbon([(CX + side * 31, 76), (CX + side * 29.5, 87), (CX + side * 31.5, 97)], [0.2, 1.0, 0.7])]), darken(col, 0.5), alpha=0.5)
    v.path(ribbon([(CX - 26, 71.5), (CX - 17, 69.4), (CX - 12, 66.8)], [0.2, 1.4, 0.2]), lighten(col, 0.5), alpha=0.55)
    clasp = s.get("clasp", "#58D0E0")
    v.path("M%.1f,72.6 H%.1f" % (CX - 5, CX + 5), None, GOLDM[3], 1.5)
    gem(c, CX - 5.4, 72.6, 1.9, clasp, shape="round")
    gem(c, CX + 5.4, 72.6, 1.9, clasp, shape="round")
    bust_rim(c)


def body_noble(c):
    """立ち襟の上着。肩章、金ボタン、たすき。"""
    v, s = c.v, c.s
    col = s.get("ac", "#3A62B4")
    trim = s.get("trimm", GOLDM)
    bust_base(c, cloth(col))
    fold_lines(c, col, ((-25, 76), (25, 76)))
    sw, nw = c.sw, c.nw
    if s.get("sash"):
        sc = s["sash"]
        v.path(poly1([(CX - sw + 14, 69.6), (CX - sw + 24, 68), (CX + 20, 97), (CX + 6, 97)]), cloth(sc, 20, 68, 70, 98), INK, 0.8)
    head_shadow(c)
    # 前立てとボタン
    v.path("M%.1f,74 V97" % CX, None, darken(col, 0.55), 0.9, salpha=0.8)
    v.path(" ".join(circle(CX - 3.6, y, 1.35) for y in (80, 87, 94)), lg(0, 78, 0, 96, trim[1], trim[2]), INK, 0.45)
    # 立ち襟
    for side in (-1, 1):
        pts = [(CX + side * (nw + 4.6), 60.8), (CX + side * 1.0, 66.4, 0.6), (CX + side * 0.8, 74.6, 0), (CX + side * 7.5, 72.6), (CX + side * (nw + 8.6), 67.4)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), cloth(lighten(col, 0.08), 36, 60, 60, 76), INK, 0.95)
        v.path(ribbon([(CX + side * (nw + 4.2), 61.8), (CX + side * 1.6, 66.8), (CX + side * 1.5, 73.4)], [1.1, 1.3, 1.1]), trim[2], alpha=0.95)
    # 肩章
    for side in (-1, 1):
        x = CX + side * (sw - 12)
        pts = [(x - side * 9, 69.6), (x + side * 2, 67.8), (x + side * 11, 72.6), (x + side * 10, 76.4), (x - side * 1, 75.2), (x - side * 9.6, 72.6)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), lg(x - 10, 67, x + 10, 77, trim[1], trim[2], trim[3]), INK, 0.9)
        fr = " ".join("M%.1f,%.1f v3.4" % (x + side * dx, 75.2 + dx * 0.13 * (1 if dx > 0 else 0.3)) for dx in (-7, -4, -1, 2, 5, 8))
        v.path(fr, None, trim[2], 1.5)
        v.path(fr, None, trim[4], 0.3, salpha=0.6)
    bust_rim(c)


def body_farm(c):
    """シャツと胸当てつきのつなぎ（農夫）。"""
    v, s = c.v, c.s
    col = s.get("ac", "#5A8A4A")
    shirt = s.get("under", "#EADFC4")
    bust_base(c, cloth(shirt))
    fold_lines(c, shirt, ((-27, 76), (27, 76)), 0.35)
    head_shadow(c)
    vneck(c, 73.5, None, width=3.6)
    sw = c.sw
    for side in (-1, 1):
        x = CX + side * 14.5
        v.path(poly1([(x - 2.8, 66.2 + (0.6 if side > 0 else 0.2)), (x + 2.8, 66.6), (x + 2.2 - side, 82), (x - 3.4 - side, 82)]), cloth(col, 30, 64, 66, 84), INK, 0.9)
    v.path(spline([(CX - 17.5, 81, 0), (CX + 17.5, 81, 0), (CX + 19.5, 97, 0), (CX - 19.5, 97, 0)]), cloth(col, 28, 78, 68, 100), INK, 1.0)
    v.path(poly1([(CX - 7, 86.5), (CX + 7, 86.5), (CX + 7, 97), (CX - 7, 97)]), None, darken(col, 0.5), 0.7, salpha=0.7)
    v.path(" ".join(circle(CX + side * 14.2, 83.6, 1.5) for side in (-1, 1)), lg(0, 82, 0, 85, BRONZE[1], BRONZE[3]), INK, 0.5)
    if s.get("kerchief"):
        cowl(c, s["kerchief"], knot=True)
    bust_rim(c)


def body_scarf(c):
    """厚手の上着と、ふわっと巻いた襟巻き。"""
    v, s = c.v, c.s
    col = s.get("ac", "#3A4A7A")
    bust_base(c, cloth(col))
    fold_lines(c, col)
    v.path("M%.1f,74 V97" % (CX - 1), None, darken(col, 0.55), 0.9, salpha=0.8)
    v.path(" ".join(circle(CX + 2.8, y, 1.3) for y in (84, 92)), s.get("btn", "#E8EEF8"), INK, 0.45)
    head_shadow(c)
    cowl(c, s.get("sc", "#E8EEF8"), knot=True, tail=True)
    bust_rim(c)


def body_robe(c):
    """打ち合わせの長衣（学者・術士）。幅広の縁取りと首飾り。"""
    v, s = c.v, c.s
    col = s.get("ac", "#6A3E9A")
    trim = s.get("trim", GOLD)
    inner = s.get("under", "#F0E8DC")
    bust_base(c, cloth(col))
    fold_lines(c, col, ((-26, 76), (-15, 84), (26, 76)))
    head_shadow(c)
    nw = c.nw
    v.path(spline([(CX - nw - 2, 64, 0), (CX + nw + 2, 64, 0), (CX + 1, 75, 0)]), cloth(inner, 40, 62, 56, 76), INK, 0.7)
    vneck(c, 71.5, None, width=1.5)
    # 打ち合わせ（左前）
    v.path(ribbon([(CX + nw + 5.5, 63.8), (CX + 2, 73), (CX - 8, 85), (CX - 15, 97)], [3.6, 4.4, 4.8, 5.0]), lg(0, 64, 0, 97, lighten(trim, 0.3), trim, darken(trim, 0.3)), INK, 0.8)
    v.path(ribbon([(CX - nw - 5.5, 63.8), (CX - 3, 71.6), (CX + 0.6, 75.4)], [3.6, 4.2, 4.2]), lg(0, 64, 0, 76, lighten(trim, 0.3), trim), INK, 0.8)
    if s.get("pendant"):
        gem(c, CX + 0.5, 79.5, 2.2, s["pendant"])
    bust_rim(c)


def body_jacket(c):
    """派手な上着（立てた襟、開いた胸元、金の鎖）。"""
    v, s = c.v, c.s
    col = s.get("ac", "#F0C23A")
    shirt = s.get("under", "#2A2438")
    bust_base(c, cloth(col))
    fold_lines(c, col, ((-26, 76), (26, 76)))
    head_shadow(c)
    nw = c.nw
    v.path(spline([(CX - nw - 4, 64.5, 0), (CX + nw + 4, 64.5, 0), (CX + 6, 97, 0), (CX - 6, 97, 0)]), cloth(shirt, 38, 62, 60, 98), INK, 0.8)
    vneck(c, 80.0, None, width=2.6)
    v.path(spline([(CX - nw - 2, 66.5), (CX - 3, 78), (CX, 82.5), (CX + 3, 78), (CX + nw + 2, 66.5)], False), None, GOLDM[2], 1.3)
    v.path(spline([(CX - nw - 2, 66.5), (CX - 3, 78), (CX, 82.5), (CX + 3, 78), (CX + nw + 2, 66.5)], False), None, GOLDM[0], 0.4, salpha=0.8)
    v.path(star(CX, 84.6, 3.4, 1.5), lg(0, 81, 0, 88, GOLDM[1], GOLDM[3]), INK, 0.5)
    for side in (-1, 1):
        pts = [(CX + side * (nw + 3), 61.2), (CX + side * (nw + 12.5), 58.6, 0), (CX + side * (nw + 13), 70), (CX + side * 9.5, 82, 0), (CX + side * 5.2, 97, 0), (CX + side * 4.6, 80), (CX + side * (nw + 1.5), 66)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), cloth(lighten(col, 0.12), 30, 58, 66, 98), INK, 1.0)
        v.path(ribbon([(CX + side * (nw + 11.5), 60.4), (CX + side * (nw + 11.6), 69.4), (CX + side * 8.8, 80.5)], [0.3, 1.2, 0.3]), WHITE, alpha=0.5)
    bust_rim(c)


def body_blouse(c):
    """丸襟とリボンの服（かわいい系）。"""
    v, s = c.v, c.s
    col = s.get("ac", "#F08AB0")
    bust_base(c, cloth(col))
    fold_lines(c, col, ((-22, 76), (22, 76)))
    head_shadow(c, 0.28)
    nw = c.nw
    for side in (-1, 1):
        pts = [(CX + side * (nw + 2.5), 63.4), (CX + side * 0.6, 70.5, 0), (CX + side * 6.4, 76.4), (CX + side * 13, 73.8), (CX + side * (nw + 9.5), 67)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), lg(36, 62, 60, 78, "#FFFFFF", "#E8E0F0"), INK, 0.9)
    rc = s.get("ribbon", "#D8405A")
    for side in (-1, 1):
        pts = [(CX, 73, 0), (CX + side * 7.5, 69.8), (CX + side * 8.6, 77.4), (CX, 74.6, 0)]
        if side < 0:
            pts.reverse()
        v.path(spline(pts), cloth(rc, 40, 69, 56, 79), INK, 0.8)
    v.path(spline([(CX - 0.6, 74.6, 0), (CX - 3.6, 83.5, 0), (CX - 0.6, 81.6), (CX + 0.6, 81.6), (CX + 3.6, 83.5, 0), (CX + 0.6, 74.6, 0)]), darken(rc, 0.12), INK, 0.7)
    v.path(circle(CX, 73.8, 1.7), lighten(rc, 0.15), INK, 0.7)
    bust_rim(c)


BODIES = {
    "tunic": body_tunic, "gambeson": body_gambeson, "leather": body_leather, "mail": body_mail, "plate": body_plate, "fur": body_fur,
    "cloak": body_cloak, "noble": body_noble, "farm": body_farm, "scarf": body_scarf, "robe": body_robe, "jacket": body_jacket, "blouse": body_blouse,
}


def draw_back(c):
    """体のうしろに見えるもの（マント・盾・矢筒）。"""
    v, s = c.v, c.s
    if s.get("cloak"):
        cloak_back(c, s["cloak"], s.get("cloak_collar", False))
    b = s.get("back")
    if b == "shield":
        x, y, r = 12, 80, 25
        v.path(circle(x, y, r), rg(x - 8, y - 10, r * 1.4, (0, "#C89A64"), (0.6, "#96683A"), (1, "#5E3C1E")), INK, 1.2)
        v.path(" ".join("M%.1f,%.1f V%.1f" % (x + dx, y - math.sqrt(r * r - dx * dx) + 1.5, 97) for dx in (-12, -4, 4, 12, 20)), None, "#4E3018", 0.6, salpha=0.6)
        v.path(circle(x, y, r - 1.6), None, lg(0, y - r, 0, y, IRON[0], IRON[2]), 2.6)
        v.path(circle(x, y, r), None, INK, 1.0)
    elif b == "quiver":
        x = 78
        d_sh, d_fl = [], []
        for i, (dx, top, lean) in enumerate(((-5, 43, -2.2), (0, 39, 0.4), (5, 44, 2.8))):
            x0, x1 = x + dx, x + dx + lean
            d_sh.append("M%.1f,70 L%.1f,%.1f" % (x0, x1, top))
            for sg in (-1, 1):
                d_fl.append(spline([(x1, top + 1, 0), (x1 + sg * 2.6, top + 3.4), (x1 + sg * 2.5 - lean * 0.2, top + 10), (x1 - lean * 0.45, top + 11, 0)]))
        v.path(" ".join(d_sh), None, "#6E4A2A", 1.3)
        fc = s.get("fletch", "#E8E4D8")
        v.path(" ".join(d_fl), lg(70, 38, 86, 56, lighten(fc, 0.4), fc, darken(fc, 0.35)), INK, 0.7)
        v.path(spline([(x - 8.5, 60, 0), (x + 8.5, 58.5, 0), (x + 7.5, 78), (x - 6, 80)]), leather("#7A4A28", 68, 56, 88, 80), INK, 1.1)
        v.path(ribbon([(x - 8.4, 62.6), (x + 8.4, 61.2)], [2.2, 2.2]), GOLDM[2], INK, 0.5)


# ───────── 頭 ─────────
def draw_neck(c):
    v, sk = c.v, c.sk
    nw = c.nw
    x0 = CX - nw + (c.fx - CX) * 0.6
    x1 = CX + nw + (c.fx - CX) * 0.2 + 0.4
    pts = [(x0 + 0.6, 50), (x1 - 0.4, 50), (x1 + 0.2, 60), (x1 + 2.6, 66), (x1 + 8, 70.5), (x1 + 9, 84, 0), (x0 - 9, 84, 0), (x0 - 8, 70.5), (x0 - 2.6, 66), (x0 - 0.2, 60)]
    v.path(spline(pts), lg(0, 57, 0, 70, sk["dp"], sk["sh"], sk["base"]), c.skline, 1.0)
    # 首の影側と、明るい側
    v.path(spline([(x1 - 2.8, 56), (x1 + 0.1, 60), (x1 + 2.4, 66), (x1 + 5, 69), (x1 - 2, 69.5), (x1 - 3.4, 63)]), sk["dp"], alpha=0.4)
    v.path(spline([(x0 + 0.8, 61), (x0 + 3.2, 65), (x0 + 2.2, 68.5), (x0 - 1.4, 67.5)]), sk["lt"], alpha=0.35)


def face_pts(c):
    kw, jw, jy, chw, chin, js = c.face
    fx, sr, sl = c.fx, c.sr, c.sl
    return [
        (CX, TOP), (CX + 10.5, TOP + 2.3), (CX + 16.0, TOP + 9.5), (CX + 17.3, 31.5),
        (CX + kw * sr, 42.5), (fx + jw * sr + 1.0, jy, js), (fx + chw + 0.9, chin - 1.5), (fx, chin),
        (fx - chw - 0.3, chin - 1.5), (fx - jw * sl + 0.2, jy, js), (CX - kw * sl - 0.2, 42.5),
        (CX - 17.3, 31.5), (CX - 16.0, TOP + 9.5), (CX - 10.5, TOP + 2.3),
    ]


def spot(c, cx, cy, r, color, a, sy=1.0):
    v = c.v
    if sy != 1.0:
        v.begin(px=cx, py=cy, sy=sy)
    v.path(circle(cx, cy, r), rg(cx, cy, r, *fade(color, a, 0.0)))
    if sy != 1.0:
        v.end()


def draw_ears(c):
    v, sk = c.v, c.sk
    kw = c.face[0]
    x = CX + kw * c.sr - 0.6
    if c.get("ears") == "elf":
        pts = [(x - 1.6, 38.5), (x + 3, 35), (x + 7.4, 29, 0), (x + 5, 38.6), (x + 2.8, 45), (x - 0.2, 48.4)]
        v.path(spline(pts), lg(x, 30, x + 8, 48, sk["base"], sk["sh"]), c.skline, 0.9)
        v.path(spline([(x + 1, 40.6), (x + 3.4, 36.6), (x + 5.6, 32.4)], False), None, sk["dp"], 0.7, salpha=0.7)
        xl = CX - kw * c.sl + 0.6
        v.path(spline([(xl + 1.4, 38.5), (xl - 2.4, 35.4), (xl - 5.8, 30, 0), (xl - 3.8, 38.6), (xl - 2, 44.6), (xl + 0.4, 47.6)]), sk["base"], c.skline, 0.9)
        return
    pts = [(x - 1, 38.2), (x + 2.6, 36.8), (x + 4.6, 39.6), (x + 4.2, 44.4), (x + 2.4, 48.6), (x - 0.4, 49.6)]
    v.path(spline(pts), lg(x, 37, x + 5, 49, sk["base"], sk["sh"]), c.skline, 0.9)
    v.path(spline([(x + 1.2, 39.6), (x + 3, 40.6), (x + 2.8, 44), (x + 1.2, 46.2)], False), None, sk["dp"], 0.7, salpha=0.75)
    xl = CX - kw * c.sl + 0.9
    v.path(spline([(xl + 1, 38.6), (xl - 1.6, 37.6), (xl - 2.8, 40.4), (xl - 2.4, 44.6), (xl - 1, 48.2), (xl + 0.8, 49)]), sk["base"], c.skline, 0.9)


def draw_face(c):
    v, sk = c.v, c.sk
    pts = face_pts(c)
    c.fpts = pts
    d = spline(pts)
    chin = c.face[4]
    fx = c.fx
    v.path(d, lg(32, 16, 66, 60, sk["lt"], sk["base"], mix(sk["base"], sk["sh"], 0.6)))
    # 髪・兜の下の影（額）
    hl = c.get("shade_y", 30.0)
    if hl:
        band = [pts[11], pts[12], pts[13], pts[0], pts[1], pts[2], pts[3], (CX + 9, hl + 2.5), (CX - 2, hl), (CX - 12, hl + 2.5)]
        v.path(spline(band), lg(0, hl - 9, 0, hl + 2.5, *fade(sk["sh"], 0.7, 0.0)))
    # 影側（右）の面
    inner = [(fx + 5.6, chin - 3.4), (fx + 10.4, 53.4), (fx + 12.0, 47.2), (fx + 13.4, 41.4), (CX + 14.4, 34.5), (CX + 13.6, 27)]
    v.path(spline(pts[2:8] + inner), lg(fx + 5, 0, CX + 18, 0, *fade(sk["sh"], 0.3, 0.85)))
    # 目のくぼみ
    age = c.get("age", 0)
    a = 0.26 + 0.1 * age + (0.08 if not c.fem else 0)
    v.path(spline([(pts[11][0] + 1, 33.5), (fx - 8, 32.6), (fx, 34.6), (fx + 8.6, 32.6), (pts[3][0] - 1, 33.5), (pts[4][0] - 1.2, 41), (fx + 9, 43.4), (fx, 41.6), (fx - 8.4, 43.4),
                   (pts[10][0] + 1.4, 41)]), lg(0, 32, 0, 44, *fade(sk["sh"], 0.0, 0.0, (0.5, a))))
    # 頬の赤み・光
    b = c.get("blush", 0.4 if c.fem else 0.22)
    spot(c, fx - 9.4, 48.6, 5.2, sk["blush"], b)
    spot(c, fx + 10.4, 48.8, 5.2, sk["blush"], b * 0.9)
    spot(c, fx - 9.0, 45.0, 4.6, WHITE, 0.22)
    v.path(d, None, c.skline, 1.05)
    # あごの線のリムライト
    v.path(spline([(pts[4][0] - 0.9, pts[4][1] + 2), (pts[5][0] - 0.9, pts[5][1] - 0.4), (pts[6][0] - 0.2, pts[6][1] - 0.9)], False), None, c.get("rimc", "#FFF2D0"), 0.7, salpha=0.5)


EYES = {
    #          幅   上   下   つり  虹彩  まつげ 羽  まゆの距離
    "soft": dict(w=8.6, hu=2.9, hl=1.7, tilt=0.7, ri=2.6, lash=1.25, wing=1.3, gap=6.4),
    "bright": dict(w=9.0, hu=3.3, hl=1.9, tilt=0.5, ri=2.9, lash=1.25, wing=1.1, gap=6.8),
    "sharp": dict(w=8.8, hu=2.3, hl=1.3, tilt=1.5, ri=2.35, lash=1.2, wing=1.6, gap=5.4),
    "calm": dict(w=8.6, hu=2.1, hl=1.5, tilt=0.5, ri=2.5, lash=1.15, wing=1.0, gap=5.8),
    "stern": dict(w=8.0, hu=1.9, hl=1.3, tilt=0.5, ri=2.25, lash=1.0, wing=0.0, gap=4.6),
    "open": dict(w=8.2, hu=2.6, hl=1.5, tilt=0.4, ri=2.45, lash=1.0, wing=0.0, gap=5.4),
    "kind": dict(w=8.0, hu=2.2, hl=1.4, tilt=-0.2, ri=2.3, lash=0.95, wing=0.0, gap=5.2),
}
BROWS = {
    #          内端dy 山   外端dy
    "soft": (-0.2, 1.0, 0.7),
    "flat": (0.3, 0.5, 0.2),
    "angry": (1.7, 0.4, -1.0),
    "sad": (-1.4, 0.5, 1.3),
    "arched": (0.2, 1.9, 0.3),
    "firm": (0.9, 0.7, -0.3),
}


def eye_centers(c):
    """(side, ex, 幅の倍率) を左（奥）・右（手前）の順に。"""
    return [(-1, c.fx - 7.9 + 0.3 * c.turn, 1.0 - 0.07 * c.turn), (1, c.fx + 8.3, 1.0)]


def draw_eyes(c):
    v, s, sk = c.v, c.s, c.sk
    kind = s.get("eyes", "soft" if c.fem else "open")
    ic = s.get("ic", "#5A4030")
    lash_c = mix(INK, "#1A0E14", 0.5)
    if kind in ("happy", "closed"):
        d = []
        for side, ex, k in eye_centers(c):
            w = 8.4 * k
            if kind == "happy":
                d.append(ribbon([(ex - side * w / 2, EYE_Y + 1.1), (ex - side * 0.4, EYE_Y - 1.9), (ex + side * w / 2, EYE_Y + 0.7)], [0.5, 1.5, 0.7]))
            else:
                d.append(ribbon([(ex - side * w / 2, EYE_Y - 0.2), (ex, EYE_Y + 1.6), (ex + side * w / 2, EYE_Y - 0.6), (ex + side * (w / 2 + 1.2), EYE_Y - 1.4)], [0.5, 1.4, 1.1, 0]))
        v.path(" ".join(d), lash_c)
        c.eye_gap = 6.0
        return
    e = dict(EYES[kind])
    e.update(s.get("eye_adj", {}))
    c.eye_gap = e["gap"]
    whites, irises, pupils, lights, lashes, creases, lowers = [], [], [], [], [], [], []
    gaze = s.get("gaze", (0.25, 0.0))
    for side, ex, k in eye_centers(c):
        w = e["w"] * k
        inner = (ex - side * w / 2, EYE_Y + 0.5)
        outer = (ex + side * w / 2, EYE_Y - e["tilt"])
        my = (inner[1] + outer[1]) / 2
        cu = (ex - side * 0.08 * w, my - 2 * e["hu"])
        cl = (ex + side * 0.14 * w, my + 2 * e["hl"])
        up = quad_pts(inner, cu, outer, 10)
        lo = quad_pts(outer, cl, inner, 8)
        opening = up + lo[1:-1]
        whites.append("M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f Q%.1f,%.1f %.1f,%.1f Z" % (inner + cu + outer + cl + inner))
        ix, iy = ex + gaze[0], EYE_Y - 0.45 + gaze[1]
        ri = e["ri"]
        irises.append(poly1(clip_convex(ellipse_pts(ix, iy, ri, ri, 16), opening)))
        pp = clip_convex(ellipse_pts(ix, iy, ri * 0.45, ri * 0.45, 8), opening)
        if pp:
            pupils.append(poly1(pp))
        lights.append(circle(ix - ri * 0.42, iy - ri * 0.42, ri * 0.33))
        lights.append(circle(ix + ri * 0.42, iy + ri * 0.4, ri * 0.15))
        # まつげ（上まぶたの線）
        peak = quad_pts(inner, cu, outer, 2)[1]
        lw = e["lash"]
        line = [inner, peak, outer]
        ws = [lw * 0.45, lw, lw * 1.05]
        if e["wing"] > 0:
            line.append((outer[0] + side * e["wing"], outer[1] - e["wing"] * 0.55))
            ws.append(0)
        lashes.append(ribbon(line, ws))
        creases.append("M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f" % (inner[0] + side * 1.0, inner[1] - 1.9, cu[0], cu[1] - 1.2, outer[0] - side * 0.2, outer[1] - 1.5))
        lmid = quad_pts(outer, cl, inner, 2)[1]
        lowers.append("M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f" % (outer[0], outer[1] + 0.2, (outer[0] + lmid[0]) / 2 + side * 0.4, lmid[1] + 0.1, lmid[0] - side * 0.6, lmid[1]))
    y0, y1 = EYE_Y - e["hu"] - 0.4, EYE_Y + e["hl"] + 0.4
    v.path(" ".join(whites), lg(0, y0, 0, y1, "#BDB4C4", "#FFFDFA", "#F4ECE8"))
    v.path(" ".join(irises), lg(0, EYE_Y - e["ri"] - 0.5, 0, EYE_Y + e["ri"], darken(ic, 0.55), ic, lighten(ic, 0.45)), darken(ic, 0.6), 0.35)
    if pupils:
        v.path(" ".join(pupils), darken(ic, 0.82))
    v.path(" ".join(lights), WHITE, alpha=0.95)
    v.path(" ".join(creases), None, sk["dp"], 0.55, salpha=0.6)
    v.path(" ".join(lowers), None, sk["line"], 0.5, salpha=0.6)
    v.path(" ".join(lashes), lash_c)


def draw_brows(c):
    v, s = c.v, c.s
    inner_dy, arch, outer_dy = BROWS[s.get("brow", "soft")]
    t = s.get("brow_t", 1.25 if c.fem else 1.9)
    col = s.get("browc") or mix(c.hc[1], c.hc[2], 0.6)
    by = EYE_Y - c.eye_gap + s.get("brow_dy", 0)
    d = []
    for side, ex, k in eye_centers(c):
        pts = [(ex - side * 4.6 * k, by + inner_dy), (ex - side * 1.0, by - arch * 0.75), (ex + side * 2.2 * k, by - arch), (ex + side * 5.4 * k, by + outer_dy)]
        d.append(ribbon(pts, [t * 0.85, t, t * 0.8, t * 0.2]))
    v.path(" ".join(d), col, mix(col, INK, 0.5), 0.3)


def draw_nose(c):
    v, s, sk = c.v, c.s, c.sk
    kind = s.get("nose", "small" if c.fem else "straight")
    nw, ny = {"small": (2.3, 48.6), "straight": (2.8, 49.2), "strong": (3.4, 49.8), "long": (2.7, 50.4), "button": (2.2, 48.0)}[kind]
    fx = c.fx - 0.5 * c.turn
    # 鼻すじの影（右）と小鼻
    v.path(spline([(fx + 1.2, EYE_Y + 0.5), (fx + 2.2, ny - 4), (fx + nw + 0.5, ny - 1.2), (fx + nw - 0.6, ny + 0.5), (fx + 0.6, ny - 0.2), (fx + 0.9, ny - 4.5)]),
           sk["sh"], alpha=0.55)
    v.path(spline([(fx - nw, ny - 1.1), (fx - nw * 0.6, ny + 0.2), (fx - 0.3, ny + 0.9), (fx + nw * 0.55, ny + 0.3), (fx + nw + 0.2, ny - 1.4)], False),
           None, sk["line"], 0.7, salpha=0.8)
    if kind in ("strong", "long"):
        v.path(spline([(fx - 1.0, EYE_Y + 1.5), (fx - 1.5, ny - 4), (fx - nw + 0.2, ny - 1.6)], False), None, sk["dp"], 0.5, salpha=0.5)
    v.path(ellipse(fx - 0.7, ny - 2.2, 1.05, 0.8), WHITE, alpha=0.42)
    v.path(ribbon([(fx - 0.6, EYE_Y + 1), (fx - 0.9, ny - 4)], [0.2, 0.9, 0.2]), WHITE, alpha=0.25)


def draw_mouth(c):
    v, s, sk = c.v, c.s, c.sk
    kind = s.get("mouth", "smile")
    fx = c.fx - 0.4 * c.turn
    my = s.get("mouth_y", 54.6 + (c.face[4] - 61.0) * 0.45)
    lips = s.get("lips", c.fem)
    lipc = s.get("lipc", sk["lip"])
    linec = mix(sk["line"], "#5A1420", 0.4)
    mw = {"smile": 4.4, "neutral": 4.0, "smirk": 4.4, "stern": 4.2, "frown": 4.2, "grin": 5.6, "oh": 2.2, "laugh": 5.4}[kind] * (1.0 if lips else 1.08)
    upL = upR = 0.0
    if kind == "smile":
        upL = upR = 1.0
    elif kind == "smirk":
        upL, upR = 0.1, 1.5
    elif kind in ("stern", "frown"):
        upL = upR = -0.7 if kind == "stern" else -1.3
    if kind in ("grin", "laugh"):
        dep = 3.6 if kind == "grin" else 5.0
        top = [(fx - mw, my - 1.3, 0), (fx - mw * 0.4, my - 0.2), (fx + mw * 0.4, my - 0.2), (fx + mw + 0.2, my - 1.5, 0)]
        bot = [(fx + mw * 0.62, my + dep * 0.72), (fx, my + dep), (fx - mw * 0.62, my + dep * 0.72)]
        v.path(spline(top + bot), "#7C1C2C", linec, 0.8)
        v.path(spline(top + [(fx + mw * 0.72, my + 1.5), (fx, my + 2.0), (fx - mw * 0.72, my + 1.5)]), "#FFFAF2")
        v.path(spline([(fx - mw * 0.45, my + dep * 0.78), (fx, my + dep * 0.62), (fx + mw * 0.45, my + dep * 0.78), (fx, my + dep - 0.4)]), "#E8707C")
        v.path(spline(top + bot), None, linec, 0.8)
        if lips:
            v.path(ribbon([(fx - mw * 0.6, my + dep * 0.82), (fx, my + dep + 0.5), (fx + mw * 0.6, my + dep * 0.82)], [0.3, 1.4, 0.3]), lipc)
        return
    if kind == "oh":
        v.path(ellipse(fx, my + 0.8, mw, 2.4), "#7C1C2C", linec, 0.8)
        v.path(ellipse(fx, my + 1.9, mw * 0.6, 0.9), "#E8707C")
        if lips:
            v.path(ellipse(fx, my + 0.8, mw + 0.5, 2.9), None, lipc, 0.9)
        return
    L = (fx - mw, my - upL)
    R = (fx + mw + 0.2, my - upR)
    cy = my + 0.45 + 0.35 * max(upL, upR, 0)
    if lips:
        v.path(spline([(L[0], L[1], 0), (fx - 1.7, my - 1.45), (fx, my - 0.85), (fx + 1.7, my - 1.45), (R[0], R[1], 0), (fx + 1.6, cy + 0.1), (fx - 1.6, cy + 0.1)]), darken(lipc, 0.16))
        v.path(spline([(L[0] + 0.7, L[1] + 0.25, 0), (fx, cy), (R[0] - 0.7, R[1] + 0.25, 0), (fx + 2.2, cy + 1.9), (fx - 2.2, cy + 1.9)]), lighten(lipc, 0.12))
        v.path(ellipse(fx - 0.9, cy + 1.05, 1.2, 0.5), WHITE, alpha=0.4)
    else:
        v.path(spline([(L[0] + 0.9, L[1] + 0.3, 0), (fx, cy), (R[0] - 0.9, R[1] + 0.3, 0), (fx + 2.0, cy + 1.6), (fx - 2.0, cy + 1.6)]), lipc, alpha=0.45)
        v.path(spline([(fx - 2.4, cy + 2.5), (fx, cy + 3.1), (fx + 2.4, cy + 2.5)], False), None, sk["dp"], 0.7, salpha=0.5)
    v.path(ribbon([L, (fx - 1.6, cy), (fx + 1.6, cy), R], [0.35, 0.85, 0.85, 0.35]), linec)
    if kind in ("smile", "smirk"):
        # 口角のくぼみ
        v.path(spline([(R[0] + 0.5, R[1] - 0.9), (R[0] + 0.9, R[1] + 0.1), (R[0] + 0.4, R[1] + 0.9)], False), None, sk["dp"], 0.5, salpha=0.6)


# ───────── 髪 ─────────
def hair_fill(c, x0=30, y0=6, x1=68, y1=62):
    lt, base, dk = c.hc
    return Linear(x0, y0, x1, y1, [lt, mix(lt, base, 0.85), dk])


def hshape(c, pts, grad=None, sw=1.05):
    c.v.path(spline(pts), grad or hair_fill(c), c.hline, sw)


def hstrands(c, lines, dark=True, a=0.5, w=0.75):
    """流れに沿った細い束（入り抜きつき）。lines は中心線の点列のリスト。"""
    d = " ".join(ribbon(ln, [0.1, w, 0.1]) for ln in lines)
    c.v.path(d, c.hc[2] if dark else lighten(c.hc[0], 0.3), alpha=a)


def hshine(c, pts, w=2.2, a=0.6):
    c.v.path(ribbon(pts, [0.2, w, w * 0.8, 0.2] if len(pts) == 4 else [0.2, w, 0.2]), lighten(c.hc[0], 0.4), alpha=a)


def hrim(c, pts, a=0.55):
    c.v.path(spline(pts, closed=False), None, c.get("rimc", "#FFF2D0"), 0.8, salpha=a)


def hback_grad(c):
    return lg(24, 8, 76, 96, c.hc[1], c.hc[2], darken(c.hc[2], 0.3))


def scallop(cx, cy, rx, ry, n, depth, a0=0.0, a1=360.0):
    """もこもこした縁（谷は角、山は丸）。"""
    pts = []
    for i in range(n):
        t0 = math.radians(a0 + (a1 - a0) * i / n)
        tm = math.radians(a0 + (a1 - a0) * (i + 0.5) / n)
        pts.append((cx + (rx - depth) * math.cos(t0), cy + (ry - depth) * math.sin(t0), 0))
        pts.append((cx + (rx + depth * 0.7) * math.cos(tm), cy + (ry + depth * 0.7) * math.sin(tm)))
    if a1 - a0 < 359.9:
        t1 = math.radians(a1)
        pts.append((cx + (rx - depth) * math.cos(t1), cy + (ry - depth) * math.sin(t1), 0))
    return pts


CAP_OUT = [(31, 45, 0), (28.8, 34), (28.4, 24), (32.2, 13.4), (41, 8), (50.5, 7.2), (60.4, 9.8), (66.4, 17), (68, 27), (67.4, 36), (66.2, 46.5, 0)]
LONG_BACK = [(48, 7), (37, 9), (29.5, 17), (26.5, 30), (25, 48), (22, 66), (19, 84), (18, 97, 0), (78, 97, 0), (77, 84), (74.5, 66), (71.5, 48),
             (69.5, 30), (66.5, 17), (59, 9)]


def hb_long(c):
    hshape(c, LONG_BACK, hback_grad(c))
    hstrands(c, [[(27, 40), (24.5, 62), (21, 90)], [(70, 40), (73, 62), (75.5, 90)], [(30, 60), (27.5, 78), (26, 95)], [(67, 60), (69.5, 78), (71, 95)]])
    hrim(c, [(70.6, 34), (72.8, 50), (75.6, 68), (77.4, 84)], 0.4)


def hlocks(c, locks, sw=0.95):
    """生え際から下がる毛束（根元は髪に溶け込み、先だけ輪郭が出る）。locks は (左の根元, 右の根元, 毛先) の列。"""
    fills, lines = [], []
    for a, b, tip in locks:
        ml = (a[0] + (tip[0] - a[0]) * 0.55 - 1.1, a[1] + (tip[1] - a[1]) * 0.55)
        mr = (b[0] + (tip[0] - b[0]) * 0.5 + 0.5, b[1] + (tip[1] - b[1]) * 0.5)
        pts = [a, ml, (tip[0], tip[1], 0.25), mr, b]
        fills.append(spline(pts))
        lines.append(spline(pts, closed=False))
    c.v.path(" ".join(fills), hair_fill(c))
    c.v.path(" ".join(lines), None, c.hline, sw)


def hf_crop(c):
    hshape(c, [(30.6, 41.5, 0), (29, 33), (28.6, 24), (31.6, 14.6), (39, 8.6), (49, 6.6), (58.6, 8.2), (65.6, 13.6), (68.2, 22), (67.8, 30), (67.2, 36), (65.6, 42, 0),
               (64.4, 34.5), (63.6, 28, 0.5), (61, 23), (57.6, 20.6), (50, 21), (42, 21), (36, 23), (33.2, 28.4, 0.6), (32.6, 35)])
    # 分け目から流した前髪
    hshape(c, [(59.8, 17.2, 0.4), (53, 12.2), (44, 11.8), (37, 15.2), (32.8, 21.5), (32, 27.5), (33.6, 31.6, 0.3), (36.6, 26.4), (42, 23.4), (49, 24.6), (55.6, 23.2),
               (58.8, 20.6)], sw=0.95)
    hshine(c, [(35.6, 20.6), (40.4, 15.6), (47, 14.4), (53.6, 15.6)], 2.0)
    hstrands(c, [[(57.6, 19), (50, 21.6), (42, 21), (36.4, 23.6)], [(63, 12.6), (66.4, 19), (66.8, 28)], [(60.6, 10), (64, 15), (65.4, 21)], [(65.8, 26), (66.6, 32), (66, 39)],
                 [(34.4, 12.6), (30.6, 19), (30, 28)]])
    hrim(c, [(62.6, 10.6), (66.6, 17), (67.4, 26)])


def hf_tousled(c):
    hshape(c, [(30.6, 42, 0), (28.6, 34), (27.6, 25), (29.4, 16), (34, 10), (38.6, 7.6), (41.6, 4.2, 0.25), (45, 6.8), (52, 5.4), (57, 3.2, 0.25), (60, 7.4),
               (65.8, 9, 0.25), (66.4, 15), (69.6, 22.2, 0.3), (68.2, 30), (67.6, 36), (65.8, 42.5, 0), (64.4, 35), (63.4, 28), (60, 23), (50, 22), (40, 22.6), (35, 25),
               (33, 30.5, 0.6), (32.5, 36)])
    hlocks(c, [((56.6, 17), (63.6, 21.5), (60.4, 32.4)), ((48.6, 15.5), (57.6, 17.6), (51.4, 31.6)), ((40.6, 16), (49.6, 16), (42.6, 32)), ((34, 19.6), (41.6, 16.6), (35.8, 30.6))])
    hshine(c, [(33, 19), (38.6, 12.6), (46, 10.4), (54, 11.6)], 2.2)
    hstrands(c, [[(48, 7), (50, 14), (51, 27)], [(41, 8), (42, 16), (42.4, 27)], [(58, 7.4), (60, 15), (60.4, 28)], [(31, 14), (29.6, 22), (30.4, 33)], [(66.4, 16), (67.4, 24), (66.8, 34)]])
    hrim(c, [(64.4, 10.6), (67.6, 17), (68.4, 26), (67.2, 36)])


def hb_ponytail(c):
    hshape(c, [(58, 10), (68, 5.5), (78, 10), (83, 23), (82.5, 40), (78.5, 54), (74.5, 63, 0), (73.4, 50), (74.4, 36), (71, 24), (64, 16)], hback_grad(c))
    hstrands(c, [[(70, 10), (78.5, 24), (77.5, 46)], [(74.5, 12), (80.5, 30), (77, 55)]])
    hshine(c, [(72, 8.6), (78.6, 15), (80.6, 26)], 1.8, 0.45)
    hrim(c, [(78.6, 11.5), (82, 23), (81.6, 40), (78, 53)], 0.5)


def hf_swept_bangs(c, flip=False):
    """分け目から流した前髪（まとめ髪・三つ編み・お団子の前側）。flip で分け目が右。"""
    line = [(64.8, 37), (64, 29), (61.5, 34, 0), (58.5, 27), (52, 25.2), (45, 22), (39.5, 19.4, 0.5), (36.5, 23.5), (34, 30), (32.6, 38)]
    st = [[(40, 12), (48, 19), (58, 25.5)], [(46, 9.4), (55, 15.6), (62.5, 26)], [(38, 13), (33.4, 21), (32, 33)], [(56, 10), (63.4, 17), (66.2, 30)]]
    sh = [(33.5, 19), (39.5, 12.6), (48, 11), (56, 13.4)]
    out = CAP_OUT
    if flip:
        line = mirror_pts(line, 48.4)
        out = mirror_pts(CAP_OUT, 48.2)
        hshape(c, line + out)
        st = [mirror_pts(x, 48.4)[::-1] for x in st]
    else:
        hshape(c, out + line)
    hshine(c, sh, 2.4)
    hstrands(c, st)
    hrim(c, [(63.4, 12.6), (66.8, 19.5), (67.4, 28), (66.8, 37)])


def hf_long(c):
    # 左右に分かれる前髪（センター分け）
    hshape(c, [(48.6, 8.6), (38.5, 8.4), (30.4, 15.5), (27.4, 28), (27.2, 42), (28.6, 55), (31.5, 64, 0), (32.6, 52), (32.4, 40), (34.6, 30), (40, 22.5),
               (46.6, 17), (49.4, 11.5)])
    hshape(c, [(48.6, 8.6), (58.5, 8.6), (66.2, 15.5), (69.4, 28), (69.8, 42), (68.6, 55), (66, 64, 0), (65.2, 52), (65.6, 40), (63.6, 30), (58.4, 22.8),
               (52.4, 17.4), (49.2, 11.5)])
    hshine(c, [(31.5, 21), (37, 13.5), (45, 11.2)], 2.6)
    hshine(c, [(53, 11.6), (60, 14), (65, 20.5)], 1.8, 0.4)
    hstrands(c, [[(44, 12), (34.5, 22), (30, 38)], [(30.5, 30), (29.5, 44), (30.6, 58)], [(53, 12), (62.5, 22), (67, 38)], [(67, 30), (67.8, 44), (66.8, 58)]])
    hrim(c, [(65.4, 15.6), (68.5, 28), (68.9, 42), (67.8, 54)], 0.45)


def hb_wild(c):
    hshape(c, [(48, 2.6), (39, 2.2), (31.5, 5), (27, 3.2, 0.3), (26, 9.5), (21, 13), (16.6, 11, 0.3), (19, 19.5), (17, 27), (13, 29.5, 0.3), (17.5, 35.5), (18, 44),
               (14.6, 49.5, 0.3), (20.5, 52), (24, 58), (27, 65, 0), (69, 65, 0), (72, 58), (75.5, 52), (81.4, 49.5, 0.3), (78, 44), (78.5, 35.5), (83, 29.5, 0.3), (79, 27),
               (77, 19.5), (79.4, 11, 0.3), (75, 13), (70, 9.5), (69, 3.2, 0.3), (64.5, 5), (57, 2.2)], hback_grad(c))
    hstrands(c, [[(27, 10), (22.6, 20), (20, 30)], [(69, 10), (73.4, 20), (76, 30)], [(23, 34), (21.4, 44), (22.6, 54)], [(73, 34), (74.6, 44), (73.4, 54)],
                 [(30, 6), (26, 14), (22.4, 17)], [(66, 6), (70, 14), (73.6, 17)]], w=0.9)
    hrim(c, [(70.4, 10.4), (76, 19.6), (77.6, 28), (77, 36), (77, 44)], 0.5)


def hf_wild(c):
    hshape(c, [(30.4, 43, 0), (28.2, 33), (27, 24), (30, 14), (38, 8.2), (48, 6), (58, 8), (66, 14), (69, 24), (68, 33), (65.8, 43, 0), (64.4, 35), (63.4, 28),
               (60, 23), (50, 22), (40, 22.6), (35, 25), (33, 30, 0.6), (32.6, 36)])
    hlocks(c, [((57, 16.6), (64.4, 22), (62, 34)), ((49.6, 15), (58.6, 17), (54.6, 32.4)), ((41, 15.6), (51, 15.6), (45.4, 34)), ((33.6, 20), (42.6, 16), (36.8, 32))], 1.0)
    hshine(c, [(32.5, 19), (39, 12), (48, 10), (56, 12)], 2.6)
    hstrands(c, [[(49, 8), (51, 16), (52.6, 28)], [(42, 9), (43, 18), (44.4, 29)], [(58.4, 9.6), (61, 18), (61.6, 30)]])


def hb_braid(c):
    # うしろへまとめた分の厚み
    hshape(c, [(48, 7.4), (38, 9), (30, 17), (29.4, 30), (31.4, 42), (66, 42), (68.6, 30), (66.6, 17), (58.6, 9)], hback_grad(c))


def braid(c, x0=67.6, y0=45.0, n=6, dx=0.55, dy=6.4):
    v = c.v
    lt, base, dk = c.hc
    for i in range(n - 1, -1, -1):
        x, y = x0 + dx * i, y0 + dy * i
        sg = 1 if i % 2 == 0 else -1
        v.begin(rotate=sg * 20, px=x, py=y)
        v.path(spline([(x, y - 4.8, 0.6), (x + 4.3, y - 0.5), (x + 1.2, y + 4.6), (x - 1.2, y + 4.6), (x - 4.3, y - 0.5)]),
               lg(x - 4, y - 5, x + 4, y + 5, lt, base, dk), c.hline, 0.95)
        v.path(ribbon([(x - 2.2, y - 1.8), (x - 0.6, y + 0.4), (x + 0.2, y + 3)], [0.1, 0.8, 0.1]), lighten(lt, 0.35), alpha=0.6)
        v.end()
    xe, ye = x0 + dx * n - 0.4, y0 + dy * n - 2.6
    v.path(spline([(xe - 2.4, ye), (xe + 2.4, ye), (xe + 3, ye + 5), (xe + 0.4, ye + 10.5, 0), (xe - 2.6, ye + 5)]), lg(xe - 3, ye, xe + 3, ye + 10, base, dk), c.hline, 0.9)
    tie = c.get("tie", "#C8A03A")
    v.path("M%.1f,%.1f h5.6 v2.6 h-5.6 Z" % (xe - 2.8, ye - 0.8), tie, INK, 0.6)


def hf_braid(c):
    hf_swept_bangs(c, flip=True)
    braid(c)


def hf_curtain(c):
    hshape(c, [(46.6, 13.6), (38, 16), (32.6, 24), (30.6, 36), (31.2, 48), (33, 58), (36, 67, 0), (35.4, 55), (34.4, 43), (35.6, 32.5), (40, 23.5), (46.8, 18.6)])
    hshape(c, [(49.6, 13.6), (58, 16), (63.6, 24), (65.8, 36), (65.4, 48), (63.8, 58), (61.2, 67, 0), (61.6, 55), (62.4, 43), (61, 32.5), (56.6, 23.5), (50.2, 18.6)])
    hstrands(c, [[(40, 19), (33.6, 30), (33, 46)], [(56, 19), (63, 30), (63.6, 46)]])
    hshine(c, [(33.4, 31), (35.6, 23.4), (41, 18)], 1.4, 0.4)


WAVY_BACK = [(48, 6.5), (36, 8), (28, 16), (25, 28), (22.5, 40), (25, 50), (20, 60), (22.5, 70), (16.5, 80), (19, 90), (15, 97, 0), (81, 97, 0), (77, 90), (79.5, 80),
             (73.5, 70), (76, 60), (71, 50), (73.5, 40), (71, 28), (68, 16), (60, 8)]


def hb_wavy(c):
    hshape(c, WAVY_BACK, hback_grad(c))
    hstrands(c, [[(26, 30), (25.5, 46), (22.6, 60), (21.6, 76)], [(70, 30), (70.5, 46), (73.4, 60), (74.4, 76)], [(29, 60), (25, 76), (24, 92)], [(67, 60), (71, 76), (72, 92)]], w=0.9)
    hrim(c, [(70.6, 28), (72.6, 40), (70.4, 50), (75, 60), (72.8, 70), (78.4, 80)], 0.45)


def hf_wavy(c):
    hshape(c, [(42.5, 9), (52, 7.4), (61, 9.6), (67.4, 16.5), (70, 27), (71.4, 38), (69, 48), (71, 57), (68, 66.5, 0), (66, 56), (66.8, 46), (64.8, 37), (62.5, 29),
               (56, 23.5), (48, 19.5), (43.5, 14)])
    hshape(c, [(43, 9), (36, 9.6), (29.8, 16), (27, 27), (25.6, 38), (27.6, 48), (25.4, 57), (28.5, 66.5, 0), (31, 56), (31.4, 46), (33, 37), (34.5, 28), (38, 20),
               (42.6, 14.5)])
    hshine(c, [(46, 11.6), (54, 11.4), (61, 14.4), (65.6, 21)], 2.4)
    hshine(c, [(29.6, 26), (31.6, 18.4), (37, 12.6)], 1.8, 0.5)
    hstrands(c, [[(46, 13), (58, 19), (65.5, 31), (68.4, 44)], [(52, 9.6), (63.4, 16), (68.6, 30)], [(40, 12), (32.4, 21), (29.4, 36), (29.6, 50)], [(68.4, 46), (67.8, 54), (68.4, 62)]])
    hrim(c, [(66.4, 16.6), (69, 27), (70.4, 38), (68, 48), (70, 57)], 0.45)


def hf_swept(c):
    hshape(c, [(30.6, 42, 0), (28.8, 33), (28.6, 24), (31.5, 14.5), (39, 8.6), (49, 6.6), (59, 8.4), (66, 14.6), (68.4, 23), (67.8, 33), (65.8, 42.5, 0),
               (64.6, 35), (63.8, 28), (61, 22.4), (56, 19.6), (51.5, 21.2), (48, 23.4, 0.5), (44.5, 21), (39.5, 19.6), (35, 22.6), (32.8, 28.5), (32.4, 35)])
    hshine(c, [(33, 19), (39, 12.4), (47, 10.4), (55, 11.8)], 2.2)
    hstrands(c, [[(48, 22.6), (51, 14), (58, 9.6)], [(42, 20), (43.5, 13), (48, 8.4)], [(55, 19.8), (59.5, 14.6), (64.6, 15)], [(36, 22), (35, 15.4), (38.4, 10.6)],
                 [(62, 23.6), (65.6, 22), (67.4, 28)]])
    hrim(c, [(62.4, 11.4), (66.6, 17), (67.8, 25), (67, 34)])


def hf_elder(c):
    # 後ろへ流した生え際と、肩の前へ下がる長い束
    hshape(c, [(27, 82, 0), (24.4, 72), (25.6, 58), (27.2, 44), (28.6, 30), (28.8, 22), (31.5, 14.5), (39, 8.6), (49, 6.6), (59, 8.4), (66, 14.6), (68.4, 22), (68.6, 30),
               (70, 44), (71.6, 58), (72.8, 72), (70.2, 82, 0), (68.6, 70), (67, 56), (65.6, 42), (64.6, 35), (63.8, 28), (61, 22.4), (56, 19.6), (51.5, 21.2), (48, 23.4, 0.5),
               (44.5, 21), (39.5, 19.6), (35, 22.6), (32.8, 28.5), (32.2, 35), (31.6, 42), (30.4, 56), (29, 70)])
    hstrands(c, [[(48, 22.6), (51, 14), (58, 9.6)], [(42, 20), (43.5, 13), (48, 8.4)], [(30.4, 30), (28.6, 46), (27.2, 62), (26.6, 76)], [(66.4, 30), (68.4, 46), (69.8, 62), (70.6, 76)]])
    hrim(c, [(67.8, 22), (68.4, 32), (69.8, 46), (71.4, 60), (72.4, 72)], 0.45)


def hb_shortbob(c):
    hshape(c, [(48, 6.5), (37, 8.5), (28.6, 16), (25.4, 28), (24.6, 40), (25.4, 50), (22.4, 58, 0), (29, 58.5), (36, 61), (60, 61), (67, 58.5), (73.6, 58, 0), (70.6, 50),
               (71.4, 40), (70.6, 28), (67.4, 16), (59, 8.5)], hback_grad(c))
    hrim(c, [(69.6, 28), (70.4, 40), (69.8, 50), (72, 56.6)], 0.5)


def hf_shortbob(c):
    hshape(c, [(29.5, 52.5, 0), (27.6, 42), (27.4, 28), (30.5, 15.5), (38.5, 8.4), (48.5, 6.6), (58.5, 8.4), (66.4, 15.5), (69.2, 28), (69, 42), (67.4, 52.5, 0),
               (65.4, 42), (64.4, 33), (63, 27), (59, 23), (50, 22), (40, 22.6), (35, 25), (33, 30), (32.2, 42)])
    hlocks(c, [((57.6, 16.6), (64.6, 22), (61.6, 35)), ((50.6, 15), (59, 17), (55.4, 33.6)), ((42, 15.4), (52, 15.4), (46, 33.4)), ((36, 17.6), (44, 15.6), (39.4, 33)),
               ((31.6, 22), (37.6, 17), (34.2, 34.4))])
    hshine(c, [(32.5, 20), (39, 12.6), (48, 10.6), (57, 12.6)], 2.6)
    hstrands(c, [[(50, 8.6), (52, 17), (53.4, 28)], [(41, 9.6), (42, 19), (43.6, 29)], [(30, 26), (29.4, 38), (30, 49)], [(66.8, 26), (67.6, 38), (67, 49)],
                 [(59, 10), (61.6, 18), (61.6, 29)]])


def hf_mohawk(c):
    v = c.v
    pts = c.fpts
    side = [pts[11], pts[12], pts[13], pts[0], pts[1], pts[2], pts[3], (CX + 15.4, 34), (CX + 13.4, 25.6), (CX + 6, 21.6), (CX - 5, 21.6), (CX - 12.8, 25.6), (CX - 15, 34)]
    v.path(spline(side), lg(30, 12, 66, 34, c.hc[1], c.hc[2]), alpha=0.5)
    hshape(c, [(42.4, 22.4, 0), (41.2, 15), (39.6, 8.4, 0), (44, 9.6), (45, 4, 0), (49, 7), (52.6, 3, 0), (54, 8.4), (59.2, 7.4, 0), (56.6, 14), (55.6, 22.8, 0), (49, 24.8)],
           hair_fill(c, 40, 0, 58, 26))
    hshine(c, [(43.6, 18), (44.6, 12), (47.6, 8.4)], 1.6)
    hstrands(c, [[(48, 23), (48.6, 14), (50.4, 7)], [(52.6, 22.6), (53.4, 14), (55.4, 9.6)]])


def hb_curly(c):
    hshape(c, scallop(48, 31, 23.5, 25, 15, 2.2), hback_grad(c))


def hf_curly(c):
    top = scallop(48, 30, 22.6, 23.6, 9, 2.0, 163.0, 377.0)
    line = [(62.8, 32), (59.6, 30.4, 0), (56.5, 25.8), (52.4, 27.8, 0), (48.5, 24.6), (44, 28, 0), (40.4, 25.4), (36.6, 30, 0), (33.6, 31.4)]
    hshape(c, top + line)
    v = c.v
    curls, lights = [], []
    for (x, y, r, a) in ((35, 17, 3.2, 200), (44, 11.5, 3.4, 250), (54, 11.5, 3.4, 300), (62, 18, 3.2, 340), (30.5, 28, 2.8, 160), (66.5, 29, 2.8, 20), (49, 19, 3.0, 270),
                         (39.5, 23, 2.6, 220), (58.5, 22.5, 2.6, 320)):
        a0 = math.radians(a)
        p3 = [(x + r * math.cos(a0 + k * 1.15), y + r * math.sin(a0 + k * 1.15)) for k in range(4)]
        curls.append(ribbon(p3, [0.1, 0.8, 0.8, 0.1]))
        lights.append(ribbon([(x + (r - 1.3) * math.cos(a0 + 3.3 + k * 0.9), y + (r - 1.3) * math.sin(a0 + 3.3 + k * 0.9)) for k in range(3)], [0.1, 0.9, 0.1]))
    v.path(" ".join(curls), c.hc[2], alpha=0.5)
    v.path(" ".join(lights), lighten(c.hc[0], 0.4), alpha=0.6)


def hf_sides(c):
    hshape(c, [(30.2, 30), (33.6, 29.4), (33.2, 37), (31.4, 43.5, 0), (29.6, 37)], hair_fill(c, 28, 28, 36, 44), 0.9)
    hshape(c, [(63.6, 29.6), (67.6, 30.4), (67.8, 38), (65.8, 44, 0), (64.6, 37)], hair_fill(c, 60, 28, 70, 44), 0.9)


BOB_BACK = [(48, 6.6), (37, 8.4), (29, 15.6), (26, 27), (25.4, 40), (26.4, 52), (28.6, 60, 0.5), (36, 62), (60, 62), (67.6, 60, 0.5), (70, 52), (70.8, 40), (70, 27),
            (67, 15.6), (59, 8.4)]


def hb_bob(c):
    hshape(c, BOB_BACK, hback_grad(c))


def hf_bob(c, low=59.5):
    hshape(c, [(48.5, 6.8), (38, 8.4), (30, 15.4), (27, 27), (26.6, 42), (28, low - 5.5), (30.5, low, 0), (33.8, low - 1.2, 0), (33.4, 46), (33, 30.4, 0.4), (38, 28.6), (41.6, 28, 0.5), (42.6, 25.4, 0), (43.8, 27.8, 0.5),
               (48, 27.5), (53, 27.8, 0.5), (54.2, 25.2, 0), (55.2, 28, 0.5), (59, 28.8), (63, 30.4, 0.4), (63.4, 46), (63.6, low - 1.2, 0), (66.8, low, 0), (69.2, low - 5.5), (70.4, 42), (70, 27), (67, 15.4), (59, 8.4)])
    hshine(c, [(31.4, 22), (37.6, 14), (47, 11.6), (56, 13.4)], 2.8)
    hstrands(c, [[(47, 9), (44, 18), (43.4, 27.6)], [(52, 9), (54.4, 18), (54.4, 27.6)], [(38, 12), (35.5, 20), (36.4, 28.6)], [(60, 12.6), (61.6, 21), (60.6, 28.8)],
                 [(29.4, 30), (29, 42), (30.6, low - 4)], [(67.6, 30), (68, 42), (66.6, low - 4)]])
    hshine(c, [(35.5, 24), (42, 22.6), (49, 22.4)], 1.1, 0.5)
    hrim(c, [(66.4, 15.6), (69.2, 27), (69.6, 42), (68.4, low - 6)], 0.5)


def hf_hime(c):
    hf_bob(c, low=56.5)


def hf_pompadour(c):
    hshape(c, [(30.8, 42, 0), (29, 33), (28.4, 25), (29.6, 15), (35.5, 6), (46, 1.4), (57.5, 1.8), (65.6, 7), (69, 15.5), (68.2, 26), (67.4, 36), (65.6, 42.5, 0),
               (64.4, 35), (63.4, 27.5), (59.5, 23.2), (50, 25), (41, 23.8), (35, 26.4, 0.6), (32.6, 31), (32.4, 36)])
    hshine(c, [(33.4, 19), (38.4, 9.6), (47, 5.4), (56.6, 6.4)], 3.2, 0.7)
    hshine(c, [(40, 20.5), (47, 18), (55, 19)], 1.3, 0.5)
    hstrands(c, [[(41, 23.4), (43.5, 13), (52, 6)], [(50, 24.6), (53, 15), (60.6, 9)], [(58.5, 23.2), (62, 17), (66.6, 15)], [(35.5, 25.6), (34.4, 16), (38.6, 8.6)],
                 [(64, 27), (66.8, 26), (67.6, 33)]], w=0.85)
    hrim(c, [(63.4, 6.4), (67.6, 13), (68, 22), (67.2, 32)])


def hb_bun(c):
    v = c.v
    x, y, r = 52.5, 6.6, 7.2
    v.path(circle(x, y, r), rg(x - 2.5, y - 2.5, r * 1.6, (0, c.hc[0]), (0.5, c.hc[1]), (1, c.hc[2])), c.hline, 1.05)
    hstrands(c, [[(47, 6), (51, 2.6), (56, 3)], [(48.6, 9.6), (53, 6.4), (58, 7.6)]])
    hb_braid(c)


def hf_bald(c):
    c.v.path(ribbon([(35, 21), (41, 16.2), (49, 15)], [0.3, 2.6, 0.3]), WHITE, alpha=0.4)


# 髪型: (うしろの関数, 前の関数, 額の影の位置)
HAIR = {
    "crop": (None, hf_crop, 29.0), "tousled": (None, hf_tousled, 31.0), "ponytail": (hb_ponytail, hf_swept_bangs, 30.0), "long": (hb_long, hf_long, 27.0),
    "wild": (hb_wild, hf_wild, 31.5), "braid": (hb_braid, hf_braid, 30.0), "curtain": (None, hf_curtain, 27.0), "wavy": (hb_wavy, hf_wavy, 27.0),
    "swept": (None, hf_swept, 25.5), "elder": (hb_long, hf_elder, 25.5), "shortbob": (hb_shortbob, hf_shortbob, 32.0), "mohawk": (None, hf_mohawk, 0),
    "curly": (hb_curly, hf_curly, 31.0), "sides": (None, hf_sides, 0), "bob": (hb_bob, hf_bob, 31.0), "hime": (hb_long, hf_hime, 31.0),
    "pompadour": (None, hf_pompadour, 28.5), "bun": (hb_bun, hf_swept_bangs, 30.0), "bald": (None, hf_bald, 0),
}


def hair_back(c):
    f = HAIR[c.get("hair", "crop")][0]
    if f:
        f(c)


def hair_front(c):
    HAIR[c.get("hair", "crop")][1](c)


# ───────── ひげ ─────────
def beard_fill(c, y0=44, y1=80):
    lt, base, dk = c.bc
    return Linear(34, y0, 62, y1, [lt, base, dk])


def draw_beard(c):
    """口より先に描く、あごのひげ。"""
    v, s = c.v, c.s
    kind = s.get("beard")
    if not kind:
        return
    fx = c.fx
    kw, jw, jy, chw, chin, _ = c.face
    pts = c.fpts
    lt, base, dk = c.bc
    line = mix(dk, INK, 0.55)
    xr, xl = CX + kw * c.sr, CX - kw * c.sl
    if kind == "stubble":
        v.path(spline([pts[4], pts[5], pts[6], pts[7], pts[8], pts[9], pts[10], (fx - 11.4, 48.6), (fx - 6, 51.4), (fx, 50.9), (fx + 6.5, 51.4), (fx + 12.6, 48.6)]),
               lg(0, 46, 0, chin, *fade(dk, 0.12, 0.42)))
        return
    if kind in ("full", "long", "wild", "sage"):
        top = [(xl + 0.4, 43.5), (fx - 11.6, 49), (fx - 7.6, 52.2), (fx - 4, 56.8), (fx, 57.6), (fx + 4.4, 56.8), (fx + 8.2, 52.2), (fx + 12.6, 49), (xr - 0.2, 43.5)]
        if kind == "full":
            out = [(xr + 0.6, 50), (fx + jw + 2.6, jy + 1), (fx + chw + 4, chin + 1.4), (fx + 0.4, chin + 4.4), (fx - chw - 3.4, chin + 1.4), (fx - jw - 1.2, jy + 1), (xl - 0.4, 50)]
            y1 = chin + 5
        elif kind == "long":
            out = [(xr + 1, 51), (fx + jw + 3.6, jy + 3), (fx + 13.5, chin + 7), (fx + 10.5, chin + 15), (fx + 6, chin + 22), (fx + 1, chin + 27.5, 0), (fx - 5, chin + 22),
                   (fx - 9.6, chin + 15), (fx - 12.5, chin + 7), (fx - jw - 2.2, jy + 3), (xl - 0.8, 51)]
            y1 = chin + 28
        elif kind == "sage":
            out = [(xr + 0.4, 50), (fx + jw + 1.6, jy + 1), (fx + chw + 3, chin + 1), (fx + 4.6, chin + 9), (fx + 2.6, chin + 17), (fx + 0.4, chin + 23, 0), (fx - 2.4, chin + 17),
                   (fx - 4.2, chin + 9), (fx - chw - 2.6, chin + 1), (fx - jw - 0.6, jy + 1), (xl - 0.2, 50)]
            y1 = chin + 23
        else:
            out = [(xr + 1.6, 49), (xr + 4.4, 55, 0), (fx + jw + 3.4, jy + 3), (fx + jw + 6, jy + 9, 0), (fx + 11.5, chin + 6), (fx + 12, chin + 13, 0), (fx + 6.4, chin + 10.5),
                   (fx + 4.6, chin + 17, 0), (fx + 0.4, chin + 12.4), (fx - 4, chin + 17, 0), (fx - 5.8, chin + 10.5), (fx - 11.4, chin + 13, 0), (fx - 10.6, chin + 6),
                   (fx - jw - 4.4, jy + 9, 0), (fx - jw - 2.2, jy + 3), (xl - 3.6, 55, 0), (xl - 1.2, 49)]
            y1 = chin + 16
        v.path(spline(top + out), beard_fill(c, 44, y1), line, 1.05)
        st = [[(fx - 9, 52), (fx - 8.4, 58), (fx - 5.4, chin + 1)], [(fx + 10, 52), (fx + 9.4, 58), (fx + 6.4, chin + 1)], [(fx - 2.4, 58.6), (fx - 2, chin), (fx - 0.6, chin + 3.4)]]
        if kind in ("long", "sage"):
            w = 1.0 if kind == "long" else 0.4
            st += [[(fx - 6 * w, chin + 3), (fx - 5 * w, chin + 11), (fx - 2 * w, chin + 20)], [(fx + 7 * w, chin + 3), (fx + 6 * w, chin + 11), (fx + 3 * w, chin + 20)],
                   [(fx + 1, chin + 4), (fx + 1.4, chin + 13), (fx + 0.8, chin + 22)]]
        if kind == "wild":
            st += [[(fx - 8, chin + 1), (fx - 8.6, chin + 6), (fx - 10.4, chin + 11)], [(fx + 9, chin + 1), (fx + 9.6, chin + 6), (fx + 11, chin + 11)],
                   [(fx + 2.4, chin + 3), (fx + 3.4, chin + 9), (fx + 4.2, chin + 15)]]
        v.path(" ".join(ribbon(x, [0.1, 0.8, 0.1]) for x in st), dk, alpha=0.5)
        v.path(ribbon([(fx - 10.4, 51), (fx - 9.6, 56.5), (fx - 7.4, chin - 0.4)], [0.2, 1.6, 0.2]), lighten(lt, 0.4), alpha=0.55)
    elif kind == "goatee":
        v.path(spline([(fx - 4.4, 57.6), (fx, 58.2), (fx + 4.8, 57.6), (fx + chw + 0.6, chin - 0.6), (fx + 0.3, chin + 4.6, 0.4), (fx - chw - 0.2, chin - 0.6)]), beard_fill(c, 56, chin + 5), line, 0.95)
        v.path(ribbon([(fx - 2.4, 59), (fx - 1.4, chin + 1), (fx, chin + 3.8)], [0.1, 0.9, 0.1]), lighten(lt, 0.3), alpha=0.5)


def draw_mustache(c):
    """口のあとに重ねる、口ひげ。"""
    v, s = c.v, c.s
    kind = s.get("beard")
    ms = s.get("mustache", kind in ("full", "long", "wild", "goatee", "sage"))
    if not ms:
        return
    fx = c.fx - 0.4 * c.turn
    lt, base, dk = c.bc
    line = mix(dk, INK, 0.55)
    my = s.get("mouth_y", 54.6 + (c.face[4] - 61.0) * 0.45)
    y = my - 3.6
    if ms == "walrus":
        pts = [(fx, y - 0.6), (fx + 3.6, y - 0.6), (fx + 7.6, y + 2.4), (fx + 8.6, y + 6, 0), (fx + 4.6, y + 4.6), (fx, y + 4), (fx - 4.6, y + 4.6), (fx - 8.4, y + 6, 0),
               (fx - 7.4, y + 2.4), (fx - 3.6, y - 0.6)]
    elif ms == "droop" or kind == "sage":
        pts = [(fx, y), (fx + 3.4, y - 0.2), (fx + 6.8, y + 2.4), (fx + 8.4, y + 8), (fx + 8, y + 15, 0), (fx + 6, y + 8), (fx + 3.6, y + 3.4), (fx, y + 2.6), (fx - 3.6, y + 3.4),
               (fx - 5.8, y + 8), (fx - 7.6, y + 15, 0), (fx - 8.2, y + 8), (fx - 6.6, y + 2.4), (fx - 3.4, y - 0.2)]
    elif ms == "curl":
        pts = [(fx, y + 0.2), (fx + 3.4, y - 0.4), (fx + 7, y + 1.4), (fx + 10.4, y - 0.6, 0), (fx + 8, y + 3.4), (fx + 4, y + 3.2), (fx, y + 2.4), (fx - 4, y + 3.2), (fx - 7.8, y + 3.4),
               (fx - 10, y - 0.6, 0), (fx - 6.8, y + 1.4), (fx - 3.4, y - 0.4)]
    else:
        pts = [(fx, y), (fx + 3.2, y - 0.4), (fx + 6.6, y + 2), (fx + 7.8, y + 4.6, 0), (fx + 4, y + 3.4), (fx, y + 2.6), (fx - 4, y + 3.4), (fx - 7.4, y + 4.6, 0), (fx - 6.4, y + 2),
               (fx - 3.2, y - 0.4)]
    v.path(spline(pts), lg(fx - 8, y - 1, fx + 8, y + 6, lt, base, dk), line, 0.85)
    v.path(ribbon([(fx - 5.6, y + 2.4), (fx - 3, y + 0.8), (fx - 0.6, y + 0.8)], [0.1, 0.8, 0.1]), lighten(lt, 0.4), alpha=0.5)


# ───────── 顔の特徴・小物 ─────────
def draw_marks(c):
    """肌の上の特徴（そばかす・しわ・傷・化粧）。目より先に描く。"""
    v, s, sk = c.v, c.s, c.sk
    fx = c.fx
    ex = s.get("extra", ())
    age = s.get("age", 0)
    if "freckles" in ex:
        d = [circle(fx + x, y, r) for x, y, r in ((-11, 46.4, 0.5), (-8.6, 47.6, 0.55), (-6.2, 46.2, 0.45), (-9.8, 49, 0.4), (-3.6, 46.8, 0.4), (4, 46.8, 0.4), (6.8, 46.2, 0.45),
                                                 (9.4, 47.6, 0.55), (11.8, 46.4, 0.5), (10.4, 49, 0.4))]
        v.path(" ".join(d), sk["dp"], alpha=0.75)
    if age >= 1:
        d = ["M%.1f,48.6 Q%.1f,52 %.1f,55.4" % (fx - 5.2, fx - 7.6, fx - 6.8), "M%.1f,48.6 Q%.1f,52 %.1f,55.4" % (fx + 5.6, fx + 8.2, fx + 7.4),
             "M%.1f,43.2 Q%.1f,44.6 %.1f,44" % (fx - 11.6, fx - 8.5, fx - 5.2), "M%.1f,43.2 Q%.1f,44.6 %.1f,44" % (fx + 5.6, fx + 9, fx + 12.2)]
        if age >= 2:
            d += ["M%.1f,39 l2.6,-1.2 M%.1f,40.6 l2.8,0.2" % (fx + 13.6, fx + 13.8), "M%.1f,27.4 Q%.1f,26.2 %.1f,27.4" % (fx - 8, fx, fx + 8.6),
                  "M%.1f,24.2 Q%.1f,23.2 %.1f,24.2" % (fx - 6, fx, fx + 6.6), "M%.1f,34.4 v-2.6 M%.1f,34.2 v-2.2" % (fx - 0.9, fx + 1.3)]
        v.path(" ".join(d), None, sk["dp"], 0.6, salpha=0.75)
    if "paint" in ex:
        pc = s.get("paintc", "#3A6ABE")
        v.path(" ".join([ribbon([(fx + 8, 45), (fx + 11.5, 47.4), (fx + 13.6, 51)], [0.4, 1.7, 0.3]), ribbon([(fx + 7.4, 48), (fx + 10, 50.4), (fx + 11.4, 53.4)], [0.4, 1.5, 0.3]),
                         ribbon([(fx - 8, 45), (fx - 10.8, 47.4), (fx - 12.2, 51)], [0.4, 1.7, 0.3]), ribbon([(fx - 7.4, 48), (fx - 9.4, 50.4), (fx - 10.4, 53.4)], [0.4, 1.5, 0.3])]),
               pc, alpha=0.85)
    if "mole" in ex:
        v.path(circle(fx + 7.2, 51.6, 0.6), sk["line"])


def draw_scar(c):
    v, s, sk = c.v, c.s, c.sk
    fx = c.fx
    ex = s.get("extra", ())
    if "scar" in ex:      # 左目（奥）をまたぐ傷
        col = mix(sk["blush"], sk["dp"], 0.5)
        v.path(ribbon([(fx - 11.4, 30.5), (fx - 9.2, 39), (fx - 6.6, 48.5)], [0.3, 1.4, 0.3]), col)
        v.path(ribbon([(fx - 11.2, 31.5), (fx - 9.3, 38.6), (fx - 7, 47.4)], [0.1, 0.5, 0.1]), sk["lt"], alpha=0.7)
    if "scar2" in ex:     # 右頬の傷
        col = mix(sk["blush"], sk["dp"], 0.5)
        v.path(ribbon([(fx + 5.4, 45.4), (fx + 9.4, 48.4), (fx + 12.4, 52.4)], [0.3, 1.3, 0.3]), col)
        v.path("M%.1f,47.4 l-1.4,1.8 M%.1f,49.6 l-1.4,1.8" % (fx + 8.6, fx + 11), None, col, 0.6)


def draw_specs(c):
    """眼鏡・サングラス・眼帯・耳飾り。"""
    v, s = c.v, c.s
    ex = s.get("extra", ())
    eyes = eye_centers(c)
    xr = CX + c.face[0] * c.sr
    xl = CX - c.face[0] * c.sl
    if "glasses" in ex:
        fc = s.get("framec", "#3A3448")
        d = " ".join(circle(e[1], EYE_Y + 0.4, 5.5 * (0.96 if e[0] < 0 else 1.0)) for e in eyes)
        v.path(d, lg(0, 34, 0, 46, rgba("#FFFFFF", 0.34), rgba("#BFE4FF", 0.06)))
        v.path(" ".join(ribbon([(e[1] - 3.6, EYE_Y - 1), (e[1] - 1.4, EYE_Y - 3.6)], [0.2, 1.0, 0.2]) for e in eyes), WHITE, alpha=0.65)
        v.path(d + " M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f M%.1f,%.1f L%.1f,%.1f M%.1f,%.1f L%.1f,%.1f" % (
            eyes[0][1] + 5.2, EYE_Y - 0.6, (eyes[0][1] + eyes[1][1]) / 2, EYE_Y - 2.4, eyes[1][1] - 5.4, EYE_Y - 0.6,
            eyes[1][1] + 5.4, EYE_Y - 0.8, xr + 0.6, EYE_Y - 1.8, eyes[0][1] - 5.2, EYE_Y - 0.8, xl - 0.2, EYE_Y - 1.6), None, fc, 1.0)
    if "shades" in ex:
        fc = GOLDM
        d = []
        for sd, ex_, k in eyes:
            w = 6.3 * k
            d.append(spline([(ex_ - w, EYE_Y - 3.4, 0.5), (ex_ + w, EYE_Y - 3.4, 0.5), (ex_ + w * 0.92, EYE_Y + 2.6), (ex_ + sd * -1.0, EYE_Y + 4.6), (ex_ - w * 0.92, EYE_Y + 2.6)]))
        dd = " ".join(d)
        v.path(dd, lg(30, 34, 62, 46, "#5A3A8C", "#1A1228", "#3A1A3C", "#C4563A"), None, 0)
        v.path(" ".join(ribbon([(e[1] - 4, EYE_Y + 0.4), (e[1] - 1.6, EYE_Y - 2.6), (e[1] + 1.6, EYE_Y - 2.9)], [0.2, 1.3, 0.2]) for e in eyes), WHITE, alpha=0.6)
        v.path(dd + " M%.1f,%.1f Q%.1f,%.1f %.1f,%.1f M%.1f,%.1f L%.1f,%.1f M%.1f,%.1f L%.1f,%.1f" % (
            eyes[0][1] + 5.6, EYE_Y - 2.4, (eyes[0][1] + eyes[1][1]) / 2, EYE_Y - 3.6, eyes[1][1] - 6, EYE_Y - 2.4,
            eyes[1][1] + 6.2, EYE_Y - 2.8, xr + 0.6, EYE_Y - 2.6, eyes[0][1] - 5.8, EYE_Y - 2.8, xl - 0.2, EYE_Y - 2.4), None, lg(0, 35, 0, 45, fc[1], fc[3]), 1.1)
    if "eyepatch" in ex:
        e = eyes[1]
        v.path("M%.1f,%.1f L%.1f,%.1f M%.1f,%.1f L%.1f,%.1f" % (xl + 1, 27, e[1] - 3, EYE_Y - 3.6, e[1] + 4, EYE_Y - 2, xr, EYE_Y - 4), None, "#2A2028", 1.3)
        v.path(spline([(e[1] - 5.6, EYE_Y - 4.4, 0.5), (e[1] + 5.8, EYE_Y - 3.6, 0.5), (e[1] + 4.6, EYE_Y + 3), (e[1] + 0.4, EYE_Y + 5.4), (e[1] - 4.4, EYE_Y + 2.8)]),
               lg(e[1] - 5, 35, e[1] + 5, 46, "#5A4A50", "#2A2028"), INK, 0.8)
    if "earring" in ex:
        v.path(circle(xr + 2.6, 51.6, 1.9), None, lg(0, 49, 0, 54, GOLDM[1], GOLDM[3]), 1.1)
        v.path(circle(xr + 2.2, 50.8, 0.5), WHITE, alpha=0.8)


# ───────── 頭の装飾 ─────────
def gear_back(c):
    v, s = c.v, c.s
    g = s.get("gear")
    gc = s.get("gc", "#3E3458")
    if g == "hood":
        v.path(spline(HOOD_OUT), darken(gc, 0.62), INK, 1.2)


HOOD_OUT = [(48, 1.4, 0.7), (35, 4), (25.5, 14), (21.5, 30), (21, 46), (17.5, 59), (12, 68.5, 0.4), (28, 73.5), (48, 76.5), (68, 73.5), (84, 68.5, 0.4), (78.5, 59), (75, 46),
            (74.5, 30), (70.5, 14), (61, 4)]
HOOD_IN = [(48, 10.2, 0.8), (39, 12.4), (32.6, 20), (30.2, 32), (30.6, 44), (32.6, 54), (36.5, 62.5, 0.5), (41, 68.5), (48, 70.5), (55, 68.5), (59.6, 62.5, 0.5), (63.6, 54), (65.8, 44),
           (66.2, 32), (63.6, 20), (57, 12.4)]


def metal_dome(m, cx=44, cy=12, r=34):
    return rg(cx, cy, r, m[0], m[2], m[3])


def gear_front(c):
    v, s = c.v, c.s
    g = s.get("gear")
    gc = s.get("gc", "#8C95A6")
    if g == "helm":
        m = s.get("gm", IRON)
        tm = s.get("gtrim", BRONZE)
        # ほお当て
        for side, x0 in ((-1, 29.6), (1, 66.6)):
            pts = [(x0 - side * 3.2, 28), (x0 + side * 2.2, 28), (x0 + side * 2.8, 40), (x0 + side * 1.0, 49.5), (x0 - side * 2.6, 52, 0), (x0 - side * 3.6, 40)]
            if side < 0:
                pts.reverse()
            v.path(spline(pts), lg(x0 - 4, 28, x0 + 4, 52, m[1], m[2], m[3]), INK, 1.0)
        v.path(spline([(27, 33.5, 0), (26.6, 24), (30.6, 13), (39.2, 6.2), (48, 4.4), (56.8, 6.2), (65.4, 13), (69.4, 24), (69, 33.5, 0), (64.6, 29.4), (57.4, 26.6), (48, 25.6),
                       (38.6, 26.6), (31.4, 29.4)]), metal_dome(m), INK, 1.25)
        v.path(ribbon([(28, 30.6), (31.6, 26.6), (38.6, 23.6), (48, 22.6), (57.4, 23.6), (64.4, 26.6), (68, 30.6)], [3.6, 3.4, 3.2, 3.2, 3.2, 3.4, 3.6]),
               lg(0, 21, 0, 32, tm[1], tm[2], tm[3]), INK, 0.8)
        v.path(" ".join(circle(x, y, 0.75) for x, y in ((32.6, 26), (40, 23.2), (56, 23.2), (63.4, 26))), tm[0], tm[4], 0.3)
        v.path(ribbon([(48, 4.6), (48.3, 13), (48.3, 21)], [1.4, 2.4, 2.8]), lg(46, 0, 50, 0, m[0], m[2], m[3]), INK, 0.6)
        v.path(ribbon([(32.6, 21), (35.5, 13.4), (41.6, 8.6)], [0.3, 2.6, 0.3]), m[0], alpha=0.75)
        if s.get("nasal"):
            v.path(spline([(46.4, 24, 0), (50, 24, 0), (49.4, 40), (48, 45.5), (46.8, 40)]), lg(46, 0, 50, 0, m[1], m[3]), INK, 0.8)
        if s.get("plume"):
            pc = s["plume"]
            v.path(spline([(48, 5.4, 0), (44, 1), (48, -4), (56, -3), (63, 2), (67.5, 10, 0), (60, 6), (54, 5)]), lg(44, -4, 66, 10, lighten(pc, 0.35), pc, darken(pc, 0.4)), INK, 1.0)
        hrim(c, [(63.6, 11.6), (67.6, 19), (68.4, 26)], 0.65)
    elif g == "winged":
        m = s.get("gm", GOLDM)
        wm = s.get("wingc", "#FFFFFF")
        for side in (-1, 1):
            bx = 48.2 + side * 18.4
            d = []
            for (tx, ty, w) in ((16.5, -22, 4.6), (19.5, -13, 4.4), (18, -4.5, 4.0)):
                tip = (bx + side * tx, 26 + ty)
                mid = (bx + side * tx * 0.5 - side * 1.5, 26 + ty * 0.45 - w * 0.9)
                low = (bx + side * tx * 0.55, 26 + ty * 0.5 + w * 0.55)
                pts = [(bx - side * 1, 24.5), mid, (tip[0], tip[1], 0), low, (bx, 29)]
                if side < 0:
                    pts.reverse()
                d.append(spline(pts))
            v.path(" ".join(d), lg(bx - 4, 30, bx + side * 18 - 4, 2, darken(wm, 0.22), wm, WHITE), mix("#6A5A30", INK, 0.4), 0.9)
            v.path(circle(bx, 26.6, 3.0), lg(0, 23, 0, 30, m[1], m[2], m[3]), INK, 0.8)
        band = [(29.4, 27.4), (37, 23.2), (48, 21.2), (59, 23.2), (67, 27.4)]
        v.path(ribbon(band, [2.6, 3.0, 3.6, 3.0, 2.6]), lg(0, 19, 0, 30, m[0], m[1], m[3]), INK, 0.9)
        v.path(spline([(44.6, 21.4, 0), (48, 11.5, 0), (51.4, 21.4, 0), (48, 24.4)]), lg(45, 12, 51, 24, m[0], m[1], m[3]), INK, 0.8)
        gem(c, 48, 21.4, 2.1, s.get("gem", "#E03A4A"), m)
    elif g == "crown":
        m = s.get("gm", GOLDM)
        v.path(spline([(30.4, 25.6, 0), (28.8, 10.4, 0), (34.8, 16.6, 0), (39.4, 7.6, 0), (43.8, 15.6, 0), (48, 4.6, 0), (52.2, 15.6, 0), (56.6, 7.6, 0), (61.2, 16.6, 0),
                       (67.2, 10.4, 0), (65.8, 25.6, 0), (57, 28.8), (48, 29.8), (39, 28.8)]), lg(30, 4, 66, 30, m[0], m[1], m[3]), INK, 1.2)
        v.path(ribbon([(30.6, 23), (39, 26.4), (48, 27.4), (57, 26.4), (65.6, 23)], [2.8] * 5), lg(0, 21, 0, 30, m[1], m[2], m[3]), INK, 0.6)
        v.path(ribbon([(30.4, 17.6), (39, 20.8), (48, 21.8), (57, 20.8), (65.8, 17.6)], [0.9] * 5), m[3], alpha=0.8)
        v.path(" ".join(circle(x, y, 1.5) for x, y in ((28.8, 10), (39.4, 7.2), (48, 4.2), (56.6, 7.2), (67.2, 10))), lg(0, 0, 0, 10, "#FFFFFF", "#E4DCC8"), INK, 0.6)
        gem(c, 48, 22.6, 2.4, s.get("gem", "#E03A4A"), m)
        gem(c, 38.6, 21.4, 1.5, "#3A7AE0", m, "round")
        gem(c, 57.6, 21.4, 1.5, "#3A7AE0", m, "round")
        v.path(ribbon([(31.6, 20.5), (32.4, 14.6), (30.6, 12)], [0.2, 1.3, 0.2]), WHITE, alpha=0.7)
        hrim(c, [(66.4, 12), (66, 19), (65, 25)], 0.7)
    elif g == "circlet":
        m = s.get("gm", GOLDM)
        v.path(ribbon([(30.8, 28.4), (38, 24.6), (48, 23.4), (58, 24.6), (65.4, 28.4)], [1.5, 1.7, 1.9, 1.7, 1.5]), lg(0, 22, 0, 30, m[0], m[2], m[3]), INK, 0.6)
        for side in (-1, 1):
            pts = [(48 + side * 2.4, 23.6, 0), (48 + side * 7.4, 20.4), (48 + side * 11.4, 22.6, 0), (48 + side * 7, 24.2)]
            if side < 0:
                pts.reverse()
            v.path(spline(pts), lg(0, 20, 0, 25, m[0], m[2]), INK, 0.5)
        gx, gy = 48, 25.2
        gcol = s.get("gem", "#58D0E0")
        v.path(spline([(gx, gy - 3.4, 0), (gx + 2.5, gy + 0.4), (gx, gy + 3.6, 0.6), (gx - 2.5, gy + 0.4)]), lg(gx - 2, gy - 3, gx + 2, gy + 4, lighten(gcol, 0.6), gcol, darken(gcol, 0.4)), INK, 0.7)
        v.path(circle(gx - 0.7, gy - 0.6, 0.6), WHITE, alpha=0.9)
    elif g == "tiara":
        m = s.get("gm", GOLDM)
        v.path(spline([(33, 24.6, 0), (37.6, 18.4), (41.6, 20.2, 0), (44.6, 13.2), (48, 8.4, 0), (51.4, 13.2), (54.4, 20.2, 0), (58.4, 18.4), (63, 24.6, 0), (56, 22.4), (48, 21),
                       (40, 22.4)]), lg(34, 8, 62, 26, m[0], m[1], m[3]), INK, 1.0)
        gcol = s.get("gem", "#E86AB8")
        gem(c, 48, 16.4, 2.3, gcol, m)
        gem(c, 38.8, 21, 1.2, gcol, m, "round")
        gem(c, 57.2, 21, 1.2, gcol, m, "round")
    elif g == "hood":
        d = spline(HOOD_OUT) + " " + spline(list(reversed(HOOD_IN)))
        v.path(d, lg(22, 2, 78, 74, lighten(gc, 0.22), gc, darken(gc, 0.45)), None, 0, evenodd=True)
        v.path(spline(HOOD_OUT), None, INK, 1.25)
        v.path(spline(HOOD_IN[:7] if False else HOOD_IN), None, INK, 0.95)
        v.path(" ".join([ribbon([(40, 6.4), (30.4, 15), (26, 30), (25.6, 46)], [0.2, 2.2, 1.6, 0.2]), ribbon([(36, 9.4), (29.6, 20), (28, 34)], [0.1, 0.9, 0.1])]), lighten(gc, 0.45), alpha=0.5)
        v.path(" ".join([ribbon([(60, 7.6), (68, 17), (70.6, 32), (71.6, 48)], [0.2, 1.5, 1.2, 0.2]), ribbon([(24, 48), (21.6, 58), (18, 68)], [0.2, 1.2, 0.2]),
                         ribbon([(73.4, 50), (75.6, 59), (79, 68)], [0.2, 1.2, 0.2])]), darken(gc, 0.6), alpha=0.5)
        tr = s.get("hood_trim")
        if tr:
            v.path(spline([(x, y + (0.9 if y > 60 else 0)) for x, y, *r in HOOD_IN[9:] + HOOD_IN[:7]], False), None, tr, 1.0, salpha=0.9)
        hrim(c, [(62, 5.4), (70, 14.6), (73.6, 30), (74.2, 46), (77.6, 60)], 0.5)
    elif g == "beret":
        v.path(spline([(26.6, 20.4), (29.4, 9.6), (39.4, 3), (52.6, 1.2), (65, 5), (72, 13), (71, 20.6), (62.6, 23.4), (48, 20.4), (35, 22.6)]),
               rg(40, 6, 36, (0, lighten(gc, 0.4)), (0.45, gc), (1, darken(gc, 0.45))), INK, 1.2)
        v.path(ribbon([(28.4, 20), (36, 21.6), (48, 19.6), (62, 22.6), (70, 20)], [1.4, 2.0, 2.2, 2.0, 1.4]), darken(gc, 0.3), alpha=0.8)
        v.path(spline([(51.4, 1.8, 0), (52.4, -1.8), (54.6, -1.4), (54, 1.8, 0)]), darken(gc, 0.2), INK, 0.8)
        v.path(ribbon([(32, 13.4), (38.6, 7.4), (48, 5)], [0.3, 2.6, 0.3]), WHITE, alpha=0.5)
        hrim(c, [(65.6, 6.4), (70.8, 13), (70.2, 19.6)], 0.6)
    elif g == "straw":
        sc1, sc2, sc3 = "#FFF0B0", "#EBCB72", "#B48A3A"
        v.begin(px=48, py=21.5, sy=0.215)
        v.path(circle(48, 21.5, 41), rg(40, 6, 52, (0, sc1), (0.6, sc2), (1, sc3)), INK, 1.2)
        v.end()
        d = []
        for i in range(14):
            a = math.radians(i * 180 / 13.0)
            d.append("M%.1f,%.1f L%.1f,%.1f" % (48 - 25 * math.cos(a), 21.5 + 5.4 * math.sin(a) + 1.0, 48 - 39.5 * math.cos(a), 21.5 + 8.3 * math.sin(a)))
        v.path(" ".join(d), None, sc3, 0.6, salpha=0.6)
        v.path(spline([(32.4, 22.6, 0), (33.4, 9.4), (40.4, 2.6), (48, 1), (55.6, 2.6), (62.6, 9.4), (63.6, 22.6, 0), (56, 25.2), (48, 26), (40, 25.2)]),
               rg(42, 6, 30, (0, sc1), (0.55, sc2), (1, sc3)), INK, 1.15)
        v.path(" ".join("M%.1f,%.1f Q48,%.1f %.1f,%.1f" % (34.4, y, y + 2.4, 61.6, y) for y in (7.4, 11.6)), None, sc3, 0.6, salpha=0.6)
        bc = s.get("bandc", "#C8443A")
        v.path(ribbon([(32.6, 19.4), (40, 22.2), (48, 23), (56, 22.2), (63.4, 19.4)], [3.6] * 5), lg(0, 17, 0, 25, lighten(bc, 0.25), bc, darken(bc, 0.3)), INK, 0.7)
        v.path(ribbon([(35.4, 12), (38.4, 6.4), (44, 3.6)], [0.3, 2.0, 0.3]), WHITE, alpha=0.6)
    elif g in ("headband", "bandana"):
        if g == "bandana":
            v.path(spline([(27.6, 29.6, 0), (27.8, 20), (32.6, 11.4), (40.6, 6.6), (50, 5.6), (59.4, 7.8), (66, 13.8), (69, 22), (68.8, 29.6, 0), (60, 25), (48, 23.4), (36, 25)]),
                   rg(40, 8, 36, (0, lighten(gc, 0.35)), (0.5, gc), (1, darken(gc, 0.45))), INK, 1.15)
            v.path(" ".join(circle(x, y, 0.9) for x, y in ((36, 14), (44, 10), (53, 9.6), (61, 13.6), (40.6, 18.6), (49, 16), (57.4, 18), (33, 20.6), (64.6, 20.4))), WHITE, alpha=0.55)
        # 結び目と垂れ
        v.path(spline([(67.6, 27, 0), (73.6, 30), (77.6, 37), (78.6, 44, 0), (74, 40.4), (71.6, 34.5), (67, 30.4, 0)]), cloth(gc, 66, 26, 80, 46), INK, 0.9)
        v.path(spline([(67.8, 28.4, 0), (72, 33.6), (72.6, 41), (70.6, 47.6, 0), (69, 41), (68.4, 35), (66.4, 31, 0)]), cloth(darken(gc, 0.12), 66, 28, 74, 48), INK, 0.9)
        bw = s.get("band_w", 3.6)
        v.path(ribbon([(28.2, 28.6), (36, 24.2), (48, 22.6), (60, 24.2), (68.2, 28.6)], [bw - 0.2, bw, bw + 0.2, bw, bw - 0.2]), lg(0, 20, 0, 31, lighten(gc, 0.35), gc, darken(gc, 0.3)), INK, 0.85)
        v.path(circle(68, 28.6, 1.9), gc, INK, 0.8)
        v.path(ribbon([(31, 26.4), (38, 23.2), (46, 22)], [0.2, 1.0, 0.2]), WHITE, alpha=0.45)
    elif g == "cap":
        v.path(spline([(26.4, 25, 0.6), (28.6, 13), (38, 5), (51, 3), (63.4, 6.6), (70.6, 15), (71, 24, 0.6), (62, 22), (48, 20.2), (35, 22.4)]),
               rg(40, 8, 36, (0, lighten(gc, 0.4)), (0.45, gc), (1, darken(gc, 0.45))), INK, 1.2)
        v.path(ribbon([(27.4, 24), (36, 22), (48, 19.8), (61, 21.6), (70, 23)], [2.4, 2.8, 3.0, 2.8, 2.4]), lg(0, 18, 0, 26, lighten(gc, 0.1), darken(gc, 0.4)), INK, 0.6)
        fcol = s.get("feather", "#E8E4D8")
        v.path(spline([(61.6, 19.6, 0), (63.6, 10), (70, 2), (79, -3.4, 0), (77, 6), (71.4, 14)]), lg(62, 20, 78, -2, darken(fcol, 0.25), fcol, lighten(fcol, 0.5)), INK, 0.8)
        v.path(spline([(62, 19), (67.6, 9.6), (76.6, -1)], False), None, darken(fcol, 0.45), 0.5)
        v.path(ribbon([(31.6, 16), (37.6, 9), (47, 6)], [0.3, 2.4, 0.3]), WHITE, alpha=0.45)
    pin = s.get("pin")
    if pin == "leaf":
        x, y = s.get("pin_xy", (63, 23))
        pc = s.get("pinc", "#F0502A")
        pts = []
        for i, (a, r) in enumerate(((-90, 6), (-64, 2.6), (-34, 5.4), (-12, 2.4), (22, 4.6), (58, 2.2), (90, 3.6), (122, 2.2), (158, 4.6), (192, 2.4), (214, 5.4), (244, 2.6))):
            pts.append((x + r * math.cos(math.radians(a)), y + r * math.sin(math.radians(a)), 0))
        v.begin(rotate=24, px=x, py=y)
        v.path(spline(pts), lg(x - 5, y - 6, x + 5, y + 5, lighten(pc, 0.45), pc, darken(pc, 0.3)), INK, 0.7)
        v.path("M%.1f,%.1f V%.1f" % (x, y - 4.6, y + 5.6), None, darken(pc, 0.45), 0.5)
        v.end()
    elif pin == "snow":
        x, y = s.get("pin_xy", (63.5, 25))
        d = []
        for k in range(3):
            a = math.radians(k * 60 + 30)
            dx, dy = 4.2 * math.cos(a), 4.2 * math.sin(a)
            d.append("M%.1f,%.1f L%.1f,%.1f" % (x - dx, y - dy, x + dx, y + dy))
        v.path(" ".join(d), None, INK, 2.2)
        v.path(" ".join(d), None, "#FFFFFF", 1.2)
        v.path(circle(x, y, 1.5), "#9AD4FF", INK, 0.6)
    elif pin == "flower":
        x, y = s.get("pin_xy", (63, 24))
        pc = s.get("pinc", "#FF8AB4")
        d = [circle(x + 2.7 * math.cos(math.radians(a)), y + 2.7 * math.sin(math.radians(a)), 2.3) for a in range(-90, 270, 72)]
        v.path(" ".join(d), lg(x - 5, y - 5, x + 5, y + 5, lighten(pc, 0.5), pc), INK, 0.6)
        v.path(circle(x, y, 1.5), "#FFE27A", INK, 0.5)


# ───────── 組み立て ─────────
def make(spec):
    c = Ctx(spec)
    v = c.v
    hk = HAIR[spec.get("hair", "crop")]
    c.s = spec = dict(spec)
    if "shade_y" not in spec:
        g = spec.get("gear")
        spec["shade_y"] = {"helm": 32.0, "hood": 33.0, "straw": 34.0, "crown": 31.0, "bandana": 31.0, "headband": 30.5}.get(g, hk[2])
    tilt = spec.get("tilt", 0.0)
    hs = spec.get("hs", 1.06)

    def head_group():
        v.begin(rotate=tilt, px=CX, py=60, sx=hs, sy=hs)

    background(c)
    draw_back(c)
    head_group()
    gear_back(c)
    hair_back(c)
    v.end()
    draw_neck(c)
    BODIES[spec.get("body", "tunic")](c)
    head_group()
    elf = spec.get("ears") == "elf"
    if not elf:
        draw_ears(c)
    draw_face(c)
    draw_marks(c)
    draw_nose(c)
    draw_eyes(c)
    draw_scar(c)
    draw_brows(c)
    draw_beard(c)
    draw_mouth(c)
    draw_mustache(c)
    draw_specs(c)
    hair_front(c)
    if elf:
        draw_ears(c)
    gear_front(c)
    v.end()
    if spec.get("gear") == "hood" and spec.get("body") == "plate":
        plate_pauldrons(c)
    return v


def S(**k):
    return k


VIOLET_STEEL = ("#FFFFFF", "#ECE6FA", "#BCB2E0", "#7C6EAC", "#463C6C")
WHITE_STEEL = ("#FFFFFF", "#F8F8FF", "#D2D6EA", "#9096B8", "#5A5E80")
RED_STEEL = ("#FFD8C8", "#E07060", "#AC3630", "#6C1A1E", "#3A0C12")
PALE_GOLD = ("#FFFFFF", "#FFF4C0", "#F8D870", "#C08A20", "#7A4E0A")

SPECS = {
    # ── 英雄 N（質素な装備・落ち着いた背景）
    "hero_tobias": S(look="m", skin="fair", face="oval", hair="tousled", hc="brown", eyes="open", ic="#7A5A32", brow="soft", mouth="smile", extra=["freckles"],
                     body="gambeson", ac="#8C7A5A", back="shield", bg="slate", motif="ring", tilt=-2),
    "hero_mira": S(look="f", skin="warm", face="heart", hair="ponytail", hc="auburn", eyes="bright", ic="#3E8A4E", brow="soft", mouth="smile", extra=["freckles"],
                   gear="headband", gc="#E8C04A", band_w=2.5, body="leather", ac="#4A7A4E", under="#EADDC0", back="quiver", fletch="#E8604A", bg="forest", motif="leaf", tilt=3),
    "hero_dunstan": S(look="m", skin="tan", face="round", hair="bald", hc="grey", beard="full", eyes="kind", ic="#6A4A2A", brow="soft", brow_t=2.3, mouth="smile", age=1,
                      nose="strong", gear="helm", body="mail", ac="#8A4E34", cloak="#6A4A30", bg="umber", motif="sun"),
    # ── 英雄 R
    "hero_aldric": S(look="m", skin="fair", face="square", hair="crop", hc="blond", eyes="open", ic="#3A6AB8", brow="firm", mouth="smile", body="plate", ornate=1,
                     cloak="#2F5AA8", bg="royal", motif="shield", sparks=2),
    "hero_seren": S(look="f", skin="pale", face="heart", hair="long", hc="silver", eyes="soft", ic="#3AA8B8", brow="soft", mouth="smile", ears="elf",
                    gear="circlet", gm=STEEL, gem="#58D0E0", body="cloak", ac="#2F7C7C", bg="teal", motif="moon", sparks=2, tilt=-3),
    "hero_brann": S(look="m", skin="tan", face="square", hair="wild", hc="red", beard="wild", eyes="stern", ic="#7A5A2A", brow="angry", brow_t=2.6, mouth="grin",
                    nose="strong", extra=["paint", "scar2"], body="fur", teeth=True, furc="#C8B89C", bg="ember", motif="flame", sparks=2, tilt=3),
    "hero_ilsa": S(look="f", skin="fair", face="oval", hair="braid", hc="black", eyes="calm", ic="#3A6AAA", brow="firm", mouth="smirk", extra=["scar2"],
                   body="leather", ac="#5A4A3A", under="#D8D0C0", cowl="#4A6A9A", bg="steel", motif="diamond", sparks=2),
    # ── 英雄 SR
    "hero_corvin": S(look="m", skin="pale", face="long", hair="curtain", hc="black", beard="goatee", eyes="sharp", eye_adj=dict(lash=0.95, wing=0.5, hu=2.0), ic="#C83A4A", brow="angry", brow_t=2.0, blush=0.05,
                     mouth="smirk", nose="long",
                     gear="hood", gc="#40306A", hood_trim="#C8D0E8", body="plate", metal=DARKM, ornate=1, trimm=STEEL, gem="#E03A4A", bg="violet", motif="moon", sparks=4),
    "hero_yselda": S(look="f", skin="fair", face="oval", hair="wavy", hc="purple", eyes="soft", ic="#A05AD8", brow="firm", mouth="smile", gear="tiara", gem="#F070C0",
                     body="plate", metal=VIOLET_STEEL, ornate=2, cloak="#6A3E9A", bg="orchid", motif="star", sparks=4),
    "hero_thorne": S(look="m", skin="tan", face="angular", hair="swept", hc="grey", beard="stubble", eyes="stern", ic="#7A9A5A", brow="angry", mouth="stern", age=1,
                     extra=["scar"], body="leather", ac="#3E4A30", under="#B8B090", cowl="#34502E", pauldron_r=BRONZE, cloak="#2A3A22", back="quiver", fletch="#D8D8C8",
                     bg="moss", motif="ring", sparks=4),
    # ── 英雄 SSR（金の縁取り・光条・きらめき）
    "hero_valeria": S(look="f", skin="warm", face="oval", hair="wavy", hc="gold", eyes="sharp", ic="#2E8AC8", brow="firm", mouth="smirk", gear="winged", gem="#E03A4A",
                      body="plate", metal=WHITE_STEEL, ornate=2, cloak="#B8303A", bg="gold", motif="sun", rays=1, sparks=6, tilt=2),
    "hero_ostrik": S(look="m", skin="tan", face="square", hair="elder", hc="white", beard="long", eyes="stern", ic="#6A9AC8", brow="firm", brow_t=2.6, mouth="stern", age=2,
                     nose="strong", gear="crown", gem="#E03A4A", body="plate", metal=GOLDM, ornate=2, trimm=PALE_GOLD, cloak="#8A1F2A", cloak_fur=True,
                     bg="crimson", motif="sun", rays=1, sparks=6),
    # ── ペルソナ（同盟の仲間）
    "kaede": S(look="f", skin="warm", face="round", hair="shortbob", hc="brown", eyes="happy", brow="soft", mouth="grin", pin="leaf", body="noble", ac="#3A62B4",
               sash="#D8404A", bg="sun", motif="burst", tilt=4),
    "marcus": S(look="m", skin="warm", face="square", hair="mohawk", hc="darkbrown", beard="stubble", eyes="stern", ic="#7A9AB8", brow="angry", brow_t=2.4, mouth="smirk",
                nose="strong", extra=["scar"], body="plate", metal=RED_STEEL, spikes=2, lames=2, bg="iron", motif="flame"),
    "ponzu": S(look="f", skin="fair", face="round", hair="curly", hc="orange", eyes="bright", ic="#7A4A2A", brow="soft", mouth="oh", extra=["freckles"],
               gear="beret", gc="#E0506A", body="blouse", bg="candy", motif="burst", tilt=-4),
    "sato": S(look="m", skin="tan", face="round", hair="sides", hc="grey", mustache="walrus", eyes="happy", brow="soft", brow_t=2.2, mouth="smile", age=1,
              gear="straw", body="farm", kerchief="#D8563A", bg="field", motif="leaf"),
    "lin": S(look="f", skin="fair", face="oval", hair="bob", hc="black", eyes="calm", ic="#5A4636", brow="flat", mouth="neutral", extra=["glasses"],
             body="robe", ac="#2E6A7A", trim="#C4E0E8", pendant="#58D0E0", bg="aqua", motif="gear"),
    "johnny": S(look="m", skin="warm", face="oval", hair="pompadour", hc="blond", eyes="open", brow="arched", mouth="grin", extra=["shades", "earring"],
                body="jacket", bg="flash", motif="burst", sparks=4, tilt=5),
    "yuki": S(look="f", skin="pale", face="heart", hair="hime", hc="iceblue", eyes="soft", ic="#5A8AC8", brow="sad", mouth="smile", pin="snow",
              body="scarf", bg="ice", motif="snow", tilt=-3),
    "gen": S(look="m", skin="tan", face="long", hair="bald", hc="grey", beard="sage", eyes="stern", ic="#4A3A2A", brow="firm", brow_t=2.6, mouth="stern", age=2,
             extra=["scar"], gear="headband", gc="#8A2A2A", body="fur", furc="#8A7A68", ac="#4A4038", bg="olive", motif="sun"),
}


# ───────── 名前だけのメンバーとアバター（パーツの組み合わせ） ─────────
def pick(seed, key, options):
    h = zlib.crc32(("%s:%s" % (seed, key)).encode())
    return options[h % len(options)]


G_SKIN = ["pale", "fair", "fair", "warm", "warm", "tan", "tan", "brown", "deep"]
G_HC_NAT = ["black", "darkbrown", "brown", "brown", "auburn", "red", "blond", "blond", "grey", "orange"]
G_HC_FAN = ["silver", "teal", "purple", "pink", "iceblue", "white", "gold"]
G_IRIS = ["#6A4A2A", "#4A3424", "#3A6AB8", "#3E8A4E", "#7A8A9A", "#8A5A2A", "#3AA8B8", "#8A5AC8", "#B86A2A"]
G_CLOTH = ["#4E6E9A", "#7A5A3A", "#3E6E4E", "#8E3E3E", "#50508A", "#9A8648", "#5E5E6A", "#3A6E7E", "#7E4270", "#B8683A", "#2E5A5A", "#6A7A3A"]
G_BG = ["slate", "forest", "umber", "royal", "teal", "ember", "steel", "violet", "orchid", "moss", "sun", "aqua", "rose", "sky", "plum", "sand", "olive", "field"]
G_MOTIF = ["ring", "ring", "sun", "star", "diamond", "shield", "leaf", "moon"]
G_METAL = [STEEL, STEEL, IRON, BRONZE, DARKM]
G_GEARC = ["#8A2A2A", "#2E5A8A", "#3E3458", "#4A6A3A", "#B8862A", "#6A3A6A", "#5A4A3A"]
G = {
    "m": dict(
        face=["oval", "square", "round", "long", "angular", "square"],
        hair=["crop", "tousled", "swept", "crop", "tousled", "mohawk", "bald", "pompadour", "swept", "wild"],
        beard=[None, None, None, "stubble", "full", "goatee", "stubble"],
        mustache=[None, None, None, None, "walrus", "curl"],
        eyes=["open", "open", "stern", "kind", "stern", "happy"],
        brow=["soft", "flat", "firm", "angry", "firm"],
        mouth=["smile", "neutral", "smirk", "grin", "stern", "smile"],
        body=["tunic", "gambeson", "leather", "mail", "plate", "fur", "noble", "cloak", "farm", "scarf", "plate", "leather"],
        gear=[None, None, None, None, None, "helm", "headband", "bandana", "hood", "cap"],
        extra=[[], [], [], [], ["freckles"], ["scar"], ["scar2"], ["glasses"], ["earring"], [], ["scar2"], ["eyepatch"]],
        pin=[None],
    ),
    "f": dict(
        face=["oval", "heart", "round", "heart", "oval"],
        hair=["long", "wavy", "ponytail", "braid", "bob", "hime", "bun", "shortbob", "curly", "wavy"],
        beard=[None], mustache=[None],
        eyes=["soft", "bright", "sharp", "calm", "soft", "happy"],
        brow=["soft", "soft", "arched", "flat", "firm"],
        mouth=["smile", "smile", "neutral", "smirk", "grin", "smile"],
        body=["tunic", "leather", "cloak", "robe", "noble", "blouse", "scarf", "plate", "gambeson", "cloak", "robe"],
        gear=[None, None, None, None, None, "circlet", "tiara", "headband", "hood", "beret", "cap"],
        extra=[[], [], [], ["freckles"], ["glasses"], ["earring"], ["mole"]],
        pin=[None, None, None, "flower", "leaf"],
    ),
    "n": dict(
        face=["oval", "heart", "round", "oval"],
        hair=["shortbob", "tousled", "bob", "crop", "curly", "swept", "tousled", "shortbob"],
        beard=[None], mustache=[None],
        eyes=["open", "calm", "bright", "open", "happy", "kind"],
        brow=["soft", "flat", "firm", "soft"],
        mouth=["smile", "neutral", "smirk", "smile", "grin"],
        body=["tunic", "leather", "cloak", "noble", "scarf", "gambeson", "robe", "mail"],
        gear=[None, None, None, None, "cap", "beret", "headband", "hood", "bandana"],
        extra=[[], [], [], ["freckles"], ["glasses"], ["earring"]],
        pin=[None],
    ),
}
BIG_HAIR = ("mohawk", "pompadour", "wild", "bun", "curly", "ponytail")


def generic_spec(seed, look="n"):
    g = G[look]

    def pk(key, options=None):
        return pick(seed, key, options if options is not None else g[key])

    hc = pk("hc", G_HC_NAT if (look == "m" or zlib.crc32(("%s:fan" % seed).encode()) % 4) else G_HC_FAN)
    sp = S(look=look, skin=pk("skin", G_SKIN), face=pk("face"), hair=pk("hair"), hc=hc, beard=pk("beard"), mustache=pk("mustache"), eyes=pk("eyes"),
           ic=pk("ic", G_IRIS), brow=pk("brow"), mouth=pk("mouth"), body=pk("body"), ac=pk("ac", G_CLOTH), gear=pk("gear"), gc=pk("gc", G_GEARC),
           extra=list(pk("extra")), pin=pk("pin"), bg=pk("bg", G_BG), motif=pk("motif", G_MOTIF), metal=pk("metal", G_METAL), tilt=pk("tilt", [-4, -2, 0, 0, 2, 3, 4]),
           sc=pk("sc", ["#E8EEF8", "#D85A5A", "#E8C03A", "#5AB87A", "#5A8AD8"]), cowl=pk("cowl", [None, None, "#4A6A9A", "#7A3A3A", "#3E5E3A"]),
           trim=pk("trim", [GOLD, GOLD, "#D8DCE8", "#E8A04A"]), kerchief=pk("ker", [None, "#D8563A", "#3A6AB8"]))
    if look == "n":
        sp.update(lips=False, blush=0.3, brow_t=1.5, nw=6.2, sw=33.0)
    if sp["beard"] and sp["mustache"]:
        sp["mustache"] = None
    if sp["hair"] == "bald" and not sp["beard"]:
        sp["beard"] = pk("bbeard", ["stubble", "full", "goatee"])
    if sp["hc"] in ("grey", "white", "silver") and look == "m":
        sp["age"] = 1
    gear = sp["gear"]
    if gear == "hood":
        if look == "f":
            sp["hair"] = "curtain"
        elif sp["hair"] not in ("crop", "tousled", "swept", "bald"):
            sp["hair"] = "crop"
        sp["pin"] = None
    elif gear == "helm":
        sp["hair"] = "bald" if sp["hair"] in ("bald", "mohawk") else "sides"
    elif gear in ("beret", "cap", "bandana") and sp["hair"] in BIG_HAIR:
        sp["gear"] = None
    elif gear in ("circlet", "tiara", "headband") and sp["hair"] in ("mohawk", "pompadour"):
        sp["gear"] = None
    if sp["gear"] in ("beret", "cap", "tiara", "hood"):
        sp["pin"] = None
    if sp["body"] == "plate":
        sp["ornate"] = pk("orn", [0, 0, 1])
        sp["cloak"] = pk("cloakc", [None, "#2F5AA8", "#8A2A2A", "#3E6E4E"])
    if sp["body"] == "fur":
        sp["furc"] = pk("furc", ["#D8CDB8", "#8A7A68", "#B8A07A"])
    if "glasses" in sp["extra"] and sp["eyes"] == "happy":
        sp["eyes"] = "open" if look != "f" else "soft"
    if "eyepatch" in sp["extra"] and sp["eyes"] == "happy":
        sp["eyes"] = "stern"
    if sp["mouth"] == "grin" and sp["brow"] == "angry":
        sp["brow"] = "firm"
    return sp


# 名前だけの同盟メンバー（personas.json の fillerMembers.look）ごとの枚数と、アバター 12 の配分（m 5 / f 5 / n 2）
GENERIC_LOOKS = {"m": 8, "f": 8, "n": 6}
AVATAR_LOOKS = ["m"] * 5 + ["f"] * 5 + ["n"] * 2
AVATAR_COUNT = len(AVATAR_LOOKS)


def generic_items():
    return [("gen_%s_%02d" % (lk, i), generic_spec("gen%s%d" % (lk, i), lk)) for lk, n in GENERIC_LOOKS.items() for i in range(n)]


# プレイヤーのアバター 12: 職業の型（騎士・狩人・術士…）ごとに 1 体ずつ手で決める（選んでもらう顔なので偏りをなくす）
AVATARS = [
    # m: 騎士 / 狩人 / 術士 / 蛮族 / 貴族
    S(look="m", skin="fair", face="angular", hair="crop", hc="brown", eyes="open", ic="#3A6AB8", brow="firm", mouth="smile", body="plate", ornate=1, cloak="#2F5AA8",
      bg="royal", motif="shield", sparks=2),
    S(look="m", skin="tan", face="oval", hair="tousled", hc="darkbrown", beard="stubble", eyes="stern", ic="#3E8A4E", brow="firm", mouth="smirk", body="leather", ac="#5A4A32",
      under="#C8BC9C", cowl="#3E6A3E", back="quiver", fletch="#E8E4D8", bg="forest", motif="leaf", tilt=-3),
    S(look="m", skin="deep", face="long", hair="swept", hc="black", beard="goatee", eyes="open", ic="#B86A2A", brow="arched", mouth="smile", body="robe", ac="#5A3A8A",
      pendant="#58D0E0", bg="violet", motif="star", sparks=2, tilt=2),
    S(look="m", skin="warm", face="square", hair="wild", hc="auburn", beard="full", eyes="stern", ic="#7A5A2A", brow="angry", brow_t=2.4, mouth="grin", nose="strong",
      extra=["scar2"], body="fur", furc="#B8A07A", teeth=True, bg="ember", motif="flame", tilt=3),
    S(look="m", skin="pale", face="oval", hair="pompadour", hc="silver", mustache="curl", eyes="kind", ic="#7A8A9A", brow="arched", mouth="smirk", body="noble", ac="#8E3040",
      bg="sand", motif="diamond", tilt=-2),
    # f: 騎士 / 術士 / 狩人 / 姫 / 冒険者
    S(look="f", skin="warm", face="oval", hair="ponytail", hc="red", eyes="sharp", ic="#3E8A4E", brow="firm", mouth="smirk", body="plate", ornate=1, cloak="#8A2A2A",
      bg="rose", motif="shield", sparks=2, tilt=2),
    S(look="f", skin="pale", face="heart", hair="long", hc="purple", eyes="calm", ic="#8A5AC8", brow="soft", mouth="smile", gear="circlet", gem="#C87AF0", body="robe",
      ac="#3A3A7A", trim="#D8DCE8", pendant="#C87AF0", bg="orchid", motif="moon", sparks=2, tilt=-3),
    S(look="f", skin="brown", face="heart", hair="braid", hc="black", eyes="bright", ic="#B86A2A", brow="soft", mouth="smile", extra=["earring"], body="leather", ac="#3E6E4E",
      under="#EADDC0", cowl="#B8683A", bg="teal", motif="leaf", tilt=3),
    S(look="f", skin="fair", face="oval", hair="wavy", hc="blond", eyes="soft", ic="#3A6AB8", brow="soft", mouth="smile", gear="tiara", gem="#58B0F0", body="cloak", ac="#3A62B4",
      under="#F4EEE0", clasp="#F2C340", bg="sky", motif="star", sparks=2),
    S(look="f", skin="tan", face="round", hair="shortbob", hc="teal", eyes="bright", ic="#3AA8B8", brow="arched", mouth="grin", extra=["freckles"], pin="flower", body="scarf",
      ac="#6A3A6A", sc="#F0C84A", bg="plum", motif="burst", tilt=-4),
    # n: 従者 / 学者
    S(look="n", skin="fair", face="oval", hair="tousled", hc="blond", eyes="open", ic="#3E8A4E", brow="soft", mouth="smile", extra=["freckles"], body="gambeson", ac="#6A7A8A",
      lips=False, blush=0.3, brow_t=1.5, nw=6.2, sw=33.0, bg="slate", motif="ring", tilt=2),
    S(look="n", skin="warm", face="heart", hair="bob", hc="darkbrown", eyes="calm", ic="#4A3424", brow="flat", mouth="smile", extra=["glasses"], body="cloak", ac="#2E6A7A",
      lips=False, blush=0.3, brow_t=1.5, nw=6.2, sw=33.0, bg="aqua", motif="gear", tilt=-2),
]
assert [a["look"] for a in AVATARS] == AVATAR_LOOKS


def avatar_items():
    return [("avatar_%02d" % i, sp) for i, sp in enumerate(AVATARS)]


def all_portraits():
    out = [("pt_" + k, make(v)) for k, v in SPECS.items()]
    out += [("pt_" + k, make(v)) for k, v in generic_items() + avatar_items()]
    return out


def main():
    args = sys.argv[1:]
    html = None
    if "--html" in args:
        i = args.index("--html")
        html = args[i + 1]
        del args[i:i + 2]
    out = pathlib.Path(args[0]) if args else pathlib.Path(__file__).resolve().parents[2] / "app/src/main/res/drawable"
    out.mkdir(parents=True, exist_ok=True)
    items = all_portraits()
    for name, v in items:
        (out / (name + ".xml")).write_text(v.to_xml(compact=True), encoding="utf-8")
    print("wrote", len(items), "portraits to", out)
    if html:
        cells = "".join('<figure style="margin:6px;display:inline-block;color:#cfd6ee;font:11px sans-serif">%s<figcaption>%s</figcaption></figure>'
                        % (v.to_svg(160, uid="p%d_" % i), name) for i, (name, v) in enumerate(items))
        pathlib.Path(html).write_text('<html><body style="background:#101a36">%s</body></html>' % cells, encoding="utf-8")
        print("wrote", html)


if __name__ == "__main__":
    main()
