package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Mail
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による M4 の境界テスト（襲撃・メール上限・全滅行軍・オフラインのシフト・R3）。 */
class QaWorldTest {
    private val data = TestSupport.data
    private val rules = data.mapRules
    private val day = 86_400L

    private fun tick(
        s: GameState,
        dt: Long,
    ) = Simulation.tick(s, dt, data)

    /** 城 Lv10・兵と資源あり・マップ初期化済み・襲撃は遠い未来。 */
    private fun base(): GameState {
        var s = TestSupport.withLevel(TestSupport.newGame(), BuildingType.CASTLE, 10)
        s = s.copy(troops = mapOf("infantry_t1" to 1000L, "infantry_t2" to 2000L, "archer_t2" to 2000L))
        s = tick(s, 0).state
        return s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
    }

    private fun raidSoon(s: GameState) =
        s.copy(map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + 1, raidWarned = true))

    private fun attack(
        s: GameState,
        barb: com.ttmrase.crownfall.core.map.Barbarian,
        troops: Map<String, Long>,
    ) = Actions.startMarch(s, MarchKind.ATTACK, TileRef(barb.x, barb.y), troops, emptyList(), data).ok()

    private fun gather(
        s: GameState,
        node: com.ttmrase.crownfall.core.map.ResourceNode,
        troops: Map<String, Long>,
    ) = Actions.startMarch(s, MarchKind.GATHER, TileRef(node.x, node.y), troops, emptyList(), data).ok()

    private fun dist(
        x: Int,
        y: Int,
    ) = MapQueries.distance(x, y, rules.center.x, rules.center.y)

    // ───── 襲撃 ─────

    @Test
    fun raidNeverCreatesTroopsOrNegativeStocksForAnyArmySize() {
        val sizes =
            listOf(10L, 1_000L, 1_000_000L, 1_000_000_000L, 1_000_000_000_000L, 1_000_000_000_000_000L)
        for (count in sizes + listOf(100_000_000_000_000_000L, Long.MAX_VALUE / 4)) {
            val start = raidSoon(base().copy(troops = mapOf("infantry_t1" to count), resources = RICH))
            val after = tick(start, 2).state
            val left = after.troops["infantry_t1"] ?: 0L
            assertTrue(left in 0..count, "count=$count left=$left")
            assertTrue(after.wounded.values.all { it >= 0 }, "count=$count wounded=${after.wounded}")
            assertTrue(after.wounded.values.sum() <= count, "count=$count wounded=${after.wounded}")
            for (t in ResourceType.entries) assertTrue(after.resources[t] in 0..RICH[t], "count=$count $t")
            assertEquals(1, after.mails.count { it.battle != null }, "count=$count one raid mail")
        }
    }

    @Test
    fun raidConservesTroopsAcrossRemainingDeadWoundedAndOverflow() {
        for (hospital in listOf(0, 1, 5)) {
            for (count in listOf(50L, 400L, 3000L)) {
                var s = base().copy(troops = mapOf("infantry_t1" to count), resources = RICH)
                if (hospital > 0) s = TestSupport.withLevel(s, BuildingType.HOSPITAL, hospital)
                val after = tick(raidSoon(s), 2).state
                val report = after.mails.single { it.battle != null }.battle!!
                val remaining = after.troops["infantry_t1"] ?: 0L
                val dead = report.defenderDead["infantry_t1"] ?: 0L
                val woundedIn = after.wounded["infantry_t1"] ?: 0L
                val overflow = report.overflowDead["infantry_t1"] ?: 0L
                assertEquals(count, remaining + dead + woundedIn + overflow, "hospital=$hospital count=$count")
            }
        }
    }

    @Test
    fun raidLootIsBoundedByExposedResourcesAndNeverNegative() {
        val s0 = base()
        val tiny = Resources(1, 2, 3, 4)
        for (res in listOf(Resources.ZERO, tiny, RICH, Resources(Long.MAX_VALUE, Long.MAX_VALUE, 0, 0))) {
            val s = raidSoon(s0.copy(troops = emptyMap(), resources = res))
            val after = tick(s, 2).state
            for (t in ResourceType.entries) assertTrue(after.resources[t] in 0..res[t], "res=$res $t")
            val loot = after.mails.single { it.battle != null }.battle!!.loot
            for (t in ResourceType.entries) assertEquals(res[t] - after.resources[t], loot[t], "loot=$res $t")
        }
    }

    // ───── メール上限 ─────

    private fun mailsOf(
        s: GameState,
        n: Int,
        read: (Int) -> Boolean,
    ): GameState {
        var cur = s
        val events = ArrayList<GameEvent>()
        repeat(n) { i ->
            cur = MailBox.add(cur, Mail(0, MailKind.SYSTEM, 1000L + i, read = read(i), systemCode = "t$i"), events)
        }
        return cur
    }

    @Test
    fun mailBoxKeepsAtMostTwoHundredAndDropsReadOnesFirst() {
        // 偶数番は既読。250 通入れると、既読の古いものから捨てられる
        val s = mailsOf(base(), 250) { it % 2 == 0 }
        assertEquals(MailBox.CAP, s.mails.size)
        assertEquals(s.mails.map { it.id }, s.mails.map { it.id }.sorted(), "oldest first")
        // 新しい順に最後の 1 通は必ず残る
        assertEquals("t249", s.mails.last().systemCode)
        // 未読は、既読が尽きるまで 1 通も捨てられない
        val unreadKept = s.mails.count { !it.read }
        assertEquals(125, unreadKept)
    }

    @Test
    fun mailBoxWithAllUnreadDropsTheOldestAndKeepsTheNewest() {
        val s = mailsOf(base(), 230) { false }
        assertEquals(MailBox.CAP, s.mails.size)
        assertEquals("t229", s.mails.last().systemCode)
        assertEquals("t30", s.mails.first().systemCode)
        assertEquals(230L, s.nextMailId - 1)
    }

    @Test
    fun markReadIsIdempotentAndUnreadCountIsConsistent() {
        var s = mailsOf(base(), 5) { false }
        assertEquals(5, MailBox.unread(s))
        val id = s.mails[2].id
        s = Actions.markMailRead(s, id).ok()
        assertEquals(4, MailBox.unread(s))
        assertEquals(s, Actions.markMailRead(s, id).ok())
        assertEquals(s, Actions.markMailRead(s, 9_999).ok())
        s = Actions.markAllMailRead(s).ok()
        assertEquals(0, MailBox.unread(s))
        assertEquals(s, Actions.markAllMailRead(s).ok())
    }

    // ───── 全滅した行軍 ─────

    @Test
    fun wipedMarchSettlesWoundedAndNeverReturnsTroops() {
        val s0 = base().copy(troops = mapOf("infantry_t1" to 5L))
        val s = TestSupport.withLevel(s0, BuildingType.HOSPITAL, 3)
        val barb = s.map.barbarians.maxByOrNull { it.level }!!
        val sent = attack(s, barb, mapOf("infantry_t1" to 5L))
        assertTrue(sent.troops.isEmpty())
        val end = sent.marches.single().phaseEndEpochSec
        val arrived = tick(sent, end - sent.nowEpochSec)
        assertTrue(arrived.state.marches.isEmpty(), "wiped march is removed on arrival")
        assertTrue((arrived.state.troops["infantry_t1"] ?: 0L) == 0L, "no troops come back")
        val mail = arrived.state.mails.single { it.battle != null }.battle!!
        assertEquals(false, mail.attackerWon)
        val back = tick(arrived.state, 10 * day).state
        assertEquals(arrived.state.troops, back.troops, "nothing returns later")
        assertEquals(1, arrived.events.count { it is GameEvent.MarchReturned })
        // 5 体は 死亡 + 負傷（病院に入った分）+ 溢れ死に で全員が説明される
        val dead = mail.attackerDead["infantry_t1"] ?: 0L
        val wounded = arrived.state.wounded["infantry_t1"] ?: 0L
        val overflow = mail.overflowDead["infantry_t1"] ?: 0L
        assertEquals(5L, dead + wounded + overflow)
    }

    // ───── OfflineCatchUp のシフト ─────

    @Test
    fun catchUpPastTheCapShiftsMarchesRefreshAndRaidByTheSkippedTime() {
        val s0 = base()
        val barb = s0.map.barbarians.minBy { dist(it.x, it.y) }
        val sent = attack(s0, barb, mapOf("infantry_t1" to 100L))
        val long =
            sent.copy(
                marches =
                    sent.marches.map {
                        it.copy(
                            phaseEndEpochSec = s0.nowEpochSec + 3 * day,
                            travelSec = 3 * day,
                        )
                    },
                map =
                    sent.map.copy(
                        nextRefreshEpochSec = s0.nowEpochSec + 4 * day,
                        nextRaidEpochSec = s0.nowEpochSec + 4 * day,
                        raidWarned = false,
                    ),
            )
        val r = OfflineCatchUp.run(long, s0.nowEpochSec + 5 * day, data)
        val now = r.state.nowEpochSec
        assertEquals(s0.nowEpochSec + 5 * day, now)
        assertEquals(2 * day, r.state.marches.single().phaseEndEpochSec - now)
        assertEquals(3 * day, r.state.map.nextRaidEpochSec - now)
        assertEquals(3 * day, r.state.map.nextRefreshEpochSec - now)
        assertTrue(r.state.mails.none { it.battle != null }, "no raid happened inside the skipped time")
    }

    @Test
    fun catchUpOfADayEqualsOneBigTickWithRaidsAndMarches() {
        var s = base()
        val barb = s.map.barbarians.minBy { dist(it.x, it.y) }
        s = attack(s, barb, mapOf("infantry_t2" to 500L))
        val node = s.map.nodes.minBy { dist(it.x, it.y) }
        s = gather(s, node, mapOf("archer_t2" to 400L))
        s = s.copy(map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + 3 * 3600))
        val whole = tick(s, day)
        val caught = OfflineCatchUp.run(s, s.nowEpochSec + day, data)
        assertEquals(whole.state, caught.state)
        assertEquals(whole.events, caught.events)
        assertTrue(whole.state.mails.any { it.battle != null }, "scenario must include battles")
    }

    // ───── R3 ─────

    @Test
    fun randomSplitsWithRaidsMarchesAndRefreshAreInvariant() {
        val rnd = java.util.Random(404)
        repeat(6) { n ->
            var s = base()
            val barb = s.map.barbarians.minBy { dist(it.x, it.y) }
            s = attack(s, barb, mapOf("infantry_t2" to 300L + n))
            val node = s.map.nodes.filter { it.type == ResourceType.WOOD }.minBy { dist(it.x, it.y) }
            s = gather(s, node, mapOf("archer_t2" to 200L))
            s = TestSupport.withLevel(s, BuildingType.WATCHTOWER, 1 + n % 4)
            val raidAt = s.nowEpochSec + 600L + rnd.nextInt(20_000)
            s = s.copy(map = s.map.copy(nextRaidEpochSec = raidAt, raidWarned = false))
            val extra = s.marches.map { it.phaseEndEpochSec - s.nowEpochSec } + (s.map.nextRaidEpochSec - s.nowEpochSec)
            SplitInvariance.assertInvariant(s, 90_000, data, rnd, randomCases = 40, extraBoundaries = extra)
        }
    }
}
