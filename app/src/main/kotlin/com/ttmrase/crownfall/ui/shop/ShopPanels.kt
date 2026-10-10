package com.ttmrase.crownfall.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.shop.GrowthFund
import com.ttmrase.crownfall.core.shop.MonthPass
import com.ttmrase.crownfall.core.shop.ShopLogic
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.util.ShortNumber

private const val HOUR = 3600L

private fun percentText(bp: Long): String = if (bp % 100 == 0L) (bp / 100).toString() else "%.1f".format(bp / 100.0)

@Composable
private fun PerkLines(
    gatherBp: Long,
    offlineBonusSec: Long,
    gemSkipBp: Long,
    secondBuilder: Boolean,
) {
    Text(stringResource(R.string.vip_perk_gather, percentText(gatherBp)), style = MaterialTheme.typography.bodyMedium)
    if (offlineBonusSec > 0) {
        Text(
            stringResource(R.string.vip_perk_offline, (offlineBonusSec / HOUR).toInt()),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (gemSkipBp > 0) {
        Text(
            stringResource(R.string.vip_perk_gem_skip, percentText(gemSkipBp)),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (secondBuilder) Text(stringResource(R.string.vip_perk_builder), style = MaterialTheme.typography.bodyMedium)
}

internal fun LazyListScope.vipTab(
    s: GameState,
    data: GameData,
    onClaimChest: () -> Unit,
) {
    val lv = VipCalculator.level(s, data)
    val next = VipCalculator.toNext(s, data)
    val perks = VipCalculator.perks(s, data)
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (lv > 0) stringResource(R.string.vip_current, lv) else stringResource(R.string.vip_none),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.vip_points, ShortNumber.format(s.shop.vipPoints)))
                if (next != null) {
                    val prev = if (lv > 0) data.vip.levels[lv - 1].pt else 0L
                    val target = data.vip.levels[next.first - 1].pt
                    LinearProgressIndicator(
                        progress = {
                            ((s.shop.vipPoints - prev).toFloat() / (target - prev).toFloat()).coerceIn(
                                0f,
                                1f,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.vip_to_next, ShortNumber.format(next.second), next.first))
                } else {
                    Text(stringResource(R.string.vip_max))
                }
                Text(stringResource(R.string.vip_streak, s.shop.loginStreak))
                if (perks != null) {
                    PerkLines(perks.gatherBp, perks.offlineBonusSec, perks.gemSkipBp, perks.secondBuilder)
                    val claimedToday = s.shop.vipChestDay == ShopLogic.localDay(s)
                    Button(onClick = onClaimChest, enabled = !claimedToday, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (claimedToday) R.string.vip_chest_done else R.string.vip_chest_claim))
                    }
                    ContentsLines(perks.chest, data)
                }
                Text(stringResource(R.string.vip_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    items(data.vip.levels, key = { "vip-${it.lv}" }) { l ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(R.string.vip_current, l.lv),
                        fontWeight = if (l.lv <= lv) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        stringResource(R.string.vip_need_pt, ShortNumber.format(l.pt)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PerkLines(l.gatherBp, l.offlineBonusSec, l.gemSkipBp, l.secondBuilder)
            }
        }
    }
}

internal fun LazyListScope.passTab(
    s: GameState,
    data: GameData,
    onBuy: (String) -> Unit,
    onClaimPass: () -> Unit,
    onClaimFund: () -> Unit,
) {
    val mp = data.shopRules.monthPass
    val gf = data.shopRules.growthFund
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.pass_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.pass_benefit_daily, mp.dailyGems))
                Text(stringResource(R.string.pass_benefit_builder))
                Text(stringResource(R.string.pass_days, mp.days))
                Text(
                    stringResource(R.string.offer_list_value, formatYen(mp.listValueYen)),
                    textDecoration = TextDecoration.LineThrough,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (MonthPass.active(s)) {
                    Text(
                        stringResource(R.string.pass_days_left, MonthPass.daysLeft(s)),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = onClaimPass, enabled = MonthPass.canClaim(s), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (MonthPass.canClaim(s)) R.string.pass_claim else R.string.pass_claimed))
                    }
                }
                Button(onClick = { onBuy(mp.sku) }, modifier = Modifier.fillMaxWidth().pulsing(!MonthPass.active(s))) {
                    Text(
                        stringResource(
                            if (MonthPass.active(s)) R.string.pass_extend else R.string.pass_buy,
                            formatYen(mp.priceYen),
                        ),
                    )
                }
            }
        }
    }
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.fund_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.fund_total, ShortNumber.format(GrowthFund.totalGems(data))),
                    color = MaterialTheme.colorScheme.error,
                )
                gf.milestones.forEach { m ->
                    val lv = m[0].toInt()
                    val claimed = lv in s.shop.growthFundClaimed
                    Text(
                        stringResource(if (claimed) R.string.fund_row_claimed else R.string.fund_row, lv, m[1]),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                val claimable = GrowthFund.claimable(s, data)
                if (s.shop.growthFundOwned) {
                    OutlinedButton(
                        onClick = onClaimFund,
                        enabled = claimable.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.fund_claim, claimable.sumOf { it.second }))
                    }
                } else {
                    Button(onClick = { onBuy(gf.sku) }, modifier = Modifier.fillMaxWidth().pulsing()) {
                        Text(stringResource(R.string.fund_buy, formatYen(gf.priceYen)))
                    }
                }
            }
        }
    }
}
