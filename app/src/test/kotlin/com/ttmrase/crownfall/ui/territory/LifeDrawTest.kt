package com.ttmrase.crownfall.ui.territory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeDrawTest {
    @Test
    fun hashIsDeterministicInRangeAndSpreadsOut() {
        var sum = 0f
        val seen = HashSet<Float>()
        for (a in -20 until 20) {
            for (b in 0 until 20) {
                val h = LifeDraw.hash01(a, b)
                assertEquals(h, LifeDraw.hash01(a, b), 0f)
                assertTrue(h >= 0f && h < 1f)
                sum += h
                seen += h
            }
        }
        // 平均はほぼ 0.5 で、値はばらけている（飾りの配置が片寄らない）
        assertEquals(0.5f, sum / 800f, 0.05f)
        assertTrue(seen.size > 700)
    }

    @Test
    fun fractWrapsNegativesIntoZeroToOne() {
        assertEquals(0.25f, LifeDraw.fract(3.25f), EPS)
        assertEquals(0.75f, LifeDraw.fract(-0.25f), EPS)
        assertEquals(0f, LifeDraw.fract(2f), EPS)
        for (i in -50..50) assertTrue(LifeDraw.fract(i * 0.37f) in 0f..1f)
    }

    @Test
    fun flickerStaysBrightEnoughAndNeverRepeatsExactly() {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var t = 0f
        while (t < 60f) {
            val f = LifeDraw.flicker(t, 1.3f)
            lo = minOf(lo, f)
            hi = maxOf(hi, f)
            t += 0.01f
        }
        assertTrue("min $lo", lo >= 0.45f)
        assertTrue("max $hi", hi <= 1.1f)
        assertTrue(hi - lo > 0.3f)
        // 位相が違えば、同じ時刻でも違う明るさ（たいまつがそろって明滅しない）
        assertTrue(LifeDraw.flicker(5f, 0f) != LifeDraw.flicker(5f, 2f))
    }

    @Test
    fun thereIsALookForEveryTownsfolkVariant() {
        assertEquals(TownLife.LOOKS, LifeDraw.TOWN_LOOKS.size)
    }

    private companion object {
        const val EPS = 0.0001f
    }
}
