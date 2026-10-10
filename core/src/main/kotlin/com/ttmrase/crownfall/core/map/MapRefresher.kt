package com.ttmrase.crownfall.core.map

import com.ttmrase.crownfall.core.data.LevelBand
import com.ttmrase.crownfall.core.data.MapRules
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.util.SeededRandom

/** 資源ノードと野蛮人の補充（密度目標まで、空きタイルへ乱数で配置）。 */
object MapRefresher {
    private const val MAX_ATTEMPTS = 400

    /** 距離 [distance] に対応するレベル帯。 */
    fun band(
        bands: List<LevelBand>,
        distance: Int,
    ): LevelBand = bands.firstOrNull { distance <= it.upToDistance } ?: bands.last()

    /**
     * 目標数まで補充した [MapState] を返し、次の補充時刻を [now] + 周期にする。
     * 乱数は `map.rngState` だけを使う（他の乱数列と独立）。
     */
    fun refresh(
        map: MapState,
        world: WorldMap,
        rules: MapRules,
        now: Long,
    ): MapState {
        val rng = SeededRandom(map.rngState)
        val nodes = map.nodes.toMutableList()
        val barbs = map.barbarians.toMutableList()
        val taken = HashSet<Int>()
        nodes.forEach { taken.add(it.y * world.size + it.x) }
        barbs.forEach { taken.add(it.y * world.size + it.x) }

        fun randomFree(): TileRef? {
            repeat(MAX_ATTEMPTS) {
                val x = rng.nextInt(world.size)
                val y = rng.nextInt(world.size)
                if (world.structureAt(x, y) == null && (y * world.size + x) !in taken) return TileRef(x, y)
            }
            return null
        }

        fun levelAt(
            bands: List<LevelBand>,
            ref: TileRef,
        ): Int {
            val b = band(bands, MapQueries.distance(ref.x, ref.y, rules.center.x, rules.center.y))
            return b.minLevel + rng.nextInt(b.maxLevel - b.minLevel + 1)
        }
        for (type in ResourceType.entries) {
            while (nodes.count { it.type == type } < rules.nodes.targetPerType) {
                val ref = randomFree() ?: break
                val level = levelAt(rules.nodes.bands, ref)
                nodes.add(ResourceNode(ref.x, ref.y, type, level, rules.nodes.remainingByLevel[level - 1]))
                taken.add(ref.y * world.size + ref.x)
            }
        }
        while (barbs.size < rules.barbarians.target) {
            val ref = randomFree() ?: break
            barbs.add(Barbarian(ref.x, ref.y, levelAt(rules.barbarians.bands, ref)))
            taken.add(ref.y * world.size + ref.x)
        }
        return map.copy(
            rngState = rng.state,
            nodes = nodes,
            barbarians = barbs,
            nextRefreshEpochSec = satAdd(now, rules.refreshPeriodSec),
        )
    }
}
