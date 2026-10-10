package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameDataLoaderTest {
    private val data = TestSupport.data
    private val raw: Map<String, String> by lazy { ClasspathDataSource.readAll() }

    private fun rulesJson(
        offlineCap: Int = 1,
        chunk: Int = 1,
        queues: Int = 1,
        newGame: String = "{\"resources\": {}}",
        minSec: Int = 300,
        heroes: String = "[\"hero_aldric\", \"hero_seren\", \"hero_brann\"]",
    ) = "{\"offlineCapSec\": $offlineCap, \"catchUpChunkSec\": $chunk, " +
        "\"baseBuildQueues\": $queues, \"offlineSummaryMinSec\": $minSec, \"starterHeroes\": $heroes, " +
        "\"newGame\": $newGame}"

    private fun parseWith(
        name: String,
        text: String,
    ) = GameDataLoader.parse(raw + (name to text))

    @Test
    fun realDataLoads() {
        assertEquals(BuildingType.entries.toSet(), data.buildings.keys)
        data.buildings.values.forEach { def ->
            assertEquals(25, def.maxLevel)
            assertEquals((1..25).toList(), def.levels.map { it.level })
        }
        assertEquals(41, data.items.size)
        assertEquals(4, data.buildings.getValue(BuildingType.FARM).count)
        assertEquals(3, data.buildings.getValue(BuildingType.QUARRY).count)
        assertEquals(2, data.buildings.getValue(BuildingType.GOLD_MINE).count)
        assertEquals(86400, data.rules.offlineCapSec)
        assertEquals(300, data.rules.offlineSummaryMinSec)
        assertEquals(listOf("hero_aldric", "hero_seren", "hero_brann"), data.rules.starterHeroes)
    }

    @Test
    fun castleSecondsHitAnchors() {
        val castle = data.building(BuildingType.CASTLE)
        mapOf(5 to 300L, 10 to 3600L, 15 to 43200L, 20 to 172800L, 25 to 604800L).forEach { (lv, target) ->
            val got = castle.level(lv).seconds
            assertTrue(got in (target * 9 / 10)..(target * 11 / 10), "Castle Lv$lv seconds=$got target=$target")
        }
        assertTrue(castle.level(2).seconds in 5..20)
    }

    @Test
    fun costAndSecondsAreMonotone() {
        data.buildings.values.forEach { def ->
            def.levels.zipWithNext { a, b ->
                assertTrue(b.seconds > a.seconds, "${def.type} seconds ${a.level}->${b.level}")
                val ta = a.cost.food + a.cost.wood + a.cost.stone + a.cost.gold
                val tb = b.cost.food + b.cost.wood + b.cost.stone + b.cost.gold
                assertTrue(tb > ta, "${def.type} total cost ${a.level}->${b.level}")
                assertTrue(b.cost.food >= a.cost.food && b.cost.stone >= a.cost.stone && b.cost.gold >= a.cost.gold)
                assertTrue(b.power > a.power)
            }
        }
    }

    @Test
    fun stoneIsTheLateBottleneckAndGoldGrows() {
        val castle = data.building(BuildingType.CASTLE)
        (15..25).forEach { assertTrue(castle.level(it).cost.stone > castle.level(it).cost.food, "Lv$it") }
        assertEquals(0, castle.level(1).cost.gold)
        assertTrue(castle.level(25).cost.gold > castle.level(10).cost.gold)
    }

    @Test
    fun prereqsFollowTheRules() {
        data.buildings.values.forEach { def ->
            def.levels.forEach { l ->
                when {
                    def.type != BuildingType.CASTLE ->
                        assertEquals(
                            listOf(com.ttmrase.crownfall.core.model.Prereq(BuildingType.CASTLE, l.level)),
                            l.prereqs,
                        )
                    l.level == 1 -> assertTrue(l.prereqs.isEmpty())
                    else ->
                        assertEquals(
                            setOf(
                                com.ttmrase.crownfall.core.model.Prereq(BuildingType.WALL, l.level - 1),
                                com.ttmrase.crownfall.core.model.Prereq(BuildingType.WAREHOUSE, l.level - 1),
                            ),
                            l.prereqs.toSet(),
                        )
                }
            }
        }
    }

    @Test
    fun effectKeysExist() {
        assertTrue(data.building(BuildingType.FARM).effect(1, "production_per_hour") > 0)
        assertTrue(data.building(BuildingType.WAREHOUSE).effect(1, "storage_cap") > 0)
        assertTrue(data.building(BuildingType.WAREHOUSE).effect(1, "protect") > 0)
        val c = data.building(BuildingType.CASTLE)
        listOf("storage_cap_base", "troop_capacity", "march_slots").forEach { assertTrue(c.effect(1, it) > 0, it) }
        assertEquals(0, c.effect(0, "storage_cap_base"))
        assertEquals(0, c.effect(1, "no_such_key"))
    }

    @Test
    fun itemsCoverAllCategoriesAndDurations() {
        SpeedupCategory.entries.forEach { c ->
            val ids = data.items.values.filter { it.category == c }.map { it.seconds }.sorted()
            assertEquals(listOf(60L, 300, 900, 3600, 10800, 28800, 86400), ids, "$c")
        }
        assertEquals("speedup_build_5m", data.items["speedup_build_5m"]?.id)
        assertTrue(SpeedupCategory.GENERAL.matches(com.ttmrase.crownfall.core.model.TimerKind.HEAL))
        assertTrue(!SpeedupCategory.BUILD.matches(com.ttmrase.crownfall.core.model.TimerKind.TRAIN))
    }

    private fun assertLoadFails(
        name: String,
        text: String,
        contains: String,
    ) {
        val e = assertFailsWith<GameDataException> { parseWith(name, text) }
        assertTrue(e.message!!.contains(contains), "message was: ${e.message}")
    }

    @Test
    fun brokenFilesAreRejectedWithClearMessages() {
        assertFailsWith<GameDataException> { GameDataLoader.parse(raw - "rules.json") }
        assertLoadFails("buildings.json", "{ not json", "buildings.json")
        assertLoadFails("buildings.json", """{"buildings": []}""", "missing types")
        assertLoadFails("rules.json", rulesJson(minSec = -1), "offlineSummaryMinSec")
        assertLoadFails("rules.json", rulesJson(heroes = "[\"hero_aldric\", \"hero_seren\"]"), "starterHeroes")
        assertLoadFails(
            "rules.json",
            rulesJson(heroes = "[\"hero_aldric\", \"hero_aldric\", \"hero_seren\"]"),
            "starterHeroes",
        )
        assertLoadFails(
            "rules.json",
            rulesJson(offlineCap = -1),
            "offlineCapSec",
        )
        assertLoadFails(
            "rules.json",
            rulesJson(chunk = 0),
            "catchUpChunkSec",
        )
        assertLoadFails(
            "rules.json",
            rulesJson(queues = 0),
            "baseBuildQueues",
        )
        assertLoadFails(
            "rules.json",
            rulesJson(newGame = """{"resources": {"food": -1}}"""),
            "negative",
        )
        assertLoadFails(
            "rules.json",
            rulesJson(newGame = """{"resources": {}, "initialLevels": {"castle": [1, 1]}}"""),
            "more slots",
        )
        assertLoadFails(
            "rules.json",
            rulesJson(newGame = """{"resources": {}, "initialLevels": {"castle": [99]}}"""),
            "out of range",
        )
        assertLoadFails(
            "rules.json",
            rulesJson(newGame = """{"resources": {}, "items": {"speedup_typo_5m": 1}}"""),
            "unknown item id speedup_typo_5m",
        )
        assertLoadFails(
            "items.json",
            """{"items":[{"id":"a","category":"build","seconds":1},{"id":"a","category":"build","seconds":2}]}""",
            "duplicate",
        )
        assertLoadFails("items.json", """{"items":[{"id":"a","category":"zzz","seconds":1}]}""", "category")
        assertLoadFails("items.json", """{"items":[{"id":"a","category":"build","seconds":0}]}""", "seconds")
        assertLoadFails("speedup.json", """{"tiers": []}""", "empty")
        assertLoadFails("speedup.json", """{"tiers": [{"upToSec": 60, "gemsPerHour": 1}]}""", "last tier")
        assertLoadFails("speedup.json", """{"tiers": [{"gemsPerHour": 1}, {"gemsPerHour": 1}]}""", "null upToSec")
        assertLoadFails(
            "speedup.json",
            """{"tiers": [{"upToSec": 60, "gemsPerHour": 1}, {"upToSec": 30, "gemsPerHour": 1}, {"gemsPerHour": 1}]}""",
            "increasing",
        )
        assertLoadFails("speedup.json", """{"tiers": [{"gemsPerHour": -1}]}""", "negative")
    }

    private fun mutateBuildings(block: (String) -> String) = block(raw.getValue("buildings.json"))

    @Test
    fun brokenBuildingsAreRejected() {
        val b = raw.getValue("buildings.json")
        assertLoadFails("buildings.json", b.replaceFirst("\"level\":2,", "\"level\":3,"), "not contiguous")
        assertLoadFails("buildings.json", b.replaceFirst("\"food\":100,", "\"food\":-100,"), "negative cost")
        assertLoadFails("buildings.json", b.replaceFirst("\"seconds\":5,", "\"seconds\":-5,"), "negative seconds")
        assertLoadFails(
            "buildings.json",
            b.replaceFirst("\"troop_capacity\":1000", "\"troop_capacity\":-1"),
            "negative effect",
        )
        assertLoadFails("buildings.json", b.replaceFirst("\"type\": \"farm\"", "\"type\": \"castle\""), "duplicate")
        assertLoadFails("buildings.json", b.replaceFirst("\"count\": 1,", "\"count\": 0,"), "count")
        assertLoadFails("buildings.json", b.replaceFirst("\"maxLevel\": 25", "\"maxLevel\": 24"), "expected 24")
        assertLoadFails(
            "buildings.json",
            b.replaceFirst("\"type\":\"wall\",\"level\":1", "\"type\":\"wall\",\"level\":99"),
            "out of range",
        )
        assertTrue(mutateBuildings { it }.isNotEmpty())
    }
}
