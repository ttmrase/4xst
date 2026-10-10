package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.model.BuildingType
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
private class ItemDto(val id: String, val category: String, val seconds: Long)

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

    private const val STARTER_HERO_COUNT = 3

    private val json = Json { ignoreUnknownKeys = true }

    /** [jsonByName]（キーはファイル名）から [GameData] を作る。違反は [GameDataException]。 */
    fun parse(jsonByName: Map<String, String>): GameData {
        val buildings = decode<BuildingsFile>(jsonByName, BUILDINGS)
        val rules = decode<RulesDto>(jsonByName, RULES)
        val items = decode<ItemsFile>(jsonByName, ITEMS)
        val speedup = decode<SpeedupFile>(jsonByName, SPEEDUP)
        val defs = buildBuildings(buildings)
        val itemDefs = buildItems(items)
        return GameData(
            buildings = defs,
            rules = buildRules(rules, defs, itemDefs),
            items = itemDefs,
            speedupTiers = buildTiers(speedup),
        )
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
    ): Rules {
        if (dto.offlineCapSec < 0) fail("rules.json: offlineCapSec must be >= 0")
        if (dto.catchUpChunkSec < 1) fail("rules.json: catchUpChunkSec must be >= 1")
        if (dto.baseBuildQueues < 1) fail("rules.json: baseBuildQueues must be >= 1")
        if (dto.offlineSummaryMinSec < 0) fail("rules.json: offlineSummaryMinSec must be >= 0")
        if (dto.starterHeroes.size != STARTER_HERO_COUNT || dto.starterHeroes.toSet().size != STARTER_HERO_COUNT) {
            fail("rules.json: starterHeroes must have $STARTER_HERO_COUNT distinct ids")
        }
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
            newGame = NewGameRules(ng.resources, ng.gems, ng.initialLevels, ng.items),
        )
    }

    private fun buildItems(file: ItemsFile): Map<String, ItemDef> {
        val out = LinkedHashMap<String, ItemDef>()
        for (dto in file.items) {
            if (dto.id in out) fail("items.json: duplicate item id ${dto.id}")
            if (dto.seconds <= 0) fail("items.json: ${dto.id} seconds must be > 0")
            val category =
                SpeedupCategory.entries.firstOrNull { it.name.equals(dto.category, ignoreCase = true) }
                    ?: fail("items.json: ${dto.id} has unknown category ${dto.category}")
            out[dto.id] = ItemDef(dto.id, category, dto.seconds)
        }
        return out
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
