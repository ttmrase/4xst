package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob

/** 不在サマリー（表示は M2）。[produced] は資源ごとの増加分で、減少した資源は 0。 */
data class OfflineSummary(
    val elapsedSec: Long,
    val appliedSec: Long,
    val produced: Resources,
    val completedJobs: List<TimerJob>,
    val levelUps: Int,
)

/** [OfflineCatchUp.run] の結果。 */
data class OfflineResult(val state: GameState, val summary: OfflineSummary, val events: List<GameEvent>)

/** 復帰時の一括進行。長い dt を chunk に分割して tick する（R3 により一括 tick と同じ結果）。 */
object OfflineCatchUp {
    /**
     * [state] を [nowEpochSec] まで進める。経過が 0 以下なら何もしない（時計の巻き戻し対策）。
     * 適用するのは `min(elapsed, capSec)` 秒だけで、超過分は生産もタイマーも進めず、
     * タイマー・バフ・2 本目ビルダーの期限を超過分だけ後ろへずらして残り時間を保ち、時刻だけ [nowEpochSec] にそろえる。
     */
    fun run(
        state: GameState,
        nowEpochSec: Long,
        data: GameData,
        capSec: Long = data.rules.offlineCapSec,
    ): OfflineResult {
        val elapsed = nowEpochSec - state.nowEpochSec
        if (elapsed <= 0) return OfflineResult(state, OfflineSummary(0, 0, Resources.ZERO, emptyList(), 0), emptyList())
        val applied = minOf(elapsed, capSec.coerceAtLeast(0))
        val chunk = data.rules.catchUpChunkSec
        val events = ArrayList<GameEvent>()
        var s = state
        var left = applied
        while (left > 0) {
            val dt = minOf(chunk, left)
            val r = Simulation.tick(s, dt, data)
            s = r.state
            events.addAll(r.events)
            left -= dt
        }
        val skipped = elapsed - applied
        if (skipped > 0) s = shift(s, skipped)
        // 維持費で Food が減った場合などは増加分 0 とする（UI は "+" 付きで表示するため負数にしない。R5）
        val produced =
            Resources(
                maxOf(0L, s.resources.food - state.resources.food),
                maxOf(0L, s.resources.wood - state.resources.wood),
                maxOf(0L, s.resources.stone - state.resources.stone),
                maxOf(0L, s.resources.gold - state.resources.gold),
            )
        val summary =
            OfflineSummary(
                elapsedSec = elapsed,
                appliedSec = applied,
                produced = produced,
                completedJobs = events.filterIsInstance<GameEvent.TimerCompleted>().map { it.job },
                levelUps = events.count { it is GameEvent.BuildingLevelUp },
            )
        return OfflineResult(s, summary, events)
    }

    private fun shift(
        s: GameState,
        sec: Long,
    ): GameState =
        s.copy(
            nowEpochSec = s.nowEpochSec + sec,
            timers =
                s.timers.map {
                    it.copy(
                        startEpochSec = it.startEpochSec + sec,
                        endEpochSec = it.endEpochSec + sec,
                    )
                },
            boosts = s.boosts.map { it.copy(untilEpochSec = it.untilEpochSec + sec) },
            secondBuilderUntilEpochSec =
                if (s.secondBuilderUntilEpochSec > 0) s.secondBuilderUntilEpochSec + sec else 0,
        )
}
