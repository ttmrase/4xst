package com.ttmrase.crownfall.core.research

import com.ttmrase.crownfall.core.data.ResearchLevel
import com.ttmrase.crownfall.core.data.ResearchPrereq
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState

/** 研究の前提判定。 */
object ResearchRules {
    /** 現在の Academy の最大 Lv（未建設は 0）。 */
    fun academyLevel(state: GameState): Int =
        state.slots.filter { it.type == BuildingType.ACADEMY }.maxOfOrNull { it.level } ?: 0

    /** [target] に必要な Academy Lv が足りていなければその Lv、足りていれば null。 */
    fun missingAcademy(
        state: GameState,
        target: ResearchLevel,
    ): Int? = target.academyLevel.takeIf { academyLevel(state) < it }

    /** [target] に必要な先行ノードのうち、まだ足りていないもの。 */
    fun missingNodes(
        state: GameState,
        target: ResearchLevel,
    ): List<ResearchPrereq> = target.nodes.filter { (state.research[it.nodeId] ?: 0) < it.level }
}
