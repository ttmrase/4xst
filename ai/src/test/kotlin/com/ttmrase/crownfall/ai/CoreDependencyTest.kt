package com.ttmrase.crownfall.ai

import com.ttmrase.crownfall.core.model.Resources
import kotlin.test.Test
import kotlin.test.assertEquals

class CoreDependencyTest {
    @Test
    fun aiCanUseCoreTypes() {
        assertEquals(Resources(3, 3, 3, 3), Resources(1, 1, 1, 1) + Resources(2, 2, 2, 2))
    }
}
