package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.alliance.AllianceHooks
import com.ttmrase.crownfall.core.alliance.AllianceLogic
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.combat.Armies
import com.ttmrase.crownfall.core.combat.CombatResolver
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.RaidStageRule
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.model.BattleReport
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.RaidStage
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.mulDiv
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.sim.Buffs
import com.ttmrase.crownfall.core.util.SeededRandom

/** NPC の襲撃（予告 → 本番）。時刻は `map.nextRaidEpochSec`（絶対時刻）で、tick の境界になる。 */
object Raids {
    /** 予告のリードタイム秒（Watchtower の Lv に比例、上限あり。Lv0 なら 0 = 予告なし）。 */
    fun warnSec(
        state: GameState,
        data: GameData,
    ): Long {
        val r = data.mapRules.raid
        return minOf(r.maxWarnSec, r.warnSecPerWatchtowerLevel * Marching.levelOf(state, BuildingType.WATCHTOWER))
    }

    /** 倉庫が守る量（各資源。STORAGE_PROTECT を適用）。 */
    fun protectedAmount(
        state: GameState,
        data: GameData,
    ): Long {
        val base =
            state.slots.filter { it.type == BuildingType.WAREHOUSE }
                .fold(0L) { a, s -> satAdd(a, data.building(s.type).effect(s.level, "protect")) }
        return Buffs.applyBp(base, BuffAggregator.compute(state, data)[BuffKey.STORAGE_PROTECT])
    }

    /** 次の境界（予告または本番）。無ければ Long.MAX_VALUE。 */
    fun nextBoundary(
        state: GameState,
        data: GameData,
    ): Long {
        val raid = state.map.nextRaidEpochSec
        if (raid <= 0) return Long.MAX_VALUE
        var next = Long.MAX_VALUE
        val warn = warnSec(state, data)
        if (!state.map.raidWarned && warn > 0 && raid - warn > state.nowEpochSec) next = raid - warn
        if (raid > state.nowEpochSec) next = minOf(next, raid)
        nextStageTime(state, data)?.let { next = minOf(next, it) }
        return next
    }

    /** まだ出していない次の段階の時刻（RESULT は襲撃の解決で出すので含めない）。 */
    private fun nextStageTime(
        state: GameState,
        data: GameData,
    ): Long? {
        val stages = data.notifyRules.raid.stages
        val rule = stages.getOrNull(state.map.raidStageDone) ?: return null
        if (rule.stage == RaidStage.RESULT.name) return null
        val at = state.map.nextRaidEpochSec - rule.offsetSec
        return at.takeIf { it > state.nowEpochSec }
    }

    /**
     * 時刻が来た段階を出す。まとめて複数の段階が来ていた（旧セーブの途中など）ときは最後の 1 つだけ出す。
     * 物見の塔の Lv が足りない段階は見えないので出さない。
     */
    private fun emitStages(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val stages = data.notifyRules.raid.stages
        val raid = state.map.nextRaidEpochSec
        var done = state.map.raidStageDone
        var last: RaidStageRule? = null
        while (done < stages.size) {
            val rule = stages[done]
            if (rule.stage == RaidStage.RESULT.name || raid - rule.offsetSec > state.nowEpochSec) break
            last = rule
            done++
        }
        var s = if (done == state.map.raidStageDone) state else state.copy(map = state.map.copy(raidStageDone = done))
        if (last != null && Marching.levelOf(state, BuildingType.WATCHTOWER) >= last.minWatchtowerLv) {
            val stage = RaidStage.valueOf(last.stage)
            events.add(GameEvent.RaidStageReached(stage, raid))
            s = AllianceLogic.enqueueReactive(s, ChatEvent.RaidStage(stage), listOf("kaede", "gen"), data)
        }
        return s
    }

    internal fun settle(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        var s = state
        if (s.map.nextRaidEpochSec <= 0) {
            val first = satAdd(s.nowEpochSec, data.mapRules.raid.firstDelaySec)
            s = s.copy(map = s.map.copy(nextRaidEpochSec = first, raidWarned = false))
        }
        while (s.map.nextRaidEpochSec != Long.MAX_VALUE && s.nowEpochSec >= s.map.nextRaidEpochSec) {
            s = emitStages(s, data, events)
            s = resolve(s, data, events)
        }
        s = emitStages(s, data, events)
        val warn = warnSec(s, data)
        if (!s.map.raidWarned && warn > 0 && s.nowEpochSec >= s.map.nextRaidEpochSec - warn) {
            events.add(GameEvent.RaidIncoming(s.map.nextRaidEpochSec))
            s = s.copy(map = s.map.copy(raidWarned = true))
        }
        return s
    }

    private fun resolve(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val rules = data.mapRules.raid
        val at = state.map.nextRaidEpochSec
        val raiders = Armies.raid(maxOf(1, Marching.castleLevel(state)), data)
        val home = Armies.homeDefense(state, data)
        val out = CombatResolver.resolve(raiders, home, data)

        var s =
            state.copy(
                troops =
                    state.troops.mapValues { (id, _) -> out.defender.remaining[id] ?: 0L }.filterValues {
                        it > 0
                    },
            )
        val (admitted, overflow) = Hospital.admit(s, out.defender.wounded, data)
        s = admitted
        var plunder = Resources.ZERO
        if (out.attackerWon) {
            val prot = protectedAmount(s, data)
            val exposed = ResourceType.entries.associateWith { maxOf(0L, s.resources[it] - prot) }
            val total = exposed.values.fold(0L, ::satAdd)
            val carry =
                out.attacker.remaining.entries.fold(0L) { a, (id, n) -> satAdd(a, n * data.troops.getValue(id).load) }
            val take = minOf(total, rules.loadCap, carry)
            if (total > 0 && take > 0) {
                for (t in ResourceType.entries) plunder = plunder.with(t, mulDiv(take, exposed.getValue(t), total))
                s = s.copy(resources = s.resources.minusOrNull(plunder) ?: s.resources)
            }
        }
        val report =
            BattleReport(
                kind = BattleKind.RAID,
                attackerWon = out.attackerWon,
                rounds = out.rounds,
                attackerInitial = out.attacker.initial,
                attackerDead =
                    (out.attacker.dead.keys + out.attacker.wounded.keys).associateWith {
                        (out.attacker.dead[it] ?: 0L) + (out.attacker.wounded[it] ?: 0L)
                    },
                attackerWounded = emptyMap(),
                defenderInitial = out.defender.initial,
                defenderDead = out.defender.dead,
                defenderWounded = out.defender.wounded.filterKeys { it !in overflow },
                loot = plunder,
                overflowDead = overflow,
            )
        val mailId = s.nextMailId
        s = MailBox.battle(s, at, report, events)
        events.add(if (out.attackerWon) GameEvent.PlayerDefeated(mailId) else GameEvent.PlayerDefendedRaid(mailId))
        events.add(GameEvent.RaidStageReached(RaidStage.RESULT, at, defended = !out.attackerWon))
        s = AllianceHooks.onRaid(s, playerWon = !out.attackerWon, data = data)
        val rng = SeededRandom(s.map.rngState)
        val gap = rules.minIntervalSec + rng.nextInt((rules.maxIntervalSec - rules.minIntervalSec + 1).toInt())
        s =
            s.copy(
                map =
                    s.map.copy(
                        rngState = rng.state,
                        nextRaidEpochSec = satAdd(at, gap),
                        raidWarned = false,
                        raidStageDone = 0,
                    ),
            )
        return com.ttmrase.crownfall.core.sim.Simulation.syncStorageFlags(s, data)
    }
}
