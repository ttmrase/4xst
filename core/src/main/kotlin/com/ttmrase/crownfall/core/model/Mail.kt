package com.ttmrase.crownfall.core.model

import com.ttmrase.crownfall.core.map.TileRef
import kotlinx.serialization.Serializable

/** 戦闘の種類。 */
@Serializable
enum class BattleKind { BARBARIAN, FORT, RAID }

/** 戦闘報告（BATTLE メールの本文。ID と数値だけで、表示は :app）。 */
@Serializable
data class BattleReport(
    val kind: BattleKind,
    val tile: TileRef? = null,
    /** 野蛮人 / 砦の Lv。襲撃では 0。 */
    val level: Int = 0,
    val attackerWon: Boolean,
    val rounds: Int,
    val attackerInitial: Map<String, Long>,
    val attackerDead: Map<String, Long>,
    val attackerWounded: Map<String, Long>,
    val defenderInitial: Map<String, Long>,
    val defenderDead: Map<String, Long>,
    val defenderWounded: Map<String, Long> = emptyMap(),
    /** プレイヤーが得た（襲撃では失った）資源。 */
    val loot: Resources = Resources.ZERO,
    val lootItems: Map<String, Int> = emptyMap(),
    val heroIds: List<String> = emptyList(),
    val heroExp: Long = 0,
    /** ラリーで合算した同盟の仮の援軍。 */
    val allyTroops: Map<String, Long> = emptyMap(),
    /** 病院に入りきらず死亡した負傷兵（帰還時に確定）。 */
    val overflowDead: Map<String, Long> = emptyMap(),
)

/** 偵察報告。[troopsLow]/[troopsHigh] は Watchtower の Lv に応じた誤差の幅。 */
@Serializable
data class ScoutReport(
    val tile: TileRef,
    val targetKind: String,
    val level: Int,
    val errorPct: Int,
    val troopsLow: Map<String, Long>,
    val troopsHigh: Map<String, Long>,
    val nodeType: ResourceType? = null,
    val nodeRemaining: Long = 0,
    val power: Long = 0,
)

/** メールの種類。RECEIPT / ALLIANCE は M5 / M6 で使う予約。 */
@Serializable
enum class MailKind { BATTLE, SCOUT, SYSTEM, RECEIPT, ALLIANCE }

/** メール 1 通。本文は ID と数値だけ（[systemCode] と [params]。表示は :app）。 */
@Serializable
data class Mail(
    val id: Long,
    val kind: MailKind,
    val epochSec: Long,
    val read: Boolean = false,
    val battle: BattleReport? = null,
    val scout: ScoutReport? = null,
    val systemCode: String? = null,
    val params: Map<String, Long> = emptyMap(),
)
