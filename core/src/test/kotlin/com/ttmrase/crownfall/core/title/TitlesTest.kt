package com.ttmrase.crownfall.core.title

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TitlesTest {
    private val data = TestSupport.data

    @Test
    fun aNewGameOwnsOnlyTheFirstTitle() {
        val s = TestSupport.newGame()
        assertEquals(listOf("villager"), Titles.owned(s, data))
        assertEquals("villager", Titles.displayed(s, data))
    }

    @Test
    fun titlesFollowTheCastleLevelAndTheBestOneIsShownByDefault() {
        val s = TestSupport.newGame(castle = 10)
        val owned = Titles.owned(s, data)
        assertTrue(owned.containsAll(listOf("villager", "landlord", "knight", "baron", "duke")))
        assertEquals("duke", Titles.displayed(s, data))
    }

    @Test
    fun theChosenTitleIsShownAndAnUnownedOneIsRefused() {
        val s = TestSupport.newGame(castle = 5)
        val chosen = Actions.selectTitle(s, "landlord", data).ok()
        assertEquals("landlord", Titles.displayed(chosen, data))
        val refused = Actions.selectTitle(s, "king", data)
        assertEquals(ActionFailure.NotOwned, (refused as ActionResult.Failure).reason)
        assertEquals("knight", Titles.displayed(Actions.selectTitle(chosen, null, data).ok(), data))
    }

    @Test
    fun personaTitlesExistAndAreNotEarnableByThePlayer() {
        val ids = data.titles.titles.associateBy { it.id }
        data.personas.personas.forEach { p ->
            assertTrue(p.title.isNotEmpty(), "${p.id} has a title")
            assertNull(ids.getValue(p.title).cond, "${p.id}'s title is persona-only")
        }
        assertEquals(data.personas.personas.size, data.personas.personas.map { it.title }.toSet().size)
    }

    @Test
    fun aStaleSelectionFallsBackToTheBestOwned() {
        val s = TestSupport.newGame().copy(selectedTitle = "emperor")
        assertEquals("villager", Titles.displayed(s, data))
    }
}
