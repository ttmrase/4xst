package com.ttmrase.crownfall.core.buff

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType

/** バフの集計結果（[BuffKey] → bp 合計）。 */
class BuffSet(private val bp: Map<BuffKey, Long>) {
    /** [key] の bp 合計（無ければ 0）。 */
    operator fun get(key: BuffKey): Long = bp[key] ?: 0L

    /** 資源 [type] の生産に効く bp（全体用 + 個別用）。 */
    fun production(type: ResourceType): Long =
        this[BuffKey.PRODUCTION_ALL] +
            this[
                when (type) {
                    ResourceType.FOOD -> BuffKey.PRODUCTION_FOOD
                    ResourceType.WOOD -> BuffKey.PRODUCTION_WOOD
                    ResourceType.STONE -> BuffKey.PRODUCTION_STONE
                    ResourceType.GOLD -> BuffKey.PRODUCTION_GOLD
                },
            ]

    override fun equals(other: Any?): Boolean = other is BuffSet && other.bp == bp

    override fun hashCode(): Int = bp.hashCode()

    override fun toString(): String = "BuffSet($bp)"
}

/**
 * バフの集約。同じ量に効くバフは出所（研究・Boost）と粒度（全体用・個別用）を問わず bp で**加算**し、
 * 最後に 1 回だけ掛ける（`value × (10000 + Σbp) / 10000`）。乗算の連鎖はしない。
 * 英雄スキルは M3 では含めない（行軍・戦闘の文脈でだけ効かせる。M4）。
 */
object BuffAggregator {
    /** [state] の Boost と研究から [BuffSet] を作る。 */
    fun compute(
        state: GameState,
        data: GameData,
    ): BuffSet {
        val sum = HashMap<BuffKey, Long>()

        fun add(
            key: BuffKey,
            bp: Long,
        ) {
            sum[key] = (sum[key] ?: 0L) + bp
        }
        for (boost in state.boosts) {
            when (boost.kind) {
                BoostKind.PRODUCTION -> add(BuffKey.PRODUCTION_ALL, boost.bp.toLong())
                BoostKind.BUILD_SPEED -> add(BuffKey.BUILD_SPEED, boost.bp.toLong())
            }
        }
        for ((nodeId, level) in state.research) {
            val node = data.research[nodeId] ?: continue
            if (level <= 0) continue
            val l = node.level(minOf(level, node.maxLevel))
            add(l.effectKey, l.effectBp)
        }
        return BuffSet(sum)
    }
}
