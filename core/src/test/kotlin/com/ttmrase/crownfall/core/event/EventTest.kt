package com.ttmrase.crownfall.core.event

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.Tracking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EventTest {
    private val data = TestSupport.data
    private val week = (2_900L..2_910L).first { WeeklyEvents.definition(it, data).kind == "HUNT" }
    private val start = WeeklyEvents.weekStart(week, 0)

    private fun game(
        seed: Long = 1L,
        at: Long = start + 3600,
    ): GameState {
        val s = TestSupport.newGame(now = at, castle = 8)
        val inited =
            s.copy(
                map = s.map.copy(seed = seed, nextRaidEpochSec = Long.MAX_VALUE / 4),
                settings = s.settings.copy(utcOffsetSec = 0),
            )
        return Simulation.tick(inited, 60, data).state
    }

    private fun withSelf(
        s: GameState,
        score: Long,
    ) = s.copy(event = s.event.copy(selfScore = score))

    private fun at(
        s: GameState,
        t: Long,
    ) = s.copy(nowEpochSec = t)

    @Test
    fun weeksStartOnMondayLocalTime() {
        // 1970-01-05 は月曜。UTC 月曜 0 時が週の境目
        val mon = 4L * 86_400
        assertEquals(WeeklyEvents.weekId(mon, 0), WeeklyEvents.weekId(mon + 86_399 + 6 * 86_400, 0))
        assertNotEquals(WeeklyEvents.weekId(mon - 1, 0), WeeklyEvents.weekId(mon, 0))
        // JST では UTC 日曜 15:00 が月曜 0 時
        val jst = 9 * 3600
        assertNotEquals(WeeklyEvents.weekId(mon - 9 * 3600 - 1, jst), WeeklyEvents.weekId(mon - 9 * 3600, jst))
        assertEquals(mon - 9 * 3600, WeeklyEvents.weekStart(WeeklyEvents.weekId(mon, jst), jst))
    }

    @Test
    fun theWeekStartsOnFirstTickAndRotates() {
        val s = game()
        assertEquals(week, s.event.weekId)
        val kinds = (0L..2L).map { WeeklyEvents.definition(week + it, data).kind }.toSet()
        assertEquals(3, kinds.size)
    }

    @Test
    fun npcScoresAreDeterministicAndKeepTheirOrderSorted() {
        val s = game(seed = 7)
        val a = WeeklyEvents.npcRows(s, data, week, 1000, 500)
        val b = WeeklyEvents.npcRows(s, data, week, 1000, 500)
        assertEquals(a, b)
        assertEquals(a.sortedByDescending { it.score }, a)
        assertEquals(data.events.npcs.size, a.size)
        val other = WeeklyEvents.npcRows(game(seed = 8), data, week, 1000, 500)
        assertNotEquals(a.map { it.name }, other.map { it.name })
    }

    @Test
    fun ownTopScoreOnlyReachesSecondToFourth() {
        for (seed in 1L..40L) {
            val base = game(seed = seed)
            val def = WeeklyEvents.definition(week, data)
            // 終了間際に、基準 (par) ちょうどの自力スコアを出した
            val s = at(withSelf(base, def.par), start + WeeklyEvents.WEEK_SEC - 100)
            val rank = WeeklyEvents.rank(s, data)
            assertTrue(rank in 2..4, "seed $seed rank $rank")
        }
    }

    @Test
    fun huntAndGatherEventsAddPointsOnlyInTheirWeek() {
        // 週ごとの種目で加点が分かれる
        var hunt = game()
        var w = week
        while (WeeklyEvents.definition(w, data).kind != "HUNT") w++
        hunt = hunt.copy(event = hunt.event.copy(weekId = w))
        val def = WeeklyEvents.definition(w, data)
        val got = Tracking.apply(hunt, listOf(GameEvent.HuntWon(false, 3), GameEvent.GatherCompleted(100_000)), data)
        assertEquals(def.perLevel * 3, got.event.selfScore)
        val fort = Tracking.apply(hunt, listOf(GameEvent.HuntWon(true, 3)), data)
        assertEquals(def.perLevel * 3 * def.fortMultiplier, fort.event.selfScore)
        var gw = week
        while (WeeklyEvents.definition(gw, data).kind != "GATHER") gw++
        val gather = hunt.copy(event = hunt.event.copy(weekId = gw))
        val g = Tracking.apply(gather, listOf(GameEvent.HuntWon(false, 3), GameEvent.GatherCompleted(100_000)), data)
        assertEquals(100_000 / WeeklyEvents.definition(gw, data).lootPerPoint, g.event.selfScore)
    }

    @Test
    fun resultsAreMailedOnceAtWeekEndAndANewWeekStarts() {
        val s0 = withSelf(game(), 50_000)
        val end = start + WeeklyEvents.WEEK_SEC
        val s1 = Simulation.tick(at(s0, end - 10), 3600, data).state
        val results = s1.mails.filter { it.kind == MailKind.SYSTEM && it.systemCode == "event_result" }
        assertEquals(1, results.size)
        assertEquals(week + 1, s1.event.weekId)
        assertEquals(0L, s1.event.selfScore)
        assertTrue((results[0].params["rank"] ?: 0) in 1..11)
        // さらに 1 日進めても二重に配られない
        val s2 = Simulation.tick(s1, 86_400, data).state
        assertEquals(1, s2.mails.count { it.systemCode == "event_result" })
    }

    @Test
    fun noParticipationNoReward() {
        val s0 = game()
        val end = start + WeeklyEvents.WEEK_SEC
        val s1 = Simulation.tick(at(s0, end - 10), 3600, data).state
        assertEquals(0, s1.mails.count { it.systemCode == "event_result" })
        assertEquals(week + 1, s1.event.weekId)
    }

    @Test
    fun rewardsFollowTheRankAndAreGrantedOnce() {
        val s0 = withSelf(game(), 50_000)
        val end = start + WeeklyEvents.WEEK_SEC
        val s1 = Simulation.tick(at(s0, end - 10), 3600, data).state
        val rank = s1.mails.first { it.systemCode == "event_result" }.params.getValue("rank").toInt()
        val reward = data.events.rewards.first { rank in it.from..it.to }.reward
        assertEquals(s0.gems + reward.gems, s1.gems)
    }

    @Test
    fun theLastDayWarningOffersAComebackPackOnce() {
        val def = WeeklyEvents.definition(week, data)
        val s0 = withSelf(game(seed = 3), def.par)
        val end = start + WeeklyEvents.WEEK_SEC
        val warnAt = end - data.events.warnBeforeEndSec
        val before = Simulation.tick(at(s0, warnAt - 100), 50, data).state
        assertTrue(!before.event.warned)
        val s1 = Simulation.tick(before, 200, data).state
        assertTrue(s1.event.warned)
        val rank = WeeklyEvents.rank(s1, data)
        assertTrue(rank in 2..4, "rank $rank")
        run {
            val offer = OfferEngine.active(s1).first { it.trigger == "rankingNear" }
            // 買えば確実に 1 位になる
            val bought = Actions.fakePurchase(s1, com.ttmrase.crownfall.core.shop.ProductIds.offer(offer.id), data).ok()
            assertEquals(1, WeeklyEvents.rank(bought, data))
        }
        // 二度目の境界処理では増えない
        val s2 = Simulation.tick(s1, 3600, data).state
        assertEquals(
            OfferEngine.active(s1).count {
                it.trigger == "rankingNear"
            },
            OfferEngine.active(s2).count { it.trigger == "rankingNear" },
        )
    }

    @Test
    fun splittingTheTickDoesNotChangeTheWeekEnd() {
        val s0 = withSelf(game(seed = 5), 40_000)
        val end = start + WeeklyEvents.WEEK_SEC
        val from = at(s0, end - 2 * 86_400)
        val whole = Simulation.tick(from, 3 * 86_400, data)
        var cur = from
        var gems = 0L
        for (dt in listOf(86_399L, 1L, 80_000L, 6_400L, 86_400L)) {
            val r = Simulation.tick(cur, dt, data)
            cur = r.state
            gems += 0
        }
        assertEquals(whole.state.event, cur.event)
        assertEquals(whole.state.gems, cur.gems + gems)
        assertEquals(whole.state.mails.map { it.params }, cur.mails.map { it.params })
        assertEquals(
            whole.state.shop.offerQueue.map { it.trigger to it.sku },
            cur.shop.offerQueue.map { it.trigger to it.sku },
        )
    }

    @Test
    fun comebackPackKeepsFirstPlaceWhenOwnScoreKeepsGrowing() {
        for (seed in 1L..20L) {
            val def = WeeklyEvents.definition(week, data)
            val end = start + WeeklyEvents.WEEK_SEC
            val s0 = withSelf(game(seed = seed), def.par)
            val s1 = Simulation.tick(at(s0, end - data.events.warnBeforeEndSec - 10), 100, data).state
            val offer = OfferEngine.active(s1).firstOrNull { it.trigger == "rankingNear" } ?: continue
            val bought = Actions.fakePurchase(s1, com.ttmrase.crownfall.core.shop.ProductIds.offer(offer.id), data).ok()
            assertEquals(1, WeeklyEvents.rank(bought, data), "seed $seed")
            // 購入後に自力スコアが 2 倍になっても、週末まで 1 位
            val grown = withSelf(bought, bought.event.selfScore * 2)
            assertEquals(1, WeeklyEvents.rank(at(grown, end - 1), data), "seed $seed grown")
            val done = Simulation.tick(at(grown, end - 5), 3600, data).state
            val rank = done.mails.last { it.systemCode == "event_result" }.params.getValue("rank")
            assertEquals(1L, rank, "seed $seed result")
        }
    }

    @Test
    fun gatherFinishedBeforeWeekEndIsScoredInThatWeekEvenWhenClosed() {
        var gw = week
        while (WeeklyEvents.definition(gw, data).kind != "GATHER") gw++
        val wStart = WeeklyEvents.weekStart(gw, 0)
        val end = wStart + WeeklyEvents.WEEK_SEC
        val base0 = game(seed = 2, at = wStart + 3600)
        val base = Simulation.tick(base0, 60, data).state
        val march =
            com.ttmrase.crownfall.core.march.March(
                1,
                com.ttmrase.crownfall.core.march.MarchKind.GATHER,
                mapOf("infantry_t1" to 10L),
                target = com.ttmrase.crownfall.core.map.TileRef(1, 1),
                phase = com.ttmrase.crownfall.core.march.MarchPhase.RETURNING,
                phaseStartEpochSec = end - 7200,
                phaseEndEpochSec = end - 3600,
                travelSec = 3600,
                loot = com.ttmrase.crownfall.core.model.Resources(1_000_000, 0, 0, 0),
            )
        val from = at(base, end - 7200)
        val s = from.copy(marches = listOf(march), event = from.event.copy(weekId = gw, selfScore = 30_000))
        val to = end + 3600
        val closed = com.ttmrase.crownfall.core.sim.OfflineCatchUp.run(s, to, data).state
        var open = s
        var t = s.nowEpochSec
        while (t < to) {
            val step = minOf(60L, to - t)
            open = com.ttmrase.crownfall.core.sim.OfflineCatchUp.run(open, t + step, data).state
            t += step
        }
        val a = closed.mails.filter { it.systemCode == "event_result" }.map { it.params }
        val b = open.mails.filter { it.systemCode == "event_result" }.map { it.params }
        assertEquals(1, a.size)
        assertEquals(b, a)
        assertTrue((a[0]["score"] ?: 0) > 30_000) // 採集ぶんが先週のスコアに入っている
        assertEquals(open.event, closed.event)
    }

    @Test
    fun comebackPackGuaranteesFirstPlaceEvenIfOwnScoreNeverGrowsAgain() {
        var checked = 0
        for (seed in 1L..60L) {
            for (selfShare in listOf(2L, 3L, 5L)) {
                val def = WeeklyEvents.definition(week, data)
                val end = start + WeeklyEvents.WEEK_SEC
                val s0 = withSelf(game(seed = seed), def.par * selfShare / 5)
                val s1 = Simulation.tick(at(s0, end - data.events.warnBeforeEndSec - 10), 100, data).state
                val offer = OfferEngine.active(s1).firstOrNull { it.trigger == "rankingNear" } ?: continue
                val bought =
                    Actions.fakePurchase(s1, com.ttmrase.crownfall.core.shop.ProductIds.offer(offer.id), data).ok()
                // 自力は二度と伸びない。週末まで進めた結果が 1 位
                val done = Simulation.tick(at(bought, end - 5), 3600, data).state
                val rank = done.mails.last { it.systemCode == "event_result" }.params.getValue("rank")
                assertEquals(1L, rank, "seed $seed share $selfShare")
                checked++
            }
        }
        assertTrue(checked > 20, "only $checked scenarios had a comeback offer")
    }
}
