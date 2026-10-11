package com.ttmrase.crownfall.core.notify

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.data.OfferBadge
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.Offer
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.RaidStage
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RaidStageAndLookaheadTest {
    private val data = TestSupport.data
    private val t0 = 20_000L * 86_400 + 3_600

    private fun game(
        watchtower: Int,
        raidIn: Long,
    ): GameState {
        val s0 = TestSupport.withLevel(TestSupport.newGame(now = t0, castle = 10), BuildingType.WATCHTOWER, watchtower)
        val s1 = Simulation.tick(s0, 1, data).state
        return s1.copy(
            map = s1.map.copy(nextRaidEpochSec = s1.nowEpochSec + raidIn, raidWarned = false, raidStageDone = 0),
        )
    }

    /** 30 秒刻みで進めて、各段階がいつ出たかを集める。 */
    private fun stagesSeen(
        s0: GameState,
        total: Long,
    ): List<Pair<RaidStage, Long>> {
        var s = s0
        val seen = ArrayList<Pair<RaidStage, Long>>()
        var t = 0L
        while (t < total) {
            val r = Simulation.tick(s, 30, data)
            s = r.state
            t += 30
            r.events.filterIsInstance<GameEvent.RaidStageReached>().forEach { seen.add(it.stage to s.nowEpochSec) }
        }
        return seen
    }

    @Test
    fun stagesComeAtTheirOffsetsAndAreVisibleByWatchtowerLevel() {
        val rules = data.notifyRules.raid.stages
        val high = game(watchtower = 10, raidIn = 20 * 3600L)
        val raidAt = high.map.nextRaidEpochSec
        val seen = stagesSeen(high, 20 * 3600L + 60)
        assertEquals(RaidStage.entries.toList(), seen.map { it.first })
        for ((stage, at) in seen) {
            val offset = rules.first { it.stage == stage.name }.offsetSec
            assertTrue(at in (raidAt - offset)..(raidAt - offset + 30), "$stage at ${at - raidAt}")
        }
        // Lv0 は開始と結果だけ
        val none = game(watchtower = 0, raidIn = 20 * 3600L)
        assertEquals(listOf(RaidStage.STARTED, RaidStage.RESULT), stagesSeen(none, 20 * 3600L + 60).map { it.first })
        // Lv1 は 15 分前から
        val one = game(watchtower = 1, raidIn = 20 * 3600L)
        assertEquals(
            listOf(RaidStage.T_MINUS_15M, RaidStage.STARTED, RaidStage.RESULT),
            stagesSeen(one, 20 * 3600L + 60).map { it.first },
        )
    }

    @Test
    fun stagesQueueAllianceChatAndKeepSplitInvariance() {
        val s = game(watchtower = 10, raidIn = 14_500)
        val after = Simulation.tick(s, 14_500 + 120, data).state
        val keys = after.alliance.pendingChat.map { it.event.key }
        assertTrue(
            "raid_marching" in keys && "raid_1h" in keys && "raid_15m" in keys && "raid_started" in keys,
            "$keys",
        )
        val raidAt = s.map.nextRaidEpochSec
        val bounds = data.notifyRules.raid.stages.map { raidAt - it.offsetSec }
        SplitInvariance.assertInvariant(s, 14_500 + 120, data, Random(7), 60, bounds)
    }

    @Test
    fun resultStageSaysWhoWon() {
        val s = game(watchtower = 0, raidIn = 120)
        val r = Simulation.tick(s, 200, data)
        val result = r.events.filterIsInstance<GameEvent.RaidStageReached>().single { it.stage == RaidStage.RESULT }
        val battle = r.state.mails.last { it.kind == MailKind.BATTLE }.battle
        assertNotNull(battle)
        assertEquals(!battle.attackerWon, result.defended)
    }

    @Test
    fun lookaheadMatchesWhatReallyHappensInACatchUp() {
        for (wt in listOf(0, 3, 10)) {
            val s = game(watchtower = wt, raidIn = 9 * 3600L)
            val plan = NotificationPlanner.lookahead(s, data)
            val raid = plan.filter { it.type.category == NoticeCategory.RAID }
            val real = OfflineCatchUp.run(s, s.map.nextRaidEpochSec + 60, data).state
            val battle = real.mails.last { it.kind == MailKind.BATTLE }.battle!!
            val expected = if (battle.attackerWon) NoticeType.RAID_DEFEATED else NoticeType.RAID_DEFENDED
            assertTrue(raid.any { it.type == expected }, "wt=$wt $raid")
            assertEquals(1, raid.count { it.type == NoticeType.RAID_STARTED })
            assertEquals(s.map.nextRaidEpochSec, raid.single { it.type == NoticeType.RAID_STARTED }.epochSec)
            // 段階の数は物見の塔の Lv で決まる
            val visible = data.notifyRules.raid.stages.count { it.minWatchtowerLv <= wt && it.offsetSec < 9 * 3600L }
            assertEquals(visible, raid.size, "wt=$wt")
        }
    }

    @Test
    fun lookaheadNeverPredictsPastTheOfflineCapButStillSchedulesComebackNotices() {
        val cap = data.rules.offlineCapSec
        val beyond = game(watchtower = 10, raidIn = cap + 3_600)
        val plan = NotificationPlanner.lookahead(beyond, data)
        // 上限の内側で起きる段階（12 時間前の「偵察された」）は予告してよいが、上限より先の開始・結果は予告しない
        val raids = plan.filter { it.type.category == NoticeCategory.RAID }
        assertTrue(raids.all { it.epochSec <= beyond.nowEpochSec + cap }, "$raids")
        assertTrue(raids.none { it.type == NoticeType.RAID_STARTED || it.type == NoticeType.RAID_DEFENDED }, "$raids")
        val inside = game(watchtower = 10, raidIn = cap - 3_600)
        assertTrue(NotificationPlanner.lookahead(inside, data).any { it.type == NoticeType.RAID_STARTED })
        val comebacks = plan.filter { it.type == NoticeType.COMEBACK }
        assertEquals(data.notifyRules.comebackDelaysSec.size, comebacks.size)
        assertTrue(comebacks.any { it.epochSec > beyond.nowEpochSec + cap })
        assertTrue(
            plan.filter { it.type.category != NoticeCategory.COMEBACK }.all { it.epochSec <= beyond.nowEpochSec + cap },
        )
    }

    @Test
    fun lookaheadIsSortedFutureOnlyAndFast() {
        val s = game(watchtower = 5, raidIn = 5 * 3600L)
        NotificationPlanner.lookahead(s, data) // warm up
        val start = System.nanoTime()
        val plan = NotificationPlanner.lookahead(s, data, 48 * 3600L)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 100, "took $ms ms")
        assertTrue(plan.all { it.epochSec > s.nowEpochSec })
        assertEquals(plan.sortedWith(compareBy({ it.epochSec }, { it.type.ordinal }, { it.param })), plan)
        assertEquals(plan.map { Triple(it.epochSec, it.type, it.param) }.toSet().size, plan.size)
    }

    @Test
    fun lookaheadFindsAllianceMessagesFromTheScheduler() {
        val s = game(watchtower = 0, raidIn = 100 * 3600L)
        val plan = NotificationPlanner.lookahead(s, data)
        // 24 時間先までの同盟の動き（要請・DM）が少なくとも 1 つは予測される
        assertTrue(plan.any { it.type == NoticeType.ALLIANCE_HELP || it.type == NoticeType.ALLIANCE_DM }, "$plan")
    }

    private fun offer(
        state: GameState,
        expiresIn: Long,
        revived: Boolean = false,
    ) = Offer(
        id = 1,
        sku = "starter",
        trigger = "test",
        createdEpochSec = state.nowEpochSec,
        expiresEpochSec = state.nowEpochSec + expiresIn,
        priceYen = 600,
        listValueYen = 1_800,
        badge = OfferBadge.entries.first(),
        contents = PackContents(),
        revived = revived,
    )

    @Test
    fun troopsGoIdleAfterTheLastTrainingJobEnds() {
        val s0 = game(watchtower = 0, raidIn = 90 * 3600L)

        fun job(
            id: Long,
            end: Long,
        ) = TimerJob(
            id = id,
            kind = TimerKind.TRAIN,
            startEpochSec = s0.nowEpochSec,
            endEpochSec = end,
            troopId = "infantry_t1",
            count = 10,
        )
        val s = s0.copy(timers = listOf(job(1, s0.nowEpochSec + 600), job(2, s0.nowEpochSec + 1_200)))
        val idle = NotificationPlanner.lookahead(s, data).filter { it.type == NoticeType.TROOPS_IDLE }
        assertEquals(1, idle.size)
        assertTrue(idle.single().epochSec > s.nowEpochSec + 1_200)
        assertEquals(NoticeChannel.TIMER, idle.single().channel)
        assertTrue(NotificationPlanner.lookahead(s0, data).none { it.type == NoticeType.TROOPS_IDLE })
    }

    @Test
    fun anExpiringOfferIsAnnouncedAsRevivedOnlyInMassFrequencyAndOnlyOnce() {
        val s0 = game(watchtower = 0, raidIn = 90 * 3600L)
        val withOffer = s0.copy(shop = s0.shop.copy(offerQueue = listOf(offer(s0, expiresIn = 7_200))))

        fun revived(st: GameState) =
            NotificationPlanner.lookahead(st, data).filter { it.type == NoticeType.OFFER_REVIVED }
        assertEquals(listOf(withOffer.nowEpochSec + 7_200), revived(withOffer).map { it.epochSec })
        val standardSettings = withOffer.shop.settings.copy(frequency = OfferFrequency.STANDARD)
        val standard = withOffer.copy(shop = withOffer.shop.copy(settings = standardSettings))
        assertTrue(revived(standard).isEmpty())
        val alreadyRevived = s0.copy(shop = s0.shop.copy(offerQueue = listOf(offer(s0, 7_200, revived = true))))
        assertTrue(revived(alreadyRevived).isEmpty())
    }
}
