package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerKind

/** 建物 1 レベル分の定義（[seconds] は L-1→L にかかる秒）。 */
data class BuildingLevel(
    val level: Int,
    val cost: Resources,
    val seconds: Long,
    val prereqs: List<Prereq>,
    val effects: Map<String, Long>,
    val power: Long,
)

/** 建物種ごとの定義。[levels] は Lv1..[maxLevel] が連続して並ぶ。 */
data class BuildingDef(
    val type: BuildingType,
    val count: Int,
    val maxLevel: Int,
    val levels: List<BuildingLevel>,
) {
    /** Lv[level]（1 始まり）の定義。 */
    fun level(level: Int): BuildingLevel = levels[level - 1]

    /** Lv[level] の効果値 [key]。level 0 や未定義キーは 0。 */
    fun effect(
        level: Int,
        key: String,
    ): Long = if (level <= 0) 0 else level(level).effects[key] ?: 0
}

/** 加速アイテムのカテゴリ。GENERAL はどの種類のタイマーにも使える。 */
enum class SpeedupCategory {
    GENERAL,
    BUILD,
    RESEARCH,
    TRAIN,
    HEAL,
    ;

    /** このカテゴリのアイテムを [kind] のタイマーに使えるか。 */
    fun matches(kind: TimerKind): Boolean =
        when (this) {
            GENERAL -> true
            BUILD -> kind == TimerKind.BUILD
            RESEARCH -> kind == TimerKind.RESEARCH
            TRAIN -> kind == TimerKind.TRAIN
            HEAL -> kind == TimerKind.HEAL
        }
}

/** 加速アイテム定義。 */
data class ItemDef(val id: String, val category: SpeedupCategory, val seconds: Long)

/** ジェムスキップの区分。残り秒が [upToSec] まで（null は無限）の部分を毎時 [gemsPerHour] で課金。 */
data class SpeedupTier(val upToSec: Long?, val gemsPerHour: Long)

/** 新規ゲームの初期値。[initialLevels] は種類ごとのスロット順のレベル（省略した種類・スロットは 0）。 */
data class NewGameRules(
    val resources: Resources,
    val gems: Long,
    val initialLevels: Map<BuildingType, List<Int>>,
    val items: Map<String, Int>,
)

/** 全体ルール。 */
data class Rules(
    val offlineCapSec: Long,
    val catchUpChunkSec: Long,
    val baseBuildQueues: Int,
    val offlineSummaryMinSec: Long,
    val starterHeroes: List<String>,
    val newGame: NewGameRules,
)

/** ゲームデータ一式（不変・注入可能。シングルトンにしない）。 */
class GameData(
    val buildings: Map<BuildingType, BuildingDef>,
    val rules: Rules,
    val items: Map<String, ItemDef>,
    val speedupTiers: List<SpeedupTier>,
) {
    /** 建物種 [type] の定義。 */
    fun building(type: BuildingType): BuildingDef = buildings.getValue(type)
}
