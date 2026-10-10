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
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.OfferBadge
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.util.DurationFormat

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

/**
 * オファーのカード（04 §4 の演出: 取り消し線の定価、「95% OFF」、バッジ、残数、カウントダウン、大きく点滅する購入ボタン）。
 * [remainingSec] / [stockLeft] が null なら該当の表示を出さない。
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
    remainingSec: Long? = null,
    stockLeft: Int? = null,
    extraGems: Long = 0,
    soldOut: Boolean = false,
    offOverride: Int? = null,
) {
    val off = offOverride ?: offPercent(priceYen, listValueYen)
    Card(modifier = modifier.fillMaxWidth()) {
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
            ContentsLines(contents, data, extraGems = extraGems)
            if (off > 0) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.offer_list_value, formatYen(listValueYen)),
                        textDecoration = TextDecoration.LineThrough,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.offer_off, off),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                remainingSec?.let {
                    Text(
                        stringResource(R.string.offer_remaining, DurationFormat.clock(it)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                stockLeft?.let {
                    Text(stringResource(R.string.offer_stock_left, it), style = MaterialTheme.typography.labelMedium)
                }
            }
            Button(
                onClick = onBuy,
                enabled = !soldOut,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
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
