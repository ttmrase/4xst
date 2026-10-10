package com.ttmrase.crownfall.ai

import kotlin.test.Test
import kotlin.test.assertEquals

class AiBuildInfoTest {
    @Test
    fun referencesCoreType() {
        val info = aiBuildInfo()
        assertEquals("crownfall-ai", info.name)
        assertEquals(1, info.saveVersion)
    }
}
