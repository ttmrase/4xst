package com.ttmrase.crownfall.core.combat

import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TierStep
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.satMul
import com.ttmrase.crownfall.core.util.SeededRandom

/** 野蛮人・砦・襲撃・援軍・プレイヤー軍の編成（数値は map_rules.json）。 */
object Armies {
    private const val PCT = 100L

    /** 値 [v] に対する Tier（`upTo` 以下の最初の段。超えたら最後）。 */
    fun tierFor(
        steps: List<TierStep>,
        v: Int,
    ): Int = (steps.firstOrNull { v <= it.upTo } ?: steps.last()).tier

    /** [total] 体を [mix]（兵種名 → %）で Tier [tier] の兵に分ける。端数は ID 順で先頭の兵種に足す。 */
    fun split(
        total: Long,
        mix: Map<String, Int>,
        tier: Int,
    ): Map<String, Long> {
        val keys = mix.keys.sorted()
        val out = LinkedHashMap<String, Long>()
        var given = 0L
        for (k in keys) {
            val n = total * mix.getValue(k) / PCT
            out["${k}_t$tier"] = n
            given += n
        }
        val first = "${keys.first()}_t$tier"
        out[first] = out.getValue(first) + (total - given)
        return out.filterValues { it > 0 }
    }

    /** 野蛮人 Lv[level] の軍。 */
    fun barbarian(
        level: Int,
        data: GameData,
    ): Army {
        val r = data.mapRules.barbarians
        val total = r.troopsPerLevelSq * level * level
        return Army(split(total, r.mix, tierFor(r.tierByLevel, level)))
    }

    /** 砦 Lv[level] の軍（野蛮人の相当 Lv の規模 × fortScalePct）。 */
    fun fort(
        level: Int,
        data: GameData,
    ): Army {
        val r = data.mapRules.barbarians
        val eq = r.fortEquivLevels[level - 1]
        val total = r.troopsPerLevelSq * eq * eq * r.fortScalePct / PCT
        return Army(split(total, r.mix, tierFor(r.tierByLevel, eq)))
    }

    /** 城 Lv[castleLevel] への襲撃軍。 */
    fun raid(
        castleLevel: Int,
        data: GameData,
    ): Army {
        val r = data.mapRules.raid
        val total = r.troopsBase + r.troopsPerCastleLevel * castleLevel
        return Army(split(total, r.mix, tierFor(r.tierByCastleLevel, castleLevel)))
    }

    /** ラリーで加わる同盟の仮の援軍（砦 Lv で決まる固定量）。 */
    fun rallyAllies(
        fortLevel: Int,
        data: GameData,
    ): Map<String, Long> {
        val r = data.mapRules.rally
        return split(r.allyTroopsByFortLevel[fortLevel - 1], r.allyMix, r.allyTier)
    }

    /** 英雄のアクティブスキル（Lv 反映）。 */
    fun heroes(
        state: GameState,
        heroIds: List<String>,
        data: GameData,
    ): List<ArmyHero> =
        heroIds.mapNotNull { id ->
            val hero = state.heroes.firstOrNull { it.heroId == id } ?: return@mapNotNull null
            val def = data.heroes[id] ?: return@mapNotNull null
            val idx = def.skills.indexOfFirst { it.slot == "active" }
            if (idx < 0) return@mapNotNull null
            val skill = def.skills[idx]
            val level = hero.skillLevels.getOrElse(idx) { 1 }.coerceIn(1, skill.values.size)
            ArmyHero(id, skill.effectKey, skill.values[level - 1].toLong())
        }

    /** プレイヤーの攻撃軍（出撃兵 + 英雄 + 行軍用バフ）。 */
    fun player(
        state: GameState,
        troops: Map<String, Long>,
        heroIds: List<String>,
        data: GameData,
        extra: Map<String, Long> = emptyMap(),
    ): Army {
        val all = LinkedHashMap(troops)
        for ((k, v) in extra) all.merge(k, v, Long::plus)
        return Army(all, heroes(state, heroIds, data), BuffAggregator.forMarch(state, heroIds, data))
    }

    /** プレイヤーの防衛軍（在城の兵 + 城壁。英雄は出撃していないので参加しない）。 */
    fun homeDefense(
        state: GameState,
        data: GameData,
    ): Army {
        val wall =
            state.slots.filter { it.type == BuildingType.WALL }.maxOfOrNull { it.level } ?: 0
        val hp = data.building(BuildingType.WALL).effect(wall, "wall_hp")
        return Army(
            state.troops.filterValues { it > 0 },
            emptyList(),
            BuffAggregator.compute(state, data),
            hp,
        )
    }

    /** 戦利品（資源 = Lv × 単価 [multiplier] 倍、アイテムは抽選）。[rng] を進める。 */
    fun loot(
        level: Int,
        multiplier: Long,
        data: GameData,
        rng: SeededRandom,
    ): Pair<Resources, Map<String, Int>> {
        val r = data.mapRules.loot
        val unit = r.resourcesPerLevel
        val k = satMul(level.toLong(), multiplier)
        val res = Resources(satMul(unit.food, k), satMul(unit.wood, k), satMul(unit.stone, k), satMul(unit.gold, k))
        val items = LinkedHashMap<String, Int>()
        for (e in r.items) {
            if (rng.nextInt(100) < e.chancePct) items.merge(e.item, e.count, Int::plus)
        }
        return res to items
    }
}
