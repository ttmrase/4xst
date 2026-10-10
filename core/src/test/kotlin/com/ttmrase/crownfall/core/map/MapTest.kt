package com.ttmrase.crownfall.core.map

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.data.GameDataException
import com.ttmrase.crownfall.core.data.GameDataLoader
import com.ttmrase.crownfall.core.model.ResourceType
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapTest {
    private companion object {
        const val FAR = 60
        const val NEAR = 2
    }

    private val data = TestSupport.data
    private val rules = data.mapRules
    private val world = MapGenerator.generate(12345, rules)

    private fun dist(s: Structure) = MapQueries.distance(s.x, s.y, rules.center.x, rules.center.y)

    @Test
    fun sameSeedGivesTheSameWorld() {
        val a = MapGenerator.generate(777, rules)
        val b = MapGenerator.generate(777, rules)
        assertEquals(a.structures, b.structures)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a.terrainAt(x, y), b.terrainAt(x, y))
        val other = MapGenerator.generate(778, rules)
        assertNotEquals(a.structures, other.structures)
    }

    @Test
    fun cachedWorldIsTheSameInstanceAndEqualToAFreshOne() {
        val c1 = MapGenerator.cached(42, rules)
        val c2 = MapGenerator.cached(42, rules)
        assertSame(c1, c2)
        assertEquals(MapGenerator.generate(42, rules).structures, c1.structures)
        MapGenerator.cached(43, rules)
        MapGenerator.cached(44, rules) // 直近 2 件までなので 42 は捨てられる
        assertEquals(c1.structures, MapGenerator.cached(42, rules).structures)
    }

    @Test
    fun playerCastleIsAtTheCenter() {
        val castle = world.structures.single { it.type == StructureType.PLAYER_CASTLE }
        assertEquals(32, castle.x)
        assertEquals(32, castle.y)
        assertEquals(64, world.size)
        assertEquals(Terrain.PLAIN, world.terrainAt(32, 32))
    }

    @Test
    fun alliesSitWithinTheRadiusAndPersonaSlotsAreTagged() {
        val allies = world.structures.filter { it.type == StructureType.ALLY_CASTLE }
        assertEquals(30, allies.size)
        allies.forEach { assertTrue(dist(it) in 3..8, "${it.tag} at distance ${dist(it)}") }
        assertEquals((0 until 8).map { "persona_slot_$it" }, allies.take(8).map { it.tag })
        assertEquals(22, allies.count { it.tag.startsWith("filler_") })
    }

    @Test
    fun npcKingdomsFortsAndHolyLand() {
        val kingdoms = world.structures.filter { it.type == StructureType.NPC_CASTLE }
        assertEquals(rules.kingdoms.map { it.id }, kingdoms.map { it.tag })
        assertEquals(rules.kingdoms.map { it.power }, kingdoms.map { it.power })
        assertEquals(1, world.structures.count { it.type == StructureType.HOLY_LAND })
        val forts = world.structures.filter { it.type == StructureType.BARBARIAN_FORT }
        assertEquals(rules.forts.levels.sorted(), forts.map { it.level }.sorted())
        forts.forEach {
            val lo = rules.forts.minDistanceByLevel[it.level - 1]
            val hi = rules.forts.maxDistanceByLevel[it.level - 1]
            assertTrue(dist(it) in lo..hi, "fort Lv${it.level} at ${dist(it)}")
        }
    }

    @Test
    fun structuresNeverOverlapAndAreOnPlainTerrain() {
        val keys = world.structures.map { it.x to it.y }
        assertEquals(keys.size, keys.toSet().size)
        world.structures.forEach {
            assertEquals(Terrain.PLAIN, world.terrainAt(it.x, it.y))
            assertEquals(it, world.structureAt(it.x, it.y))
            assertTrue(world.inBounds(it.x, it.y))
        }
        assertNull(world.structureAt(0, 63))
    }

    @Test
    fun terrainHasEveryKindAndRoughlyTheConfiguredShare() {
        val counts = HashMap<Terrain, Int>()
        for (y in 0 until world.size) for (x in 0 until world.size) counts.merge(world.terrainAt(x, y), 1, Int::plus)
        Terrain.entries.forEach { assertTrue((counts[it] ?: 0) > 0, "$it missing") }
        assertTrue((counts[Terrain.PLAIN] ?: 0) > world.size * world.size / 2)
    }

    @Test
    fun generationIsFast() {
        MapGenerator.generate(1, rules) // warm-up
        val ms = measureTimeMillis { repeat(10) { MapGenerator.generate(it.toLong(), rules) } } / 10
        System.err.println("PERF map generate=${ms}ms")
        assertTrue(ms < 50, "generation took ${ms}ms")
    }

    // ───── refresh ─────

    private fun filled(seed: Long = 5): MapState =
        MapRefresher.refresh(MapState(seed = seed, rngState = seed), world, rules, now = 1000)

    @Test
    fun refreshFillsToTheTargetDensityAndSchedulesTheNextOne() {
        val m = filled()
        ResourceType.entries.forEach { type -> assertEquals(14, m.nodes.count { it.type == type }, "$type") }
        assertEquals(40, m.barbarians.size)
        assertEquals(1000 + rules.refreshPeriodSec, m.nextRefreshEpochSec)
        val keys = (m.nodes.map { it.x to it.y } + m.barbarians.map { it.x to it.y })
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { (x, y) -> assertNull(world.structureAt(x, y)) }
    }

    @Test
    fun levelsFollowTheDistanceBands() {
        val m = filled()
        m.nodes.forEach {
            val band = MapRefresher.band(rules.nodes.bands, MapQueries.distance(it.x, it.y, 32, 32))
            assertTrue(it.level in band.minLevel..band.maxLevel, "node ${it.level} at ${it.x},${it.y}")
            assertEquals(rules.nodes.remainingByLevel[it.level - 1], it.remaining)
        }
        m.barbarians.forEach {
            val band = MapRefresher.band(rules.barbarians.bands, MapQueries.distance(it.x, it.y, 32, 32))
            assertTrue(it.level in band.minLevel..band.maxLevel, "barbarian ${it.level}")
        }
        // 遠いほど高レベル帯
        assertTrue(
            MapRefresher.band(rules.barbarians.bands, FAR).minLevel >
                MapRefresher.band(rules.barbarians.bands, NEAR).minLevel,
        )
    }

    @Test
    fun refreshOnlyTopsUpAndIsDeterministic() {
        val m = filled()
        assertEquals(m, filled())
        val killed =
            m.copy(
                barbarians = m.barbarians.drop(5),
                nodes = m.nodes.filterNot { it.type == ResourceType.WOOD },
            )
        val topped = MapRefresher.refresh(killed, world, rules, now = 9999)
        assertEquals(40, topped.barbarians.size)
        assertEquals(14, topped.nodes.count { it.type == ResourceType.WOOD })
        assertEquals(
            m.nodes.filter { it.type == ResourceType.FOOD },
            topped.nodes.filter { it.type == ResourceType.FOOD },
        )
        // すでに満杯なら配置は増えない
        val again = MapRefresher.refresh(topped, world, rules, now = 99999)
        assertEquals(topped.nodes, again.nodes)
        assertEquals(topped.barbarians, again.barbarians)
        assertEquals(99999 + rules.refreshPeriodSec, again.nextRefreshEpochSec)
    }

    // ───── queries ─────

    @Test
    fun tileAtAndRegionAndNeighbors() {
        val m = filled()
        val node = m.nodes.first()
        val tile = assertNotNull(MapQueries.tileAt(world, m, node.x, node.y))
        assertEquals(node, tile.node)
        assertNull(tile.barbarian)
        val barb = m.barbarians.first()
        assertEquals(barb, MapQueries.tileAt(world, m, barb.x, barb.y)?.barbarian)
        assertEquals(StructureType.PLAYER_CASTLE, MapQueries.tileAt(world, m, 32, 32)?.structure?.type)
        assertNull(MapQueries.tileAt(world, m, -1, 0))
        assertNull(MapQueries.tileAt(world, m, 64, 0))
        assertEquals(9, MapQueries.region(world, m, 31, 31, 33, 33).size)
        assertEquals(4, MapQueries.region(world, m, -5, -5, 1, 1).size) // 範囲外はクリップ
        assertEquals(64 * 64, MapQueries.region(world, m, 0, 0, 63, 63).size)
        assertEquals(4, MapQueries.neighbors(world, 10, 10).size)
        assertEquals(2, MapQueries.neighbors(world, 0, 0).size)
        assertEquals(7, MapQueries.distance(1, 1, 5, 4))
        val inRegion = MapQueries.region(world, m, 0, 0, 63, 63)
        assertEquals(m.nodes.size, inRegion.count { it.node != null })
        assertEquals(m.barbarians.size, inRegion.count { it.barbarian != null })
    }

    // ───── rules validation ─────

    private fun badRules(
        from: String,
        to: String,
    ): String {
        val raw = ClasspathDataSource.readAll()
        val text = raw.getValue("map_rules.json")
        assertTrue(text.contains(from), "pattern not found: $from")
        val e =
            assertFailsWith<GameDataException> {
                GameDataLoader.parse(raw + ("map_rules.json" to text.replaceFirst(from, to)))
            }
        return e.message ?: ""
    }

    @Test
    fun brokenMapRulesAreRejected() {
        assertTrue(badRules("\"size\": 64", "\"size\": 2").contains("size"))
        assertTrue(
            badRules("\"center\": {\"x\": 32, \"y\": 32}", "\"center\": {\"x\": 99, \"y\": 32}").contains("outside"),
        )
        assertTrue(badRules("\"id\": \"kingdom_1\"", "\"id\": \"kingdom_0\"").contains("duplicate"))
        assertTrue(badRules("\"waterPermille\": 70", "\"waterPermille\": 900").contains("terrain"))
        assertTrue(badRules("\"minRadius\": 3", "\"minRadius\": 0").contains("radius"))
        assertTrue(
            badRules("\"minDistanceByLevel\": [10, 14, 18, 24, 30]", "\"minDistanceByLevel\": [10]").contains("fort"),
        )
        assertTrue(
            badRules(
                "{\"upToDistance\": 14, \"minLevel\": 1, \"maxLevel\": 2}",
                "{\"upToDistance\": 4, \"minLevel\": 1, \"maxLevel\": 2}",
            ).contains("increasing"),
        )
        assertTrue(
            badRules(
                "\"remainingByLevel\": [30000, 60000, 120000, 240000, 480000, 960000]",
                "\"remainingByLevel\": [30000]",
            ).contains("node tables"),
        )
        assertTrue(badRules("\"baseSecPerTile\": 30", "\"baseSecPerTile\": 0").contains("march"))
        assertTrue(badRules("\"woundedPct\": 70", "\"woundedPct\": 170").contains("combat"))
        assertTrue(badRules("\"refreshPeriodSec\": 3600", "\"refreshPeriodSec\": 0").contains("refreshPeriodSec"))
        assertTrue(badRules("\"minIntervalSec\": 302400", "\"minIntervalSec\": 0").contains("raid"))
        assertTrue(
            badRules(
                "\"mix\": {\"infantry\": 40, \"cavalry\": 30, \"archer\": 30}",
                "\"mix\": {\"infantry\": 40}",
            ).contains("mix"),
        )
        assertTrue(
            badRules(
                "\"mix\": {\"infantry\": 40, \"cavalry\": 30, \"archer\": 30}",
                "\"mix\": {\"wizard\": 100}",
            ).contains("mix"),
        )
    }
}
