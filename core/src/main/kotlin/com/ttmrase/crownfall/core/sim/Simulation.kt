package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffSet
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul

/** [Simulation.tick] / アクションの結果。 */
data class TickResult(val state: GameState, val events: List<GameEvent>)

/**
 * シミュレーション本体。`tick` は純関数で、入力の [GameState] を変更しない。
 *
 * 分割不変性（R3）: `tick(tick(s, a), b) == tick(s, a + b)`（state・events の連結とも）。
 * dt を「次の境界（タイマー完了・Boost 期限・2 本目ビルダー期限）」ごとのセグメントに区切り、
 * セグメントごとに 生産 → 境界の確定処理 を行う。
 */
object Simulation {
    private const val SECONDS_PER_HOUR = 3600L

    /** [state] を [dtSec] 秒進める。dt = 0 は期限を過ぎたジョブの確定処理だけ。 */
    fun tick(
        state: GameState,
        dtSec: Long,
        data: GameData,
    ): TickResult {
        require(dtSec >= 0) { "dtSec must be >= 0: $dtSec" }
        val events = ArrayList<GameEvent>()
        val target = satAdd(state.nowEpochSec, dtSec)
        var s = state
        while (true) {
            s = settleDue(s, data, events)
            if (s.nowEpochSec >= target) break
            val segEnd = minOf(target, nextBoundary(s))
            s = produce(s, segEnd - s.nowEpochSec, data, events)
            s = s.copy(nowEpochSec = segEnd)
        }
        return TickResult(s, events)
    }

    /** 資源 1 種の上限（Castle の storage_cap_base + Warehouse の storage_cap）。 */
    fun storageCap(
        state: GameState,
        data: GameData,
    ): Long {
        var cap = 0L
        for (slot in state.slots) {
            val def = data.building(slot.type)
            when (slot.type) {
                BuildingType.CASTLE -> cap = satAdd(cap, def.effect(slot.level, "storage_cap_base"))
                BuildingType.WAREHOUSE -> cap = satAdd(cap, def.effect(slot.level, "storage_cap"))
                else -> Unit
            }
        }
        return cap
    }

    /** 資源 [type] の毎時生産レート（バフ込み、切り捨て）。 */
    fun hourlyRate(
        state: GameState,
        type: ResourceType,
        data: GameData,
        buffs: BuffSet = BuffAggregator.compute(state, data),
    ): Long {
        var base = 0L
        for (slot in state.slots) {
            if (slot.type.producedResource == type) {
                base = satAdd(base, data.building(slot.type).effect(slot.level, "production_per_hour"))
            }
        }
        return Buffs.applyBp(base, buffs.production(type))
    }

    /** 同時に進められる BUILD の数。 */
    fun buildQueueCapacity(
        state: GameState,
        data: GameData,
    ): Int = data.rules.baseBuildQueues + if (state.secondBuilderUntilEpochSec > state.nowEpochSec) 1 else 0

    private fun nextBoundary(s: GameState): Long {
        var next = Long.MAX_VALUE
        for (t in s.timers) if (t.endEpochSec > s.nowEpochSec) next = minOf(next, t.endEpochSec)
        for (b in s.boosts) if (b.untilEpochSec > s.nowEpochSec) next = minOf(next, b.untilEpochSec)
        if (s.secondBuilderUntilEpochSec > s.nowEpochSec) next = minOf(next, s.secondBuilderUntilEpochSec)
        return next
    }

    /** 現在時刻で期限を迎えたバフ除去とタイマー完了を確定させる（終了時刻の早い順、同時刻は id 順）。 */
    private fun settleDue(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        var s = state
        val expired = s.boosts.filter { it.untilEpochSec <= s.nowEpochSec }
        if (expired.isNotEmpty()) {
            s = s.copy(boosts = s.boosts.filter { it.untilEpochSec > s.nowEpochSec })
            expired.forEach { events.add(GameEvent.BoostExpired(it.id)) }
        }
        val due =
            s.timers.filter { it.endEpochSec <= s.nowEpochSec }.sortedWith(
                compareBy({ it.endEpochSec }, { it.id }),
            )
        if (due.isNotEmpty()) {
            val dueIds = due.map { it.id }.toSet()
            var slots = s.slots
            var research = s.research
            for (job in due) {
                val slotId = job.slotId
                val level = job.targetLevel
                when (job.kind) {
                    TimerKind.BUILD ->
                        if (slotId != null && level != null) {
                            slots = slots.map { if (it.slotId == slotId) it.copy(level = level) else it }
                            val type = slots.first { it.slotId == slotId }.type
                            events.add(GameEvent.BuildingLevelUp(slotId, type, level))
                        }
                    TimerKind.RESEARCH ->
                        if (job.researchId != null && level != null) {
                            research = research + (job.researchId to level)
                            events.add(GameEvent.ResearchCompleted(job.researchId, level))
                        }
                    else -> Unit
                }
                events.add(GameEvent.TimerCompleted(job))
            }
            s = s.copy(slots = slots, research = research, timers = s.timers.filter { it.id !in dueIds })
            s = syncStorageFlags(s, data)
        }
        return s
    }

    /**
     * セグメント内の生産。cap に達したら cap で止め、端数の繰り越しもリセットする。
     * StorageFull は「cap に達した正確な時刻」の順（同時刻は ResourceType 順）で出す。これにより
     * 分割の仕方に関係なく events の並びが一致する（R3）。すでに満杯の資源は区間の開始時刻で出す。
     */
    private fun produce(
        state: GameState,
        seconds: Long,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        if (seconds <= 0) return state
        val cap = storageCap(state, data)
        val buffs = BuffAggregator.compute(state, data)
        var res = state.resources
        var rem = state.productionRemainder
        var flags = state.storageFullFlags
        val reached = ArrayList<Pair<Long, ResourceType>>()
        for (type in ResourceType.entries) {
            val have = res[type]
            if (have < cap && type in flags) flags = flags - type
            if (have >= cap) {
                rem = rem.with(type, 0)
                if (cap > 0 && type !in flags) {
                    flags = flags + type
                    reached.add(state.nowEpochSec to type)
                }
                continue
            }
            val rate = hourlyRate(state, type, data, buffs)
            val total = satAdd(satMul(rate, seconds), rem[type])
            val produced = total / SECONDS_PER_HOUR
            if (satAdd(have, produced) >= cap) {
                val needTotal = satMul(cap - have, SECONDS_PER_HOUR) - rem[type]
                val secs = (needTotal - 1) / rate + 1
                reached.add(satAdd(state.nowEpochSec, secs) to type)
                flags = flags + type
                res = res.with(type, cap)
                rem = rem.with(type, 0)
            } else {
                res = res.with(type, have + produced)
                rem = rem.with(type, total % SECONDS_PER_HOUR)
            }
        }
        reached.sortedWith(
            compareBy({
                it.first
            }, { it.second.ordinal }),
        ).forEach { events.add(GameEvent.StorageFull(it.second)) }
        return state.copy(resources = res, productionRemainder = rem, storageFullFlags = flags)
    }

    /** 上限を下回った資源の StorageFull フラグを戻す（イベントは出さない）。 */
    internal fun syncStorageFlags(
        state: GameState,
        data: GameData,
    ): GameState {
        val cap = storageCap(state, data)
        val flags = state.storageFullFlags.filterTo(HashSet()) { state.resources[it] >= cap }
        return if (flags.size == state.storageFullFlags.size) state else state.copy(storageFullFlags = flags)
    }
}
