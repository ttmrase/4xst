package com.ttmrase.crownfall.core.bubble

import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.sim.Buffs
import com.ttmrase.crownfall.core.sim.Simulation

/**
 * 収穫バブル（M11 T11.2）。生産施設（畑・製材所・採石場・金鉱）の上に、前回の回収から
 * `bubbleIntervalSec` 後にバブルが 1 つ出て、タップすると `bubbleMinutes` 分の生産が受け取れる。
 *
 * 状態は「最後に回収した時刻」（[GameState.lastBubbleCollect]）だけで、出ているかは時刻から導く（tick のイベントなし）。
 * 何時間放っておいても 1 つにしかならない（溜まらない）。受け取りは行動なので、tick の分割に影響しない（R3）。
 */
object HarvestBubble {
    private const val MINUTE_SEC = 60L

    /** [slot] が生産施設で建っていれば、その資源。 */
    private fun produced(slot: BuildingSlot): ResourceType? = if (slot.level >= 1) slot.type.producedResource else null

    /** バブルが出る時刻（epoch 秒）。未回収の建物は 0（= いつでも出ている）。 */
    fun readyAt(
        state: GameState,
        slotId: Int,
        data: GameData,
    ): Long = (state.lastBubbleCollect[slotId] ?: Long.MIN_VALUE / 2) + data.rules.bubbleIntervalSec

    /** いまバブルが出ているスロット（スロット ID 順）。 */
    fun ready(
        state: GameState,
        data: GameData,
    ): List<Int> =
        state.slots
            .filter { produced(it) != null && state.nowEpochSec >= readyAt(state, it.slotId, data) }
            .map { it.slotId }
            .sorted()

    /** スロット [slotId] のバブルの中身（バフ込みの毎時生産 × 分数 ÷ 60、切り捨て）。生産施設でなければ空。 */
    fun contents(
        state: GameState,
        slotId: Int,
        data: GameData,
    ): Resources {
        val slot = state.slots.firstOrNull { it.slotId == slotId } ?: return Resources.ZERO
        val type = produced(slot) ?: return Resources.ZERO
        val buffs = BuffAggregator.compute(state, data)
        val perHour = data.building(slot.type).effect(slot.level, "production_per_hour")
        val boosted = Buffs.applyBp(perHour, buffs.production(type))
        return Resources.ZERO.with(type, boosted * data.rules.bubbleMinutes / MINUTE_SEC)
    }

    /**
     * [slotIds] のうち、いま出ているバブルを回収する。倉庫の上限を超える分は切り捨てる。
     * 回収した ID と、結果の状態を返す（出ていないもの・倉庫が満杯で入らないものは何もしない）。
     */
    fun collect(
        state: GameState,
        slotIds: Collection<Int>,
        data: GameData,
    ): Pair<GameState, List<Int>> {
        val cap = Simulation.storageCap(state, data)
        val ready = ready(state, data).toSet()
        var res = state.resources
        val collected = mutableListOf<Int>()
        for (id in slotIds.distinct().sorted()) {
            if (id !in ready) continue
            val c = contents(state, id, data)
            val next = ResourceType.entries.fold(res) { r, t -> r.with(t, minOf(maxOf(r[t], cap), r[t] + c[t])) }
            if (next == res) continue // 倉庫が満杯でひとつも入らないときは、バブルを残す
            res = next
            collected += id
        }
        if (collected.isEmpty()) return state to emptyList()
        val stamp = collected.associateWith { state.nowEpochSec }
        return state.copy(resources = res, lastBubbleCollect = state.lastBubbleCollect + stamp) to collected
    }
}
