package com.ttmrase.crownfall.core.research

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.data.ResearchPrereq
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Buffs
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchTest {
    private val data = TestSupport.data

    /** Academy を Lv[academy] にして資源を満たした状態。 */
    private fun ready(academy: Int = 3): GameState =
        TestSupport.withAcademy(TestSupport.advanced(), academy).copy(resources = RICH)

    @Test
    fun startResearchChargesCostAndSchedulesCompletion() {
        val s = ready()
        val r = Actions.startResearch(s, "eco_food", data).ok()
        val level1 = data.research.getValue("eco_food").level(1)
        assertEquals(RICH.minusOrNull(level1.cost), r.resources)
        val job = r.timers.single()
        assertEquals(TimerKind.RESEARCH, job.kind)
        assertEquals("eco_food", job.researchId)
        assertEquals(1, job.targetLevel)
        assertEquals(s.nowEpochSec + level1.seconds, job.endEpochSec)
        assertEquals(2, r.nextJobId)
    }

    @Test
    fun completionRaisesTheLevelAndEmitsTheEvent() {
        val s = Actions.startResearch(ready(), "eco_food", data).ok().copy(resources = Resources.ZERO)
        val end = s.timers.single().endEpochSec - s.nowEpochSec
        val before = Simulation.tick(s, end - 1, data)
        assertEquals(null, before.state.research["eco_food"])
        val done = Simulation.tick(s, end, data)
        assertEquals(1, done.state.research["eco_food"])
        assertTrue(done.state.timers.isEmpty())
        assertEquals(
            listOf(GameEvent.ResearchCompleted("eco_food", 1), GameEvent.TimerCompleted(s.timers.single())),
            done.events,
        )
        // 次のレベルへ（Lv2 は Academy 3 が必要）
        val again = Actions.startResearch(done.state.copy(resources = RICH), "eco_food", data).ok()
        assertEquals(2, again.timers.single().targetLevel)
    }

    @Test
    fun failuresFollowTheDocumentedOrder() {
        val s = ready(academy = 1)
        assertEquals(ActionFailure.NodeNotFound, Actions.startResearch(s, "nope", data).failure())
        val maxed = s.copy(research = mapOf("eco_food" to 10))
        assertEquals(ActionFailure.MaxLevel, Actions.startResearch(maxed, "eco_food", data).failure())

        val running = Actions.startResearch(s, "eco_food", data).ok()
        assertEquals(ActionFailure.AlreadyResearching, Actions.startResearch(running, "eco_food", data).failure())

        // 前提: Lv2 は Academy 3、上位ノードは基礎ノード Lv5
        val l2 = s.copy(research = mapOf("eco_food" to 1))
        assertEquals(
            ActionFailure.ResearchPrereqNotMet(3, emptyList()),
            Actions.startResearch(l2, "eco_food", data).failure(),
        )
        val adv = Actions.startResearch(TestSupport.withAcademy(s, 25), "eco_food_adv", data).failure()
        assertEquals(ActionFailure.ResearchPrereqNotMet(null, listOf(ResearchPrereq("eco_food", 5))), adv)
        val both = Actions.startResearch(s, "eco_food_adv", data).failure()
        assertEquals(ActionFailure.ResearchPrereqNotMet(5, listOf(ResearchPrereq("eco_food", 5))), both)

        // 前提 OK でもキューが埋まっていれば QueueFull（資源不足より先）
        val poor = running.copy(resources = Resources.ZERO)
        assertEquals(ActionFailure.QueueFull, Actions.startResearch(poor, "eco_wood", data).failure())

        // 資源不足は正確な不足量
        val lacking = s.copy(resources = Resources.ZERO)
        val cost = data.research.getValue("eco_food").level(1).cost
        assertEquals(ActionFailure.NotEnoughResources(cost), Actions.startResearch(lacking, "eco_food", data).failure())
        // Academy 未建設では Lv1 から前提不足
        val noAcademy = TestSupport.advanced().copy(resources = RICH)
        assertEquals(
            ActionFailure.ResearchPrereqNotMet(1, emptyList()),
            Actions.startResearch(noAcademy, "eco_food", data).failure(),
        )
    }

    @Test
    fun researchSpeedIsAppliedAndRoundedUp() {
        val base = ready()
        val plain = Actions.startResearch(base, "eco_food", data).ok().timers.single()
        val fast =
            Actions.startResearch(
                base.copy(research = mapOf("dev_research" to 10)),
                "eco_gold",
                data,
            ).ok().timers.single()
        val seconds = data.research.getValue("eco_gold").level(1).seconds
        assertEquals(Buffs.speedSeconds(seconds, 3000), fast.endEpochSec - fast.startEpochSec)
        assertEquals((seconds * 10_000 + 12_999) / 13_000, fast.endEpochSec - fast.startEpochSec)
        assertTrue(fast.endEpochSec - fast.startEpochSec <= plain.endEpochSec - plain.startEpochSec + seconds)
        assertEquals(1, Buffs.speedSeconds(1, 1_000_000))
        assertEquals(BuffKey.RESEARCH_SPEED, data.research.getValue("dev_research").level(1).effectKey)
    }

    @Test
    fun completionChangesTheProductionRateAtTheExactBoundary() {
        val s = Actions.startResearch(ready(), "eco_food", data).ok().copy(resources = Resources.ZERO)
        val end = s.timers.single().endEpochSec - s.nowEpochSec
        val total = 3600L * 10
        val r = Simulation.tick(s, total, data).state
        // 完了まで 300/時、完了後は +200bp（= 306/時）
        val expected = (300 * end + 306 * (total - end)) / 3600
        assertEquals(expected, r.resources.food)
        assertEquals(306, Simulation.hourlyRate(r, ResourceType.FOOD, data))
    }

    @Test
    fun splitInvarianceHoldsAcrossResearchCompletion() {
        val rnd = java.util.Random(77)
        val s = Actions.startResearch(ready(), "eco_food", data).ok().copy(resources = Resources(0, 0, 0, 0))
        repeat(60) { n ->
            val total = (rnd.nextInt(30_000) + 1).toLong()
            val whole = Simulation.tick(s, total, data)
            var cur = s
            val events = ArrayList<GameEvent>()
            var left = total
            while (left > 0) {
                val piece = minOf(left, (rnd.nextInt(900) + 1).toLong())
                val r = Simulation.tick(cur, piece, data)
                cur = r.state
                events.addAll(r.events)
                left -= piece
            }
            assertEquals(whole.state, cur, "state differs (case $n, total=$total)")
            assertEquals(whole.events, events, "events differ (case $n, total=$total)")
        }
    }

    @Test
    fun researchPowerIsAddedToTotal() {
        val s = TestSupport.newGame()
        assertEquals(0, PowerCalculator.researchPower(s, data))
        val researched = s.copy(research = mapOf("eco_food" to 3, "dev_build" to 1, "ghost" to 4, "eco_wood" to 0))
        val expected =
            data.research.getValue("eco_food").level(3).power +
                data.research.getValue("dev_build").level(1).power
        assertEquals(expected, PowerCalculator.researchPower(researched, data))
        assertEquals(
            PowerCalculator.buildingPower(researched, data) + expected,
            PowerCalculator.total(researched, data),
        )
        assertEquals(T0, s.nowEpochSec)
    }
}
