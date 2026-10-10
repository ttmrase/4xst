package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.ok
import kotlin.test.Test
import kotlin.test.assertEquals

class VipCelebratedTest {
    @Test
    fun startsUninitialisedAndNeverGoesBack() {
        val s = TestSupport.newGame()
        assertEquals(-1, s.shop.vipCelebrated)
        val a = Actions.markVipCelebrated(s, 5).ok()
        assertEquals(5, a.shop.vipCelebrated)
        val b = Actions.markVipCelebrated(a, 3).ok()
        assertEquals(5, b.shop.vipCelebrated)
    }
}
