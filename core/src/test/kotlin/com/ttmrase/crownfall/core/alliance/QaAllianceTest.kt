package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Settings
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.save.SaveGame
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による M5 のスケジューラ境界テスト（分グリッドの R3、24h catch-up、長期放置の上限）。 */
class QaAllianceTest {
    private val data = TestSupport.data
    private val hour = 3600L
    private val day = 86_400L

    /** 現地時刻が違っても同じ性質が成り立つことを見るため、UTC オフセットを変えて作る。 */
    private fun base(
        offsetSec: Int,
        now: Long = 20_000L * day + 3 * hour + 17,
    ): GameState {
        val s = TestSupport.advanced(level = 10, castle = 10, now = now)
        return Simulation.tick(
            s.copy(rngState = 11, resources = RICH, settings = Settings(offsetSec)),
            0,
            data,
        ).state
    }

    private val offsets = listOf(0, 9 * 3600, -5 * 3600, 14 * 3600, -12 * 3600)

    @Test
    fun minuteGridIsSplitInvariantInEveryTimezone() {
        val rnd = java.util.Random(77)
        for (offset in offsets) {
            val s = base(offset)
            val toGrid = 60L - Math.floorMod(s.nowEpochSec, 60L)
            // 最初の 25 分は毎分の境界 ±1 秒で切り、その先はランダムに切る
            val grid = (0 until 25).map { toGrid + 60L * it }
            SplitInvariance.assertInvariant(s, 3 * hour, data, rnd, randomCases = 25, extraBoundaries = grid)
        }
    }

    @Test
    fun schedulerEvaluatesEachMinuteOnceEvenWhenTickedOneSecondAtATime() {
        // 21 時台は活動中のペルソナが多く、確実にチャットが出る
        val s = base(0, now = 20_000L * day + 21 * hour + 17)
        val whole = Simulation.tick(s, 30 * 60L, data)
        var cur = s
        val events = ArrayList<com.ttmrase.crownfall.core.model.GameEvent>()
        repeat(30 * 60) {
            val r = Simulation.tick(cur, 1, data)
            cur = r.state
            events.addAll(r.events)
        }
        assertEquals(whole.state, cur)
        assertEquals(whole.events, events)
        assertTrue(whole.state.alliance.chatQueuedTotal > 0, "scenario must produce chat")
    }

    @Test
    fun twentyFourHourCatchUpEqualsOneBigTickInEveryTimezone() {
        for (offset in offsets) {
            val s = base(offset)
            val whole = Simulation.tick(s, day, data)
            val caught = OfflineCatchUp.run(s, s.nowEpochSec + day, data)
            assertEquals(whole.state, caught.state, "offset=$offset")
            assertEquals(whole.events, caught.events, "offset=$offset")
        }
    }

    @Test
    fun twentyFourHourCatchUpIsFast() {
        val s = base(9 * 3600)
        // ウォームアップ（JIT）
        OfflineCatchUp.run(s, s.nowEpochSec + day, data)
        val times =
            List(3) {
                val t0 = System.nanoTime()
                OfflineCatchUp.run(s, s.nowEpochSec + day, data)
                (System.nanoTime() - t0) / 1_000_000
            }
        println("QA 24h catch-up (1440 evaluations) ms: $times")
        // docs/02 §6 の目標は 300ms。CI のばらつきを見て 3 倍まで許容し、実測は上のログに残す
        assertTrue(times.min() < 900, "24h catch-up took $times ms")
    }

    @Test
    fun longNeglectKeepsEveryCollectionWithinItsCapAndTheSaveSmall() {
        val caps = data.allianceRules.caps
        for (offset in listOf(0, 9 * 3600)) {
            val s = base(offset)
            val a = Simulation.tick(s, 14 * day, data).state.alliance
            assertTrue(a.pendingChat.size <= caps.pendingChat, "pendingChat=${a.pendingChat.size}")
            assertTrue(a.actionLog.size <= caps.actionLog, "actionLog=${a.actionLog.size}")
            assertTrue(a.requests.size <= caps.openRequests, "requests=${a.requests.size}")
            assertTrue(a.rallies.size <= caps.openRallies, "rallies=${a.rallies.size}")
            assertTrue(a.helpPool.size <= caps.helpPool, "helpPool=${a.helpPool.size}")
            assertTrue(a.gifts.size <= caps.gifts, "gifts=${a.gifts.size}")
            assertTrue(
                a.history.values.all { it.size <= caps.history },
                "history=${a.history.mapValues { it.value.size }}",
            )
            val long = Simulation.tick(s, 14 * day, data).state
            val bytes = SaveCodec.encode(SaveGame(savedAtEpochSec = long.nowEpochSec, seed = 1, state = long)).length
            assertTrue(bytes < 2 * 1024 * 1024, "save is $bytes bytes")
        }
    }
}
