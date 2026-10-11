package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiQueriesTest {
    private val data = TestSupport.data

    @Test
    fun everyBuildingTypeHasASection() {
        val sections = BuildingType.entries.associateWith { UiQueries.sectionOf(it) }
        assertEquals(BuildingSection.CORE, sections[BuildingType.CASTLE])
        assertEquals(BuildingSection.RESOURCE, sections[BuildingType.FARM])
        assertEquals(BuildingSection.RESOURCE, sections[BuildingType.WAREHOUSE])
        assertEquals(BuildingSection.MILITARY, sections[BuildingType.WALL])
        assertEquals(BuildingSection.MILITARY, sections[BuildingType.HOSPITAL])
        assertEquals(BuildingSection.OTHER, sections[BuildingType.ACADEMY])
        assertEquals(BuildingSection.OTHER, sections[BuildingType.TAVERN])
        assertEquals(BuildingSection.entries.toSet(), sections.values.toSet())
    }

    @Test
    fun previewForAffordableUpgrade() {
        val s = TestSupport.newGame()
        val castle = TestSupport.slotOf(s, BuildingType.CASTLE)
        val p = assertNotNull(UiQueries.upgradePreview(s, castle, data))
        assertEquals(2, p.nextLevel)
        assertEquals(data.building(BuildingType.CASTLE).level(2).cost, p.cost)
        assertTrue(p.missing.isZero)
        assertNull(p.blocker)
        assertTrue(p.unmetPrereqs.isEmpty())
        assertEquals(data.building(BuildingType.CASTLE).level(2).seconds, p.durationSec)
        assertTrue(p.currentEffects.isNotEmpty() && p.nextEffects.isNotEmpty())
        assertNull(p.upgrading)
    }

    @Test
    fun previewReportsMissingResourcesExactly() {
        val s = TestSupport.newGame().copy(resources = Resources(food = 100, wood = 0, stone = 0, gold = 0))
        val castle = TestSupport.slotOf(s, BuildingType.CASTLE)
        val p = assertNotNull(UiQueries.upgradePreview(s, castle, data))
        val cost = data.building(BuildingType.CASTLE).level(2).cost
        assertEquals(cost.missingFrom(s.resources), p.missing)
        assertEquals(ActionFailure.NotEnoughResources(p.missing), p.blocker)
    }

    @Test
    fun previewReportsUnmetPrereqsAndBoostedDuration() {
        val s = TestSupport.newGame().copy(resources = RICH)
        val farm = TestSupport.slotOf(s, BuildingType.FARM)
        val p = assertNotNull(UiQueries.upgradePreview(s, farm, data))
        assertEquals(listOf(Prereq(BuildingType.CASTLE, 2)), p.unmetPrereqs)
        assertEquals(ActionFailure.PrereqNotMet(p.unmetPrereqs), p.blocker)

        val a = TestSupport.advanced().copy(resources = RICH)
        val boosted = Actions.addBoost(a, Boost("b", BoostKind.BUILD_SPEED, 10_000, a.nowEpochSec + 999)).ok()
        val plain = assertNotNull(UiQueries.upgradePreview(a, farm, data))
        val fast = assertNotNull(UiQueries.upgradePreview(boosted, farm, data))
        assertEquals((plain.durationSec + 1) / 2, fast.durationSec)
    }

    @Test
    fun previewForMaxLevelAndUpgradingAndUnknownSlot() {
        val s = TestSupport.advanced().copy(resources = RICH)
        assertNull(UiQueries.upgradePreview(s, 999, data))
        val maxed = s.copy(slots = s.slots.map { if (it.slotId == 1) it.copy(level = 25) else it })
        val m = assertNotNull(UiQueries.upgradePreview(maxed, 1, data))
        assertNull(m.nextLevel)
        assertEquals(ActionFailure.MaxLevel, m.blocker)
        assertTrue(m.cost.isZero)
        val started = Actions.startUpgrade(s, 1, data).ok()
        val u = assertNotNull(UiQueries.upgradePreview(started, 1, data))
        assertEquals(started.timers.single(), u.upgrading)
        assertEquals(ActionFailure.AlreadyUpgrading, u.blocker)
        // 未建設（Lv0）では現在の効果が空
        val fresh = TestSupport.newGame()
        val unbuilt = assertNotNull(UiQueries.upgradePreview(fresh, fresh.slots.first { it.level == 0 }.slotId, data))
        assertTrue(unbuilt.currentEffects.isEmpty())
    }

    private fun job(kind: TimerKind) = TimerJob(1, kind, 1, 2, 0, 1000)

    @Test
    fun usableItemsFilterByCategoryAndSort() {
        val s =
            TestSupport.newGame().copy(
                items =
                    mapOf(
                        "speedup_build_5m" to 2,
                        "speedup_general_1m" to 3,
                        "speedup_train_5m" to 4,
                        "speedup_build_15m" to 0,
                        "unknown" to 9,
                    ),
            )
        val usable = UiQueries.usableItems(s, job(TimerKind.BUILD), data)
        assertEquals(listOf("speedup_general_1m", "speedup_build_5m"), usable.map { it.item.id })
        assertEquals(listOf(3, 2), usable.map { it.owned })
        assertEquals(
            listOf(
                "speedup_general_1m",
                "speedup_train_5m",
            ),
            UiQueries.usableItems(s, job(TimerKind.TRAIN), data).map {
                it.item.id
            },
        )
    }

    @Test
    fun itemsNeededRoundsUpAndCapsAtOwned() {
        assertEquals(0, UiQueries.itemsNeeded(0, 60, 5))
        assertEquals(0, UiQueries.itemsNeeded(100, 0, 5))
        assertEquals(0, UiQueries.itemsNeeded(100, 60, 0))
        assertEquals(1, UiQueries.itemsNeeded(60, 60, 5))
        assertEquals(2, UiQueries.itemsNeeded(61, 60, 5))
        assertEquals(5, UiQueries.itemsNeeded(10_000, 60, 5))
        assertEquals(1, UiQueries.itemsNeeded(1, 86_400, 5))
    }

    @Test
    fun remainingAndProgress() {
        val s = TestSupport.newGame().copy(nowEpochSec = 500)
        val j = job(TimerKind.BUILD)
        assertEquals(500, UiQueries.remainingSec(s, j))
        assertEquals(0.5f, UiQueries.progress(s, j))
        assertEquals(0, UiQueries.remainingSec(s.copy(nowEpochSec = 2000), j))
        assertEquals(1f, UiQueries.progress(s.copy(nowEpochSec = 2000), j))
        assertEquals(0f, UiQueries.progress(s.copy(nowEpochSec = -5), j))
        assertEquals(1f, UiQueries.progress(s, j.copy(endEpochSec = 0)))
    }
}
