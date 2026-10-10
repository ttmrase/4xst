"""アイソメトリック風の建物スプライトを組み立てる小さな作図ライブラリ（標準ライブラリのみ）。

座標系: グリッド (x, y, z)。x は右下、y は左下、z は上へ。画面への射影は
  sx = CX + (x - y) * U
  sy = CY + (x + y - S) * V - z
で、U:V = 2:1（地面のひし形が 2:1）。スプライトは 128 x 160 の viewport で、地面の中心が (CX, CY) = (64, 120)。
Scene に図形（多角形・折れ線）を描画順に積み、VectorDrawable の XML にする（出力は決定論的）。
"""

import math

W_VP, H_VP = 128, 160
CX, CY = 64.0, 120.0
U, V = 14.0, 7.0
S = 4.0  # 4 x 4 グリッドの足元（中心が S）


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


class Shape:
    __slots__ = ("pts", "fill", "stroke", "sw", "alpha", "closed")

    def __init__(self, pts, fill, stroke, sw, alpha, closed=True):
        self.pts, self.fill, self.stroke, self.sw, self.alpha, self.closed = pts, fill, stroke, sw, alpha, closed


def set_anchor(cx, cy):
    """スプライトの原点（足元の中心）を viewport 内のどこに置くか。"""
    global CX, CY
    CX, CY = float(cx), float(cy)


class Scene:
    def __init__(self, w=W_VP, h=H_VP):
        self.shapes = []
        self.w, self.h = w, h

    # ---- 基本図形 -------------------------------------------------------------
    def poly(self, pts, fill, stroke=None, sw=0.6, alpha=1.0):
        self.shapes.append(Shape([(float(x), float(y)) for x, y in pts], fill, stroke, sw, alpha))

    def line(self, pts, stroke, sw=1.0, alpha=1.0):
        self.shapes.append(Shape([(float(x), float(y)) for x, y in pts], None, stroke, sw, alpha, closed=False))

    def ellipse(self, cx, cy, rx, ry, fill, stroke=None, sw=0.6, alpha=1.0, n=20):
        pts = [(cx + rx * math.cos(2 * math.pi * i / n), cy + ry * math.sin(2 * math.pi * i / n)) for i in range(n)]
        self.poly(pts, fill, stroke, sw, alpha)

    # ---- 箱・屋根 -------------------------------------------------------------
    def box(self, x0, y0, x1, y1, z0, z1, c, top=None, outline=True, lf=1.0, rf=0.72, tf=1.15):
        """直方体。見えるのは上面・左前面（y = y1）・右前面（x = x1）。"""
        st = shade(c, 0.45) if outline else None
        self.poly([P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1), P(x1, y0, z1)], shade(c, rf), st)
        self.poly([P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)], shade(c, lf), st)
        self.poly([P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], top or shade(c, tf), st)

    def gable(self, x0, y0, x1, y1, z, h, c, axis="x", over=0.25, gable_c=None):
        """切妻屋根。axis='x' は棟が x 方向。軒を over だけ出す。"""
        x0, y0, x1, y1 = x0 - over, y0 - over, x1 + over, y1 + over
        st = shade(c, 0.4)
        gc = gable_c or shade(c, 0.6)
        if axis == "x":
            ym = (y0 + y1) / 2
            self.poly([P(x0, y0, z), P(x1, y0, z), P(x1, ym, z + h), P(x0, ym, z + h)], shade(c, 0.78), st)
            self.poly([P(x0, y1, z), P(x1, y1, z), P(x1, ym, z + h), P(x0, ym, z + h)], shade(c, 1.0), st)
            self.poly([P(x1, y0, z), P(x1, y1, z), P(x1, ym, z + h)], gc, st)
        else:
            xm = (x0 + x1) / 2
            self.poly([P(x0, y0, z), P(x0, y1, z), P(xm, y1, z + h), P(xm, y0, z + h)], shade(c, 0.78), st)
            self.poly([P(x1, y0, z), P(x1, y1, z), P(xm, y1, z + h), P(xm, y0, z + h)], shade(c, 1.0), st)
            self.poly([P(x0, y1, z), P(x1, y1, z), P(xm, y1, z + h)], gc, st)

    def pyramid(self, x0, y0, x1, y1, z, h, c, over=0.2):
        x0, y0, x1, y1 = x0 - over, y0 - over, x1 + over, y1 + over
        xm, ym = (x0 + x1) / 2, (y0 + y1) / 2
        st = shade(c, 0.4)
        self.poly([P(x1, y0, z), P(x1, y1, z), P(xm, ym, z + h)], shade(c, 0.74), st)
        self.poly([P(x0, y1, z), P(x1, y1, z), P(xm, ym, z + h)], shade(c, 1.05), st)

    def cylinder(self, xc, yc, r, z0, z1, c, top=None, outline=True):
        st = shade(c, 0.45) if outline else None
        n = 14
        front = []
        for i in range(n + 1):
            phi = math.radians(135 - 180 * i / n)
            front.append((xc + r * math.cos(phi), yc + r * math.sin(phi)))
        # 左半分（φ=135°→45°）と右半分（45°→-45°）で色を分ける
        half = n // 2
        left = front[: half + 1]
        right = front[half:]

        def body(arc):
            lo = [P(x, y, z0) for x, y in arc]
            hi = [P(x, y, z1) for x, y in reversed(arc)]
            return lo + hi

        self.poly(body(left), shade(c, 1.0), st)
        self.poly(body(right), shade(c, 0.72), st)
        cap = []
        for i in range(24):
            phi = 2 * math.pi * i / 24
            cap.append(P(xc + r * math.cos(phi), yc + r * math.sin(phi), z1))
        self.poly(cap, top or shade(c, 1.18), st)

    def cone(self, xc, yc, r, z, h, c, over=0.12):
        r += over
        st = shade(c, 0.4)
        # 底の前半円（左端 → 右端）と頂点
        arc = []
        n = 14
        for i in range(n + 1):
            phi = math.radians(135 - 180 * i / n)
            arc.append(P(xc + r * math.cos(phi), yc + r * math.sin(phi), z))
        apex = P(xc, yc, z + h)
        half = n // 2
        self.poly(arc[: half + 1] + [apex], shade(c, 1.05), st)
        self.poly(arc[half:] + [apex], shade(c, 0.74), st)

    def dome(self, xc, yc, r, z, h, c, over=0.0):
        r += over
        st = shade(c, 0.42)
        n = 16
        arc = []
        for i in range(n + 1):
            phi = math.radians(135 - 180 * i / n)
            arc.append(P(xc + r * math.cos(phi), yc + r * math.sin(phi), z))
        ax = r * U * math.sqrt(2)
        cx, cy = P(xc, yc, z)
        # 左端から頂点を通って右端まで（半楕円）
        up = []
        m = 16
        for i in range(m + 1):
            th = math.pi - math.pi * i / m
            up.append((cx + ax * math.cos(th), cy - h * math.sin(th)))
        body = arc + list(reversed(up))
        self.poly(body, shade(c, 1.0), st)
        right = [p for p in up if p[0] >= cx]
        rarc = arc[n // 2:]
        self.poly(rarc + list(reversed(right)), shade(c, 0.74), st)

    # ---- 付属物 ---------------------------------------------------------------
    def flag(self, x, y, z, h, c, w=7.0, pole="#4A3A2A"):
        a, b = P(x, y, z), P(x, y, z + h)
        self.line([a, b], pole, 1.1)
        t = (b[0], b[1])
        self.poly([t, (t[0] + w, t[1] + w * 0.28), (t[0] + w * 0.15, t[1] + w * 0.62)], c, shade(c, 0.5), 0.4)

    def banner(self, x, y, z, h, c, trim="#E8C547"):
        """垂れ幕（右前面に沿う）。"""
        pts = [P(x, y - 0.18, z), P(x, y + 0.18, z), P(x, y + 0.18, z - h), P(x, y, z - h - 0.28), P(x, y - 0.18, z - h)]
        self.poly(pts, c, shade(c, 0.5), 0.4)
        self.line([P(x, y - 0.18, z), P(x, y + 0.18, z)], trim, 1.0)

    def win_left(self, x, z, w, h, y1, c="#2B2230", lit=None, frame=None):
        pts = [P(x - w / 2, y1, z - h / 2), P(x + w / 2, y1, z - h / 2), P(x + w / 2, y1, z + h / 2), P(x - w / 2, y1, z + h / 2)]
        self.poly(pts, lit or c, frame or shade(c, 1.6), 0.4)

    def win_right(self, y, z, w, h, x1, c="#2B2230", lit=None, frame=None):
        pts = [P(x1, y - w / 2, z - h / 2), P(x1, y + w / 2, z - h / 2), P(x1, y + w / 2, z + h / 2), P(x1, y - w / 2, z + h / 2)]
        self.poly(pts, lit or c, frame or shade(c, 1.6), 0.4)

    def door_left(self, x, w, h, y1, c="#3A2A1E"):
        pts = [P(x - w / 2, y1, 0), P(x + w / 2, y1, 0), P(x + w / 2, y1, h * 0.7)]
        arch = []
        for i in range(7):
            th = math.pi * i / 6
            arch.append(P(x + (w / 2) * math.cos(th), y1, h * 0.7 + (h * 0.3) * math.sin(th)))
        self.poly([P(x - w / 2, y1, 0), P(x + w / 2, y1, 0)] + arch, c, shade(c, 1.8), 0.4)

    def shadow(self, x0, y0, x1, y1, alpha=0.28):
        self.poly([P(x0, y0, 0), P(x1, y0, 0), P(x1, y1, 0), P(x0, y1, 0)], "#000000", None, 0, alpha)

    def pad(self, x0, y0, x1, y1, c, edge=None):
        """足元の敷石・地面のパッド。"""
        self.poly([P(x0, y0, 0), P(x1, y0, 0), P(x1, y1, 0), P(x0, y1, 0)], c, edge or shade(c, 0.6), 0.5)

    # ---- 出力 ----------------------------------------------------------------
    def to_xml(self, name=""):
        out = []
        out.append('<?xml version="1.0" encoding="utf-8"?>')
        out.append("<!-- tools/artgen で生成（手で編集しない）: %s -->" % name)
        out.append(
            '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="%ddp" android:height="%ddp" '
            'android:viewportWidth="%d" android:viewportHeight="%d">' % (self.w, self.h, self.w, self.h)
        )
        for s in self.shapes:
            d = _path_data(s.pts, s.closed)
            attrs = []
            if s.fill is not None:
                attrs.append('android:fillColor="%s"' % _argb(s.fill))
                if s.alpha < 1.0:
                    attrs.append('android:fillAlpha="%.2f"' % s.alpha)
            if s.stroke is not None and s.sw > 0:
                attrs.append('android:strokeColor="%s"' % s.stroke)
                attrs.append('android:strokeWidth="%.2f"' % s.sw)
                attrs.append('android:strokeLineJoin="round"')
                if s.fill is None and s.alpha < 1.0:
                    attrs.append('android:strokeAlpha="%.2f"' % s.alpha)
            out.append('  <path %s android:pathData="%s" />' % (" ".join(attrs), d))
        out.append("</vector>")
        return "\n".join(out) + "\n"


def _argb(c):
    return c if len(c) == 7 else c


def _path_data(pts, closed):
    parts = []
    for i, (x, y) in enumerate(pts):
        parts.append("%s%.1f,%.1f" % ("M" if i == 0 else "L", x, y))
    return "".join(parts) + ("Z" if closed else "")
