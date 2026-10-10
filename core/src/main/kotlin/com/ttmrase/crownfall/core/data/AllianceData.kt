package com.ttmrase.crownfall.core.data

import com.ttmrase.crownfall.core.model.Resources
import kotlinx.serialization.Serializable

/** 活動時間帯の 1 区間（現地時刻の [start] 時以上 [end] 時未満。24 を超える [end] は翌日に続く）。 */
@Serializable
data class HourRange(val start: Int, val end: Int)

/** 性格の 5 軸（0〜1000）。 */
@Serializable
data class Personality(
    val extraversion: Int,
    val caring: Int,
    val aggression: Int,
    val meticulous: Int,
    val humor: Int,
)

/** 口調。[endings] は語尾、[emojiPermille] は絵文字の頻度（‰）、[tilde] は波線を付けるか。 */
@Serializable
data class SpeechStyle(
    val endings: List<String>,
    val emojiPermille: Int,
    val punctuation: String,
    val tilde: Boolean,
    val samples: List<String>,
)

/** AI ペルソナ（03 §2）。並び順がマップの persona_slot_N に対応する。 */
@Serializable
data class PersonaDef(
    val id: String,
    val displayName: String,
    val rank: Int,
    val vipLv: Int,
    val region: String,
    val activeHours: List<HourRange>,
    val personality: Personality,
    val speechStyle: SpeechStyle,
    val specialty: String,
    /** (日数, 城 Lv) の折れ線。日数は同盟開始からの経過日。 */
    val castleLvCurve: List<List<Int>>,
    val spendingHabit: Int,
    val favorThresholds: List<Int>,
    val nicknameSuffix: String,
    val troopTier: Int,
)

/** 名前・城 Lv・最終ログインだけの同盟メンバー。 */
@Serializable
data class FillerDef(val name: String, val castleLv: Int, val lastLoginHoursAgo: Int)

/** personas.json。 */
@Serializable
data class PersonasFile(
    val personas: List<PersonaDef>,
    val fillerMembers: List<FillerDef>,
    val worldNpcNames: List<String>,
    val allianceName: String,
    val allianceTag: String,
) {
    /** ペルソナ ID から定義。 */
    fun persona(id: String): PersonaDef? = personas.firstOrNull { it.id == id }
}

@Serializable
data class AllianceCaps(
    val pendingChat: Int,
    val history: Int,
    val actionLog: Int,
    val openRequests: Int,
    val openRallies: Int,
    val helpPool: Int,
    val gifts: Int,
)

@Serializable
data class AllianceDurations(val request: Long, val rallyJoin: Long, val helpEntry: Long, val gift: Long)

/** 毎分あたりの発生確率（‰）。 */
@Serializable
data class AllianceChances(
    val ambient: Int,
    val worldAmbient: Int,
    val needsHelp: Int,
    val asksScout: Int,
    val purchase: Int,
    val johnnyPurchase: Int,
    val aiRally: Int,
    val memberHelpRequest: Int,
    val quiz: Int,
    val yukiReturn: Int,
    val aiHelpOnPlayerTimer: Int,
)

@Serializable
data class HelpRules(
    val reductionPct: Int,
    val minReductionSec: Long,
    val dailyLimit: Int,
    val pointsPerHelp: Long,
    val favorPerHelp: Int,
    val favorEveryHelps: Int,
    val minHelpsPerTimer: Int,
)

@Serializable
data class RequestRules(
    val amountBase: Long,
    val amountPerCastleLv: Long,
    val feeBpBase: Long,
    val feeBpReductionPerTradingPostLevel: Long,
    val minFeeBp: Long,
    val favor: Int,
    val points: Long,
    val scoutFavor: Int,
    val scoutPoints: Long,
)

@Serializable
data class DonateRules(
    val resourceUnit: Long,
    val gemUnit: Long,
    val techExpPerResourceUnit: Long,
    val techExpPerGemUnit: Long,
    val pointsPerResourceUnit: Long,
    val pointsPerGemUnit: Long,
    val favor: Int,
    val dailyLimit: Int,
)

/** 同盟テック 1 種。[expPerLevel] は累積経験値、[bpPerLevel] はそのレベルの累積 bp。 */
@Serializable
data class TechDef(
    val id: String,
    val buff: String,
    val expPerLevel: List<Long>,
    val bpPerLevel: List<Long>,
)

/** 同盟ショップの品。[item] か [resources] のどちらか。 */
@Serializable
data class ShopEntry(
    val id: String,
    val item: String? = null,
    val count: Int = 1,
    val resources: Resources? = null,
    val cost: Long,
    val dailyLimit: Int,
)

/** ギフト箱の報酬候補。 */
@Serializable
data class GiftReward(
    val weight: Int,
    val resources: Resources? = null,
    val item: String? = null,
    val count: Int = 1,
    val gems: Long = 0,
)

@Serializable
data class AiRallyRules(
    val hosts: List<String>,
    val extraParticipants: Int,
    val troopsPerCastleLv: Long,
    val maxFortLevel: Int,
    val castleLvPerFortLevel: Int,
    val allyMix: Map<String, Int>,
)

@Serializable
data class QuizRule(val id: String, val correct: Int)

@Serializable
data class QuizSettings(val correctFavor: Int, val wrongFavor: Int, val minIntervalSec: Long)

@Serializable
data class FavorRules(val milestoneGiftResources: Resources, val milestoneGiftGems: Long)

@Serializable
data class LogRules(val helpWindowSec: Long)

/** alliance_rules.json。 */
@Serializable
data class AllianceRules(
    val schedulerPeriodSec: Long,
    val caps: AllianceCaps,
    val durationsSec: AllianceDurations,
    val chancePermillePerMinute: AllianceChances,
    val help: HelpRules,
    val request: RequestRules,
    val donate: DonateRules,
    val techs: List<TechDef>,
    val shop: List<ShopEntry>,
    val gifts: List<GiftReward>,
    val rally: AiRallyRules,
    val quizzes: List<QuizRule>,
    val quiz: QuizSettings,
    val favor: FavorRules,
    val log: LogRules,
) {
    /** 同盟テック [id]。 */
    fun tech(id: String): TechDef? = techs.firstOrNull { it.id == id }
}

/** クイズの文面（選択肢の並び順は [AllianceRules.quizzes] の correct が指す）。 */
@Serializable
data class QuizText(val question: String, val options: List<String>)

/** templates.json。`templates[eventKey][personaId or "*"]` がテンプレ一覧。 */
@Serializable
data class TemplatesFile(
    val templates: Map<String, Map<String, List<String>>>,
    val quizzes: Map<String, QuizText>,
    /** 表示名のテーブル（`resource.food`、`tech.<id>`、`sku.<id>`、`topic.<id>`、`call.default`）。 */
    val labels: Map<String, String> = emptyMap(),
)
