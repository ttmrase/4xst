package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.OfferBadge
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.ui.common.remainingUntil
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GameButtonStyle
import com.ttmrase.crownfall.ui.kit.GamePanel
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.theme.CrownFonts
import com.ttmrase.crownfall.ui.theme.PressButton

private const val PULSE_SCALE = 1.05f
private const val PULSE_MS = 650

/** 購入ボタンの拡大縮小（確認優位 UI。大きく点滅させる）。 */
fun Modifier.pulsing(enabled: Boolean = true): Modifier =
    if (!enabled) {
        this
    } else {
        composed {
            val transition = rememberInfiniteTransition(label = "pulse")
            val scale by transition.animateFloat(
                initialValue = 1f,
                targetValue = PULSE_SCALE,
                animationSpec = infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse),
                label = "pulseScale",
            )
            graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
        }
    }

/** バッジの文言。 */
@Composable
fun badgeLabel(badge: OfferBadge): String? =
    when (badge) {
        OfferBadge.NONE -> null
        OfferBadge.RECOMMENDED -> stringResource(R.string.badge_recommended)
        OfferBadge.POPULAR -> stringResource(R.string.badge_popular)
        OfferBadge.BEST_VALUE -> stringResource(R.string.badge_best_value)
    }

/** 残り時間の行。毎秒の時計（LocalClock）はこの小さな Composable だけが読む（カード全体は再構図しない）。 */
@Composable
private fun Countdown(
    expiresEpochSec: Long,
    fallbackNow: Long,
) {
    val remaining = remainingUntil(expiresEpochSec, fallbackNow)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.offer_remaining_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        FlipCountdown(remaining)
    }
}

/**
 * オファーのカード（04 §4 の演出: 取り消し線の定価、「95% OFF」、バッジ、残数、カウントダウン、大きく点滅する購入ボタン）。
 * [expiresEpochSec] / [stockLeft] が null なら該当の表示を出さない。
 */
@Composable
fun OfferCard(
    title: String,
    tagline: String?,
    contents: PackContents,
    data: GameData,
    priceYen: Long,
    listValueYen: Long,
    badge: OfferBadge,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
    expiresEpochSec: Long? = null,
    nowEpochSec: Long = 0L,
    stockLeft: Int? = null,
    extraGems: Long = 0,
    soldOut: Boolean = false,
    offOverride: Int? = null,
    oneTime: Boolean = false,
    extraRibbons: List<String> = emptyList(),
    foil: Boolean = true,
) {
    val off = offOverride ?: offPercent(priceYen, listValueYen)
    GamePanel(
        modifier = modifier.fillMaxWidth().then(if (foil) Modifier.goldFoil() else Modifier),
        style = if (foil) FrameStyle.Royal else FrameStyle.Panel,
        corner = 16.dp,
        ornate = foil,
    ) {
        // 題字の帯（左に商品名、右にバッジ）
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color(0x66FFD04A), Color(0x22FFD04A), Color.Transparent)),
                            size = Size(size.width, size.height),
                        )
                        drawLine(
                            Brush.horizontalGradient(listOf(Cf.Gold, Color.Transparent)),
                            Offset(0f, size.height),
                            Offset(size.width, size.height),
                            1.dp.toPx(),
                        )
                    }
                    .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        brush = Cf.GoldTextBrush,
                        shadow = Shadow(Cf.Ink, Offset(0f, 2f), 3f),
                    ),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            badgeLabel(badge)?.let {
                Row(
                    modifier =
                        Modifier
                            .gameFrame(FrameStyle.Alarm, corner = 9.dp)
                            .padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.STAR, size = 13.dp)
                    Text(
                        it,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            tagline?.let {
                Text(
                    it,
                    color = Color(0xFFFFB3A8),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            ContentsLines(contents, data, extraGems = extraGems)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (off > 0) Ribbon(stringResource(R.string.offer_off, off))
                if (oneTime) Ribbon(stringResource(R.string.offer_one_time))
                stockLeft?.let { Ribbon(stringResource(R.string.offer_stock_ribbon, it)) }
                extraRibbons.forEach { Ribbon(it) }
            }
            if (off > 0) {
                RollingPrice(listYen = listValueYen, dealYen = priceYen)
                Text(
                    stringResource(R.string.offer_list_value, formatYen(listValueYen)),
                    style = MaterialTheme.typography.bodySmall,
                    color = Cf.Muted,
                )
            }
            expiresEpochSec?.let { Countdown(it, nowEpochSec) }
            PressButton(
                onClick = onBuy,
                enabled = !soldOut,
                style = GameButtonStyle.Ruby,
                modifier = Modifier.fillMaxWidth().height(56.dp).pulsing(!soldOut),
            ) {
                Text(
                    if (soldOut) stringResource(R.string.offer_sold_out) else formatYen(priceYen),
                    fontFamily = CrownFonts.Numeric,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                )
            }
        }
    }
}
