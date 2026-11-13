package com.ttmrase.crownfall.ui.territory

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 飾りの動きに使う、やわらかい小さな絵（煙の玉・あかりのにじみ・雲・きらめき）。
 * 放射グラデーションで 1 度だけビットマップに焼き、毎フレームは貼るだけにする（描画のたびに Brush や Path を作らない）。
 * 領地ビューとワールドマップで共有する。
 */
internal class LifeArt private constructor() {
    /** 白い、縁のやわらかい玉（煙・土ぼこり・しぶき）。 */
    val puff: ImageBitmap =
        bake(PUFF_PX, PUFF_PX) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White,
                    0.45f to Color.White.copy(alpha = 0.72f),
                    1f to Color.Transparent,
                ),
            )
        }

    /** 暖かいあかりのにじみ（たいまつ・炉・焚き火）。加算で重ねる。 */
    val glow: ImageBitmap =
        bake(GLOW_PX, GLOW_PX) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFFF2C4),
                    0.22f to Color(0xD9FFC864),
                    0.55f to Color(0x59FF9A3C),
                    1f to Color(0x00FF7A2A),
                ),
            )
        }

    /** こんもりした雲の形（白）。影は [SHADOW_TINT] で暗く染めて使う。 */
    val cloud: ImageBitmap =
        bake(CLOUD_W, CLOUD_H) {
            for (k in CLOUD_LUMPS.indices step LUMP_STRIDE) {
                val c = Offset(CLOUD_LUMPS[k] * CLOUD_W, CLOUD_LUMPS[k + 1] * CLOUD_H)
                val r = CLOUD_LUMPS[k + 2] * CLOUD_H
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.White.copy(alpha = 0.85f),
                        0.5f to Color.White.copy(alpha = 0.5f),
                        1f to Color.Transparent,
                        center = c,
                        radius = r,
                    ),
                    radius = r,
                    center = c,
                )
            }
        }

    /** 4 方向に伸びる小さなきらめき（金の屋根・水面・アップグレードできる建物）。 */
    val spark: ImageBitmap =
        bake(SPARK_PX, SPARK_PX) {
            val ray = Brush.radialGradient(0f to Color.White, 0.3f to Color(0x99FFFFFF), 1f to Color.Transparent)
            scale(1f, SPARK_THIN) { drawCircle(ray) }
            scale(SPARK_THIN, 1f) { drawCircle(ray) }
            drawCircle(
                Brush.radialGradient(
                    0f to Color.White,
                    0.5f to Color(0x66FFFFFF),
                    1f to Color.Transparent,
                    radius = SPARK_PX * SPARK_CORE,
                ),
                radius = SPARK_PX * SPARK_CORE,
            )
        }

    companion object {
        /** アプリ全体で 1 つ（最初に使うときに焼く。合わせて 0.3MB ほど）。 */
        val shared: LifeArt by lazy { LifeArt() }

        /** 雲の影の色。 */
        val SHADOW_TINT: ColorFilter = ColorFilter.tint(Color(0xFF0E1A2E))

        /** 工房の濃い煙の色。 */
        val SOOT_TINT: ColorFilter = ColorFilter.tint(Color(0xFF6E6A66))
        val DUST_TINT: ColorFilter = ColorFilter.tint(Color(0xFFD9C7A0))
        val FIREFLY_TINT: ColorFilter = ColorFilter.tint(Color(0xFFE8FF8A))
        val GOLD_TINT: ColorFilter = ColorFilter.tint(Color(0xFFFFE08A))
        val EMBER_TINT: ColorFilter = ColorFilter.tint(Color(0xFFFF9A3C))
        val ICE_TINT: ColorFilter = ColorFilter.tint(Color(0xFFD8F1FF))
        val PETAL_TINT: ColorFilter = ColorFilter.tint(Color(0xFFFFC1D3))

        private const val PUFF_PX = 48
        private const val GLOW_PX = 64
        private const val CLOUD_W = 256
        private const val CLOUD_H = 128
        private const val SPARK_PX = 48
        private const val SPARK_THIN = 0.13f
        private const val SPARK_CORE = 0.2f
        private const val LUMP_STRIDE = 3

        /** 雲のこぶ（x, y は画像に対する割合、半径は高さに対する割合）。 */
        private val CLOUD_LUMPS =
            floatArrayOf(
                0.24f, 0.60f, 0.34f,
                0.40f, 0.44f, 0.42f,
                0.58f, 0.50f, 0.46f,
                0.76f, 0.60f, 0.32f,
                0.50f, 0.66f, 0.30f,
                0.33f, 0.70f, 0.24f,
                0.67f, 0.72f, 0.24f,
            )

        private fun bake(
            w: Int,
            h: Int,
            block: DrawScope.() -> Unit,
        ): ImageBitmap {
            val image = ImageBitmap(w, h)
            CanvasDrawScope().draw(
                Density(1f),
                LayoutDirection.Ltr,
                Canvas(image),
                Size(w.toFloat(), h.toFloat()),
                block,
            )
            return image
        }
    }
}

/** 小さな人の服と髪の色。 */
internal class FigureLook(
    val tunic: Color,
    val legs: Color,
    val hair: Color,
    val skin: Color = Color(0xFFF1D2B0),
)

/**
 * 飾りの動きの部品（小さな人・旗・鳥・炎・やわらかい玉）を描く関数。領地ビューとワールドマップで共有する。
 * どれも引数の値だけで描き、オブジェクトを確保しない（Offset / Size / Color は値クラス）。
 */
internal object LifeDraw {
    const val GEAR_NONE = 0
    const val GEAR_SACK = 1
    const val GEAR_TOOL = 2
    const val GEAR_SPEAR = 3

    /** 人の背の高さ（[figure] の u に対する倍率）。 */
    const val FIGURE_HEIGHT = 12.2f

    /** 町の人の見た目（[TownLife.LOOKS] 種類）。 */
    val TOWN_LOOKS =
        arrayOf(
            FigureLook(Color(0xFFC8553D), Color(0xFF4A3B33), Color(0xFF5A3A22)),
            FigureLook(Color(0xFF4F8FD0), Color(0xFF39404F), Color(0xFF2E2A28)),
            FigureLook(Color(0xFF7FB85A), Color(0xFF4D4234), Color(0xFFB8843C)),
            FigureLook(Color(0xFFE6B450), Color(0xFF54402E), Color(0xFF3A2A1E)),
            FigureLook(Color(0xFF9A6FC0), Color(0xFF3C3A4A), Color(0xFF6E4A2A)),
            FigureLook(Color(0xFFE8E0CC), Color(0xFF5A4A3A), Color(0xFFD9B36A)),
            FigureLook(Color(0xFF4FA8A0), Color(0xFF3F4640), Color(0xFF8A5A30)),
            FigureLook(Color(0xFFB8733E), Color(0xFF453A30), Color(0xFF2A2420)),
        )

    /** 衛兵（鎖かたびら・鉄兜）。 */
    val GUARD_LOOK = FigureLook(Color(0xFF8E97A6), Color(0xFF3A3F4C), Color(0xFFC8CED8))

    private val SHADE = Color(0xFF14102A)
    private val SACK = Color(0xFFD2B987)
    private val WOOD = Color(0xFF6B4A2A)
    private val STEEL = Color(0xFFDCE2EA)
    private val CREST = Color(0xFFC43A2E)
    private val BIRD = Color(0xFF2B3140)
    private val BIRD_LIGHT = Color(0xFF8E97A8)
    private val FLAME_OUT = Color(0xFFFF7A1E)
    private val FLAME_MID = Color(0xFFFFB02E)
    private val FLAME_CORE = Color(0xFFFFF0A0)
    private const val MIN_ALPHA = 0.01f
    private const val TWO_PI = 6.2831855f

    /** 0 以上 1 未満の決定論的な乱数（[a], [b] だけで決まる。Math.random は使わない）。 */
    fun hash01(
        a: Int,
        b: Int,
    ): Float {
        var h = a * 374_761_393 + b * 668_265_263
        h = (h xor (h ushr 13)) * 1_274_126_177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65_536f
    }

    /** 炎のゆらめき（0.5..1.06）。速さの違う 3 つの波を重ねて、規則的に見えないようにする。[p] は炎ごとの位相。 */
    fun flicker(
        seconds: Float,
        p: Float,
    ): Float =
        FLICKER_BASE + 0.14f * sin(seconds * 11f + p) + 0.08f * sin(seconds * 23f + p * 1.7f) +
            0.06f * sin(seconds * 5.3f + p * 0.6f)

    /** [x] の小数部分（負でも 0 以上 1 未満）。 */
    fun fract(x: Float): Float {
        val f = x - x.toInt()
        return if (f < 0f) f + 1f else f
    }

    /**
     * やわらかい絵 [image] を、中心 ([cx], [cy])・大きさ [w] x [h] に貼る。位置も大きさも小数のまま（1px 単位に丸めない）
     * なので、ゆっくり動かしても震えない。画面の外なら何もしない。
     */
    fun DrawScope.soft(
        image: ImageBitmap,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        alpha: Float,
        tint: ColorFilter? = null,
        blend: BlendMode = DrawScope.DefaultBlendMode,
    ) {
        if (alpha <= MIN_ALPHA || w <= 0f || h <= 0f) return
        val left = cx - w / 2f
        val top = cy - h / 2f
        if (left > size.width || top > size.height || left + w < 0f || top + h < 0f) return
        withTransform({
            translate(left, top)
            scale(w / image.width, h / image.height, Offset.Zero)
        }) {
            drawImage(image, alpha = alpha.coerceAtMost(1f), colorFilter = tint, blendMode = blend)
        }
    }

    /**
     * 小さな人。足元が ([x], [y])、1 単位が [u] px（背は約 [FIGURE_HEIGHT] 単位）。
     * [stride] は足の振りの位相（ラジアン）。[faceX] は画面での左右の向き（-1 / +1）、[away] は奥へ歩いている（背中が見える）。
     * 光は左上から: 胴の右側を暗く、左肩と頭に小さな明るみを置く。
     */
    fun DrawScope.figure(
        x: Float,
        y: Float,
        u: Float,
        look: FigureLook,
        stride: Float,
        moving: Boolean,
        faceX: Float,
        away: Boolean,
        gear: Int = GEAR_NONE,
    ) {
        val swing = if (moving) sin(stride) else 0f
        val lift = if (moving) cos(stride) else 0f
        // 体は、両足がそろう瞬間にいちばん高い
        val top = y - (if (moving) (1f - abs(swing)) * BOB * u else 0f)
        val depth = if (away) -1f else 1f
        drawOval(SHADE.copy(alpha = 0.26f), Offset(x - 2.3f * u, y - 0.9f * u), Size(5.6f * u, 1.9f * u))
        // 足（奥の足を先に）
        val hipY = top - 3.5f * u
        val stepX = faceX * swing * STEP_X * u
        val stepY = depth * swing * STEP_Y * u
        drawLine(
            look.legs,
            Offset(x + 0.75f * u, hipY),
            Offset(x + 0.75f * u - stepX, y - stepY * 0.5f - (-lift).coerceAtLeast(0f) * LIFT * u),
            strokeWidth = 1.3f * u,
            cap = StrokeCap.Round,
        )
        drawLine(
            look.legs,
            Offset(x - 0.75f * u, hipY),
            Offset(x - 0.75f * u + stepX, y + stepY * 0.5f - lift.coerceAtLeast(0f) * LIFT * u),
            strokeWidth = 1.3f * u,
            cap = StrokeCap.Round,
        )
        // 背負うもの（頭と胴の後ろ）
        if (gear == GEAR_TOOL) {
            val hx = x + faceX * 1.2f * u
            val hy = top - 5.2f * u
            val tx = x - faceX * 2.5f * u
            val ty = top - 12.4f * u
            drawLine(WOOD, Offset(hx, hy), Offset(tx, ty), strokeWidth = 0.75f * u, cap = StrokeCap.Round)
            drawLine(
                STEEL,
                Offset(tx - 1.1f * u, ty + faceX * 0.6f * u),
                Offset(tx + 1.1f * u, ty - faceX * 0.6f * u),
                strokeWidth = 1.1f * u,
                cap = StrokeCap.Round,
            )
        }
        // 胴
        drawRoundRect(
            look.tunic,
            Offset(x - 1.8f * u, top - 8.4f * u),
            Size(3.6f * u, 5.4f * u),
            CornerRadius(1.5f * u),
        )
        drawRoundRect(
            SHADE.copy(alpha = 0.3f),
            Offset(x + 0.3f * u, top - 8.2f * u),
            Size(1.5f * u, 5.1f * u),
            CornerRadius(0.9f * u),
        )
        drawCircle(Color.White.copy(alpha = 0.26f), 0.85f * u, Offset(x - 0.85f * u, top - 7.5f * u))
        drawLine(
            look.legs,
            Offset(x - 1.6f * u, top - 4.5f * u),
            Offset(x + 1.6f * u, top - 4.5f * u),
            strokeWidth = 0.6f * u,
        )
        if (gear == GEAR_SPEAR) {
            // 盾（奥の腕）と、まっすぐ立てた槍
            val sx = x - faceX * 1.7f * u
            drawOval(SHADE, Offset(sx - 1.5f * u, top - 8.1f * u), Size(3f * u, 3.8f * u))
            drawOval(CREST, Offset(sx - 1.15f * u, top - 7.75f * u), Size(2.3f * u, 3.1f * u))
            drawCircle(Color.White.copy(alpha = 0.4f), 0.5f * u, Offset(sx - 0.35f * u, top - 7f * u))
            val px = x + faceX * 2.2f * u
            drawLine(WOOD, Offset(px, y - 0.4f * u), Offset(px, top - 13.2f * u), strokeWidth = 0.7f * u)
            drawLine(
                STEEL,
                Offset(px, top - 13f * u),
                Offset(px, top - 15f * u),
                strokeWidth = 1.1f * u,
                cap = StrokeCap.Round,
            )
        } else {
            // 腕（足と逆に振る。荷を担いでいるときは肩の荷に添える）
            val ax = x + faceX * 0.3f * u
            val handX = if (gear == GEAR_SACK) x - faceX * 0.9f * u else ax - faceX * swing * 1.3f * u
            val handY = if (gear == GEAR_SACK) top - 8.6f * u else top - 4.6f * u
            drawLine(
                look.tunic,
                Offset(ax, top - 7.6f * u),
                Offset(handX, handY),
                strokeWidth = 1.1f * u,
                cap = StrokeCap.Round,
            )
            drawLine(
                SHADE.copy(alpha = 0.22f),
                Offset(ax, top - 7.6f * u),
                Offset(handX, handY),
                strokeWidth = 1.1f * u,
                cap = StrokeCap.Round,
            )
            drawCircle(look.skin, 0.55f * u, Offset(handX, handY))
        }
        if (gear == GEAR_SACK) {
            val cx = x - faceX * 1.6f * u
            val cy = top - 9.2f * u
            drawOval(SACK, Offset(cx - 2.2f * u, cy - 1.9f * u), Size(4.4f * u, 3.9f * u))
            drawOval(SHADE.copy(alpha = 0.28f), Offset(cx - 1.2f * u, cy - 0.2f * u), Size(3.3f * u, 2.2f * u))
            drawCircle(Color.White.copy(alpha = 0.32f), 0.8f * u, Offset(cx - 0.8f * u, cy - 0.9f * u))
        }
        // 頭（髪 / 兜の上に顔。奥へ歩くときは後ろ頭が見える）
        val headY = top - 10.3f * u
        drawCircle(look.hair, 1.9f * u, Offset(x - faceX * 0.2f * u, headY))
        if (away) {
            drawCircle(look.skin, 0.9f * u, Offset(x + faceX * 1f * u, headY + 0.5f * u))
        } else {
            drawCircle(look.skin, 1.5f * u, Offset(x + faceX * 0.35f * u, headY + 0.4f * u))
        }
        drawCircle(Color.White.copy(alpha = 0.3f), 0.6f * u, Offset(x - 0.6f * u, headY - 0.8f * u))
        if (gear == GEAR_SPEAR) {
            drawLine(
                CREST,
                Offset(x, headY - 1.7f * u),
                Offset(x - faceX * 1.3f * u, headY - 2.5f * u),
                strokeWidth = 0.9f * u,
                cap = StrokeCap.Round,
            )
        }
    }

    /**
     * 風にはためく旗。竿の上端 ([x], [y]) から右へ [length]、下へ [drop]。縦の細い帯を波に沿ってずらして並べ、
     * 波の向きで明暗を付ける（Path を作らない）。[swallow] なら先が燕尾、[pennant] なら先細りの三角旗。
     */
    fun DrawScope.flag(
        x: Float,
        y: Float,
        length: Float,
        drop: Float,
        color: Color,
        seconds: Float,
        phase: Float,
        swallow: Boolean = false,
        pennant: Boolean = false,
    ) {
        // 帯の数は旗の大きさに合わせる（大きな旗でも段が見えず、小さな旗では描く数を減らす）
        val strips = (length / FLAG_STRIP_PX).toInt().coerceIn(FLAG_STRIPS_MIN, FLAG_STRIPS_MAX)
        val strip = length / strips
        for (i in 0 until strips) {
            val f = (i + 0.5f) / strips
            val arg = seconds * FLAG_SPEED - f * FLAG_WAVES * TWO_PI + phase
            // 竿の側は動かず、先ほど大きく揺れて少し垂れる
            val cy = y + sin(arg) * drop * FLAG_AMP * f + drop * FLAG_SAG * f * f
            val cx = x + strip * (i + 0.5f)
            val taper = if (pennant) f * PENNANT_TAPER else 0f
            val top = cy + drop * 0.5f * taper
            val bottom = cy + drop * (1f - 0.5f * taper)
            val light = cos(arg)
            val notch = if (swallow && f > SWALLOW_FROM) (f - SWALLOW_FROM) / (1f - SWALLOW_FROM) * drop * 0.42f else 0f
            val w = strip + FLAG_OVERLAP
            if (notch > 0f) {
                val mid = (top + bottom) / 2f
                drawLine(color, Offset(cx, top), Offset(cx, mid - notch), strokeWidth = w)
                drawLine(color, Offset(cx, mid + notch), Offset(cx, bottom), strokeWidth = w)
            } else {
                drawLine(color, Offset(cx, top), Offset(cx, bottom), strokeWidth = w)
            }
            val shade = if (light > 0f) Color.White.copy(alpha = light * 0.22f) else SHADE.copy(alpha = -light * 0.34f)
            drawLine(shade, Offset(cx, top), Offset(cx, bottom), strokeWidth = w)
        }
    }

    /** 旗竿（暗い木に細い明るみ、金の玉）。足元が ([x], [y])、高さ [height]。 */
    fun DrawScope.pole(
        x: Float,
        y: Float,
        height: Float,
        width: Float,
    ) {
        drawLine(Color(0xFF3A2A1E), Offset(x, y), Offset(x, y - height), strokeWidth = width, cap = StrokeCap.Round)
        drawLine(
            Color(0xFFA98A5C),
            Offset(x - width * 0.22f, y - width),
            Offset(x - width * 0.22f, y - height),
            strokeWidth = width * 0.3f,
        )
        drawCircle(Color(0xFFE8C547), width * 0.95f, Offset(x, y - height - width * 0.4f))
        drawCircle(Color.White.copy(alpha = 0.6f), width * 0.32f, Offset(x - width * 0.3f, y - height - width * 0.7f))
    }

    /**
     * 鳥 1 羽（V 字の翼）。中心 ([x], [y])、翼の幅 [span]、[flap] は -1..1（羽ばたき）。[dir] は進む向き（-1 / +1）。
     * [shadow] なら地面に落ちる影（平たく、薄い）。
     */
    fun DrawScope.bird(
        x: Float,
        y: Float,
        span: Float,
        flap: Float,
        dir: Float,
        shadow: Boolean = false,
    ) {
        val squash = if (shadow) 0.45f else 1f
        val half = span * 0.5f
        val elbowY = y - (flap * 0.16f + 0.06f) * span * squash
        val tipY = y - flap * 0.34f * span * squash
        val color = if (shadow) SHADE.copy(alpha = 0.2f) else BIRD
        val w = span * (if (shadow) 0.16f else 0.11f)
        drawLine(color, Offset(x, y), Offset(x - half * 0.5f, elbowY), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(
            color,
            Offset(x - half * 0.5f, elbowY),
            Offset(x - half, tipY),
            strokeWidth = w * 0.8f,
            cap = StrokeCap.Round,
        )
        drawLine(color, Offset(x, y), Offset(x + half * 0.5f, elbowY), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(
            color,
            Offset(x + half * 0.5f, elbowY),
            Offset(x + half, tipY),
            strokeWidth = w * 0.8f,
            cap = StrokeCap.Round,
        )
        if (shadow) return
        // 胴と頭、翼の上面の明るみ
        drawOval(color, Offset(x - span * 0.11f, y - span * 0.07f), Size(span * 0.22f, span * 0.14f))
        drawCircle(color, span * 0.06f, Offset(x + dir * span * 0.13f, y - span * 0.03f))
        drawLine(
            BIRD_LIGHT.copy(alpha = 0.7f),
            Offset(x - half * 0.5f, elbowY - w * 0.3f),
            Offset(x + half * 0.5f, elbowY - w * 0.3f),
            strokeWidth = w * 0.3f,
        )
    }

    /** ゆらめく炎（外・中・芯の 3 重の楕円）。根元が ([x], [y])、高さ [height]。[flick] は 0.5..1.1 ほどの揺れ。 */
    fun DrawScope.flame(
        x: Float,
        y: Float,
        height: Float,
        flick: Float,
        sway: Float,
    ) {
        val h = height * flick
        val w = height * 0.62f
        drawOval(FLAME_OUT, Offset(x - w / 2f + sway, y - h), Size(w, h))
        drawOval(FLAME_MID, Offset(x - w * 0.34f + sway * 0.6f, y - h * 0.74f), Size(w * 0.68f, h * 0.74f))
        drawOval(FLAME_CORE, Offset(x - w * 0.18f + sway * 0.3f, y - h * 0.44f), Size(w * 0.36f, h * 0.44f))
    }

    private const val FLICKER_BASE = 0.78f
    private const val BOB = 0.6f
    private const val STEP_X = 1.5f
    private const val STEP_Y = 0.75f
    private const val LIFT = 0.9f
    private const val FLAG_STRIP_PX = 2f
    private const val FLAG_STRIPS_MIN = 8
    private const val FLAG_STRIPS_MAX = 26
    private const val FLAG_SPEED = 5.2f
    private const val FLAG_WAVES = 1.15f
    private const val FLAG_AMP = 0.2f
    private const val FLAG_SAG = 0.16f
    private const val FLAG_OVERLAP = 0.6f
    private const val PENNANT_TAPER = 0.9f
    private const val SWALLOW_FROM = 0.72f
}
