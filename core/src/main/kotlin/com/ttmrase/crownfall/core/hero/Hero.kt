package com.ttmrase.crownfall.core.hero

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.HeroDef
import com.ttmrase.crownfall.core.data.ItemKind
import com.ttmrase.crownfall.core.data.SummonTable
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.model.SummonEntry
import com.ttmrase.crownfall.core.model.SummonResult
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul
import com.ttmrase.crownfall.core.util.SeededRandom

/** 召喚の種類。 */
enum class SummonKind { NORMAL, PREMIUM }

/** 初期英雄の付与（純関数・冪等）。M2 のセーブ（heroes が空で starterHeroId だけある）の補完にも使う。 */
object HeroBootstrap {
    /** [GameState.starterHeroId] の英雄を所持していなければ Lv1・★1 で付与する。 */
    fun ensureStarter(state: GameState): GameState {
        val id = state.starterHeroId ?: return state
        if (state.heroes.any { it.heroId == id }) return state
        return state.copy(heroes = state.heroes + HeroState(id))
    }
}

/** 英雄の戦力。 */
object HeroPower {
    /** 所持英雄の戦力合計 = Σ(rarityBase × ★ + Lv × perLevel + Σスキル Lv × perSkillLevel)。 */
    fun total(
        state: GameState,
        data: GameData,
    ): Long {
        val rules = data.heroRules
        return state.heroes.fold(0L) { acc, h ->
            val def = data.heroes[h.heroId] ?: return@fold acc
            val base = satMul(rules.rarityPower.getValue(def.rarity), h.star.toLong())
            val level = satMul(h.level.toLong(), rules.powerPerLevel)
            val skills = satMul(h.skillLevels.sumOf { it.toLong() }, rules.powerPerSkillLevel)
            satAdd(acc, satAdd(base, satAdd(level, skills)))
        }
    }
}

/** 召喚の抽選（SeededRandom で決定論的）。 */
object Summoner {
    private const val BP_TOTAL = 10_000
    private val rarityOrder = listOf(HeroRarity.SSR, HeroRarity.SR, HeroRarity.R, HeroRarity.N)

    /** [kind] の設定。 */
    fun table(
        data: GameData,
        kind: SummonKind,
    ): SummonTable = if (kind == SummonKind.NORMAL) data.heroRules.normal else data.heroRules.premium

    /** 天井まであと何回か（天井なしの種類は null）。 */
    fun untilPity(
        state: GameState,
        data: GameData,
        kind: SummonKind = SummonKind.PREMIUM,
    ): Int? = table(data, kind).pityAt.takeIf { it > 0 }?.let { it - state.summonPity }

    /** 抽選結果。[state] は召喚後の状態（コストは含まない）。 */
    class Drawn(
        val state: GameState,
        val result: SummonResult,
    )

    /** [times] 回引く。コストの支払いは呼び出し側（Actions.summon）が済ませている前提。 */
    fun draw(
        state: GameState,
        kind: SummonKind,
        times: Int,
        data: GameData,
    ): Drawn {
        val table = table(data, kind)
        val rng = SeededRandom(state.rngState)
        var pity = state.summonPity
        val rarities = ArrayList<HeroRarity>()
        val picked = ArrayList<HeroDef>()
        repeat(times) {
            val roll = rng.nextInt(BP_TOTAL)
            val forced = table.pityAt > 0 && pity + 1 >= table.pityAt
            val rarity = if (forced) HeroRarity.SSR else rarityOf(roll, table)
            if (table.pityAt > 0) pity = if (rarity == HeroRarity.SSR) 0 else pity + 1
            rarities.add(rarity)
            picked.add(pick(rarity, rng, data))
        }
        if (times >= TEN && rarities.none { it >= table.tenGuarantee }) {
            rarities[times - 1] = table.tenGuarantee
            picked[times - 1] = pick(table.tenGuarantee, rng, data)
        }
        var heroes = state.heroes
        var shards = state.heroShards
        val entries =
            picked.map { def ->
                if (heroes.any { it.heroId == def.id }) {
                    val gain = data.heroRules.dupShards.getValue(def.rarity)
                    shards = shards + (def.id to (shards[def.id] ?: 0) + gain)
                    SummonEntry(def.id, def.rarity, isNew = false, shards = gain)
                } else {
                    heroes = heroes + HeroState(def.id)
                    SummonEntry(def.id, def.rarity, isNew = true, shards = 0)
                }
            }
        val after = state.copy(heroes = heroes, heroShards = shards, summonPity = pity, rngState = rng.state)
        return Drawn(after, SummonResult(entries, pity))
    }

    private const val TEN = 10

    private fun rarityOf(
        roll: Int,
        table: SummonTable,
    ): HeroRarity {
        var acc = 0
        for (r in rarityOrder) {
            acc += table.rates[r] ?: 0
            if (roll < acc) return r
        }
        return HeroRarity.N
    }

    private fun pick(
        rarity: HeroRarity,
        rng: SeededRandom,
        data: GameData,
    ): HeroDef {
        val pool = data.heroes.values.filter { it.rarity == rarity }
        return pool[rng.nextInt(pool.size)]
    }
}

/** 英雄の育成（経験値・星・スキル）。失敗の判定は Actions 側。 */
object HeroGrowth {
    /** 星 [star] のレベル上限。 */
    fun levelCap(
        star: Int,
        data: GameData,
    ): Int = data.heroRules.starLevelCaps[star.coerceIn(1, data.heroRules.starLevelCaps.size) - 1]

    /** [hero] に [exp] を加える。上限レベルで止め、余りは捨てる。 */
    fun addExp(
        hero: HeroState,
        exp: Long,
        data: GameData,
    ): HeroState {
        val cap = levelCap(hero.star, data)
        var level = hero.level
        var cur = satAdd(hero.exp, exp)
        while (level < cap) {
            val need = data.heroRules.expToNext[level - 1]
            if (cur < need) break
            cur -= need
            level++
        }
        return hero.copy(level = level, exp = if (level >= cap) 0 else cur)
    }

    /** 経験値アイテムの種類か。 */
    fun isExpItem(
        id: String,
        data: GameData,
    ): Boolean = data.items[id]?.kind == ItemKind.HERO_EXP
}
