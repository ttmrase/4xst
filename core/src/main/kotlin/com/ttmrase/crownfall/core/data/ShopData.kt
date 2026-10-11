package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.model.Resources
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** パック・報酬の中身。ジェム・資源・アイテム・英雄の欠片・2 本目ビルダー（日数）。 */
@Serializable
data class PackContents(
    val gems: Long = 0,
    val resources: Resources = Resources.ZERO,
    val items: Map<String, Int> = emptyMap(),
    val heroShards: Map<String, Int> = emptyMap(),
    val secondBuilderDays: Int = 0,
    /** 週替わりイベントのボーナススコア（逆転パック。NPC の追従計算には入らない）。 */
    val eventPoints: Long = 0,
) {
    /** 何も入っていないか。 */
    val isEmpty: Boolean
        get() =
            gems == 0L && resources == Resources.ZERO && items.isEmpty() && heroShards.isEmpty() &&
                secondBuilderDays == 0 && eventPoints == 0L
}

/** パックのバッジ（04 §4）。 */
@Serializable
enum class OfferBadge { NONE, RECOMMENDED, POPULAR, BEST_VALUE }

/**
 * トリガーの DSL（`{"when":"castleUp","minLv":5}`）。種類 [kind] ごとに使う引数が決まる:
 * login / castleUp(minLv) / resourceShortage / gemShortage / defeated / longTimer(minSec) /
 * afterPurchase(chainOf) / rankingNear / exitIntent / vipNear(withinPt) / comeback(minAwaySec) / piggyFull。
 */
@Serializable
data class TriggerDef(
    @SerialName("when") val kind: String,
    val minLv: Int = 0,
    val minSec: Long = 0,
    val chainOf: String? = null,
    val withinPt: Long = 0,
    val minAwaySec: Long = 0,
)

/** ジェム SKU。[firstDouble] なら、その SKU の初回購入だけ基本のジェムが 2 倍（ボーナスは 2 倍にしない）。 */
@Serializable
data class GemSku(
    val sku: String,
    val priceYen: Long,
    val gems: Long,
    val bonus: Long,
    val firstDouble: Boolean,
)

/**
 * パック 1 種。[lvScale] は内容を城 Lv 倍にする（城 Lv 記念）。[shelf] は常設の棚に並べる。
 * [deal] が "normal" / "super" ならデイリーディールの候補。[tier] は連鎖の段（0 は連鎖外）。
 */
@Serializable
data class PackDef(
    val id: String,
    val priceYen: Long,
    val listValueYen: Long,
    val badge: OfferBadge = OfferBadge.NONE,
    val durationSec: Long,
    val dailyStock: Int,
    val cooldownSec: Long,
    val contents: PackContents,
    val shelf: Boolean = false,
    val lvScale: Boolean = false,
    val tier: Int = 0,
    val trigger: TriggerDef? = null,
    val deal: String? = null,
    /** このパックを出すのに解放が要る機能の id（[com.ttmrase.crownfall.core.feature.Feature.id]）。 */
    val feature: String? = null,
)

/** offers.json。 */
@Serializable
data class OffersFile(val gemSkus: List<GemSku>, val packs: List<PackDef>) {
    /** パック [id]。 */
    fun pack(id: String): PackDef? = packs.firstOrNull { it.id == id }

    /** ジェム SKU [sku]。 */
    fun gem(sku: String): GemSku? = gemSkus.firstOrNull { it.sku == sku }
}

@Serializable
data class FrequencyRules(
    val sessionCapLow: Int,
    val sessionCapStandard: Int,
    val defaultCooldownSec: Long,
    val chainMax: Int,
    val queueCap: Int,
    val reviveDurationSec: Long,
    /** オファーのポップアップを閉じてから、次のポップアップを自動で出すまでの最短秒数。 */
    val popupGapSec: Long,
)

@Serializable
data class LoginRules(val pointsByStreak: List<Int>)

/** 不足ちょうどのパック用の換算表。[resourceYenTable] は (資源の合計量の上限, 円) の昇順。 */
@Serializable
data class ShortageRules(
    val resourceYenTable: List<List<Long>>,
    val durationSec: Long,
    val cooldownSec: Long,
)

@Serializable
data class DealRules(val normalCount: Int, val superCount: Int, val superOffPct: Int)

@Serializable
data class MonthPassRules(
    val sku: String,
    val priceYen: Long,
    val days: Int,
    val dailyGems: Long,
    val dailyItems: Map<String, Int>,
    val listValueYen: Long,
)

@Serializable
data class GrowthFundRules(
    val sku: String,
    val priceYen: Long,
    val listValueYen: Long,
    /** (城 Lv, 受け取るジェム)。 */
    val milestones: List<List<Long>>,
)

@Serializable
data class PiggyRules(
    val sku: String,
    val openYen: Long,
    val cap: Long,
    val gemsPerBuild: Long,
    val gemsPerResearch: Long,
    val gemsPerDefense: Long,
    val gemsPerMarch: Long,
)

@Serializable
data class WheelSegment(
    val id: String,
    val weight: Int,
    val gems: Long = 0,
    val resources: Resources = Resources.ZERO,
    val items: Map<String, Int> = emptyMap(),
    val heroShards: Map<String, Int> = emptyMap(),
    /** 「惜しい」演出の対象（SSR の欠片）。 */
    val tease: Boolean = false,
)

@Serializable
data class WheelRules(
    val freePerDay: Int,
    val extraSku: String,
    val extraYen: Long,
    val extraGems: Long,
    val segments: List<WheelSegment>,
)

@Serializable
data class ShopStats(val vipNearWithinPt: Long)

/** shop_rules.json。 */
@Serializable
data class ShopRules(
    val yenPerVipPoint: Int,
    val frequency: FrequencyRules,
    val login: LoginRules,
    val loginPackSku: String,
    val exitPackSku: String,
    val socialProofSkus: List<String>,
    val shortage: ShortageRules,
    val dailyDeals: DealRules,
    val monthPass: MonthPassRules,
    val growthFund: GrowthFundRules,
    val piggy: PiggyRules,
    val wheel: WheelRules,
    val ledgerCap: Int,
    val stats: ShopStats,
)

/** VIP 1 段の閾値（累計 pt）と特典。 */
@Serializable
data class VipLevelDef(
    val lv: Int,
    val pt: Long,
    val gatherBp: Long,
    val offlineBonusSec: Long,
    val gemSkipBp: Long,
    val secondBuilder: Boolean,
    val chest: PackContents,
)

/** vip.json。 */
@Serializable
data class VipFile(val levels: List<VipLevelDef>)
