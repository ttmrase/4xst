package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.alliance.AllianceScheduler
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.event.EventScheduler
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.map.MapRefresher
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.satAdd

/** マップ関連の tick 処理（行軍の段階、補充、襲撃）。Simulation から境界ごとに呼ばれる。 */
object WorldSim {
    private const val SEED_MIX = 0x5DEECE66DL

    /** 期限を迎えたものを 行軍 → 補充 → 襲撃 の順に確定する。 */
    fun settle(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        var s = initMap(state, data)
        s = MarchSim.settle(s, data, events)
        s = refresh(s, data)
        if (FeatureGate.unlocked(s, data, Feature.RAIDS)) s = Raids.settle(s, data, events)
        if (FeatureGate.unlocked(s, data, Feature.ALLIANCE)) s = AllianceScheduler.settle(s, data, events)
        return if (FeatureGate.unlocked(s, data, Feature.EVENTS)) EventScheduler.settle(s, data, events) else s
    }

    /** 次の境界（行軍の段階終了、補充、襲撃の予告 / 本番）。 */
    fun nextBoundary(
        state: GameState,
        data: GameData,
    ): Long {
        var next = Long.MAX_VALUE
        if (FeatureGate.unlocked(state, data, Feature.RAIDS)) next = minOf(next, Raids.nextBoundary(state, data))
        if (FeatureGate.unlocked(state, data, Feature.ALLIANCE)) {
            next = minOf(next, AllianceScheduler.nextGrid(state.nowEpochSec))
        }
        if (FeatureGate.unlocked(state, data, Feature.EVENTS)) {
            next = minOf(next, EventScheduler.nextBoundary(state, data))
        }
        for (m in state.marches) if (m.phaseEndEpochSec > state.nowEpochSec) next = minOf(next, m.phaseEndEpochSec)
        val r = state.map.nextRefreshEpochSec
        if (r > state.nowEpochSec && hasDeficit(state, data)) next = minOf(next, r)
        return next
    }

    /** 未初期化（旧セーブ / 新規）のマップに seed を決めて初回補充する。 */
    private fun initMap(
        state: GameState,
        data: GameData,
    ): GameState {
        if (state.map.nextRefreshEpochSec > 0) return state
        val seed = if (state.map.seed != 0L) state.map.seed else state.rngState xor SEED_MIX
        val base = state.map.copy(seed = seed, rngState = if (state.map.rngState != 0L) state.map.rngState else seed)
        val world = Marching.world(state.copy(map = base), data)
        return state.copy(map = MapRefresher.refresh(base, world, data.mapRules, state.nowEpochSec))
    }

    /**
     * 補充は「不足があり、予定時刻を過ぎている」ときだけ 1 回行う（満杯なら何も起きないので境界にしない。
     * 長い dt でも周期ごとに刻まずに済む）。不足が生まれた時刻に予定が過ぎていたら [arm] で 1 周期先へ送る。
     */
    private fun refresh(
        state: GameState,
        data: GameData,
    ): GameState {
        val next = state.map.nextRefreshEpochSec
        if (next <= 0 || next > state.nowEpochSec || !hasDeficit(state, data)) return state
        val world = Marching.world(state, data)
        return state.copy(map = MapRefresher.refresh(state.map, world, data.mapRules, state.nowEpochSec))
    }

    /** ノード・野蛮人が目標数に足りないか。 */
    fun hasDeficit(
        state: GameState,
        data: GameData,
    ): Boolean {
        val r = data.mapRules
        return state.map.barbarians.size < r.barbarians.target ||
            ResourceType.entries.any { t -> state.map.nodes.count { it.type == t } < r.nodes.targetPerType }
    }

    /** 時刻 [at] に不足が生まれたとき、補充予定がすでに過ぎていれば 1 周期後にする（即座の再出現を防ぐ）。 */
    internal fun arm(
        state: GameState,
        at: Long,
        data: GameData,
    ): GameState =
        if (state.map.nextRefreshEpochSec in 1..at) {
            state.copy(map = state.map.copy(nextRefreshEpochSec = satAdd(at, data.mapRules.refreshPeriodSec)))
        } else {
            state
        }
}
