package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineCatchUpTest {
    private val data = TestSupport.data
    private val day = 86_400L

    @Test
    fun twentyFourHoursMatchesOneBigTick() {
        val s = TestSupport.newGame()
        val r = OfflineCatchUp.run(s, T0 + day, data)
        val whole = Simulation.tick(s, day, data)
        assertEquals(whole.state, r.state)
        assertEquals(day, r.summary.elapsedSec)
        assertEquals(day, r.summary.appliedSec)
        assertEquals(300 * 24, r.summary.produced.food)
        assertEquals(T0 + day, r.state.nowEpochSec)
    }

    @Test
    fun capsAtOfflineLimitAndKeepsRemainingTimes() {
        val s0 = TestSupport.advanced().copy(resources = RICH)
        val s =
            Actions.startUpgrade(s0, 1, data).ok().let {
                Actions.addBoost(it, Boost("b", BoostKind.PRODUCTION, 100, T0 + 10 * day)).ok()
            }.let { Actions.grantSecondBuilder(it, 5 * day).ok() }
        val timer = s.timers.single()
        // 長いタイマーに置き換えて「打ち切り分は進まない」ことを確認する
        val long = s.copy(timers = listOf(timer.copy(endEpochSec = T0 + 3 * day)), resources = Resources.ZERO)
        val r = OfflineCatchUp.run(long, T0 + 5 * day, data)
        assertEquals(day, r.summary.appliedSec)
        assertEquals(5 * day, r.summary.elapsedSec)
        assertEquals(T0 + 5 * day, r.state.nowEpochSec)
        // 24h 進めた分だけ残り時間が減る: 3 日 - 1 日 = 2 日
        assertEquals(2 * day, r.state.timers.single().endEpochSec - r.state.nowEpochSec)
        assertEquals(10 * day - day, r.state.boosts.single().untilEpochSec - r.state.nowEpochSec)
        assertEquals(5 * day - day, r.state.secondBuilderUntilEpochSec - r.state.nowEpochSec)
        assertEquals(303 * 24, r.state.resources.food) // 生産は 24h 分だけ（+1% の Boost 込み）
    }

    @Test
    fun fortyEightHoursProducesSameAsTwentyFour() {
        val s = TestSupport.newGame()
        val a = OfflineCatchUp.run(s, T0 + 2 * day, data).state.resources
        val b = OfflineCatchUp.run(s, T0 + day, data).state.resources
        assertEquals(b, a)
    }

    @Test
    fun zeroAndNegativeElapsedDoNothing() {
        val s = TestSupport.newGame()
        val zero = OfflineCatchUp.run(s, T0, data)
        assertEquals(s, zero.state)
        assertEquals(0, zero.summary.elapsedSec)
        val back = OfflineCatchUp.run(s, T0 - 500, data)
        assertEquals(s, back.state)
        assertTrue(back.events.isEmpty())
    }

    @Test
    fun exactlyAtCapAndCustomCap() {
        val s = TestSupport.newGame()
        val exact = OfflineCatchUp.run(s, T0 + day, data)
        assertEquals(day, exact.summary.appliedSec)
        val small = OfflineCatchUp.run(s, T0 + day, data, capSec = 3600)
        assertEquals(3600, small.summary.appliedSec)
        assertEquals(300, small.summary.produced.food)
        assertEquals(T0 + day, small.state.nowEpochSec)
        val none = OfflineCatchUp.run(s, T0 + 100, data, capSec = -5)
        assertEquals(0, none.summary.appliedSec)
    }

    @Test
    fun summaryListsCompletedJobsAndLevelUps() {
        val s0 = TestSupport.advanced().copy(resources = RICH)
        val s = Actions.startUpgrade(s0, 1, data).ok()
        val r = OfflineCatchUp.run(s, T0 + 3600, data)
        assertEquals(1, r.summary.levelUps)
        assertEquals(1, r.summary.completedJobs.size)
        assertTrue(r.events.any { it is GameEvent.BuildingLevelUp })
        assertEquals(2, r.state.slots[1].level)
    }

    @Test
    fun matchesBigTickWithQueuedWork() {
        val s0 = TestSupport.advanced(3, castle = 4).copy(resources = RICH)
        val s =
            Actions.startUpgrade(s0, TestSupport.slotOf(s0, BuildingType.WAREHOUSE), data).ok()
                .copy(resources = Resources(1000, 1000, 1000, 0))
        assertEquals(Simulation.tick(s, 6 * 3600L, data).state, OfflineCatchUp.run(s, T0 + 6 * 3600L, data).state)
    }

    @Test
    fun performance24HoursIsFast() {
        val s = TestSupport.advanced().copy(resources = RICH)
        OfflineCatchUp.run(s, T0 + day, data) // warm-up
        val ms = measureTimeMillis { OfflineCatchUp.run(s, T0 + day, data) }
        val tickNanos =
            run {
                val start = System.nanoTime()
                repeat(1000) { Simulation.tick(s, 60, data) }
                (System.nanoTime() - start) / 1000
            }
        System.err.println("PERF catchUp24h=${ms}ms tick(60s)=${tickNanos / 1000.0}us")
        assertTrue(ms < 2000, "24h catch-up took ${ms}ms")
        assertTrue(tickNanos < 2_000_000, "tick took ${tickNanos}ns")
    }
}
