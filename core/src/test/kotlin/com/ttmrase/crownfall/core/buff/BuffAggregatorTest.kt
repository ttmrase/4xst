package com.ttmrase.crownfall.core.buff

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.data.ResearchLevel
import com.ttmrase.crownfall.core.data.ResearchNodeDef
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BuffAggregatorTest {
    private val data = TestSupport.data

    private fun node(
        id: String,
        key: BuffKey,
        bp: Long,
    ) = ResearchNodeDef(
        id,
        "test",
        1,
        listOf(ResearchLevel(1, Resources.ZERO, 1, 0, emptyList(), key, bp, 0)),
    )

    @Test
    fun emptyStateHasNoBuffs() {
        val set = BuffAggregator.compute(TestSupport.newGame(), data)
        BuffKey.entries.forEach { assertEquals(0, set[it], "$it") }
        assertEquals(set, BuffAggregator.compute(TestSupport.newGame(), data))
        assertNotEquals(0, set.hashCode() + 1)
        assertEquals("BuffSet({})", set.toString())
    }

    @Test
    fun boostsMapToTheirKeys() {
        val s0 = TestSupport.newGame()
        val s =
            Actions.addBoost(s0, Boost("p", BoostKind.PRODUCTION, 700, T0 + 99)).ok().let {
                Actions.addBoost(it, Boost("b", BoostKind.BUILD_SPEED, 300, T0 + 99)).ok()
            }
        val set = BuffAggregator.compute(s, data)
        assertEquals(700, set[BuffKey.PRODUCTION_ALL])
        assertEquals(300, set[BuffKey.BUILD_SPEED])
        ResourceType.entries.forEach { assertEquals(700, set.production(it)) }
    }

    @Test
    fun researchLevelsAreCumulativeAndAdditive() {
        val s =
            TestSupport.newGame().copy(
                research = mapOf("eco_food" to 3, "eco_food_adv" to 2, "unknown" to 5, "eco_wood" to 0),
            )
        val set = BuffAggregator.compute(s, data)
        // eco_food Lv3 = 600、eco_food_adv Lv2 = 300。同じ PRODUCTION_FOOD なので加算で 900。
        assertEquals(600L + 300L, set[BuffKey.PRODUCTION_FOOD])
        assertEquals(0, set[BuffKey.PRODUCTION_WOOD])
        assertEquals(900, set.production(ResourceType.FOOD))
    }

    @Test
    fun levelAboveMaxIsClampedToMax() {
        val s = TestSupport.newGame().copy(research = mapOf("dev_build" to 99))
        assertEquals(3000, BuffAggregator.compute(s, data)[BuffKey.BUILD_SPEED])
    }

    @Test
    fun globalAndSpecificProductionBuffsAddUpInsteadOfMultiplying() {
        // PRODUCTION_ALL 1000bp + PRODUCTION_FOOD 500bp → ×1.15（×1.1×1.05 = ×1.155 ではない）
        val custom = data.copy(research = data.research + ("t_food" to node("t_food", BuffKey.PRODUCTION_FOOD, 500)))
        val base = TestSupport.newGame()
        val s =
            Actions.addBoost(base, Boost("p", BoostKind.PRODUCTION, 1000, T0 + 9999)).ok()
                .copy(research = mapOf("t_food" to 1))
        val rate = Simulation.hourlyRate(s, ResourceType.FOOD, custom)
        assertEquals(345, rate) // 300 × 1.15
        assertNotEquals(346, rate) // 乗算なら floor(300 × 1.1 × 1.05) = 346
        // 他の資源は全体用だけ: 300 × 1.1
        assertEquals(330, Simulation.hourlyRate(s, ResourceType.WOOD, custom))
    }
}
