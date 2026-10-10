package com.ttmrase.crownfall.core.notify

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.AllianceRequest
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.RequestKind
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationPlannerTest {
    private val data = TestSupport.data
    private val now = 20_000L * 86_400 + 3600

    private fun base(): GameState {
        val s = TestSupport.newGame(now = now)
        return s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
    }

    private fun plan(s: GameState) = NotificationPlanner.plan(s, data)

    @Test
    fun timersBecomeNoticesAtTheirEndTime() {
        val s =
            base().copy(
                timers =
                    listOf(
                        TimerJob(1, TimerKind.BUILD, 1, 2, now, now + 600),
                        TimerJob(2, TimerKind.RESEARCH, null, null, now, now + 1200),
                    ),
            )
        val timer = plan(s).filter { it.channel == NoticeChannel.TIMER && it.type != NoticeType.STORAGE_FULL }
        assertEquals(
            listOf(now + 600 to NoticeType.BUILD_DONE, now + 1200 to NoticeType.RESEARCH_DONE),
            timer.map {
                it.epochSec to it.type
            },
        )
    }

    @Test
    fun pastTimesAreNeverPlanned() {
        val s =
            base().copy(
                timers =
                    listOf(
                        TimerJob(1, TimerKind.BUILD, 1, 2, now - 900, now - 10),
                        TimerJob(2, TimerKind.TRAIN, 1, null, now - 5, now),
                    ),
            )
        assertTrue(plan(s).none { it.type == NoticeType.BUILD_DONE || it.type == NoticeType.TRAIN_DONE })
        assertTrue(plan(s).all { it.epochSec > now })
    }

    @Test
    fun sameTimeAndTypeAreMerged() {
        val s =
            base().copy(
                timers =
                    listOf(
                        TimerJob(1, TimerKind.BUILD, 1, 2, now, now + 600),
                        TimerJob(2, TimerKind.BUILD, 2, 2, now, now + 600),
                        TimerJob(3, TimerKind.BUILD, 3, 2, now, now + 700),
                    ),
            )
        val builds = plan(s).filter { it.type == NoticeType.BUILD_DONE }
        assertEquals(listOf(2, 1), builds.map { it.count })
    }

    @Test
    fun requestsExpireWithALeadTimeOnTheAllianceChannel() {
        val s =
            base().let {
                it.copy(
                    alliance =
                        it.alliance.copy(
                            requests =
                                listOf(
                                    AllianceRequest(1, "kaede", RequestKind.SCOUT, expiresEpochSec = now + 3600),
                                ),
                        ),
                )
            }
        val n = plan(s).single { it.type == NoticeType.REQUEST_EXPIRING }
        assertEquals(now + 3600 - 600, n.epochSec)
        assertEquals(NoticeChannel.ALLIANCE, n.channel)
    }

    @Test
    fun raidArrivalIsPlannedWhenThereIsNoWarningLeft() {
        val s = base().let { it.copy(map = it.map.copy(nextRaidEpochSec = now + 7200)) }
        val raid = plan(s).filter { it.channel == NoticeChannel.RAID }
        assertTrue(raid.isNotEmpty())
        assertTrue(raid.all { it.epochSec in (now + 1)..(now + 7200) })
    }

    @Test
    fun resultIsSortedAndBounded() {
        val timers = (1L..80L).map { TimerJob(it, TimerKind.TRAIN, 1, null, now, now + it * 60) }
        val p = plan(base().copy(timers = timers))
        assertTrue(p.size <= 60)
        assertEquals(p.sortedBy { it.epochSec }.map { it.epochSec }, p.map { it.epochSec })
    }

    @Test
    fun raidWarningAndArrivalAreBothPlannedWhenTheWarningIsAhead() {
        val s = base().let { it.copy(map = it.map.copy(nextRaidEpochSec = now + 20 * 3600)) }
        val raid = plan(s).filter { it.channel == NoticeChannel.RAID }
        val types = raid.map { it.type }
        assertEquals(listOf(NoticeType.RAID_ARRIVAL), types.filter { it == NoticeType.RAID_ARRIVAL })
        assertEquals(now + 20 * 3600, raid.single { it.type == NoticeType.RAID_ARRIVAL }.epochSec)
        val warned = s.copy(map = s.map.copy(raidWarned = true))
        assertEquals(
            listOf(NoticeType.RAID_ARRIVAL),
            plan(warned).filter { it.channel == NoticeChannel.RAID }.map { it.type },
        )
    }
}
