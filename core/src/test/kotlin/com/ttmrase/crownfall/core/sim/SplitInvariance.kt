package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import kotlin.test.assertEquals

/**
 * R3（分割不変性）の共通検証。`tick(s, a + b)` と `tick(tick(s, a), b)` が state・events の連結とも一致することを確認する。
 * M4 以降の境界（行軍フェーズ、マップ補充、襲撃）は [extraBoundaries] に「開始からの相対秒」で渡すと、その ±1 秒でも切る。
 */
object SplitInvariance {
    /** 1 本の tick を [cuts]（昇順・重複可）で分割して流した結果。 */
    private fun stepped(
        state: GameState,
        total: Long,
        cuts: List<Long>,
        data: GameData,
    ): TickResult {
        var cur = state
        val events = ArrayList<GameEvent>()
        var done = 0L
        for (cut in cuts.filter { it in 0..total }.sorted() + total) {
            val r = Simulation.tick(cur, cut - done, data)
            cur = r.state
            events.addAll(r.events)
            done = cut
        }
        return TickResult(cur, events)
    }

    /** 全タイマー・Boost・2 本目ビルダーの期限と [extraBoundaries] の ±1 秒、およびランダムな分割で不変性を確認する。 */
    fun assertInvariant(
        state: GameState,
        total: Long,
        data: GameData,
        random: java.util.Random,
        randomCases: Int = 100,
        extraBoundaries: List<Long> = emptyList(),
    ) {
        val whole = Simulation.tick(state, total, data)
        val builtIn =
            state.timers.map { it.endEpochSec - state.nowEpochSec } +
                state.boosts.map { it.untilEpochSec - state.nowEpochSec } +
                (state.secondBuilderUntilEpochSec - state.nowEpochSec)
        for (b in (builtIn + extraBoundaries).filter { it > 0 }) {
            for (cut in listOf(b - 1, b, b + 1)) {
                val r = stepped(state, total, listOf(cut), data)
                assertEquals(whole.state, r.state, "state differs at boundary cut=$cut")
                assertEquals(whole.events, r.events, "events differ at boundary cut=$cut")
            }
        }
        repeat(randomCases) { n ->
            val cuts = List(1 + random.nextInt(4)) { (random.nextDouble() * total).toLong() }
            val r = stepped(state, total, cuts, data)
            assertEquals(whole.state, r.state, "state differs (random case $n, cuts=$cuts)")
            assertEquals(whole.events, r.events, "events differ (random case $n, cuts=$cuts)")
        }
    }
}
