package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AvatarTest {
    @Test
    fun defaultsToZeroAndCanBeChosenWithinRange() {
        val s = TestSupport.newGame()
        assertEquals(0, s.avatar)
        assertEquals(11, Actions.setAvatar(s, 11).ok().avatar)
    }

    @Test
    fun outOfRangeIsRejectedAndLeavesTheStateAlone() {
        val s = TestSupport.newGame()
        assertIs<ActionResult.Failure>(Actions.setAvatar(s, -1))
        assertIs<ActionResult.Failure>(Actions.setAvatar(s, Actions.AVATAR_COUNT))
    }
}
