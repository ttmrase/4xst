package com.ttmrase.crownfall.ui

import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.ui.kit.Glyph
import org.junit.Assert.assertEquals
import org.junit.Test

/** 兵種・資源のアイコンの対応表を固定する（1.1.0 で騎馬と弓兵が逆になっていた回帰）。 */
class GlyphTest {
    @Test
    fun everyTroopTypeHasItsOwnGlyph() {
        val expected =
            mapOf(
                TroopType.INFANTRY to Glyph.INFANTRY,
                TroopType.CAVALRY to Glyph.CAVALRY,
                TroopType.ARCHER to Glyph.ARCHER,
                TroopType.SIEGE to Glyph.SIEGE,
            )
        assertEquals("the table must cover every TroopType", TroopType.entries.toSet(), expected.keys)
        for (type in TroopType.entries) assertEquals(type.name, expected.getValue(type), type.glyph())
        assertEquals("glyphs are distinct", TroopType.entries.size, TroopType.entries.map { it.glyph() }.toSet().size)
    }

    @Test
    fun everyResourceTypeHasItsOwnGlyph() {
        val expected =
            mapOf(
                ResourceType.FOOD to Glyph.FOOD,
                ResourceType.WOOD to Glyph.WOOD,
                ResourceType.STONE to Glyph.STONE,
                ResourceType.GOLD to Glyph.GOLD,
            )
        assertEquals(ResourceType.entries.toSet(), expected.keys)
        for (type in ResourceType.entries) assertEquals(type.name, expected.getValue(type), type.glyph())
    }
}
