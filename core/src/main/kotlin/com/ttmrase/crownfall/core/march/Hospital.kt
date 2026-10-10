package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.military.Military
import com.ttmrase.crownfall.core.model.GameState

/** 戦闘後の負傷兵の収容。病院の空き容量を超えた分は死亡。 */
object Hospital {
    /** [wounded] を ID 順に収容し、(新しい状態, 入りきらず死亡した分) を返す。 */
    fun admit(
        state: GameState,
        wounded: Map<String, Long>,
        data: GameData,
    ): Pair<GameState, Map<String, Long>> {
        if (wounded.values.all { it <= 0 }) return state to emptyMap()
        val cap = Military.hospitalCapacity(state, data, BuffAggregator.compute(state, data))
        var free = maxOf(0L, cap - state.wounded.values.sum())
        val merged = HashMap(state.wounded)
        val overflow = LinkedHashMap<String, Long>()
        for (id in wounded.keys.sorted()) {
            val n = wounded.getValue(id)
            if (n <= 0) continue
            val taken = minOf(n, free)
            free -= taken
            if (taken > 0) merged[id] = (merged[id] ?: 0L) + taken
            if (n > taken) overflow[id] = n - taken
        }
        return state.copy(wounded = merged) to overflow
    }
}
