package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState

/** tick・アクションからショップへ知らせる口（ピギーバンクの蓄積とオファーのトリガー）。 */
internal object ShopHooks {
    /**
     * 同じ時刻（[state] の現在時刻）に起きた [events] を処理する。
     * R3 のため、イベントが起きた境界の時刻で呼ぶこと（tick の終端でまとめて呼ばない）。
     */
    fun onEvents(
        state: GameState,
        events: List<GameEvent>,
        data: GameData,
    ): GameState {
        if (events.isEmpty()) return state
        var s = state
        val piggy = data.shopRules.piggy
        var gems = 0L
        for (e in events) {
            when (e) {
                is GameEvent.BuildingLevelUp -> {
                    gems += piggy.gemsPerBuild
                    if (e.type == BuildingType.CASTLE) {
                        val ctx = TriggerCtx(lv = e.newLevel, params = mapOf("lv" to e.newLevel.toLong()))
                        s = OfferEngine.fire(s, "castleUp", ctx, data)
                    }
                }
                is GameEvent.ResearchCompleted -> gems += piggy.gemsPerResearch
                is GameEvent.PlayerDefendedRaid -> gems += piggy.gemsPerDefense
                is GameEvent.MarchReturned -> gems += piggy.gemsPerMarch
                is GameEvent.PlayerDefeated -> s = OfferEngine.fire(s, "defeated", TriggerCtx(), data)
                else -> Unit
            }
        }
        if (gems > 0) {
            val wasFull = PiggyBank.isFull(s, data)
            s = PiggyBank.accrue(s, gems, data)
            if (!wasFull && PiggyBank.isFull(s, data)) s = OfferEngine.fire(s, "piggyFull", TriggerCtx(), data)
        }
        return s
    }

    /** タイマーを [durationSec] で開始した。 */
    fun onTimerStarted(
        state: GameState,
        durationSec: Long,
        data: GameData,
    ): GameState = OfferEngine.onLongTimer(state, durationSec, data)

    /** 不在から戻った（[awaySec] は不在の長さ）。 */
    fun onReturn(
        state: GameState,
        awaySec: Long,
        data: GameData,
    ): GameState = OfferEngine.onComeback(state, awaySec, data)
}
