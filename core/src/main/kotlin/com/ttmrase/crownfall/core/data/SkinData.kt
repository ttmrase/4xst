package com.ttmrase.crownfall.core.data

import kotlinx.serialization.Serializable

/** スキンの部位。 */
enum class SkinCategory(val id: String) {
    CASTLE("castle"),
    TERRITORY("territory"),
    TRAIL("trail"),
    FRAME("frame"),
    NAMEPLATE("nameplate"),
    BUBBLE("bubble"),
    ;

    companion object {
        fun fromId(id: String): SkinCategory? = entries.firstOrNull { it.id == id }
    }
}

/** スキンのレア度（福袋の排出と、重複したときの欠片の量に使う）。 */
enum class SkinRarity { N, R, SR, SSR }

/**
 * 解放の条件（購入以外）。`vip`: VIP Lv が [lv] 以上、`claimed`: クエスト [id] を受け取り済み、
 * `event_rank`: 週替わりイベントで [lv] 位以内になったことがある。
 * 条件を満たすと自動で所持扱いになる（保存はしない）。
 */
@Serializable
data class SkinUnlock(val type: String, val lv: Int = 0, val id: String = "")

/**
 * スキン 1 件。[priceYen] が 0 でなければ ¥ で買える（擬似購入）。[limited] は週替わりで出る限定（毎週決まった順で巡り、
 * 復刻される）。[bag] が true なら福袋の排出に含まれる。[theme] は見た目の系統（ice / gold / sakura / dragon など）。
 */
@Serializable
data class SkinDef(
    val id: String,
    val category: String,
    val theme: String,
    val rarity: SkinRarity,
    val priceYen: Long = 0,
    val limited: Boolean = false,
    val bag: Boolean = false,
    val unlock: SkinUnlock? = null,
)

/** 福袋の設定。確率は bp（合計 10000）、[pityAt] 連続で SR 以上が出なければ次は SR 以上確定。 */
@Serializable
data class SkinBagRules(
    val gems: Long,
    val tenGems: Long,
    val pityAt: Int,
    val rates: Map<SkinRarity, Int>,
    val dupShards: Map<SkinRarity, Int>,
    val exchange: Map<SkinRarity, Int>,
)

/** skins.json。[featuredPerWeek] は週替わりで買える限定スキンの数。 */
@Serializable
data class SkinsFile(
    val featuredPerWeek: Int,
    val bag: SkinBagRules,
    val skins: List<SkinDef>,
)
