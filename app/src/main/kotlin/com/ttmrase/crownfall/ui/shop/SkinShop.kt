package com.ttmrase.crownfall.ui.shop

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.SkinCategory
import com.ttmrase.crownfall.core.data.SkinDef
import com.ttmrase.crownfall.core.data.SkinRarity
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.SkinBagEntry
import com.ttmrase.crownfall.core.shop.ProductIds
import com.ttmrase.crownfall.core.skin.Skins
import com.ttmrase.crownfall.ui.rarityColor
import androidx.compose.foundation.Image as ComposeImage

/** スキン名（文字列 `skin_<id>`。縮小で消えないよう keep.xml に載せている）。 */
fun skinName(
    context: Context,
    id: String,
): String =
    context.resources.getIdentifier("skin_$id", "string", context.packageName).takeIf { it != 0 }
        ?.let { context.getString(it) } ?: id

private fun categoryName(c: SkinCategory): Int =
    when (c) {
        SkinCategory.CASTLE -> R.string.skin_cat_castle
        SkinCategory.TERRITORY -> R.string.skin_cat_territory
        SkinCategory.TRAIL -> R.string.skin_cat_trail
        SkinCategory.FRAME -> R.string.skin_cat_frame
        SkinCategory.NAMEPLATE -> R.string.skin_cat_nameplate
        SkinCategory.BUBBLE -> R.string.skin_cat_bubble
    }

/** テーマの代表色（見本の帯・プレビューの色）。 */
fun skinThemeColor(theme: String): Color =
    when (theme) {
        "ice" -> Color(0xFF7FB8E8)
        "gold" -> Color(0xFFE8B730)
        "sakura" -> Color(0xFFF08CAB)
        "flame" -> Color(0xFFEF6C3A)
        "dragon" -> Color(0xFFB0243A)
        else -> Color(0xFF9AA0A6)
    }

private fun rarityName(r: SkinRarity): String = r.name

/** ショップの「スキン」タブ: 今週の限定 / 常設 / 福袋 / 欠片の交換 / 着せ替え。購入は擬似購入（[onBuy] で決済シート）。 */
fun LazyListScope.skinTab(
    s: GameState,
    data: GameData,
    onBuy: (String) -> Unit,
    onPreview: (SkinDef) -> Unit,
    onEquip: (SkinCategory, String?) -> Unit,
    onBag: (Boolean) -> Unit,
    onExchange: (String) -> Unit,
    bagBusy: Boolean = false,
) {
    val owned = Skins.owned(s, data)
    val featured = Skins.featured(data, Skins.week(s))
    item { Text(stringResource(R.string.skin_week_title), style = MaterialTheme.typography.titleMedium) }
    featured.forEach {
            skin ->
        item(key = "f_" + skin.id) { SkinCard(skin, s, data, owned, onBuy, onPreview, onEquip) }
    }
    item { Text(stringResource(R.string.skin_regular_title), style = MaterialTheme.typography.titleMedium) }
    data.skins.skins.filter { !it.limited && it.priceYen > 0 }.forEach { skin ->
        item(key = "r_" + skin.id) { SkinCard(skin, s, data, owned, onBuy, onPreview, onEquip) }
    }
    item { Text(stringResource(R.string.skin_bag_title), style = MaterialTheme.typography.titleMedium) }
    item { SkinBagCard(s, data, bagBusy, onBag) }
    item {
        Text(
            stringResource(R.string.skin_exchange_title, s.shop.skins.shards),
            style = MaterialTheme.typography.titleMedium,
        )
    }
    data.skins.skins.filter { it.bag && it.id !in owned }.forEach { skin ->
        item(key = "x_" + skin.id) { ExchangeRow(skin, s, data, onExchange) }
    }
    item { Text(stringResource(R.string.skin_wardrobe_title), style = MaterialTheme.typography.titleMedium) }
    SkinCategory.entries.forEach { cat -> item(key = "w_" + cat.id) { WardrobeRow(cat, s, data, owned, onEquip) } }
}

@Composable
private fun Swatch(
    skin: SkinDef,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(44.dp)
            .background(skinThemeColor(skin.theme), RoundedCornerShape(10.dp))
            .border(2.dp, rarityColor(rarityOf(skin.rarity)), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(categoryGlyph(skin.category), style = MaterialTheme.typography.titleMedium)
    }
}

private fun categoryGlyph(category: String): String =
    when (category) {
        "castle" -> "🏰"
        "territory" -> "🗺"
        "trail" -> "✨"
        "frame" -> "🖼"
        "nameplate" -> "🏷"
        else -> "💬"
    }

private fun rarityOf(r: SkinRarity) =
    when (r) {
        SkinRarity.N -> com.ttmrase.crownfall.core.model.HeroRarity.N
        SkinRarity.R -> com.ttmrase.crownfall.core.model.HeroRarity.R
        SkinRarity.SR -> com.ttmrase.crownfall.core.model.HeroRarity.SR
        SkinRarity.SSR -> com.ttmrase.crownfall.core.model.HeroRarity.SSR
    }

@Composable
private fun SkinCard(
    skin: SkinDef,
    s: GameState,
    data: GameData,
    owned: Set<String>,
    onBuy: (String) -> Unit,
    onPreview: (SkinDef) -> Unit,
    onEquip: (SkinCategory, String?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cat = SkinCategory.fromId(skin.category) ?: return
    val equipped = s.shop.skins.equipped[skin.category] == skin.id
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(skin)
            Column(Modifier.weight(1f)) {
                Text(skinName(context, skin.id), fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.skin_card_sub, stringResource(categoryName(cat)), rarityName(skin.rarity)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (skin.limited) {
                    Text(
                        stringResource(R.string.skin_limited_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onPreview(skin) }) { Text(stringResource(R.string.skin_preview)) }
                when {
                    skin.id in owned && equipped ->
                        OutlinedButton(onClick = {
                            onEquip(cat, null)
                        }) { Text(stringResource(R.string.skin_unequip)) }
                    skin.id in owned ->
                        Button(
                            onClick = { onEquip(cat, skin.id) },
                        ) { Text(stringResource(R.string.skin_equip)) }
                    Skins.purchasable(s, skin, data) ->
                        Button(
                            onClick = { onBuy(ProductIds.skin(skin.id)) },
                        ) { Text(stringResource(R.string.skin_price, skin.priceYen)) }
                }
            }
        }
    }
}

@Composable
private fun SkinBagCard(
    s: GameState,
    data: GameData,
    busy: Boolean,
    onBag: (Boolean) -> Unit,
) {
    val rules = data.skins.bag
    val pity = (rules.pityAt - s.shop.skins.bagSinceRare).coerceAtLeast(0)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.skin_bag_sub), style = MaterialTheme.typography.bodyMedium)
            // 提供割合（確率）を必ず表示する
            Skins.bagTable(data).forEach { (rarity, bp, list) ->
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Text(
                    stringResource(
                        R.string.skin_bag_rate,
                        rarityName(rarity),
                        bp / 100.0,
                        list.joinToString("・") { skinName(ctx, it.id) },
                    ),
                    color = rarityColor(rarityOf(rarity)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(R.string.skin_bag_pity, pity), style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(
                    R.string.skin_bag_dup,
                    rules.dupShards.getValue(SkinRarity.N),
                    rules.dupShards.getValue(SkinRarity.R),
                    rules.dupShards.getValue(SkinRarity.SR),
                    rules.dupShards.getValue(SkinRarity.SSR),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    onBag(false)
                }, enabled = !busy && s.gems >= rules.gems) { Text(stringResource(R.string.skin_bag_one, rules.gems)) }
                Button(onClick = {
                    onBag(true)
                }, enabled = !busy && s.gems >= rules.tenGems) {
                    Text(
                        stringResource(R.string.skin_bag_ten, rules.tenGems),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExchangeRow(
    skin: SkinDef,
    s: GameState,
    data: GameData,
    onExchange: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cost = Skins.exchangeCost(s, skin.id, data) ?: return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(skinName(context, skin.id) + "  (${rarityName(skin.rarity)})", modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onExchange(skin.id) }, enabled = s.shop.skins.shards >= cost) {
            Text(stringResource(R.string.skin_exchange_cost, cost))
        }
    }
}

@Composable
private fun WardrobeRow(
    cat: SkinCategory,
    s: GameState,
    data: GameData,
    owned: Set<String>,
    onEquip: (SkinCategory, String?) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val mine = data.skins.skins.filter { it.category == cat.id && it.id in owned }
    val current = s.shop.skins.equipped[cat.id]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(
                R.string.skin_wardrobe_row,
                stringResource(categoryName(cat)),
                current?.let {
                    skinName(context, it)
                } ?: stringResource(R.string.skin_none),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = {
                onEquip(cat, null)
            }, enabled = current != null) { Text(stringResource(R.string.skin_unequip)) }
            mine.forEach { skin ->
                OutlinedButton(
                    onClick = { onEquip(cat, skin.id) },
                    enabled = skin.id != current,
                ) { Text(skinName(context, skin.id)) }
            }
        }
    }
}

/** スキンのプレビュー（城は 5 段階の見た目を並べる。それ以外は色見本と説明）。 */
@Composable
fun SkinPreviewDialog(
    skin: SkinDef,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        title = { Text(skinName(context, skin.id)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (skin.category == "castle") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(1, 3, 5).forEach { tier ->
                            val id =
                                context.resources.getIdentifier(
                                    "bld_castle_${skin.theme}_t$tier",
                                    "drawable",
                                    context.packageName,
                                )
                            if (id != 0) ComposeImage(painterResource(id), null, Modifier.weight(1f).size(96.dp))
                        }
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().size(
                            96.dp,
                        ).background(skinThemeColor(skin.theme), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(categoryGlyph(skin.category), style = MaterialTheme.typography.displaySmall)
                    }
                }
                Text(stringResource(R.string.skin_preview_note), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

/** 福袋の結果の一覧。1 つごとに「NEW」か「重複 → 欠片 n」を出す。 */
@Composable
fun SkinBagResultDialog(
    entries: List<SkinBagEntry>,
    newCount: Int,
    data: GameData,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
        title = { Text(stringResource(R.string.skin_bag_result_title, newCount)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                entries.forEach { e ->
                    val d = Skins.def(data, e.skinId)
                    val tail =
                        if (e.isNew) {
                            stringResource(R.string.skin_bag_item_new)
                        } else {
                            stringResource(R.string.skin_bag_item_dup, e.shards)
                        }
                    Text(
                        "${e.rarity}  ${skinName(context, e.skinId)}  $tail",
                        color = d?.let { rarityColor(rarityOf(it.rarity)) } ?: Color.Unspecified,
                    )
                }
            }
        },
    )
}
