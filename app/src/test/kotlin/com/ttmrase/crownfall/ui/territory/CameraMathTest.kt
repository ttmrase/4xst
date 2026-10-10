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
        val cam = CameraMath.initial(w, h, 0f, 60f)
        val out = CameraMath.transform(cam, w, h, 500f, 800f, panX = 12f, panY = -8f, zoom = 1f)
        assertEquals(cam.sc, out.sc, 1e-6f)
        assertTrue(out.cx != cam.cx || out.cy != cam.cy)
    }

    @Test
    fun zoomIsClampedToTheLimitsInBothDirections() {
        val cam = CameraMath.initial(w, h, 0f, 60f)
        val zoomedIn = CameraMath.transform(cam, w, h, 540f, 800f, 0f, 0f, zoom = 100f)
        assertEquals(CameraMath.maxScale(w), zoomedIn.sc, 1e-4f)
        val zoomedOut = CameraMath.transform(cam, w, h, 540f, 800f, 0f, 0f, zoom = 0.001f)
        assertEquals(CameraMath.minScale(w), zoomedOut.sc, 1e-4f)
    }

    @Test
    fun theVisibleAreaNeverLeavesTheTerritoryBoundingBoxByMoreThanTheMargin() {
        val half = 32f
        val cam = CameraMath.initial(w, h, 0f, 60f)
        for ((px, py) in listOf(-1e7f to -1e7f, 1e7f to 1e7f, -1e7f to 1e7f, 1e7f to -1e7f, 0f to 1e7f, 1e7f to 0f)) {
            val out = CameraMath.transform(cam, w, h, 540f, 800f, px, py, 1f, limitHalf = half)
            val mid = PlotLayout.MID
            val minX = PlotLayout.wx(mid - half, mid + half) - CameraMath.EDGE_MARGIN
            val maxX = PlotLayout.wx(mid + half, mid - half) + CameraMath.EDGE_MARGIN
            val minY = PlotLayout.wy(mid - half, mid - half) - CameraMath.EDGE_MARGIN
            val maxY = PlotLayout.wy(mid + half, mid + half) + CameraMath.EDGE_MARGIN
            val hw = w / (2f * out.sc)
            val hh = h / (2f * out.sc)
            assertTrue("left ${out.cx - hw}", out.cx - hw >= minX - 1e-2f)
            assertTrue("right ${out.cx + hw}", out.cx + hw <= maxX + 1e-2f)
            assertTrue("top ${out.cy - hh}", out.cy - hh >= minY - 1e-2f)
            assertTrue("bottom ${out.cy + hh}", out.cy + hh <= maxY + 1e-2f)
        }
    }

    @Test
    fun aTerritorySmallerThanTheViewStaysWhereTheInitialCameraPutsIt() {
        val cam = CameraMath.initial(w, h, PlotLayout.INITIAL_CENTER_X, PlotLayout.INITIAL_CENTER_Y)
        val out = CameraMath.clampToTerritory(cam, PlotLayout.WALL_HALF[0], w, h)
        assertEquals(cam.cx, out.cx, 1e-3f)
        assertEquals(cam.cy, out.cy, 1e-3f)
    }

    @Test
    fun theInitialCameraCentresTheTerritoryInTheAreaBelowTheHud() {
        val cam = CameraMath.initial(w, h, PlotLayout.INITIAL_CENTER_X, PlotLayout.INITIAL_CENTER_Y)
        val screenY = h / 2f + (PlotLayout.INITIAL_CENTER_Y - cam.cy) * cam.sc
        assertEquals((h + h * CameraMath.HUD_FRACTION) / 2f, screenY, 1e-2f)
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
