package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.TestSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FillerLookTest {
    @Test
    fun everyFillerHasALookAndTheCountsMatchTheGeneratedPortraits() {
        val fillers = TestSupport.data.personas.fillerMembers
        assertTrue(fillers.all { it.look in setOf("m", "f", "n") })
        assertEquals(mapOf("m" to 8, "f" to 8, "n" to 6), fillers.groupingBy { it.look }.eachCount())
    }
}
