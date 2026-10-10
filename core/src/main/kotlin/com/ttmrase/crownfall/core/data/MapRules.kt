package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.model.Resources
import kotlinx.serialization.Serializable

/** 座標。 */
@Serializable
data class XY(val x: Int, val y: Int)

/** 地形の分布（‰）と平滑化の回数。 */
@Serializable
data class TerrainRules(
    val forestPermille: Int,
    val mountainPermille: Int,
    val waterPermille: Int,
    val smoothingPasses: Int,
)

/** 同盟の城の配置（AI ペルソナ枠 + 名前だけの城）。 */
@Serializable
data class AllyRules(val personaSlots: Int, val fillers: Int, val minRadius: Int, val maxRadius: Int)

/** NPC 王国の城。[power] は表示用の目安戦力。 */
@Serializable
data class KingdomRule(val id: String, val x: Int, val y: Int, val power: Long)

/** 野蛮人の砦（Lv ごとの自城からの距離帯）。 */
@Serializable
data class FortRules(
    val levels: List<Int>,
    val minDistanceByLevel: List<Int>,
    val maxDistanceByLevel: List<Int>,
)

/** 自城からの距離 [upToDistance] 以下に出るレベルの帯。 */
@Serializable
data class LevelBand(val upToDistance: Int, val minLevel: Int, val maxLevel: Int)

/** 資源ノード。 */
@Serializable
data class NodeRules(
    val targetPerType: Int,
    val bands: List<LevelBand>,
    val remainingByLevel: List<Long>,
    val gatherPerHourByLevel: List<Long>,
)

/** 値 [upTo] 以下なら [tier]。 */
@Serializable
data class TierStep(val upTo: Int, val tier: Int)

/** 野蛮人と砦。 */
@Serializable
data class BarbarianRules(
    val target: Int,
    val bands: List<LevelBand>,
    val troopsPerLevelSq: Long,
    val tierByLevel: List<TierStep>,
    val mix: Map<String, Int>,
    val fortEquivLevels: List<Int>,
    val fortScalePct: Long,
    val expPerLevel: Long,
)

/** 行軍の時間パラメータ。 */
@Serializable
data class MarchRules(
    val baseSecPerTile: Long,
    val refSpeed: Long,
    val scoutSecPerTile: Long,
    val rallyWaitSec: Long,
    val maxHeroes: Int,
    val baseSlots: Int,
    val castleLevelPerSlot: Int,
    val troopCapBase: Long,
    val troopCapPerCastleLevel: Long,
)

/** 偵察の精度（Watchtower の Lv が高いほど誤差が小さい）。 */
@Serializable
data class ScoutRules(val baseErrorPct: Int, val errorPctPerWatchtowerLevel: Int, val minErrorPct: Int)

/** 戦利品のアイテム抽選。 */
@Serializable
data class LootEntry(val item: String, val count: Int, val chancePct: Int)

/** 戦利品の表。 */
@Serializable
data class LootRules(val resourcesPerLevel: Resources, val fortMultiplier: Long, val items: List<LootEntry>)

/** ラリー（M4 では援軍は仮のもの）。 */
@Serializable
data class RallyRules(
    val allyTroopsByFortLevel: List<Long>,
    val allyTier: Int,
    val allyMix: Map<String, Int>,
    val participationResourcesPerLevel: Resources,
)

/** 戦闘係数（すべて整数・bp）。 */
@Serializable
data class CombatRules(
    val maxRounds: Int,
    val advantageBp: Long,
    val disadvantageBp: Long,
    val siegeVsTroopsBp: Long,
    val siegeVsWallBp: Long,
    val defenseK: Long,
    val wallAbsorbBp: Long,
    val woundedPct: Long,
    val activeSkillRound: Int,
)

/** NPC 襲撃。 */
@Serializable
data class RaidRules(
    val troopsBase: Long,
    val troopsPerCastleLevel: Long,
    val tierByCastleLevel: List<TierStep>,
    val mix: Map<String, Int>,
    val loadCap: Long,
    val minIntervalSec: Long,
    val maxIntervalSec: Long,
    val firstDelaySec: Long,
    val warnSecPerWatchtowerLevel: Long,
    val maxWarnSec: Long,
)

/** map_rules.json の全体。 */
@Serializable
data class MapRules(
    val size: Int,
    val center: XY,
    val terrain: TerrainRules,
    val allies: AllyRules,
    val kingdoms: List<KingdomRule>,
    val forts: FortRules,
    val holyLand: XY,
    val nodes: NodeRules,
    val barbarians: BarbarianRules,
    val refreshPeriodSec: Long,
    val march: MarchRules,
    val scout: ScoutRules,
    val loot: LootRules,
    val rally: RallyRules,
    val combat: CombatRules,
    val raid: RaidRules,
)
