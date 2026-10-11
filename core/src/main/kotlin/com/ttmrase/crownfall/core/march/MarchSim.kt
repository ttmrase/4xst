package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.alliance.AllianceHooks
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.combat.Armies
import com.ttmrase.crownfall.core.combat.CombatResolver
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.hero.HeroGrowth
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.model.BattleReport
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.mulDiv
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul
import com.ttmrase.crownfall.core.sim.Buffs
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.util.SeededRandom

/** 行軍の段階遷移（到着 → 採集 / 戦闘 / 偵察 → 帰還）。時刻は各段階の絶対時刻 [March.phaseEndEpochSec]。 */
internal object MarchSim {
    private const val SECONDS_PER_HOUR = 3600L

    /** 現在時刻までに期限を迎えた行軍の段階を、期限の早い順（同時刻は id 順）にすべて進める。 */
    fun settle(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        var s = state
        while (true) {
            val m =
                s.marches.filter { it.phaseEndEpochSec <= s.nowEpochSec }
                    .minWithOrNull(compareBy({ it.phaseEndEpochSec }, { it.id })) ?: return s
            s = advance(s, m, data, events)
        }
    }

    private fun replace(
        s: GameState,
        m: March,
    ): GameState = s.copy(marches = s.marches.map { if (it.id == m.id) m else it })

    private fun home(
        s: GameState,
        m: March,
    ): GameState =
        replace(
            s,
            m.copy(
                phase = MarchPhase.RETURNING,
                phaseStartEpochSec = m.phaseEndEpochSec,
                phaseEndEpochSec = satAdd(m.phaseEndEpochSec, maxOf(1L, m.travelSec)),
            ),
        )

    private fun advance(
        s: GameState,
        m: March,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState =
        when (m.phase) {
            MarchPhase.WAITING ->
                replace(
                    s,
                    m.copy(
                        phase = MarchPhase.OUTBOUND,
                        phaseStartEpochSec = m.phaseEndEpochSec,
                        phaseEndEpochSec = satAdd(m.phaseEndEpochSec, maxOf(1L, m.travelSec)),
                    ),
                )
            MarchPhase.WORKING ->
                home(s, m.copy(loot = m.workType?.let { Resources.ZERO.with(it, m.workAmount) } ?: Resources.ZERO))
            MarchPhase.RETURNING -> complete(s, m, data, events)
            MarchPhase.OUTBOUND -> arrive(s, m, data, events)
        }

    private fun arrive(
        s: GameState,
        m: March,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState =
        when (m.kind) {
            MarchKind.GATHER -> arriveGather(s, m, data, events)
            MarchKind.SCOUT -> arriveScout(s, m, data, events)
            MarchKind.ATTACK, MarchKind.RALLY -> fight(s, m, data, events)
        }

    private fun gone(
        s: GameState,
        m: March,
        code: String,
        events: MutableList<GameEvent>,
    ): GameState =
        home(
            MailBox.system(
                s,
                m.phaseEndEpochSec,
                code,
                mapOf("x" to m.target.x.toLong(), "y" to m.target.y.toLong()),
                events,
            ),
            m,
        )

    private fun arriveGather(
        s: GameState,
        m: March,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val node = s.map.nodes.firstOrNull { it.x == m.target.x && it.y == m.target.y }
        val busy = s.marches.any { it.id != m.id && it.phase == MarchPhase.WORKING && it.target == m.target }
        if (node == null || node.remaining <= 0) return gone(s, m, "march_target_gone", events)
        if (busy) return gone(s, m, "gather_node_busy", events)
        val amount = minOf(m.capacity, node.remaining)
        if (amount <= 0) return gone(s, m, "march_target_gone", events)
        val bp = BuffAggregator.forMarch(s, m.heroIds, data)[BuffKey.GATHER_SPEED]
        val rate = maxOf(1L, Buffs.applyBp(data.mapRules.nodes.gatherPerHourByLevel[node.level - 1], bp))
        val duration = maxOf(1L, (satMul(amount, SECONDS_PER_HOUR) + rate - 1) / rate)
        val left = node.remaining - amount
        val nodes =
            s.map.nodes.mapNotNull {
                when {
                    it != node -> it
                    left > 0 -> it.copy(remaining = left)
                    else -> null
                }
            }
        val working =
            m.copy(
                phase = MarchPhase.WORKING,
                phaseStartEpochSec = m.phaseEndEpochSec,
                phaseEndEpochSec = satAdd(m.phaseEndEpochSec, duration),
                workType = node.type,
                workAmount = amount,
            )
        val armed = if (left > 0) s else WorldSim.arm(s, m.phaseEndEpochSec, data)
        return replace(armed.copy(map = armed.map.copy(nodes = nodes)), working)
    }

    private fun arriveScout(
        s: GameState,
        m: March,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val report = Scouting.report(s, m.target, data) ?: return gone(s, m, "march_target_gone", events)
        val scouted = AllianceHooks.onScouted(MailBox.scout(s, m.phaseEndEpochSec, report, events), m.target, data)
        return home(scouted, m)
    }

    private fun capLoot(
        loot: Resources,
        capacity: Long,
    ): Resources {
        val total = ResourceType.entries.fold(0L) { a, t -> satAdd(a, loot[t]) }
        if (total <= capacity) return loot
        var out = Resources.ZERO
        for (t in ResourceType.entries) out = out.with(t, mulDiv(loot[t], capacity, total))
        return out
    }

    private fun fight(
        s0: GameState,
        m: March,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val rules = data.mapRules
        val isFort = m.kind == MarchKind.RALLY
        val at = m.phaseEndEpochSec
        val barb = s0.map.barbarians.firstOrNull { it.x == m.target.x && it.y == m.target.y }
        val fort =
            Marching.world(s0, data).structureAt(m.target.x, m.target.y)
                ?.takeIf { it.type == StructureType.BARBARIAN_FORT }
        val level = (if (isFort) fort?.level else barb?.level) ?: return gone(s0, m, "march_target_gone", events)
        val defender = if (isFort) Armies.fort(level, data) else Armies.barbarian(level, data)
        val allies =
            when {
                m.hostId != 0L -> m.allyTroops
                isFort -> Armies.rallyAllies(level, data)
                else -> emptyMap()
            }
        val out = CombatResolver.resolve(Armies.player(s0, m.troops, m.heroIds, data, allies), defender, data)

        // 同盟の仮の援軍は同じ兵種に混ざって戦うので、自軍の損耗は兵数比で按分する
        val remaining = LinkedHashMap<String, Long>()
        val wounded = LinkedHashMap<String, Long>()
        val dead = LinkedHashMap<String, Long>()
        for ((id, own) in m.troops.toSortedMap()) {
            if (own <= 0) continue
            val total = own + (allies[id] ?: 0L)
            val lost = (out.attacker.initial.getValue(id) - out.attacker.remaining.getValue(id)) * own / total
            val w = (out.attacker.wounded[id] ?: 0L) * own / total
            if (own - lost > 0) remaining[id] = own - lost
            if (w > 0) wounded[id] = w
            if (lost - w > 0) dead[id] = lost - w
        }
        var s = s0
        var loot = Resources.ZERO
        var items: Map<String, Int> = emptyMap()
        var exp = 0L
        if (isFort && !out.attackerWon) {
            // ラリーは負けても（全滅しても）参加報酬が出る。上限は出撃した部隊の積載量
            val bonus = rules.rally.participationResourcesPerLevel * level.toLong()
            loot = capLoot(bonus, Marching.capacity(s, m.troops, m.heroIds, data))
        }
        if (out.attackerWon) {
            events.add(GameEvent.HuntWon(isFort, level))
            val rng = SeededRandom(s.rngState)
            val (res, drops) = Armies.loot(level, if (isFort) rules.loot.fortMultiplier else 1L, data, rng)
            s = s.copy(rngState = rng.state)
            val bonus = if (isFort) rules.rally.participationResourcesPerLevel * level.toLong() else Resources.ZERO
            loot = capLoot(res + bonus, Marching.capacity(s, remaining, m.heroIds, data))
            items = drops
            exp = satMul(rules.barbarians.expPerLevel, level.toLong()) * if (isFort) rules.loot.fortMultiplier else 1L
            if (!isFort) {
                s = WorldSim.arm(s, at, data)
                s = s.copy(map = s.map.copy(barbarians = s.map.barbarians.filter { it != barb }))
            }
        }
        val report =
            BattleReport(
                kind = if (isFort) BattleKind.FORT else BattleKind.BARBARIAN,
                tile = m.target,
                level = level,
                attackerWon = out.attackerWon,
                rounds = out.rounds,
                attackerInitial = m.troops.filterValues { it > 0 },
                attackerDead = dead,
                attackerWounded = wounded,
                defenderInitial = out.defender.initial,
                defenderDead =
                    (out.defender.dead.keys + out.defender.wounded.keys).associateWith {
                        (out.defender.dead[it] ?: 0L) + (out.defender.wounded[it] ?: 0L)
                    },
                loot = loot,
                lootItems = items,
                heroIds = m.heroIds,
                heroExp = exp,
                allyTroops = allies,
            )
        val mailId = s.nextMailId
        s = MailBox.battle(s, at, report, events)
        if (m.hostId != 0L) s = AllianceHooks.onRallyResult(s, m.hostPersona, m.hostId, out.attackerWon, data)
        val after =
            m.copy(
                troops = remaining,
                loot = loot,
                lootItems = items,
                woundedBack = wounded,
                heroExp = exp,
                mailId = mailId,
            )
        // 全滅なら帰還せず、その場で負傷兵・経験値を確定させる
        return if (remaining.isEmpty()) complete(s, after, data, events) else home(s, after)
    }

    /** 帰還の確定: 兵・負傷兵・戦利品・経験値を受け取り、行軍を消す。 */
    private fun complete(
        state: GameState,
        m: March,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        var s = state.copy(marches = state.marches.filter { it.id != m.id })
        val troops = HashMap(s.troops)
        for ((id, n) in m.troops) if (n > 0) troops[id] = (troops[id] ?: 0L) + n
        s = s.copy(troops = troops)
        val (admitted, overflow) = Hospital.admit(s, m.woundedBack, data)
        s = admitted
        if (overflow.isNotEmpty() && m.mailId > 0) {
            s =
                s.copy(
                    mails =
                        s.mails.map {
                            if (it.id == m.mailId) it.copy(battle = it.battle?.copy(overflowDead = overflow)) else it
                        },
                )
        }
        // 報酬は倉庫上限を超えて受け取れる（上限で止まるのは生産だけ。docs/02 §7）
        val res = s.resources + m.loot
        val items = HashMap(s.items)
        for ((id, n) in m.lootItems) items[id] = (items[id] ?: 0) + n
        val heroes =
            s.heroes.map { if (it.heroId in m.heroIds && m.heroExp > 0) HeroGrowth.addExp(it, m.heroExp, data) else it }
        s = s.copy(resources = res, items = items, heroes = heroes)
        if (m.kind == MarchKind.GATHER && m.loot != Resources.ZERO) {
            val t = ResourceType.entries.first { m.loot[it] > 0 }
            val params = mapOf("type" to t.ordinal.toLong(), "amount" to m.loot[t])
            s = MailBox.system(s, m.phaseEndEpochSec, "gather_done", params, events)
        }
        if (m.kind == MarchKind.GATHER) {
            events.add(GameEvent.GatherCompleted(m.loot.food + m.loot.wood + m.loot.stone + m.loot.gold, m.loot))
        }
        events.add(GameEvent.MarchReturned(m.id, m.kind))
        return Simulation.syncStorageFlags(s, data)
    }
}
