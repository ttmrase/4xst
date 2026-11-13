package com.ttmrase.crownfall.ui.shop

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.ItemDef
import com.ttmrase.crownfall.core.data.ItemKind
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Offer
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.itemName
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.namedString
import com.ttmrase.crownfall.ui.theme.CrownFonts
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

/** 中身 1 つ分の札（アイコン・量・名前）。 */
private class RewardTile(
    val glyph: Glyph,
    val amount: String,
    val label: String? = null,
    val describe: String,
)

/** アイテムの種類ごとのアイコン。 */
fun ItemDef.glyph(): Glyph =
    when (kind) {
        ItemKind.SPEEDUP -> Glyph.SPEEDUP
        ItemKind.HERO_EXP -> Glyph.EXP
        ItemKind.SKILL_BOOK -> Glyph.BOOK
        ItemKind.SUMMON_TICKET_NORMAL, ItemKind.SUMMON_TICKET_PREMIUM -> Glyph.TICKET
    }

/**
 * パックや報酬の中身を、アイコンつきの札で並べる（ジェム → 資源 → アイテム → 欠片 → イベントスコア → ビルダー）。
 * 幅に入らない分は折り返す。読み上げには「ジェム +60」などの文を付ける。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentsLines(
    contents: PackContents,
    data: GameData,
    modifier: Modifier = Modifier,
    extraGems: Long = 0,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tiles = mutableListOf<RewardTile>()
    if (contents.gems > 0 || extraGems > 0) {
        val n = ShortNumber.format(contents.gems + extraGems)
        tiles += RewardTile(Glyph.GEM, "+$n", describe = stringResource(R.string.content_gems, n))
    }
    ResourceType.entries.forEach { type ->
        val n = contents.resources[type]
        if (n > 0) {
            val text = ShortNumber.format(n)
            tiles +=
                RewardTile(
                    type.glyph(),
                    "+$text",
                    describe = stringResource(R.string.content_resource, stringResource(type.nameRes()), text),
                )
        }
    }
    contents.items.forEach { (id, n) ->
        val def = data.items[id]
        val label = if (def != null) itemName(context, def) else id
        tiles +=
            RewardTile(def?.glyph() ?: Glyph.GIFT, "×$n", label, stringResource(R.string.content_item, label, n))
    }
    contents.heroShards.forEach { (id, n) ->
        val res = heroNameRes(id)
        val label = if (res != null) stringResource(res) else id
        tiles += RewardTile(Glyph.SHARD, "×$n", label, stringResource(R.string.content_shards, label, n))
    }
    if (contents.eventPoints > 0) {
        val n = ShortNumber.format(contents.eventPoints)
        tiles += RewardTile(Glyph.TROPHY, "+$n", describe = stringResource(R.string.content_event_points, n))
    }
    if (contents.secondBuilderDays > 0) {
        tiles +=
            RewardTile(
                Glyph.BUILD,
                "+${contents.secondBuilderDays}",
                describe = stringResource(R.string.content_builder, contents.secondBuilderDays),
            )
    }
    if (tiles.isEmpty()) return
    FlowRow(
        modifier = modifier.padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tiles.forEach { tile ->
            Column(
                modifier =
                    Modifier
                        .width(if (tile.label != null) LABEL_TILE_DP.dp else TILE_DP.dp)
                        .gameFrame(FrameStyle.Inset, corner = 10.dp)
                        .semantics(mergeDescendants = true) { contentDescription = tile.describe }
                        .padding(horizontal = 3.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GlyphIcon(tile.glyph, size = 30.dp)
                Text(
                    tile.amount,
                    color = Cf.Ivory,
                    fontFamily = CrownFonts.Numeric,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    lineHeight = 16.sp,
                    maxLines = 1,
                )
                if (tile.label != null) {
                    Text(
                        tile.label,
                        color = Cf.Muted,
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val TILE_DP = 58
private const val LABEL_TILE_DP = 84
