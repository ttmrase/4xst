package com.ttmrase.crownfall.core.notify

import com.ttmrase.crownfall.core.TestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPolicyTest {
    private val rules = TestSupport.data.notifyRules
    private val hour0 = 20_000L * 86_400 // UTC 0 時

    private fun n(
        type: NoticeType,
        at: Long,
        count: Int = 1,
        param: Int = 0,
    ) = PlannedNotice(at, type, type.channel, count, param)

    private fun apply(
        plan: List<PlannedNotice>,
        settings: NotifySettings = NotifySettings(),
    ) = NotificationPolicy.apply(plan, settings, rules)

    @Test
    fun intensityDecidesWhichCategoriesPass() {
        val plan =
            listOf(
                n(NoticeType.RAID_1H, hour0 + 10),
                n(NoticeType.BUILD_DONE, hour0 + 20),
                n(NoticeType.ALLIANCE_DM, hour0 + 30),
                n(NoticeType.OFFER_EXPIRING, hour0 + 40),
                n(NoticeType.DAILY_LEFT, hour0 + 50),
                n(NoticeType.COMEBACK, hour0 + 60),
            )
        assertEquals(6, apply(plan, NotifySettings(Intensity.MASS)).size)
        assertEquals(
            listOf(NoticeType.RAID_1H, NoticeType.BUILD_DONE, NoticeType.ALLIANCE_DM, NoticeType.OFFER_EXPIRING),
            apply(plan, NotifySettings(Intensity.STANDARD)).map { it.type },
        )
        assertEquals(
            listOf(NoticeType.RAID_1H, NoticeType.BUILD_DONE),
            apply(plan, NotifySettings(Intensity.LOW)).map { it.type },
        )
    }

    @Test
    fun userCanSwitchOffCategoriesButRaidsStayWhenOnlyIntensityIsLowered() {
        val plan = listOf(n(NoticeType.RAID_STARTED, hour0 + 10), n(NoticeType.OFFER_EXPIRING, hour0 + 20))
        val off = apply(plan, NotifySettings(disabled = setOf(NoticeCategory.OFFER)))
        assertEquals(listOf(NoticeType.RAID_STARTED), off.map { it.type })
        val raidOff = apply(plan, NotifySettings(disabled = setOf(NoticeCategory.RAID)))
        assertEquals(listOf(NoticeType.OFFER_EXPIRING), raidOff.map { it.type })
    }

    @Test
    fun hourlyCapFoldsTheRestIntoOneSummaryAndNeverTouchesRaids() {
        val cap = rules.intensity.getValue("LOW").hourlyCap
        val timers = (1..cap + 3).map { n(NoticeType.BUILD_DONE, hour0 + it * 10L, param = it) }
        val raid = n(NoticeType.RAID_15M, hour0 + 500)
        val out = apply(timers + raid, NotifySettings(Intensity.LOW))
        assertEquals(cap, out.count { it.type == NoticeType.BUILD_DONE })
        val summary = out.single { it.type == NoticeType.SUMMARY }
        assertEquals(3, summary.count)
        assertEquals(hour0 + (cap + 1) * 10L, summary.epochSec)
        assertTrue(out.any { it.type == NoticeType.RAID_15M })
        // 別の時間帯は別に数える
        val next = timers.map { it.copy(epochSec = it.epochSec + 3600) }
        assertEquals(2, apply(timers + next, NotifySettings(Intensity.LOW)).count { it.type == NoticeType.SUMMARY })
    }

    @Test
    fun quietHoursStopEverythingButRaidsUnlessTheyAreAlsoSilenced() {
        // 現地 JST。UTC 15:00 = 0:00 JST
        val jst = 9 * 3600
        val midnightJst = hour0 + 15 * 3600 // JST 0:00 と同じ時刻
        val plan = listOf(n(NoticeType.BUILD_DONE, midnightJst + 60), n(NoticeType.RAID_STARTED, midnightJst + 120))
        val quiet =
            NotifySettings(quietEnabled = true, quietStartMinute = 23 * 60, quietEndMinute = 7 * 60, utcOffsetSec = jst)
        assertEquals(listOf(NoticeType.RAID_STARTED), apply(plan, quiet).map { it.type })
        assertTrue(apply(plan, quiet.copy(raidThroughQuiet = false)).isEmpty())
        // おやすみ時間の外
        val daytime = listOf(n(NoticeType.BUILD_DONE, midnightJst + 12 * 3600))
        assertEquals(1, apply(daytime, quiet).size)
        // 日をまたがない時間帯
        val lunch = quiet.copy(quietStartMinute = 12 * 60, quietEndMinute = 13 * 60)
        assertEquals(0, apply(listOf(n(NoticeType.BUILD_DONE, midnightJst + 12 * 3600 + 60)), lunch).size)
        assertEquals(1, apply(listOf(n(NoticeType.BUILD_DONE, midnightJst + 13 * 3600)), lunch).size)
        // 無効なら何もしない
        assertEquals(2, apply(plan, quiet.copy(quietEnabled = false)).size)
    }

    @Test
    fun resultIsSortedAndIdempotent() {
        val plan =
            listOf(
                n(NoticeType.OFFER_EXPIRING, hour0 + 50),
                n(NoticeType.RAID_STARTED, hour0 + 10),
                n(NoticeType.BUILD_DONE, hour0 + 30),
            )
        val once = apply(plan)
        assertEquals(once.sortedBy { it.epochSec }, once)
        assertEquals(once, apply(once))
    }

    @Test
    fun nextLocalMinuteHandlesTimeZonesAndSkipToday() {
        val jst = 9 * 3600
        val now = hour0 + 15 * 3600 + 3600 // JST 1:00
        val at = Lookahead.nextLocal(now, jst, 9 * 60)!!
        assertEquals(now + 8 * 3600, at)
        assertEquals(at + 86_400, Lookahead.nextLocal(now, jst, 9 * 60, skipToday = true)!!)
        // いまちょうどの時刻は翌日
        assertEquals(now + 86_400, Lookahead.nextLocal(now, jst, 60))
    }
}
