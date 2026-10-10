package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.alliance.AllianceHooks
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffSet
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.march.WorldSim
import com.ttmrase.crownfall.core.military.Military
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
    private const val FULL = 0
    private const val DEPLETED = 1

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
            val segEnd = minOf(target, nextBoundary(s, data))
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

    private fun nextBoundary(
        s: GameState,
        data: GameData,
    ): Long {
        var next = Long.MAX_VALUE
        for (t in s.timers) if (t.endEpochSec > s.nowEpochSec) next = minOf(next, t.endEpochSec)
        for (b in s.boosts) if (b.untilEpochSec > s.nowEpochSec) next = minOf(next, b.untilEpochSec)
        if (s.secondBuilderUntilEpochSec > s.nowEpochSec) next = minOf(next, s.secondBuilderUntilEpochSec)
        return minOf(next, WorldSim.nextBoundary(s, data))
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
            var troops = s.troops
            val castleUps = ArrayList<Int>()
            for (job in due) {
                val slotId = job.slotId
                val level = job.targetLevel
                when (job.kind) {
                    TimerKind.BUILD ->
                        if (slotId != null && level != null) {
                            slots = slots.map { if (it.slotId == slotId) it.copy(level = level) else it }
                            val type = slots.first { it.slotId == slotId }.type
                            events.add(GameEvent.BuildingLevelUp(slotId, type, level))
                            if (type == BuildingType.CASTLE) castleUps.add(level)
                        }
                    TimerKind.RESEARCH ->
                        if (job.researchId != null && level != null) {
                            research = research + (job.researchId to level)
                            events.add(GameEvent.ResearchCompleted(job.researchId, level))
                        }
                    TimerKind.TRAIN ->
                        if (job.troopId != null) {
                            troops = troops + (job.troopId to (troops[job.troopId] ?: 0L) + job.count)
                            events.add(GameEvent.TroopsTrained(job.troopId, job.count))
                        }
                    TimerKind.HEAL -> {
                        job.healBatch.forEach { (id, n) -> troops = troops + (id to (troops[id] ?: 0L) + n) }
                        events.add(GameEvent.TroopsHealed(job.healBatch))
                    }
                }
                events.add(GameEvent.TimerCompleted(job))
            }
            s =
                s.copy(
                    slots = slots,
                    research = research,
                    troops = troops,
                    timers = s.timers.filter { it.id !in dueIds },
                )
            s = syncStorageFlags(s, data)
            for (lv in castleUps) s = AllianceHooks.onCastleUp(s, lv, data)
        }
        return WorldSim.settle(s, data, events)
    }

    /**
     * セグメント内の生産と維持費。Food は「生産 − 維持費」の正味レートで増減する。
     * - 正味が 0 以上: cap に達したら cap で止め、端数の繰り越しもリセットする（StorageFull）。
     * - 正味が負: 0 に向かって減り、0 で止めて端数もリセットする（FoodDepleted）。
     * 到達イベントは「到達した正確な時刻」の順（同時刻は資源順）で出す。これにより分割の仕方に関係なく
     * events の並びが一致する（R3）。すでに満杯・枯渇している資源は区間の開始時刻で出す。
     * 端数の繰り越しは floorDiv / floorMod で符号付きでも一貫させる。
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
        val upkeep = Military.upkeepPerHour(state, data, buffs)
        var res = state.resources
        var rem = state.productionRemainder
        var flags = state.storageFullFlags
        var depleted = state.foodDepleted
        val reached = ArrayList<Reached>()
        for (type in ResourceType.entries) {
            val have = res[type]
            if (have < cap && type in flags) flags = flags - type
            if (type == ResourceType.FOOD && have > 0 && depleted) depleted = false
            if (cap > 0 && have >= cap && type !in flags) {
                flags = flags + type
                reached.add(Reached(state.nowEpochSec, type, FULL, GameEvent.StorageFull(type)))
            }
            val gross = hourlyRate(state, type, data, buffs)
            val net = if (type == ResourceType.FOOD) gross - upkeep else gross
            if (net >= 0) {
                if (have >= cap) {
                    rem = rem.with(type, 0)
                    continue
                }
                val total = satAdd(satMul(net, seconds), rem[type])
                val produced = total / SECONDS_PER_HOUR
                if (satAdd(have, produced) >= cap) {
                    val needTotal = satMul(cap - have, SECONDS_PER_HOUR) - rem[type]
                    val secs = (needTotal - 1) / net + 1
                    reached.add(Reached(satAdd(state.nowEpochSec, secs), type, FULL, GameEvent.StorageFull(type)))
                    flags = flags + type
                    res = res.with(type, cap)
                    rem = rem.with(type, 0)
                } else {
                    res = res.with(type, have + produced)
                    rem = rem.with(type, total % SECONDS_PER_HOUR)
                }
            } else {
                val total = satAdd(satMul(net, seconds), rem[type])
                val delta = Math.floorDiv(total, SECONDS_PER_HOUR)
                if (have + delta <= 0) {
                    // floorDiv(net*secs + rem, 3600) <= -have を最初に満たす秒（floor のせいで「ちょうど 0」より早く 0 になる）
                    val secs =
                        if (have == 0L) {
                            0L
                        } else {
                            (satAdd(satMul(have, SECONDS_PER_HOUR), rem[type]) - (SECONDS_PER_HOUR - 1) - 1) / -net + 1
                        }
                    if (!depleted) {
                        depleted = true
                        reached.add(Reached(satAdd(state.nowEpochSec, secs), type, DEPLETED, GameEvent.FoodDepleted))
                    }
                    res = res.with(type, 0)
                    rem = rem.with(type, 0)
                } else {
                    res = res.with(type, have + delta)
                    rem = rem.with(type, Math.floorMod(total, SECONDS_PER_HOUR))
                }
            }
        }
        reached.sortedWith(compareBy({ it.time }, { it.type.ordinal }, { it.order })).forEach { events.add(it.event) }
        return syncStorageFlags(
            state.copy(resources = res, productionRemainder = rem, storageFullFlags = flags, foodDepleted = depleted),
            data,
        )
    }

    private class Reached(
        val time: Long,
        val type: ResourceType,
        val order: Int,
        val event: GameEvent,
    )

    /** 上限を下回った資源の StorageFull フラグを戻す（イベントは出さない）。 */
    internal fun syncStorageFlags(
        state: GameState,
        data: GameData,
    ): GameState {
        val cap = storageCap(state, data)
        val flags = state.storageFullFlags.filterTo(HashSet()) { state.resources[it] >= cap }
        val depleted = state.foodDepleted && state.resources.food <= 0
        return if (flags.size == state.storageFullFlags.size && depleted == state.foodDepleted) {
            state
        } else {
            state.copy(storageFullFlags = flags, foodDepleted = depleted)
        }
    }
}
