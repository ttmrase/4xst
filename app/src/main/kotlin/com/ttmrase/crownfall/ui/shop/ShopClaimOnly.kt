package com.ttmrase.crownfall.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.GrowthFund
import com.ttmrase.crownfall.core.shop.MonthPass
import com.ttmrase.crownfall.core.shop.ShopLogic
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.collectCalmState
import kotlinx.coroutines.launch

/**
 * ショップが未解放でも、古いセーブで買ってある権利（月パスの毎日・VIP チェスト・成長ファンド）だけは受け取れる
 * （「受け取り」だけの画面。権利が無ければ何も出さない）。M11 T11.1 の差し戻し T9。
 */
@Composable
fun ShopClaimOnly(
    session: GameSession,
    data: GameData,
) {
    val state by session.collectCalmState()
    val s = ShopLogic.rollDay(state ?: return)
    val scope = rememberCoroutineScope()
    var shown by remember { mutableStateOf<Pair<Int, PackContents>?>(null) }
    shown?.let { (title, contents) -> RewardDialog(stringResource(title), contents, data, false) { shown = null } }
    val pass = MonthPass.active(s) && MonthPass.canClaim(s)
    val chest = VipCalculator.chest(s, data).takeIf { !it.isEmpty && s.shop.vipChestDay != ShopLogic.localDay(s) }
    val fund = if (s.shop.growthFundOwned) GrowthFund.claimable(s, data) else emptyList()
    if (!pass && chest == null && fund.isEmpty()) return

    fun claim(
        title: Int,
        contents: PackContents,
        action: (com.ttmrase.crownfall.core.model.GameState) -> ActionResult,
    ) {
        scope.launch { if (session.dispatch(action) is ActionResult.Success) shown = title to contents }
    }
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.shop_claim_only_title), style = MaterialTheme.typography.titleMedium)
        if (pass) {
            Button(
                onClick = {
                    claim(
                        R.string.pass_claim_title,
                        MonthPass.daily(data),
                    ) { Actions.claimMonthPass(it, data) }
                },
            ) {
                Text(stringResource(R.string.shop_claim_pass))
            }
        }
        chest?.let { c ->
            Button(onClick = { claim(R.string.vip_chest_open_title, c) { Actions.claimVipChest(it, data) } }) {
                Text(stringResource(R.string.shop_claim_vip_chest))
            }
        }
        if (fund.isNotEmpty()) {
            val gems = fund.sumOf { it.second }
            Button(onClick = {
                claim(R.string.reward_chest_title, PackContents(gems = gems)) { Actions.claimGrowthFund(it, data) }
            }) {
                Text(stringResource(R.string.shop_claim_fund))
            }
        }
    }
}
