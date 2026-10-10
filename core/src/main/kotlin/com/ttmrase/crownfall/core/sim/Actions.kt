package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.economy.CostCheck
import com.ttmrase.crownfall.core.economy.Costs
import com.ttmrase.crownfall.core.economy.GemSkip
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul

/** アクション失敗の理由（ID と数値のみ。表示文言は :app）。 */
sealed interface ActionFailure {
    data object SlotNotFound : ActionFailure

    data object MaxLevel : ActionFailure

    data object AlreadyUpgrading : ActionFailure

    data class PrereqNotMet(val missing: List<Prereq>) : ActionFailure

    data object QueueFull : ActionFailure

    data class NotEnoughResources(val missing: Resources) : ActionFailure

    data object JobNotFound : ActionFailure

    data object ItemNotOwned : ActionFailure

    data object CategoryMismatch : ActionFailure

    data class NotEnoughGems(val missing: Long) : ActionFailure

    data object InvalidArgument : ActionFailure
}

/** アクション結果。例外は投げず、成功か失敗かで返す。 */
sealed interface ActionResult {
    data class Success(val state: GameState, val events: List<GameEvent> = emptyList()) : ActionResult

    data class Failure(val reason: ActionFailure) : ActionResult
}

/** プレイヤー操作・報酬の受け口（すべて純関数）。 */
object Actions {
    /** チュートリアル完了を表すステップ番号。 */
    const val TUTORIAL_DONE = 3

    /** スロット [slotId] のアップグレードを開始する。検査は宣言順で、最初の失敗を返す。 */
    fun startUpgrade(
        state: GameState,
        slotId: Int,
        data: GameData,
    ): ActionResult {
        val slot = state.slots.firstOrNull { it.slotId == slotId } ?: return fail(ActionFailure.SlotNotFound)
        val def = data.building(slot.type)
        if (slot.level >= def.maxLevel) return fail(ActionFailure.MaxLevel)
        if (state.timers.any { it.kind == TimerKind.BUILD && it.slotId == slotId }) {
            return fail(
                ActionFailure.AlreadyUpgrading,
            )
        }
        val target = def.level(slot.level + 1)
        val missingPrereqs =
            target.prereqs.filter { p ->
                p.level > 0 && (state.slots.filter { it.type == p.type }.maxOfOrNull { it.level } ?: 0) < p.level
            }
        if (missingPrereqs.isNotEmpty()) return fail(ActionFailure.PrereqNotMet(missingPrereqs))
        val busy = state.timers.count { it.kind == TimerKind.BUILD }
        if (busy >= Simulation.buildQueueCapacity(state, data)) return fail(ActionFailure.QueueFull)
        val rest =
            when (val check = Costs.check(state.resources, target.cost)) {
                is CostCheck.Missing -> return fail(ActionFailure.NotEnoughResources(check.missing))
                CostCheck.Ok -> state.resources.minusOrNull(target.cost) ?: return fail(ActionFailure.InvalidArgument)
            }
        val duration = Buffs.buildSeconds(target.seconds, Buffs.totalBp(state.boosts, BoostKind.BUILD_SPEED))
        val job =
            TimerJob(
                id = state.nextJobId,
                kind = TimerKind.BUILD,
                slotId = slotId,
                targetLevel = slot.level + 1,
                startEpochSec = state.nowEpochSec,
                endEpochSec = satAdd(state.nowEpochSec, duration),
            )
        val next = state.copy(resources = rest, timers = state.timers + job, nextJobId = state.nextJobId + 1)
        return ActionResult.Success(Simulation.syncStorageFlags(next, data))
    }

    /** 2 本目ビルダーを [durationSec] 秒付与する（期限が残っていれば延長）。 */
    fun grantSecondBuilder(
        state: GameState,
        durationSec: Long,
    ): ActionResult {
        if (durationSec <= 0) return fail(ActionFailure.InvalidArgument)
        val base = maxOf(state.secondBuilderUntilEpochSec, state.nowEpochSec)
        return ActionResult.Success(state.copy(secondBuilderUntilEpochSec = satAdd(base, durationSec)))
    }

    /** 資源を加算する（報酬・購入。上限を超えてよい。飽和）。負の成分は R5（負数を作らない）のため拒否する。 */
    fun addResources(
        state: GameState,
        amount: Resources,
    ): ActionResult {
        if (amount.food < 0 || amount.wood < 0 || amount.stone < 0 || amount.gold < 0) {
            return fail(ActionFailure.InvalidArgument)
        }
        return ActionResult.Success(state.copy(resources = state.resources + amount))
    }

    /** ジェムを加算する（飽和）。 */
    fun addGems(
        state: GameState,
        amount: Long,
    ): ActionResult {
        if (amount < 0) return fail(ActionFailure.InvalidArgument)
        return ActionResult.Success(state.copy(gems = satAdd(state.gems, amount)))
    }

    /** アイテムを [count] 個加算する。 */
    fun addItems(
        state: GameState,
        itemId: String,
        count: Int,
    ): ActionResult {
        if (count <= 0) return fail(ActionFailure.InvalidArgument)
        val have = state.items[itemId] ?: 0
        val sum = have.toLong() + count
        return ActionResult.Success(
            state.copy(items = state.items + (itemId to minOf(sum, Int.MAX_VALUE.toLong()).toInt())),
        )
    }

    /** チュートリアルの進行を [step]（0..3）へ進める。後戻りはしない（現在以下なら何も変えず成功）。 */
    fun setTutorialStep(
        state: GameState,
        step: Int,
    ): ActionResult {
        if (step < 0 || step > TUTORIAL_DONE) return fail(ActionFailure.InvalidArgument)
        if (step <= state.tutorialStep) return ActionResult.Success(state)
        return ActionResult.Success(state.copy(tutorialStep = step))
    }

    /** 期限付きバフを追加する（同 id は置き換え）。 */
    fun addBoost(
        state: GameState,
        boost: Boost,
    ): ActionResult = ActionResult.Success(state.copy(boosts = state.boosts.filter { it.id != boost.id } + boost))

    /** 加速アイテム [itemId] を [count] 個、タイマー [jobId] に使う。残り時間を超える分は捨てる。 */
    fun applySpeedupItem(
        state: GameState,
        jobId: Long,
        itemId: String,
        count: Int,
        data: GameData,
    ): ActionResult {
        if (count <= 0) return fail(ActionFailure.InvalidArgument)
        val job = state.timers.firstOrNull { it.id == jobId } ?: return fail(ActionFailure.JobNotFound)
        val item = data.items[itemId]
        if ((state.items[itemId] ?: 0) < count || item == null) return fail(ActionFailure.ItemNotOwned)
        if (item.category?.matches(job.kind) != true) return fail(ActionFailure.CategoryMismatch)
        val reduction = satMul(item.seconds, count.toLong())
        val newEnd = maxOf(state.nowEpochSec, job.endEpochSec - reduction)
        val left = (state.items[itemId] ?: 0) - count
        val items = if (left > 0) state.items + (itemId to left) else state.items - itemId
        val next =
            state.copy(
                items = items,
                timers = state.timers.map { if (it.id == jobId) it.copy(endEpochSec = newEnd) else it },
            )
        val settled = Simulation.tick(next, 0, data)
        return ActionResult.Success(settled.state, settled.events)
    }

    /** ジェムでタイマー [jobId] を即時完了させる。足りなければ [ActionFailure.NotEnoughGems]。 */
    fun gemSkip(
        state: GameState,
        jobId: Long,
        data: GameData,
    ): ActionResult {
        val job = state.timers.firstOrNull { it.id == jobId } ?: return fail(ActionFailure.JobNotFound)
        val cost = GemSkip.cost(maxOf(0L, job.endEpochSec - state.nowEpochSec), data)
        if (state.gems < cost) return fail(ActionFailure.NotEnoughGems(cost - state.gems))
        val next =
            state.copy(
                gems = state.gems - cost,
                timers = state.timers.map { if (it.id == jobId) it.copy(endEpochSec = state.nowEpochSec) else it },
            )
        val settled = Simulation.tick(next, 0, data)
        return ActionResult.Success(settled.state, settled.events)
    }

    private fun fail(reason: ActionFailure) = ActionResult.Failure(reason)
}
