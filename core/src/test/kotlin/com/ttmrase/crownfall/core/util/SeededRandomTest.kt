package com.ttmrase.crownfall.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SeededRandomTest {
    @Test
    fun matchesSplitMix64ReferenceVectors() {
        val r = SeededRandom(0)
        assertEquals(-0x1ddf57c684e23251L, r.nextLong()) // 0xE220A8397B1DCDAF
        assertEquals(0x6E789E6AA1B965F4L, r.nextLong())
        assertEquals(0x06C45D188009454FL, r.nextLong())
    }

    @Test
    fun sameSeedSameSequenceAndStateIsResumable() {
        val a = SeededRandom(123)
        val b = SeededRandom(123)
        repeat(100) { assertEquals(a.nextLong(), b.nextLong()) }
        val resumed = SeededRandom(a.state)
        assertEquals(a.nextLong(), resumed.nextLong())
    }

    @Test
    fun nextIntStaysInBound() {
        val r = SeededRandom(7)
        repeat(1000) { assertTrue(r.nextInt(10) in 0 until 10) }
        assertFailsWith<IllegalArgumentException> { r.nextInt(0) }
    }

    @Test
    fun clocks() {
        val c = FixedClock(100)
        c.advance(5)
        assertEquals(105, c.nowEpochSec())
        assertTrue(SystemClock.nowEpochSec() > 1_700_000_000)
    }
}
