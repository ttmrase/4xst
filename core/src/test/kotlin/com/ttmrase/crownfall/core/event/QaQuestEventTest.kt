package com.ttmrase.crownfall.core.event

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.EventState
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.notify.NotificationPlanner
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.save.SaveGame
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.InMemorySaveStorage
import com.ttmrase.crownfall.core.shop.ShopLogic
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.util.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** QA による M7 の境界テスト（NPC ランキング、週・日の境界でのイベント帰属、通知プランナー）。 */
@OptIn(ExperimentalCoroutinesApi::class)
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

    // ───── 逆転パック: 購入後は追従が購入時点の自力で固定される ─────

    @Test
    fun comebackPackKeepsFirstPlaceToTheWeekEndWhenTheOwnScoreKeepsGrowing() {
        var bought = 0
        for (seed in 1L..100L) {
            for (week in 0L until data.events.rotation.size.toLong()) {
                val w = week + 100 // rotation の何周目でもよい
                val def = WeeklyEvents.definition(w, data)
                if (def.kind == "POWER") continue
                val start = WeeklyEvents.weekStart(w, 0)
                val warnNow = start + WeeklyEvents.WEEK_SEC - data.events.warnBeforeEndSec
                for (selfFactor in listOf(600L, 800L, 1000L, 1200L)) {
                    val s0 = TestSupport.newGame(now = warnNow)
                    var s =
                        s0.copy(
                            map = s0.map.copy(seed = seed * 7919),
                            event = EventState(weekId = w, selfScore = def.par * selfFactor / 1000),
                        )
                    if (WeeklyEvents.rank(s, data) !in 2..4) continue
                    val gap = WeeklyEvents.gapToFirst(s, data)
                    val points = gap + gap * data.events.bonusMarginPermille / 1000 + 1
                    s = ShopLogic.grant(s, PackContents(eventPoints = points), data)
                    bought++
                    val end = start + WeeklyEvents.WEEK_SEC - 1
                    val grown = s.copy(nowEpochSec = end, event = s.event.copy(selfScore = s.event.selfScore * 2))
                    assertEquals(1, WeeklyEvents.rank(grown, data), "seed=$seed ${def.id} factor=$selfFactor")
                    // 追従は購入時点の自力で固定: 自力が伸びても NPC の点数は変わらない
                    val same = s.copy(nowEpochSec = end)
                    assertEquals(
                        WeeklyEvents.standings(same, data).filter { !it.isPlayer }.map { it.score },
                        WeeklyEvents.standings(grown, data).filter { !it.isPlayer }.map { it.score },
                        "NPC scores must not chase the own score after the pack",
                    )
                }
            }
        }
        assertTrue(bought > 300, "scenario must exercise many purchases: $bought")
    }

    // ───── 週・日の境界でのイベントの帰属（GameSession 経由。アプリを開いていても閉じていても同じ） ─────

    private fun TestScope.sessionOf(
        state: GameState,
        clock: FixedClock,
    ): GameSession {
        val storage =
            InMemorySaveStorage(
                primary = SaveCodec.encode(SaveGame(savedAtEpochSec = state.nowEpochSec, seed = 42, state = state)),
            )
        val d = StandardTestDispatcher(testScheduler)
        return GameSession(data, clock, storage, backgroundScope, d, d)
    }

    private fun nearest(
        s: GameState,
        type: ResourceType,
    ) = s.map.nodes.filter { it.type == type }.minBy {
        MapQueries.distance(it.x, it.y, data.mapRules.center.x, data.mapRules.center.y)
    }

    /** 採集祭の週の終わりの 20 時間前に出発した採集隊が、週の終わりの前に帰還する状態と、週の終わり。 */
    private fun gatherWeekScenario(): Pair<GameState, Long> {
        val week = (0L..20L).first { WeeklyEvents.definition(it, data).kind == "GATHER" }
        val weekEnd = WeeklyEvents.weekStart(week + 1, 0)
        var s = TestSupport.withLevel(TestSupport.newGame(now = weekEnd - 20 * hour), BuildingType.CASTLE, 10)
        s = s.copy(troops = mapOf("archer_t2" to 40L))
        s = Simulation.tick(s, 0, data).state
        s = s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
        s = s.copy(event = EventState(weekId = week, selfScore = data.events.participation.minScore + 10))
        val node = nearest(s, ResourceType.FOOD)
        val troops = mapOf("archer_t2" to 40L)
        s = Actions.startMarch(s, MarchKind.GATHER, TileRef(node.x, node.y), troops, emptyList(), data).ok()
        return s to weekEnd
    }

    @Test
    fun gatherLootBeforeTheWeekEndCountsForThatWeekWhetherOrNotTheAppWasOpen() =
        runTest {
            val (start, weekEnd) = gatherWeekScenario()
            val until = weekEnd + hour
            val openClock = FixedClock(start.nowEpochSec)
            val open = sessionOf(start, openClock)
            open.load()
            while (openClock.epochSec < until) {
                openClock.epochSec += 60
                open.advanceToNow()
            }
            val closed = sessionOf(start, FixedClock(until))
            closed.load()

            fun resultScore(s: GameState) =
                s.mails.last { it.kind == MailKind.SYSTEM && it.systemCode == "event_result" }.params.getValue("score")
            val a = assertNotNull(open.state.value)
            val b = assertNotNull(closed.state.value)
            assertTrue(resultScore(a) > data.events.participation.minScore + 10, "the gather must be in the result")
            assertEquals(resultScore(a), resultScore(b), "week result score depends on whether the app was open")
            assertEquals(a.event.selfScore, b.event.selfScore, "next week's score depends on it too")
        }

    @Test
    fun dailyCountersAreAttributedToTheDayTheEventHappened() =
        runTest {
            val s0 = TestSupport.advanced(level = 3, castle = 3, now = 20_000L * day + 22 * hour)
            var s = Simulation.tick(s0.copy(resources = TestSupport.RICH), 0, data).state
            s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.FARM), data).ok()
            assertTrue(s.timers.single().endEpochSec < 20_001L * day, "the upgrade must finish before local midnight")
            val until = 20_001L * day + hour
            val openClock = FixedClock(s.nowEpochSec)
            val open = sessionOf(s, openClock)
            open.load()
            while (openClock.epochSec < until) {
                openClock.epochSec += 60
                open.advanceToNow()
            }
            val closed = sessionOf(s, FixedClock(until))
            closed.load()
            val a = assertNotNull(open.state.value)
            val b = assertNotNull(closed.state.value)
            assertEquals(1L, a.quest.counters["build"], "the build is counted once in total")
            assertEquals(a.quest.counters["build"], b.quest.counters["build"])
            assertEquals(
                a.quest.dailyCounts,
                b.quest.dailyCounts,
                "today's counters depend on whether the app was open",
            )
        }
}
