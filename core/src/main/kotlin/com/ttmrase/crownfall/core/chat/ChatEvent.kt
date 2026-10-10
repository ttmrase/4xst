package com.ttmrase.crownfall.core.chat

import com.ttmrase.crownfall.core.map.TileRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * チャットの「きっかけ」（03 §3）。:core が決定論的に作り、文面は :app / :ai が作る。
 * [key] はテンプレのキー（templates.json）。
 */
@Serializable
sealed interface ChatEvent {
    /** テンプレ・ログ用の種類キー。 */
    val key: String

    @Serializable
    @SerialName("player_helped")
    data class PlayerHelped(val count: Int) : ChatEvent {
        override val key get() = ChatKeys.PLAYER_HELPED
    }

    @Serializable
    @SerialName("player_donated")
    data class PlayerDonated(val amount: Long, val tech: String) : ChatEvent {
        override val key get() = ChatKeys.PLAYER_DONATED
    }

    @Serializable
    @SerialName("player_joined_rally")
    data class PlayerJoinedRally(val rallyId: Long, val won: Boolean, val host: String) : ChatEvent {
        override val key get() = if (won) ChatKeys.PLAYER_JOINED_RALLY else ChatKeys.PLAYER_RALLY_LOST
    }

    @Serializable
    @SerialName("player_castle_up")
    data class PlayerCastleUp(val lv: Int) : ChatEvent {
        override val key get() = ChatKeys.PLAYER_CASTLE_UP
    }

    @Serializable
    @SerialName("player_defeated")
    data class PlayerDefeated(val by: String) : ChatEvent {
        override val key get() = ChatKeys.PLAYER_DEFEATED
    }

    @Serializable
    @SerialName("player_defended")
    data class PlayerDefended(val by: String) : ChatEvent {
        override val key get() = ChatKeys.PLAYER_DEFENDED
    }

    @Serializable
    @SerialName("npc_needs_help")
    data class NpcNeedsHelp(
        val persona: String,
        val resource: String,
        val amount: Long,
        val requestId: Long,
    ) : ChatEvent {
        override val key get() = ChatKeys.NPC_NEEDS_HELP
    }

    @Serializable
    @SerialName("npc_asks_scout")
    data class NpcAsksScout(val persona: String, val tile: TileRef, val requestId: Long) : ChatEvent {
        override val key get() = ChatKeys.NPC_ASKS_SCOUT
    }

    @Serializable
    @SerialName("npc_purchased")
    data class NpcPurchased(val persona: String, val sku: String, val giftId: Long) : ChatEvent {
        override val key get() = ChatKeys.NPC_PURCHASED
    }

    @Serializable
    @SerialName("npc_calls_rally")
    data class NpcCallsRally(
        val persona: String,
        val rallyId: Long,
        val fortLevel: Int,
        val tile: TileRef,
    ) : ChatEvent {
        override val key get() = ChatKeys.NPC_CALLS_RALLY
    }

    @Serializable
    @SerialName("npc_thanks")
    data class NpcThanks(val persona: String, val requestKind: String, val amount: Long) : ChatEvent {
        override val key get() = if (requestKind == "scout") ChatKeys.NPC_THANKS_SCOUT else ChatKeys.NPC_THANKS
    }

    @Serializable
    @SerialName("ambient")
    data class Ambient(val topic: String) : ChatEvent {
        override val key get() = ChatKeys.AMBIENT
    }

    @Serializable
    @SerialName("world_ambient")
    data class WorldAmbient(val topic: String) : ChatEvent {
        override val key get() = ChatKeys.WORLD_AMBIENT
    }

    @Serializable
    @SerialName("daily_greeting")
    data object DailyGreeting : ChatEvent {
        override val key get() = ChatKeys.DAILY_GREETING
    }

    @Serializable
    @SerialName("player_free_text")
    data class PlayerFreeText(val text: String, val channel: String) : ChatEvent {
        override val key get() = ChatKeys.PLAYER_FREE_TEXT
    }

    @Serializable
    @SerialName("event_ranking")
    data class EventRanking(val rank: Int) : ChatEvent {
        override val key get() = ChatKeys.EVENT_RANKING
    }

    @Serializable
    @SerialName("favor_milestone")
    data class FavorMilestone(val persona: String, val lv: Int) : ChatEvent {
        override val key get() = ChatKeys.FAVOR_MILESTONE
    }

    @Serializable
    @SerialName("ponzu_quiz")
    data class PonzuQuiz(val quizId: String) : ChatEvent {
        override val key get() = ChatKeys.PONZU_QUIZ
    }

    @Serializable
    @SerialName("quiz_result")
    data class QuizResult(val quizId: String, val correct: Boolean) : ChatEvent {
        override val key get() = if (correct) ChatKeys.QUIZ_RIGHT else ChatKeys.QUIZ_WRONG
    }

    @Serializable
    @SerialName("vip_up")
    data class VipUp(val lv: Int) : ChatEvent {
        override val key get() = ChatKeys.VIP_UP
    }

    /** 襲撃の段階（偵察された〜開始。結果は player_defended / player_defeated で扱う）。 */
    @Serializable
    @SerialName("raid_stage")
    data class RaidStage(val stage: com.ttmrase.crownfall.core.model.RaidStage) : ChatEvent {
        override val key get() = ChatKeys.raidStage(stage)
    }

    @Serializable
    @SerialName("yuki_return")
    data class YukiReturn(val helpCount: Int) : ChatEvent {
        override val key get() = if (helpCount > 0) ChatKeys.YUKI_RETURN else ChatKeys.YUKI_RETURN_NONE
    }
}

/** テンプレのキーとチャンネル名。 */
object ChatKeys {
    const val PLAYER_HELPED = "player_helped"
    const val PLAYER_DONATED = "player_donated"
    const val PLAYER_JOINED_RALLY = "player_joined_rally"
    const val PLAYER_RALLY_LOST = "player_rally_lost"
    const val PLAYER_CASTLE_UP = "player_castle_up"
    const val PLAYER_DEFEATED = "player_defeated"
    const val PLAYER_DEFENDED = "player_defended"
    const val NPC_NEEDS_HELP = "npc_needs_help"
    const val NPC_ASKS_SCOUT = "npc_asks_scout"
    const val NPC_PURCHASED = "npc_purchased"
    const val NPC_CALLS_RALLY = "npc_calls_rally"
    const val NPC_THANKS = "npc_thanks"
    const val NPC_THANKS_SCOUT = "npc_thanks_scout"
    const val AMBIENT = "ambient"
    const val WORLD_AMBIENT = "world_ambient"
    const val DAILY_GREETING = "daily_greeting"
    const val PLAYER_FREE_TEXT = "player_free_text"
    const val EVENT_RANKING = "event_ranking"
    const val FAVOR_MILESTONE = "favor_milestone"
    const val PONZU_QUIZ = "ponzu_quiz"
    const val QUIZ_RIGHT = "quiz_right"
    const val QUIZ_WRONG = "quiz_wrong"
    const val YUKI_RETURN = "yuki_return"
    const val YUKI_RETURN_NONE = "yuki_return_none"
    const val VIP_UP = "vip_up"
    const val RAID_SCOUTED = "raid_scouted"
    const val RAID_MARCHING = "raid_marching"
    const val RAID_1H = "raid_1h"
    const val RAID_15M = "raid_15m"
    const val RAID_STARTED = "raid_started"

    /** 襲撃の段階 [stage] のテンプレキー（RESULT は専用のキーを持たないので開始と同じ）。 */
    fun raidStage(stage: com.ttmrase.crownfall.core.model.RaidStage): String =
        when (stage) {
            com.ttmrase.crownfall.core.model.RaidStage.SCOUTED -> RAID_SCOUTED
            com.ttmrase.crownfall.core.model.RaidStage.MARCHING -> RAID_MARCHING
            com.ttmrase.crownfall.core.model.RaidStage.T_MINUS_1H -> RAID_1H
            com.ttmrase.crownfall.core.model.RaidStage.T_MINUS_15M -> RAID_15M
            com.ttmrase.crownfall.core.model.RaidStage.STARTED, com.ttmrase.crownfall.core.model.RaidStage.RESULT ->
                RAID_STARTED
        }

    /** 自由入力の分類（キーは `player_free_text.<分類>`）。 */
    val FREE_TEXT_CATEGORIES = listOf("resource", "battle", "research", "chat", "thanks", "question")

    /** 全テンプレキー（自由入力は分類ごと）。 */
    val ALL: List<String> =
        listOf(
            PLAYER_HELPED, PLAYER_DONATED, PLAYER_JOINED_RALLY, PLAYER_RALLY_LOST, PLAYER_CASTLE_UP,
            PLAYER_DEFEATED, PLAYER_DEFENDED, NPC_NEEDS_HELP, NPC_ASKS_SCOUT, NPC_PURCHASED, NPC_CALLS_RALLY,
            NPC_THANKS, NPC_THANKS_SCOUT, AMBIENT, WORLD_AMBIENT, DAILY_GREETING, EVENT_RANKING,
            FAVOR_MILESTONE, PONZU_QUIZ, QUIZ_RIGHT, QUIZ_WRONG, YUKI_RETURN, YUKI_RETURN_NONE, VIP_UP,
            RAID_SCOUTED, RAID_MARCHING, RAID_1H, RAID_15M, RAID_STARTED,
        ) + FREE_TEXT_CATEGORIES.map { "$PLAYER_FREE_TEXT.$it" }

    /** 同盟チャンネル。 */
    const val ALLIANCE = "alliance"

    /** ワールドチャンネル。 */
    const val WORLD = "world"

    /** ペルソナとの DM チャンネル名。 */
    fun dm(personaId: String) = "dm_$personaId"
}
