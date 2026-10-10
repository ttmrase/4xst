package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.combat.Armies
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.AiRally
import com.ttmrase.crownfall.core.model.GameState

/** AI 主催ラリーの援軍。参加ペルソナの城 Lv（castleLvCurve）から決定的に兵力を決める。 */
object AiRallies {
    private const val SECONDS_PER_DAY = 86_400L

    /** [rally] の参加者全員の援軍（兵種 ID → 数）。 */
    fun allyTroops(
        state: GameState,
        rally: AiRally,
        data: GameData,
    ): Map<String, Long> {
        val days = (state.nowEpochSec - state.alliance.startEpochSec) / SECONDS_PER_DAY
        val rules = data.allianceRules.rally
        val total = LinkedHashMap<String, Long>()
        for (id in rally.participants) {
            val def = data.personas.persona(id) ?: continue
            val troops = Personas.castleLv(def, days) * rules.troopsPerCastleLv
            for ((k, n) in Armies.split(troops, rules.allyMix, def.troopTier)) total.merge(k, n, Long::plus)
        }
        return total
    }
}
