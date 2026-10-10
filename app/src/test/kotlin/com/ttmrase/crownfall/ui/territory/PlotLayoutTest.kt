package com.ttmrase.crownfall.ui.territory

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.sim.NewGame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotLayoutTest {
    private val cells = PlotLayout.cellsFor(NewGame.create(ClasspathDataSource.load(), 1_000_000L, 42L).slots)

    @Test
    fun everySlotHasItsOwnCell() {
        assertTrue(cells.size == 27)
        assertTrue(cells.map { it.second }.toSet().size == cells.size)
    }

    @Test
    fun theFirstWallEnclosesEveryPlotFootprint() {
        val half = PlotLayout.WALL_HALF[0]
        val mid = PlotLayout.MID
        for ((_, cell, _) in cells) {
            val (x0, y0) = PlotLayout.footprintOrigin(cell)
            for (v in listOf(x0, x0 + 4f)) assertTrue("x $v", v > mid - half + 1f && v < mid + half - 1f)
            for (v in listOf(y0, y0 + 4f)) assertTrue("y $v", v > mid - half + 1f && v < mid + half - 1f)
        }
    }

    @Test
    fun noWallLineCrossesAFootprintInAnyBand() {
        val mid = PlotLayout.MID
        for (half in PlotLayout.WALL_HALF) {
            for (edge in listOf(mid - half, mid + half)) {
                for ((_, cell, _) in cells) {
                    val (x0, y0) = PlotLayout.footprintOrigin(cell)
                    val crossesX = edge >= x0 - 0.6f && edge <= x0 + 4.6f
                    val crossesY = edge >= y0 - 0.6f && edge <= y0 + 4.6f
                    assertTrue("wall $edge crosses plot $cell", !crossesX && !crossesY)
                }
            }
        }
    }

    @Test
    fun wallsGrowOutwardAndSignsNameTheirOwnLevel() {
        for (i in 1 until PlotLayout.WALL_HALF.size) assertTrue(PlotLayout.WALL_HALF[i] > PlotLayout.WALL_HALF[i - 1])
        assertTrue(PlotLayout.nextBandCastleLevel(1) == 6)
        assertTrue(PlotLayout.nextBandCastleLevel(PlotLayout.BANDS) == null)
    }
}
