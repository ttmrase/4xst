package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.MapGenerator
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul
import com.ttmrase.crownfall.core.sim.Buffs

/** 行軍の数値計算（枠・上限・移動時間・積載量）。 */
object Marching {
    /** 静的な世界（キャッシュ済み）。 */
    fun world(
        state: GameState,
        data: GameData,
    ): WorldMap = MapGenerator.cached(state.map.seed, data.mapRules)

    /** Castle の最高 Lv。 */
    fun castleLevel(state: GameState): Int = levelOf(state, BuildingType.CASTLE)

    /** 建物 [type] の最高 Lv。 */
    fun levelOf(
        state: GameState,
        type: BuildingType,
    ): Int = state.slots.filter { it.type == type }.maxOfOrNull { it.level } ?: 0

    /** 同時に出せる行軍の数。 */
    fun slots(
        state: GameState,
        data: GameData,
    ): Int = data.mapRules.march.let { it.baseSlots + castleLevel(state) / it.castleLevelPerSlot }

    /** 1 隊に積める兵数の上限。 */
    fun troopCap(
        state: GameState,
        data: GameData,
    ): Long =
        data.mapRules.march.let {
            satAdd(it.troopCapBase, satMul(it.troopCapPerCastleLevel, castleLevel(state).toLong()))
        }

    /** 行軍中の英雄 ID。 */
    fun busyHeroes(state: GameState): Set<String> = state.marches.flatMapTo(HashSet()) { it.heroIds }

    /** 片道の所要秒（城から [target] まで）。偵察は固定速度、他は最も遅い兵種の速度。行軍速度バフ込み（最小 1 秒）。 */
    fun travelSec(
        state: GameState,
        kind: MarchKind,
        troops: Map<String, Long>,
        heroIds: List<String>,
        target: TileRef,
        data: GameData,
    ): Long {
        val r = data.mapRules.march
        val dist = MapQueries.distance(target.x, target.y, data.mapRules.center.x, data.mapRules.center.y).toLong()
        val perTile =
            if (kind == MarchKind.SCOUT) {
                r.scoutSecPerTile
            } else {
                val slowest =
                    troops.filterValues { it > 0 }.keys.minOfOrNull { data.troops.getValue(it).speed } ?: r.refSpeed
                (r.baseSecPerTile * r.refSpeed + slowest - 1) / maxOf(1L, slowest)
            }
        val bp = BuffAggregator.forMarch(state, heroIds, data)[BuffKey.MARCH_SPEED]
        return Buffs.speedSeconds(maxOf(1L, dist) * perTile, bp)
    }

    /** 積載量 = Σ(兵数 × load) に LOAD_CAPACITY を適用。 */
    fun capacity(
        state: GameState,
        troops: Map<String, Long>,
        heroIds: List<String>,
        data: GameData,
    ): Long {
        var base = 0L
        for ((id, n) in troops) if (n > 0) base = satAdd(base, satMul(n, data.troops.getValue(id).load))
        return Buffs.applyBp(base, BuffAggregator.forMarch(state, heroIds, data)[BuffKey.LOAD_CAPACITY])
    }
}
