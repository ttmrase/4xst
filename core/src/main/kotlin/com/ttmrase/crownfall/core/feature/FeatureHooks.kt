package com.ttmrase.crownfall.core.feature

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.shop.OfferEngine

/** 機能が解放された時刻（城 Lv が上がったタイマーの完了。tick の境界）に走る処理。 */
internal object FeatureHooks {
    fun onUnlocked(
        state: GameState,
        feature: Feature,
        data: GameData,
    ): GameState =
        when (feature) {
            Feature.SHOP -> OfferEngine.onShopUnlocked(state, data)
            Feature.RAIDS -> scheduleFirstRaid(state, data)
            else -> state
        }

    /** 襲撃の予定が無い（または解放前に過ぎてしまった）なら、解放の時刻 + firstDelaySec にする。 */
    private fun scheduleFirstRaid(
        state: GameState,
        data: GameData,
    ): GameState {
        val next = state.map.nextRaidEpochSec
        if (next > state.nowEpochSec) return state
        val first = satAdd(state.nowEpochSec, data.mapRules.raid.firstDelaySec)
        return state.copy(map = state.map.copy(nextRaidEpochSec = first, raidWarned = false, raidStageDone = 0))
    }
}
