package com.ttmrase.crownfall.core.economy

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.hero.HeroPower
import com.ttmrase.crownfall.core.military.Military
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.satAdd

/** 戦力（Power）計算。建物・研究（・M3 で兵・英雄）の合計を [total] で返す。 */
object PowerCalculator {
    /** 全スロットの建物戦力の合計（level 0 は 0）。 */
    fun buildingPower(
        state: GameState,
        data: GameData,
    ): Long =
        state.slots.fold(0L) { acc, slot ->
            if (slot.level <= 0) acc else satAdd(acc, data.building(slot.type).level(slot.level).power)
        }

    /** 研究の戦力（各ノードの現在レベル時点の power の合計）。 */
    fun researchPower(
        state: GameState,
        data: GameData,
    ): Long =
        state.research.entries.fold(0L) { acc, (id, level) ->
            val node = data.research[id]
            if (node == null || level <= 0) acc else satAdd(acc, node.level(minOf(level, node.maxLevel)).power)
        }

    /** 合計戦力（建物 + 研究 + 生存兵 + 英雄）。 */
    fun total(
        state: GameState,
        data: GameData,
    ): Long =
        satAdd(
            satAdd(buildingPower(state, data), researchPower(state, data)),
            satAdd(Military.troopPower(state, data), HeroPower.total(state, data)),
        )
}
