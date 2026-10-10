package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.data.GameDataException
import com.ttmrase.crownfall.core.data.GameDataLoader
import com.ttmrase.crownfall.core.model.ActionLogEntry
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.Settings
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AllianceSchedulerTest {
    private val data = TestSupport.data
    private val hour = 3600L
    private val day = 86_400L

    /** 現地（UTC+0）の 00:00 ちょうどから始まる新規ゲーム。 */
    private fun fresh(seed: Long = 42): GameState {
        val s = TestSupport.newGame(now = 20_000L * day, seed = seed)
        return s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
    }

    private fun queue(
        a: com.ttmrase.crownfall.core.model.AllianceState,
        event: ChatEvent,
        at: Long,
    ) = AllianceLogic.enqueue(a, event, at, listOf("kaede"), ChatKeys.ALLIANCE, data)

    private fun rich(s: GameState) = s.copy(resources = TestSupport.RICH)

    private fun tick(
        s: GameState,
        dt: Long,
    ) = Simulation.tick(s, dt, data)

    @Test
    fun sameSeedAndTimeGiveTheSameEvents() {
        val a = tick(fresh(7), 2 * day)
        val b = tick(fresh(7), 2 * day)
        assertEquals(a.state.alliance, b.state.alliance)
        assertTrue(a.state.alliance.chatQueuedTotal > 0)
        assertNotEquals(a.state.alliance.pendingChat, tick(fresh(8), 2 * day).state.alliance.pendingChat)
    }

    @Test
    fun personasSpeakOnlyInsideTheirActiveHours() {
        for (offset in listOf(0, 9 * 3600, -5 * 3600)) {
            var s = fresh().copy(settings = Settings(offset))
            var checked = 0
            repeat(7 * 24) {
                s = tick(s, hour).state
                for (p in s.alliance.pendingChat) {
                    val first = p.speakers.firstOrNull() ?: continue
                    val def = data.personas.persona(first)!!
                    assertTrue(
                        Personas.isActive(def, p.createdEpochSec, offset),
                        "$first at ${p.createdEpochSec} offset=$offset",
                    )
                    checked++
                }
                s = s.copy(alliance = s.alliance.copy(pendingChat = emptyList()))
            }
            assertTrue(checked > 20, "events=$checked")
        }
    }

    @Test
    fun activeHoursHandleWrapAndOffsets() {
        val kaede = data.personas.persona("kaede")!! // 20:00〜27:00 = 20:00〜03:00
        val base = 20_000L * day
        assertTrue(Personas.isActive(kaede, base + 21 * hour, 0))
        assertTrue(Personas.isActive(kaede, base + 2 * hour, 0))
        assertFalse(Personas.isActive(kaede, base + 3 * hour, 0))
        assertFalse(Personas.isActive(kaede, base + 12 * hour, 0))
        assertTrue(Personas.isActive(kaede, base + 12 * hour, 9 * 3600)) // 12:00 UTC = 21:00 JST
        assertEquals(21, Personas.localHour(base + 12 * hour, 9 * 3600))
        assertEquals(4, Personas.localHour(base - 1 * hour, 5 * 3600))
        assertTrue(Personas.startsAt(kaede, base + 20 * hour, 0))
        assertFalse(Personas.startsAt(kaede, base + 20 * hour + 60, 0))
    }

    @Test
    fun splitInvarianceAcrossTheMinuteGrid() {
        var s = fresh()
        s = Actions.startUpgrade(rich(s), TestSupport.slotOf(s, BuildingType.CASTLE), data).ok()
        s = s.copy(timers = s.timers.map { it.copy(helpRequested = true, endEpochSec = it.endEpochSec + 6 * hour) })
        val extra = (1L..30L).map { it * 60 + (it % 3) } + listOf(17L * 60 + 1, 3 * hour)
        SplitInvariance.assertInvariant(
            s,
            4 * hour,
            data,
            java.util.Random(5),
            randomCases = 40,
            extraBoundaries = extra,
        )
    }

    @Test
    fun twentyFourHourCatchUpIsFast() {
        val s = fresh()
        OfflineCatchUp.run(s, s.nowEpochSec + day, data) // warm-up
        val t0 = System.nanoTime()
        val r = OfflineCatchUp.run(s, s.nowEpochSec + day, data)
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.err.println("PERF alliance catch-up 24h = ${ms}ms")
        assertTrue(ms < 300, "took ${ms}ms")
        assertTrue(r.summary.chatsReceived > 0)
        assertEquals(r.state.alliance.chatQueuedTotal.toInt(), r.summary.chatsReceived)
    }

    @Test
    fun pendingQueueIsCappedAndDropsAmbientFirst() {
        val cap = data.allianceRules.caps.pendingChat
        var a = fresh().alliance
        a = queue(a, ChatEvent.PlayerHelped(1), 1)
        repeat(cap) {
            a = queue(a, ChatEvent.Ambient("t$it"), 2L + it)
        }
        assertEquals(cap, a.pendingChat.size)
        assertTrue(a.pendingChat.first().event is ChatEvent.PlayerHelped) // 重要なものは残り、最古の Ambient が落ちる
        assertEquals("t1", (a.pendingChat[1].event as ChatEvent.Ambient).topic)
        // Ambient が無ければ最古を捨てる
        var b = fresh().alliance
        repeat(cap + 3) {
            b = queue(b, ChatEvent.PlayerHelped(it), it.toLong())
        }
        assertEquals(cap, b.pendingChat.size)
        assertEquals(3, (b.pendingChat.first().event as ChatEvent.PlayerHelped).count)
        assertEquals((cap + 3).toLong(), b.chatQueuedTotal)
    }

    @Test
    fun dailyGreetingOncePerLocalDay() {
        var s = fresh()
        val greetings = ArrayList<Long>()
        repeat(3 * 24) {
            s = tick(s, hour).state
            greetings +=
                s.alliance.pendingChat.filter {
                    it.event is ChatEvent.DailyGreeting
                }.map { Personas.localDay(it.createdEpochSec, 0) }
            s = s.copy(alliance = s.alliance.copy(pendingChat = emptyList()))
        }
        assertEquals(greetings.distinct(), greetings)
        assertTrue(greetings.size >= 3)
    }

    @Test
    fun aiMembersHelpTimersThatAskedForHelp() {
        var s = fresh()
        s = Actions.startUpgrade(rich(s), TestSupport.slotOf(s, BuildingType.CASTLE), data).ok()
        val job = s.timers.single().copy(helpRequested = true, endEpochSec = s.nowEpochSec + 10 * hour)
        s = s.copy(timers = listOf(job))
        val r = tick(s, 6 * hour)
        val helps = r.events.filterIsInstance<GameEvent.HelpReceived>()
        val perTimer = data.allianceRules.help.minHelpsPerTimer
        assertTrue(helps.size in 1..perTimer, "helps=${helps.size}")
        assertTrue(helps.all { it.reducedSec >= data.allianceRules.help.minReductionSec })
        val updated = r.state.timers.single()
        assertEquals(helps.size, updated.helpCount)
        assertEquals(job.endEpochSec - helps.sumOf { it.reducedSec }, updated.endEpochSec)
        assertEquals(helps.size.toLong(), r.state.alliance.helpReceivedTotal)
        // 求めていないタイマーにはヘルプしない
        val quiet = tick(s.copy(timers = listOf(job.copy(helpRequested = false))), 6 * hour)
        assertTrue(quiet.events.none { it is GameEvent.HelpReceived })
    }

    @Test
    fun favorMilestoneFiresOncePerLevelAndGivesAGift() {
        val def = data.personas.persona("kaede")!!
        var s = fresh()
        val th = def.favorThresholds
        s = AllianceLogic.addFavor(s, "kaede", th[0] - 1, data)
        assertTrue(s.alliance.pendingChat.none { it.event is ChatEvent.FavorMilestone })
        s = AllianceLogic.addFavor(s, "kaede", 1, data)
        s = AllianceLogic.addFavor(s, "kaede", 1, data)
        val ms = s.alliance.pendingChat.filter { it.event is ChatEvent.FavorMilestone }
        assertEquals(1, ms.size)
        assertEquals(ChatKeys.dm("kaede"), ms.single().channel)
        assertEquals(1, s.alliance.gifts.size)
        assertEquals(1, s.alliance.favorLevel["kaede"])
        // 一気に 2 レベル上がる場合は 2 回
        s = AllianceLogic.addFavor(s, "kaede", th[2], data)
        assertEquals(3, s.alliance.pendingChat.count { it.event is ChatEvent.FavorMilestone })
        assertEquals(s, AllianceLogic.addFavor(s, "nobody", 5, data))
        assertEquals(s, AllianceLogic.addFavor(s, "kaede", 0, data))
    }

    @Test
    fun yukiReturnReferencesLoggedHelp() {
        var s = fresh()
        val log =
            (1..4).map { ActionLogEntry(s.nowEpochSec - hour * it, LogKind.HELPED_MEMBER, "yuki") } +
                ActionLogEntry(s.nowEpochSec - hour, LogKind.HELPED_MEMBER, "kaede")
        for (e in log) s = s.copy(alliance = AllianceLogic.log(s.alliance, e, data))
        assertEquals(4, AllianceLogic.logCount(s.alliance, LogKind.HELPED_MEMBER, 0, "yuki"))
        val seen = ArrayList<ChatEvent.YukiReturn>()
        repeat(14) {
            s = tick(s, day).state
            seen += s.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.YukiReturn>()
            s = s.copy(alliance = s.alliance.copy(pendingChat = emptyList()))
        }
        assertTrue(seen.isNotEmpty())
        assertEquals(4, seen.first().helpCount)
        // 行動ログは直近 N 件だけ
        var a = s.alliance
        repeat(80) { a = AllianceLogic.log(a, ActionLogEntry(it.toLong(), LogKind.SCOUTED), data) }
        assertEquals(data.allianceRules.caps.actionLog, a.actionLog.size)
    }

    @Test
    fun castleLevelCurveAndCallNames() {
        val kaede = data.personas.persona("kaede")!!
        assertEquals(22, Personas.castleLv(kaede, 0))
        assertEquals(27, Personas.castleLv(kaede, 30))
        assertEquals(24, Personas.castleLv(kaede, 12)) // 22 + 5*12/30
        assertEquals(30, Personas.castleLv(kaede, 1000))
        assertEquals(22, Personas.castleLv(kaede, -5))
        assertEquals("領主さん", Personas.callName(kaede, 0, "アレン", "領主さん"))
        assertEquals("領主さん", Personas.callName(kaede, 1, "アレン", "領主さん"))
        assertEquals("アレン", Personas.callName(kaede, 2, "アレン", "領主さん"))
        assertEquals("アレンはん", Personas.callName(kaede, 3, "アレン", "領主さん"))
        assertEquals("領主さん", Personas.callName(kaede, 3, "", "領主さん"))
        assertEquals(0, Personas.favorLevel(kaede, 0))
        assertEquals(kaede.favorThresholds.size, Personas.favorLevel(kaede, 1_000_000))
    }

    @Test
    fun brokenAllianceDataIsRejected() {
        val raw = ClasspathDataSource.readAll()

        fun reject(
            file: String,
            from: String,
            to: String,
        ) {
            val text = raw.getValue(file)
            assertTrue(text.contains(from), "pattern not found: $from")
            assertFailsWith<GameDataException> { GameDataLoader.parse(raw + (file to text.replaceFirst(from, to))) }
        }
        reject("personas.json", "\"id\": \"gen\"", "\"id\": \"kaede\"")
        reject("personas.json", "\"start\": 20,", "\"start\": 40,")
        reject("personas.json", "\"troopTier\": 3", "\"troopTier\": 9")
        reject("alliance_rules.json", "\"schedulerPeriodSec\": 60", "\"schedulerPeriodSec\": 30")
        reject("alliance_rules.json", "\"buff\": \"GATHER_SPEED\"", "\"buff\": \"NOPE\"")
        reject(
            "alliance_rules.json",
            "\"item\": \"speedup_general_5m\", \"count\": 1, \"cost\": 10",
            "\"item\": \"nope\", \"count\": 1, \"cost\": 10",
        )
        reject("alliance_rules.json", "\"hosts\": [\"marcus\", \"kaede\"]", "\"hosts\": [\"nobody\"]")
        reject("templates.json", "\"q_storage\"", "\"q_other\"")
    }

    @Test
    fun fillerMembersAndAlignmentWithMapSlots() {
        assertEquals(8, data.personas.personas.size)
        assertEquals(22, data.personas.fillerMembers.size)
        assertEquals(
            listOf(
                "kaede",
                "marcus",
                "ponzu",
                "sato",
                "lin",
                "johnny",
                "yuki",
                "gen",
            ),
            data.personas.personas.map {
                it.id
            },
        )
        assertEquals(data.mapRules.allies.personaSlots, data.personas.personas.size)
        assertEquals(data.mapRules.allies.fillers, data.personas.fillerMembers.size)
    }
}
