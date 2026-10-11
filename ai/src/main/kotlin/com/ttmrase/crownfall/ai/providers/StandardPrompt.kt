package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.SpokenLine
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.PersonaBrief

/**
 * 標準プロファイル（Gemma 3n E2B / E4B クラス）のプロンプト（純関数）。
 * システム指示は自然な文章で 400〜700 文字（人物・プレイヤーとの関係・世界・文体・出力）。
 * ユーザー側は、口調の例（同じペルソナ × 同じイベントの定型文）→ 直近 8〜10 行の会話（表示名: 発言）→
 * ト書き「（状況: …）」→「{表示名} の返事:」。履歴は、全体が [USER_BUDGET_CHARS] を超えないよう古い行から削る
 * （プロンプト全体を 600〜700 トークン以内に収める）。内部の ID は出さない。
 * 英語（en）では、同じ構成の固定文を英語で書き、日本語のままの人物の説明・例文は入れない。
 */
object StandardPrompt {
    const val MAX_SYSTEM_CHARS = 700
    const val MIN_SYSTEM_CHARS = 400
    const val MAX_HISTORY = 10
    const val MIN_HISTORY = 3
    const val USER_BUDGET_CHARS = 1_100
    const val MAX_SPEAKERS = 3
    private const val MAX_EXAMPLES = 5
    private const val SITUATION_SENTENCES = 3

    /** 1 人が話す 1 回分。 */
    fun single(
        request: ChatRequest,
        speaker: String,
        spoken: List<SpokenLine> = emptyList(),
    ): SmallModelPrompt.Built {
        val ja = request.locale != "en"
        val brief = briefOf(request, speaker)
        val system =
            fit {
                    level ->
                if (ja) systemJa(request, listOf(brief), false, level) else systemEn(listOf(brief), false)
            }
        return SmallModelPrompt.Built(system, user(request, listOf(brief), ja, spoken, false))
    }

    /** 複数の話者を 1 回の生成で「表示名: セリフ」の最大 3 行にさせる。 */
    fun multi(
        request: ChatRequest,
        speakers: List<String>,
    ): SmallModelPrompt.Built {
        val ja = request.locale != "en"
        val briefs = speakers.take(MAX_SPEAKERS).map { briefOf(request, it) }
        val system = fit { level -> if (ja) systemJa(request, briefs, true, level) else systemEn(briefs, true) }
        return SmallModelPrompt.Built(system, user(request, briefs, ja, emptyList(), true))
    }

    /** [multi] の出力を読むための、表示名 → ペルソナ ID の表。 */
    fun nameTable(
        request: ChatRequest,
        speakers: List<String>,
    ): Map<String, String> = speakers.take(MAX_SPEAKERS).associate { briefOf(request, it).displayName to it }

    private fun briefOf(
        request: ChatRequest,
        speaker: String,
    ): PersonaBrief =
        request.personas.firstOrNull { it.id == speaker }
            ?: PersonaBrief(speaker, request.names[speaker] ?: DEFAULT_NAME, "", "", emptyList())

    /** 詳細度を下げながら（例文 → 性格 → 口調）、[MAX_SYSTEM_CHARS] 以内に収まる最初の文を返す。 */
    private inline fun fit(render: (Int) -> String): String {
        var last = ""
        for (level in 0..MAX_LEVEL) {
            last = render(level)
            if (last.length <= MAX_SYSTEM_CHARS) return last
        }
        return last.take(MAX_SYSTEM_CHARS)
    }

    private fun examplesFor(
        brief: PersonaBrief,
        level: Int,
        multi: Boolean,
    ): List<String> {
        val cap = if (multi) 1 else MAX_EXAMPLES
        val n = (cap - level).coerceAtLeast(0).coerceAtMost(brief.examples.size)
        return brief.examples.take(n)
    }

    private fun systemJa(
        request: ChatRequest,
        briefs: List<PersonaBrief>,
        multi: Boolean,
        level: Int,
    ): String {
        val lead = briefs.first()
        val b = StringBuilder()
        if (multi) {
            b.appendLine("あなたはスマホの戦略ゲーム「Crownfall」の同盟チャットの会話を書きます。")
            b.appendLine("【登場人物】")
            for (p in briefs) b.appendLine("・" + personJa(p, level, true))
            b.appendLine(
                "【プレイヤーとの関係】プレイヤーの呼び方は " + briefs.joinToString("、") { "${it.displayName}→「${call(it)}」" } + "。",
            )
        } else {
            b.appendLine("あなたは「${lead.displayName}」として、スマホの戦略ゲーム「Crownfall」の同盟チャットで会話します。")
            b.appendLine("【人物】" + personJa(lead, level, false))
            b.appendLine("【プレイヤーとの関係】プレイヤーのことは「${call(lead)}」と呼びます。関係は「${lead.relation.ifBlank { "初対面" }}」です。")
        }
        b.appendLine(world(request))
        b.appendLine(
            "【文体】1〜3文。チャットらしく短く。相手の言葉を拾って返し、少し話題を広げたり、たまに質問を返したりしてよい。" +
                "ゲーム外の話や冗談も軽くOK。毎回ゲームの状況に触れなくてよい。決まり文句・説明口調・箇条書き・絵文字の多用は避ける。",
        )
        if (multi) {
            b.append(
                "【出力】" + briefs.joinToString("、") { it.displayName } + " の順に、「表示名: セリフ」の形で 1 人 1 行（最大 3 行）。セリフ以外は書かない。",
            )
        } else {
            b.append("【出力】セリフだけを書く。名前・ラベル・記号は付けない。")
        }
        return b.toString()
    }

    private fun world(request: ChatRequest): String {
        val alliance = request.allianceLine.ifBlank { "同盟" }
        return "【世界】同盟は $alliance。プレイヤーは領主として所属しています。" +
            "資源は食料・木材・石・金の 4 種で、建設・研究・ラリー（砦攻め）・野蛮人討伐があります。"
    }

    private fun personJa(
        p: PersonaBrief,
        level: Int,
        multi: Boolean,
    ): String {
        val head = if (multi) "${p.displayName}: " else ""
        val parts = ArrayList<String>()
        if (p.role.isNotBlank()) parts += p.role
        if (p.traits.isNotBlank() && level < LEVEL_DROP_TRAITS) parts += "性格は${p.traits}"
        if (p.voice.isNotBlank()) parts += "話し方: ${p.voice}"
        val ex = examplesFor(p, level, multi)
        if (ex.isNotEmpty()) parts += "口調の例: " + ex.joinToString("") { "「$it」" }
        return head + parts.joinToString("。")
    }

    private fun systemEn(
        briefs: List<PersonaBrief>,
        multi: Boolean,
    ): String {
        val lead = briefs.first()
        val b = StringBuilder()
        if (multi) {
            b.appendLine("You write a conversation in the alliance chat of the mobile strategy game \"Crownfall\".")
            b.appendLine("Characters: " + briefs.joinToString(", ") { "\"${it.displayName}\"" } + ".")
            b.appendLine(
                "They call the player: " + briefs.joinToString(", ") { "${it.displayName} -> \"${callEn(it)}\"" } + ".",
            )
        } else {
            b.appendLine(
                "You are \"${lead.displayName}\", chatting in the alliance chat of the mobile strategy game " +
                    "\"Crownfall\".",
            )
            b.appendLine(
                "You call the player \"${callEn(
                    lead,
                )}\". Your relationship with the player: ${relationEn(lead.relation)}.",
            )
        }
        b.appendLine(
            "The player is a lord in your alliance. Resources: food, wood, stone, gold. " +
                "Players build, research, join rallies and fight barbarians.",
        )
        b.appendLine(
            "Style: one to three short, chatty sentences. Pick up what the other person said, " +
                "widen the topic a little, " +
                "and sometimes ask a question back. Off-topic chat and jokes are fine; you need not mention the game " +
                "every time. Avoid stock phrases, lecturing, lists and lots of emoji.",
        )
        if (multi) {
            b.append(
                "Output: in the order " +
                    briefs.joinToString(", ") {
                        it.displayName
                    } + ", one line each as \"Name: line\" (at most 3 lines). Write nothing else.",
            )
        } else {
            b.append("Output: write only the line itself, with no name, label or symbols. Reply in English.")
        }
        return b.toString()
    }

    private fun call(p: PersonaBrief): String = p.callName.ifBlank { "領主さん" }

    private fun callEn(p: PersonaBrief): String = p.callName.ifBlank { "my lord" }

    private fun relationEn(relation: String): String =
        when (relation) {
            "顔なじみ" -> "acquaintances"
            "親しい" -> "close friends"
            "相棒" -> "partners"
            else -> "just met"
        }

    private fun user(
        request: ChatRequest,
        briefs: List<PersonaBrief>,
        ja: Boolean,
        spoken: List<SpokenLine>,
        multi: Boolean,
    ): String {
        val history =
            (
                request.messages.filter { it.speaker != "system" && it.text.isNotBlank() }
                    .map { nameOf(request, it.speaker) to it.text.trim() } +
                    spoken.map { nameOf(request, it.speaker) to it.text.trim() }
            ).takeLast(MAX_HISTORY)
        val fixed = fixedParts(request, briefs, ja, multi)
        var lines = history
        while (lines.size > MIN_HISTORY && length(fixed, lines) > USER_BUDGET_CHARS) lines = lines.drop(1)
        return buildString {
            fixed.examples?.let { appendLine(it) }
            lines.forEach { (who, text) -> appendLine("$who: $text") }
            appendLine(fixed.direction)
            append(fixed.closing)
        }
    }

    private class Fixed(val examples: String?, val direction: String, val closing: String)

    private fun length(
        fixed: Fixed,
        lines: List<Pair<String, String>>,
    ): Int =
        (fixed.examples?.length ?: 0) + fixed.direction.length + fixed.closing.length +
            lines.sumOf {
                it.first.length + it.second.length + 3
            }

    private fun fixedParts(
        request: ChatRequest,
        briefs: List<PersonaBrief>,
        ja: Boolean,
        multi: Boolean,
    ): Fixed {
        val lead = briefs.first()
        val names = briefs.joinToString("、") { it.displayName }
        val freeText = request.event is ChatEvent.PlayerFreeText
        val examples =
            if (!ja) {
                null
            } else {
                val lines =
                    briefs.mapNotNull { p ->
                        request.fewShot[p.id]?.takeIf { it.isNotEmpty() }
                            ?.let { exs ->
                                val head = if (multi) "例（${p.displayName}）: " else "例: "
                                head + exs.take(2).joinToString("") { s -> "「$s」" }
                            }
                    }
                lines.joinToString("\n").ifEmpty { null }
            }
        val situation = situation(request)
        val task = if (freeText) "相手の言葉に具体的に反応し、ときどき質問を返す" else "自然に会話を続ける"
        val direction =
            if (ja) {
                "（状況: $situation $names として$task。）"
            } else {
                "(Situation: the conversation continues in the alliance chat. As $names, " +
                    (if (freeText) EN_FREE_TEXT else EN_CONTINUE) +
                    ".)"
            }
        val closing =
            when {
                ja && multi -> "$names の順に、「表示名: セリフ」で答える:"
                ja -> "${lead.displayName} の返事:"
                multi -> "Answer in the order $names, as \"Name: line\":"
                else -> "${lead.displayName}'s reply:"
            }
        return Fixed(examples, direction, closing)
    }

    /** きっかけの文（最大 3 文）に、状況の材料（城 Lv・資源・直近のできごと）を足す。 */
    private fun situation(request: ChatRequest): String {
        val sentences = splitSentences(request.situation).take(SITUATION_SENTENCES)
        return (sentences + request.status.trim()).filter { it.isNotBlank() }.joinToString("")
    }

    private fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        text.forEachIndexed { i, c ->
            if (c == '。' || c == '！' || c == '？') {
                out += text.substring(start, i + 1)
                start = i + 1
            }
        }
        if (start < text.length) out += text.substring(start)
        return out
    }

    private fun nameOf(
        request: ChatRequest,
        speaker: String,
    ): String =
        request.names[speaker]
            ?: request.personas.firstOrNull { it.id == speaker }?.displayName
            ?: if (speaker.startsWith("world:")) speaker.removePrefix("world:") else DEFAULT_NAME

    private const val DEFAULT_NAME = "メンバー"
    private const val EN_FREE_TEXT = "react to what the player said and sometimes ask a question back"
    private const val EN_CONTINUE = "keep the chat going naturally"
    private const val MAX_LEVEL = 6
    private const val LEVEL_DROP_TRAITS = 4
}
