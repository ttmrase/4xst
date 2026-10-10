package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

private class Particle(
    val x: Float,
    val speed: Float,
    val size: Float,
    val hue: Float,
    val phase: Float,
    val spin: Float,
)

private const val PARTICLES = 70
private const val NANOS = 1_000_000_000f
private const val DURATION_SEC = 3.2f
private const val HUE_MAX = 360f

/** 紙吹雪（購入完了の演出）。約 3 秒で降り終わる。ゲーム状態には影響しない（Random は UI だけ）。 */
@Composable
fun Confetti(modifier: Modifier = Modifier) {
    val particles =
        remember {
            List(PARTICLES) {
                Particle(
                    Random.nextFloat(),
                    0.25f + Random.nextFloat() * 0.5f,
                    5f + Random.nextFloat() * 7f,
                    Random.nextFloat() * HUE_MAX,
                    Random.nextFloat(),
                    Random.nextFloat() * 6f,
                )
            }
        }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < DURATION_SEC) withFrameNanos { now -> t = (now - start) / NANOS }
    }
    if (t >= DURATION_SEC) return
    Canvas(modifier) {
        particles.forEach { p ->
            val progress = (t * p.speed + p.phase * 0.2f)
            val y = progress * size.height - 20f
            if (y in -20f..size.height) {
                val sway = kotlin.math.sin((t + p.spin) * 3f) * 18f
                drawRect(
                    color = Color.hsv(p.hue, 0.7f, 0.95f),
                    topLeft = Offset(p.x * size.width + sway, y),
                    size = Size(p.size * 1.6f, p.size),
                )
            }
        }
    }
}

/** アニメーションするチェックマーク（円 → ✓）。 */
@Composable
fun AnimatedCheck(
    modifier: Modifier = Modifier,
    color: Color,
    sizeDp: Dp = 72.dp,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(durationMillis = 700)) }
    Canvas(modifier.size(sizeDp)) {
        val stroke = Stroke(width = size.minDimension / 12f, cap = StrokeCap.Round)
        val p = progress.value
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 360f * (p * 2f).coerceAtMost(1f),
            useCenter = false,
            style = stroke,
        )
        val a = Offset(size.width * 0.28f, size.height * 0.52f)
        val b = Offset(size.width * 0.44f, size.height * 0.68f)
        val c = Offset(size.width * 0.74f, size.height * 0.34f)
        val tick = ((p - 0.5f) * 2f).coerceIn(0f, 1f)
        if (tick > 0f) {
            val first = (tick * 2f).coerceAtMost(1f)
            drawLine(
                color,
                a,
                Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first),
                stroke.width,
                StrokeCap.Round,
            )
            if (tick > 0.5f) {
                val second = (tick - 0.5f) * 2f
                drawLine(
                    color,
                    b,
                    Offset(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second),
                    stroke.width,
                    StrokeCap.Round,
                )
            }
        }
    }
}
