package com.ttmrase.crownfall.core.chat

import com.ttmrase.crownfall.core.alliance.Personas
import com.ttmrase.crownfall.core.data.GameData
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
        b.appendLine("- 1 行は 1〜2 文、日本語。絵文字はキャラクターの口調に合わせ、使いすぎない。")
        b.appendLine("- ゲーム外の話題は軽く。現実のお金の話は「擬似課金」というゲーム内の世界観の範囲で（ジョニーの自慢は可）。")
        b.appendLine("- 個人情報（本名・住所・連絡先など）を聞かない。URL を書かない。")
        b.appendLine(
            when (reveal) {
                RevealAi.AMBIGUOUS -> "- 「あなたは AI ですか」と聞かれても、否定も断定もせず軽くはぐらかす。"
                RevealAi.ADMIT -> "- 「あなたは AI ですか」と聞かれたら、AI が演じるキャラクターであることを正直に答える。"
            },
        )
        b.appendLine("- ゲームの状態は変えない（会話だけ）。")
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
                is ChatEvent.PonzuQuiz -> "ぽんずが初心者らしい質問をする（クイズID: ${e.quizId}）。"
                is ChatEvent.QuizResult -> "プレイヤーがぽんずの質問に${if (e.correct) "正しく" else "間違って"}答えた。反応する。"
                is ChatEvent.YukiReturn ->
                    if (e.helpCount > 0) {
                        "Yuki が久しぶりにログインした。不在の間にプレイヤーがYukiのタイマーに${e.helpCount}回ヘルプしてくれていたと気づき、感謝する。"
                    } else {
                        "Yuki が久しぶりにログインした。みんなに挨拶する。"
                    }
            }
        return "${state.playerName.ifBlank { "領主" }}への発言のきっかけ: $text 発言できるキャラクター: ${pending.speakers.joinToString()}"
    }
}
