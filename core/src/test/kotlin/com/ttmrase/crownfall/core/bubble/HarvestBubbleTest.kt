package com.ttmrase.crownfall.core.bubble

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HarvestBubbleTest {
    private val data = TestSupport.data
    private val interval = data.rules.bubbleIntervalSec

    private fun farm(s: com.ttmrase.crownfall.core.model.GameState) = TestSupport.slotOf(s, BuildingType.FARM)

    @Test
    fun rulesAreTenMinutesAndThreeMinutesOfOutput() {
        assertEquals(600, interval)
        assertEquals(3, data.rules.bubbleMinutes)
    }

    @Test
    fun everyBuiltProducerHasABubbleAtTheStartAndUnbuiltOnesDoNot() {
        val s = TestSupport.newGame()
        val ready = HarvestBubble.ready(s, data)
        val types = ready.map { id -> s.slots.first { it.slotId == id }.type }
        assertEquals(
            listOf(BuildingType.FARM, BuildingType.SAWMILL, BuildingType.QUARRY, BuildingType.GOLD_MINE),
            types.sortedBy { it.ordinal },
        )
        assertEquals(Resources.ZERO, HarvestBubble.contents(s, TestSupport.slotOf(s, BuildingType.WALL), data))
    }

    @Test
    fun contentsAreMinutesOfTheBuildingsOwnOutput() {
        val s = TestSupport.newGame()
        val perHour = data.building(BuildingType.FARM).effect(1, "production_per_hour")
        assertEquals(perHour * 3 / 60, HarvestBubble.contents(s, farm(s), data).food)
    }

    @Test
    fun collectingPaysOnceThenWaitsExactlyTheInterval() {
        val s0 = TestSupport.newGame()
        val id = farm(s0)
        val s1 = Actions.collectBubble(s0, id, data).ok()
        assertEquals(s0.resources.food + HarvestBubble.contents(s0, id, data).food, s1.resources.food)
        assertTrue(id !in HarvestBubble.ready(s1, data))
        assertIs<ActionResult.Failure>(Actions.collectBubble(s1, id, data))
        val before = Simulation.tick(s1, interval - 1, data).state
        assertTrue(id !in HarvestBubble.ready(before, data))
        val at = Simulation.tick(s1, interval, data).state
        assertTrue(id in HarvestBubble.ready(at, data))
    }

    @Test
    fun bubblesNeverAccumulateEvenAfterADayOffline() {
        val s0 = Actions.collectAllBubbles(TestSupport.newGame(), data).ok()
        val day = Simulation.tick(s0, 24 * 3600, data).state
        assertEquals(4, HarvestBubble.ready(day, data).size)
        val a = Actions.collectAllBubbles(day, data).ok()
        assertTrue(HarvestBubble.ready(a, data).isEmpty())
        assertEquals(ActionFailure.NothingToClaim, (Actions.collectAllBubbles(a, data) as ActionResult.Failure).reason)
    }

    @Test
    fun collectAllTakesEveryReadyBubbleAndUnknownSlotsFail() {
        val s = TestSupport.newGame()
        val all = Actions.collectAllBubbles(s, data).ok()
        assertEquals(setOf(farm(s)) + HarvestBubble.ready(s, data).toSet(), all.lastBubbleCollect.keys)
        assertEquals(ActionFailure.SlotNotFound, (Actions.collectBubble(s, 9999, data) as ActionResult.Failure).reason)
        // 建っていない畑（Lv0）は何も出ない
        val unbuilt = s.slots.first { it.type == BuildingType.FARM && it.level == 0 }.slotId
        assertEquals(
            ActionFailure.NothingToClaim,
            (Actions.collectBubble(s, unbuilt, data) as ActionResult.Failure).reason,
        )
    }

    @Test
    fun aFullWarehouseKeepsTheBubbleAndCapsPartialGains() {
        val s = TestSupport.newGame()
        val cap = Simulation.storageCap(s, data)
        val full = s.copy(resources = s.resources.with(com.ttmrase.crownfall.core.model.ResourceType.FOOD, cap))
        assertEquals(
            ActionFailure.NothingToClaim,
            (Actions.collectBubble(full, farm(s), data) as ActionResult.Failure).reason,
        )
        assertTrue(farm(s) in HarvestBubble.ready(full, data))
        val nearly = s.copy(resources = s.resources.with(com.ttmrase.crownfall.core.model.ResourceType.FOOD, cap - 1))
        assertEquals(cap, Actions.collectBubble(nearly, farm(s), data).ok().resources.food)
    }

    @Test
    fun aProductionBoostRaisesTheContents() {
        val s = TestSupport.newGame()
        val boosted =
            Actions.addBoost(
                s,
                com.ttmrase.crownfall.core.model.Boost(
                    "t",
                    com.ttmrase.crownfall.core.model.BoostKind.PRODUCTION,
                    5000,
                    s.nowEpochSec + 3600,
                ),
            ).ok()
        assertTrue(HarvestBubble.contents(boosted, farm(s), data).food > HarvestBubble.contents(s, farm(s), data).food)
    }

    @Test
    fun tickSplitInvarianceHoldsWithBubbleStamps() {
        val s = Actions.collectAllBubbles(TestSupport.newGame(), data).ok()
        SplitInvariance.assertInvariant(s, 3 * 3600L, data, java.util.Random(7), extraBoundaries = listOf(interval))
    }

    @Test
    fun collectingIsDeterministic() {
        val a = Actions.collectAllBubbles(TestSupport.newGame(), data).ok()
        val b = Actions.collectAllBubbles(TestSupport.newGame(), data).ok()
        assertEquals(a, b)
    }
}
