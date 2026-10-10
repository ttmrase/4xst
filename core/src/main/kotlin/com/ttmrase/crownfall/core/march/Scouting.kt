package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.combat.Armies
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.MapGenerator
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ScoutReport

/** 偵察。Watchtower の Lv が高いほど兵数の誤差が小さい。 */
object Scouting {
    /** 偵察の誤差 %（Watchtower Lv 反映）。 */
    fun errorPct(
        state: GameState,
        data: GameData,
    ): Int {
        val r = data.mapRules.scout
        val lv = state.slots.filter { it.type == BuildingType.WATCHTOWER }.maxOfOrNull { it.level } ?: 0
        return maxOf(r.minErrorPct, r.baseErrorPct - r.errorPctPerWatchtowerLevel * lv)
    }

    /** [tile] が偵察できる対象か。 */
    fun isScoutable(
        state: GameState,
        tile: TileRef,
        data: GameData,
    ): Boolean = report(state, tile, data) != null

    /** [tile] の偵察報告。何も無ければ null。 */
    fun report(
        state: GameState,
        tile: TileRef,
        data: GameData,
    ): ScoutReport? {
        val err = errorPct(state, data)

        fun band(real: Map<String, Long>) =
            real.mapValues { it.value * (100 - err) / 100 } to real.mapValues { it.value * (100 + err) / 100 }
        state.map.barbarians.firstOrNull { it.x == tile.x && it.y == tile.y }?.let {
            val (lo, hi) = band(Armies.barbarian(it.level, data).troops)
            return ScoutReport(tile, "barbarian", it.level, err, lo, hi)
        }
        state.map.nodes.firstOrNull { it.x == tile.x && it.y == tile.y }?.let {
            return ScoutReport(
                tile,
                "node",
                it.level,
                err,
                emptyMap(),
                emptyMap(),
                nodeType = it.type,
                nodeRemaining = it.remaining,
            )
        }
        val s = MapGenerator.cached(state.map.seed, data.mapRules).structureAt(tile.x, tile.y) ?: return null
        return when (s.type) {
            StructureType.BARBARIAN_FORT -> {
                val (lo, hi) = band(Armies.fort(s.level, data).troops)
                ScoutReport(tile, "fort", s.level, err, lo, hi)
            }
            StructureType.NPC_CASTLE -> ScoutReport(tile, "npc_castle", 0, err, emptyMap(), emptyMap(), power = s.power)
            else -> null
        }
    }
}
