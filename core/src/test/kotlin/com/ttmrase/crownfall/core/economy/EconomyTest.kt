package com.ttmrase.crownfall.core.economy

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EconomyTest {
    private val data = TestSupport.data

    private fun started() =
        Actions.startUpgrade(
            TestSupport.advanced().copy(resources = TestSupport.RICH),
            1,
            data,
        ).ok()

    @Test
    fun costCheckReturnsExactMissing() {
        val cost = Resources(100, 50, 30, 10)
        assertEquals(CostCheck.Ok, Costs.check(Resources(100, 50, 30, 10), cost))
        assertEquals(CostCheck.Missing(Resources(1, 0, 30, 0)), Costs.check(Resources(99, 80, 0, 10), cost))
        assertEquals(CostCheck.Ok, Costs.check(Resources.ZERO, Resources.ZERO))
    }

    @Test
    fun gemSkipTableValues() {
        assertEquals(0, GemSkip.cost(0, data))
        assertEquals(0, GemSkip.cost(-5, data))
        assertEquals(1, GemSkip.cost(1, data))
        assertEquals(3, GemSkip.cost(60, data))
        assertEquals(57, GemSkip.cost(3600, data))
        assertTrue(GemSkip.cost(86_400, data) in 500..800)
        assertTrue(GemSkip.cost(3600, data) in 40..60)
        assertTrue(GemSkip.cost(Long.MAX_VALUE, data) > 0)
    }

    @Test
    fun gemSkipProperties() {
        var prev = 0L
        var r = 1L
        while (r < 40 * 86_400L) {
            val c = GemSkip.cost(r, data)
            assertTrue(c >= 1, "remaining=$r")
            assertTrue(c >= prev, "monotone at $r")
            prev = c
            r += if (r < 7200) 7 else 997
        }
        // 区分は「短いほど割高」: 長く一括でスキップするほど秒あたりの単価が下がる（劣加法性）
        val rnd = java.util.Random(1)
        repeat(500) {
            val a = (rnd.nextInt(200_000) + 1).toLong()
            val b = (rnd.nextInt(200_000) + 1).toLong()
            assertTrue(GemSkip.cost(a + b, data) <= GemSkip.cost(a, data) + GemSkip.cost(b, data), "a=$a b=$b")
        }
        val rates = data.speedupTiers.map { it.gemsPerHour }
        assertEquals(rates.sortedDescending(), rates)
    }

    @Test
    fun speedupItemShortensTimerAndConsumesStack() {
        val s = Actions.addItems(started(), "speedup_build_5m", 3).ok()
        val job = s.timers.single()
        val after = Actions.applySpeedupItem(s, job.id, "speedup_build_5m", 1, data).ok()
        // Farm Lv2 は 3 秒なので 1 個で完了する（超過分は捨てる）
        assertTrue(after.timers.isEmpty())
        assertEquals(2, after.items["speedup_build_5m"])
        assertEquals(2, after.slots[1].level)
    }

    @Test
    fun speedupItemPartialReduction() {
        val base = started()
        val job = base.timers.single().copy(endEpochSec = T0 + 1000)
        val s = Actions.addItems(base.copy(timers = listOf(job)), "speedup_general_1m", 2).ok()
        val after = Actions.applySpeedupItem(s, job.id, "speedup_general_1m", 2, data).ok()
        assertEquals(T0 + 1000 - 120, after.timers.single().endEpochSec)
        assertTrue(after.items["speedup_general_1m"] == null)
    }

    @Test
    fun speedupItemFailures() {
        val s = started()
        val id = s.timers.single().id
        assertEquals(ActionFailure.JobNotFound, Actions.applySpeedupItem(s, 99, "speedup_build_5m", 1, data).failure())
        assertEquals(ActionFailure.ItemNotOwned, Actions.applySpeedupItem(s, id, "speedup_build_5m", 1, data).failure())
        assertEquals(ActionFailure.ItemNotOwned, Actions.applySpeedupItem(s, id, "nope", 1, data).failure())
        val withTrain = Actions.addItems(s, "speedup_train_5m", 1).ok()
        assertEquals(
            ActionFailure.CategoryMismatch,
            Actions.applySpeedupItem(withTrain, id, "speedup_train_5m", 1, data).failure(),
        )
        assertEquals(
            ActionFailure.InvalidArgument,
            Actions.applySpeedupItem(s, id, "speedup_build_5m", 0, data).failure(),
        )
    }

    @Test
    fun gemSkipCompletesImmediatelyAndChargesGems() {
        val s = started().copy(gems = 1000)
        val job = s.timers.single().copy(endEpochSec = T0 + 3600)
        val prepared = s.copy(timers = listOf(job))
        val cost = GemSkip.cost(3600, data)
        val r = Actions.gemSkip(prepared, job.id, data)
        val after = r.ok()
        assertEquals(1000 - cost, after.gems)
        assertTrue(after.timers.isEmpty())
        assertEquals(2, after.slots[1].level)
        assertTrue(
            (r as com.ttmrase.crownfall.core.sim.ActionResult.Success).events.any { it is GameEvent.BuildingLevelUp },
        )
    }

    @Test
    fun gemSkipNotEnoughGemsReportsExactShortfall() {
        val s = started().copy(gems = 10)
        val job = s.timers.single().copy(endEpochSec = T0 + 3600)
        val prepared = s.copy(timers = listOf(job))
        val cost = GemSkip.cost(3600, data)
        assertEquals(ActionFailure.NotEnoughGems(cost - 10), Actions.gemSkip(prepared, job.id, data).failure())
        assertEquals(ActionFailure.JobNotFound, Actions.gemSkip(prepared, 77, data).failure())
    }

    @Test
    fun powerGrowsWithLevelsAndMatchesTargets() {
        val fresh = TestSupport.newGame()
        val base = PowerCalculator.total(fresh, data)
        assertTrue(base > 0)
        assertEquals(base, PowerCalculator.buildingPower(fresh, data))
        val up = fresh.copy(slots = fresh.slots.map { if (it.type == BuildingType.CASTLE) it.copy(level = 2) else it })
        assertTrue(PowerCalculator.total(up, data) > base)

        fun all(level: Int) = fresh.copy(slots = fresh.slots.map { it.copy(level = level) })
        assertTrue(PowerCalculator.total(all(8), data) in 25_000..40_000)
        assertTrue(PowerCalculator.total(all(15), data) in 300_000..500_000)
        assertTrue(PowerCalculator.total(all(22), data) in 3_000_000..6_000_000)
        assertEquals(0, PowerCalculator.total(all(0), data))
    }
}
