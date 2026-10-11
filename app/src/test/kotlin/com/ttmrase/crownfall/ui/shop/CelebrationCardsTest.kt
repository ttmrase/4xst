package com.ttmrase.crownfall.ui.shop

import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CelebrationCardsTest {
    @Test
    fun cardsListGemsThenResourcesThenItemsThenShards() {
        val contents =
            PackContents(
                gems = 300,
                resources = Resources.ZERO.with(ResourceType.FOOD, 1000).with(ResourceType.GOLD, 50),
                items = mapOf("speedup_1h" to 2, "shield" to 1),
                heroShards = mapOf("sora" to 5),
            )
        val cards = celebrationCards(contents)
        assertEquals(listOf("💎"), cards.take(1).map { it.glyph })
        assertEquals(5, cards.size)
        assertEquals("3", cards[cards.size - 2].amount)
        assertEquals("5", cards.last().amount)
    }

    @Test
    fun emptyPackHasNoCardsAndTheListIsCapped() {
        assertTrue(celebrationCards(PackContents()).isEmpty())
        val all =
            PackContents(
                gems = 1,
                resources = Resources(1, 1, 1, 1),
                items = mapOf("a" to 1),
                heroShards = mapOf("b" to 1),
            )
        assertEquals(6, celebrationCards(all).size)
    }
}
