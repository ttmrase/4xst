package com.ttmrase.crownfall.core.model

import kotlinx.serialization.Serializable

/** タイマーの種類（M1 で使うのは BUILD のみ）。 */
@Serializable
enum class TimerKind { BUILD, RESEARCH, TRAIN, HEAL }

/** 進行中のタイマー。[endEpochSec] は絶対時刻で、完了条件は `now >= endEpochSec`。 */
@Serializable
data class TimerJob(
    val id: Long,
    val kind: TimerKind,
    val slotId: Int? = null,
    val targetLevel: Int? = null,
    val startEpochSec: Long,
    val endEpochSec: Long,
    /** TRAIN: 訓練する兵種 ID。 */
    val troopId: String? = null,
    /** TRAIN: 訓練数。 */
    val count: Long = 0,
    /** RESEARCH: 研究ノード ID（[targetLevel] がこの完了後のレベル）。 */
    val researchId: String? = null,
    /** HEAL: 治療中の兵（兵種 ID → 数）。 */
    val healBatch: Map<String, Long> = emptyMap(),
    /** ヘルプを求めた（同盟メンバーがヘルプしてくれる）。 */
    val helpRequested: Boolean = false,
    /** 受けたヘルプの回数。 */
    val helpCount: Int = 0,
)

/** 期限付きバフの種類。 */
@Serializable
enum class BoostKind { PRODUCTION, BUILD_SPEED }

/** 期限付きバフ。[bp] は basis point（1bp = 0.01%）。 */
@Serializable
data class Boost(val id: String, val kind: BoostKind, val bp: Int, val untilEpochSec: Long)
