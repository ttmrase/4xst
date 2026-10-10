package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameDataM3Test {
    private val data = TestSupport.data
    private val raw: Map<String, String> by lazy { ClasspathDataSource.readAll() }

    private fun assertFails(
        name: String,
        text: String,
        contains: String,
    ) {
        val e = assertFailsWith<GameDataException> { GameDataLoader.parse(raw + (name to text)) }
        assertTrue(e.message!!.contains(contains), "message was: ${e.message}")
    }

    // ───── troops ─────

    @Test
    fun troopsCoverEveryTypeAndTier() {
        assertEquals(16, data.troops.size)
        TroopType.entries.forEach { type ->
            val tiers = data.troops.values.filter { it.type == type }.sortedBy { it.tier }
            assertEquals(listOf(1, 2, 3, 4), tiers.map { it.tier }, "$type")
            assertEquals(listOf(1, 6, 11, 16), tiers.map { it.unlockBuildingLevel })
            tiers.zipWithNext { a, b ->
                assertTrue(b.trainSec > a.trainSec && b.power > a.power && b.hp > a.hp && b.attack > a.attack, "$type")
                assertTrue(b.upkeepPer100PerHour >= a.upkeepPer100PerHour)
                assertTrue(b.cost.food >= a.cost.food && b.cost.gold >= a.cost.gold)
            }
        }
        assertEquals("infantry_t1", data.troops.getValue("infantry_t1").id)
    }

    @Test
    fun counterTriangleAndSiege() {
        fun counter(type: TroopType) = data.troops.values.first { it.type == type }.counter
        assertEquals(TroopType.CAVALRY, counter(TroopType.INFANTRY))
        assertEquals(TroopType.ARCHER, counter(TroopType.CAVALRY))
        assertEquals(TroopType.INFANTRY, counter(TroopType.ARCHER))
        assertEquals(null, counter(TroopType.SIEGE))
        assertEquals(BuildingType.BARRACKS, TroopType.INFANTRY.trainingBuilding)
        assertEquals(BuildingType.SIEGE_WORKSHOP, TroopType.SIEGE.trainingBuilding)
    }

    @Test
    fun trainingBuildingsExposeCapacityAndHospitalCapacity() {
        listOf(
            BuildingType.BARRACKS,
            BuildingType.ARCHERY_RANGE,
            BuildingType.STABLE,
            BuildingType.SIEGE_WORKSHOP,
        ).forEach {
            assertTrue(data.building(it).effect(1, "train_capacity") > 0, "$it")
            assertTrue(data.building(it).effect(25, "train_capacity") > data.building(it).effect(1, "train_capacity"))
        }
        assertTrue(data.building(BuildingType.HOSPITAL).effect(1, "hospital_capacity") > 0)
    }

    // ───── research ─────

    @Test
    fun researchHasThreeBranchesOfTenNodes() {
        assertEquals(30, data.research.size)
        val byBranch = data.research.values.groupBy { it.branch }
        assertEquals(setOf("economy", "military", "development"), byBranch.keys)
        byBranch.values.forEach { assertEquals(10, it.size) }
        data.research.values.forEach { n ->
            assertEquals(10, n.maxLevel)
            n.levels.zipWithNext { a, b ->
                assertTrue(b.seconds > a.seconds, "${n.id} seconds")
                assertTrue(b.effectBp > a.effectBp, "${n.id} bp is cumulative and increasing")
                assertTrue(b.academyLevel >= a.academyLevel)
                assertTrue(b.power > a.power)
            }
        }
    }

    @Test
    fun researchTimesFollowAcademyAndReachDays() {
        val longest = data.research.values.maxOf { it.levels.last().seconds }
        assertTrue(longest > 2 * 86_400, "longest research is $longest s")
        val academy = data.building(BuildingType.ACADEMY)
        val node = data.research.getValue("dev_build")
        node.levels.forEach { l ->
            assertTrue(l.seconds >= academy.level(l.academyLevel).seconds, "${node.id} Lv${l.level}")
        }
    }

    @Test
    fun researchCostsFitInStorageAtTheRequiredAcademyLevel() {
        data.research.values.forEach { n ->
            n.levels.forEach { l ->
                val cap =
                    data.building(BuildingType.CASTLE).effect(l.academyLevel, "storage_cap_base") +
                        data.building(BuildingType.WAREHOUSE).effect(l.academyLevel, "storage_cap")
                val worst = maxOf(l.cost.food, l.cost.wood, l.cost.stone, l.cost.gold)
                assertTrue(worst <= cap, "${n.id} Lv${l.level}: cost $worst > cap $cap")
            }
        }
    }

    @Test
    fun researchPrereqsPointToExistingNodes() {
        val adv = data.research.getValue("eco_food_adv")
        assertEquals(listOf(ResearchPrereq("eco_food", 5)), adv.level(1).nodes)
        assertTrue(data.research.getValue("eco_food").level(1).nodes.isEmpty())
        assertEquals(BuffKey.PRODUCTION_FOOD, adv.level(1).effectKey)
        assertEquals(
            BuffKey.entries.toSet().containsAll(
                data.research.values.flatMap {
                        n ->
                    n.levels.map { it.effectKey }
                },
            ),
            true,
        )
    }

    // ───── heroes ─────

    @Test
    fun heroRosterAndStarters() {
        assertEquals(12, data.heroes.size)
        val counts = data.heroes.values.groupingBy { it.rarity }.eachCount()
        assertEquals(mapOf(HeroRarity.N to 3, HeroRarity.R to 4, HeroRarity.SR to 3, HeroRarity.SSR to 2), counts)
        data.rules.starterHeroes.forEach { assertEquals(HeroRarity.R, data.heroes.getValue(it).rarity) }
        data.heroes.values.forEach { h ->
            assertEquals(3, h.skills.size)
            assertEquals(listOf("active", "passive", "passive"), h.skills.map { it.slot })
            h.skills.forEach { assertEquals(5, it.values.size) }
        }
    }

    @Test
    fun heroRulesAreConsistent() {
        val r = data.heroRules
        assertEquals(59, r.expToNext.size)
        assertEquals(60, r.maxLevel)
        assertEquals(listOf(20, 30, 40, 50, 60), r.starLevelCaps)
        assertEquals(
            mapOf(HeroRarity.N to 5, HeroRarity.R to 10, HeroRarity.SR to 20, HeroRarity.SSR to 40),
            r.dupShards,
        )
        assertEquals(10_000, r.premium.rates.values.sum())
        assertEquals(10_000, r.normal.rates.values.sum())
        assertEquals(200, r.premium.rates.getValue(HeroRarity.SSR))
        assertEquals(1300, r.premium.rates.getValue(HeroRarity.SR))
        assertEquals(null, r.normal.rates[HeroRarity.SSR])
        assertEquals(100, r.premium.pityAt)
        assertEquals(300, r.premium.gems)
        assertEquals(2700, r.premium.gemsTen)
        assertTrue(r.expToNext.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun newItemsExist() {
        assertEquals(1000, data.items.getValue("hero_exp_1k").value)
        assertEquals(5000, data.items.getValue("hero_exp_5k").value)
        assertEquals(20000, data.items.getValue("hero_exp_20k").value)
        assertEquals(ItemKind.SKILL_BOOK, data.items.getValue("skill_book").kind)
        assertEquals(ItemKind.SUMMON_TICKET_NORMAL, data.items.getValue("summon_ticket_normal").kind)
        assertEquals(ItemKind.SUMMON_TICKET_PREMIUM, data.items.getValue("summon_ticket_premium").kind)
        assertEquals(null, data.items.getValue("hero_exp_1k").category)
        assertEquals(60, data.items.getValue("speedup_general_1m").seconds)
    }

    @Test
    fun foodUpkeepStaysWellBelowEarlyProduction() {
        // 初期の Farm（Lv1）で T1 の兵 1000 体を養える（維持費 10/時 < 生産 300/時）
        val s = TestSupport.newGame().copy(troops = mapOf("infantry_t1" to 1000))
        assertTrue(Simulation.hourlyRate(s, com.ttmrase.crownfall.core.model.ResourceType.FOOD, data) > 10)
    }

    // ───── validation ─────

    private fun mutate(
        name: String,
        from: String,
        to: String,
    ): String {
        val text = raw.getValue(name)
        assertTrue(text.contains(from), "pattern not found in $name: $from")
        return text.replaceFirst(from, to)
    }

    @Test
    fun brokenTroopsAreRejected() {
        val n = "troops.json"
        assertFails(n, mutate(n, "\"id\":\"infantry_t2\"", "\"id\":\"infantry_t1\""), "duplicate")
        assertFails(n, mutate(n, "\"type\":\"infantry\"", "\"type\":\"dragon\""), "unknown troop type")
        assertFails(n, mutate(n, "\"trainSec\":6", "\"trainSec\":0"), "trainSec")
        assertFails(n, mutate(n, "\"tier\":1", "\"tier\":9"), "tier")
        assertFails(n, mutate(n, "\"counter\":\"cavalry\"", "\"counter\":\"wyvern\""), "counter")
        assertFails(n, mutate(n, "\"power\":2,", "\"power\":-2,"), "negative")
        val missingTier =
            raw.getValue(n).lines().filterNot {
                it.contains("\"id\":\"siege_t4\"")
            }.joinToString("\n").replace("},\n ]", "}\n ]")
        assertFails(n, missingTier, "tiers 1..4")
    }

    private fun researchJson(second: String = "\"prereqs\":{\"academyLevel\":1,\"nodes\":[]}"): String =
        """{"nodes":[
        {"id":"a","branch":"b","maxLevel":1,"levels":[{"level":1,"cost":{},"seconds":1,"prereqs":{"academyLevel":1,"nodes":[{"id":"b","level":1}]},"effect":{"key":"BUILD_SPEED","bp":1},"power":1}]},
        {"id":"b","branch":"b","maxLevel":1,"levels":[{"level":1,"cost":{},"seconds":1,$second,"effect":{"key":"BUILD_SPEED","bp":1},"power":1}]}
        ]}"""

    @Test
    fun brokenResearchIsRejected() {
        val n = "research.json"
        assertFails(n, researchJson("\"prereqs\":{\"academyLevel\":1,\"nodes\":[{\"id\":\"a\",\"level\":1}]}"), "cycle")
        assertFails(
            n,
            researchJson("\"prereqs\":{\"academyLevel\":1,\"nodes\":[{\"id\":\"zzz\",\"level\":1}]}"),
            "does not exist",
        )
        assertFails(
            n,
            researchJson("\"prereqs\":{\"academyLevel\":1,\"nodes\":[{\"id\":\"a\",\"level\":7}]}"),
            "out of range",
        )
        assertFails(n, mutate(n, "\"key\":\"PRODUCTION_FOOD\"", "\"key\":\"NOPE\""), "unknown buff key")
        assertFails(n, mutate(n, "\"level\":2,", "\"level\":3,"), "not contiguous")
        assertFails(n, mutate(n, "\"seconds\":", "\"seconds\":-"), "negative")
        assertFails(n, mutate(n, "\"id\": \"eco_wood\"", "\"id\": \"eco_food\""), "duplicate")
        assertFails(n, mutate(n, "\"maxLevel\": 10", "\"maxLevel\": 9"), "expected 9")
    }

    @Test
    fun brokenHeroesAreRejected() {
        val n = "heroes.json"
        assertFails(n, mutate(n, "\"SSR\":200,", "\"SSR\":201,"), "rates must sum")
        assertFails(n, mutate(n, "\"ticketItem\":\"summon_ticket_premium\"", "\"ticketItem\":\"nope\""), "ticketItem")
        assertFails(
            n,
            mutate(n, "\"ticketItem\":\"summon_ticket_premium\"", "\"ticketItem\":\"skill_book\""),
            "not a summon ticket",
        )
        assertFails(n, mutate(n, "\"id\":\"hero_mira\"", "\"id\":\"hero_tobias\""), "duplicate")
        assertFails(n, mutate(n, "\"troopAffinity\":\"infantry\"", "\"troopAffinity\":\"mage\""), "unknown troop type")
        assertFails(n, mutate(n, "\"starLevelCaps\":[20,30,40,50,60]", "\"starLevelCaps\":[20,30,40,50,99]"), "exceeds")
        assertFails(n, mutate(n, "\"starUpShards\":[20,40,80,160]", "\"starUpShards\":[20,40,80]"), "starUpShards")
        assertFails(n, mutate(n, "\"maxSkillLevel\":5", "\"maxSkillLevel\":4"), "skill values")
        assertFails(n, mutate(n, "\"dupShards\":{\"N\":5,", "\"dupShards\":{"), "dupShards")
        assertFails(n, mutate(n, "\"pityAt\":100", "\"pityAt\":-1"), "negative")
    }

    @Test
    fun heroesWithoutAnSsrCannotHavePity() {
        val noSsr =
            raw.getValue("heroes.json").lines().filterNot { it.contains("\"rarity\":\"SSR\"") }.joinToString("\n")
                .replace("},\n ]", "}\n ]")
        assertFails("heroes.json", noSsr, "SSR")
    }

    @Test
    fun rulesReferToExistingHeroes() {
        val n = "rules.json"
        assertFails(n, mutate(n, "\"hero_aldric\"", "\"hero_ghost\""), "unknown hero id")
        assertFails(
            n,
            mutate(n, "\"offlineSummaryMinSec\": 300,", "\"offlineSummaryMinSec\": 300, \"healCostPct\": -1,"),
            "heal",
        )
    }

    @Test
    fun brokenItemsAreRejected() {
        assertFails("items.json", """{"items":[{"id":"x","kind":"wand"}]}""", "unknown kind")
        assertFails("items.json", """{"items":[{"id":"x","kind":"hero_exp","value":-5}]}""", "value")
    }
}
