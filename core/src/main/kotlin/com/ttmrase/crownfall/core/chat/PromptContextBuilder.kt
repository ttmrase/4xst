package com.ttmrase.crownfall.core.chat

import com.ttmrase.crownfall.core.alliance.Personas
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PersonaDef
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.PendingChat
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.util.ShortNumber

/** 「あなたは AI？」への対応（設定 `revealAi`）。 */
enum class RevealAi { AMBIGUOUS, ADMIT }

/** LLM に渡す会話の 1 行。 */
data class PromptTurn(val speaker: String, val text: String)

/** LLM 用のプロンプト。[system] は世界設定と出力ルール、[turns] は直近チャット + きっかけの説明。 */
data class Prompt(val system: String, val turns: List<PromptTurn>)

/**
 * 小型モデル向けに絞ったペルソナの要約。[displayName] は表示名（ID ではない）、[callName] はプレイヤーの呼び方、
 * [voice] は口調と性格の短い説明、[examples] は口調の例（定型文から）。
 */
data class PersonaBrief(
    val id: String,
    val displayName: String,
    val callName: String,
    val voice: String,
    val examples: List<String>,
    /** 役職と得意分野（例「R5・同盟のリーダー」）。標準プロファイルで使う。 */
    val role: String = "",
    /** 性格の 5 軸を言葉にしたもの（例「社交的、面倒見がよい」）。 */
    val traits: String = "",
    /** プレイヤーとの関係を言葉にしたもの（初対面 / 顔なじみ / 親しい / 相棒）。 */
    val relation: String = "",
)

/**
 * プロンプトの組み立て（純粋な文字列生成。03 §4）。
 * 端末 ID・連絡先・API キーは決して含めない。ゲーム内の名前（プレイヤー名）と、ゲーム状態の要約だけを渡す。
 */
object PromptContextBuilder {
    private const val RECENT_CHAT = 20
    private const val RECENT_EVENTS = 5
    private const val LOW_PCT = 10
    private const val HIGH_PCT = 80
    private const val PERCENT = 100
    private const val SECONDS_PER_DAY = 86_400L
    private const val BRIEF_EXAMPLES_MAX = 5
    private const val STATUS_EVENTS = 2
    private const val ALLIANCE_SIZE = 30
    private const val AXIS_HIGH = 650
    private const val AXIS_LOW = 350
    private const val FAVOR_MAX = 4
    private const val BRIEF_ENDINGS = 3
    private const val BRIEF_TRAITS = 2
    private const val TRAIT_HIGH = 700
    private const val TRAIT_LOW = 300

    /** [pending] に対するプロンプト。 */
    fun build(
        state: GameState,
        pending: PendingChat,
        data: GameData,
        reveal: RevealAi = RevealAi.AMBIGUOUS,
    ): Prompt {
        val history = state.alliance.history[pending.channel].orEmpty().takeLast(RECENT_CHAT)
        val trigger = PromptTurn("system", describe(state, pending, data))
        val turns = history.map { PromptTurn(it.speaker, it.text) } + trigger
        return Prompt(system(state, pending, data, reveal), turns)
    }

    /** 小型モデル向けに、発言者ごとの要約（表示名・呼び方・口調・例文）を作る。 */
    fun briefs(
        state: GameState,
        pending: PendingChat,
        data: GameData,
    ): List<PersonaBrief> {
        val default = data.templates.labels["call.default"] ?: "領主さん"
        return pending.speakers.mapNotNull { data.personas.persona(it) }.map { p ->
            val lv = state.alliance.favorLevel[p.id] ?: 0
            brief(p, Personas.callName(p, lv, state.playerName, default), lv)
        }
    }

    /** [p] の要約。[callName] はプレイヤーの呼び方、[favorLv] は好感度レベル（0..4）。 */
    fun brief(
        p: PersonaDef,
        callName: String,
        favorLv: Int = 0,
    ): PersonaBrief =
        PersonaBrief(
            id = p.id,
            displayName = p.displayName,
            callName = callName,
            voice = voiceOf(p),
            examples = p.speechStyle.samples.sortedBy { it.length }.take(BRIEF_EXAMPLES_MAX),
            role = roleOf(p),
            traits = traitsOf(p),
            relation = relationOf(favorLv),
        )

    /** 同盟の 1 行（標準プロファイルの「世界」）。 */
    fun worldLine(data: GameData): String =
        "[${data.personas.allianceTag}] ${data.personas.allianceName}（メンバー ${ALLIANCE_SIZE} 名）"

    /**
     * 標準プロファイルの「状況」の材料: 城 Lv、不足・満杯近い資源だけ、直近のできごと（最大 2 件）。
     * 普通の資源は書かない（毎回ゲームの状況に触れさせないため）。
     */
    fun statusLine(
        state: GameState,
        data: GameData,
    ): String {
        val cap = Simulation.storageCap(state, data)
        val notable =
            ResourceType.entries.mapNotNull {
                val pct = if (cap > 0) (state.resources[it] * PERCENT / cap).toInt() else 0
                val label = data.templates.labels["resource.${it.name.lowercase()}"] ?: it.name
                when {
                    pct < LOW_PCT -> "${label}が不足"
                    pct > HIGH_PCT -> "${label}が満杯近い"
                    else -> null
                }
            }
        val events = recentEvents(state).take(STATUS_EVENTS)
        return buildString {
            append("城Lv${Marching.castleLevel(state)}。")
            if (notable.isNotEmpty()) append(notable.joinToString("、")).append("。")
            if (events.isNotEmpty()) append("直近: ").append(events.joinToString("、")).append("。")
        }
    }

    private fun roleOf(p: PersonaDef): String {
        val job =
            when (p.specialty) {
                "leader" -> "同盟のリーダー"
                "war" -> "戦闘担当"
                "newbie" -> "初心者"
                "gather" -> "採集担当"
                "analyst" -> "分析担当"
                "spender" -> "課金が大好きなメンバー"
                "returner" -> "久しぶりに戻ってきたメンバー"
                "veteran" -> "古参のメンバー"
                else -> "メンバー"
            }
        return "R${p.rank}の$job"
    }

    private fun traitsOf(p: PersonaDef): String {
        val t = p.personality
        val words =
            listOfNotNull(
                axis(t.extraversion, "社交的", "控えめ"),
                axis(t.caring, "面倒見がよい", "ドライ"),
                axis(t.aggression, "好戦的", "穏やか"),
                axis(t.meticulous, "几帳面", "おおらか"),
                axis(t.humor, "ユーモアがある", "真面目"),
            )
        return words.joinToString("、")
    }

    private fun axis(
        v: Int,
        high: String,
        low: String,
    ): String? =
        when {
            v >= AXIS_HIGH -> high
            v <= AXIS_LOW -> low
            else -> null
        }

    private fun relationOf(favorLv: Int): String =
        when {
            favorLv <= 0 -> "初対面"
            favorLv == 1 -> "顔なじみ"
            favorLv < FAVOR_MAX -> "親しい"
            else -> "相棒"
        }

    /** 会話履歴の発言者 ID から表示名を引く表（ペルソナ、プレイヤー、ワールドの NPC）。 */
    fun names(
        state: GameState,
        data: GameData,
    ): Map<String, String> =
        data.personas.personas.associate { it.id to it.displayName } +
            ("player" to state.playerName.ifBlank { data.templates.labels["call.default"] ?: "領主" })

    /** きっかけの説明（1 文）。小型モデルのユーザーメッセージの先頭に置く。 */
    fun situation(
        state: GameState,
        pending: PendingChat,
        data: GameData,
    ): String {
        // きっかけの文には内部の ID（kaede など）が入ることがある。小型モデルには表示名だけを見せる。
        var text = situationText(state, pending, data)
        for (p in data.personas.personas) {
            text = text.replace(Regex("(?<![A-Za-z0-9_])${Regex.escape(p.id)}(?![A-Za-z0-9_])"), p.displayName)
        }
        return text
    }

    private fun voiceOf(p: PersonaDef): String {
        val region =
            when (p.region) {
                "kansai" -> "関西弁"
                "de" -> "英語まじり"
                "us" -> "ノリが軽い"
                else -> ""
            }
        val traits =
            buildList {
                val t = p.personality
                if (t.extraversion >= TRAIT_HIGH) add("社交的")
                if (t.extraversion <= TRAIT_LOW) add("寡黙")
                if (t.caring >= TRAIT_HIGH) add("面倒見がよい")
                if (t.aggression >= TRAIT_HIGH) add("好戦的")
                if (t.meticulous >= TRAIT_HIGH) add("几帳面")
                if (t.humor >= TRAIT_HIGH) add("ユーモアがある")
            }
        val ends = p.speechStyle.endings.take(BRIEF_ENDINGS).joinToString("・")
        val head = listOf(region, "語尾は$ends").filter { it.isNotEmpty() }.joinToString("、")
        return if (traits.isEmpty()) head else "$head。${traits.take(BRIEF_TRAITS).joinToString("で")}"
    }

    private fun system(
        state: GameState,
        pending: PendingChat,
        data: GameData,
        reveal: RevealAi,
    ): String {
        val speakers = pending.speakers.mapNotNull { data.personas.persona(it) }
        val b = StringBuilder()
        b.appendLine("あなたはスマホ向け 4X 戦略ゲーム「Crownfall」の同盟チャットを演じる脚本家です。")
        b.appendLine("同盟名は [${data.personas.allianceTag}] ${data.personas.allianceName}（メンバー 30 名）。プレイヤーは領主として所属しています。")
        b.appendLine()
        b.appendLine("## 発言するキャラクター")
        for (p in speakers) {
            val lv = state.alliance.favorLevel[p.id] ?: 0
            val call = Personas.callName(p, lv, state.playerName, data.templates.labels["call.default"] ?: "領主さん")
            b.appendLine("- id=${p.id} 名前=${p.displayName} 役職=R${p.rank} 役割=${p.specialty} 好感度Lv=$lv プレイヤーの呼び方=$call")
            b.appendLine("  口調の例: " + p.speechStyle.samples.take(3).joinToString(" / "))
        }
        b.appendLine()
        b.appendLine("## 状況")
        b.appendLine(stateSummary(state, data))
        b.appendLine()
        b.appendLine("## ルール")
        b.appendLine("- 短く自然に、1 行 1〜2 文の日本語で。絵文字は使いすぎない。毎回ゲームの状況に触れなくてよい。軽い脱線やユーモアは歓迎。")
        b.appendLine("- 個人情報（本名・住所・連絡先など）を聞かない。URL を書かない。現実のお金の話は「擬似課金」の世界観の範囲で。")
        b.appendLine(
            when (reveal) {
                RevealAi.AMBIGUOUS -> "- 「あなたは AI ですか」と聞かれても、否定も断定もせず軽くはぐらかす。ゲームの状態は変えない。"
                RevealAi.ADMIT -> "- 「あなたは AI ですか」と聞かれたら、AI が演じるキャラクターだと正直に答える。ゲームの状態は変えない。"
            },
        )
        b.appendLine()
        b.appendLine("## 出力形式")
        b.append("次の JSON だけを出力する: {\"lines\":[{\"speaker\":\"<キャラクターのid>\",\"text\":\"<発言>\"}]}")
        return b.toString()
    }

    private fun stateSummary(
        state: GameState,
        data: GameData,
    ): String {
        val cap = Simulation.storageCap(state, data)
        val b = StringBuilder()
        b.append("城Lv${Marching.castleLevel(state)}、戦力${ShortNumber.format(PowerCalculator.total(state, data))}。")
        b.append("資源: ")
        b.append(
            ResourceType.entries.joinToString("、") {
                val have = state.resources[it]
                val label = data.templates.labels["resource.${it.name.lowercase()}"] ?: it.name
                val pct = if (cap > 0) (have * PERCENT / cap).toInt() else 0
                val level =
                    if (pct < LOW_PCT) {
                        "不足"
                    } else if (pct > HIGH_PCT) {
                        "満杯近い"
                    } else {
                        "普通"
                    }
                "$label$level"
            },
        )
        b.append("。直近のできごと: ")
        val events = recentEvents(state)
        b.append(if (events.isEmpty()) "特になし" else events.joinToString("、"))
        b.append("。進行中のイベント順位: なし。")
        return b.toString()
    }

    /** 直近のできごと（メールと行動ログから新しい順に最大 5 件）。 */
    fun recentEvents(state: GameState): List<String> {
        val fromMails =
            state.mails.map { m ->
                val text =
                    when {
                        m.battle != null -> battleText(m.battle.kind, m.battle.attackerWon)
                        m.kind == MailKind.SCOUT -> "偵察報告を受け取った"
                        else -> "お知らせ"
                    }
                m.epochSec to text
            }
        val fromLog =
            state.alliance.actionLog.map {
                it.epochSec to
                    when (it.kind) {
                        LogKind.HELPED_MEMBER -> "同盟メンバーにヘルプした"
                        LogKind.SENT_RESOURCES -> "要請に資源を送った"
                        LogKind.SCOUTED -> "要請された偵察をした"
                        LogKind.JOINED_RALLY -> "ラリーに参加した"
                        LogKind.DONATED -> "同盟テックに寄付した"
                    }
            }
        return (fromMails + fromLog).sortedByDescending { it.first }.take(RECENT_EVENTS).map { it.second }
    }

    private fun battleText(
        kind: BattleKind,
        attackerWon: Boolean,
    ): String =
        when (kind) {
            BattleKind.RAID -> if (attackerWon) "襲撃に敗れた" else "襲撃を撃退した"
            BattleKind.FORT -> if (attackerWon) "砦のラリーに勝った" else "砦のラリーに負けた"
            BattleKind.BARBARIAN -> if (attackerWon) "野蛮人に勝った" else "野蛮人に負けた"
        }

    /** きっかけの説明（user メッセージの末尾）。 */
    fun describe(
        state: GameState,
        pending: PendingChat,
        data: GameData,
    ): String =
        "${state.playerName.ifBlank { "領主" }}への発言のきっかけ: ${situationText(state, pending, data)} " +
            "発言できるキャラクター: ${pending.speakers.joinToString()}"

    private fun situationText(
        state: GameState,
        pending: PendingChat,
        data: GameData,
    ): String {
        val labels = data.templates.labels
        val vars = ChatContext.vars(state, pending.event, data)
        val v = vars.values
        val text =
            when (val e = pending.event) {
                is ChatEvent.PlayerHelped -> "プレイヤーが同盟メンバー${e.count}人のタイマーにヘルプした。お礼を言う。"
                is ChatEvent.PlayerDonated -> "プレイヤーが同盟テック「${v["tech"]}」に寄付した。感謝する。"
                is ChatEvent.PlayerJoinedRally ->
                    "プレイヤーが${v["host"]}主催の砦ラリーに参加し、${if (e.won) "勝利した" else "敗北した"}。主催者が反応する。"
                is ChatEvent.PlayerCastleUp -> "プレイヤーの城がLv${e.lv}になった。祝福する。"
                is ChatEvent.PlayerDefeated -> "プレイヤーが NPC の襲撃に敗れ、資源を奪われた。慰めたり助言したりする。"
                is ChatEvent.PlayerDefended -> "プレイヤーが NPC の襲撃を撃退した。称える。"
                is ChatEvent.NpcNeedsHelp -> "${e.persona}が${v["resource"]}を${v["amount"]}ほど必要としていて、誰かに助けを求める。"
                is ChatEvent.NpcAsksScout -> "${e.persona}がタイル${v["tile"]}の偵察を頼む。"
                is ChatEvent.NpcPurchased -> "${e.persona}が「${v["sku"]}」を（擬似課金で）買い、全員にギフト箱を配った。"
                is ChatEvent.NpcCallsRally -> "${e.persona}が砦Lv${e.fortLevel}（${v["tile"]}）のラリーを立て、参加を呼びかける。"
                is ChatEvent.NpcThanks -> "プレイヤーが${e.persona}の要請に応えた。${e.persona}がお礼を言う。"
                is ChatEvent.Ambient -> "雑談。話題: ${labels["topic.${e.topic}"] ?: e.topic}。2〜4行で自然に。"
                is ChatEvent.WorldAmbient ->
                    "ワールドチャット。他の同盟の知らない人たちのぽつぽつした雑談。話題: ${labels["topic.${e.topic}"] ?: e.topic}。"
                ChatEvent.DailyGreeting -> "プレイヤーが今日最初にログインした。挨拶する。"
                is ChatEvent.PlayerFreeText -> "プレイヤーが次のように発言した: 「${e.text}」。活動中のキャラクターが1〜2人返事をする。"
                is ChatEvent.EventRanking -> "イベントのランキングは現在${e.rank}位。励ます。"
                is ChatEvent.FavorMilestone -> "${e.persona}がプレイヤーとの絆がLv${e.lv}になり、個別にお礼を言う（DM）。"
                is ChatEvent.PonzuQuiz ->
                    "ぽんずが初心者らしい質問をする" +
                        (data.templates.quizzes[e.quizId]?.question?.let { "（例: 「$it」）" } ?: "") + "。"
                is ChatEvent.QuizResult -> "プレイヤーがぽんずの質問に${if (e.correct) "正しく" else "間違って"}答えた。反応する。"
                is ChatEvent.RaidStage -> "NPC の襲撃が近づいている（段階: ${e.stage}）。プレイヤーに備えを促す・励ます。"
                is ChatEvent.VipUp -> "プレイヤーのVIPがVIP${e.lv}に上がった。祝福する。"
                is ChatEvent.YukiReturn ->
                    if (e.helpCount > 0) {
                        "Yuki が久しぶりにログインした。不在の間にプレイヤーがYukiのタイマーに${e.helpCount}回ヘルプしてくれていたと気づき、感謝する。"
                    } else {
                        "Yuki が久しぶりにログインした。みんなに挨拶する。"
                    }
            }
        return text
    }
}
