package com.ttmrase.crownfall.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.DailyDeals
import com.ttmrase.crownfall.core.shop.GrowthFund
import com.ttmrase.crownfall.core.shop.MonthPass
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.PiggyBank
import com.ttmrase.crownfall.core.shop.ProductIds
import com.ttmrase.crownfall.core.shop.ShopLogic
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import kotlinx.coroutines.launch

private const val SECONDS_PER_DAY = 86_400L
private val TABS =
    listOf(
        R.string.shop_tab_recommended,
        R.string.shop_tab_gems,
        R.string.shop_tab_packs,
        R.string.shop_tab_deals,
        R.string.shop_tab_vip,
        R.string.shop_tab_pass,
    )

/** 今日の残数を使った分を除いた残り（日付が変わっていれば全部残っている）。 */
internal fun stockLeft(
    state: GameState,
    sku: String,
    daily: Int,
): Int {
    val used = if (state.shop.day == ShopLogic.localDay(state)) state.shop.stockUsed[sku] ?: 0 else 0
    return (daily - used).coerceAtLeast(0)
}

/**
 * ショップ画面（T6.3）。おすすめ / ジェム / パック / デイリーディール / VIP / 月パス。
 * 購入ボタンは [onBuy]（商品 ID）で決済シートを開く。ここでは通信せず、台帳も読まない。
 */
@Composable
fun ShopScreen(
    session: GameSession,
    data: GameData,
    snackbarHostState: SnackbarHostState,
    onBuy: (String) -> Unit,
) {
    val state by session.collectCalmState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val s = state ?: return
    // デイリー「ショップを開く」用。画面を開くたびに 1 回だけ数える
    var recorded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(recorded) {
        if (!recorded) {
            recorded = true
            session.dispatch { Actions.recordShopOpened(it) }
        }
    }

    var reward by remember { mutableStateOf<Triple<Int, PackContents, Boolean>?>(null) }

    fun act(
        shown: Triple<Int, PackContents, Boolean>? = null,
        block: (GameState) -> ActionResult,
    ) {
        scope.launch {
            val r = session.dispatch(block)
            if (r is ActionResult.Failure) {
                snackbarHostState.showReplacing(failureMessage(context, r.reason))
            } else if (shown != null) {
                reward = shown
            }
        }
    }
    reward?.let { (titleRes, contents, paidBack) ->
        RewardDialog(stringResource(titleRes), contents, data, paidBack) { reward = null }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.shop_gems, ShortNumber.format(s.gems)), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.shop_vip_short, com.ttmrase.crownfall.core.shop.VipCalculator.level(s, data)))
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(title)) })
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                0 -> recommended(s, data, session, snackbarHostState, onBuy)
                1 -> gems(s, data, onBuy)
                2 -> packs(s, data, onBuy)
                3 -> deals(s, data, onBuy)
                4 ->
                    vipTab(s, data) {
                        act(Triple(R.string.vip_chest_open_title, VipCalculator.chest(s, data), false)) { st ->
                            Actions.claimVipChest(st, data)
                        }
                    }
                else ->
                    passTab(
                        s,
                        data,
                        onBuy,
                        onClaimPass = {
                            val daily = MonthPass.daily(data)
                            act(
                                Triple(R.string.pass_claim_title, daily, false),
                            ) { st -> Actions.claimMonthPass(st, data) }
                        },
                        onClaimFund = {
                            val gems = GrowthFund.claimable(s, data).sumOf { it.second }
                            val paid = fundPaidBack(s, data, gems)
                            act(Triple(R.string.reward_chest_title, PackContents(gems = gems), paid)) { st ->
                                Actions.claimGrowthFund(st, data)
                            }
                        },
                    )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.recommended(
    s: GameState,
    data: GameData,
    session: GameSession,
    snackbarHostState: SnackbarHostState,
    onBuy: (String) -> Unit,
) {
    val offers = OfferEngine.active(s).asReversed()
    items(offers, key = { "offer-${it.id}" }) { o ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val name = productName(context, o.sku)
        OfferCard(
            title = if (o.revived) stringResource(R.string.offer_revived, name) else name,
            tagline = offerTagline(context, o),
            contents = o.contents,
            data = data,
            priceYen = o.priceYen,
            listValueYen = o.listValueYen,
            badge = o.badge,
            expiresEpochSec = o.expiresEpochSec,
            nowEpochSec = s.nowEpochSec,
            stockLeft = data.offers.pack(o.sku)?.let { stockLeft(s, o.sku, it.dailyStock) },
            onBuy = { onBuy(ProductIds.offer(o.id)) },
        )
    }
    item { PiggyCard(s, data, onBuy) }
    item { WheelCard(s, data, session, snackbarHostState) { onBuy(data.shopRules.wheel.extraSku) } }
}

@Composable
private fun PiggyCard(
    s: GameState,
    data: GameData,
    onBuy: (String) -> Unit,
) {
    val rules = data.shopRules.piggy
    val full = PiggyBank.isFull(s, data)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.piggy_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (full) {
                    Text(
                        stringResource(R.string.piggy_full),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.pulsing(),
                    )
                }
            }
            Text(
                stringResource(
                    R.string.piggy_amount,
                    ShortNumber.format(s.shop.piggyGems),
                    ShortNumber.format(rules.cap),
                ),
            )
            LinearProgressIndicator(progress = {
                (s.shop.piggyGems.toFloat() / rules.cap.toFloat()).coerceIn(0f, 1f)
            }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.piggy_note), style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { onBuy(rules.sku) },
                enabled = s.shop.piggyGems > 0,
                modifier = Modifier.fillMaxWidth().pulsing(full),
            ) { Text(stringResource(R.string.piggy_open, formatYen(rules.openYen))) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.gems(
    s: GameState,
    data: GameData,
    onBuy: (String) -> Unit,
) {
    items(data.offers.gemSkus, key = { it.sku }) { g ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val first = g.firstDouble && g.sku !in s.shop.firstPurchased
        OfferCard(
            title = productName(context, g.sku),
            tagline = if (first) stringResource(R.string.billing_first_double) else null,
            contents = PackContents(gems = g.gems + g.bonus),
            data = data,
            priceYen = g.priceYen,
            listValueYen = g.priceYen,
            badge = badgeFor(g.sku),
            extraGems = if (first) g.gems else 0,
            extraRibbons =
                buildList {
                    if (first) add(stringResource(R.string.ribbon_first_double))
                    if (g.bonus > 0) add(stringResource(R.string.ribbon_gem_bonus, g.bonus))
                },
            onBuy = { onBuy(g.sku) },
        )
    }
}

private fun badgeFor(sku: String) =
    when (sku) {
        "gems_m" -> com.ttmrase.crownfall.core.data.OfferBadge.RECOMMENDED
        "gems_l" -> com.ttmrase.crownfall.core.data.OfferBadge.POPULAR
        "gems_xxl" -> com.ttmrase.crownfall.core.data.OfferBadge.BEST_VALUE
        else -> com.ttmrase.crownfall.core.data.OfferBadge.NONE
    }

private fun androidx.compose.foundation.lazy.LazyListScope.packs(
    s: GameState,
    data: GameData,
    onBuy: (String) -> Unit,
) {
    items(data.offers.packs.filter { it.shelf }, key = { it.id }) { p ->
        val context = androidx.compose.ui.platform.LocalContext.current
        val left = stockLeft(s, p.id, p.dailyStock)
        OfferCard(
            title = productName(context, p.id),
            tagline = null,
            contents = p.contents,
            data = data,
            priceYen = p.priceYen,
            listValueYen = p.listValueYen,
            badge = p.badge,
            stockLeft = left,
            soldOut = left <= 0,
            onBuy = { onBuy(p.id) },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.deals(
    s: GameState,
    data: GameData,
    onBuy: (String) -> Unit,
) {
    val cards = DailyDeals.today(s, data)
    val off = s.settings.utcOffsetSec
    val midnight = (ShopLogic.localDay(s) + 1) * SECONDS_PER_DAY - off
    item { Text(stringResource(R.string.deals_note), style = MaterialTheme.typography.bodyMedium) }
    items(cards, key = { it.pack.id }) { c ->
        val context = androidx.compose.ui.platform.LocalContext.current
        OfferCard(
            title =
                if (c.isSuper) {
                    stringResource(
                        R.string.deal_super_title,
                        c.offPct,
                    )
                } else {
                    productName(context, c.pack.id)
                },
            tagline = if (c.isSuper) productName(context, c.pack.id) else null,
            contents = c.pack.contents,
            data = data,
            priceYen = c.priceYen,
            listValueYen = c.listValueYen,
            badge = c.pack.badge,
            expiresEpochSec = midnight,
            nowEpochSec = s.nowEpochSec,
            stockLeft = if (c.soldOut) 0 else 1,
            soldOut = c.soldOut,
            offOverride = c.offPct,
            onBuy = { onBuy(ProductIds.deal(c.pack.id)) },
        )
    }
}
