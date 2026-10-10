package com.ttmrase.crownfall.core.event

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.notify.NotificationPlanner
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による M7 の境界テスト（NPC ランキング、週・日の境界でのイベント帰属、通知プランナー）。 */
class QaQuestEventTest {
    private val data = TestSupport.data
    private val day = 86_400L
    private val hour = 3600L

    // ───── NPC ランキング ─────

    @Test
    fun ownScoreAloneAlwaysLandsOnSecondToFourthAcrossManySeeds() {
        val s0 = TestSupport.newGame()
        val maxFollower =
            data.events.events.associate { it.id to it.par * data.events.followers.parMaxPermille / 1000 }
        var checked = 0
        for (seed in 1L..300L) {
            val s = s0.copy(map = s0.map.copy(seed = seed * 7919))
            for (week in 0L until data.events.rotation.size * 2L) {
                val def = WeeklyEvents.definition(week, data)
                // 追従する上位 NPC より下の NPC を自力が上回っている現実的な範囲（par 以上）で見る
                for (factor in listOf(1L, 2L, 5L)) {
                    val self = maxOf(def.par, maxFollower.getValue(def.id)) * factor
                    val rows = WeeklyEvents.npcRows(s, data, week, self, 1000)
                    val rank = rows.count { it.score > self } + 1
                    assertTrue(rank in 2..4, "seed=$seed week=$week ${def.id} self=$self rank=$rank")
                    checked++
                }
            }
        }
        assertTrue(checked > 1000)
    }

    @Test
    fun npcRowsAreSortedAndNeverNegativeAtEveryProgress() {
        val s = TestSupport.newGame().let { it.copy(map = it.map.copy(seed = 99)) }
        for (frac in listOf(0L, 1L, 333L, 999L, 1000L)) {
            for (self in listOf(0L, 1L, 12_345L, Long.MAX_VALUE / 4)) {
                val rows = WeeklyEvents.npcRows(s, data, 5, self, frac)
                assertEquals(rows.map { it.score }, rows.map { it.score }.sortedDescending())
                assertTrue(rows.all { it.score >= 0 }, "frac=$frac self=$self")
            }
        }
    }

    // ───── 通知プランナー ─────

    @Test
    fun plannerNeverReturnsPastTimesAndStaysSortedAndBoundedOverADay() {
        var s = TestSupport.advanced(level = 5, castle = 5, now = 20_000L * day + 12 * hour)
        s = s.copy(resources = TestSupport.RICH, gems = 1000)
        s = Actions.grantSecondBuilder(s, 3 * day).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.FARM), data).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.SAWMILL), data).ok()
        s = Actions.offerForGemShortage(s, 700, data).ok()
        s = Simulation.tick(s, 0, data).state
        var t = 0L
        while (t < day) {
            val plan = NotificationPlanner.plan(s, data)
            assertTrue(plan.all { it.epochSec > s.nowEpochSec }, "t=$t has a non-future notice")
            assertEquals(plan, plan.sortedWith(compareBy({ it.epochSec }, { it.type.ordinal }, { it.param })))
            assertEquals(
                plan.size,
                plan.map { Triple(it.epochSec, it.type, it.param) }.toSet().size,
                "duplicates at t=$t",
            )
            assertTrue(plan.size <= 60)
            assertTrue(plan.all { it.count >= 1 })
            s = Simulation.tick(s, 997, data).state
            t += 997
        }
    }
}
