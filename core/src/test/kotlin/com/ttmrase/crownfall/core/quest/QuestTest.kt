package com.ttmrase.crownfall.core.quest

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.Tracking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestTest {
    private val data = TestSupport.data
    private val day = 86_400L
    private val t0 = 20_000L * day + 12 * 3600

    private fun base(utc: Int = 0): GameState {
        val s = TestSupport.newGame(now = t0)
        return s.copy(
            settings = s.settings.copy(utcOffsetSec = utc),
            map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4),
        )
    }

    private fun track(
        s: GameState,
        vararg events: GameEvent,
    ) = Tracking.apply(s, events.toList(), data)

    // ───── イベントからの加算 ─────

    @Test
    fun countersComeOnlyFromEventsAndFeedBothTotalsAndDaily() {
        val s =
            track(
                base(),
                GameEvent.HelpGiven(3),
                GameEvent.HelpGiven(2),
                GameEvent.GatherCompleted(500),
                GameEvent.TroopsTrained("infantry_t1", 40),
                GameEvent.TroopsTrained("archer_t1", 10),
                GameEvent.ResearchCompleted("x", 1),
                GameEvent.BuildingLevelUp(0, BuildingType.FARM, 2),
                GameEvent.HuntWon(false, 3),
                GameEvent.Donated(100),
                GameEvent.ShopOpened,
                GameEvent.ChatPosted,
                GameEvent.RallyJoined,
            )
        val expected =
            mapOf(
                "help" to 5L, "gather" to 1L, "train" to 2L, "trainTroops" to 50L, "research" to 1L, "build" to 1L,
                "hunt" to 1L, "donate" to 1L, "shop" to 1L, "chat" to 1L, "rally" to 1L,
            )
        assertEquals(expected, s.quest.counters)
        assertEquals(expected, s.quest.dailyCounts)
        // 関係の無いイベントでは何も増えない
        assertEquals(
            s.quest.counters,
            track(
                s,
                GameEvent.FoodDepleted,
                GameEvent.MailReceived(1, com.ttmrase.crownfall.core.model.MailKind.SYSTEM),
            ).quest.counters,
        )
    }

    @Test
    fun summonCountsEveryRoll() {
        val result = com.ttmrase.crownfall.core.model.SummonResult(List(10) { dummyEntry() }, 0)
        val s = Tracking.apply(base(), listOf(GameEvent.HeroSummoned(result)), data)
        assertEquals(10L, s.quest.counters["summon"])
    }

    private fun dummyEntry() =
        com.ttmrase.crownfall.core.model.SummonEntry(
            "hero_aldric",
            com.ttmrase.crownfall.core.model.HeroRarity.R,
            false,
            0,
        )

    // ───── 状態から判定する条件 ─────

    @Test
    fun stateConditionsAreEvaluatedOnDemand() {
        val s = TestSupport.advanced(level = 5, castle = 5, now = t0)

        fun cond(
            type: String,
            building: String? = null,
            lv: Int = 0,
            value: Long = 0,
            total: Int = 0,
            count: Int = 0,
        ) = com.ttmrase.crownfall.core.data.QuestCond(type, building, lv, value, total, count)
        assertTrue(QuestLogic.progress(cond("building", "castle", 5), s, data).done)
        assertFalse(QuestLogic.progress(cond("building", "castle", 6), s, data).done)
        assertEquals(5L, QuestLogic.progress(cond("building", "wall", 9), s, data).current)
        assertTrue(QuestLogic.progress(cond("power", value = 1000), s, data).done)
        assertFalse(QuestLogic.progress(cond("research", total = 3), s, data).done)
        assertTrue(
            QuestLogic.progress(cond("research", total = 3), s.copy(research = mapOf("a" to 2, "b" to 1)), data).done,
        )
        assertEquals(s.heroes.size.toLong(), QuestLogic.progress(cond("heroes", count = 9), s, data).current)
    }

    // ───── デイリー ─────

    @Test
    fun dailyResetsOnTheLocalDayButTotalsStay() {
        var s = track(base(), GameEvent.HelpGiven(5))
        val help = data.quests.dailies.first { it.key == "help" }
        assertTrue(QuestLogic.dailyProgress(help, s).done)
        s = Actions.claimQuest(s, help.id, data).ok()
        assertEquals(10, QuestLogic.dailyPoints(s, data))
        // 同じ日: 二重に受け取れない
        assertEquals(ActionFailure.AlreadyClaimed, Actions.claimQuest(s, help.id, data).failure())
        // 翌日 0 時を過ぎたら（イベントが無くても）見た目はリセット
        val next = s.copy(nowEpochSec = (t0 / day + 1) * day + 5)
        assertEquals(0, QuestLogic.dailyPoints(next, data))
        assertEquals(0L, QuestLogic.dailyProgress(help, next).current)
        val tracked = track(next, GameEvent.HelpGiven(1))
        assertEquals(1L, tracked.quest.dailyCounts["help"])
        assertEquals(6L, tracked.quest.counters["help"])
        assertTrue(tracked.quest.dailyClaimed.isEmpty() && tracked.quest.dailyChests.isEmpty())
    }

    @Test
    fun dailyBoundaryFollowsTheTimeZone() {
        val jst = base(utc = 9 * 3600) // t0 は UTC 正午 = 21:00 JST。3 時間後に日が変わる
        val s = track(jst, GameEvent.ShopOpened)
        val early = s.copy(nowEpochSec = s.nowEpochSec + 2 * 3600)
        val late = s.copy(nowEpochSec = s.nowEpochSec + 3 * 3600)
        assertEquals(1L, track(early, GameEvent.ShopOpened).quest.dailyCounts["shop"]?.minus(1) ?: 0L)
        assertEquals(1L, track(late, GameEvent.ShopOpened).quest.dailyCounts["shop"])
    }

    @Test
    fun chestsNeedPointsAndAreClaimedOncePerDay() {
        var s = base()
        val all = data.quests.dailies
        val events = all.map { d -> listOf(eventFor(d.key)) }.flatten()
        s = Tracking.apply(s, events + GameEvent.HelpGiven(4), data) // help は 5 回
        assertEquals(ActionFailure.NothingToClaim, Actions.claimDailyChest(s, 1, data).failure())
        // 項目ごとに受け取ってポイントを貯める
        data.quests.chests.forEach { chest ->
            while (QuestLogic.dailyPoints(s, data) < chest.points) {
                val d = all.first { it.id !in s.quest.dailyClaimed }
                s = Actions.claimQuest(s, d.id, data).ok()
            }
            val gems = s.gems
            s = Actions.claimDailyChest(s, chest.tier, data).ok()
            assertEquals(gems + chest.reward.gems, s.gems)
            assertEquals(ActionFailure.AlreadyClaimed, Actions.claimDailyChest(s, chest.tier, data).failure())
        }
        assertEquals(100, QuestLogic.dailyPoints(s, data))
        assertEquals(ActionFailure.QuestNotFound, Actions.claimDailyChest(s, 9, data).failure())
        val tomorrow = s.copy(nowEpochSec = s.nowEpochSec + day)
        assertEquals(0, QuestLogic.dailyPoints(tomorrow, data))
        assertTrue(QuestLogic.view(tomorrow).dailyChests.isEmpty())
    }

    private fun eventFor(key: String): GameEvent =
        when (key) {
            "help" -> GameEvent.HelpGiven(1)
            "gather" -> GameEvent.GatherCompleted(1)
            "train" -> GameEvent.TroopsTrained("infantry_t1", 1)
            "research" -> GameEvent.ResearchCompleted("x", 1)
            "build" -> GameEvent.BuildingLevelUp(0, BuildingType.FARM, 2)
            "hunt" -> GameEvent.HuntWon(false, 1)
            "donate" -> GameEvent.Donated(1)
            "summon" -> GameEvent.HeroSummoned(com.ttmrase.crownfall.core.model.SummonResult(listOf(dummyEntry()), 0))
            "shop" -> GameEvent.ShopOpened
            else -> GameEvent.ChatPosted
        }

    // ───── チャプター・同盟・実績 ─────

    @Test
    fun chaptersProgressInOrderAndPayOnce() {
        val ch1 = data.quests.chapters[0]
        val ch2 = data.quests.chapters[1]
        var s = TestSupport.newGame(now = t0)
        assertEquals(ch1, QuestLogic.currentChapter(s, data))
        assertEquals(ActionFailure.NothingToClaim, Actions.claimQuest(s, ch1.id, data).failure())
        // 1 章のタスク（城 Lv2・農場 Lv2・製材所 Lv2）を満たす
        val lv2 = setOf(BuildingType.CASTLE, BuildingType.FARM, BuildingType.SAWMILL)
        s = s.copy(slots = s.slots.map { if (it.type in lv2) it.copy(level = 2) else it })
        assertTrue(QuestLogic.chapterDone(ch1, s, data))
        assertEquals(1, QuestLogic.claimableCount(s, data))
        // 2 章は 1 章を受け取るまで受け取れない
        assertEquals(ActionFailure.NothingToClaim, Actions.claimQuest(s, ch2.id, data).failure())
        val gems = s.gems
        s = Actions.claimQuest(s, ch1.id, data).ok()
        assertEquals(gems + ch1.reward.gems, s.gems)
        ch1.reward.items.forEach { (id, n) -> assertTrue((s.items[id] ?: 0) >= n) }
        assertEquals(ActionFailure.AlreadyClaimed, Actions.claimQuest(s, ch1.id, data).failure())
        assertEquals(ch2, QuestLogic.currentChapter(s, data))
        assertEquals(ActionFailure.QuestNotFound, Actions.claimQuest(s, "ch99", data).failure())
    }

    @Test
    fun allianceAndAchievementQuestsAreOneShot() {
        var s = track(base(), GameEvent.Donated(1))
        val donate1 = data.quests.alliance.first { it.id == "al_donate_1" }
        assertTrue(donate1 in QuestLogic.claimableOneShots(s, data))
        s = Actions.claimQuest(s, donate1.id, data).ok()
        assertEquals(ActionFailure.AlreadyClaimed, Actions.claimQuest(s, donate1.id, data).failure())
        assertFalse(donate1 in QuestLogic.claimableOneShots(s, data))
        assertEquals(ActionFailure.NothingToClaim, Actions.claimQuest(s, "al_donate_10", data).failure())
        // 実績の段階: 討伐 10 / 50
        val many = Tracking.apply(s, List(10) { GameEvent.HuntWon(false, 1) }, data)
        assertTrue(data.quests.achievements.first { it.id == "ach_hunt_1" } in QuestLogic.claimableOneShots(many, data))
        assertFalse(
            data.quests.achievements.first { it.id == "ach_hunt_2" } in QuestLogic.claimableOneShots(many, data),
        )
    }

    @Test
    fun nothingIsClaimedAutomatically() {
        val s = Tracking.apply(base(), List(50) { GameEvent.HuntWon(false, 1) } + GameEvent.HelpGiven(5), data)
        val before = s.gems
        val later = Simulation.tick(s, 3600, data).state
        assertEquals(before, later.gems)
        assertTrue(later.quest.claimed.isEmpty() && later.quest.dailyClaimed.isEmpty())
        assertTrue(QuestLogic.claimableCount(later, data) > 0)
    }

    // ───── R3 ─────

    @Test
    fun countersDoNotDependOnHowTheTickIsSplit() {
        var s = TestSupport.advanced(level = 3, castle = 3, now = t0).copy(resources = TestSupport.RICH)
        s = s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
        val farm = s.slots.first { it.type == BuildingType.FARM }.slotId
        val sawmill = s.slots.first { it.type == BuildingType.SAWMILL }.slotId
        s =
            s.copy(
                timers =
                    listOf(
                        TimerJob(1, TimerKind.BUILD, farm, 4, t0, t0 + 300),
                        TimerJob(2, TimerKind.BUILD, sawmill, 4, t0, t0 + 900),
                    ),
                nextJobId = 3,
            )
        val whole = Simulation.tick(s, 2000, data)
        val wholeTracked = Tracking.apply(whole.state, whole.events, data)
        var cur = s
        var events = emptyList<GameEvent>()
        for (dt in listOf(299L, 2L, 598L, 1L, 600L, 500L)) {
            val r = Simulation.tick(cur, dt, data)
            cur = r.state
            events = events + r.events
        }
        val splitTracked = Tracking.apply(cur, events, data)
        assertEquals(wholeTracked.quest, splitTracked.quest)
        assertEquals(2L, splitTracked.quest.counters["build"])
    }
}
