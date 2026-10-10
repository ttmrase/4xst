package com.ttmrase.crownfall.core.model

import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.map.TileRef
import kotlinx.serialization.Serializable

/** 端末設定のうち :core が知る必要があるもの（タイムゾーンは :app が設定する）。 */
@Serializable
data class Settings(val utcOffsetSec: Int = 0)

/** 同盟からの要請の種類。 */
@Serializable
enum class RequestKind { RESOURCE, SCOUT }

/** AI メンバーからの要請（期限付き）。 */
@Serializable
data class AllianceRequest(
    val id: Long,
    val persona: String,
    val kind: RequestKind,
    val resource: ResourceType? = null,
    val amount: Long = 0,
    val tile: TileRef? = null,
    val expiresEpochSec: Long,
)

/** 同盟ヘルプ一覧の 1 件（メンバーのタイマーへのヘルプ待ち）。 */
@Serializable
data class HelpEntry(
    val id: Long,
    val member: String,
    val isPersona: Boolean,
    val timerKind: TimerKind,
    val expiresEpochSec: Long,
)

/** 同盟ギフト箱（誰かの「課金」やマイルストーンで届く）。 */
@Serializable
data class GiftBox(
    val id: Long,
    val from: String,
    val sku: String,
    val createdEpochSec: Long,
    val expiresEpochSec: Long,
)

/** AI が主催する砦のラリー。[joinDeadlineEpochSec] までに参加できる。 */
@Serializable
data class AiRally(
    val id: Long,
    val host: String,
    val target: TileRef,
    val fortLevel: Int,
    val joinDeadlineEpochSec: Long,
    val participants: List<String>,
)

/** プレイヤーの行動ログの種類（Yuki などが過去の行動を参照する）。 */
@Serializable
enum class LogKind { HELPED_MEMBER, SENT_RESOURCES, SCOUTED, JOINED_RALLY, DONATED }

@Serializable
data class ActionLogEntry(
    val epochSec: Long,
    val kind: LogKind,
    val persona: String? = null,
    val amount: Long = 0,
)

/** 文面待ちのチャットのきっかけ。[speakers] は作成時に活動中だったペルソナ（先頭が主発話者）。 */
@Serializable
data class PendingChat(
    val id: Long,
    val event: ChatEvent,
    val createdEpochSec: Long,
    val speakers: List<String>,
    val channel: String,
)

/**
 * チャット 1 行。[speaker] はペルソナ ID / `player` / `world:<名前>` / `system`。
 * クイズでは [quizId] と [choices] を持ち、答えると [answered] が選択肢の番号になる（未回答は -1）。
 */
@Serializable
data class ChatMessage(
    val id: Long,
    val channel: String,
    val speaker: String,
    val text: String,
    val epochSec: Long,
    val quizId: String? = null,
    val choices: List<String> = emptyList(),
    val answered: Int = -1,
)

/** 同盟全体の状態（すべて既定値付き。版は上げない）。 */
@Serializable
data class AllianceState(
    /** スケジューラのハッシュの種。0 なら未初期化。 */
    val seed: Long = 0,
    val startEpochSec: Long = 0,
    val favor: Map<String, Int> = emptyMap(),
    val favorLevel: Map<String, Int> = emptyMap(),
    val points: Long = 0,
    val donatedTotal: Long = 0,
    val techExp: Map<String, Long> = emptyMap(),
    val requests: List<AllianceRequest> = emptyList(),
    val helpPool: List<HelpEntry> = emptyList(),
    val gifts: List<GiftBox> = emptyList(),
    val rallies: List<AiRally> = emptyList(),
    val actionLog: List<ActionLogEntry> = emptyList(),
    val pendingChat: List<PendingChat> = emptyList(),
    val history: Map<String, List<ChatMessage>> = emptyMap(),
    val chatRead: Map<String, Long> = emptyMap(),
    val nextId: Long = 1,
    /** 1 日の上限カウンタの対象日（現地日）。 */
    val day: Long = -1,
    val helpedToday: Int = 0,
    val donatedToday: Int = 0,
    val shopBought: Map<String, Int> = emptyMap(),
    val lastGreetingDay: Long = -1,
    val lastQuizEpochSec: Long = 0,
    val quizIndex: Int = 0,
    /** 最後にスケジューラを評価した分（エポック秒 / 60）。同じ分を二重に評価しないための印。 */
    val evalMinute: Long = Long.MIN_VALUE,
    val chatQueuedTotal: Long = 0,
    val helpReceivedTotal: Long = 0,
)
