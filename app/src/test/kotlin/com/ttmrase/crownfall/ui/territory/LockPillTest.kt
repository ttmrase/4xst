package com.ttmrase.crownfall.ui.territory

import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.Prereq
import org.junit.Assert.assertEquals
import org.junit.Test

class LockPillTest {
    @Test
    fun onlyTheNextCastleLevelPlotsGetALabel() {
        val locks =
            mapOf(
                1 to Prereq(BuildingType.CASTLE, 3),
                2 to Prereq(BuildingType.CASTLE, 3),
                3 to Prereq(BuildingType.CASTLE, 5),
                4 to Prereq(BuildingType.CASTLE, 9),
            )
        assertEquals(setOf(1, 2), lockPillSlots(locks))
    }

    @Test
    fun buildingPrereqLabelsShowOnlyWhenNoCastleGatesRemain() {
        val barracksOnly = mapOf(7 to Prereq(BuildingType.BARRACKS, 3))
        assertEquals(setOf(7), lockPillSlots(barracksOnly))
        assertEquals(setOf(1), lockPillSlots(barracksOnly + (1 to Prereq(BuildingType.CASTLE, 2))))
        assertEquals(emptySet<Int>(), lockPillSlots(emptyMap()))
    }
}
