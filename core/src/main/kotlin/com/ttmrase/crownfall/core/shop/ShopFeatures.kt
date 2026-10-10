package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.data.PackDef
import com.ttmrase.crownfall.core.data.WheelSegment
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.util.Mix
import com.ttmrase.crownfall.core.util.SeededRandom

private const val SECONDS_PER_DAY = 86_400L
private const val PERCENT = 100L

/** デイリーディール 1 枚。[offPct] は定価に対する割引率（表示用）。 */
data class DealCard(
    val pack: PackDef,
    val priceYen: Long,
    val listValueYen: Long,
    val offPct: Int,
    val isSuper: Boolean,
    val soldOut: Boolean,
)

/** デイリーディール。毎日 3 枚（普通 2 + 99% OFF の ¥120 が 1）。内容は (種, 現地日) のハッシュで決まる（分割不変）。 */
object DailyDeals {
    /** 今日のディール（決定論。状態は変えない）。 */
    fun today(
        state: GameState,
        data: GameData,
    ): List<DealCard> {
        val day = ShopLogic.localDay(state)
        val seed = ShopLogic.seed(state)
        val rules = data.shopRules.dailyDeals

        fun pick(
            kind: String,
            count: Int,
            salt: Long,
        ): List<PackDef> =
            data.offers.packs.filter { it.deal == kind }
                .sortedBy { Mix.mix(seed xor (day * 31 + salt) xor it.id.hashCode().toLong()) }
                .take(count)

        val supers = pick("super", rules.superCount, 1L)
        val normals = pick("normal", rules.normalCount, 2L)
        val used = state.shop.stockUsed.takeIf { state.shop.day == day } ?: emptyMap()
        return (supers.map { it to true } + normals.map { it to false }).map { (p, sup) ->
            val off =
                if (sup) {
                    rules.superOffPct
                } else {
                    (PERCENT - p.priceYen * PERCENT / p.listValueYen).toInt()
                }
            DealCard(p, p.priceYen, p.listValueYen, off, sup, (used[p.id] ?: 0) >= p.dailyStock)
        }
    }

    /** 今日のディールに [packId] があるか。 */
    fun find(
        state: GameState,
        packId: String,
        data: GameData,
    ): DealCard? = today(state, data).firstOrNull { it.pack.id == packId }
}

/** ピギーバンク（行動でジェムが貯まり、¥610 で開封）。 */
object PiggyBank {
    /** [gems] ぶん貯める。満杯（cap）を超えない。 */
    fun accrue(
        state: GameState,
        gems: Long,
        data: GameData,
    ): GameState {
        val cap = data.shopRules.piggy.cap
        val next = minOf(cap, state.shop.piggyGems + gems)
        return state.copy(shop = state.shop.copy(piggyGems = next))
    }

    /** 満杯か。 */
    fun isFull(
        state: GameState,
        data: GameData,
    ): Boolean = state.shop.piggyGems >= data.shopRules.piggy.cap
}

/** 月パス（30 日。毎日の受け取り + 期間中は 2 本目ビルダー）。 */
object MonthPass {
    /** 期間中か。 */
    fun active(state: GameState): Boolean = state.shop.monthPassUntilEpochSec > state.nowEpochSec

    /** 残り日数（切り上げ）。 */
    fun daysLeft(state: GameState): Int =
        if (!active(
                state,
            )
        ) {
            0
        } else {
            ((state.shop.monthPassUntilEpochSec - state.nowEpochSec + SECONDS_PER_DAY - 1) / SECONDS_PER_DAY).toInt()
        }

    /** 今日の分を受け取れるか。 */
    fun canClaim(state: GameState): Boolean =
        active(state) && state.shop.monthPassClaimedDay != ShopLogic.localDay(state)

    /** 今日の受け取り内容。 */
    fun daily(data: GameData): PackContents =
        PackContents(gems = data.shopRules.monthPass.dailyGems, items = data.shopRules.monthPass.dailyItems)
}

/** 成長ファンド（城 Lv の到達ごとにジェムが戻る）。 */
object GrowthFund {
    /** 受け取れるマイルストーン（城 Lv, ジェム）。購入済みで、到達済みで、未受領のもの。 */
    fun claimable(
        state: GameState,
        data: GameData,
    ): List<Pair<Int, Long>> {
        if (!state.shop.growthFundOwned) return emptyList()
        val lv = ShopLogic.castleLevel(state)
        return data.shopRules.growthFund.milestones.map { it[0].toInt() to it[1] }
            .filter { it.first <= lv && it.first !in state.shop.growthFundClaimed }
    }

    /** マイルストーンの返還ジェム合計（「元が取れる」演出用）。 */
    fun totalGems(data: GameData): Long = data.shopRules.growthFund.milestones.sumOf { it[1] }
}

/** ラッキーホイールの 1 回の結果。[tease] は「惜しい」演出で先に見せる位置（SSR の欠片。当たりがそれなら -1）。 */
data class SpinOutcome(
    val index: Int,
    val segment: WheelSegment,
    val tease: Int,
    val state: GameState,
)

/** ラッキーホイール（1 日 1 回無料、追加は ¥120 かジェム）。結果は state の乱数（rngState）で決める。 */
object LuckyWheel {
    /** 無料の 1 回が残っているか。 */
    fun freeAvailable(state: GameState): Boolean = state.shop.wheelFreeDay != ShopLogic.localDay(state)

    /** 重み付きで 1 セグメントを選ぶ。乱数は [rng] から取る。 */
    fun pick(
        rng: SeededRandom,
        segments: List<WheelSegment>,
    ): Int {
        var r = rng.nextInt(segments.sumOf { it.weight })
        for ((i, s) in segments.withIndex()) {
            if (r < s.weight) return i
            r -= s.weight
        }
        return segments.lastIndex
    }

    /** 1 回回す。状態の rngState を進めて報酬を付与する。回数の管理（無料・追加・ジェム）は呼び出し側。 */
    fun spin(
        state: GameState,
        data: GameData,
    ): SpinOutcome {
        val segs = data.shopRules.wheel.segments
        val rng = SeededRandom(state.rngState)
        val idx = pick(rng, segs)
        val seg = segs[idx]
        val teaseIdx = segs.indexOfFirst { it.tease }
        val s =
            ShopLogic.grant(
                state.copy(rngState = rng.state),
                PackContents(seg.gems, seg.resources, seg.items, seg.heroShards),
                data,
            )
        return SpinOutcome(idx, seg, if (seg.tease) -1 else teaseIdx, s)
    }
}
