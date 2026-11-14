package com.ttmrase.crownfall.ui.territory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    /** [hz] の画面で [seconds] 秒ぶんのフレームを流して、描いた回数を数える。 */
    private fun drawn(
        pacer: FramePacer,
        hz: Int,
        seconds: Int = 1,
        startNanos: Long = 0L,
    ): Int {
        var count = 0
        for (i in 0 until hz * seconds) if (pacer.accept(startNanos + i * NANOS_PER_SEC / hz)) count++
        return count
    }

    @Test
    fun everyFrameIsDrawnAt60And90HzAndEveryOtherOneAt120Hz() {
        assertEquals(60, drawn(FramePacer(), 60))
        assertEquals(90, drawn(FramePacer(), 90))
        assertEquals(60, drawn(FramePacer(), 120))
        assertEquals(72, drawn(FramePacer(), 144))
    }

    @Test
    fun aHeavyCanvasFallsBackToAnEvenThirtyFpsAndRecoversWithHysteresis() {
        val pacer = FramePacer()
        repeat(200) { pacer.reportCost(9 * NANOS_PER_MS) }
        assertTrue(pacer.slowed)
        assertEquals(30, drawn(pacer, 60))
        assertEquals(30, drawn(FramePacer().also { p -> repeat(200) { p.reportCost(9 * NANOS_PER_MS) } }, 120))
        // しきい値のあいだ（3.5〜7ms）では行ったり来たりしない
        repeat(200) { pacer.reportCost(5 * NANOS_PER_MS) }
        assertTrue(pacer.slowed)
        repeat(200) { pacer.reportCost(2 * NANOS_PER_MS) }
        assertFalse(pacer.slowed)
        assertEquals(60, drawn(pacer, 60, startNanos = 10 * NANOS_PER_SEC))
    }

    @Test
    fun aSingleSlowFrameDoesNotSlowThePacing() {
        val pacer = FramePacer()
        repeat(100) { pacer.reportCost(2 * NANOS_PER_MS) }
        pacer.reportCost(40 * NANOS_PER_MS)
        assertFalse(pacer.slowed)
        assertTrue(pacer.averageCostMs < FramePacer.COST_HIGH_MS)
    }

    /** [gapsMs] の間隔を順に繰り返しながら [frames] 回の合図を流す。最後の合図の時刻を返す。 */
    private fun feed(
        pacer: FramePacer,
        startNanos: Long,
        frames: Int,
        vararg gapsMs: Float,
    ): Long {
        var now = startNanos
        for (i in 0 until frames) {
            now += (gapsMs[i % gapsMs.size] * NANOS_PER_MS).toLong()
            pacer.accept(now)
        }
        return now
    }

    @Test
    fun droppedFramesSlowThePacingEvenWhenRecordingIsCheap() {
        val pacer = FramePacer()
        // 60Hz で間に合っている間は落とさない
        var now = feed(pacer, 0L, 600, FRAME_60)
        assertFalse(pacer.slowed)
        // 4 回に 1 回だけ落ちる程度では落とさない
        now = feed(pacer, now, 600, FRAME_60, FRAME_60, FRAME_60, 2 * FRAME_60)
        assertFalse(pacer.slowed)
        // 1 つおきに落ちる状態が続いたら、等間隔の 30fps に落とす（記録の時間は短いまま）
        repeat(50) { pacer.reportCost(1 * NANOS_PER_MS) }
        now = feed(pacer, now, 120, FRAME_60, 2 * FRAME_60)
        assertTrue(pacer.slowed)
        assertEquals(30, drawn(pacer, 60, startNanos = now + NANOS_PER_SEC / 60))
    }

    @Test
    fun afterAWhileFullSpeedIsTriedAgainAndTheWaitDoublesIfItDropsAgain() {
        val pacer = FramePacer()
        var now = feed(pacer, 0L, 200, FRAME_60)
        now = feed(pacer, now, 120, FRAME_60, 2 * FRAME_60)
        assertTrue(pacer.slowed)
        // 20 秒たつまでは落としたまま。たったら試し直す
        now = feed(pacer, now, 60 * 15, FRAME_60)
        assertTrue(pacer.slowed)
        now = feed(pacer, now, 60 * 6, FRAME_60)
        assertFalse(pacer.slowed)
        // すぐまた落ちたら、次に試すのは 40 秒後
        now = feed(pacer, now, 240, FRAME_60, 2 * FRAME_60)
        assertTrue(pacer.slowed)
        now = feed(pacer, now, 60 * 30, FRAME_60)
        assertTrue(pacer.slowed)
        now = feed(pacer, now, 60 * 12, FRAME_60)
        assertFalse(pacer.slowed)
        // そのあと間に合い続ければ、落とさない
        feed(pacer, now, 60 * 30, FRAME_60)
        assertFalse(pacer.slowed)
    }

    @Test
    fun aSingleHitchAPauseOrAHighRefreshRateDoesNotSlowThePacing() {
        val pacer = FramePacer()
        var now = feed(pacer, 0L, 300, FRAME_60)
        // 1 回の引っかかり（120ms）
        now = feed(pacer, now, 1, 120f)
        now = feed(pacer, now, 60, FRAME_60)
        assertFalse(pacer.slowed)
        // 画面が隠れていた（3 秒）あと、開き直した直後の乱れ
        now = feed(pacer, now, 1, 3000f)
        now = feed(pacer, now, 40, FRAME_60, 2 * FRAME_60)
        assertFalse(pacer.slowed)
        // 120Hz: 合図は 8.3ms ごとに来て、描くのは 1 つおき。落ちてはいない
        val fast = FramePacer()
        feed(fast, 0L, 1200, FRAME_120)
        assertFalse(fast.slowed)
        // 120Hz から 60Hz に下がっても、やがて 60Hz を基準にし直す（落としっぱなしにならない）
        var t = feed(fast, 1200L * 8_333_333L, 60 * 120, FRAME_60)
        t = feed(fast, t, 60 * 30, FRAME_60)
        assertFalse(fast.slowed)
    }

    @Test
    fun aClockThatGoesBackwardsStillDraws() {
        val pacer = FramePacer()
        assertTrue(pacer.accept(5 * NANOS_PER_SEC))
        assertTrue(pacer.accept(1 * NANOS_PER_SEC))
    }

    @Test
    fun theAmbientClockWrapsInsteadOfLosingPrecision() {
        // 起動から 30 日後でも、1 フレーム（16ms）の差が Float で表せる
        val uptimeMs = 30L * 24 * 3600 * 1000
        val a = ambientSeconds(uptimeMs)
        val b = ambientSeconds(uptimeMs + 16)
        assertTrue(a in 0f..3600f)
        assertEquals(0.016f, b - a, 0.001f)
    }

    private companion object {
        const val NANOS_PER_SEC = 1_000_000_000L
        const val NANOS_PER_MS = 1_000_000L
        const val FRAME_60 = 16.667f
        const val FRAME_120 = 8.333f
    }
}
