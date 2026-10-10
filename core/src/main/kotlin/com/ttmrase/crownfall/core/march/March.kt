package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import kotlinx.serialization.Serializable

/** 行軍の種類。RALLY は主催者（プレイヤー）が集結を待ってから砦へ出る。 */
@Serializable
enum class MarchKind { GATHER, ATTACK, SCOUT, RALLY }

/** 行軍の段階。時刻はすべて絶対時刻（R2）。 */
@Serializable
enum class MarchPhase { OUTBOUND, WORKING, WAITING, RETURNING }

/**
 * 行軍 1 隊。[troops] は出撃中の兵（戦闘後は生存兵に置き換わる）、[capacity] は積載量。
 * [phaseStartEpochSec]〜[phaseEndEpochSec] が現在の段階。採集は [workType] / [workAmount] が予約済みの量。
 * 帰還時に [loot] / [lootItems] / [heroExp] を受け取り、[woundedBack] を病院へ入れる。[mailId] は戦闘報告のメール。
 */
@Serializable
data class March(
    val id: Long,
    val kind: MarchKind,
    val troops: Map<String, Long>,
    val heroIds: List<String> = emptyList(),
    val target: TileRef,
    val phase: MarchPhase,
    val phaseStartEpochSec: Long,
    val phaseEndEpochSec: Long,
    val travelSec: Long,
    val capacity: Long = 0,
    val workType: ResourceType? = null,
    val workAmount: Long = 0,
    val loot: Resources = Resources.ZERO,
    val lootItems: Map<String, Int> = emptyMap(),
    val woundedBack: Map<String, Long> = emptyMap(),
    val heroExp: Long = 0,
    val mailId: Long = 0,
    /** AI 主催ラリーに参加した行軍のとき、そのラリーの ID（0 はプレイヤー主催 / 単独）。 */
    val hostId: Long = 0,
    val hostPersona: String = "",
    /** AI 主催ラリーの援軍（参加時に確定する）。 */
    val allyTroops: Map<String, Long> = emptyMap(),
)
