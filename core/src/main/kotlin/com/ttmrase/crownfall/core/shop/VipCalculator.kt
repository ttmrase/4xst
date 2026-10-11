package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.alliance.AllianceLogic
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.data.VipLevelDef
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.mulDiv
import com.ttmrase.crownfall.core.model.satAdd

/** VIP の計算と特典（04 §7）。VIP は台帳ではなく `shop.vipPoints` で管理する。 */
object VipCalculator {
    private const val BP_TOTAL = 10_000L

    /** 累計 [points] での VIP Lv（0 = まだ VIP でない）。 */
    fun level(
        points: Long,
        data: GameData,
    ): Int = data.vip.levels.count { points >= it.pt }

    /** 現在の VIP Lv。 */
    fun level(
        state: GameState,
        data: GameData,
    ): Int = level(state.shop.vipPoints, data)

    /** 現在の Lv の特典（Lv0 は null）。 */
    fun perks(
        state: GameState,
        data: GameData,
    ): VipLevelDef? = level(state, data).let { if (it <= 0) null else data.vip.levels[it - 1] }

    /** 次の Lv と、そこまでのあと何 pt か（最大 Lv なら null）。 */
    fun toNext(
        state: GameState,
        data: GameData,
    ): Pair<Int, Long>? {
        val lv = level(state, data)
        val next = data.vip.levels.getOrNull(lv) ?: return null
        return next.lv to (next.pt - state.shop.vipPoints)
    }

    /** 採集速度の bp（BuffAggregator に加算される）。 */
    fun gatherBp(
        state: GameState,
        data: GameData,
    ): Long = perks(state, data)?.gatherBp ?: 0L

    /** オフライン上限の延長秒。 */
    fun offlineBonusSec(
        state: GameState,
        data: GameData,
    ): Long = perks(state, data)?.offlineBonusSec ?: 0L

    /** ジェムスキップの割引（bp）。 */
    fun gemSkipBp(
        state: GameState,
        data: GameData,
    ): Long = perks(state, data)?.gemSkipBp ?: 0L

    /** 2 本目のビルダーが常設か（VIP6 以上）。 */
    fun hasSecondBuilder(
        state: GameState,
        data: GameData,
    ): Boolean = perks(state, data)?.secondBuilder == true

    /** 割引後のジェムスキップのコスト（切り上げ、最低 1）。 */
    fun discountedGemCost(
        cost: Long,
        state: GameState,
        data: GameData,
    ): Long {
        if (cost <= 0) return cost
        val bp = gemSkipBp(state, data).coerceIn(0, BP_TOTAL)
        if (bp == 0L) return cost
        val scaled = mulDiv(cost, BP_TOTAL - bp, BP_TOTAL)
        val exact = scaled * BP_TOTAL == cost * (BP_TOTAL - bp)
        return maxOf(1L, if (exact) scaled else scaled + 1)
    }

    /** デイリーログインで得る pt（連続 [streak] 日目。表の末尾が上限）。 */
    fun loginPoints(
        streak: Int,
        data: GameData,
    ): Long {
        val table = data.shopRules.login.pointsByStreak
        return table[(streak - 1).coerceIn(0, table.lastIndex)].toLong()
    }

    /** VIP デイリーチェストの中身（Lv0 は空）。 */
    fun chest(
        state: GameState,
        data: GameData,
    ): PackContents = perks(state, data)?.chest ?: PackContents()

    /**
     * [points] を加算する。Lv が上がったら [GameEvent.VipLevelUp] を [events] に足す。
     * 昇格間近のオファーは呼び出し側（[ShopHooks]）が判断する。
     */
    fun addPoints(
        state: GameState,
        points: Long,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        if (points <= 0) return state
        val before = level(state, data)
        val s = state.copy(shop = state.shop.copy(vipPoints = satAdd(state.shop.vipPoints, points)))
        val after = level(s, data)
        var out = s
        for (lv in before + 1..after) {
            events.add(GameEvent.VipLevelUp(lv))
            // 同盟のお祝いのチャットは、同盟の解放（城 Lv4）から
            if (FeatureGate.unlocked(out, data, Feature.ALLIANCE)) {
                out = AllianceLogic.enqueueReactive(out, ChatEvent.VipUp(lv), listOf("johnny", "kaede"), data)
            }
        }
        // 段階解放: ショップが解放されるまで VIP の演出は出さない（祝った Lv を黙って追従させる）
        if (!FeatureGate.unlocked(out, data, Feature.SHOP) && out.shop.vipCelebrated >= 0) {
            out = out.copy(shop = out.shop.copy(vipCelebrated = maxOf(out.shop.vipCelebrated, after)))
        }
        return out
    }
}
