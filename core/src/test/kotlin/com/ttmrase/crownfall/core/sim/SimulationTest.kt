package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SimulationTest {
    private val data = TestSupport.data
    private val farm0 = 1 // slot ids are assigned in BuildingType order: castle=0, farm=1..4

    private fun tick(
        s: GameState,
        dt: Long,
    ) = Simulation.tick(s, dt, data)

    @Test
    fun newGameLayout() {
        val s = TestSupport.newGame()
        assertEquals(27, s.slots.size)
        assertEquals(4, s.slots.count { it.type == BuildingType.FARM })
        assertEquals(1, s.slots.count { it.type == BuildingType.FARM && it.level == 1 })
        assertEquals(1, s.slots.first { it.type == BuildingType.CASTLE }.level)
        assertEquals(farm0, TestSupport.slotOf(s, BuildingType.FARM))
        assertEquals(42, s.rngState)
        assertEquals(s.slots.map { it.slotId }, s.slots.indices.toList())
    }

    @Test
    fun productionAccumulatesWithRemainder() {
        val s = TestSupport.newGame()
        val rate = Simulation.hourlyRate(s, ResourceType.FOOD, data)
        assertEquals(300, rate)
        assertEquals(s.resources.food + 300, tick(s, 3600).state.resources.food)
        val one = tick(s, 1).state
        assertEquals(s.resources.food, one.resources.food)
        assertEquals(300, one.productionRemainder.food)
        val twelve = tick(s, 12).state // 3600 / 300 = 12s per unit
        assertEquals(s.resources.food + 1, twelve.resources.food)
        assertEquals(0, twelve.productionRemainder.food)
        assertEquals(T0 + 12, twelve.nowEpochSec)
    }

    @Test
    fun tickDoesNotMutateInput() {
        val s = TestSupport.newGame()
        val copy = s.copy()
        tick(s, 5000)
        assertEquals(copy, s)
    }

    @Test
    fun negativeDtRejected() {
        assertFailsWith<IllegalArgumentException> { tick(TestSupport.newGame(), -1) }
    }

    @Test
    fun deterministic() {
        val a = prepared()
        val b = prepared()
        assertEquals(tick(a, 123_456), tick(b, 123_456))
    }

    @Test
    fun productionStopsAtCapAndStorageFullFiresOnce() {
        val s0 = TestSupport.newGame()
        val cap = Simulation.storageCap(s0, data)
        assertEquals(30_000, cap)
        val s = s0.copy(resources = Resources(cap - 10, 0, 0, 0))
        val r = tick(s, 36_000) // +3000 food if uncapped
        assertEquals(cap, r.state.resources.food)
        assertEquals(0, r.state.productionRemainder.food)
        assertEquals(
            listOf(GameEvent.StorageFull(ResourceType.FOOD)),
            r.events.filterIsInstance<GameEvent.StorageFull>(),
        )
        assertTrue(ResourceType.FOOD in r.state.storageFullFlags)
        val again = tick(r.state, 36_000)
        assertTrue(again.events.none { it is GameEvent.StorageFull })
        assertEquals(cap, again.state.resources.food)
    }

    @Test
    fun rewardsMayExceedCapButProductionStays0() {
        val s0 = TestSupport.newGame()
        val cap = Simulation.storageCap(s0, data)
        val s = Actions.addResources(s0, Resources(cap + 500, 0, 0, 0)).ok()
        assertEquals(s0.resources.food + cap + 500, s.resources.food)
        val r = tick(s, 7200)
        assertEquals(s.resources.food, r.state.resources.food)
        assertTrue(r.events.any { it == GameEvent.StorageFull(ResourceType.FOOD) })
    }

    @Test
    fun storageFlagClearsAfterSpending() {
        val s0 = TestSupport.newGame()
        val cap = Simulation.storageCap(s0, data)
        val full = tick(s0.copy(resources = Resources(cap, cap, cap, cap)), 10).state
        assertEquals(ResourceType.entries.toSet(), full.storageFullFlags)
        val started = Actions.startUpgrade(full, TestSupport.slotOf(full, BuildingType.CASTLE), data).ok()
        assertTrue(ResourceType.STONE !in started.storageFullFlags || started.resources.stone >= cap)
        assertTrue(ResourceType.FOOD !in started.storageFullFlags)
        val refilled = tick(started, 3600 * 24 * 3).state
        assertTrue(ResourceType.FOOD in refilled.storageFullFlags || refilled.resources.food < cap)
    }

    @Test
    fun timerCompletionBoundaries() {
        val s0 = TestSupport.advanced().copy(resources = RICH)
        val s = Actions.startUpgrade(s0, farm0, data).ok().copy(resources = Resources.ZERO)
        val job = s.timers.single()
        val dur = job.endEpochSec - job.startEpochSec
        assertEquals(data.building(BuildingType.FARM).level(2).seconds, dur)
        val before = tick(s, dur - 1)
        assertEquals(1, before.state.slots[farm0].level)
        assertTrue(before.events.none { it is GameEvent.TimerCompleted })
        val exact = tick(s, dur)
        assertEquals(2, exact.state.slots[farm0].level)
        assertTrue(exact.state.timers.isEmpty())
        assertEquals(
            listOf(GameEvent.BuildingLevelUp(farm0, BuildingType.FARM, 2), GameEvent.TimerCompleted(job)),
            exact.events,
        )
        val after = tick(s, dur + 1)
        assertEquals(2, after.state.slots[farm0].level)
        assertEquals(s.nowEpochSec + dur + 1, after.state.nowEpochSec)
    }

    @Test
    fun zeroDtSettlesOverdueJobs() {
        val s0 = TestSupport.advanced().copy(resources = RICH)
        val s = Actions.startUpgrade(s0, farm0, data).ok()
        val overdue = s.copy(timers = s.timers.map { it.copy(endEpochSec = s.nowEpochSec) })
        val r = tick(overdue, 0)
        assertEquals(2, r.state.slots[farm0].level)
        assertEquals(overdue.nowEpochSec, r.state.nowEpochSec)
    }

    @Test
    fun upgradingBuildingKeepsProducingAtOldLevel() {
        val s0 = TestSupport.advanced().copy(resources = RICH)
        val s = Actions.startUpgrade(s0, farm0, data).ok().copy(resources = Resources.ZERO)
        val dur = s.timers.single().endEpochSec - s.nowEpochSec
        val mid = tick(s, dur - 1).state
        assertEquals((dur - 1) * 300 / 3600, mid.resources.food)
        assertEquals(1, mid.slots[farm0].level)
    }

    @Test
    fun productionRateChangesAtCompletionBoundary() {
        val funded = TestSupport.advanced().copy(resources = RICH)
        val s =
            Actions.startUpgrade(funded, farm0, data).ok().let {
                it.copy(resources = Resources.ZERO, productionRemainder = Resources.ZERO)
            }
        val dur = s.timers.single().endEpochSec - s.nowEpochSec
        val total = 3600 * 10L
        val r = tick(s, total).state
        val l2 = data.building(BuildingType.FARM).effect(2, "production_per_hour")
        val expected = (300 * dur + l2 * (total - dur)) / 3600
        assertEquals(expected, r.resources.food)
    }

    @Test
    fun startUpgradeFailuresInDocumentedOrder() {
        val s = TestSupport.newGame()
        assertEquals(
            ActionFailure.SlotNotFound,
            Actions.startUpgrade(s, 999, data).let { (it as ActionResult.Failure).reason },
        )
        val maxed = s.copy(slots = s.slots.map { if (it.slotId == farm0) it.copy(level = 25) else it })
        assertEquals(ActionFailure.MaxLevel, (Actions.startUpgrade(maxed, farm0, data) as ActionResult.Failure).reason)

        val rich = TestSupport.advanced().copy(resources = RICH)
        val started = Actions.startUpgrade(rich, farm0, data).ok()
        assertEquals(
            ActionFailure.AlreadyUpgrading,
            (Actions.startUpgrade(started, farm0, data) as ActionResult.Failure).reason,
        )

        // 前提: Castle Lv1 のまま Farm Lv6 は不可。0→1 の建設は Castle >= 1 で可能。
        assertTrue(Actions.startUpgrade(rich, farm0 + 1, data) is ActionResult.Success)
        val farmLv5 = rich.copy(slots = rich.slots.map { if (it.slotId == farm0) it.copy(level = 5) else it })
        val pf = (Actions.startUpgrade(farmLv5, farm0, data) as ActionResult.Failure).reason
        assertEquals(
            listOf(com.ttmrase.crownfall.core.model.Prereq(BuildingType.CASTLE, 6)),
            (pf as ActionFailure.PrereqNotMet).missing,
        )

        // キュー満杯は資源不足より先
        val poor = started.copy(resources = Resources.ZERO)
        assertEquals(
            ActionFailure.QueueFull,
            (Actions.startUpgrade(poor, farm0 + 1, data) as ActionResult.Failure).reason,
        )

        // 資源不足は正確な不足量を返す
        val lacking = TestSupport.advanced().copy(resources = Resources.ZERO)
        val cost = data.building(BuildingType.FARM).level(2).cost
        val r = (Actions.startUpgrade(lacking.copy(slots = lacking.slots), farm0, data) as ActionResult.Failure).reason
        assertEquals(ActionFailure.NotEnoughResources(cost), r)
    }

    @Test
    fun successDeductsCostAndAppliesBuildSpeedBoost() {
        val s0 = TestSupport.advanced().copy(resources = RICH)
        val cost = data.building(BuildingType.FARM).level(2).cost
        val plain = Actions.startUpgrade(s0, farm0, data).ok()
        assertEquals(RICH.minusOrNull(cost), plain.resources)
        assertEquals(1, plain.timers.single().id)
        assertEquals(2, plain.nextJobId)

        val boosted = Actions.addBoost(s0, Boost("b", BoostKind.BUILD_SPEED, 10_000, T0 + 1_000_000)).ok()
        val job = Actions.startUpgrade(boosted, farm0, data).ok().timers.single()
        val base = data.building(BuildingType.FARM).level(2).seconds
        assertEquals((base + 1) / 2, job.endEpochSec - job.startEpochSec) // 2 倍速、切り上げ
        // 最小 1 秒
        val huge = Actions.addBoost(s0, Boost("b", BoostKind.BUILD_SPEED, 1_000_000_000, T0 + 1_000_000)).ok()
        val j = Actions.startUpgrade(huge, farm0, data).ok().timers.single()
        assertEquals(1, j.endEpochSec - j.startEpochSec)
    }

    @Test
    fun secondBuilderQueueAndExpiry() {
        val s0 = TestSupport.advanced().copy(resources = RICH)
        assertEquals(1, Simulation.buildQueueCapacity(s0, data))
        val s = Actions.grantSecondBuilder(s0, 100).ok()
        assertEquals(2, Simulation.buildQueueCapacity(s, data))
        val a = Actions.startUpgrade(s, farm0, data).ok()
        val b = Actions.startUpgrade(a, farm0 + 1, data).ok()
        assertEquals(2, b.timers.size)
        // 期限が切れても進行中は最後まで進む。新規開始だけできない。
        val later = tick(b, 101).state
        assertEquals(1, Simulation.buildQueueCapacity(later, data))
        val done = tick(b, 100_000).state
        assertTrue(done.timers.isEmpty())
        val extended = Actions.grantSecondBuilder(s, 50).ok()
        assertEquals(T0 + 150, extended.secondBuilderUntilEpochSec)
        assertEquals(ActionFailure.InvalidArgument, (Actions.grantSecondBuilder(s, 0) as ActionResult.Failure).reason)
        val expired = b.copy(secondBuilderUntilEpochSec = T0 - 1)
        assertEquals(
            ActionFailure.QueueFull,
            (Actions.startUpgrade(expired, farm0 + 2, data) as ActionResult.Failure).reason,
        )
        assertTrue(tick(expired, 100_000).state.timers.isEmpty())
    }

    @Test
    fun boostsExpireAtBoundaryAndFireEvent() {
        val s0 = TestSupport.newGame()
        val s = Actions.addBoost(s0, Boost("prod", BoostKind.PRODUCTION, 10_000, T0 + 1800)).ok()
        assertEquals(600, Simulation.hourlyRate(s, ResourceType.FOOD, data))
        val r = tick(s, 3600)
        // 前半 1800 秒は 2 倍、後半は 1 倍
        assertEquals(s0.resources.food + 300 + 150, r.state.resources.food)
        assertEquals(listOf<GameEvent>(GameEvent.BoostExpired("prod")), r.events)
        assertTrue(r.state.boosts.isEmpty())
        // 同 id は置換
        val replaced = Actions.addBoost(s, Boost("prod", BoostKind.PRODUCTION, 5000, T0 + 10)).ok()
        assertEquals(1, replaced.boosts.size)
    }

    @Test
    fun saturatesInsteadOfOverflowing() {
        val s0 = TestSupport.newGame()
        val s = Actions.addResources(s0, Resources(Long.MAX_VALUE, 0, 0, 0)).ok()
        assertEquals(Long.MAX_VALUE, s.resources.food)
        val huge = Actions.addBoost(s0, Boost("x", BoostKind.PRODUCTION, Int.MAX_VALUE, Long.MAX_VALUE)).ok()
        val r = tick(huge, 1_000_000_000_000L)
        assertTrue(r.state.resources.food >= 0)
        assertEquals(Long.MAX_VALUE, tick(s0.copy(nowEpochSec = Long.MAX_VALUE - 5), 100).state.nowEpochSec)
    }

    @Test
    fun rewardActions() {
        val s = TestSupport.newGame()
        assertEquals(s.gems + 50, Actions.addGems(s, 50).ok().gems)
        assertEquals(ActionFailure.InvalidArgument, (Actions.addGems(s, -1) as ActionResult.Failure).reason)
        assertEquals(Long.MAX_VALUE, Actions.addGems(s.copy(gems = Long.MAX_VALUE - 1), 100).ok().gems)
        assertEquals(5, Actions.addItems(s, "speedup_build_5m", 5).ok().items["speedup_build_5m"])
        assertEquals(
            Int.MAX_VALUE,
            Actions.addItems(s.copy(items = mapOf("a" to Int.MAX_VALUE)), "a", 5).ok().items["a"],
        )
        assertEquals(ActionFailure.InvalidArgument, (Actions.addItems(s, "a", 0) as ActionResult.Failure).reason)
    }

    // ───── R3: 分割不変性 ─────

    private fun prepared(): GameState {
        var s = TestSupport.advanced(3, castle = 4).copy(resources = RICH)
        s = Actions.grantSecondBuilder(s, 4000).ok()
        s = Actions.addBoost(s, Boost("p", BoostKind.PRODUCTION, 2500, T0 + 900)).ok()
        s = Actions.addBoost(s, Boost("b", BoostKind.BUILD_SPEED, 5000, T0 + 100)).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.WAREHOUSE), data).ok()
        s = Actions.startUpgrade(s, farm0, data).ok()
        // 上限に近づけて StorageFull の発火も混ぜる
        return s.copy(resources = Resources(29_000, 5, 29_900, 0))
    }

    @Test
    fun splitInvarianceHolds() {
        val rnd = java.util.Random(20261010)
        val base = prepared()
        repeat(150) { n ->
            val total = (rnd.nextInt(200_000) + 1).toLong()
            val whole = tick(base, total)
            var s = base
            val events = ArrayList<GameEvent>()
            var left = total
            while (left > 0) {
                val piece = if (rnd.nextInt(4) == 0) 0L else minOf(left, (rnd.nextInt(5000) + 1).toLong())
                val r = tick(s, piece)
                s = r.state
                events.addAll(r.events)
                left -= piece
            }
            assertEquals(whole.state, s, "state differs (case $n, total=$total)")
            assertEquals(whole.events, events, "events differ (case $n, total=$total)")
        }
    }

    @Test
    fun splitInvarianceAcrossCapAndStorageLift() {
        val s0 = TestSupport.advanced(2, castle = 3).copy(resources = RICH)
        val cap = Simulation.storageCap(s0, data)
        val withWarehouse =
            Actions.startUpgrade(s0, TestSupport.slotOf(s0, BuildingType.WAREHOUSE), data).ok()
                .copy(resources = RICH.copy(food = cap - 1))
        val end = withWarehouse.timers.single().endEpochSec - withWarehouse.nowEpochSec
        for (cut in listOf(1L, end - 1, end, end + 1, 40_000L)) {
            val whole = tick(withWarehouse, 100_000)
            val a = tick(withWarehouse, cut)
            val b = tick(a.state, 100_000 - cut)
            assertEquals(whole.state, b.state, "cut=$cut")
            assertEquals(whole.events, a.events + b.events, "cut=$cut")
        }
        val whole = tick(withWarehouse, 100_000)
        assertEquals(2, whole.events.count { it == GameEvent.StorageFull(ResourceType.FOOD) })
    }
}
