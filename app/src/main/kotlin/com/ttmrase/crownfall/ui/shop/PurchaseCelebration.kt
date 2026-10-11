package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.glyph
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

private const val COINS = 46
private const val NANOS = 1_000_000_000f
private const val SHOWER_SEC = 3.4f
private const val FLY_MS = 900
private const val FLY_STAGGER_MS = 160L
private const val CHEST_SFX_DELAY_MS = 350L
private const val COIN_SFX_DELAY_MS = 180L

private class Coin(
    val x: Float,
    val speed: Float,
    val radius: Float,
    val phase: Float,
)

/** 金貨の雨（購入完了）。下で一度弾んでから消える。UI だけの乱数で、ゲーム状態には影響しない。 */
@Composable
fun CoinShower(modifier: Modifier = Modifier) {
    val coins =
        remember {
            List(COINS) {
                Coin(
                    Random.nextFloat(),
                    0.35f + Random.nextFloat() * 0.5f,
                    7f + Random.nextFloat() * 6f,
                    Random.nextFloat(),
                )
            }
        }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (t < SHOWER_SEC) withFrameNanos { now -> t = (now - start) / NANOS }
    }
    if (t >= SHOWER_SEC) return
    Canvas(modifier) {
        coins.forEach { c ->
            val fall = (t * c.speed + c.phase * 0.3f) % 1.6f
            val y = if (fall < 1f) fall * size.height else size.height - abs(sin((fall - 1f) * 5f)) * size.height * 0.1f
            val squash = abs(sin((t + c.phase) * 6f))
            val center = Offset(c.x * size.width, y)
            drawOval(
                color = FoilDark,
                topLeft = center - Offset(c.radius, c.radius * squash.coerceAtLeast(0.25f)),
                size = androidx.compose.ui.geometry.Size(c.radius * 2f, c.radius * 2f * squash.coerceAtLeast(0.25f)),
            )
            drawOval(
                color = FoilGold,
                topLeft = center - Offset(c.radius * 0.8f, c.radius * 0.8f * squash.coerceAtLeast(0.25f)),
                size =
                    androidx.compose.ui.geometry.Size(
                        c.radius * 1.6f,
                        c.radius * 1.6f * squash.coerceAtLeast(0.25f),
                    ),
            )
        }
    }
}

/**
 * 購入完了の祝福: ファンファーレ → 宝箱が開く → 中身のアイコンが所持品（🎒）へ飛んでいく。
 * 金貨の雨は呼び出し側の全面オーバーレイ（[CoinShower]）で降らせる。
 */
@Composable
fun PurchaseCelebration(
    contents: PackContents,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        Sfx.play(context, SfxId.FANFARE)
        delay(COIN_SFX_DELAY_MS)
        Sfx.play(context, SfxId.COIN)
        delay(CHEST_SFX_DELAY_MS - COIN_SFX_DELAY_MS)
        Sfx.play(context, SfxId.CHEST)
    }
    val glyphs = remember(contents) { flyingGlyphs(contents) }
    Box(modifier = modifier.fillMaxWidth().height(170.dp)) {
        TreasureChest(modifier = Modifier.align(Alignment.TopCenter))
        Text("🎒", fontSize = 30.sp, modifier = Modifier.align(Alignment.BottomEnd))
        glyphs.forEachIndexed { i, glyph -> FlyingIcon(glyph, delayMs = 700L + i * FLY_STAGGER_MS) }
    }
}

private fun flyingGlyphs(contents: PackContents): List<String> {
    val list = mutableListOf<String>()
    if (contents.gems > 0) list += "💎"
    ResourceType.entries.forEach { if (contents.resources[it] > 0) list += it.glyph() }
    if (contents.items.isNotEmpty()) list += "🎁"
    if (contents.heroShards.isNotEmpty()) list += "⭐"
    return list.take(6)
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.FlyingIcon(
    glyph: String,
    delayMs: Long,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        progress.animateTo(1f, tween(FLY_MS, easing = FastOutSlowInEasing))
    }
    val p = progress.value
    if (p <= 0f || p >= 1f) return
    Text(
        glyph,
        style = TextStyle(fontSize = 26.sp),
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .offset(x = (150f * p).dp, y = (40f + 110f * p).dp)
                .graphicsLayer {
                    val scale = 1f - 0.6f * p
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - p * p
                },
    )
}
