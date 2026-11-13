package com.ttmrase.crownfall.ui.map

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.March
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.MarchViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MarchTrackTest {
    private val data = ClasspathDataSource.load()
    private val center = data.mapRules.center
    private val march =
        March(
            id = 1L,
            kind = MarchKind.GATHER,
            troops = emptyMap(),
            target = TileRef(center.x + 6, center.y - 8),
            phase = MarchPhase.OUTBOUND,
            phaseStartEpochSec = START,
            phaseEndEpochSec = START + TRAVEL,
            travelSec = TRAVEL,
        )
    private val marches = listOf(march)

    @Test
    fun atTheTickThePositionIsExactlyTheCorePosition() {
        val track = MarchTrack()
        track.update(marches, START + 10, 5_000L, data, animate = true)
        val (x, y) = MarchViews.position(march, START + 10, data)
        assertEquals(x, track.x(0), EPS)
        assertEquals(y, track.y(0), EPS)
    }

    @Test
    fun betweenTicksItGlidesTowardsTheNextSecondWithoutJumping() {
        val track = MarchTrack()
        val (ax, ay) = MarchViews.position(march, START + 10, data)
        val (bx, by) = MarchViews.position(march, START + 11, data)
        track.update(marches, START + 10, 5_000L, data, animate = true)
        track.update(marches, START + 10, 5_500L, data, animate = true)
        assertEquals((ax + bx) / 2f, track.x(0), EPS)
        assertEquals((ay + by) / 2f, track.y(0), EPS)
        // 1 秒たって時計が進む瞬間も、位置はつながっている
        track.update(marches, START + 10, 5_990L, data, animate = true)
        val beforeX = track.x(0)
        val beforeY = track.y(0)
        track.update(marches, START + 11, 6_000L, data, animate = true)
        assertTrue(hypot(track.x(0) - beforeX, track.y(0) - beforeY) < hypot(bx - ax, by - ay) * 0.05f)
        assertEquals(bx, track.x(0), EPS)
        // 時計が遅れても、次の秒の位置より先へは行かない
        track.update(marches, START + 11, 9_000L, data, animate = true)
        val (cx, cy) = MarchViews.position(march, START + 12, data)
        assertEquals(cx, track.x(0), EPS)
        assertEquals(cy, track.y(0), EPS)
    }

    @Test
    fun withAnimationsDisabledItStaysOnTheCorePosition() {
        val track = MarchTrack()
        track.update(marches, START + 10, 5_000L, data, animate = false)
        track.update(marches, START + 10, 5_700L, data, animate = false)
        val (x, y) = MarchViews.position(march, START + 10, data)
        assertEquals(x, track.x(0), EPS)
        assertEquals(y, track.y(0), EPS)
    }

    @Test
    fun aChangedMarchListIsPickedUpInTheSameSecond() {
        val track = MarchTrack()
        track.update(marches, START + 10, 5_000L, data, animate = true)
        val back = march.copy(phase = MarchPhase.WORKING)
        track.update(listOf(march, back), START + 10, 5_200L, data, animate = true)
        assertEquals(back.target.x.toFloat(), track.x(1), EPS)
        assertEquals(back.target.y.toFloat(), track.y(1), EPS)
    }

    private companion object {
        const val START = 1_000_000L
        const val TRAVEL = 100L
        const val EPS = 0.001f
    }
}
