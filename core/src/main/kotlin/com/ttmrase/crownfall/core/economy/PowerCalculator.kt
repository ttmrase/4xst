package com.ttmrase.crownfall.core.economy

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.satAdd

/** 戦力（Power）計算。M3 で兵・研究・英雄を [total] に足す。 */
object PowerCalculator {
    /** 全スロットの建物戦力の合計（level 0 は 0）。 */
    fun buildingPower(
        state: GameState,
        data: GameData,
    ): Long =
        state.slots.fold(0L) { acc, slot ->
            if (slot.level <= 0) acc else satAdd(acc, data.building(slot.type).level(slot.level).power)
        }

    /** 合計戦力（現状は建物のみ）。 */
    fun total(
        state: GameState,
        data: GameData,
    ): Long = buildingPower(state, data)
}
