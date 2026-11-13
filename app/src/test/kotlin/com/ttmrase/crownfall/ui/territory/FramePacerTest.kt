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
    }
}
