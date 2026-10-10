package com.ttmrase.crownfall.core.map

import com.ttmrase.crownfall.core.data.MapRules
import com.ttmrase.crownfall.core.util.SeededRandom
import kotlin.math.abs

/** 静的マップ（地形・自城・同盟の城・NPC 王国・砦・聖地）の決定論的な生成。 */
object MapGenerator {
    private const val SALT = 0x4D41_5047L
    private const val PERMILLE = 1000
    private const val MAX_ATTEMPTS = 20_000
    private const val SMOOTH_THRESHOLD = 5
    private const val LONELY_THRESHOLD = 2
    private val cache =
        object : LinkedHashMap<Pair<Long, Int>, WorldMap>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Int>, WorldMap>?): Boolean =
                size > 2
        }

    /** [seed] と [rules] から世界を作る（同じ入力なら常に同じ結果）。 */
    fun generate(
        seed: Long,
        rules: MapRules,
    ): WorldMap {
        val rng = SeededRandom(seed xor SALT)
        val size = rules.size
        val terrain = rollTerrain(rng, rules)
        val occupied = HashSet<Int>()
        val structures = ArrayList<Structure>()

        fun place(s: Structure) {
            occupied.add(s.y * size + s.x)
            structures.add(s)
            terrain[s.y * size + s.x] = Terrain.PLAIN.ordinal.toByte()
        }

        fun free(
            x: Int,
            y: Int,
        ) = x in 0 until size && y in 0 until size && (y * size + x) !in occupied

        fun pickInRing(
            minD: Int,
            maxD: Int,
        ): Pair<Int, Int> {
            repeat(MAX_ATTEMPTS) {
                val dx = rng.nextInt(2 * maxD + 1) - maxD
                val dy = rng.nextInt(2 * maxD + 1) - maxD
                val d = abs(dx) + abs(dy)
                val x = rules.center.x + dx
                val y = rules.center.y + dy
                if (d in minD..maxD && free(x, y)) return x to y
            }
            error("cannot place a structure within distance $minD..$maxD")
        }

        place(Structure(StructureType.PLAYER_CASTLE, rules.center.x, rules.center.y))
        for (i in 0 until rules.allies.personaSlots + rules.allies.fillers) {
            val (x, y) = pickInRing(rules.allies.minRadius, rules.allies.maxRadius)
            val isPersona = i < rules.allies.personaSlots
            val tag = if (isPersona) "persona_slot_$i" else "filler_${i - rules.allies.personaSlots}"
            place(Structure(StructureType.ALLY_CASTLE, x, y, tag = tag))
        }
        for (k in rules.kingdoms) {
            place(Structure(StructureType.NPC_CASTLE, k.x, k.y, tag = k.id, power = k.power))
        }
        place(Structure(StructureType.HOLY_LAND, rules.holyLand.x, rules.holyLand.y))
        for (level in rules.forts.levels) {
            val (x, y) =
                pickInRing(
                    rules.forts.minDistanceByLevel[level - 1],
                    rules.forts.maxDistanceByLevel[level - 1],
                )
            place(Structure(StructureType.BARBARIAN_FORT, x, y, level = level))
        }
        return WorldMap(size, terrain, structures)
    }

    /** [generate] の結果を（直近 2 件まで）使い回す。世界は不変なので共有してよい。 */
    @Synchronized
    fun cached(
        seed: Long,
        rules: MapRules,
    ): WorldMap = cache.getOrPut(seed to rules.hashCode()) { generate(seed, rules) }

    private fun rollTerrain(
        rng: SeededRandom,
        rules: MapRules,
    ): ByteArray {
        val size = rules.size
        val t = rules.terrain
        var cur = ByteArray(size * size)
        for (i in cur.indices) {
            val roll = rng.nextInt(PERMILLE)
            cur[i] =
                when {
                    roll < t.waterPermille -> Terrain.WATER
                    roll < t.waterPermille + t.mountainPermille -> Terrain.MOUNTAIN
                    roll < t.waterPermille + t.mountainPermille + t.forestPermille -> Terrain.FOREST
                    else -> Terrain.PLAIN
                }.ordinal.toByte()
        }
        repeat(t.smoothingPasses) {
            val next = cur.copyOf()
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val counts = IntArray(Terrain.entries.size)
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until size && ny in 0 until size) counts[cur[ny * size + nx].toInt()]++
                        }
                    }
                    val own = cur[y * size + x].toInt()
                    val dominant = Terrain.entries.filter { it != Terrain.PLAIN }.maxByOrNull { counts[it.ordinal] }
                    next[y * size + x] =
                        when {
                            dominant != null && counts[dominant.ordinal] >= SMOOTH_THRESHOLD ->
                                dominant.ordinal.toByte()
                            own != Terrain.PLAIN.ordinal && counts[own] <= LONELY_THRESHOLD ->
                                Terrain.PLAIN.ordinal.toByte()
                            else -> cur[y * size + x]
                        }
                }
            }
            cur = next
        }
        return cur
    }
}
