package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.economy.GemSkip
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による境界テスト（R3 分割不変性、catch-up、GemSkip 性質、Long 飽和）。 */
class QaBoundaryTest {
    private val data = TestSupport.data
    private val day = 86_400L

    private fun tick(
        s: GameState,
        dt: Long,
    ) = Simulation.tick(s, dt, data)

    /** タイマー 2 本・Boost 2 種・2 本目ビルダーを持ち、cap 付近の資源がある状態。 */
    private fun busyState(): GameState {
        var s = TestSupport.advanced(3, castle = 4).copy(resources = RICH)
        s = Actions.grantSecondBuilder(s, 2500).ok()
        s = Actions.addBoost(s, Boost("p", BoostKind.PRODUCTION, 2500, T0 + 700)).ok()
        s = Actions.addBoost(s, Boost("b", BoostKind.BUILD_SPEED, 5000, T0 + 100)).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.WAREHOUSE), data).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.FARM), data).ok()
        return s.copy(resources = Resources(29_990, 100, 29_000, 0))
    }

    private fun boundaries(s: GameState): List<Long> =
        (
            s.timers.map { it.endEpochSec } + s.boosts.map { it.untilEpochSec } +
                s.secondBuilderUntilEpochSec
        ).filter { it > s.nowEpochSec }.map { it - s.nowEpochSec }

    @Test
    fun cutAtEveryBoundaryPlusMinusOneIsInvariant() {
        val base = busyState()
        val total = 60_000L
        val whole = tick(base, total)
        val cuts = boundaries(base).flatMap { listOf(it - 1, it, it + 1) }.filter { it in 1 until total }
        assertTrue(cuts.size >= 12, "expected boundary cuts, got $cuts")
        for (cut in cuts) {
            val a = tick(base, cut)
            val b = tick(a.state, total - cut)
            assertEquals(whole.state, b.state, "state differs at cut=$cut")
            assertEquals(whole.events, a.events + b.events, "events differ at cut=$cut")
        }
    }

    @Test
    fun threeWayCutsAcrossBoundariesAreInvariant() {
        val base = busyState()
        val total = 30_000L
        val whole = tick(base, total)
        val bs = boundaries(base).filter { it < total }
        for (x in bs) {
            for (y in bs) {
                if (y <= x) continue
                val a = tick(base, x - 1)
                val b = tick(a.state, y - x + 2)
                val c = tick(b.state, total - (x - 1) - (y - x + 2))
                assertEquals(whole.state, c.state, "x=$x y=$y")
                assertEquals(whole.events, a.events + b.events + c.events, "x=$x y=$y")
            }
        }
    }

    @Test
    fun randomSplitsWithRandomBoostsAndBuilderExpiry() {
        val rnd = java.util.Random(7)
        repeat(120) { n ->
            var s = TestSupport.advanced(3, castle = 4).copy(resources = RICH)
            s = Actions.grantSecondBuilder(s, (rnd.nextInt(3000) + 1).toLong()).ok()
            val prodBoost = Boost("p", BoostKind.PRODUCTION, rnd.nextInt(20_000) - 5000, T0 + rnd.nextInt(5000))
            val buildBoost = Boost("b", BoostKind.BUILD_SPEED, rnd.nextInt(20_000), T0 + rnd.nextInt(500))
            s = Actions.addBoost(s, prodBoost).ok()
            s = Actions.addBoost(s, buildBoost).ok()
            s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.FARM, 0), data).ok()
            s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.SAWMILL, 0), data).ok()
            s = s.copy(resources = Resources(rnd.nextInt(40_000).toLong(), rnd.nextInt(40_000).toLong(), 0, 0))
            val total = (rnd.nextInt(50_000) + 1).toLong()
            val whole = tick(s, total)
            val cut1 = rnd.nextInt(total.toInt()).toLong()
            val cut2 = cut1 + rnd.nextInt((total - cut1).toInt() + 1)
            val a = tick(s, cut1)
            val b = tick(a.state, cut2 - cut1)
            val c = tick(b.state, total - cut2)
            assertEquals(whole.state, c.state, "case $n total=$total cuts=$cut1,$cut2")
            assertEquals(whole.events, a.events + b.events + c.events, "case $n")
        }
    }

    @Test
    fun noNegativeResourcesEvenWithNegativeProductionBoost() {
        val s = Actions.addBoost(TestSupport.newGame(), Boost("neg", BoostKind.PRODUCTION, -50_000, T0 + 10_000)).ok()
        val r = tick(s, 20_000).state
        assertTrue(r.resources.food >= s.resources.food)
        assertTrue(r.productionRemainder.food in 0..3599)
    }

    @Test
    fun productionAtLongMaxStaysSaturatedAndNonNegative() {
        val s = TestSupport.newGame().copy(resources = Resources(Long.MAX_VALUE, Long.MAX_VALUE - 1, 0, 0))
        val r = tick(s, 10 * day).state
        assertEquals(Long.MAX_VALUE, r.resources.food)
        assertTrue(r.resources.wood >= Long.MAX_VALUE - 1)
        assertTrue(r.resources.stone >= 0 && r.resources.gold >= 0)
    }

    /** 丸め前の厳密コスト × 3600（区分ごとの 秒 × 毎時ジェム の合計）。 */
    private fun exactScaled(remaining: Long): Long {
        var total = 0L
        var lower = 0L
        for (tier in data.speedupTiers) {
            val upper = tier.upToSec ?: Long.MAX_VALUE
            if (remaining <= lower) break
            total += (minOf(remaining, upper) - lower) * tier.gemsPerHour
            lower = upper
        }
        return total
    }

    @Test
    fun gemSkipUnroundedUnitPriceNeverIncreasesAndRoundingStaysBounded() {
        // 整数 ceil 後の単価は原理的に単調にならない（cost(20)=1, cost(21)=2）。
        // 設計意図は「丸め前の連続単価が非増加」＋「丸め誤差は区分数ジェム未満」。
        var prev = 1L
        var r = 2L
        while (r < 3 * day) {
            assertTrue(exactScaled(r) * prev <= exactScaled(prev) * r, "exact unit price rose: $prev -> $r")
            val scaled = exactScaled(r)
            val c = GemSkip.cost(r, data)
            assertTrue(c * 3600 >= scaled, "cost below exact at $r")
            assertTrue(c * 3600 < scaled + data.speedupTiers.size * 3600L, "rounding too large at $r")
            prev = r
            r += if (r < 7200) 1 else 61
        }
    }

    @Test
    fun addResourcesRejectsNegativeComponents() {
        val s = TestSupport.newGame()
        val bad =
            listOf(
                Resources(food = -1),
                Resources(wood = -1),
                Resources(stone = -1),
                Resources(gold = -1),
            )
        for (amount in bad) {
            val result = Actions.addResources(s, amount) as ActionResult.Failure
            assertEquals(ActionFailure.InvalidArgument, result.reason)
        }
        assertEquals(s.resources, Actions.addResources(s, Resources.ZERO).ok().resources)
    }

    // ───── OfflineCatchUp ─────

    @Test
    fun catchUpIsIndependentOfChunkSize() {
        val s = busyState()
        val elapsed = 12_345L
        val ref = OfflineCatchUp.run(s, T0 + elapsed, data)
        for (chunk in listOf(1L, 7L, 59L, 60L, 61L, 3600L, elapsed, elapsed + 1)) {
            val d = GameData(data.buildings, data.rules.copy(catchUpChunkSec = chunk), data.items, data.speedupTiers)
            val r = OfflineCatchUp.run(s, T0 + elapsed, d)
            assertEquals(ref.state, r.state, "chunk=$chunk")
            assertEquals(ref.events, r.events, "chunk=$chunk")
        }
    }

    @Test
    fun catchUpComposesAcrossTwoSessionsUnderCap() {
        val s = busyState()
        val mid = OfflineCatchUp.run(s, T0 + 5_000, data)
        val end = OfflineCatchUp.run(mid.state, T0 + 20_000, data)
        val direct = OfflineCatchUp.run(s, T0 + 20_000, data)
        assertEquals(direct.state, end.state)
        assertEquals(direct.events, mid.events + end.events)
    }

    @Test
    fun catchUpAroundCapBoundary() {
        val s = TestSupport.newGame()
        val atCap = OfflineCatchUp.run(s, T0 + day, data)
        val overBy1 = OfflineCatchUp.run(s, T0 + day + 1, data)
        val underBy1 = OfflineCatchUp.run(s, T0 + day - 1, data)
        assertEquals(day, atCap.summary.appliedSec)
        assertEquals(day, overBy1.summary.appliedSec)
        assertEquals(day - 1, underBy1.summary.appliedSec)
        assertEquals(atCap.state.resources, overBy1.state.resources)
        assertEquals(T0 + day + 1, overBy1.state.nowEpochSec)
    }

    @Test
    fun catchUpOverCapDoesNotReviveExpiredSecondBuilderOrBoost() {
        var s = TestSupport.advanced().copy(resources = RICH)
        s = Actions.grantSecondBuilder(s, 10).ok()
        s = Actions.addBoost(s, Boost("p", BoostKind.PRODUCTION, 1000, T0 + 10)).ok()
        val r = OfflineCatchUp.run(s, T0 + 5 * day, data)
        assertEquals(1, Simulation.buildQueueCapacity(r.state, data))
        assertTrue(r.state.boosts.isEmpty())
        // 期限が切れていた 2 本目ビルダーを、巻き戻し無しで年単位後に再度 catch-up しても復活しない
        val again = OfflineCatchUp.run(r.state, r.state.nowEpochSec + 400 * day, data)
        assertEquals(1, Simulation.buildQueueCapacity(again.state, data))
    }

    @Test
    fun clockRewindLeavesStateUntouchedAndBuilderUntilStaysAbsolute() {
        val s = busyState()
        val r = OfflineCatchUp.run(s, T0 - 10_000, data)
        assertEquals(s, r.state)
        assertTrue(r.events.isEmpty())
        assertEquals(0, r.summary.appliedSec)
    }

    @Test
    fun catchUpWithHugeElapsedDoesNotOverflow() {
        val s = busyState()
        val r = OfflineCatchUp.run(s, Long.MAX_VALUE - 1, data)
        assertEquals(day, r.summary.appliedSec)
        assertTrue(r.state.timers.all { it.endEpochSec >= r.state.nowEpochSec })
        assertTrue(r.state.nowEpochSec > 0)
    }
}
