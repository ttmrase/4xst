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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.OfferBadge
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.ui.common.remainingUntil
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
    Card(modifier = modifier.fillMaxWidth().then(if (foil) Modifier.goldFoil() else Modifier)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                badgeLabel(badge)?.let {
                    Surface(color = MaterialTheme.colorScheme.tertiary, shape = MaterialTheme.shapes.small) {
                        Text(
                            it,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onTertiary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            tagline?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ContentIcons(contents)
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
                )
            }
            expiresEpochSec?.let { Countdown(it, nowEpochSec) }
            PressButton(
                onClick = onBuy,
                enabled = !soldOut,
                colors = ButtonDefaults.buttonColors(containerColor = BuyRed, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp).pulsing(!soldOut),
            ) {
                Text(
                    if (soldOut) stringResource(R.string.offer_sold_out) else formatYen(priceYen),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}
