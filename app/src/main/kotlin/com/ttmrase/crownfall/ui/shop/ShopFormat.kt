package com.ttmrase.crownfall.ui.shop

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Offer
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.itemName
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.namedString
import java.text.NumberFormat
import java.util.Locale

private const val PERCENT = 100L

/** 成長ファンドの購入額を、基本のジェム SKU の換算（円/個）でジェムに直した「元が取れる」ライン。 */
fun fundPaybackGems(data: GameData): Long {
    val base = data.offers.gemSkus.firstOrNull() ?: return 0L
    if (base.priceYen <= 0 || base.gems <= 0) return 0L
    val fund = data.shopRules.growthFund.priceYen
    return (fund * base.gems + base.priceYen - 1) / base.priceYen
}

/** これまでに受け取った成長ファンドのジェム合計。 */
fun fundClaimedGems(
    state: GameState,
    data: GameData,
): Long =
    data.shopRules.growthFund.milestones
        .filter { it[0].toInt() in state.shop.growthFundClaimed }
        .sumOf { it[1] }

/** 今回 [newGems] を受け取ると、はじめて「元が取れる」か。 */
fun fundPaidBack(
    state: GameState,
    data: GameData,
    newGems: Long,
): Boolean {
    val payback = fundPaybackGems(data)
    val claimed = fundClaimedGems(state, data)
    return payback > 0 && claimed < payback && claimed + newGems >= payback
}

/** 円の表示（¥ とカンマ区切り）。 */
fun formatYen(yen: Long): String = "¥" + NumberFormat.getIntegerInstance(Locale.JAPAN).format(yen)

/** 定価に対する割引率（%。定価が価格以下なら 0）。 */
fun offPercent(
    priceYen: Long,
    listValueYen: Long,
): Int = if (listValueYen <= priceYen || listValueYen <= 0) 0 else (PERCENT - priceYen * PERCENT / listValueYen).toInt()

/** 商品名（パック / ジェム SKU / 特別商品）。復活したものには呼び出し側が接頭辞を付ける。 */
fun productName(
    context: Context,
    sku: String,
): String =
    if (sku.startsWith(
            "skin:",
        )
    ) {
        skinName(context, sku.removePrefix("skin:"))
    } else {
        namedString(context, "offer_name_", sku)
    }

/** オファーの一言（「あと n 石で建設可能！」など。トリガーごと）。無ければ null。 */
fun offerTagline(
    context: Context,
    offer: Offer,
): String? {
    val p = offer.params
    return when (offer.trigger) {
        "resourceShortage" -> {
            val index = (p["main"] ?: 0L).toInt().coerceIn(0, ResourceType.entries.lastIndex)
            val type = ResourceType.entries[index]
            val key = listOf("food", "wood", "stone", "gold")[index]
            context.getString(
                R.string.offer_tag_shortage,
                ShortNumber.format(p[key] ?: 0L),
                context.getString(type.nameRes()),
            )
        }
        "gemShortage" ->
            context.getString(
                R.string.offer_tag_gem_shortage,
                ShortNumber.format(p["missing"] ?: 0L),
                formatYen(p["yen"] ?: 0L),
            )
        "castleUp" -> context.getString(R.string.offer_tag_castle, (p["lv"] ?: 0L).toInt())
        "rankingNear" -> context.getString(R.string.offer_tag_ranking, (p["gap"] ?: 0L).toInt())
        "vipNear" -> context.getString(R.string.offer_tag_vip, (p["pt"] ?: 0L).toInt(), (p["vip"] ?: 0L).toInt())
        "defeated" -> context.getString(R.string.offer_tag_defeated)
        "longTimer" -> context.getString(R.string.offer_tag_long_timer)
        "comeback" -> context.getString(R.string.offer_tag_comeback)
        "login" -> context.getString(R.string.offer_tag_login)
        "exitIntent" -> context.getString(R.string.offer_tag_exit)
        "piggyFull" -> context.getString(R.string.offer_tag_piggy_full)
        "featureUnlock" -> context.getString(R.string.offer_tag_feature_unlock)
        "afterPurchase" -> context.getString(R.string.offer_tag_chain)
        "secondBuilder" -> context.getString(R.string.offer_tag_builder)
        else -> null
    }
}

/** パックの中身を 1 行ずつ並べる。 */
@Composable
fun ContentsLines(
    contents: PackContents,
    data: GameData,
    modifier: Modifier = Modifier,
    extraGems: Long = 0,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(modifier = modifier.padding(vertical = 2.dp)) {
        if (contents.gems > 0 || extraGems > 0) {
            Text(
                stringResource(R.string.content_gems, ShortNumber.format(contents.gems + extraGems)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ResourceType.entries.forEach { type ->
            val n = contents.resources[type]
            if (n > 0) {
                Text(
                    stringResource(R.string.content_resource, stringResource(type.nameRes()), ShortNumber.format(n)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        contents.items.forEach { (id, n) ->
            val def = data.items[id]
            val label = if (def != null) itemName(context, def) else id
            Text(stringResource(R.string.content_item, label, n), style = MaterialTheme.typography.bodyMedium)
        }
        contents.heroShards.forEach { (id, n) ->
            val res = heroNameRes(id)
            val label = if (res != null) stringResource(res) else id
            Text(stringResource(R.string.content_shards, label, n), style = MaterialTheme.typography.bodyMedium)
        }
        if (contents.eventPoints > 0) {
            Text(
                stringResource(R.string.content_event_points, ShortNumber.format(contents.eventPoints)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (contents.secondBuilderDays > 0) {
            Text(
                stringResource(R.string.content_builder, contents.secondBuilderDays),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
