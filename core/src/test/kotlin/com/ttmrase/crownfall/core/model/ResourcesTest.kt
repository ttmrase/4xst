package com.ttmrase.crownfall.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourcesTest {
    private val r = Resources(10, 20, 30, 40)

    @Test
    fun getAndWith() {
        assertEquals(10, r[ResourceType.FOOD])
        assertEquals(40, r[ResourceType.GOLD])
        ResourceType.entries.forEach { assertEquals(7, r.with(it, 7)[it]) }
        assertEquals(20, r.with(ResourceType.FOOD, 7)[ResourceType.WOOD])
    }

    @Test
    fun plusSaturatesAtLongMax() {
        val big = Resources(Long.MAX_VALUE - 1, 0, 0, 0)
        assertEquals(Long.MAX_VALUE, (big + Resources(5, 0, 0, 0)).food)
        assertEquals(Resources(11, 22, 33, 44), r + Resources(1, 2, 3, 4))
    }

    @Test
    fun timesSaturates() {
        assertEquals(Resources(30, 60, 90, 120), r * 3)
        assertEquals(Long.MAX_VALUE, (Resources(Long.MAX_VALUE / 2 + 1, 0, 0, 0) * 2).food)
        assertEquals(0, (r * 0).food)
    }

    @Test
    fun minusOrNullFailsWhenShort() {
        assertEquals(Resources(5, 5, 5, 5), r.minusOrNull(Resources(5, 15, 25, 35)))
        assertNull(r.minusOrNull(Resources(11, 0, 0, 0)))
        assertNull(r.minusOrNull(Resources(0, 0, 0, 41)))
    }

    @Test
    fun missingFromIsExactAndNonNegative() {
        val cost = Resources(100, 5, 50, 0)
        assertEquals(Resources(90, 0, 20, 0), cost.missingFrom(r))
        assertTrue(Resources.ZERO.isZero)
        assertTrue(Resources(1, 2, 3, 4).missingFrom(Resources(10, 10, 10, 10)).isZero)
    }

    @Test
    fun saturatingHelpers() {
        assertEquals(Long.MAX_VALUE, satAdd(Long.MAX_VALUE, 1))
        assertEquals(Long.MIN_VALUE, satAdd(Long.MIN_VALUE, -1))
        assertEquals(Long.MIN_VALUE, satMul(Long.MAX_VALUE, -2))
        assertEquals(-6, satMul(2, -3))
        assertEquals(Long.MAX_VALUE, satMul(Long.MIN_VALUE, -1))
    }
}
