package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockedSlotsTest {
    private val data = TestSupport.data

    @Test
    fun aNewGameHasNoLockedSlotBecauseEveryLevelOnePrerequisiteIsCastleOne() {
        val s = TestSupport.newGame()
        assertTrue(UiQueries.lockedSlots(s, data).isEmpty())
    }

    @Test
    fun withoutACastleEveryUnbuiltSlotIsLockedByCastleLevelOne() {
        val s = TestSupport.withLevel(TestSupport.newGame(), BuildingType.CASTLE, 0)
        val locked = UiQueries.lockedSlots(s, data)
        val unbuilt = s.slots.filter { it.level == 0 && it.type != BuildingType.CASTLE }
        assertTrue(unbuilt.isNotEmpty())
        for (slot in unbuilt) {
            val need = locked[slot.slotId]
            assertEquals(BuildingType.CASTLE, need?.type, "slot ${slot.slotId}")
            assertEquals(1, need?.level)
        }
    }

    @Test
    fun builtSlotsAreNeverLocked() {
        val s = TestSupport.withLevel(TestSupport.newGame(), BuildingType.FARM, 3)
        val farm = s.slots.first { it.type == BuildingType.FARM && it.level > 0 }
        assertTrue(farm.slotId !in UiQueries.lockedSlots(s, data))
    }
}
