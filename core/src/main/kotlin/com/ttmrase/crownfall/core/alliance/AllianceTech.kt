package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TechDef
import com.ttmrase.crownfall.core.model.GameState

/** 同盟テックのレベルと効果（BuffAggregator の出所の 1 つ）。 */
object AllianceTech {
    /** 経験値 [exp] でのレベル（累積閾値を超えた数）。 */
    fun level(
        tech: TechDef,
        exp: Long,
    ): Int = tech.expPerLevel.count { exp >= it }

    /** 現在のレベル。 */
    fun level(
        state: GameState,
        tech: TechDef,
    ): Int = level(tech, state.alliance.techExp[tech.id] ?: 0L)

    /** 最大レベルか。 */
    fun isMax(
        state: GameState,
        tech: TechDef,
    ): Boolean = level(state, tech) >= tech.expPerLevel.size

    /** 現在のレベルでの bp（Lv0 は 0）。 */
    fun bp(
        state: GameState,
        tech: TechDef,
    ): Long = level(state, tech).let { if (it <= 0) 0L else tech.bpPerLevel[it - 1] }

    /** 各テックの (BuffKey, bp)。 */
    fun buffs(
        state: GameState,
        data: GameData,
    ): List<Pair<BuffKey, Long>> =
        data.allianceRules.techs.mapNotNull { t ->
            val key = BuffKey.entries.firstOrNull { it.name == t.buff } ?: return@mapNotNull null
            val bp = bp(state, t)
            if (bp > 0) key to bp else null
        }
}
