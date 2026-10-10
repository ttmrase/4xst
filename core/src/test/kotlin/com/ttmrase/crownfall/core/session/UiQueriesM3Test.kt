package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.data.ResearchPrereq
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiQueriesM3Test {
    private val data = TestSupport.data

    private fun barracks(level: Int = 1) = TestSupport.withLevel(TestSupport.advanced(), BuildingType.BARRACKS, level)

    @Test
    fun troopTiersLockUntilTheBuildingIsHighEnough() {
        val s =
            barracks(
                1,
            ).copy(resources = RICH, troops = mapOf("infantry_t1" to 7), wounded = mapOf("infantry_t1" to 3))
        val tiers = UiQueries.troopTiers(s, TroopType.INFANTRY, data)
        assertEquals(listOf(1, 2, 3, 4), tiers.map { it.troop.tier })
        assertEquals(listOf(false, true, true, true), tiers.map { it.locked })
        assertEquals(listOf(1, 6, 11, 16), tiers.map { it.requiredBuildingLevel })
        assertEquals(7, tiers[0].owned)
        assertEquals(3, tiers[0].wounded)
        assertEquals(100, tiers[0].maxTrainable) // Lv1 の train_capacity
        assertEquals(0, tiers[1].maxTrainable)
        // 建物が無い兵種は全 Tier が未解放
        val archers = UiQueries.troopTiers(s, TroopType.ARCHER, data)
        assertTrue(archers.all { it.locked && it.maxTrainable == 0L })
        val high = UiQueries.troopTiers(barracks(6).copy(resources = RICH), TroopType.INFANTRY, data)
        assertEquals(listOf(false, false, true, true), high.map { it.locked })
    }

    @Test
    fun maxTrainableIsBoundedByResourcesAndCapacity() {
        val troop = data.troops.getValue("infantry_t1")
        val capped = barracks().copy(resources = RICH)
        assertEquals(100, UiQueries.maxTrainable(capped, troop, data))
        // 食料 40 / 体 → 食料 400 なら 10 体
        val poor = barracks().copy(resources = Resources(food = 400, wood = 100_000))
        assertEquals(10, UiQueries.maxTrainable(poor, troop, data))
        assertEquals(0, UiQueries.maxTrainable(barracks().copy(resources = Resources.ZERO), troop, data))
        // 単価 0 の成分（石材・金貨）は制約にならない
        assertEquals(0, troop.cost.gold)
    }

    @Test
    fun trainPreviewMatchesTheAction() {
        val s = barracks().copy(resources = RICH)
        val p = assertNotNull(UiQueries.trainPreview(s, "infantry_t1", 10, data))
        val troop = data.troops.getValue("infantry_t1")
        assertEquals(troop.cost * 10, p.cost)
        assertTrue(p.missing.isZero)
        assertEquals(troop.trainSec * 10, p.durationSec)
        assertNull(p.blocker)
        assertNull(p.running)
        assertNull(UiQueries.trainPreview(s, "ghost", 1, data))
        // 上限超過・未解放・資源不足
        assertEquals(ActionFailure.InvalidArgument, UiQueries.trainPreview(s, "infantry_t1", 101, data)?.blocker)
        assertEquals(ActionFailure.TroopLocked(6), UiQueries.trainPreview(s, "infantry_t2", 1, data)?.blocker)
        val short = UiQueries.trainPreview(s.copy(resources = Resources(food = 100)), "infantry_t1", 10, data)
        assertEquals((troop.cost * 10).missingFrom(Resources(food = 100)), short?.missing)
        assertTrue(short?.blocker is ActionFailure.NotEnoughResources)
        // 進行中の訓練が見える
        val running = Actions.startTraining(s, "infantry_t1", 5, data).ok()
        val busy = assertNotNull(UiQueries.trainPreview(running, "infantry_t1", 5, data))
        assertEquals(running.timers.single(), busy.running)
        assertEquals(ActionFailure.QueueFull, busy.blocker)
        assertEquals(running.timers.single(), UiQueries.trainJob(running, running.timers.single().slotId!!))
        assertNull(UiQueries.trainJob(s, 0))
    }

    private fun lab(
        level: Int = 3,
        base: GameState = TestSupport.advanced(),
    ) = TestSupport.withAcademy(base, level).copy(resources = RICH)

    @Test
    fun researchPreviewReportsNextLevelPrereqsAndBlockers() {
        val s = lab()
        val p = assertNotNull(UiQueries.researchPreview(s, "eco_food", data))
        assertEquals(0, p.currentLevel)
        assertEquals(1, p.next?.level)
        assertNull(p.blocker)
        assertTrue(!p.locked)
        assertEquals(data.research.getValue("eco_food").level(1).seconds, p.durationSec)
        assertNull(UiQueries.researchPreview(s, "ghost", data))
        // 上位ノードは基礎ノード Lv5 が必要
        val adv = assertNotNull(UiQueries.researchPreview(s, "eco_food_adv", data))
        assertTrue(adv.locked)
        assertEquals(listOf(ResearchPrereq("eco_food", 5)), adv.missingNodes)
        assertTrue(adv.blocker is ActionFailure.ResearchPrereqNotMet)
        // Academy 未建設
        val none = assertNotNull(UiQueries.researchPreview(lab(0), "eco_food", data))
        assertEquals(1, none.missingAcademy)
        assertTrue(none.locked)
        // 最大 Lv
        val maxed =
            assertNotNull(UiQueries.researchPreview(s.copy(research = mapOf("eco_food" to 10)), "eco_food", data))
        assertNull(maxed.next)
        assertEquals(ActionFailure.MaxLevel, maxed.blocker)
        assertEquals(0, maxed.durationSec)
        assertTrue(maxed.missing.isZero)
    }

    @Test
    fun researchPreviewAppliesSpeedAndShowsTheRunningJob() {
        val s = lab().copy(research = mapOf("dev_research" to 10))
        val p = assertNotNull(UiQueries.researchPreview(s, "eco_food", data))
        val base = data.research.getValue("eco_food").level(1).seconds
        assertEquals((base * 10_000 + 12_999) / 13_000, p.durationSec)
        val started = Actions.startResearch(s, "eco_wood", data).ok()
        val running = assertNotNull(UiQueries.researchPreview(started, "eco_wood", data))
        assertEquals(started.timers.single(), running.running)
        assertEquals(ActionFailure.AlreadyResearching, running.blocker)
        assertEquals(started.timers.single(), UiQueries.researchJob(started))
        assertNull(UiQueries.researchJob(s))
        // 他のノードは QueueFull（前提を満たしているとき）
        assertEquals(ActionFailure.QueueFull, UiQueries.researchPreview(started, "eco_food", data)?.blocker)
        val poor = assertNotNull(UiQueries.researchPreview(lab().copy(resources = Resources.ZERO), "eco_food", data))
        assertEquals(data.research.getValue("eco_food").level(1).cost, poor.missing)
    }

    @Test
    fun healJobIsFound() {
        val s =
            TestSupport.withLevel(TestSupport.advanced(), BuildingType.HOSPITAL, 1)
                .copy(resources = RICH, wounded = mapOf("infantry_t1" to 10L))
        assertNull(UiQueries.healJob(s))
        val started = Actions.startHeal(s, mapOf("infantry_t1" to 10L), data).ok()
        assertEquals(started.timers.single(), UiQueries.healJob(started))
    }
}
