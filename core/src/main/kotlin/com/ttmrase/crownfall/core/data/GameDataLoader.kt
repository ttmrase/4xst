package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.Resources
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** データ検証違反。起動時に一度だけの検証なので例外で通知する。 */
class GameDataException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
private class BuildingsFile(val buildings: List<BuildingDto>)

@Serializable
private class BuildingDto(val type: BuildingType, val count: Int, val maxLevel: Int, val levels: List<LevelDto>)

@Serializable
private class LevelDto(
    val level: Int,
    val cost: Resources,
    val seconds: Long,
    val prereqs: List<Prereq> = emptyList(),
    val effects: Map<String, Long> = emptyMap(),
    val power: Long,
)

@Serializable
private class RulesDto(
    val offlineCapSec: Long,
    val catchUpChunkSec: Long,
    val baseBuildQueues: Int,
    val offlineSummaryMinSec: Long = 300,
    val starterHeroes: List<String> = emptyList(),
    val healCostPct: Long = 50,
    val healTimePct: Long = 40,
    val newGame: NewGameDto,
)

@Serializable
private class NewGameDto(
    val resources: Resources,
    val gems: Long = 0,
    val initialLevels: Map<BuildingType, List<Int>> = emptyMap(),
    val items: Map<String, Int> = emptyMap(),
)

@Serializable
private class ItemsFile(val items: List<ItemDto>)

@Serializable
private class ItemDto(
    val id: String,
    val kind: String = "speedup",
    val category: String? = null,
    val seconds: Long = 0,
    val value: Long = 0,
)

@Serializable
private class TroopsFile(val troops: List<TroopDto>)

@Serializable
private class TroopDto(
    val id: String,
    val type: String,
    val tier: Int,
    val attack: Long,
    val defense: Long,
    val hp: Long,
    val speed: Long,
    val load: Long,
    val trainSec: Long,
    val cost: Resources,
    val upkeepPer100PerHour: Long,
    val power: Long,
    val unlockBuildingLevel: Int,
    val counter: String? = null,
)

@Serializable
private class ResearchFile(val nodes: List<ResearchNodeDto>)

@Serializable
private class ResearchNodeDto(val id: String, val branch: String, val maxLevel: Int, val levels: List<ResearchLevelDto>)

@Serializable
private class ResearchLevelDto(
    val level: Int,
    val cost: Resources,
    val seconds: Long,
    val prereqs: ResearchPrereqsDto,
    val effect: ResearchEffectDto,
    val power: Long,
)

@Serializable
private class ResearchPrereqsDto(val academyLevel: Int, val nodes: List<ResearchNodeReqDto> = emptyList())

@Serializable
private class ResearchNodeReqDto(val id: String, val level: Int)

@Serializable
private class ResearchEffectDto(val key: String, val bp: Long)

@Serializable
private class HeroesFile(val rules: HeroRulesDto, val heroes: List<HeroDto>)

@Serializable
private class HeroRulesDto(
    val expToNext: List<Long>,
    val starLevelCaps: List<Int>,
    val starUpShards: List<Int>,
    val dupShards: Map<HeroRarity, Int>,
    val maxSkillLevel: Int,
    val power: HeroPowerDto,
    val summon: SummonsDto,
)

@Serializable
private class HeroPowerDto(val rarityBase: Map<HeroRarity, Long>, val perLevel: Long, val perSkillLevel: Long)

@Serializable
private class SummonsDto(val normal: SummonTableDto, val premium: SummonTableDto)

@Serializable
private class SummonTableDto(
    val gold: Long = 0,
    val goldTen: Long = 0,
    val gems: Long = 0,
    val gemsTen: Long = 0,
    val ticketItem: String,
    val rates: Map<HeroRarity, Int>,
    val tenGuarantee: HeroRarity,
    val pityAt: Int = 0,
)

@Serializable
private class HeroDto(
    val id: String,
    val rarity: HeroRarity,
    val troopAffinity: String,
    val baseStats: HeroStatsDto,
    val skills: List<HeroSkillDto>,
)

@Serializable
private class HeroStatsDto(val attack: Long, val defense: Long, val hp: Long)

@Serializable
private class HeroSkillDto(val slot: String, val effectKey: String, val values: List<Int>)

@Serializable
private class SpeedupFile(val tiers: List<TierDto>)

@Serializable
private class TierDto(val upToSec: Long? = null, val gemsPerHour: Long)

/** JSON 文字列から [GameData] を組み立てて検証する。ファイル I/O は持たない。 */
object GameDataLoader {
    /** データファイル名。 */
    const val BUILDINGS = "buildings.json"
    const val RULES = "rules.json"
    const val ITEMS = "items.json"
    const val SPEEDUP = "speedup.json"
    const val TROOPS = "troops.json"
    const val RESEARCH = "research.json"
    const val HEROES = "heroes.json"
    const val MAP_RULES = "map_rules.json"
    const val PERSONAS = "personas.json"
    const val ALLIANCE_RULES = "alliance_rules.json"
    const val TEMPLATES = "templates.json"
    const val OFFERS = "offers.json"
    const val SHOP_RULES = "shop_rules.json"
    const val VIP = "vip.json"

    private const val STARTER_HERO_COUNT = 3
    private const val TROOP_TIERS = 4
    private const val HERO_SKILLS = 3
    private const val BP_TOTAL = 10_000
    private const val MIN_MAP = 8
    private const val MAX_MAP = 256
    private const val PERMILLE = 1000
    private const val PERCENT = 100

    private val json = Json { ignoreUnknownKeys = true }

    /** [jsonByName]（キーはファイル名）から [GameData] を作る。違反は [GameDataException]。 */
    fun parse(jsonByName: Map<String, String>): GameData {
        val buildings = decode<BuildingsFile>(jsonByName, BUILDINGS)
        val rules = decode<RulesDto>(jsonByName, RULES)
        val items = decode<ItemsFile>(jsonByName, ITEMS)
        val speedup = decode<SpeedupFile>(jsonByName, SPEEDUP)
        val troopsFile = decode<TroopsFile>(jsonByName, TROOPS)
        val researchFile = decode<ResearchFile>(jsonByName, RESEARCH)
        val heroesFile = decode<HeroesFile>(jsonByName, HEROES)
        val mapRules = validateMapRules(decode<MapRules>(jsonByName, MAP_RULES))
        val personas = decode<PersonasFile>(jsonByName, PERSONAS)
        val allianceRules = decode<AllianceRules>(jsonByName, ALLIANCE_RULES)
        val templates = decode<TemplatesFile>(jsonByName, TEMPLATES)
        val offers = decode<OffersFile>(jsonByName, OFFERS)
        val shopRules = decode<ShopRules>(jsonByName, SHOP_RULES)
        val vip = decode<VipFile>(jsonByName, VIP)
        val defs = buildBuildings(buildings)
        val itemDefs = buildItems(items)
        val heroDefs = buildHeroes(heroesFile, itemDefs)
        return GameData(
            buildings = defs,
            rules = buildRules(rules, defs, itemDefs, heroDefs.first),
            items = itemDefs,
            speedupTiers = buildTiers(speedup),
            troops = buildTroops(troopsFile),
            research = buildResearch(researchFile),
            heroes = heroDefs.first,
            heroRules = heroDefs.second,
            mapRules = mapRules,
            personas = personas,
            allianceRules = allianceRules,
            templates = templates,
            offers = offers,
            shopRules = shopRules,
            vip = vip,
        ).also {
            AllianceValidator.validate(it)
            ShopValidator.validate(it)
        }
    }

    private inline fun <reified T> decode(
        src: Map<String, String>,
        name: String,
    ): T {
        val text = src[name] ?: throw GameDataException("missing data file: $name")
        return try {
            json.decodeFromString<T>(text)
        } catch (e: SerializationException) {
            throw GameDataException("invalid $name: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw GameDataException("invalid $name: ${e.message}", e)
        }
    }

    private fun fail(message: String): Nothing = throw GameDataException(message)

    private fun nonNegative(r: Resources) = r.food >= 0 && r.wood >= 0 && r.stone >= 0 && r.gold >= 0

    private fun buildBuildings(file: BuildingsFile): Map<BuildingType, BuildingDef> {
        val byType = LinkedHashMap<BuildingType, BuildingDef>()
        for (dto in file.buildings) {
            if (dto.type in byType) fail("buildings.json: duplicate type ${dto.type}")
            if (dto.count < 1) fail("buildings.json: ${dto.type} count must be >= 1")
            if (dto.maxLevel < 1) fail("buildings.json: ${dto.type} maxLevel must be >= 1")
            if (dto.levels.size != dto.maxLevel) {
                fail("buildings.json: ${dto.type} has ${dto.levels.size} levels, expected ${dto.maxLevel}")
            }
            val levels =
                dto.levels.mapIndexed { i, l ->
                    if (l.level != i + 1) {
                        fail(
                            "buildings.json: ${dto.type} levels not contiguous at index $i (level=${l.level})",
                        )
                    }
                    if (!nonNegative(l.cost)) fail("buildings.json: ${dto.type} Lv${l.level} has negative cost")
                    if (l.seconds < 0 || l.power < 0) {
                        fail(
                            "buildings.json: ${dto.type} Lv${l.level} has negative seconds/power",
                        )
                    }
                    if (l.effects.values.any { it < 0 }) {
                        fail(
                            "buildings.json: ${dto.type} Lv${l.level} has negative effect",
                        )
                    }
                    BuildingLevel(l.level, l.cost, l.seconds, l.prereqs, l.effects, l.power)
                }
            byType[dto.type] = BuildingDef(dto.type, dto.count, dto.maxLevel, levels)
        }
        val missing = BuildingType.entries.filter { it !in byType }
        if (missing.isNotEmpty()) fail("buildings.json: missing types $missing")
        for (def in byType.values) {
            for (l in def.levels) {
                for (p in l.prereqs) {
                    val target = byType[p.type]
                    if (p.level < 0 || p.level > (target?.maxLevel ?: 0)) {
                        fail("buildings.json: ${def.type} Lv${l.level} prereq ${p.type} Lv${p.level} is out of range")
                    }
                }
            }
        }
        return byType
    }

    private fun buildRules(
        dto: RulesDto,
        defs: Map<BuildingType, BuildingDef>,
        items: Map<String, ItemDef>,
        heroes: Map<String, HeroDef>,
    ): Rules {
        if (dto.offlineCapSec < 0) fail("rules.json: offlineCapSec must be >= 0")
        if (dto.catchUpChunkSec < 1) fail("rules.json: catchUpChunkSec must be >= 1")
        if (dto.baseBuildQueues < 1) fail("rules.json: baseBuildQueues must be >= 1")
        if (dto.offlineSummaryMinSec < 0) fail("rules.json: offlineSummaryMinSec must be >= 0")
        if (dto.starterHeroes.size != STARTER_HERO_COUNT || dto.starterHeroes.toSet().size != STARTER_HERO_COUNT) {
            fail("rules.json: starterHeroes must have $STARTER_HERO_COUNT distinct ids")
        }
        dto.starterHeroes.firstOrNull { it !in heroes }?.let {
            fail(
                "rules.json: starterHeroes has unknown hero id $it",
            )
        }
        if (dto.healCostPct < 0 || dto.healTimePct < 0) fail("rules.json: heal percentages must be >= 0")
        val ng = dto.newGame
        if (!nonNegative(ng.resources) || ng.gems < 0) fail("rules.json: newGame has negative resources/gems")
        for ((type, levels) in ng.initialLevels) {
            val def = defs.getValue(type)
            if (levels.size > def.count) {
                fail(
                    "rules.json: newGame.initialLevels.$type has more slots than count ${def.count}",
                )
            }
            if (levels.any { it < 0 || it > def.maxLevel }) {
                fail(
                    "rules.json: newGame.initialLevels.$type level out of range",
                )
            }
        }
        if (ng.items.values.any { it < 0 }) fail("rules.json: newGame.items has negative count")
        ng.items.keys.firstOrNull { it !in items }?.let { fail("rules.json: newGame.items has unknown item id $it") }
        return Rules(
            offlineCapSec = dto.offlineCapSec,
            catchUpChunkSec = dto.catchUpChunkSec,
            baseBuildQueues = dto.baseBuildQueues,
            offlineSummaryMinSec = dto.offlineSummaryMinSec,
            starterHeroes = dto.starterHeroes,
            healCostPct = dto.healCostPct,
            healTimePct = dto.healTimePct,
            newGame = NewGameRules(ng.resources, ng.gems, ng.initialLevels, ng.items),
        )
    }

    private fun buildItems(file: ItemsFile): Map<String, ItemDef> {
        val out = LinkedHashMap<String, ItemDef>()
        for (dto in file.items) {
            if (dto.id in out) fail("items.json: duplicate item id ${dto.id}")
            val kind =
                ItemKind.entries.firstOrNull { it.name.equals(dto.kind, ignoreCase = true) }
                    ?: fail("items.json: ${dto.id} has unknown kind ${dto.kind}")
            if (dto.value < 0) fail("items.json: ${dto.id} value must be >= 0")
            if (kind == ItemKind.SPEEDUP) {
                if (dto.seconds <= 0) fail("items.json: ${dto.id} seconds must be > 0")
                val category =
                    SpeedupCategory.entries.firstOrNull { it.name.equals(dto.category, ignoreCase = true) }
                        ?: fail("items.json: ${dto.id} has unknown category ${dto.category}")
                out[dto.id] = ItemDef(dto.id, kind, category, dto.seconds, 0)
            } else {
                out[dto.id] = ItemDef(dto.id, kind, null, 0, dto.value)
            }
        }
        return out
    }

    private fun validateMapRules(m: MapRules): MapRules {
        val where = "map_rules.json"
        if (m.size !in MIN_MAP..MAX_MAP) fail("$where: size out of range")

        fun inside(p: XY) = p.x in 0 until m.size && p.y in 0 until m.size
        if (!inside(m.center) || !inside(m.holyLand)) fail("$where: center/holyLand is outside the map")
        m.kingdoms.firstOrNull { it.x !in 0 until m.size || it.y !in 0 until m.size }?.let {
            fail("$where: kingdom ${it.id} is outside the map")
        }
        if (m.kingdoms.map { it.id }.toSet().size != m.kingdoms.size) fail("$where: duplicate kingdom id")
        val terrainSum = m.terrain.forestPermille + m.terrain.mountainPermille + m.terrain.waterPermille
        val negativeShare =
            listOf(
                m.terrain.forestPermille,
                m.terrain.mountainPermille,
                m.terrain.waterPermille,
            ).any { it < 0 }
        if (negativeShare || terrainSum > PERMILLE) {
            fail("$where: terrain permille values are invalid")
        }
        if (m.allies.minRadius < 1 || m.allies.maxRadius < m.allies.minRadius) fail("$where: ally radius is invalid")
        val fortLevels = m.forts.levels.maxOrNull() ?: 0
        if (m.forts.minDistanceByLevel.size < fortLevels || m.forts.maxDistanceByLevel.size < fortLevels) {
            fail("$where: fort distance tables must cover every fort level")
        }
        if (m.rally.allyTroopsByFortLevel.size < fortLevels || m.barbarians.fortEquivLevels.size < fortLevels) {
            fail("$where: fort tables must cover every fort level")
        }
        for ((name, bands) in listOf("nodes" to m.nodes.bands, "barbarians" to m.barbarians.bands)) {
            if (bands.isEmpty() || bands.zipWithNext().any { (a, b) -> b.upToDistance <= a.upToDistance }) {
                fail("$where: $name bands must be non-empty and increasing")
            }
            if (bands.any { it.minLevel < 1 || it.maxLevel < it.minLevel }) {
                fail(
                    "$where: $name band levels are invalid",
                )
            }
        }
        if (m.nodes.bands.maxOf { it.maxLevel } > m.nodes.remainingByLevel.size ||
            m.nodes.bands.maxOf { it.maxLevel } > m.nodes.gatherPerHourByLevel.size ||
            m.nodes.gatherPerHourByLevel.any { it <= 0 }
        ) {
            fail("$where: node tables must cover every node level")
        }
        if (m.march.baseSecPerTile <= 0 || m.march.refSpeed <= 0 || m.march.scoutSecPerTile <= 0) {
            fail("$where: march times must be positive")
        }
        val mr = m.march
        if (mr.baseSlots < 1 || mr.castleLevelPerSlot < 1 || mr.troopCapBase < 1 || mr.maxHeroes < 0) {
            fail("$where: march slots / troop cap must be positive")
        }
        val c = m.combat
        if (c.maxRounds < 1 || c.woundedPct !in 0..100 || c.defenseK <= 0 || c.shieldCapBp !in 0..9999) {
            fail(
                "$where: combat rules are invalid",
            )
        }
        if (m.refreshPeriodSec < 1) fail("$where: refreshPeriodSec must be >= 1")
        if (m.raid.minIntervalSec < 1 || m.raid.maxIntervalSec < m.raid.minIntervalSec) {
            fail(
                "$where: raid intervals are invalid",
            )
        }
        for (mix in listOf(m.barbarians.mix, m.raid.mix, m.rally.allyMix)) {
            if (mix.isEmpty() || mix.values.sum() != PERCENT ||
                mix.keys.any {
                        k ->
                    TroopType.entries.none { it.name.equals(k, true) }
                }
            ) {
                fail("$where: troop mix must use known troop types and sum to 100")
            }
        }
        return m
    }

    private fun troopType(
        name: String,
        where: String,
    ): TroopType =
        TroopType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: fail("$where: unknown troop type $name")

    private fun buildTroops(file: TroopsFile): Map<String, TroopDef> {
        val out = LinkedHashMap<String, TroopDef>()
        for (t in file.troops) {
            if (t.id in out) fail("troops.json: duplicate id ${t.id}")
            val type = troopType(t.type, "troops.json: ${t.id}")
            if (t.tier !in 1..TROOP_TIERS) fail("troops.json: ${t.id} tier out of range")
            if (t.trainSec <= 0) fail("troops.json: ${t.id} trainSec must be > 0")
            if (!nonNegative(t.cost) || t.attack < 0 || t.defense < 0 || t.hp < 0 || t.speed < 0 || t.load < 0 ||
                t.upkeepPer100PerHour < 0 || t.power < 0 || t.unlockBuildingLevel < 1
            ) {
                fail("troops.json: ${t.id} has a negative or invalid value")
            }
            val counter = t.counter?.let { troopType(it, "troops.json: ${t.id} counter") }
            out[t.id] =
                TroopDef(
                    t.id, type, t.tier, t.attack, t.defense, t.hp, t.speed, t.load, t.trainSec, t.cost,
                    t.upkeepPer100PerHour, t.power, t.unlockBuildingLevel, counter,
                )
        }
        for (type in TroopType.entries) {
            val tiers = out.values.filter { it.type == type }.map { it.tier }.sorted()
            if (tiers != (1..TROOP_TIERS).toList()) {
                fail(
                    "troops.json: $type must have tiers 1..$TROOP_TIERS, has $tiers",
                )
            }
        }
        return out
    }

    private fun buildResearch(file: ResearchFile): Map<String, ResearchNodeDef> {
        val out = LinkedHashMap<String, ResearchNodeDef>()
        for (n in file.nodes) {
            if (n.id in out) fail("research.json: duplicate node id ${n.id}")
            if (n.maxLevel < 1 || n.levels.size != n.maxLevel) {
                fail("research.json: ${n.id} has ${n.levels.size} levels, expected ${n.maxLevel}")
            }
            val levels =
                n.levels.mapIndexed { i, l ->
                    if (l.level != i + 1) fail("research.json: ${n.id} levels not contiguous at index $i")
                    if (!nonNegative(l.cost) || l.seconds < 0 || l.power < 0 || l.effect.bp < 0) {
                        fail("research.json: ${n.id} Lv${l.level} has a negative value")
                    }
                    if (l.prereqs.academyLevel < 0) fail("research.json: ${n.id} Lv${l.level} academyLevel is negative")
                    val key =
                        BuffKey.entries.firstOrNull { it.name == l.effect.key }
                            ?: fail("research.json: ${n.id} has unknown buff key ${l.effect.key}")
                    ResearchLevel(
                        l.level,
                        l.cost,
                        l.seconds,
                        l.prereqs.academyLevel,
                        l.prereqs.nodes.map { ResearchPrereq(it.id, it.level) },
                        key,
                        l.effect.bp,
                        l.power,
                    )
                }
            out[n.id] = ResearchNodeDef(n.id, n.branch, n.maxLevel, levels)
        }
        for (n in out.values) {
            for (l in n.levels) {
                for (p in l.nodes) {
                    val target =
                        out[p.nodeId]
                            ?: fail("research.json: ${n.id} Lv${l.level} prereq ${p.nodeId} does not exist")
                    if (p.level < 1 || p.level > target.maxLevel) {
                        fail("research.json: ${n.id} Lv${l.level} prereq ${p.nodeId} Lv${p.level} is out of range")
                    }
                }
            }
        }
        checkNoResearchCycle(out)
        return out
    }

    private fun checkNoResearchCycle(nodes: Map<String, ResearchNodeDef>) {
        val state = HashMap<String, Int>() // 1 = 訪問中, 2 = 完了

        fun visit(id: String) {
            when (state[id]) {
                1 -> fail("research.json: prerequisite cycle through $id")
                2 -> return
            }
            state[id] = 1
            nodes.getValue(id).levels.flatMap { it.nodes }.forEach { visit(it.nodeId) }
            state[id] = 2
        }
        nodes.keys.forEach { visit(it) }
    }

    private fun buildHeroes(
        file: HeroesFile,
        items: Map<String, ItemDef>,
    ): Pair<Map<String, HeroDef>, HeroRules> {
        val r = file.rules
        if (r.expToNext.isEmpty() || r.expToNext.any { it <= 0 }) fail("heroes.json: expToNext must be positive")
        if (r.starLevelCaps.isEmpty() || r.starLevelCaps.zipWithNext().any { (a, b) -> b < a }) {
            fail("heroes.json: starLevelCaps must be non-decreasing")
        }
        if (r.starLevelCaps.last() > r.expToNext.size + 1) fail("heroes.json: starLevelCaps exceeds the exp curve")
        if (r.starUpShards.size != r.starLevelCaps.size - 1 || r.starUpShards.any { it <= 0 }) {
            fail("heroes.json: starUpShards must have ${r.starLevelCaps.size - 1} positive entries")
        }
        if (HeroRarity.entries.any { (r.dupShards[it] ?: -1) < 0 }) {
            fail(
                "heroes.json: dupShards must cover every rarity",
            )
        }
        if (r.maxSkillLevel < 1) fail("heroes.json: maxSkillLevel must be >= 1")
        if (HeroRarity.entries.any {
                (r.power.rarityBase[it] ?: -1) < 0
            } || r.power.perLevel < 0 || r.power.perSkillLevel < 0
        ) {
            fail("heroes.json: power table must cover every rarity with non-negative values")
        }
        val heroes = LinkedHashMap<String, HeroDef>()
        for (h in file.heroes) {
            if (h.id in heroes) fail("heroes.json: duplicate hero id ${h.id}")
            if (h.skills.size != HERO_SKILLS) fail("heroes.json: ${h.id} must have $HERO_SKILLS skills")
            if (h.skills.any { it.values.size != r.maxSkillLevel || it.values.any { v -> v < 0 } }) {
                fail("heroes.json: ${h.id} skill values must have ${r.maxSkillLevel} non-negative entries")
            }
            if (h.baseStats.attack < 0 || h.baseStats.defense < 0 || h.baseStats.hp < 0) {
                fail(
                    "heroes.json: ${h.id} has negative stats",
                )
            }
            heroes[h.id] =
                HeroDef(
                    h.id, h.rarity, troopType(h.troopAffinity, "heroes.json: ${h.id}"),
                    h.baseStats.attack, h.baseStats.defense, h.baseStats.hp,
                    h.skills.map { HeroSkillDef(it.slot, it.effectKey, it.values) },
                )
        }

        fun table(
            name: String,
            t: SummonTableDto,
        ): SummonTable {
            if (t.rates.values.any { it < 0 } || t.rates.values.sum() != BP_TOTAL) {
                fail("heroes.json: summon.$name rates must sum to $BP_TOTAL bp")
            }
            if (t.gold < 0 || t.goldTen < 0 || t.gems < 0 || t.gemsTen < 0 || t.pityAt < 0) {
                fail("heroes.json: summon.$name has a negative value")
            }
            val ticket =
                items[t.ticketItem]
                    ?: fail("heroes.json: summon.$name ticketItem ${t.ticketItem} is not in items.json")
            if (ticket.kind != ItemKind.SUMMON_TICKET_NORMAL && ticket.kind != ItemKind.SUMMON_TICKET_PREMIUM) {
                fail("heroes.json: summon.$name ticketItem ${t.ticketItem} is not a summon ticket")
            }
            for ((rarity, rate) in t.rates) {
                if (rate > 0 &&
                    heroes.values.none {
                        it.rarity == rarity
                    }
                ) {
                    fail("heroes.json: summon.$name has no hero of rarity $rarity")
                }
            }
            if (heroes.values.none { it.rarity >= t.tenGuarantee }) {
                fail(
                    "heroes.json: summon.$name guarantee rarity has no heroes",
                )
            }
            if (t.pityAt > 0 &&
                heroes.values.none {
                    it.rarity == HeroRarity.SSR
                }
            ) {
                fail("heroes.json: summon.$name pity needs an SSR hero")
            }
            return SummonTable(t.gold, t.goldTen, t.gems, t.gemsTen, t.ticketItem, t.rates, t.tenGuarantee, t.pityAt)
        }
        val rules =
            HeroRules(
                expToNext = r.expToNext,
                starLevelCaps = r.starLevelCaps,
                starUpShards = r.starUpShards,
                dupShards = r.dupShards,
                maxSkillLevel = r.maxSkillLevel,
                rarityPower = r.power.rarityBase,
                powerPerLevel = r.power.perLevel,
                powerPerSkillLevel = r.power.perSkillLevel,
                normal = table("normal", r.summon.normal),
                premium = table("premium", r.summon.premium),
            )
        return heroes to rules
    }

    private fun buildTiers(file: SpeedupFile): List<SpeedupTier> {
        if (file.tiers.isEmpty()) fail("speedup.json: tiers must not be empty")
        var prev = 0L
        file.tiers.forEachIndexed { i, t ->
            if (t.gemsPerHour < 0) fail("speedup.json: tier $i has negative gemsPerHour")
            val up = t.upToSec
            val last = i == file.tiers.lastIndex
            if (up == null && !last) fail("speedup.json: only the last tier may have null upToSec")
            if (up != null) {
                if (last) fail("speedup.json: the last tier must have null upToSec")
                if (up <= prev) fail("speedup.json: tier $i upToSec must be increasing")
                prev = up
            }
        }
        return file.tiers.map { SpeedupTier(it.upToSec, it.gemsPerHour) }
    }
}
