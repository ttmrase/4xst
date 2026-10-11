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
    fun roadsStartAtTheCastleAvoidWaterAndAreStable() {
        val roads = MapArt.roads(world)
        assertEquals(roads, MapArt.roads(world))
        val castle = world.structures.first { it.type == StructureType.PLAYER_CASTLE }
        assertTrue(roads.isNotEmpty())
        for (line in roads) {
            assertEquals(castle.x + 0.5f, line.first().x, 0f)
            assertEquals(castle.y + 0.5f, line.first().y, 0f)
            for (p in line) assertTrue(world.terrainAt(p.x.toInt(), p.y.toInt()) != Terrain.WATER)
        }
    }

    @Test
    fun dayNightIdsRoundTrip() {
        DayNight.entries.forEach { assertEquals(it, DayNight.of(it.id)) }
        assertEquals(DayNight.AUTO, DayNight.of(99))
    }
}
