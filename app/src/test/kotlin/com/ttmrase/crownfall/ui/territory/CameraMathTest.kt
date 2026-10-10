package com.ttmrase.crownfall.ui.territory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMathTest {
    private val w = 1080f
    private val h = 1600f

    @Test
    fun scaleBoundsAreOrdered() {
        assertTrue(CameraMath.minScale(w) < CameraMath.maxScale(w))
    }

    @Test
    fun aOneFingerPanDoesNotThrowAndMovesTheCamera() {
        val cam = CameraMath.initial(w, 0f, 60f)
        val out = CameraMath.transform(cam, w, h, 500f, 800f, panX = 12f, panY = -8f, zoom = 1f)
        assertEquals(cam.sc, out.sc, 1e-6f)
        assertTrue(out.cx != cam.cx || out.cy != cam.cy)
    }

    @Test
    fun zoomIsClampedToTheLimitsInBothDirections() {
        val cam = CameraMath.initial(w, 0f, 60f)
        val zoomedIn = CameraMath.transform(cam, w, h, 540f, 800f, 0f, 0f, zoom = 100f)
        assertEquals(CameraMath.maxScale(w), zoomedIn.sc, 1e-4f)
        val zoomedOut = CameraMath.transform(cam, w, h, 540f, 800f, 0f, 0f, zoom = 0.001f)
        assertEquals(CameraMath.minScale(w), zoomedOut.sc, 1e-4f)
    }

    @Test
    fun theViewCentreNeverLeavesTheTerritoryWhateverTheDirectionOfThePan() {
        val half = 20f
        val cam = CameraMath.initial(w, 0f, 60f)
        for ((px, py) in listOf(-1e7f to -1e7f, 1e7f to 1e7f, -1e7f to 1e7f, 1e7f to -1e7f, 0f to 1e7f, 1e7f to 0f)) {
            val out = CameraMath.transform(cam, w, h, 540f, 800f, px, py, 1f, limitHalf = half)
            val gx = (out.cx / PlotLayout.U + out.cy / PlotLayout.V) / 2f
            val gy = (out.cy / PlotLayout.V - out.cx / PlotLayout.U) / 2f
            assertTrue("gx=$gx", gx in PlotLayout.MID - half - 1e-3f..PlotLayout.MID + half + 1e-3f)
            assertTrue("gy=$gy", gy in PlotLayout.MID - half - 1e-3f..PlotLayout.MID + half + 1e-3f)
        }
    }

    @Test
    fun aCentreInsideTheTerritoryIsLeftAlone() {
        val c =
            CamState(PlotLayout.wx(PlotLayout.MID, PlotLayout.MID), PlotLayout.wy(PlotLayout.MID, PlotLayout.MID), 1.2f)
        assertEquals(c, CameraMath.clampToTerritory(c, 20f))
    }

    @Test
    fun theWorldPointUnderTheFingerStaysPutWhileZooming() {
        val cam = CamState(40f, 90f, 1.5f)
        val fx = 700f
        val fy = 500f
        val before = 40f + (fx - w / 2f) / 1.5f
        val out = CameraMath.transform(cam, w, h, fx, fy, 0f, 0f, zoom = 1.4f)
        val after = out.cx + (fx - w / 2f) / out.sc
        assertEquals(before, after, 1e-3f)
    }

    @Test
    fun anEmptyViewIsIgnored() {
        val cam = CamState(1f, 2f, 3f)
        assertEquals(cam, CameraMath.transform(cam, 0f, 0f, 0f, 0f, 5f, 5f, 2f))
    }
}
