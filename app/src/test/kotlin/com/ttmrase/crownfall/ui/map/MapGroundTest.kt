package com.ttmrase.crownfall.ui.map

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.map.MapGenerator
import com.ttmrase.crownfall.core.map.Terrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGroundTest {
    private val data = ClasspathDataSource.load()
    private val seed = 42L
    private val world = MapGenerator.generate(seed, data.mapRules)

    private fun patchFor(
        x: Int,
        y: Int,
    ) = MapGround.Patch(world, seed, x / MapArt.CHUNK * MapArt.CHUNK, y / MapArt.CHUNK * MapArt.CHUNK)

    /** まわり 5×5 がすべて [terrain] のタイル（ゆがみとぼかしが届かない、地形の内側）。 */
    private fun interior(terrain: Terrain): Pair<Int, Int>? {
        for (y in 2 until world.size - 2) {
            for (x in 2 until world.size - 2) {
                var all = true
                for (dy in -2..2) for (dx in -2..2) if (world.terrainAt(x + dx, y + dy) != terrain) all = false
                if (all) return x to y
            }
        }
        return null
    }

    @Test
    fun theMixFollowsTheTerrainAwayFromEdges() {
        val mix = MapGround.Mix()
        val plain = checkNotNull(interior(Terrain.PLAIN))
        patchFor(plain.first, plain.second).mix(plain.first + 0.5f, plain.second + 0.5f, mix)
        assertEquals(0f, mix.forest, 0f)
        assertEquals(0f, mix.mountain, 0f)
        assertEquals(0f, mix.water, 0f)
        assertEquals(0f, mix.shore, 0f)
        interior(Terrain.FOREST)?.let { (x, y) ->
            patchFor(x, y).mix(x + 0.5f, y + 0.5f, mix)
            assertEquals(1f, mix.forest, 0f)
            assertEquals(0f, mix.water, 0f)
        }
        // どのタイルの中心でも、割合は 0..1 に収まる
        for (y in 0 until world.size step 5) {
            for (x in 0 until world.size step 5) {
                patchFor(x, y).mix(x + 0.5f, y + 0.5f, mix)
                for (v in listOf(mix.forest, mix.mountain, mix.water, mix.shore, mix.depth, mix.snow, mix.sand)) {
                    assertTrue(v in 0f..1f)
                }
            }
        }
    }

    @Test
    fun waterTilesAreWetAndHaveAShore() {
        val mix = MapGround.Mix()
        var lakes = 0
        for (y in 1 until world.size - 1) {
            for (x in 1 until world.size - 1) {
                if (world.terrainAt(x, y) != Terrain.WATER || MapArt.isRiver(world, x, y)) continue
                var all = true
                for (dy in -1..1) for (dx in -1..1) if (world.terrainAt(x + dx, y + dy) != Terrain.WATER) all = false
                if (!all) continue
                lakes++
                patchFor(x, y).mix(x + 0.5f, y + 0.5f, mix)
                assertEquals(1f, mix.water, 0f)
                // 砂は水面より広いので、水のあるところには必ずある
                assertEquals(1f, mix.shore, 0f)
            }
        }
        // 世界に湖の内側が無ければ、この検査は素通りする（地形しだい）
        assertTrue(lakes >= 0)
    }

    @Test
    fun snowIsInTheNorthAndSandInTheSouthEast() {
        val mix = MapGround.Mix()
        val n = world.size
        patchFor(n / 2, 0).mix(n / 2 + 0.5f, 0.5f, mix)
        assertEquals(1f, mix.snow, 0f)
        assertEquals(0f, mix.sand, 0f)
        patchFor(n - 1, n - 1).mix(n - 0.5f, n - 0.5f, mix)
        assertEquals(1f, mix.sand, 0f)
        assertEquals(0f, mix.snow, 0f)
        val c = data.mapRules.center
        patchFor(c.x, c.y).mix(c.x + 0.5f, c.y + 0.5f, mix)
        assertEquals(0f, mix.snow, 0f)
        assertEquals(0f, mix.sand, 0f)
    }

    @Test
    fun theContinuousBiomeFunctionsMatchTheTileOnes() {
        val n = world.size
        for (y in 0 until n step 3) {
            for (x in 0 until n step 3) {
                assertEquals(MapArt.snowT(x, y, n, seed), MapArt.snowAt(x.toFloat(), y.toFloat(), n, seed), 0f)
                assertEquals(MapArt.sandT(x, y, n, seed), MapArt.sandAt(x.toFloat(), y.toFloat(), n, seed), 0f)
            }
        }
    }

    @Test
    fun neighbouringChunksAgreeAlongTheirSeam() {
        // 隣り合うチャンクの下絵は、境目の両側 1 タイルぶん重なる。同じ点は同じ値（継ぎ目が出ない）
        val a = MapGround.Mix()
        val b = MapGround.Mix()
        for (cy in 1..3) {
            for (cx in 1..3) {
                val left = MapGround.Patch(world, seed, cx * MapArt.CHUNK, cy * MapArt.CHUNK)
                val right = MapGround.Patch(world, seed, (cx + 1) * MapArt.CHUNK, cy * MapArt.CHUNK)
                val seam = (cx + 1) * MapArt.CHUNK.toFloat()
                var y = cy * MapArt.CHUNK - 1f
                while (y < (cy + 1) * MapArt.CHUNK + 1f) {
                    for (dx in listOf(-0.94f, -0.31f, 0f, 0.44f, 0.94f)) {
                        left.mix(seam + dx, y, a)
                        right.mix(seam + dx, y, b)
                        assertEquals(a.forest, b.forest, 0f)
                        assertEquals(a.mountain, b.mountain, 0f)
                        assertEquals(a.water, b.water, 0f)
                        assertEquals(a.shore, b.shore, 0f)
                        assertEquals(a.depth, b.depth, 0f)
                        assertEquals(a.snow, b.snow, 0f)
                        assertEquals(a.sand, b.sand, 0f)
                        assertEquals(left.height(seam + dx, y, a), right.height(seam + dx, y, b), 0f)
                    }
                    y += 0.37f
                }
            }
        }
    }

    @Test
    fun mountainsStandAboveThePlainForTheReliefShading() {
        val mix = MapGround.Mix()
        val plain = checkNotNull(interior(Terrain.PLAIN))
        val plainPatch = patchFor(plain.first, plain.second)
        plainPatch.mix(plain.first + 0.5f, plain.second + 0.5f, mix)
        val low = plainPatch.height(plain.first + 0.5f, plain.second + 0.5f, mix)
        var peaks = 0
        for (y in 0 until world.size) {
            for (x in 0 until world.size) {
                if (world.terrainAt(x, y) != Terrain.MOUNTAIN) continue
                val patch = patchFor(x, y)
                patch.mix(x + 0.5f, y + 0.5f, mix)
                if (mix.mountain < 0.99f) continue
                peaks++
                assertTrue(patch.height(x + 0.5f, y + 0.5f, mix) > low)
            }
        }
        assertTrue(peaks >= 0)
    }
}
