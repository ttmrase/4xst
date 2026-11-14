package com.ttmrase.crownfall.ui.territory

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.sim.NewGame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class GroundPlanTest {
    private val cells = PlotLayout.cellsFor(NewGame.create(ClasspathDataSource.load(), 1_000_000L, 42L).slots)
    private val plan = GroundPlan.of(cells)
    private val mid = PlotLayout.MID
    private val bands = 1..PlotLayout.BANDS

    private companion object {
        /** 色むらの下絵の、枠の縁の濃さの上限（255 分の 3。見えない）。 */
        const val EDGE_ALPHA = 3
    }

    private fun cheb(
        gx: Float,
        gy: Float,
    ): Float = max(abs(gx - mid), abs(gy - mid))

    @Test
    fun noiseIsDeterministicAndInRange() {
        for (i in 0 until 200) {
            val x = i * 0.37f - 20f
            val y = i * 0.11f - 7f
            val a = GroundNoise.value(x, y, 5)
            assertEquals(a, GroundNoise.value(x, y, 5), 0f)
            assertTrue(a in 0f..1f)
            assertTrue(GroundNoise.cloud(x, y, 5) in 0f..1f)
        }
        // 種が違えば別の模様
        assertTrue((0 until 50).any { GroundNoise.value(it * 0.7f, 3.3f, 1) != GroundNoise.value(it * 0.7f, 3.3f, 2) })
    }

    @Test
    fun theLawnCoversEverythingInsideTheWallAndFadesIntoTheForest() {
        for (band in bands) {
            val half = PlotLayout.WALL_HALF[band - 1]
            val core = plan.lawnCoreHalf(band)
            assertTrue(core > half)
            var t = -core
            while (t <= core) {
                // 草地が必ず覆う正方形の縁（森の床を描かない範囲の境目）は、どこも濃さ 1
                for ((gx, gy) in listOf(mid + t to mid - core, mid + t to mid + core, mid - core to mid + t)) {
                    assertEquals("band $band at $gx, $gy", 1f, plan.lawn(gx, gy, band), 0.001f)
                }
                t += 0.37f
            }
            assertEquals(1f, plan.lawn(mid, mid, band), 0f)
            // 下絵の枠の外では、もう草地ではない
            val out = plan.frameHalf(band)
            assertEquals(0f, plan.lawn(mid + out, mid, band), 0f)
            assertEquals(0f, plan.lawn(mid - out, mid + out, band), 0f)
        }
    }

    @Test
    fun streetsAreDirtInTheHamletAndPavedAsTheCastleGrows() {
        // 井戸を通る大通り（y = 4.5）の、区画の間の点と、ふつうの通り（y = -0.5）の点
        val avenue = 7f to TownLife.streetCoord(1)
        val street = 7f to TownLife.streetCoord(0)
        assertTrue(plan.earth(avenue.first, avenue.second, 1) > 0.9f)
        assertEquals(0f, plan.stone(avenue.first, avenue.second, 1), 0f)
        assertEquals(0f, plan.stone(street.first, street.second, 1), 0f)
        assertTrue(plan.stone(avenue.first, avenue.second, 2) > 0.9f)
        assertEquals(0f, plan.stone(street.first, street.second, 2), 0f)
        for (band in 3..PlotLayout.BANDS) {
            assertTrue(plan.stone(avenue.first, avenue.second, band) > 0.9f)
            assertTrue(plan.stone(street.first, street.second, band) > 0.9f)
            // 石畳の下には、少し広い土の床がある
            assertTrue(plan.earth(street.first, street.second, band) > 0.9f)
        }
        // 区画の真ん中には、道も石畳も無い（建物の土台が乗る）
        val (cx, cy) = PlotLayout.centerOf(1 to 2)
        for (band in bands) assertEquals(0f, plan.stone(cx, cy, band), 0f)
    }

    @Test
    fun lanesOutsideThePlotBlockStayUnpavedInEveryBand() {
        // 区画の並びの外（x = 24.5 の通り）は踏み分け道。どの帯でも石で敷かない（芝の上に格子が浮いて見えるため）
        val x = TownLife.streetCoord(5)
        for (band in 3..PlotLayout.BANDS) {
            var y = 9f
            while (y < 14f) {
                assertTrue("band $band y $y", plan.stone(x, y, band) < 0.02f)
                y += 0.5f
            }
        }
    }

    @Test
    fun everyPlotStandsOnAnApronOfEarth() {
        for ((_, cell, _) in cells) {
            val (x0, y0) = PlotLayout.footprintOrigin(cell)
            for (band in bands) {
                assertTrue("plot $cell band $band", plan.earth(x0 - 0.1f, y0 + 2f, band) > 0.5f)
                assertTrue("plot $cell band $band", plan.earth(x0 + 2f, y0 + 4.1f, band) > 0.5f)
            }
        }
    }

    @Test
    fun aPlazaSurroundsTheCastleEarthFirstThenStone() {
        val castle = cells.first { it.third == com.ttmrase.crownfall.core.model.BuildingType.CASTLE }.second
        val (x0, y0) = PlotLayout.footprintOrigin(castle)
        val gx = x0 - 0.35f
        val gy = y0 + 2f
        assertTrue(plan.earth(gx, gy, 1) > 0.9f)
        assertEquals(0f, plan.stone(gx, gy, 1), 0f)
        for (band in 2..PlotLayout.BANDS) assertTrue(plan.stone(gx, gy, band) > 0.9f)
        // 井戸（噴水）のまわりも広場
        for (band in 2..PlotLayout.BANDS) assertTrue(plan.stone(TownLife.PLAZA + 1f, TownLife.PLAZA, band) > 0.9f)
    }

    @Test
    fun aRoadLeavesThroughTheGateAndFadesIntoTheForest() {
        for (band in bands) {
            val gate = mid + PlotLayout.WALL_HALF[band - 1]
            // 門の 2 単位外。道はゆるく蛇行するので、横に少し探す
            var best = 0f
            var side = -1f
            while (side <= 1f) {
                best = max(best, plan.earth(gate + 2f + side, gate + 2f - side, band))
                side += 0.1f
            }
            assertTrue("band $band", best > 0.8f)
            // 遠くでは消えている
            assertEquals(0f, plan.earth(gate + 9f, gate + 9f, band), 0f)
        }
    }

    @Test
    fun theForestFloorEndsFarOutAndTheRiverHasBanks() {
        assertEquals(1f, plan.forest(mid, mid), 0f)
        assertEquals(1f, plan.forest(mid + 40f, mid - 40f), 0f)
        assertEquals(0f, plan.forest(mid + 64f, mid), 0f)
        for (gy in listOf(-20f, 0f, 30f)) {
            val x = TerritoryScene.riverX(gy)
            assertEquals(1f, plan.water(x, gy), 0f)
            assertEquals(0f, plan.water(x + 4f, gy), 0f)
            // 川岸は水面より広い
            assertTrue(plan.water(x + 2.45f, gy) < 0.05f)
            assertTrue(plan.bank(x + 2.45f, gy) > 0.5f)
            assertEquals(0f, plan.bank(x + 5f, gy), 0f)
        }
        // 川の両端は消える
        assertEquals(0f, plan.water(TerritoryScene.riverX(TerritoryScene.RIVER_FROM), TerritoryScene.RIVER_FROM), 0.01f)
    }

    @Test
    fun bakedSheetsAgreeWithThePointFunctions() {
        val hamlet = plan.bake(1)
        assertNull(hamlet.stone)
        val town = plan.bake(3)
        val stone = town.stone
        assertNotNull(stone)
        val frame = town.earth.frame
        assertEquals(frame.w, frame.h)
        assertEquals(frame.w * frame.h, town.earth.data.size)
        val tolerance = 1.5f / 255f
        var px = 3
        while (px < frame.w) {
            var py = 5
            while (py < frame.h) {
                val gx = frame.gx(px)
                val gy = frame.gy(py)
                assertEquals(plan.earth(gx, gy, 3), town.earth.at(gx, gy), tolerance)
                assertEquals(plan.stone(gx, gy, 3), stone!!.at(gx, gy), tolerance)
                py += 37
            }
            px += 41
        }
        val lawn = town.lawn
        assertEquals(plan.lawn(mid + 20.3f, mid - 7.1f, 3), lawn.at(mid + 20.3f, mid - 7.1f), 0.2f)
        assertEquals(1f, lawn.at(mid, mid), 0f)
        assertEquals(0f, lawn.at(mid + 500f, mid), 0f)
        // 色むらの下絵は、枠の縁で透明に消える（四角い継ぎ目を出さない）
        val shade = town.shade
        for (i in 0 until shade.frame.w) assertTrue(shade.data[i] ushr 24 <= EDGE_ALPHA)
        assertTrue(shade.data.any { it ushr 24 > 20 })
    }

    @Test
    fun bakingIsDeterministic() {
        val a = plan.bake(2)
        val b = GroundPlan.of(cells).bake(2)
        assertArrayEquals(a.earth.data, b.earth.data)
        assertArrayEquals(a.lawn.data, b.lawn.data)
        assertArrayEquals(a.shade.data, b.shade.data)
    }

    @Test
    fun decalsStayOnTheGrassInsideTheirOwnWall() {
        val d = plan.decals()
        val again = plan.decals()
        assertEquals(d.count, again.count)
        assertArrayEquals(d.x.copyOf(d.count), again.x.copyOf(d.count), 0f)
        assertTrue("only ${d.count} decals", d.count > 100)
        var flowers = 0
        for (i in 0 until d.count) {
            val band = d.band[i].toInt()
            val kind = d.kind[i].toInt()
            assertTrue(band in bands)
            assertTrue(kind in GroundPlan.DECAL_DAISY..GroundPlan.DECAL_PEBBLES)
            assertTrue(cheb(d.x[i], d.y[i]) < PlotLayout.WALL_HALF[band - 1])
            if (kind <= GroundPlan.DECAL_BLUEBELL) flowers++
            if (kind == GroundPlan.DECAL_PEBBLES) continue
            // 花と草むらは、現れた帯より後のどの帯でも、道・広場・エプロンにかからない
            for (b in band..PlotLayout.BANDS) {
                assertTrue(plan.earth(d.x[i], d.y[i], b) < 0.1f)
                assertTrue(plan.stone(d.x[i], d.y[i], b) < 0.1f)
            }
        }
        assertTrue(flowers > 10)
    }

    @Test
    fun coarserTextureLevelsAreUsedWhenZoomedOut() {
        // 草のテクスチャ（512px が 12 単位）: 拡大では等倍、初期ズームあたりで 1/2、いちばん引くと 1/4
        val ppu = 512f / 12f
        assertEquals(0, GroundPlan.textureLevel(ppu, 3f))
        assertEquals(1, GroundPlan.textureLevel(ppu, 1.2f))
        assertEquals(2, GroundPlan.textureLevel(ppu, 0.4f))
        var last = 0
        var sc = 4f
        while (sc > 0.2f) {
            val level = GroundPlan.textureLevel(ppu, sc)
            assertTrue(level >= last && level < GroundPlan.TEXTURE_LEVELS)
            last = level
            sc -= 0.1f
        }
        assertEquals(0, GroundPlan.textureLevelFor(32f, 96f))
        assertEquals(GroundPlan.TEXTURE_LEVELS - 1, GroundPlan.textureLevelFor(512f, 12f))
    }
}
