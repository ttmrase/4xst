package com.ttmrase.crownfall.core.notify

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.shop.ShopTestSupport
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による M9 の境界テスト（先読みと実際の catch-up の一致、上限、通知の選別、襲撃段階の R3）。 */
class QaNotifyTest {
    private val data = TestSupport.data
    private val hour = 3600L
    private val t0 = 20_000L * 86_400 + 3_600

    private fun game(
        watchtower: Int,
        raidIn: Long,
        archers: Long = 0,
    ): GameState {
        val s0 = TestSupport.withLevel(TestSupport.newGame(now = t0), BuildingType.WATCHTOWER, watchtower)
        val s1 =
            Simulation.tick(
                s0.copy(troops = if (archers > 0) mapOf("archer_t2" to archers) else emptyMap()),
                1,
                data,
            )
        val s = s1.state
        return s.copy(
            map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + raidIn, raidWarned = false, raidStageDone = 0),
        )
    }

    private fun typeOf(e: GameEvent.RaidStageReached) =
        when (e.stage) {
            com.ttmrase.crownfall.core.model.RaidStage.SCOUTED -> NoticeType.RAID_SCOUTED
            com.ttmrase.crownfall.core.model.RaidStage.MARCHING -> NoticeType.RAID_MARCHING
            com.ttmrase.crownfall.core.model.RaidStage.T_MINUS_1H -> NoticeType.RAID_1H
            com.ttmrase.crownfall.core.model.RaidStage.T_MINUS_15M -> NoticeType.RAID_15M
            com.ttmrase.crownfall.core.model.RaidStage.STARTED -> NoticeType.RAID_STARTED
            com.ttmrase.crownfall.core.model.RaidStage.RESULT ->
                if (e.defended) NoticeType.RAID_DEFENDED else NoticeType.RAID_DEFEATED
        }

    // ───── 先読み = 実際の catch-up ─────

    @Test
    fun raidNoticesOfTheLookaheadAreExactlyTheStagesOfARealCatchUp() {
        val rnd = Random(2024)
        var defended = 0
        var defeated = 0
        repeat(120) {
            val wt = rnd.nextInt(11)
            val archers = listOf(0L, 40L, 400L, 4000L)[rnd.nextInt(4)]
            val raidIn = 600L + rnd.nextInt(30 * 3600)
            val s = game(wt, raidIn, archers)
            val until = s.map.nextRaidEpochSec + 120
            val planned =
                NotificationPlanner.lookahead(s, data, until - s.nowEpochSec)
                    .filter { it.type.category == NoticeCategory.RAID && it.epochSec <= until }
                    .map { it.epochSec to it.type }
            val real =
                OfflineCatchUp.run(s, until, data).events.filterIsInstance<GameEvent.RaidStageReached>()
                    .map { e ->
                        val offset = data.notifyRules.raid.stages.first { it.stage == e.stage.name }.offsetSec
                        (e.raidAt - offset) to typeOf(e)
                    }
                    .filter { it.first > s.nowEpochSec }
            assertEquals(real, planned, "wt=$wt archers=$archers raidIn=$raidIn")
            if (real.any { it.second == NoticeType.RAID_DEFENDED }) defended++
            if (real.any { it.second == NoticeType.RAID_DEFEATED }) defeated++
        }
        assertTrue(defended > 5 && defeated > 5, "both outcomes must be exercised: $defended / $defeated")
    }

    @Test
    fun aRaidBeyondTheOfflineCapIsNeitherAnnouncedNorResolvedByTheCatchUp() {
        val cap = data.rules.offlineCapSec
        val s = game(watchtower = 10, raidIn = cap + hour, archers = 40)
        val plan = NotificationPlanner.lookahead(s, data, 10 * cap)
        assertTrue(
            plan.none { it.type == NoticeType.RAID_STARTED || it.type == NoticeType.RAID_DEFEATED },
            "$plan",
        )
        assertTrue(
            plan.filter { it.type.category != NoticeCategory.COMEBACK }.all { it.epochSec <= s.nowEpochSec + cap },
        )
        // 長く閉じていても、上限の外の襲撃は帰った時点では起きていない（時刻がずれて、帰った 1 時間後）
        for (away in listOf(cap + hour, 2 * cap, 5 * cap)) {
            val back = OfflineCatchUp.run(s, s.nowEpochSec + away, data)
            val results =
                back.events.filterIsInstance<GameEvent.RaidStageReached>().filter { it.stage.name == "RESULT" }
            assertTrue(results.isEmpty(), "away=$away resolved a raid the lookahead did not announce")
            assertEquals(back.state.nowEpochSec + hour, back.state.map.nextRaidEpochSec, "away=$away")
        }
    }

    @Test
    fun vipExtendedOfflineCapIsUsedByBothTheLookaheadAndTheCatchUp() {
        var s = ShopTestSupport.base()
        val gem = data.offers.gemSkus.maxByOrNull { it.priceYen }!!.sku
        var n = 0
        while (VipCalculator.offlineBonusSec(s, data) == 0L && n++ < 60) s = Actions.fakePurchase(s, gem, data).ok()
        val bonus = VipCalculator.offlineBonusSec(s, data)
        assertTrue(bonus > 0, "scenario must reach a VIP level with an offline bonus")
        val cap = data.rules.offlineCapSec + bonus
        val inside =
            s.copy(
                map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + cap - hour, raidWarned = false, raidStageDone = 0),
            )
        val plan = NotificationPlanner.lookahead(inside, data, 10 * cap)
        assertTrue(plan.any { it.type == NoticeType.RAID_STARTED }, "inside the VIP cap, the raid is announced")
        val real = OfflineCatchUp.run(inside, inside.nowEpochSec + cap, data)
        assertTrue(real.events.any { it is GameEvent.RaidStageReached && it.stage.name == "RESULT" })
        val outside = inside.copy(map = inside.map.copy(nextRaidEpochSec = inside.nowEpochSec + cap + hour))
        assertTrue(NotificationPlanner.lookahead(outside, data, 10 * cap).none { it.type == NoticeType.RAID_STARTED })
    }

    // ───── 襲撃の段階の R3 ─────

    @Test
    fun raidStagesKeepSplitInvarianceForEveryWatchtowerLevelAndBothOutcomes() {
        val rnd = Random(77)
        for (wt in 0..10) {
            for (archers in listOf(0L, 4000L)) {
                val s = game(wt, raidIn = 13 * hour, archers = archers)
                val raidAt = s.map.nextRaidEpochSec
                val bounds =
                    data.notifyRules.raid.stages.flatMap {
                        listOf(
                            raidAt - it.offsetSec - 1,
                            raidAt - it.offsetSec,
                        )
                    }
                SplitInvariance.assertInvariant(
                    s,
                    13 * hour + 600,
                    data,
                    rnd,
                    randomCases = 12,
                    extraBoundaries = bounds,
                )
            }
        }
    }

    // ───── 通知の選別（NotificationPolicy） ─────

    private fun randomPlan(rnd: Random): List<PlannedNotice> {
        val types = NoticeType.entries.filter { it.category != NoticeCategory.SUMMARY }
        return List(40 + rnd.nextInt(80)) {
            val t = types[rnd.nextInt(types.size)]
            PlannedNotice(t0 + 1 + rnd.nextInt(6 * 3600), t, t.channel, 1 + rnd.nextInt(2), rnd.nextInt(3))
        }.distinctBy { Triple(it.epochSec, it.type, it.param) }
    }

    @Test
    fun policyNeverDropsARaidNeverExceedsTheHourlyCapAndKeepsEveryOtherNoticeCounted() {
        val rnd = Random(5)
        repeat(300) {
            val plan = randomPlan(rnd)
            val intensity = Intensity.entries[rnd.nextInt(3)]
            val disabled = NoticeCategory.entries.filter { rnd.nextInt(5) == 0 }.toSet()
            val quiet = rnd.nextBoolean()
            val off = (rnd.nextInt(27) - 12) * 3600
            val start = rnd.nextInt(1440)
            val settings =
                NotifySettings(
                    intensity,
                    disabled,
                    quiet,
                    start,
                    rnd.nextInt(1440),
                    raidThroughQuiet = true,
                    utcOffsetSec = off,
                )
            val rule = data.notifyRules.intensity.getValue(intensity.name)
            val out = NotificationPolicy.apply(plan, settings, data.notifyRules)

            assertEquals(out, out.sortedWith(compareBy({ it.epochSec }, { it.type.ordinal }, { it.param })))
            // 襲撃は強度でカテゴリが許される限り、おやすみでも上限でも落ちない（ユーザーが切ったときだけ消える）
            val raidIn = plan.filter { it.type.category == NoticeCategory.RAID }
            val raidOut = out.filter { it.type.category == NoticeCategory.RAID }
            val raidAllowed = "RAID" in rule.categories && NoticeCategory.RAID !in disabled
            assertEquals(
                if (raidAllowed) {
                    raidIn.sortedWith(
                        compareBy({ it.epochSec }, { it.type.ordinal }, { it.param }),
                    )
                } else {
                    emptyList()
                },
                raidOut,
            )
            // 切ったカテゴリ・強度外のカテゴリは出ない
            val allowed = rule.categories.toSet() - disabled.map { it.name }.toSet()
            assertTrue(out.all { it.type.category == NoticeCategory.SUMMARY || it.type.category.name in allowed })
            // 1 時間あたりの非襲撃（まとめ以外）は上限以下
            out.filter { it.type.category != NoticeCategory.RAID && it.type.category != NoticeCategory.SUMMARY }
                .groupBy { Math.floorDiv(it.epochSec, hour) }
                .forEach { (b, l) -> assertTrue(l.size <= rule.hourlyCap, "hour $b has ${l.size} > ${rule.hourlyCap}") }
            // 数は保存される: 出た件数 + まとめに畳まれた件数 = おやすみ・カテゴリ選別後の件数（件数の合計）
            val survivors =
                plan.filter { it.type.category.name in allowed }.filter { n ->
                    !(quiet && inQuiet(n.epochSec, settings) && n.type.category != NoticeCategory.RAID)
                }
            assertEquals(
                survivors.filter { it.type.category != NoticeCategory.RAID }.sumOf { it.count },
                out.filter { it.type.category != NoticeCategory.RAID }.sumOf { it.count },
                "notices must be shown or folded into a summary, never silently lost",
            )
        }
    }

    private fun inQuiet(
        epoch: Long,
        s: NotifySettings,
    ): Boolean {
        val m = (Math.floorMod(epoch + s.utcOffsetSec, 86_400L) / 60).toInt()
        return if (s.quietStartMinute <= s.quietEndMinute) {
            m in s.quietStartMinute until s.quietEndMinute
        } else {
            m >= s.quietStartMinute || m < s.quietEndMinute
        }
    }

    @Test
    fun quietHoursAreStartInclusiveEndExclusiveAcrossMidnightAndOffsets() {
        for (off in listOf(0, 9 * 3600, -5 * 3600, 14 * 3600)) {
            val settings =
                NotifySettings(Intensity.MASS, emptySet(), true, 23 * 60, 7 * 60, utcOffsetSec = off)
            val dayStart = Math.floorDiv(t0 + off, 86_400L) * 86_400L - off

            fun at(
                localMinute: Int,
                sec: Int = 0,
            ) = PlannedNotice(
                dayStart + 86_400L + localMinute * 60L + sec,
                NoticeType.OFFER_LAST_CALL,
                NoticeChannel.OFFER,
            )
            val out =
                NotificationPolicy.apply(
                    listOf(at(7 * 60 - 1, 59), at(7 * 60), at(22 * 60 + 59, 59), at(23 * 60), at(0)),
                    settings,
                    data.notifyRules,
                )
            // 7:00 ちょうど（終了は含まない）と 22:59:59 だけが残る
            assertEquals(
                listOf(at(7 * 60), at(22 * 60 + 59, 59)).map { it.epochSec },
                out.map { it.epochSec },
                "off=$off",
            )
        }
    }

    @Test
    fun lowIntensityOnlyAllowsRaidAndTimerAndDisabledRaidIsHonoured() {
        val rnd = Random(9)
        val plan = randomPlan(rnd)
        val low = NotificationPolicy.apply(plan, NotifySettings(Intensity.LOW), data.notifyRules)
        assertTrue(
            low.all { it.type.category in setOf(NoticeCategory.RAID, NoticeCategory.TIMER, NoticeCategory.SUMMARY) },
        )
        val noRaid =
            NotificationPolicy.apply(
                plan,
                NotifySettings(Intensity.MASS, disabled = setOf(NoticeCategory.RAID)),
                data.notifyRules,
            )
        assertTrue(noRaid.none { it.type.category == NoticeCategory.RAID })
    }
}
