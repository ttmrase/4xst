package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.March
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.march.Scouting
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.satAdd

/** 行軍の開始と呼び戻し（検査は宣言順で、最初の失敗を返す）。 */
internal object MarchActions {
    fun start(
        state: GameState,
        kind: MarchKind,
        target: TileRef,
        troops: Map<String, Long>,
        heroIds: List<String>,
        data: GameData,
    ): ActionResult {
        val rules = data.mapRules
        if (!Marching.world(state, data).inBounds(target.x, target.y)) return fail(ActionFailure.InvalidTarget)
        if (!validTarget(state, kind, target, data)) return fail(ActionFailure.InvalidTarget)
        val sent = troops.filterValues { it > 0 }
        if (sent.isEmpty() || sent.any { (id, n) -> id !in data.troops || n > (state.troops[id] ?: 0L) }) {
            return fail(ActionFailure.NoTroops)
        }
        if (heroIds.size > rules.march.maxHeroes || heroIds.toSet().size != heroIds.size) {
            return fail(ActionFailure.InvalidArgument)
        }
        if (heroIds.any { id -> state.heroes.none { it.heroId == id } }) return fail(ActionFailure.HeroNotOwned)
        if (heroIds.any { it in Marching.busyHeroes(state) }) return fail(ActionFailure.HeroBusy)
        if (state.marches.size >= Marching.slots(state, data)) return fail(ActionFailure.NoMarchSlot)
        if (sent.values.fold(0L, ::satAdd) > Marching.troopCap(state, data)) return fail(ActionFailure.OverCapacity)

        val travel = Marching.travelSec(state, kind, sent, heroIds, target, data)
        val now = state.nowEpochSec
        val waiting = kind == MarchKind.RALLY
        val end = satAdd(now, if (waiting) rules.march.rallyWaitSec else travel)
        val march =
            March(
                id = state.nextMarchId,
                kind = kind,
                troops = sent.toSortedMap().toMap(),
                heroIds = heroIds,
                target = target,
                phase = if (waiting) MarchPhase.WAITING else MarchPhase.OUTBOUND,
                phaseStartEpochSec = now,
                phaseEndEpochSec = end,
                travelSec = travel,
                capacity = Marching.capacity(state, sent, heroIds, data),
            )
        val left = state.troops.mapValues { (id, n) -> n - (sent[id] ?: 0L) }.filterValues { it > 0 }
        return ActionResult.Success(
            state.copy(troops = left, marches = state.marches + march, nextMarchId = state.nextMarchId + 1),
        )
    }

    private fun validTarget(
        state: GameState,
        kind: MarchKind,
        t: TileRef,
        data: GameData,
    ): Boolean =
        when (kind) {
            MarchKind.GATHER -> state.map.nodes.any { it.x == t.x && it.y == t.y }
            MarchKind.ATTACK -> state.map.barbarians.any { it.x == t.x && it.y == t.y }
            MarchKind.RALLY -> Marching.world(state, data).structureAt(t.x, t.y)?.type == StructureType.BARBARIAN_FORT
            MarchKind.SCOUT -> Scouting.isScoutable(state, t, data)
        }

    /**
     * 行軍 [marchId] を呼び戻す。集結待ち・出発直後は即座に兵が戻る。移動中は経過時間で引き返し、
     * 採集中は採れた分（経過に比例）だけ持ち帰り、残りはノードへ戻す。帰還中は呼び戻せない。
     */
    fun recall(
        state: GameState,
        marchId: Long,
        data: GameData,
    ): ActionResult {
        val m = state.marches.firstOrNull { it.id == marchId } ?: return fail(ActionFailure.MarchNotFound)
        val now = state.nowEpochSec
        val elapsed = maxOf(0L, now - m.phaseStartEpochSec)
        return when (m.phase) {
            MarchPhase.RETURNING -> fail(ActionFailure.InvalidArgument)
            MarchPhase.WAITING -> ActionResult.Success(restore(state, m), listOf(GameEvent.MarchReturned(m.id, m.kind)))
            MarchPhase.OUTBOUND ->
                if (elapsed == 0L) {
                    ActionResult.Success(restore(state, m), listOf(GameEvent.MarchReturned(m.id, m.kind)))
                } else {
                    ActionResult.Success(
                        replace(
                            state,
                            m.copy(
                                phase = MarchPhase.RETURNING,
                                phaseStartEpochSec = now,
                                phaseEndEpochSec = now + elapsed,
                            ),
                        ),
                    )
                }
            MarchPhase.WORKING -> {
                val total = maxOf(1L, m.phaseEndEpochSec - m.phaseStartEpochSec)
                val got = m.workAmount * minOf(elapsed, total) / total
                val back = m.workAmount - got
                val nodes =
                    state.map.nodes.map {
                        if (it.x == m.target.x && it.y == m.target.y) it.copy(remaining = it.remaining + back) else it
                    }
                val loot = m.workType?.let { Resources.ZERO.with(it, got) } ?: Resources.ZERO
                val returning =
                    m.copy(
                        phase = MarchPhase.RETURNING,
                        phaseStartEpochSec = now,
                        phaseEndEpochSec = now + maxOf(1L, m.travelSec),
                        loot = loot,
                    )
                ActionResult.Success(replace(state.copy(map = state.map.copy(nodes = nodes)), returning))
            }
        }
    }

    private fun replace(
        s: GameState,
        m: March,
    ): GameState = s.copy(marches = s.marches.map { if (it.id == m.id) m else it })

    private fun restore(
        s: GameState,
        m: March,
    ): GameState {
        val troops = HashMap(s.troops)
        for ((id, n) in m.troops) troops[id] = (troops[id] ?: 0L) + n
        return s.copy(troops = troops, marches = s.marches.filter { it.id != m.id })
    }

    private fun fail(reason: ActionFailure) = ActionResult.Failure(reason)
}
