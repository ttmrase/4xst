package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.ItemDef
import com.ttmrase.crownfall.core.data.ResearchLevel
import com.ttmrase.crownfall.core.data.ResearchNodeDef
import com.ttmrase.crownfall.core.data.ResearchPrereq
import com.ttmrase.crownfall.core.data.TroopDef
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.military.Military
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.research.ResearchRules
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Buffs

/** 城内画面の区分け（セクション見出し）。 */
enum class BuildingSection { CORE, RESOURCE, MILITARY, OTHER }

/** アップグレード詳細シート用の表示モデル（ID と数値のみ）。 */
data class UpgradePreview(
    val slot: BuildingSlot,
    val maxLevel: Int,
    /** 次のレベル。最大レベルなら null。 */
    val nextLevel: Int?,
    val cost: Resources,
    /** 現在の所持に対する不足分（足りていれば ZERO）。 */
    val missing: Resources,
    /** Boost 込みの所要秒。最大レベルなら 0。 */
    val durationSec: Long,
    /** 満たしていない前提条件。 */
    val unmetPrereqs: List<Prereq>,
    val currentEffects: Map<String, Long>,
    val nextEffects: Map<String, Long>,
    /** 実行できない理由（startUpgrade の検査順で最初の失敗）。実行可能なら null。 */
    val blocker: ActionFailure?,
    /** このスロットの進行中の建設。 */
    val upgrading: TimerJob?,
)

/** 兵舎画面の 1 Tier 分の表示モデル。 */
data class TroopTierView(
    val troop: TroopDef,
    /** 訓練建物の Lv が足りず解放されていない。 */
    val locked: Boolean,
    val requiredBuildingLevel: Int,
    val owned: Long,
    val wounded: Long,
    /** 今の資源と建物で 1 回に訓練できる最大数（未解放なら 0）。 */
    val maxTrainable: Long,
)

/** 訓練の確認表示モデル。 */
data class TrainPreview(
    val count: Long,
    val cost: Resources,
    val missing: Resources,
    val durationSec: Long,
    /** 実行できない理由（startTraining の検査順で最初の失敗）。実行可能なら null。 */
    val blocker: ActionFailure?,
    /** この訓練建物で進行中の訓練。 */
    val running: TimerJob?,
)

/** 研究ノードの表示モデル。 */
data class ResearchPreview(
    val node: ResearchNodeDef,
    val currentLevel: Int,
    /** 次のレベル。最大なら null。 */
    val next: ResearchLevel?,
    val missing: Resources,
    val durationSec: Long,
    val missingAcademy: Int?,
    val missingNodes: List<ResearchPrereq>,
    val blocker: ActionFailure?,
    val running: TimerJob?,
) {
    /** 前提を満たさず着手できない（鍵表示）。 */
    val locked: Boolean get() = missingAcademy != null || missingNodes.isNotEmpty()
}

/** 研究一覧の 1 行分の表示モデル。 */
data class ResearchRow(val currentLevel: Int, val locked: Boolean)

/** 所持している加速アイテム 1 種。 */
data class UsableItem(val item: ItemDef, val owned: Int)

/** UI 向けの純関数クエリ（コンテナでテストできるよう :core に置く）。 */
object UiQueries {
    /** 建物種の表示セクション。 */
    fun sectionOf(type: BuildingType): BuildingSection =
        when (type) {
            BuildingType.CASTLE -> BuildingSection.CORE
            BuildingType.FARM,
            BuildingType.SAWMILL,
            BuildingType.QUARRY,
            BuildingType.GOLD_MINE,
            BuildingType.WAREHOUSE,
            -> BuildingSection.RESOURCE
            BuildingType.BARRACKS,
            BuildingType.ARCHERY_RANGE,
            BuildingType.STABLE,
            BuildingType.SIEGE_WORKSHOP,
            BuildingType.HOSPITAL,
            BuildingType.WALL,
            -> BuildingSection.MILITARY
            else -> BuildingSection.OTHER
        }

    /** スロット [slotId] のアップグレード表示モデル。スロットが無ければ null。 */
    fun upgradePreview(
        state: GameState,
        slotId: Int,
        data: GameData,
    ): UpgradePreview? {
        val slot = state.slots.firstOrNull { it.slotId == slotId } ?: return null
        val def = data.building(slot.type)
        val next = if (slot.level < def.maxLevel) def.level(slot.level + 1) else null
        val blocker = (Actions.startUpgrade(state, slotId, data) as? ActionResult.Failure)?.reason
        val unmet =
            next?.prereqs.orEmpty().filter { p ->
                p.level > 0 && (state.slots.filter { it.type == p.type }.maxOfOrNull { it.level } ?: 0) < p.level
            }
        val bp = BuffAggregator.compute(state, data)[BuffKey.BUILD_SPEED]
        return UpgradePreview(
            slot = slot,
            maxLevel = def.maxLevel,
            nextLevel = next?.level,
            cost = next?.cost ?: Resources.ZERO,
            missing = next?.cost?.missingFrom(state.resources) ?: Resources.ZERO,
            durationSec = next?.let { Buffs.speedSeconds(it.seconds, bp) } ?: 0,
            unmetPrereqs = unmet,
            currentEffects = if (slot.level > 0) def.level(slot.level).effects else emptyMap(),
            nextEffects = next?.effects ?: emptyMap(),
            blocker = blocker,
            upgrading = state.timers.firstOrNull { it.kind == TimerKind.BUILD && it.slotId == slotId },
        )
    }

    /** 兵種 [type] の Tier1..4 の表示モデル。 */
    fun troopTiers(
        state: GameState,
        type: TroopType,
        data: GameData,
    ): List<TroopTierView> =
        data.troops.values.filter { it.type == type }.sortedBy { it.tier }.map { troop ->
            val level = Military.trainingBuildingLevel(state, troop)
            val locked = level < troop.unlockBuildingLevel
            TroopTierView(
                troop = troop,
                locked = locked,
                requiredBuildingLevel = troop.unlockBuildingLevel,
                owned = state.troops[troop.id] ?: 0L,
                wounded = state.wounded[troop.id] ?: 0L,
                maxTrainable = if (locked) 0L else maxTrainable(state, troop, data),
            )
        }

    /** 今の資源で 1 回に訓練できる最大数 = min(train_capacity, 各資源 ÷ 単価)。 */
    fun maxTrainable(
        state: GameState,
        troop: TroopDef,
        data: GameData,
    ): Long {
        var max = Military.trainCapacity(state, troop, data)
        for (type in ResourceType.entries) {
            val unit = troop.cost[type]
            if (unit > 0) max = minOf(max, state.resources[type] / unit)
        }
        return maxOf(0L, max)
    }

    /** 兵 [troopId] を [count] 体訓練する場合の確認表示モデル。未知の兵種なら null。 */
    fun trainPreview(
        state: GameState,
        troopId: String,
        count: Long,
        data: GameData,
    ): TrainPreview? {
        val troop = data.troops[troopId] ?: return null
        val cost = Military.trainingCost(troop, maxOf(0L, count))
        val bp = BuffAggregator.compute(state, data)[BuffKey.TRAIN_SPEED]
        val slot = Military.trainingSlot(state, troop)
        return TrainPreview(
            count = count,
            cost = cost,
            missing = cost.missingFrom(state.resources),
            durationSec = Buffs.speedSeconds(Military.baseTrainSeconds(troop, maxOf(1L, count)), bp),
            blocker = (Actions.startTraining(state, troopId, count, data) as? ActionResult.Failure)?.reason,
            running = state.timers.firstOrNull { it.kind == TimerKind.TRAIN && it.slotId == slot?.slotId },
        )
    }

    /** 研究ノード [nodeId] の表示モデル。未知のノードなら null。 */
    fun researchPreview(
        state: GameState,
        nodeId: String,
        data: GameData,
    ): ResearchPreview? {
        val node = data.research[nodeId] ?: return null
        val current = state.research[nodeId] ?: 0
        val next = if (current < node.maxLevel) node.level(current + 1) else null
        val bp = BuffAggregator.compute(state, data)[BuffKey.RESEARCH_SPEED]
        return ResearchPreview(
            node = node,
            currentLevel = current,
            next = next,
            missing = next?.cost?.missingFrom(state.resources) ?: Resources.ZERO,
            durationSec = next?.let { Buffs.speedSeconds(it.seconds, bp) } ?: 0,
            missingAcademy = next?.let { ResearchRules.missingAcademy(state, it) },
            missingNodes = next?.let { ResearchRules.missingNodes(state, it) }.orEmpty(),
            blocker = (Actions.startResearch(state, nodeId, data) as? ActionResult.Failure)?.reason,
            running = state.timers.firstOrNull { it.kind == TimerKind.RESEARCH && it.researchId == nodeId },
        )
    }

    /** 研究一覧の 1 行分（現在 Lv と、次のレベルの前提を満たさないか）。バフ集計や全検査をしない軽量版。 */
    fun researchRow(
        state: GameState,
        nodeId: String,
        data: GameData,
    ): ResearchRow? {
        val node = data.research[nodeId] ?: return null
        val current = state.research[nodeId] ?: 0
        val next = if (current < node.maxLevel) node.level(current + 1) else null
        val locked =
            next != null &&
                (
                    ResearchRules.missingAcademy(state, next) != null ||
                        ResearchRules.missingNodes(state, next).isNotEmpty()
                )
        return ResearchRow(current, locked)
    }

    /** 研究の進行中ジョブ（1 本）。 */
    fun researchJob(state: GameState): TimerJob? = state.timers.firstOrNull { it.kind == TimerKind.RESEARCH }

    /** 治療の進行中ジョブ（1 本）。 */
    fun healJob(state: GameState): TimerJob? = state.timers.firstOrNull { it.kind == TimerKind.HEAL }

    /** 訓練建物 [slotId] の進行中ジョブ。 */
    fun trainJob(
        state: GameState,
        slotId: Int,
    ): TimerJob? = state.timers.firstOrNull { it.kind == TimerKind.TRAIN && it.slotId == slotId }

    /** [job] に使える加速アイテム（カテゴリ一致＋GENERAL）を所持数つきで、短い順に返す。 */
    fun usableItems(
        state: GameState,
        job: TimerJob,
        data: GameData,
    ): List<UsableItem> =
        state.items.mapNotNull { (id, owned) ->
            val item = data.items[id]
            if (item != null && owned > 0 && item.category?.matches(job.kind) == true) UsableItem(item, owned) else null
        }.sortedWith(compareBy({ it.item.seconds }, { it.item.id }))

    /** 残り [remainingSec] を消化するのに必要な個数（所持数で頭打ち。余りが出ても警告しない）。 */
    fun itemsNeeded(
        remainingSec: Long,
        itemSec: Long,
        owned: Int,
    ): Int {
        if (remainingSec <= 0 || itemSec <= 0 || owned <= 0) return 0
        val need = (remainingSec - 1) / itemSec + 1
        return minOf(need, owned.toLong()).toInt()
    }

    /** [job] の残り秒（0 以上）。 */
    fun remainingSec(
        state: GameState,
        job: TimerJob,
    ): Long = maxOf(0L, job.endEpochSec - state.nowEpochSec)

    /** [job] の進捗（0.0〜1.0）。 */
    fun progress(
        state: GameState,
        job: TimerJob,
    ): Float {
        val total = job.endEpochSec - job.startEpochSec
        if (total <= 0) return 1f
        val done = (state.nowEpochSec - job.startEpochSec).coerceIn(0, total)
        return done.toFloat() / total.toFloat()
    }
}
