"""アイソメトリック風の建物スプライトを組み立てる小さな作図ライブラリ（標準ライブラリのみ）。

座標系: グリッド (x, y, z)。x は右下、y は左下、z は上へ。画面への射影は
  sx = CX + (x - y) * U
  sy = CY + (x + y - S) * V - z
で、U:V = 2:1（地面のひし形が 2:1）。スプライトは 128 x 160 の viewport で、地面の中心が (CX, CY) = (64, 120)。
Scene に図形（多角形・折れ線）を描画順に積み、VectorDrawable の XML にする（出力は決定論的）。

光は左上から当たる（左前面 y = y1 が明るく、右前面 x = x1 が影）。図形の塗りは単色のほか
線形・放射グラデーション（Linear / Radial）を使える。Scene(outline=色) にすると、立体の外形を
1 本の太い縁取り（下敷き）にまとめて出す。Scene(cast=True) にすると、立体が地面へ落とす影を
1 本のパスにまとめて出す。確認用に to_svg() で同じ絵の SVG も出せる。
"""

import colorsys
import math

W_VP, H_VP = 128, 160
CX, CY = 64.0, 120.0
U, V = 14.0, 7.0
S = 4.0  # 4 x 4 グリッドの足元（中心が S）

# 落ち影: グリッド上で (SUN_DX, SUN_DY) の向きへ、高さ 1 あたり SUN_K だけ伸びる（画面では右下）。
SUN_DX, SUN_DY, SUN_K = 1.0, -0.3, 0.026
LIGHT_TINT = "#FFF0C4"   # 光の当たる面に混ぜる暖色
SHADOW_TINT = "#2A2454"  # 影の面に混ぜる寒色
SHADOW_INK = "#1B1430"   # 地面の影の色


def P(x, y, z=0.0):
    return (CX + (x - y) * U, CY + (x + y - S) * V - z)


def hex_to_rgb(h):
    h = h.lstrip("#")
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def rgb_to_hex(r, g, b):
    return "#%02X%02X%02X" % (max(0, min(255, round(r))), max(0, min(255, round(g))), max(0, min(255, round(b))))


def shade(c, f):
    """明るさを f 倍にする（f>1 は明るく、f<1 は暗く）。"""
    r, g, b = hex_to_rgb(c)
    if f >= 1:
        t = f - 1
        return rgb_to_hex(r + (255 - r) * t, g + (255 - g) * t, b + (255 - b) * t)
    return rgb_to_hex(r * f, g * f, b * f)


def mix(a, b, t):
    ra, ga, ba = hex_to_rgb(a)
    rb, gb, bb = hex_to_rgb(b)
    return rgb_to_hex(ra + (rb - ra) * t, ga + (gb - ga) * t, ba + (bb - ba) * t)


def lit(c, f=1.06, t=0.10):
    """光の当たる面の色（少し明るく、暖色へ寄せる）。"""
    return mix(shade(c, f), LIGHT_TINT, t)


def shd(c, f=0.72, t=0.16):
    """影の面の色（暗く、寒色へ寄せる）。鮮やかな色は濁らせない（黄〜橙は茶へ寄せ、寒色の混ぜ込みを弱める）。"""
    if f >= 1.0:
        return mix(shade(c, f), SHADOW_TINT, t)
    r, g, b = hex_to_rgb(c)
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    if s > 0.2:
        if 0.06 < h < 0.2:
            h = max(0.05, h - 0.03 * min(1.0, (s - 0.2) / 0.3))
        s = min(1.0, s * 1.1)
    r, g, b = colorsys.hsv_to_rgb(h, s, v * f)
    k = t * (1.0 - 0.6 * min(1.0, s / 0.5))
    return mix(rgb_to_hex(r * 255, g * 255, b * 255), SHADOW_TINT, k)


def edge(c, f=0.46):
    """内側の細い縁取りの色。"""
    return mix(shade(c, f), SHADOW_TINT, 0.25)


def sat(c, f):
    """彩度を f 倍にする。"""
    r, g, b = hex_to_rgb(c)
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    r, g, b = colorsys.hsv_to_rgb(h, max(0.0, min(1.0, s * f)), v)
    return rgb_to_hex(r * 255, g * 255, b * 255)


def with_alpha(c, a):
    """'#RRGGBB' と不透明度から '#AARRGGBB'。"""
    return "#%02X%s" % (max(0, min(255, round(a * 255))), c.lstrip("#").upper()[-6:])


# ---- グラデーション ---------------------------------------------------------------

def _norm(stops):
    if stops and not isinstance(stops[0], (tuple, list)):
        n = len(stops)
        return [(i / (n - 1.0), c) for i, c in enumerate(stops)]
    return [(float(o), c) for o, c in stops]


class Linear:
    """線形グラデーション（viewport 座標）。stops は色の並び、または [(offset, 色), ...]。色は '#RRGGBB' か '#AARRGGBB'。"""

    def __init__(self, x0, y0, x1, y1, stops):
        self.x0, self.y0, self.x1, self.y1, self.stops = float(x0), float(y0), float(x1), float(y1), _norm(stops)


class Radial:
    def __init__(self, cx, cy, r, stops):
        self.cx, self.cy, self.r, self.stops = float(cx), float(cy), float(r), _norm(stops)


def vgrad(y0, y1, *colors):
    """上から下への線形グラデーション。"""
    return Linear(0, y0, 0, y1, list(colors))


def hgrad(x0, x1, *colors):
    return Linear(x0, 0, x1, 0, list(colors))


def grad_perp(a0, a1, b, stops):
    """直線 a0-a1 を offset 0、点 b を offset 1 とし、等色線が a0-a1 に平行な線形グラデーション。
    斜めの面（壁・屋根）で、上の辺から下の辺へ色を流すのに使う。"""
    ex, ey = a1[0] - a0[0], a1[1] - a0[1]
    ln = math.hypot(ex, ey) or 1.0
    nx, ny = -ey / ln, ex / ln
    d = (b[0] - a0[0]) * nx + (b[1] - a0[1]) * ny
    return Linear(a0[0], a0[1], a0[0] + nx * d, a0[1] + ny * d, stops)


def is_grad(p):
    return hasattr(p, "stops")


def flat_of(p):
    """塗りの代表色（グラデーションなら stops の平均）。単色しか扱えない確認ツール用。"""
    if p is None or isinstance(p, str):
        return p
    rs = gs = bs = 0.0
    for _, c in p.stops:
        r, g, b = hex_to_rgb(c[-6:])
        rs, gs, bs = rs + r, gs + g, bs + b
    n = float(len(p.stops))
    return rgb_to_hex(rs / n, gs / n, bs / n)


def hull(pts):
    """凸包（単調鎖法）。"""
    ps = sorted(set((round(x, 3), round(y, 3)) for x, y in pts))
    if len(ps) < 3:
        return ps

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lo = []
    for p in ps:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    up = []
    for p in reversed(ps):
        while len(up) >= 2 and cross(up[-2], up[-1], p) <= 0:
            up.pop()
        up.append(p)
    return lo[:-1] + up[:-1]


def oriented(pts):
    """多角形の向きをそろえる（1 本のパスに重ねても nonzero 塗りで穴があかないように）。"""
    a = 0.0
    for i in range(len(pts)):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % len(pts)]
        a += x0 * y1 - x1 * y0
    return list(reversed(pts)) if a < 0 else pts


def rows(a0, a1, b0, b1, n, lo=0.0, hi=1.0):
    """辺 a0-a1 と辺 b0-b1 のあいだを n 等分する線分の並び（瓦の段・石の目地・板目）。両端は含めない。"""
    out = []
    for i in range(1, n + 1):
        t = lo + (hi - lo) * i / (n + 1.0)
        out.append([(a0[0] + (b0[0] - a0[0]) * t, a0[1] + (b0[1] - a0[1]) * t),
                    (a1[0] + (b1[0] - a1[0]) * t, a1[1] + (b1[1] - a1[1]) * t)])
    return out


class Shape:
    """1 本のパス。pts が最初の部分パス、subs が残りの部分パス [(pts, closed), ...]。
    fill は色文字列か Linear / Radial。d を与えると pathData をそのまま使う（円弧など）。
    xf = (pivotX, pivotY, scaleX, scaleY) があると group で包む。kind は Scene 内部の予約（縁取り・落ち影の差し込み位置）。"""

    __slots__ = ("pts", "fill", "stroke", "sw", "alpha", "closed", "subs", "d", "salpha", "xf", "kind", "cap")

    def __init__(self, pts, fill, stroke, sw, alpha, closed=True, subs=None, d=None, salpha=1.0, xf=None, kind=None,
                 cap=None):
        self.pts, self.fill, self.stroke, self.sw, self.alpha, self.closed = pts, fill, stroke, sw, alpha, closed
        self.subs, self.d, self.salpha, self.xf, self.kind, self.cap = subs, d, salpha, xf, kind, cap


def set_anchor(cx, cy):
    """スプライトの原点（足元の中心）を viewport 内のどこに置くか。"""
    global CX, CY
    CX, CY = float(cx), float(cy)


def _fl(pts):
    return [(float(x), float(y)) for x, y in pts]


class Scene:
    def __init__(self, w=W_VP, h=H_VP, outline=None, ow=1.1, cast=False, compact=False):
        """outline: 外形の縁取りの色（None なら縁取りなし）。ow: 縁取りの太さ（外へ出る幅）。
        cast: 立体が落ち影を登録するか。compact: XML を詰めて書く（小数の .0 を省くなど）。"""
        self.shapes = []
        self.w, self.h = w, h
        self.outline, self.ow, self.casting, self.compact = outline, ow, cast, compact
        self._layers = []     # 縁取りの層ごとの外形（多角形の並び）
        self._cur = None      # いまの層の番号
        self._casts = []      # 落ち影（画面座標の凸多角形）
        self._cast_at = False

    # ---- 基本図形 -------------------------------------------------------------
    def poly(self, pts, fill, stroke=None, sw=0.6, alpha=1.0, sil=False, salpha=1.0):
        pts = _fl(pts)
        if sil:
            self.sil(pts)
        self.shapes.append(Shape(pts, fill, stroke, sw, alpha, salpha=salpha))

    def line(self, pts, stroke, sw=1.0, alpha=1.0, cap=None):
        self.shapes.append(Shape(_fl(pts), None, stroke, sw, alpha, closed=False, cap=cap))

    def lines(self, segs, stroke, sw=1.0, alpha=1.0, cap=None):
        """複数の折れ線を 1 本のパスにまとめる（同じ色・太さの細部をまとめてパス数を抑える）。"""
        segs = [_fl(s) for s in segs if len(s) >= 2]
        if not segs:
            return
        self.shapes.append(Shape(segs[0], None, stroke, sw, alpha, closed=False,
                                 subs=[(s, False) for s in segs[1:]], cap=cap))

    def polys(self, polys, fill, stroke=None, sw=0.5, alpha=1.0, sil=False, salpha=1.0):
        """複数の多角形を 1 本のパスにまとめる。"""
        polys = [oriented(_fl(p)) for p in polys if len(p) >= 3]
        if not polys:
            return
        if sil:
            for p in polys:
                self.sil(p)
        self.shapes.append(Shape(polys[0], fill, stroke, sw, alpha, subs=[(p, True) for p in polys[1:]], salpha=salpha))

    def ellipse(self, cx, cy, rx, ry, fill, stroke=None, sw=0.6, alpha=1.0, n=20, sil=False):
        pts = [(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]
        self.poly(pts, fill, stroke, sw, alpha, sil=sil)

    def oval(self, cx, cy, rx, ry, fill, stroke=None, sw=0.5, alpha=1.0, sil=False, salpha=1.0, xf=None):
        """円弧で書く楕円（ellipse より XML が短い）。"""
        pts = [(cx + rx * math.cos(2 * math.pi * i / 16), cy + ry * math.sin(2 * math.pi * i / 16)) for i in range(16)]
        if sil:
            self.sil(pts)
        d = "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0Z" % (_f(cx - rx), _f(cy), _f(rx), _f(ry), _f(2 * rx), _f(rx),
                                                         _f(ry), _f(-2 * rx))
        self.shapes.append(Shape(pts, fill, stroke, sw, alpha, d=d, salpha=salpha, xf=xf))

    def ovals(self, items, fill, stroke=None, sw=0.5, alpha=1.0, sil=False):
        """複数の楕円 [(cx, cy, rx, ry), ...] を 1 本のパスにまとめる。"""
        if not items:
            return
        d, first = "", None
        for cx, cy, rx, ry in items:
            pts = [(cx + rx * math.cos(2 * math.pi * i / 12), cy + ry * math.sin(2 * math.pi * i / 12)) for i in range(12)]
            if first is None:
                first = pts
            if sil:
                self.sil(pts)
            d += "M%s,%sa%s,%s 0 1,0 %s,0a%s,%s 0 1,0 %s,0Z" % (_f(cx - rx), _f(cy), _f(rx), _f(ry), _f(2 * rx), _f(rx),
                                                              _f(ry), _f(-2 * rx))
        self.shapes.append(Shape(first, fill, stroke, sw, alpha, d=d))

    def glow(self, cx, cy, rx, ry, c, alpha=0.5, mid=None):
        """中心が濃く外へ消える楕円（やわらかい影・あかりのにじみ）。mid=(offset, 率) で途中の濃さを指定。"""
        stops = [(0.0, with_alpha(c, alpha))]
        if mid:
            stops.append((mid[0], with_alpha(c, alpha * mid[1])))
        stops.append((1.0, with_alpha(c, 0.0)))
        xf = None if abs(rx - ry) < 0.05 else (cx, cy, 1.0, ry / float(rx))
        self.oval(cx, cy, rx, rx, Radial(cx, cy, rx, stops), xf=xf)

    # ---- 縁取り・落ち影 ---------------------------------------------------------
    def sil(self, pts):
        """外形（太い縁取りの下敷き）に多角形を加える。いまの層の最初の登録位置に下敷きが入る。"""
        if self._cur is None:
            self._layers.append([])
            self._cur = len(self._layers) - 1
            self.shapes.append(Shape([], None, None, 0, 1.0, kind=("outline", self._cur)))
        self._layers[self._cur].append(oriented(_fl(pts)))

    def layer(self):
        """縁取りの層を切る。これ以降の立体は、手前のものとして改めて太い縁取りを持つ。"""
        self._cur = None

    def cast_here(self):
        """落ち影のパスをここ（描画順）に差し込む。呼ばなければ最初の登録位置に入る。"""
        if not self._cast_at:
            self._cast_at = True
            self.shapes.append(Shape([], None, None, 0, 1.0, kind=("cast", 0)))

    def cast(self, pts3):
        """3 次元の点の集まりが地面へ落とす影（凸包）を登録する。"""
        if not self.casting:
            return
        self.cast_here()
        g = [P(x + z * SUN_K * SUN_DX, y + z * SUN_K * SUN_DY, 0) for x, y, z in pts3]
        h = hull(g)
        if len(h) >= 3:
            self._casts.append(oriented(h))

    def cast_box(self, x0, y0, x1, y1, z0, z1):
        self.cast([(x, y, z) for z in (z0, z1) for x, y in ((x0, y0), (x1, y0), (x1, y1), (x0, y1))])

    def cast_round(self, xc, yc, r, z0, z1, apex=None):
        pts = []
        for i in range(10):
            a = 2 * math.pi * i / 10
            pts.append((xc + r * math.cos(a), yc + r * math.sin(a), z0))
            pts.append((xc + r * math.cos(a), yc + r * math.sin(a), z1))
        if apex is not None:
            pts.append((xc, yc, apex))
        self.cast(pts)

    # ---- 箱・屋根 -------------------------------------------------------------
    def box(self, x0, y0, x1, y1, z0, z1, c, top=None, outline=True, lf=1.0, rf=0.72, tf=1.15, grad=None, cast=True,
            sil=True, eave=0.0, faces="rlt"):
        """直方体。見えるのは上面・左前面（y = y1）・右前面（x = x1）。
        grad: 壁を上から下へ暗くするか（None なら高さで決める。"r" / "l" で片面だけ）。
        eave: 壁の上端に落ちる軒の影の高さ。faces: 描く面（r = 右前、l = 左前、t = 上）。"""
        st = edge(c) if outline else None
        hh = float(z1 - z0)
        if grad is None:
            grad = hh >= 8.0
        if grad is True:
            grad = "rl"
        grad = grad or ""
        if sil:
            self.sil([P(x0, y1, z0), P(x1, y1, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1), P(x0, y1, z1)])
        if cast:
            self.cast_box(x0, y0, x1, y1, z0, z1)
        cr, cl = shd(c, rf), shade(c, lf)
        rt, rb = shade(cr, 1.08), shade(cr, 0.80)
        lt, lb = lit(cl), mix(shade(cl, 0.84), SHADOW_TINT, 0.06)
        fr, fl = cr, cl
        e = min(0.6, eave / hh) if hh > 0 else 0.0
        if e > 0:
            sr = [(0.0, shade(cr, 0.62)), (e, shade(cr, 0.70)), (e, rt), (1.0, rb)]
            sl = [(0.0, shd(cl, 0.62)), (e, shd(cl, 0.70)), (e, lt), (1.0, lb)]
        else:
            sr, sl = [rt, rb], [lt, lb]
        if "r" in grad:
            fr = grad_perp(P(x1, y1, z1), P(x1, y0, z1), P(x1, y1, z0), sr)
        if "l" in grad:
            fl = grad_perp(P(x0, y1, z1), P(x1, y1, z1), P(x0, y1, z0), sl)
        if "r" in faces:
            self.poly([P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1), P(x1, y0, z1)], fr, st, 0.45)
        if "l" in faces:
            self.poly([P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)], fl, st, 0.45)
        if "t" in faces:
            self.poly([P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], top or lit(c, tf), st, 0.45)

    def gable(self, x0, y0, x1, y1, z, h, c, axis="x", over=0.25, gable_c=None, cast=True, sil=True, tiles=0,
              thick=1.6, ridge=None, wall=None, rw=0.9):
        """切妻屋根。axis='x' は棟が x 方向。軒を over だけ出す。
        tiles: 屋根面に入れる段の数。thick: 軒先の厚み。ridge: 棟の色（None なら屋根の明るい色）。rw: 棟の線の太さ。
        wall: 妻壁（三角の壁）の色。与えると、妻の三角を軒裏の影にして、その内側に壁を描く。"""
        x0, y0, x1, y1 = x0 - over, y0 - over, x1 + over, y1 + over
        st = edge(c, 0.42)
        gc = gable_c or (shd(c, 0.42) if wall else shd(c, 0.6))
        cl, cd = c, shd(c, 0.74)
        if axis == "x":
            ym = (y0 + y1) / 2.0
            r0, r1 = P(x0, ym, z + h), P(x1, ym, z + h)       # 棟
            e0, e1 = P(x0, y1, z), P(x1, y1, z)               # 手前（左前）の軒
            b0, b1 = P(x0, y0, z), P(x1, y0, z)               # 奥の軒
            if sil:
                self.sil([e0, (e0[0], e0[1] + thick), (e1[0], e1[1] + thick), b1, r1, r0])
                if b0[1] < r0[1]:
                    self.sil([b0, b1, r1, r0])
            if cast:
                self.cast([(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z), (x0, ym, z + h), (x1, ym, z + h)])
            if b0[1] < r0[1]:  # 奥の面が見えるのは、棟より軒が上に来る浅い屋根だけ
                self.poly([b0, b1, r1, r0], shd(c, 0.62), st, 0.45)
            self.poly([b1, e1, r1], gc, st, 0.45)
            if wall:
                cw = shd(wall, 0.72)
                wp = [P(x1, y0 + over + 0.1, z), P(x1, y1, z), P(x1, ym + 0.06, z + h - 2.6)]
                self.poly(wp, vgrad(wp[2][1], wp[1][1], shade(cw, 0.8), shade(cw, 1.04)), None, 0)
            self.poly([e0, e1, r1, r0], grad_perp(r0, r1, e0, [lit(cl, 1.12), shade(cl, 0.84)]), st, 0.45)
            if tiles:
                self.lines(rows(r0, r1, e0, e1, tiles), shade(cl, 0.62), 0.45, 0.55)
            if thick > 0:
                self.poly([e0, e1, (e1[0], e1[1] + thick), (e0[0], e0[1] + thick)], shade(cl, 0.58), st, 0.4)
                self.line([e1, (e1[0], e1[1] + thick * 0.5), (r1[0], r1[1] + thick * 0.5)], shd(c, 0.45), 0.9)
            self.line([r0, r1], ridge or lit(cl, 1.3, 0.2), rw)
        else:
            xm = (x0 + x1) / 2.0
            r0, r1 = P(xm, y0, z + h), P(xm, y1, z + h)
            e0, e1 = P(x1, y0, z), P(x1, y1, z)               # 手前（右前）の軒
            b0, b1 = P(x0, y0, z), P(x0, y1, z)
            if sil:
                self.sil([e1, (e1[0], e1[1] + thick), (e0[0], e0[1] + thick), e0, r0, r1, b1])
                if b0[1] < r0[1]:
                    self.sil([b0, b1, r1, r0])
            if cast:
                self.cast([(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z), (xm, y0, z + h), (xm, y1, z + h)])
            if b0[1] < r0[1]:
                self.poly([b0, b1, r1, r0], shade(cl, 0.9), st, 0.45)
            self.poly([b1, e1, r1], gable_c or (shd(c, 0.5) if wall else shade(cl, 0.82)), st, 0.45)
            if wall:
                wp = [P(x0 + over + 0.1, y1, z), P(x1, y1, z), P(xm + 0.06, y1, z + h - 2.6)]
                self.poly(wp, vgrad(wp[2][1], wp[1][1], shade(wall, 0.78), lit(wall, 1.04)), None, 0)
            self.poly([e0, e1, r1, r0], grad_perp(r0, r1, e0, [shade(cd, 1.12), shade(cd, 0.78)]), st, 0.45)
            if tiles:
                self.lines(rows(r0, r1, e0, e1, tiles), shade(cd, 0.6), 0.45, 0.6)
            if thick > 0:
                self.poly([e0, e1, (e1[0], e1[1] + thick), (e0[0], e0[1] + thick)], shade(cd, 0.6), st, 0.4)
                self.line([b1, r1, e1], shade(cl, 0.55), 0.9)
            self.line([r0, r1], ridge or lit(cl, 1.2, 0.15), rw)

    def pyramid(self, x0, y0, x1, y1, z, h, c, over=0.2, cast=True, sil=True, tiles=0, thick=1.4):
        x0, y0, x1, y1 = x0 - over, y0 - over, x1 + over, y1 + over
        xm, ym = (x0 + x1) / 2.0, (y0 + y1) / 2.0
        st = edge(c, 0.42)
        a = P(xm, ym, z + h)
        pl, pm, pr = P(x0, y1, z), P(x1, y1, z), P(x1, y0, z)
        cd = shd(c, 0.74)
        if sil:
            self.sil([pl, (pl[0], pl[1] + thick), (pm[0], pm[1] + thick), (pr[0], pr[1] + thick), pr, a])
        if cast:
            self.cast([(x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z), (xm, ym, z + h)])
        self.poly([pr, pm, a], grad_perp(a, (a[0] + pm[0] - pr[0], a[1] + pm[1] - pr[1]), pm,
                                         [shade(cd, 1.15), shade(cd, 0.78)]), st, 0.45)
        self.poly([pl, pm, a], grad_perp(a, (a[0] + pm[0] - pl[0], a[1] + pm[1] - pl[1]), pm,
                                         [lit(c, 1.14), shade(c, 0.84)]), st, 0.45)
        if tiles:
            sl, sr = [], []
            for i in range(1, tiles + 1):
                t = i / (tiles + 1.0)
                q = lambda p: (a[0] + (p[0] - a[0]) * t, a[1] + (p[1] - a[1]) * t)  # noqa: E731
                sl.append([q(pl), q(pm)])
                sr.append([q(pm), q(pr)])
            self.lines(sl, shade(c, 0.62), 0.45, 0.55)
            self.lines(sr, shade(cd, 0.6), 0.45, 0.6)
        if thick > 0:
            self.poly([pl, pm, (pm[0], pm[1] + thick), (pl[0], pl[1] + thick)], shade(c, 0.58), st, 0.4)
            self.poly([pm, pr, (pr[0], pr[1] + thick), (pm[0], pm[1] + thick)], shade(cd, 0.6), st, 0.4)
        self.line([pm, a], lit(c, 1.3, 0.2), 0.8)

    def _arc(self, xc, yc, r, z, n=14):
        """手前の半円（左端 → 右端）。"""
        out = []
        for i in range(n + 1):
            phi = math.radians(135 - 180 * i / n)
            out.append(P(xc + r * math.cos(phi), yc + r * math.sin(phi), z))
        return out

    def _round_stops(self, c):
        return [(0.0, shade(c, 0.9)), (0.24, lit(c, 1.1, 0.14)), (0.52, c), (0.84, shd(c, 0.66)), (1.0, shd(c, 0.78))]

    def cylinder(self, xc, yc, r, z0, z1, c, top=None, outline=True, cast=True, sil=True, cap=True):
        st = edge(c) if outline else None
        lo = self._arc(xc, yc, r, z0)
        hi = self._arc(xc, yc, r, z1)
        body = lo + list(reversed(hi))
        cc = P(xc, yc, z1)
        ax = r * U * math.sqrt(2)
        if sil:
            self.sil(body)
            self.sil([(cc[0] + ax * math.cos(2 * math.pi * i / 16), cc[1] + ax * 0.5 * math.sin(2 * math.pi * i / 16))
                      for i in range(16)])
        if cast:
            self.cast_round(xc, yc, r, z0, z1)
        self.poly(body, Linear(cc[0] - ax, 0, cc[0] + ax, 0, self._round_stops(c)), st, 0.45)
        if cap:
            self.oval(cc[0], cc[1], ax, ax * 0.5, top or lit(c, 1.18), st, 0.45)

    def cone(self, xc, yc, r, z, h, c, over=0.12, cast=True, sil=True):
        r += over
        st = edge(c, 0.42)
        arc = self._arc(xc, yc, r, z)
        apex = P(xc, yc, z + h)
        ax = r * U * math.sqrt(2)
        if sil:
            self.sil(arc + [apex])
        if cast:
            self.cast_round(xc, yc, r, z, z, apex=z + h)
        stops = [(0.0, shade(c, 0.92)), (0.3, lit(c, 1.14, 0.14)), (0.5, shade(c, 0.96)), (0.6, shd(c, 0.72)),
                 (1.0, shd(c, 0.56))]
        self.poly(arc + [apex], Linear(apex[0] - ax, 0, apex[0] + ax, 0, stops), st, 0.45)

    def dome(self, xc, yc, r, z, h, c, over=0.0, cast=True, sil=True):
        r += over
        st = edge(c, 0.42)
        n = 16
        arc = self._arc(xc, yc, r, z, n)
        ax = r * U * math.sqrt(2)
        cx, cy = P(xc, yc, z)
        # 左端から頂点を通って右端まで（半楕円）
        up = []
        for i in range(n + 1):
            th = math.pi - math.pi * i / n
            up.append((cx + ax * math.cos(th), cy - h * math.sin(th)))
        body = arc + list(reversed(up))
        if sil:
            self.sil(body)
        if cast:
            self.cast_round(xc, yc, r * 0.8, z, z + h * 0.8)
        rr = max(ax, h) * 1.7
        self.poly(body, Radial(cx - ax * 0.4, cy - h * 0.62, rr,
                               [(0.0, lit(c, 1.3, 0.2)), (0.3, c), (0.72, shd(c, 0.66)), (1.0, shd(c, 0.5))]), st, 0.45)

    # ---- 付属物 ---------------------------------------------------------------
    def flag(self, x, y, z, h, c, w=7.0, pole="#4A3A2A"):
        a, b = P(x, y, z), P(x, y, z + h)
        self.line([a, b], pole, 1.1, cap="round")
        t = (b[0], b[1] + 0.6)
        # はためく三角旗（上半分を明るく、下半分を暗く）
        tip = (t[0] + w, t[1] + w * 0.30)
        low = (t[0] + w * 0.12, t[1] + w * 0.62)
        mid = (t[0] + w * 0.45, t[1] + w * 0.28)
        self.poly([t, (t[0] + w * 0.5, t[1] - w * 0.04), tip, (t[0] + w * 0.55, t[1] + w * 0.5), low], c,
                  edge(c, 0.5), 0.45, sil=True)
        self.poly([(t[0] + 0.3, t[1] + w * 0.3), mid, tip, (t[0] + w * 0.55, t[1] + w * 0.5), low], shd(c, 0.74), None, 0)
        self.oval(b[0], b[1] - 0.4, 0.9, 0.9, "#F2CB55", "#7A5A14", 0.3)

    def banner(self, x, y, z, h, c, trim="#E8C547"):
        """垂れ幕（右前面に沿う）。"""
        pts = [P(x, y - 0.18, z), P(x, y + 0.18, z), P(x, y + 0.18, z - h), P(x, y, z - h - 0.28 * 7), P(x, y - 0.18, z - h)]
        cs = shd(c, 0.8)
        self.poly(pts, grad_perp(pts[0], pts[1], pts[2], [shade(cs, 1.1), shade(cs, 0.8)]), edge(c, 0.5), 0.4)
        self.line([P(x, y - 0.18, z), P(x, y + 0.18, z)], trim, 1.0)
        self.line([P(x, y - 0.18, z - h + 1.2), P(x, y, z - h - 0.28 * 7 + 1.4), P(x, y + 0.18, z - h + 1.2)], trim, 0.5)

    def banner_left(self, x, y, z, h, c, trim="#E8C547", w=0.18):
        """垂れ幕（左前面 y = y に沿う）。"""
        pts = [P(x - w, y, z), P(x + w, y, z), P(x + w, y, z - h), P(x, y, z - h - 0.28 * 7), P(x - w, y, z - h)]
        self.poly(pts, grad_perp(pts[0], pts[1], pts[2], [lit(c, 1.1), shade(c, 0.8)]), edge(c, 0.5), 0.4)
        self.line([pts[0], pts[1]], trim, 1.0)
        self.line([P(x - w, y, z - h + 1.2), P(x, y, z - h - 0.28 * 7 + 1.4), P(x + w, y, z - h + 1.2)], trim, 0.5)

    def win_left(self, x, z, w, h, y1, c="#2B2230", lit=None, frame=None):
        pts = [P(x - w / 2, y1, z - h / 2), P(x + w / 2, y1, z - h / 2), P(x + w / 2, y1, z + h / 2), P(x - w / 2, y1, z + h / 2)]
        if lit:
            cx, cy = P(x, y1, z)
            self.oval(cx, cy, w * U * 0.5 + 2.4, h * 0.5 + 2.4, lit, None, 0, 0.24)
        self.poly(pts, lit or c, frame or shade(c, 1.6), 0.6)

    def win_right(self, y, z, w, h, x1, c="#2B2230", lit=None, frame=None):
        pts = [P(x1, y - w / 2, z - h / 2), P(x1, y + w / 2, z - h / 2), P(x1, y + w / 2, z + h / 2), P(x1, y - w / 2, z + h / 2)]
        if lit:
            cx, cy = P(x1, y, z)
            self.oval(cx, cy, w * U * 0.5 + 2.4, h * 0.5 + 2.4, lit, None, 0, 0.24)
        self.poly(pts, lit or c, frame or shade(c, 1.6), 0.6)

    def _arch(self, fn, a, w, h, z0=0.0, n=6):
        """アーチ形（下が四角、上が半円）の輪郭。fn(t, z) が面上の点を返す。"""
        pts = [fn(a - w / 2, z0), fn(a + w / 2, z0)]
        for i in range(n + 1):
            th = math.pi * i / n
            pts.append(fn(a + (w / 2) * math.cos(th), z0 + h * 0.68 + (h * 0.32) * math.sin(th)))
        return pts

    def door_left(self, x, w, h, y1, c="#3A2A1E", frame=None, z0=0.0, grad=True):
        """左前面（y = y1）のアーチの扉。frame があれば石の枠をつける。"""
        fn = lambda a, z: P(a, y1, z)  # noqa: E731
        if frame:
            self.poly(self._arch(fn, x, w + 0.26, h + 1.8, z0), frame, edge(frame), 0.4)
        pts = self._arch(fn, x, w, h, z0)
        self.poly(pts, vgrad(pts[4][1], pts[0][1], shade(c, 0.7), shade(c, 1.15)) if grad else c, shade(c, 0.45), 0.4)
        self.lines([[P(x, y1, z0), P(x, y1, z0 + h)], [P(x - w / 2, y1, z0 + h * 0.5), P(x + w / 2, y1, z0 + h * 0.5)]],
                   shade(c, 0.5), 0.4, 0.8)

    def door_right(self, y, w, h, x1, c="#3A2A1E", frame=None, z0=0.0):
        """右前面（x = x1）のアーチの扉。"""
        fn = lambda a, z: P(x1, a, z)  # noqa: E731
        if frame:
            self.poly(self._arch(fn, y, w + 0.26, h + 1.8, z0), frame, edge(frame), 0.4)
        pts = self._arch(fn, y, w, h, z0)
        c2 = shd(c, 0.8)
        self.poly(pts, vgrad(pts[4][1], pts[1][1], shade(c2, 0.7), shade(c2, 1.1)), shade(c2, 0.45), 0.4)
        self.lines([[P(x1, y, z0), P(x1, y, z0 + h)]], shade(c2, 0.5), 0.4, 0.8)

    def shadow(self, x0, y0, x1, y1, alpha=0.28):
        self.poly([P(x0, y0, 0), P(x1, y0, 0), P(x1, y1, 0), P(x0, y1, 0)], "#000000", None, 0, alpha)

    def soft_shadow(self, x0, y0, x1, y1, alpha=0.4, grow=0.3):
        """足元のやわらかい影（接地の影）。区画 (x0, y0)-(x1, y1) の中心から外へ消える。"""
        cx, cy = P((x0 + x1) / 2.0, (y0 + y1) / 2.0, 0)
        rx = ((x1 - x0) + (y1 - y0)) * 0.5 * U * (1.0 + grow)
        self.glow(cx + 1.5, cy + 1.0, rx, rx * 0.5, SHADOW_INK, alpha, mid=(0.62, 0.8))

    def pad(self, x0, y0, x1, y1, c, edge=None, grad=False):
        """足元の敷石・地面のパッド。grad: 奥（上）を明るく、手前（下）を暗くする。"""
        pts = [P(x0, y0, 0), P(x1, y0, 0), P(x1, y1, 0), P(x0, y1, 0)]
        fill = vgrad(pts[0][1], pts[2][1], lit(c, 1.1), shade(c, 0.86)) if grad else c
        self.poly(pts, fill, edge or shade(c, 0.6), 0.5)

    # ---- 出力 ----------------------------------------------------------------
    def resolved(self):
        """差し込み位置の予約（縁取り・落ち影）を実際のパスに置き換えた図形の並び。"""
        out = []
        for s in self.shapes:
            if s.kind is None:
                out.append(s)
            elif s.kind[0] == "outline":
                ps = self._layers[s.kind[1]]
                if self.outline and ps:
                    out.append(Shape(ps[0], self.outline, self.outline, self.ow * 2.0, 1.0,
                                     subs=[(p, True) for p in ps[1:]]))
            elif s.kind[0] == "cast" and self._casts:
                xa = min(x for p in self._casts for x, _ in p)
                xb = max(max(x for p in self._casts for x, _ in p), xa + 1.0)
                xb = min(xb + 2.0, self.w - 0.5)
                g = Linear(xa, 0, xb, 0, [(0.0, with_alpha(SHADOW_INK, 0.40)), (0.6, with_alpha(SHADOW_INK, 0.30)),
                                          (1.0, with_alpha(SHADOW_INK, 0.0))])
                out.append(Shape(self._casts[0], g, None, 0, 1.0, subs=[(p, True) for p in self._casts[1:]]))
        return out

    def stats(self):
        """(パスの数, グラデーションの数)。"""
        rs = self.resolved()
        return len(rs), sum(1 for s in rs if is_grad(s.fill))

    def to_xml(self, name=""):
        shapes = self.resolved()
        cp = self.compact
        num = _f if cp else (lambda v: "%.1f" % v)
        has_grad = any(is_grad(s.fill) for s in shapes)
        out = []
        out.append('<?xml version="1.0" encoding="utf-8"?>')
        out.append("<!-- tools/artgen で生成（手で編集しない）: %s -->" % name)
        ns = 'xmlns:android="http://schemas.android.com/apk/res/android"'
        if has_grad:
            ns += ' xmlns:aapt="http://schemas.android.com/aapt"'
        out.append(
            '<vector %s android:width="%ddp" android:height="%ddp" '
            'android:viewportWidth="%d" android:viewportHeight="%d">' % (ns, self.w, self.h, self.w, self.h)
        )
        ind = "" if cp else "  "
        for s in shapes:
            d = s.d if s.d is not None else _path_data(s.pts, s.closed, num)
            for sp, scl in (s.subs or ()):
                d += _path_data(sp, scl, num)
            attrs = []
            grad = None
            if is_grad(s.fill):
                grad = s.fill
            elif s.fill is not None:
                attrs.append('android:fillColor="%s"' % s.fill)
                if s.alpha < 1.0:
                    attrs.append('android:fillAlpha="%.2f"' % s.alpha)
            if s.stroke is not None and s.sw > 0:
                attrs.append('android:strokeColor="%s"' % s.stroke)
                attrs.append('android:strokeWidth="%.2f"' % s.sw)
                if not cp or s.sw >= 0.8:
                    attrs.append('android:strokeLineJoin="round"')
                if s.cap:
                    attrs.append('android:strokeLineCap="%s"' % s.cap)
                sa = _stroke_alpha(s)
                if sa < 1.0:
                    attrs.append('android:strokeAlpha="%.2f"' % sa)
            if s.xf:
                out.append('%s<group android:pivotX="%s" android:pivotY="%s" android:scaleX="%s" android:scaleY="%s">'
                           % (ind, _f(s.xf[0]), _f(s.xf[1]), _f3(s.xf[2]), _f3(s.xf[3])))
            if grad is None:
                out.append('%s<path %s android:pathData="%s" />' % (ind, " ".join(attrs), d))
            else:
                out.append('%s<path %sandroid:pathData="%s">' % (ind, "".join(a + " " for a in attrs), d))
                out.append('%s<aapt:attr name="android:fillColor">%s</aapt:attr>' % (ind, _grad_xml(grad, s.alpha)))
                out.append("%s</path>" % ind)
            if s.xf:
                out.append("%s</group>" % ind)
        out.append("</vector>")
        return "\n".join(out) + "\n"

    # ---- SVG（確認用） ----------------------------------------------------------
    def to_svg(self, size=None, uid="g"):
        """同じ絵の SVG（ブラウザで見本を作るため。VectorDrawable と同じ描画順・同じグラデーション）。"""
        defs, body = [], []
        n = [0]

        def paint(p, a):
            if p is None:
                return "none", 1.0
            if isinstance(p, str):
                c = p.lstrip("#")
                if len(c) == 8:
                    return "#" + c[2:], a * int(c[0:2], 16) / 255.0
                return p, a
            n[0] += 1
            gid = "%s%d" % (uid, n[0])
            stops = ""
            for off, c in p.stops:
                cc, op = c.lstrip("#"), 1.0
                if len(cc) == 8:
                    op, cc = int(cc[0:2], 16) / 255.0, cc[2:]
                stops += '<stop offset="%s" stop-color="#%s" stop-opacity="%s"/>' % (_f3(off), cc, _f3(op * a))
            if isinstance(p, Linear) or hasattr(p, "x0"):
                defs.append('<linearGradient id="%s" gradientUnits="userSpaceOnUse" x1="%s" y1="%s" x2="%s" y2="%s">%s'
                            "</linearGradient>" % (gid, _f(p.x0), _f(p.y0), _f(p.x1), _f(p.y1), stops))
            else:
                defs.append('<radialGradient id="%s" gradientUnits="userSpaceOnUse" cx="%s" cy="%s" r="%s">%s'
                            "</radialGradient>" % (gid, _f(p.cx), _f(p.cy), _f(p.r), stops))
            return "url(#%s)" % gid, 1.0

        for s in self.resolved():
            d = s.d if s.d is not None else _path_data(s.pts, s.closed, _f)
            for sp, scl in (s.subs or ()):
                d += _path_data(sp, scl, _f)
            f, fo = paint(s.fill, s.alpha)
            st, so = "none", 1.0
            if s.stroke is not None and s.sw > 0:
                st, so = paint(s.stroke, _stroke_alpha(s))
            el = ('<path d="%s" fill="%s" fill-opacity="%s" stroke="%s" stroke-opacity="%s" stroke-width="%s" '
                  'stroke-linejoin="round" stroke-linecap="%s"/>' % (d, f, _f3(fo), st, _f3(so), _f3(s.sw), s.cap or "butt"))
            if s.xf:
                px, py, sx, sy = s.xf
                el = '<g transform="translate(%s %s) scale(%s %s) translate(%s %s)">%s</g>' % (
                    _f(px), _f(py), _f3(sx), _f3(sy), _f(-px), _f(-py), el)
            body.append(el)
        w = size or self.w
        h = (size * self.h / float(self.w)) if size else self.h
        return '<svg xmlns="http://www.w3.org/2000/svg" width="%s" height="%s" viewBox="0 0 %d %d"><defs>%s</defs>%s</svg>' % (
            _f(w), _f(h), self.w, self.h, "".join(defs), "".join(body))


def _stroke_alpha(s):
    """線の不透明度。塗りのない線は alpha、塗りのある図形の縁は salpha。"""
    if s.fill is None:
        return min(s.alpha, s.salpha)
    return s.salpha


def _f(v):
    s = "%.1f" % v
    if s.endswith(".0"):
        s = s[:-2]
    return "0" if s == "-0" else s


def _f3(v):
    s = ("%.3f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def _argb8(c, a=1.0):
    """'#RRGGBB' / '#AARRGGBB' と追加の不透明度から '#AARRGGBB'。"""
    cc = c.lstrip("#").upper()
    if len(cc) == 8:
        a, cc = a * int(cc[0:2], 16) / 255.0, cc[2:]
    return "#%02X%s" % (max(0, min(255, round(a * 255))), cc)


def _grad_xml(g, alpha=1.0):
    if hasattr(g, "x0"):
        head = '<gradient android:type="linear" android:startX="%s" android:startY="%s" android:endX="%s" android:endY="%s"' % (
            _f(g.x0), _f(g.y0), _f(g.x1), _f(g.y1))
    else:
        head = '<gradient android:type="radial" android:centerX="%s" android:centerY="%s" android:gradientRadius="%s"' % (
            _f(g.cx), _f(g.cy), _f(g.r))
    st = g.stops
    if len(st) == 2 and st[0][0] == 0.0 and st[1][0] == 1.0:
        return '%s android:startColor="%s" android:endColor="%s" />' % (head, _argb8(st[0][1], alpha), _argb8(st[1][1], alpha))
    items = "".join('<item android:offset="%s" android:color="%s" />' % (_f3(o), _argb8(c, alpha)) for o, c in st)
    return "%s>%s</gradient>" % (head, items)


def _argb(c):
    return c if len(c) == 7 else c


def _path_data(pts, closed, num=None):
    parts = []
    if num is None:
        for i, (x, y) in enumerate(pts):
            parts.append("%s%.1f,%.1f" % ("M" if i == 0 else "L", x, y))
    else:
        for i, (x, y) in enumerate(pts):
            parts.append("%s%s,%s" % ("M" if i == 0 else "L", num(x), num(y)))
    return "".join(parts) + ("Z" if closed else "")
