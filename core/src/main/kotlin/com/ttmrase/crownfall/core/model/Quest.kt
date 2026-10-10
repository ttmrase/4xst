package com.ttmrase.crownfall.core.model

import kotlinx.serialization.Serializable

/**
 * クエストの進捗。カウンタは GameEvent からだけ加算する（`QuestTracker.track`）。
 * [counters] は通算、デイリーは現地日 [dailyDay] のカウンタ [dailyCounts] とポイント受け取り済みの項目 [dailyClaimed]。
 * [claimed] はチャプター・同盟クエスト・実績の受け取り済み ID。
 */
@Serializable
data class QuestState(
    val counters: Map<String, Long> = emptyMap(),
    val claimed: Set<String> = emptySet(),
    val dailyDay: Long = -1,
    val dailyCounts: Map<String, Long> = emptyMap(),
    val dailyClaimed: Set<String> = emptySet(),
    val dailyChests: Set<Int> = emptySet(),
)

/**
 * 週替わりイベントの進捗。[weekId] は現在のイベントの週（現地の月曜始まり）、
 * [selfScore] は自力のスコア（戦力成長祭は戦力の増加分から計算するので使わない）、
 * [bonusScore] は逆転パックのボーナス。[warned] は終了 24h 前の処理済み。
 */
@Serializable
data class EventState(
    val weekId: Long = -1,
    val selfScore: Long = 0,
    val bonusScore: Long = 0,
    val basePower: Long = 0,
    val warned: Boolean = false,
)
