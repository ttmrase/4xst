package com.ttmrase.crownfall.ui.map

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.map.MapGenerator
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.Terrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapArtTest {
    private val data = ClasspathDataSource.load()
    private val world = MapGenerator.generate(42L, data.mapRules)

    @Test
    fun noiseIsDeterministicAndInRange() {
        for (i in 0 until 50) {
            val a = MapArt.noise(i * 0.37f, i * 0.11f, 7L)
            assertEquals(a, MapArt.noise(i * 0.37f, i * 0.11f, 7L), 0f)
            assertTrue(a in 0f..1f)
        }
    }

    @Test
    fun roadsFormAConnectedNetworkFromTheCastleAvoidWaterAndAreStable() {
        val roads = MapArt.roads(world)
        assertEquals(roads, MapArt.roads(world))
        assertTrue(roads.isNotEmpty())
        val castle = world.structures.first { it.type == StructureType.PLAYER_CASTLE }
        val centers = world.structures.associateBy { (it.x + 0.5f) to (it.y + 0.5f) }
        // 水の上を通らない
        for (line in roads) for (p in line) assertTrue(world.terrainAt(p.x.toInt(), p.y.toInt()) != Terrain.WATER)
        // 両端は拠点の中心。道でつながった拠点の過半は自城と同じ網につながっている（最小全域木が土台）
        val parent = HashMap<Pair<Float, Float>, Pair<Float, Float>>()

        fun find(k: Pair<Float, Float>): Pair<Float, Float> {
            var r = k
            while (parent.getOrPut(r) { r } != r) r = parent.getValue(r)
            return r
        }
        for (line in roads) {
            val a = line.first().x to line.first().y
            val b = line.last().x to line.last().y
            assertTrue(a in centers && b in centers)
            parent[find(a)] = find(b)
        }
        val root = find((castle.x + 0.5f) to (castle.y + 0.5f))
        val keys = parent.keys.toList()
        assertTrue(keys.count { find(it) == root } * 2 >= keys.size)
    }

    @Test
    fun dayNightIdsRoundTrip() {
        DayNight.entries.forEach { assertEquals(it, DayNight.of(it.id)) }
        assertEquals(DayNight.AUTO, DayNight.of(99))
    }

    @Test
    fun chunkResolutionFollowsTheOnScreenTileSizeWithinTwoTimesUpscale() {
        assertEquals(12, MapChunkCache.resolutionFor(10f))
        assertEquals(32, MapChunkCache.resolutionFor(40f))
        assertEquals(64, MapChunkCache.resolutionFor(100f))
        assertEquals(96, MapChunkCache.resolutionFor(180f))
        // 上限を超えて拡大しても段階は増えない
        assertEquals(96, MapChunkCache.resolutionFor(900f))
        MapChunkCache.LEVELS.forEach { px ->
            assertTrue(MapChunkCache.resolutionFor(px * 2f) <= px.coerceAtLeast(MapChunkCache.LEVELS.last()))
        }
    }

    @Test
    fun snowIsInTheNorthSandInTheSouthEastAndTheCastleSurroundingsAreClear() {
        val n = world.size
        assertTrue(MapArt.snowT(n / 2, 0, n, 42L) > 0.9f)
        assertTrue(MapArt.sandT(n - 1, n - 1, n, 42L) > 0.9f)
        val c = data.mapRules.center
        assertEquals(0f, MapArt.snowT(c.x, c.y, n, 42L), 0f)
        assertEquals(0f, MapArt.sandT(c.x, c.y, n, 42L), 0f)
        // 雪と砂は重ならない
        for (i in 0 until n step 7) {
            for (j in 0 until n step 7) {
                assertTrue(MapArt.snowT(i, j, n, 42L) == 0f || MapArt.sandT(i, j, n, 42L) == 0f)
            }
        }
    }

    @Test
    fun narrowWaterIsARiverAndLakesAreNot() {
        val n = world.size
        var rivers = 0
        var lakeInteriors = 0
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (world.terrainAt(x, y) != Terrain.WATER) continue
                if (MapArt.isRiver(world, x, y)) rivers++ else lakeInteriors++
            }
        }
        // 判定は決定論（二度呼んでも同じ）で、水が少しでもあれば分類される
        assertEquals(rivers + lakeInteriors, (0 until n * n).count { world.terrainAt(it % n, it / n) == Terrain.WATER })
        assertEquals(rivers, (0 until n * n).count { MapArt.isRiver(world, it % n, it / n) })
    }
}
