package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.util.ShortNumber
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
private const val CHEST_SFX_DELAY_MS = 600L
private const val COIN_SFX_DELAY_MS = 350L
private const val ICONS_START_MS = 1500L
private const val CARD_STAGGER_MS = 220L
private const val CARD_HOLD_MS = 900L
private const val MAX_CARDS = 6

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
        delay(CHEST_SFX_DELAY_MS)
        Sfx.play(context, SfxId.CHEST) // ひびが入って光がもれる瞬間
        delay(COIN_SFX_DELAY_MS)
        Sfx.play(context, SfxId.COIN)
    }
    val cards = remember(contents) { celebrationCards(contents) }
    val glyphs = remember(cards) { cards.map { it.glyph } }
    // 宝箱が開いたあと、中身のカードが 1 枚ずつ並び、少し見せてから所持品へ飛ぶ
    val flyStart = ICONS_START_MS + cards.size * CARD_STAGGER_MS + CARD_HOLD_MS
    Box(modifier = modifier.fillMaxWidth().height(190.dp)) {
        TreasureChest(modifier = Modifier.align(Alignment.TopCenter))
        Text("🎒", fontSize = 30.sp, modifier = Modifier.align(Alignment.CenterEnd))
        Row(
            modifier = Modifier.align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            cards.forEachIndexed { i, card -> ItemCard(card, delayMs = ICONS_START_MS + i * CARD_STAGGER_MS) }
        }
        glyphs.forEachIndexed { i, glyph -> FlyingIcon(glyph, delayMs = flyStart + i * FLY_STAGGER_MS) }
    }
}

/** 並べる 1 枚のカード（絵文字と量）。 */
data class CelebrationCard(
    val glyph: String,
    val amount: String,
)

/** 宝箱の中身のカード（ジェム → 資源 → アイテム → 英雄のかけら。最大 6 枚）。純関数。 */
fun celebrationCards(contents: PackContents): List<CelebrationCard> {
    val list = mutableListOf<CelebrationCard>()
    if (contents.gems > 0) list += CelebrationCard("💎", ShortNumber.format(contents.gems))
    ResourceType.entries.forEach {
        if (contents.resources[it] > 0) list += CelebrationCard(it.glyph(), ShortNumber.format(contents.resources[it]))
    }
    if (contents.items.isNotEmpty()) list += CelebrationCard("🎁", contents.items.values.sum().toString())
    if (contents.heroShards.isNotEmpty()) list += CelebrationCard("⭐", contents.heroShards.values.sum().toString())
    return list.take(MAX_CARDS)
}

@Composable
private fun ItemCard(
    card: CelebrationCard,
    delayMs: Long,
) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
    }
    val p = pop.value
    if (p <= 0f) return
    Column(
        modifier =
            Modifier
                .graphicsLayer {
                    scaleX = p
                    scaleY = p
                    alpha = p.coerceIn(0f, 1f)
                }
                .background(Color(0x66FFFFFF), RoundedCornerShape(8.dp))
                .border(1.dp, FoilGold, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(card.glyph, fontSize = 20.sp)
        Text(card.amount, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))
    }
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
                .offset(x = (150f * p).dp, y = (60f + 40f * p).dp)
                .graphicsLayer {
                    val scale = 1f - 0.6f * p
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - p * p
                },
    )
}
