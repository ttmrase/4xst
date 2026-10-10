package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.ItemDef
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
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
        val bp = Buffs.totalBp(state.boosts, BoostKind.BUILD_SPEED)
        return UpgradePreview(
            slot = slot,
            maxLevel = def.maxLevel,
            nextLevel = next?.level,
            cost = next?.cost ?: Resources.ZERO,
            missing = next?.cost?.missingFrom(state.resources) ?: Resources.ZERO,
            durationSec = next?.let { Buffs.buildSeconds(it.seconds, bp) } ?: 0,
            unmetPrereqs = unmet,
            currentEffects = if (slot.level > 0) def.level(slot.level).effects else emptyMap(),
            nextEffects = next?.effects ?: emptyMap(),
            blocker = blocker,
            upgrading = state.timers.firstOrNull { it.kind == TimerKind.BUILD && it.slotId == slotId },
        )
    }

    /** [job] に使える加速アイテム（カテゴリ一致＋GENERAL）を所持数つきで、短い順に返す。 */
    fun usableItems(
        state: GameState,
        job: TimerJob,
        data: GameData,
    ): List<UsableItem> =
        state.items.mapNotNull { (id, owned) ->
            val item = data.items[id]
            if (item != null && owned > 0 && item.category.matches(job.kind)) UsableItem(item, owned) else null
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
