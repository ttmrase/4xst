package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.HeroRarity
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

/** アイテムの種類。 */
enum class ItemKind { SPEEDUP, HERO_EXP, SKILL_BOOK, SUMMON_TICKET_NORMAL, SUMMON_TICKET_PREMIUM }

/**
 * アイテム定義。加速は [category] と [seconds]、英雄経験値は [value]（経験値量）を使う。
 * 加速以外では [category] は null。
 */
data class ItemDef(
    val id: String,
    val kind: ItemKind,
    val category: SpeedupCategory?,
    val seconds: Long,
    val value: Long,
)

/** ジェムスキップの区分。残り秒が [upToSec] まで（null は無限）の部分を毎時 [gemsPerHour] で課金。 */
data class SpeedupTier(val upToSec: Long?, val gemsPerHour: Long)

/** 新規ゲームの初期値。[initialLevels] は種類ごとのスロット順のレベル（省略した種類・スロットは 0）。 */
data class NewGameRules(
    val resources: Resources,
    val gems: Long,
    val initialLevels: Map<BuildingType, List<Int>>,
    val items: Map<String, Int>,
)

/** 兵種。三すくみの [counter]（有利な相手）は M4 の戦闘で使う。 */
enum class TroopType(val trainingBuilding: BuildingType) {
    INFANTRY(BuildingType.BARRACKS),
    CAVALRY(BuildingType.STABLE),
    ARCHER(BuildingType.ARCHERY_RANGE),
    SIEGE(BuildingType.SIEGE_WORKSHOP),
}

/** 兵の定義（1 体あたりの値。[upkeepPer100PerHour] は 100 体あたりの毎時 Food 維持費）。 */
data class TroopDef(
    val id: String,
    val type: TroopType,
    val tier: Int,
    val attack: Long,
    val defense: Long,
    val hp: Long,
    val speed: Long,
    val load: Long,
    val trainSec: Long,
    val cost: Resources,
    val upkeepPer100PerHour: Long,
    val power: Long,
    val unlockBuildingLevel: Int,
    val counter: TroopType?,
)

/** 研究の前提となる先行ノード。 */
data class ResearchPrereq(val nodeId: String, val level: Int)

/** 研究ノードの 1 レベル分。[effectBp] はそのレベル時点の累積 bp。 */
data class ResearchLevel(
    val level: Int,
    val cost: Resources,
    val seconds: Long,
    val academyLevel: Int,
    val nodes: List<ResearchPrereq>,
    val effectKey: BuffKey,
    val effectBp: Long,
    val power: Long,
)

/** 研究ノード。 */
data class ResearchNodeDef(val id: String, val branch: String, val maxLevel: Int, val levels: List<ResearchLevel>) {
    /** Lv[level]（1 始まり）の定義。 */
    fun level(level: Int): ResearchLevel = levels[level - 1]
}

/** 英雄のスキル（Lv1..maxSkillLevel の値）。[slot] は "active" か "passive"。効果キーは M4 で使う。 */
data class HeroSkillDef(val slot: String, val effectKey: String, val values: List<Int>)

/** 英雄の定義。 */
data class HeroDef(
    val id: String,
    val rarity: HeroRarity,
    val troopAffinity: TroopType,
    val attack: Long,
    val defense: Long,
    val hp: Long,
    val skills: List<HeroSkillDef>,
)

/** 召喚の種類ごとの設定。確率は bp（合計 10000）。[pityAt] が 0 なら天井なし。 */
data class SummonTable(
    val gold: Long,
    val goldTen: Long,
    val gems: Long,
    val gemsTen: Long,
    val ticketItem: String,
    val rates: Map<HeroRarity, Int>,
    val tenGuarantee: HeroRarity,
    val pityAt: Int,
)

/** 英雄育成の共通ルール。 */
data class HeroRules(
    val expToNext: List<Long>,
    val starLevelCaps: List<Int>,
    val starUpShards: List<Int>,
    val dupShards: Map<HeroRarity, Int>,
    val maxSkillLevel: Int,
    val rarityPower: Map<HeroRarity, Long>,
    val powerPerLevel: Long,
    val powerPerSkillLevel: Long,
    val normal: SummonTable,
    val premium: SummonTable,
) {
    /** 最大レベル。 */
    val maxLevel: Int get() = expToNext.size + 1
}

/** 全体ルール。 */
data class Rules(
    val offlineCapSec: Long,
    val catchUpChunkSec: Long,
    val baseBuildQueues: Int,
    val offlineSummaryMinSec: Long,
    val starterHeroes: List<String>,
    val healCostPct: Long,
    val healTimePct: Long,
    val newGame: NewGameRules,
)

/** ゲームデータ一式（不変・注入可能。シングルトンにしない）。 */
data class GameData(
    val buildings: Map<BuildingType, BuildingDef>,
    val rules: Rules,
    val items: Map<String, ItemDef>,
    val speedupTiers: List<SpeedupTier>,
    val troops: Map<String, TroopDef>,
    val research: Map<String, ResearchNodeDef>,
    val heroes: Map<String, HeroDef>,
    val heroRules: HeroRules,
    val mapRules: MapRules,
    val personas: PersonasFile,
    val allianceRules: AllianceRules,
    val templates: TemplatesFile,
    val offers: OffersFile,
    val shopRules: ShopRules,
    val vip: VipFile,
) {
    /** 建物種 [type] の定義。 */
    fun building(type: BuildingType): BuildingDef = buildings.getValue(type)
}
