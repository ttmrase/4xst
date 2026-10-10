package com.ttmrase.crownfall.core.data

import kotlinx.serialization.Serializable

/** 襲撃の 1 段階。[offsetSec] は襲撃時刻の何秒前か、[minWatchtowerLv] は段階が見えるために必要な物見の塔の Lv。 */
@Serializable
data class RaidStageRule(val stage: String, val offsetSec: Long, val minWatchtowerLv: Int)

@Serializable
data class RaidNotifyRules(val stages: List<RaidStageRule>)

/** 強度ごとの通知。[hourlyCap] は 1 時間あたりの上限（襲撃は対象外）、[categories] は有効なカテゴリ。 */
@Serializable
data class IntensityRule(val hourlyCap: Int, val categories: List<String>)

/** notification_rules.json（通知の段階・強度・先読みの設定）。 */
@Serializable
data class NotificationRules(
    val raid: RaidNotifyRules,
    val intensity: Map<String, IntensityRule>,
    val lookaheadHorizonSec: Long,
    val offerLeadSec: List<Long>,
    val eventEndingLeadSec: Long,
    val comebackDelaysSec: List<Long>,
    val dailyReminderLocalMinute: Int,
    val loginBonusLocalMinute: Int,
    val freeWheelLocalMinute: Int,
)
