package com.ttmrase.crownfall.core.map

import com.ttmrase.crownfall.core.model.ResourceType
import kotlinx.serialization.Serializable

/** マップ上の座標参照。 */
@Serializable
data class TileRef(val x: Int, val y: Int)

/** 地形。水と山も通行は可能（簡略化。描画だけが変わる）。 */
enum class Terrain { PLAIN, FOREST, MOUNTAIN, WATER }

/** 固定拠点の種類。 */
enum class StructureType { PLAYER_CASTLE, ALLY_CASTLE, NPC_CASTLE, BARBARIAN_FORT, HOLY_LAND }

/**
 * 固定拠点。[tag] は同盟の城なら `persona_slot_N`（AI ペルソナ枠。M5 で personas.json と対応）か `filler_N`、
 * NPC 城なら王国 ID。[level] は砦の Lv。[power] は NPC 王国の目安戦力（表示用）。
 */
data class Structure(
    val type: StructureType,
    val x: Int,
    val y: Int,
    val level: Int = 0,
    val tag: String = "",
    val power: Long = 0,
)

/** 静的な世界（地形と固定拠点）。seed から毎回再生成でき、セーブには含めない。 */
class WorldMap(
    val size: Int,
    private val terrain: ByteArray,
    val structures: List<Structure>,
) {
    private val byIndex: Map<Int, Structure> = structures.associateBy { it.y * size + it.x }

    /** 座標が範囲内か。 */
    fun inBounds(
        x: Int,
        y: Int,
    ): Boolean = x in 0 until size && y in 0 until size

    /** 地形。 */
    fun terrainAt(
        x: Int,
        y: Int,
    ): Terrain = Terrain.entries[terrain[y * size + x].toInt()]

    /** その座標の固定拠点（無ければ null）。 */
    fun structureAt(
        x: Int,
        y: Int,
    ): Structure? = byIndex[y * size + x]
}

/** 資源ノード（動的）。[remaining] は残量。 */
@Serializable
data class ResourceNode(
    val x: Int,
    val y: Int,
    val type: ResourceType,
    val level: Int,
    val remaining: Long,
)

/** 野蛮人（動的）。 */
@Serializable
data class Barbarian(val x: Int, val y: Int, val level: Int)

/**
 * マップの動的部分（セーブに含める）。[seed] は静的部分の生成 seed、[rngState] は補充用の乱数状態。
 * [nextRefreshEpochSec] が 0 なら未初期化（ロード後に MapBootstrap が初期配置する）。
 */
@Serializable
data class MapState(
    val seed: Long = 0,
    val rngState: Long = 0,
    val nodes: List<ResourceNode> = emptyList(),
    val barbarians: List<Barbarian> = emptyList(),
    val nextRefreshEpochSec: Long = 0,
    val nextRaidEpochSec: Long = 0,
    val raidWarned: Boolean = false,
)

/** 1 タイルの内容（UI・行軍の対象判定用）。 */
data class Tile(
    val x: Int,
    val y: Int,
    val terrain: Terrain,
    val structure: Structure?,
    val node: ResourceNode?,
    val barbarian: Barbarian?,
)
