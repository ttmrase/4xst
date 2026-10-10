package com.ttmrase.crownfall.core.military

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による M3 の境界テスト（維持費・Long 飽和・完了境界・入力検証）。 */
class QaMilitaryTest {
    private val data = TestSupport.data
    private val troop = "infantry_t1"

    private fun tick(
        s: GameState,
        dt: Long,
    ) = Simulation.tick(s, dt, data)

    private fun base(): GameState = TestSupport.withLevel(TestSupport.advanced(3, castle = 4), BuildingType.BARRACKS, 3)

    private fun job(
        id: Long,
        kind: TimerKind,
        end: Long,
    ): TimerJob =
        when (kind) {
            TimerKind.TRAIN ->
                TimerJob(id, kind, startEpochSec = T0, endEpochSec = end, troopId = troop, count = 7)
            TimerKind.HEAL ->
                TimerJob(id, kind, startEpochSec = T0, endEpochSec = end, healBatch = mapOf(troop to 5L))
            TimerKind.RESEARCH ->
                TimerJob(
                    id,
                    kind,
                    targetLevel = 1,
                    startEpochSec = T0,
                    endEpochSec = end,
                    researchId = data.research.keys.first(),
                )
            TimerKind.BUILD ->
                TimerJob(
                    id,
                    kind,
                    slotId = TestSupport.slotOf(base(), BuildingType.FARM),
                    targetLevel = 2,
                    startEpochSec = T0,
                    endEpochSec = end,
                )
        }

    // ───── 完了境界 ─────

    @Test
    fun trainResearchHealCompleteExactlyAtEndAndNotBefore() {
        for (kind in listOf(TimerKind.TRAIN, TimerKind.RESEARCH, TimerKind.HEAL)) {
            val s = base().copy(timers = listOf(job(1, kind, T0 + 100)), nextJobId = 2)
            assertEquals(1, tick(s, 99).state.timers.size, "$kind end-1")
            val at = tick(s, 100)
            assertTrue(at.state.timers.isEmpty(), "$kind end")
            assertEquals(1, at.events.count { it is GameEvent.TimerCompleted }, "$kind completed once")
            val after = tick(s, 101)
            assertTrue(after.state.timers.isEmpty(), "$kind end+1")
            assertEquals(1, after.events.count { it is GameEvent.TimerCompleted }, "$kind not completed twice")
            // end-1 → +1 と end → +1 と一括が一致
            val stepped = tick(tick(s, 99).state, 2)
            assertEquals(after.state, stepped.state, "$kind split at end-1")
        }
    }

    @Test
    fun healAndTrainAddTroopsOnceEvenWhenSettledAgain() {
        val s = base().copy(timers = listOf(job(1, TimerKind.TRAIN, T0 + 10), job(2, TimerKind.HEAL, T0 + 10)))
        val done = tick(s, 10).state
        assertEquals(12L, done.troops[troop])
        assertEquals(12L, tick(done, 0).state.troops[troop])
        assertEquals(12L, tick(done, 1000).state.troops[troop])
    }

    @Test
    fun simultaneousCompletionsAreOrderedByEndTimeThenId() {
        val s =
            base().copy(
                timers =
                    listOf(
                        job(3, TimerKind.BUILD, T0 + 50),
                        job(1, TimerKind.TRAIN, T0 + 50),
                        job(2, TimerKind.RESEARCH, T0 + 50),
                        job(9, TimerKind.HEAL, T0 + 49),
                    ),
                nextJobId = 10,
            )
        val completed = tick(s, 60).events.filterIsInstance<GameEvent.TimerCompleted>().map { it.job.id }
        assertEquals(listOf(9L, 1L, 2L, 3L), completed)
        for (cut in listOf(48L, 49L, 50L, 51L)) {
            val whole = tick(s, 60)
            val a = tick(s, cut)
            val b = tick(a.state, 60 - cut)
            assertEquals(whole.state, b.state, "cut=$cut")
            assertEquals(whole.events, a.events + b.events, "cut=$cut")
        }
    }

    // ───── 維持費・飽和 ─────

    @Test
    fun hugeTroopCountSaturatesUpkeepAndDepletesFoodOnceWithoutNegatives() {
        for (count in listOf(Long.MAX_VALUE / 8, Long.MAX_VALUE)) {
            val s = base().copy(troops = mapOf(troop to count), resources = Resources(5_000, 5_000, 5_000, 5_000))
            val first = tick(s, 1)
            assertEquals(0, first.state.resources.food, "count=$count")
            assertEquals(1, first.events.count { it == GameEvent.FoodDepleted }, "count=$count")
            val later = tick(first.state, 1_000_000)
            assertEquals(0, later.state.resources.food)
            assertTrue(later.events.none { it == GameEvent.FoodDepleted }, "no repeated depletion")
            assertTrue(later.state.productionRemainder.food == 0L)
            assertEquals(tick(s, 1_000_001).state, later.state)
        }
    }

    @Test
    fun foodAboveCapWithNetNegativeRateIsSplitInvariant() {
        val b = base()
        val cap = Simulation.storageCap(b, data)
        val s = b.copy(troops = mapOf(troop to 40_000L), resources = RICH.copy(food = cap + 500))
        val total = 200_000L
        val whole = tick(s, total)
        assertTrue(whole.state.resources.food >= 0)
        val rnd = java.util.Random(11)
        repeat(100) { n ->
            var cur = s
            val events = ArrayList<GameEvent>()
            var left = total
            while (left > 0) {
                val piece = minOf(left, (rnd.nextInt(30_000) + (if (rnd.nextBoolean()) 0 else 1)).toLong())
                val r = tick(cur, piece)
                cur = r.state
                events.addAll(r.events)
                left -= piece
            }
            assertEquals(whole.state, cur, "case $n")
            assertEquals(whole.events, events, "case $n")
        }
    }

    @Test
    fun randomSplitsWithMilitaryTimersTroopsAndBoostsAreInvariant() {
        val rnd = java.util.Random(2026)
        repeat(120) { n ->
            val s =
                base().copy(
                    troops = mapOf(troop to (rnd.nextInt(60_000) + 1).toLong()),
                    resources = Resources(rnd.nextInt(40_000).toLong(), 100, 100, 100),
                    timers =
                        listOf(
                            job(1, TimerKind.TRAIN, T0 + 1 + rnd.nextInt(5000)),
                            job(2, TimerKind.HEAL, T0 + 1 + rnd.nextInt(5000)),
                            job(3, TimerKind.RESEARCH, T0 + 1 + rnd.nextInt(5000)),
                        ),
                    nextJobId = 4,
                )
            val total = (rnd.nextInt(40_000) + 1).toLong()
            val whole = tick(s, total)
            val c1 = rnd.nextInt(total.toInt()).toLong()
            val c2 = c1 + rnd.nextInt((total - c1).toInt() + 1)
            val a = tick(s, c1)
            val b = tick(a.state, c2 - c1)
            val c = tick(b.state, total - c2)
            assertEquals(whole.state, c.state, "case $n")
            assertEquals(whole.events, a.events + b.events + c.events, "case $n")
        }
    }

    @Test
    fun offlineSummaryNeverReportsNegativeProduction() {
        val s = base().copy(troops = mapOf(troop to 100_000L), resources = Resources(50_000, 0, 0, 0))
        val r = OfflineCatchUp.run(s, T0 + 3600, data)
        val p = r.summary.produced
        assertTrue(p.food >= 0 && p.wood >= 0 && p.stone >= 0 && p.gold >= 0, "produced=$p")
    }

    // ───── 入力検証 ─────

    @Test
    fun trainingCountBoundsAreRejectedWithoutOverflow() {
        val s = base().copy(resources = RICH)
        val cap = Military.trainCapacity(s, data.troops.getValue(troop), data)
        assertTrue(cap > 0)
        val unitFood = data.troops.getValue(troop).cost.food
        val trained = Actions.startTraining(s, troop, cap, data).ok()
        assertEquals(s.resources.food - unitFood * cap, trained.resources.food)
        for (bad in listOf(cap + 1, Long.MAX_VALUE, 0L, -1L, Long.MIN_VALUE)) {
            val reason = Actions.startTraining(s, troop, bad, data).failure()
            assertEquals(ActionFailure.InvalidArgument, reason, "count=$bad")
        }
        assertEquals(ActionFailure.InvalidArgument, Actions.startTraining(s, "no_such_troop", 1, data).failure())
    }

    @Test
    fun healInputValidationAndAccounting() {
        val hospital = TestSupport.withLevel(base(), BuildingType.HOSPITAL, 1)
        val s = hospital.copy(resources = RICH, wounded = mapOf(troop to 10L))
        val badBatches =
            listOf(
                emptyMap(),
                mapOf(troop to 0L),
                mapOf(troop to -1L),
                mapOf(troop to 11L),
                mapOf("x" to 1L),
            )
        for (bad in badBatches) {
            val reason = Actions.startHeal(s, bad, data).failure()
            assertEquals(ActionFailure.InvalidArgument, reason, "batch=$bad")
        }
        val started = Actions.startHeal(s, mapOf(troop to 4L), data).ok()
        assertEquals(6L, started.wounded[troop])
        assertEquals(ActionFailure.QueueFull, Actions.startHeal(started, mapOf(troop to 1L), data).failure())
        val all = Actions.startHeal(s, mapOf(troop to 10L), data).ok()
        assertTrue(troop !in all.wounded, "fully healed batch leaves no zero entry")
        val done = tick(all, 1_000_000).state
        assertEquals(10L, done.troops[troop])
        assertTrue(done.wounded.isEmpty())
    }
}
