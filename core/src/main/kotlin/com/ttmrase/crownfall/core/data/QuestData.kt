package com.ttmrase.crownfall.core.data

import kotlinx.serialization.Serializable

/**
 * クエストの条件。種類 [type] ごとに使う項目が決まる:
 * building(building, lv) / power(value) / research(total) / heroes(count) / counter(key, target)。
 */
@Serializable
data class QuestCond(
    val type: String,
    val building: String? = null,
    val lv: Int = 0,
    val value: Long = 0,
    val total: Int = 0,
    val count: Int = 0,
    val key: String? = null,
    val target: Long = 0,
)

/** チャプターのタスク 1 つ。 */
@Serializable
data class QuestTaskDef(val id: String, val cond: QuestCond)

/** メインチャプター。全タスクを達成すると [reward] を受け取れる。 */
@Serializable
data class ChapterDef(val id: String, val tasks: List<QuestTaskDef>, val reward: PackContents)

/** デイリー 1 項目。[key] のその日のカウンタが [target] に達すると [points] を受け取れる。 */
@Serializable
data class DailyDef(val id: String, val key: String, val target: Long, val points: Int)

/** デイリー宝箱（ポイントが [points] に達したら受け取れる）。 */
@Serializable
data class ChestDef(val tier: Int, val points: Int, val reward: PackContents)

/** 同盟クエスト・実績の 1 件（累積の条件を満たすと 1 回だけ受け取れる）。 */
@Serializable
data class QuestDef(val id: String, val cond: QuestCond, val reward: PackContents)

/** quests.json。 */
@Serializable
data class QuestsFile(
    val chapters: List<ChapterDef>,
    val dailies: List<DailyDef>,
    val chests: List<ChestDef>,
    val alliance: List<QuestDef>,
    val achievements: List<QuestDef>,
)

/** イベント 1 種。[kind] は HUNT / GATHER / POWER。[par] は一生懸命遊んだ人の 1 週間のおおよそのスコア。 */
@Serializable
data class EventDef(
    val id: String,
    val kind: String,
    val par: Long,
    val perLevel: Long = 0,
    val fortMultiplier: Long = 1,
    val lootPerPoint: Long = 0,
)

@Serializable
data class NpcDef(val name: String, val alliance: String)

@Serializable
data class LeaderRules(
    val min: Int,
    val max: Int,
    val followMinPermille: Int,
    val followMaxPermille: Int,
    val parMinPermille: Int,
    val parMaxPermille: Int,
)

@Serializable
data class FollowerRules(val parMinPermille: Int, val parMaxPermille: Int)

@Serializable
data class RankReward(val from: Int, val to: Int, val reward: PackContents)

@Serializable
data class Participation(val minScore: Long)

/** events.json。 */
@Serializable
data class EventsFile(
    val rotation: List<String>,
    val events: List<EventDef>,
    val npcs: List<NpcDef>,
    val leaders: LeaderRules,
    val followers: FollowerRules,
    val warnBeforeEndSec: Long,
    val bonusMarginPermille: Int,
    val rewards: List<RankReward>,
    val participation: Participation,
) {
    /** イベント [id]。 */
    fun event(id: String): EventDef? = events.firstOrNull { it.id == id }
}
