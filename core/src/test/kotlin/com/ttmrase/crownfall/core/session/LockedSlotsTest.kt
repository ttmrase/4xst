package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockedSlotsTest {
    private val data = TestSupport.data

    @Test
    fun aNewGameLocksExactlyTheBuildingsTheUnlockTableHoldsBack() {
        val s = TestSupport.newGame()
        val locked = UiQueries.lockedSlots(s, data)
        val gated = s.slots.filter { it.level == 0 && data.building(it.type).level(1).prereqs.single().level > 1 }
        assertTrue(gated.isNotEmpty())
        assertEquals(gated.map { it.slotId }.toSet(), locked.keys)
        for (slot in gated) assertEquals(BuildingType.CASTLE, locked.getValue(slot.slotId).type)
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
            assertEquals(data.building(slot.type).level(1).prereqs.single().level, need?.level)
        }
    }

    @Test
    fun builtSlotsAreNeverLocked() {
        val s = TestSupport.withLevel(TestSupport.newGame(), BuildingType.FARM, 3)
        val farm = s.slots.first { it.type == BuildingType.FARM && it.level > 0 }
        assertTrue(farm.slotId !in UiQueries.lockedSlots(s, data))
    }
}
