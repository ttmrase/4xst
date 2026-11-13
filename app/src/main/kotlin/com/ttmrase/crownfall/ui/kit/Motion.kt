package com.ttmrase.crownfall.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.ui.common.animationsEnabled
import kotlin.math.PI
import kotlin.math.sin

// UI の「常に少し動いている」ための部品（docs/02 §21）。
// どれも自分の層（graphicsLayer）の中だけを動かす。層の位置・拡大・透明度の変更は描き直しを起こさないので、
// 画面全体を毎フレーム描き直さない。端末でアニメーションを切っているときは止まる。

/** 0 → 1 を [periodMs] で繰り返す値。アニメーションを切った端末では 0 のまま。描画の段か層の中で読むこと。 */
@Composable
fun rememberLoop(
    periodMs: Int,
    label: String = "loop",
): State<Float> {
    if (!animationsEnabled()) return remember { mutableFloatStateOf(0f) }
    return rememberInfiniteTransition(label = label).animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart),
        label = label,
    )
}

/** ゆっくり上下に浮く（主役の絵・気づいてほしいボタン）。[phase] で隣どうしの動きをずらす。 */
fun Modifier.idleBob(
    amplitude: Dp = 3.dp,
    periodMs: Int = 2600,
    phase: Float = 0f,
    enabled: Boolean = true,
): Modifier =
    composed {
        if (!enabled) return@composed this
        val t = rememberLoop(periodMs, "bob")
        graphicsLayer { translationY = sin((t.value + phase) * TWO_PI) * amplitude.toPx() }
    }

/** ゆっくり膨らんで戻る（受け取れる報酬・押してほしいボタン）。 */
fun Modifier.breathe(
    amount: Float = 0.04f,
    periodMs: Int = 1500,
    enabled: Boolean = true,
): Modifier =
    composed {
        if (!enabled) return@composed this
        val t = rememberLoop(periodMs, "breathe")
        graphicsLayer {
            val k = 1f + amount * (0.5f + 0.5f * sin(t.value * TWO_PI))
            scaleX = k
            scaleY = k
        }
    }

/**
 * 画面の地の上を、淡い光の粒がゆっくり立ちのぼる（一覧の画面の背景。全面を自分で描く城内・マップ・タイトルでは出さない）。
 * 自分の層に描くので、上に載る画面の中身は描き直さない。
 */
@Composable
fun BackdropMotes(modifier: Modifier = Modifier) {
    if (!animationsEnabled()) return
    val t = rememberLoop(MOTE_LOOP_MS, "motes")
    Canvas(modifier.fillMaxSize().graphicsLayer()) {
        val time = t.value
        for (i in 0 until MOTES) {
            // 粒ごとに決まった列・速さ・大きさ（疑似乱数は添字から。毎フレーム同じ並び）
            val seed = (i * 0.618034f) % 1f
            val lane = (i * 0.381966f + 0.07f) % 1f
            val laps = 1 + i % 3
            val p = (time * laps + seed) % 1f
            val x = size.width * lane + sin((p * 2f + seed) * TWO_PI) * 12.dp.toPx()
            val y = size.height * (1.05f - 1.1f * p)
            val fade = (if (p < FADE_IN) p / FADE_IN else 1f - (p - FADE_IN) / (1f - FADE_IN)).coerceIn(0f, 1f)
            val twinkle = 0.6f + 0.4f * sin((time * TWINKLES + seed) * TWO_PI)
            val r = (1.2f + 2.2f * seed).dp.toPx()
            val color = if (i % 4 == 0) MoteBlue else MoteGold
            drawCircle(color.copy(alpha = 0.10f * fade * twinkle), r * 3.2f, Offset(x, y))
            drawCircle(color.copy(alpha = 0.55f * fade * twinkle), r, Offset(x, y))
        }
    }
}

private val MoteGold = Color(0xFFFFE08A)
private val MoteBlue = Color(0xFF9CD3FF)
private const val MOTES = 22
private const val MOTE_LOOP_MS = 48_000
private const val TWINKLES = 30f
private const val FADE_IN = 0.15f
private const val TWO_PI = (2 * PI).toFloat()
