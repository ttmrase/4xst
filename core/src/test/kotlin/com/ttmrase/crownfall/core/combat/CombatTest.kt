package com.ttmrase.crownfall.core.combat

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.buff.BuffSet
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.util.SeededRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CombatTest {
    private val data = TestSupport.data
    private val rules = data.mapRules.combat

    private fun army(
        vararg t: Pair<String, Long>,
        heroes: List<ArmyHero> = emptyList(),
        buffs: BuffSet = BuffSet(emptyMap()),
        wall: Long = 0,
    ) = Army(mapOf(*t), heroes, buffs, wall)

    private fun lost(
        side: SideResult,
        id: String,
    ) = side.initial.getValue(id) - side.remaining.getValue(id)

    private fun fight(
        a: Army,
        d: Army,
    ) = CombatResolver.resolve(a, d, data)

    @Test
    fun resolutionIsDeterministic() {
        val a = army("infantry_t2" to 500, "archer_t2" to 300)
        val d = army("cavalry_t2" to 600, "infantry_t2" to 100)
        assertEquals(fight(a, d), fight(a, d))
    }

    @Test
    fun emptyArmies() {
        val some = army("infantry_t1" to 10)
        val none = army()
        val r1 = fight(some, none)
        assertTrue(r1.attackerWon)
        assertEquals(0, r1.rounds)
        val r2 = fight(none, some)
        assertFalse(r2.attackerWon)
        assertFalse(fight(none, none).attackerWon)
    }

    @Test
    fun overwhelmingForceWinsAndWeakLoses() {
        val big = army("infantry_t2" to 5000)
        val small = army("infantry_t2" to 100)
        assertTrue(fight(big, small).attackerWon)
        assertFalse(fight(small, big).attackerWon)
    }

    @Test
    fun lossesSplitIntoWoundedAndDeadWithoutLeak() {
        val r = fight(army("infantry_t2" to 1000), army("infantry_t2" to 1000, "archer_t2" to 500))
        for (side in listOf(r.attacker, r.defender)) {
            for ((id, n) in side.initial) {
                val rem = side.remaining.getValue(id)
                val w = side.wounded[id] ?: 0
                val dead = side.dead[id] ?: 0
                assertEquals(n, rem + w + dead, id)
                assertEquals((n - rem) * rules.woundedPct / 100, w, id)
            }
        }
        assertTrue(r.rounds in 1..rules.maxRounds)
    }

    @Test
    fun matchupCoefficients() {
        val inf = data.troops.getValue("infantry_t1")
        val cav = data.troops.getValue("cavalry_t1")
        val arc = data.troops.getValue("archer_t1")
        val sge = data.troops.getValue("siege_t1")
        // infantry > cavalry > archer > infantry
        assertEquals(rules.advantageBp, CombatResolver.matchupBp(inf, cav, rules))
        assertEquals(rules.advantageBp, CombatResolver.matchupBp(cav, arc, rules))
        assertEquals(rules.advantageBp, CombatResolver.matchupBp(arc, inf, rules))
        assertEquals(rules.disadvantageBp, CombatResolver.matchupBp(cav, inf, rules))
        assertEquals(rules.siegeVsTroopsBp, CombatResolver.matchupBp(sge, inf, rules))
        assertEquals(10_000L, CombatResolver.matchupBp(inf, inf, rules))
    }

    @Test
    fun advantageKillsMoreThanDisadvantage() {
        // 同数の歩兵 vs 騎兵: 歩兵（有利）が攻撃側のとき、騎兵の損耗の方が大きい
        val r = fight(army("infantry_t2" to 1000), army("cavalry_t2" to 1000))
        val aLost = lost(r.attacker, "infantry_t2")
        val dLost = lost(r.defender, "cavalry_t2")
        assertTrue(dLost > aLost, "dLost=$dLost aLost=$aLost")
    }

    @Test
    fun siegeIsWeakAgainstTroopsButStrongAgainstWall() {
        val target = army("infantry_t2" to 1000)
        val siegeFirst = fight(army("siege_t2" to 300), target)
        val infFirst = fight(army("infantry_t2" to 300), target)
        val sLost = lost(siegeFirst.defender, "infantry_t2")
        val iLost = lost(infFirst.defender, "infantry_t2")
        // 攻撃力が同程度なら、対兵 60% の攻城は歩兵より与ダメが小さい
        val sAtk = data.troops.getValue("siege_t2").attack
        val iAtk = data.troops.getValue("infantry_t2").attack
        assertTrue(sLost * iAtk < iLost * sAtk || sLost <= iLost, "siege=$sLost inf=$iLost")
        // 城壁に対しては攻城の方が早く壁を削る
        val wallDef = army("infantry_t2" to 1000, wall = 1_000_000)
        val wSiege = fight(army("siege_t2" to 300), wallDef).defender.wallHpLeft
        val wInf = fight(army("infantry_t2" to 300), wallDef).defender.wallHpLeft
        assertTrue(wSiege * iAtk < wInf * sAtk, "wallLeft siege=$wSiege inf=$wInf")
    }

    @Test
    fun wallReducesDefenderLossesAndIsConsumed() {
        val a = army("infantry_t2" to 1500)
        val bare = fight(a, army("infantry_t2" to 1000))
        val walled = fight(a, army("infantry_t2" to 1000, wall = 200_000))
        assertTrue(lost(walled.defender, "infantry_t2") <= lost(bare.defender, "infantry_t2"))
        assertTrue(walled.defender.wallHpLeft < 200_000)
        // 壊れた壁は 0 で止まる
        val tiny = fight(a, army("infantry_t2" to 1000, wall = 10))
        assertEquals(0, tiny.defender.wallHpLeft)
    }

    @Test
    fun roundCapIsRespectedAndTiesGoToTheDefender() {
        val x = army("infantry_t4" to 100_000)
        val r = fight(x, x)
        assertTrue(r.rounds <= rules.maxRounds)
        assertFalse(r.attackerWon) // 完全に同条件ならタイ → 防御側
    }

    @Test
    fun buffsChangeTheOutcome() {
        val base = army("infantry_t2" to 1000)
        val foe = army("infantry_t2" to 1000)
        val plain = fight(base, foe)
        val buffed =
            fight(base.copy(buffs = BuffSet(mapOf(BuffKey.TROOP_ATTACK to 5000L, BuffKey.TROOP_HP to 5000L))), foe)
        assertTrue(lost(buffed.attacker, "infantry_t2") < lost(plain.attacker, "infantry_t2"))
        assertTrue(lost(buffed.defender, "infantry_t2") > lost(plain.defender, "infantry_t2"))
    }

    @Test
    fun heroActiveSkillsOnlyActAtTheConfiguredRound() {
        val a = army("archer_t2" to 800)
        val d = army("infantry_t2" to 800)
        val plain = fight(a, d)
        val volley = fight(a.copy(heroes = listOf(ArmyHero("h", "volley_bp", 20_000))), d)
        assertTrue(
            volley.defender.remaining.getValue("infantry_t2") <= plain.defender.remaining.getValue("infantry_t2"),
        )
        // 騎兵向けの突撃は弓兵には効かない
        val charge = fight(a.copy(heroes = listOf(ArmyHero("h", "charge_bp", 20_000))), d)
        assertEquals(plain, charge)
        val shield = fight(a, d.copy(heroes = listOf(ArmyHero("h", "shield_bp", 5_000))))
        assertTrue(
            shield.defender.remaining.getValue("infantry_t2") >= plain.defender.remaining.getValue("infantry_t2"),
        )
        // activeSkillRound 以降に決着がつかない小競り合いでは効果が出ない
        val tiny = fight(army("infantry_t1" to 5), army("infantry_t1" to 1))
        val tinyHero =
            fight(
                army("infantry_t1" to 5, heroes = listOf(ArmyHero("h", "burst_damage_bp", 30_000))),
                army("infantry_t1" to 1),
            )
        assertEquals(tiny.attackerWon, tinyHero.attackerWon)
    }

    @Test
    fun armyBuilders() {
        val b5 = Armies.barbarian(5, data)
        assertEquals(rules.let { data.mapRules.barbarians.troopsPerLevelSq * 25 }, b5.troops.values.sum())
        val tier = Armies.tierFor(data.mapRules.barbarians.tierByLevel, 5)
        assertTrue(b5.troops.keys.all { it.endsWith("_t$tier") })
        val fort = Armies.fort(3, data)
        val eq = data.mapRules.barbarians.fortEquivLevels[2]
        assertEquals(data.mapRules.barbarians.troopsPerLevelSq * eq * eq * 3, fort.troops.values.sum())
        val raid = Armies.raid(4, data)
        assertEquals(
            data.mapRules.raid.troopsBase + 4 * data.mapRules.raid.troopsPerCastleLevel,
            raid.troops.values.sum(),
        )
        val allies = Armies.rallyAllies(2, data)
        assertEquals(data.mapRules.rally.allyTroopsByFortLevel[1], allies.values.sum())
        // 端数が出ても総数は保たれる
        val mix = mapOf("infantry" to 34, "archer" to 33, "cavalry" to 33)
        assertEquals(103L, Armies.split(103, mix, 1).values.sum())
    }

    @Test
    fun lootScalesWithLevelAndIsDeterministic() {
        val (r1, i1) = Armies.loot(3, 1, data, SeededRandom(9))
        val (r2, i2) = Armies.loot(3, 1, data, SeededRandom(9))
        assertEquals(r1, r2)
        assertEquals(i1, i2)
        val unit = data.mapRules.loot.resourcesPerLevel
        assertEquals(unit.food * 3, r1.food)
        val (rf, _) = Armies.loot(3, data.mapRules.loot.fortMultiplier, data, SeededRandom(9))
        assertEquals(unit.food * 3 * data.mapRules.loot.fortMultiplier, rf.food)
    }

    @Test
    fun marchBuffsIncludeHeroPassivesAndActiveSkillsFollowLevels() {
        val s =
            TestSupport.newGame().let {
                it.copy(heroes = it.heroes + HeroState("hero_brann", skillLevels = listOf(2, 3, 1)))
            }
        val base = BuffAggregator.compute(s, data)
        val march = BuffAggregator.forMarch(s, listOf("hero_brann", "hero_unknown"), data)
        // brann: パッシブ 2 番目 = march_speed(Lv3: 600)、3 番目 = troop_attack(Lv1: 300)
        assertEquals(base[BuffKey.MARCH_SPEED] + 600, march[BuffKey.MARCH_SPEED])
        assertEquals(base[BuffKey.TROOP_ATTACK] + 300, march[BuffKey.TROOP_ATTACK])
        val a = Armies.player(s, mapOf("cavalry_t1" to 10), listOf("hero_brann"), data, mapOf("cavalry_t1" to 5))
        assertEquals(15L, a.troops.getValue("cavalry_t1"))
        assertEquals(listOf(ArmyHero("hero_brann", "charge_bp", 14_100)), a.heroes)
        assertTrue(Armies.heroes(s, listOf("hero_none"), data).isEmpty())
    }

    @Test
    fun homeDefenseUsesTroopsAtHomeAndTheWall() {
        val s = TestSupport.advanced(level = 3).let { it.copy(troops = mapOf("infantry_t1" to 50, "archer_t1" to 0)) }
        val d = Armies.homeDefense(s, data)
        assertEquals(mapOf("infantry_t1" to 50L), d.troops)
        assertTrue(d.wallHp > 0)
    }
}
