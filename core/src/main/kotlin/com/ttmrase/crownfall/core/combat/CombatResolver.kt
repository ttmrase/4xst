package com.ttmrase.crownfall.core.combat

import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.buff.BuffSet
import com.ttmrase.crownfall.core.data.CombatRules
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TroopDef
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul

/** 戦闘に参加する英雄のアクティブスキル（[effectKey] は burst_damage_bp / volley_bp / charge_bp / shield_bp）。 */
data class ArmyHero(val heroId: String, val effectKey: String, val valueBp: Long)

/** 戦闘の片側。[troops] は兵 ID → 数、[wallHp] は防御側だけが持つ城壁の耐久。 */
data class Army(
    val troops: Map<String, Long>,
    val heroes: List<ArmyHero> = emptyList(),
    val buffs: BuffSet = BuffSet(emptyMap()),
    val wallHp: Long = 0,
)

/** 片側の結果。[lost] = [wounded] + [dead]。 */
data class SideResult(
    val initial: Map<String, Long>,
    val remaining: Map<String, Long>,
    val wounded: Map<String, Long>,
    val dead: Map<String, Long>,
    val wallHpLeft: Long,
) {
    val lost: Map<String, Long>
        get() = (wounded.keys + dead.keys).associateWith { (wounded[it] ?: 0L) + (dead[it] ?: 0L) }
}

/** 戦闘結果。[attackerWon] はラウンド上限まで決着しない場合は残存戦力の大小で決める（同値は防御側の勝ち）。 */
data class BattleOutcome(
    val attackerWon: Boolean,
    val rounds: Int,
    val attacker: SideResult,
    val defender: SideResult,
)

/**
 * 戦闘解決（純関数・乱数なし・整数のみ）。
 * 1 ラウンド = 双方が同時に与ダメージを計算 → 相手の兵種へ数の比率で配分（相性 bp）→ 防御で減衰 → HP で割って損耗。
 * 損耗の端数は兵種ごとに次ラウンドへ繰り越す。
 */
object CombatResolver {
    private const val BP_ONE = 10_000L
    private const val PCT = 100L

    private class Squad(
        val def: TroopDef,
        var count: Long,
        val atk: Long,
        val defense: Long,
        val hp: Long,
    ) {
        var carry = 0L
    }

    private class Side(
        val army: Army,
        val units: List<Squad>,
        var wallHp: Long,
    ) {
        val total: Long get() = units.fold(0L) { a, u -> satAdd(a, u.count) }
    }

    private fun scaled(
        base: Long,
        bp: Long,
    ): Long = satMul(base, maxOf(1L, BP_ONE + bp)) / BP_ONE

    private fun build(
        army: Army,
        data: GameData,
    ): Side {
        val units =
            army.troops.entries.filter { it.value > 0 }.sortedBy { it.key }.map { (id, n) ->
                val d = data.troops.getValue(id)
                Squad(
                    d,
                    n,
                    scaled(d.attack, army.buffs[BuffKey.TROOP_ATTACK]),
                    scaled(d.defense, army.buffs[BuffKey.TROOP_DEFENSE]),
                    maxOf(1L, scaled(d.hp, army.buffs[BuffKey.TROOP_HP])),
                )
            }
        return Side(army, units, army.wallHp)
    }

    /** 攻撃側の兵種 [a] から防御側の兵種 [d] への相性 bp。 */
    fun matchupBp(
        a: TroopDef,
        d: TroopDef,
        rules: CombatRules,
    ): Long =
        when {
            a.type == TroopType.SIEGE -> if (d.type == TroopType.SIEGE) BP_ONE else rules.siegeVsTroopsBp
            a.counter == d.type -> rules.advantageBp
            d.counter == a.type -> rules.disadvantageBp
            else -> BP_ONE
        }

    private fun activeBonusBp(
        side: Side,
        unit: Squad,
        round: Int,
        rules: CombatRules,
    ): Long {
        if (round != rules.activeSkillRound) return BP_ONE
        var bp = BP_ONE
        for (h in side.army.heroes) {
            val applies =
                when (h.effectKey) {
                    "burst_damage_bp" -> true
                    "volley_bp" -> unit.def.type == TroopType.ARCHER
                    "charge_bp" -> unit.def.type == TroopType.CAVALRY
                    else -> false
                }
            if (applies) bp = bp * h.valueBp / BP_ONE
        }
        return bp
    }

    private fun shieldBp(
        side: Side,
        round: Int,
        rules: CombatRules,
    ): Long {
        if (round != rules.activeSkillRound) return 0
        val sum = side.army.heroes.filter { it.effectKey == "shield_bp" }.sumOf { it.valueBp }
        return minOf(sum, 9000L)
    }

    /** [attacker] が [defender] を攻撃した結果。 */
    fun resolve(
        attacker: Army,
        defender: Army,
        data: GameData,
    ): BattleOutcome {
        val rules = data.mapRules.combat
        val a = build(attacker, data)
        val d = build(defender, data)
        var rounds = 0
        while (rounds < rules.maxRounds && a.total > 0 && d.total > 0) {
            rounds++
            val lossesOnD = round(a, d, rounds, rules)
            val lossesOnA = round(d, a, rounds, rules)
            apply(d, lossesOnD)
            apply(a, lossesOnA)
        }
        val won =
            when {
                a.total == 0L -> false
                d.total == 0L -> true
                else -> power(a) > power(d)
            }
        return BattleOutcome(won, rounds, result(a, rules.woundedPct), result(d, rules.woundedPct))
    }

    private fun power(s: Side): Long = s.units.fold(0L) { acc, u -> satAdd(acc, satMul(u.count, u.def.power)) }

    /** [from] が [to] に与えるダメージを [to] の各兵種への HP 損耗（体数 + 繰越）として返す。城壁の耐久は [to] 側で減る。 */
    private fun round(
        from: Side,
        to: Side,
        round: Int,
        rules: CombatRules,
    ): LongArray {
        val totalTo = to.total
        val dmgTo = LongArray(to.units.size)
        val shield = BP_ONE - shieldBp(to, round, rules)
        var wall = to.wallHp
        for (u in from.units) {
            if (u.count <= 0) continue
            val raw = satMul(u.count, satMul(u.atk, activeBonusBp(from, u, round, rules))) / BP_ONE
            to.units.forEachIndexed { j, t ->
                if (t.count <= 0) return@forEachIndexed
                val part = satMul(raw, t.count) / totalTo
                var hit = part * matchupBp(u.def, t.def, rules) / BP_ONE * shield / BP_ONE
                if (wall > 0) {
                    val absorbed = hit * rules.wallAbsorbBp / BP_ONE
                    val wallBp = if (u.def.type == TroopType.SIEGE) rules.siegeVsWallBp else BP_ONE
                    val wallDmg = absorbed * wallBp / BP_ONE
                    if (wallDmg <= wall) {
                        wall -= wallDmg
                        hit -= absorbed
                    } else {
                        val covered = absorbed * wall / wallDmg
                        wall = 0
                        hit -= covered
                    }
                }
                dmgTo[j] = satAdd(dmgTo[j], hit)
            }
        }
        to.wallHp = wall
        return LongArray(to.units.size) { j ->
            val t = to.units[j]
            dmgTo[j] * rules.defenseK / (rules.defenseK + t.defense)
        }
    }

    private fun apply(
        side: Side,
        effective: LongArray,
    ) {
        side.units.forEachIndexed { i, u ->
            if (u.count <= 0) return@forEachIndexed
            val pool = satAdd(effective[i], u.carry)
            val kills = pool / u.hp
            if (kills >= u.count) {
                u.count = 0
                u.carry = 0
            } else {
                u.count -= kills
                u.carry = pool % u.hp
            }
        }
    }

    private fun result(
        s: Side,
        woundedPct: Long,
    ): SideResult {
        val initial = s.army.troops.filterValues { it > 0 }
        val remaining = HashMap<String, Long>()
        val wounded = HashMap<String, Long>()
        val dead = HashMap<String, Long>()
        for (u in s.units) {
            val id = u.def.id
            val lost = initial.getValue(id) - u.count
            remaining[id] = u.count
            val w = lost * woundedPct / PCT
            if (w > 0) wounded[id] = w
            if (lost - w > 0) dead[id] = lost - w
        }
        return SideResult(initial, remaining, wounded, dead, s.wallHp)
    }
}
