package com.ttmrase.crownfall.ui.title

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.theme.CrownFonts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.ttmrase.crownfall.ui.kit.rememberLoop

/**
 * タイトルの一枚絵。夕暮れの空・月・星・山並み・丘の上の城を、手続きの図形で描く（画像ファイルは使わない）。
 * 雲と星のまたたき、舞い上がる火の粉がゆっくり動く。アニメーションを切った端末では止め絵にする。
 */
@Composable
fun TitleBackdrop(modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val t = rememberLoop(LOOP_MS, "title")
    val stars = remember { List(STARS) { i -> Triple(hash(i * 3 + 1), hash(i * 3 + 2), hash(i * 3 + 3)) } }
    val embers = remember { List(EMBERS) { i -> Triple(hash(i * 7 + 11), hash(i * 7 + 13), hash(i * 7 + 17)) } }
    Box(modifier.fillMaxSize()) {
        // 動かない景色（空・月・山並み・城下町・丘）は 1 度だけ画像に焼いて、毎フレームは貼るだけにする。
        // 毎フレーム描くのは、星のまたたきと火の粉だけ（自分の層の中）
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val w = size.width.toInt().coerceAtLeast(1)
                val h = size.height.toInt().coerceAtLeast(1)
                val still = ImageBitmap(w, h)
                CanvasDrawScope().draw(this, layoutDirection, Canvas(still), size) {
                    sky()
                    moon()
                    ridge(0.50f, 0.085f, 3.1f, 0.6f, Color(0xFF3A2F7A), Color(0xFF2A2362), 0f)
                    mist(0.56f, Color(0x33FFB38A))
                    ridge(0.58f, 0.07f, 4.3f, 2.1f, Color(0xFF241D58), Color(0xFF17143F), 0f)
                    skyline(0.635f)
                    mist(0.66f, Color(0x26FF9A6B))
                    hill()
                    vignette()
                }
                onDrawBehind { drawImage(still) }
            },
        )
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val time = if (animate) t.value else 0.2f
            stars(stars, time)
            embers(embers, time)
        }
        // 丘の上の城（ゲーム内の最上段階の城のスプライト）と、その後ろでゆっくり回る光条
        Box(
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().offset(y = CASTLE_TOP.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                Modifier
                    .size(CASTLE_SIZE.dp)
                    .graphicsLayer { rotationZ = if (animate) t.value * FULL_TURN * RAY_TURNS else 0f }
                    .drawWithCache {
                        val c = Offset(size.width / 2, size.height / 2)
                        val r = size.width * 0.78f
                        val rays = Path()
                        for (i in 0 until RAYS) {
                            val a0 = (2 * PI * i / RAYS).toFloat()
                            val a1 = a0 + RAY_WIDTH
                            rays.moveTo(c.x, c.y)
                            rays.lineTo(c.x + r * cos(a0), c.y + r * sin(a0))
                            rays.lineTo(c.x + r * cos(a1), c.y + r * sin(a1))
                            rays.close()
                        }
                        val rayBrush = Brush.radialGradient(listOf(Color(0x40FFE9A8), Color.Transparent), c, r)
                        val glow =
                            Brush.radialGradient(listOf(Color(0x88FFD27A), Color(0x33FF8A4A), Color.Transparent), c, r)
                        onDrawBehind {
                            drawCircle(glow, r, c)
                            drawPath(rays, rayBrush)
                        }
                    },
            )
            Image(
                painter = painterResource(R.drawable.bld_castle_t5),
                contentDescription = null,
                modifier = Modifier.size(CASTLE_SIZE.dp),
            )
        }
    }
}

/** 題字。王冠の紋章の下に、金の「CROWNFALL」。暗い縁取りと落ち影で空の上でも読める。 */
@Composable
fun TitleLogo(
    name: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.drawBehind {
                val c = Offset(size.width / 2, size.height / 2)
                drawCircle(
                    Brush.radialGradient(listOf(Color(0x99FFD86A), Color.Transparent), c, size.width),
                    size.width,
                    c,
                )
            },
        ) { GlyphIcon(Glyph.VIP, size = 64.dp) }
        val base =
            TextStyle(
                fontFamily = CrownFonts.Numeric,
                fontWeight = FontWeight.Black,
                fontSize = 46.sp,
                lineHeight = 52.sp,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )
        Box(contentAlignment = Alignment.Center) {
            // 縁取り（太い暗い線）→ 金の面、の順に重ねる
            Text(
                name,
                style =
                    base.copy(
                        color = Color(0xFF2A1503),
                        drawStyle = Stroke(width = OUTLINE_PX, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                        shadow = Shadow(Color(0xCC000000), Offset(0f, 8f), 14f),
                    ),
                maxLines = 1,
                softWrap = false,
            )
            Text(
                name,
                style =
                    base.copy(
                        brush =
                            Brush.verticalGradient(
                                listOf(Color(0xFFFFFBE0), Cf.GoldHi, Cf.Gold, Color(0xFFE89A1C), Color(0xFFFFE08A)),
                            ),
                    ),
                maxLines = 1,
                softWrap = false,
            )
        }
        // 題字の下の飾り線
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(width = 220.dp, height = 12.dp)
                .drawBehind {
                    val cy = size.height / 2
                    val line =
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Cf.Gold, Cf.GoldHi, Cf.Gold, Color.Transparent),
                        )
                    drawLine(line, Offset(0f, cy), Offset(size.width, cy), 1.6.dp.toPx())
                    val d = 4.dp.toPx()
                    val gem =
                        Path().apply {
                            moveTo(size.width / 2, cy - d)
                            lineTo(size.width / 2 + d, cy)
                            lineTo(size.width / 2, cy + d)
                            lineTo(size.width / 2 - d, cy)
                            close()
                        }
                    drawPath(gem, Cf.GoldHi)
                    drawPath(gem, Cf.GoldDeep, style = Stroke(1.dp.toPx()))
                },
        )
    }
}

private fun DrawScope.sky() {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF070B24),
            0.28f to Color(0xFF1B1A55),
            0.48f to Color(0xFF5B2E78),
            0.60f to Color(0xFFC2523F),
            0.66f to Color(0xFFF2A14E),
            0.72f to Color(0xFF3A1F3F),
            1f to Color(0xFF05060F),
        ),
    )
}

private fun DrawScope.stars(
    stars: List<Triple<Float, Float, Float>>,
    time: Float,
) {
    stars.forEach { (x, y, k) ->
        val twinkle = 0.45f + 0.55f * (0.5f + 0.5f * sin(2 * PI.toFloat() * (time * TWINKLES + k)))
        val fade = (1f - y * 1.25f).coerceIn(0f, 1f)
        val r = (0.6f + 1.4f * k) * density
        drawCircle(Color.White.copy(alpha = twinkle * fade * 0.9f), r, Offset(x * size.width, y * size.height * 0.5f))
    }
}

private fun DrawScope.moon() {
    val c = Offset(size.width * 0.80f, size.height * 0.105f)
    val r = size.width * 0.075f
    drawCircle(Brush.radialGradient(listOf(Color(0x55DCE6FF), Color.Transparent), c, r * 4.2f), r * 4.2f, c)
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFFBEA), Color(0xFFDCE2F6)), c - Offset(r * 0.3f, r * 0.3f), r * 1.6f),
        r,
        c,
    )
    drawCircle(Color(0x22697AB0), r * 0.22f, c + Offset(r * 0.25f, -r * 0.2f))
    drawCircle(Color(0x22697AB0), r * 0.15f, c + Offset(-r * 0.3f, r * 0.3f))
}

/** 山並み 1 枚。sin を重ねた稜線の下を、上から下へ暗くなる色で塗る。 */
private fun DrawScope.ridge(
    base: Float,
    amp: Float,
    freq: Float,
    phase: Float,
    top: Color,
    bottom: Color,
    drift: Float,
) {
    val w = size.width
    val h = size.height
    val path = Path().apply { moveTo(0f, h) }
    var x = 0f
    val step = w / RIDGE_STEPS
    while (x <= w + step) {
        val u = x / w + drift
        val a = freq * u * PI.toFloat()
        val wave = 0.6f * sin(a + phase) + 0.3f * sin(a * 2.3f + phase * 1.7f) + 0.1f * sin(a * 5.1f)
        val y = base - amp * wave
        path.lineTo(x, y * h)
        x += step
    }
    path.lineTo(w, h)
    path.close()
    drawPath(path, Brush.verticalGradient(listOf(top, bottom), (base - amp) * h, (base + 0.2f) * h))
}

/** 遠くの城下町のシルエット（塔と屋根の並び）。 */
private fun DrawScope.skyline(base: Float) {
    val w = size.width
    val y0 = base * size.height
    val color = Color(0xFF120F33)
    var x = -4 * density
    var i = 0
    while (x < w) {
        val k = hash(i * 5 + 3)
        val bw = (10 + 16 * hash(i * 5 + 4)) * density
        val bh = (10 + 34 * k * k) * density
        drawRect(color, Offset(x, y0 - bh), Size(bw, bh + 40 * density))
        if (k > 0.45f) {
            val roof =
                Path().apply {
                    moveTo(x - 2 * density, y0 - bh)
                    lineTo(x + bw / 2, y0 - bh - bw * 0.9f)
                    lineTo(x + bw + 2 * density, y0 - bh)
                    close()
                }
            drawPath(roof, color)
        } else {
            // 鋸壁
            var mx = x
            while (mx < x + bw) {
                drawRect(color, Offset(mx, y0 - bh - 3 * density), Size(3 * density, 3 * density))
                mx += 6 * density
            }
        }
        // 灯りのついた窓
        if (hash(i * 5 + 6) > 0.35f) {
            drawRect(
                Color(0xFFFFD27A).copy(alpha = 0.85f),
                Offset(x + bw * 0.4f, y0 - bh * 0.55f),
                Size(2.2f * density, 3.4f * density),
            )
        }
        x += bw + (1 + 6 * hash(i * 5 + 7)) * density
        i++
    }
}

private fun DrawScope.mist(
    at: Float,
    color: Color,
) {
    val y = at * size.height
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, color, Color.Transparent), y - 40 * density, y + 40 * density),
        Offset(0f, y - 40 * density),
        Size(size.width, 80 * density),
    )
}

/** 手前の丘（城が立つ場所）。 */
private fun DrawScope.hill() {
    val w = size.width
    val h = size.height
    val top = h * 0.655f
    val path =
        Path().apply {
            moveTo(-w * 0.1f, h)
            lineTo(-w * 0.1f, top + h * 0.09f)
            cubicTo(w * 0.2f, top - h * 0.01f, w * 0.38f, top - h * 0.035f, w * 0.5f, top - h * 0.035f)
            cubicTo(w * 0.62f, top - h * 0.035f, w * 0.8f, top - h * 0.01f, w * 1.1f, top + h * 0.09f)
            lineTo(w * 1.1f, h)
            close()
        }
    drawPath(path, Brush.verticalGradient(listOf(Color(0xFF1D2A4A), Color(0xFF0A0F22), Color(0xFF05060F)), top, h))
    drawPath(path, Color(0x55FFB870), style = Stroke(1.5f * density))
}

private fun DrawScope.embers(
    embers: List<Triple<Float, Float, Float>>,
    time: Float,
) {
    embers.forEach { (x, phase, k) ->
        val p = (time * (EMBER_LAPS + (k * 3).toInt()) + phase) % 1f
        val y = size.height * (0.78f - 0.5f * p)
        val sway = sin((p * 3f + phase) * 2 * PI.toFloat()) * 14f * density
        val alpha = (if (p < 0.15f) p / 0.15f else 1f - (p - 0.15f) / 0.85f).coerceIn(0f, 1f)
        val c = Offset(x * size.width + sway, y)
        val r = (1.2f + 1.8f * k) * density
        drawCircle(Color(0xFFFFB04A).copy(alpha = alpha * 0.35f), r * 2.6f, c)
        drawCircle(Color(0xFFFFE9A8).copy(alpha = alpha * 0.9f), r, c)
    }
}

private fun DrawScope.vignette() {
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, Color(0x99000000)),
            Offset(size.width / 2, size.height * 0.45f),
            size.height * 0.75f,
        ),
    )
}

/** 0..1 の決定論の疑似乱数（描画のたびに同じ絵になる）。 */
private fun hash(i: Int): Float {
    var x = i * 0x9E3779B1.toInt()
    x = x xor (x ushr 15)
    x *= 0x85EBCA6B.toInt()
    x = x xor (x ushr 13)
    return (x and 0xFFFF) / 65535f
}

private const val LOOP_MS = 120_000
private const val STARS = 90
private const val EMBERS = 26
private const val EMBER_LAPS = 6
private const val TWINKLES = 40f
private const val RIDGE_STEPS = 72
private const val RAYS = 14
private const val RAY_WIDTH = 0.16f
private const val FULL_TURN = 360f
private const val RAY_TURNS = 3f
private const val CASTLE_TOP = 250
private const val CASTLE_SIZE = 330
private const val OUTLINE_PX = 9f
