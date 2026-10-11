package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatTurn
import com.ttmrase.crownfall.ai.SpokenLine
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.chat.PersonaBrief
import com.ttmrase.crownfall.core.chat.PromptContextBuilder
import com.ttmrase.crownfall.core.data.GameData

/**
 * 小型モデル（1B クラス）専用のプロンプト（純関数）。長い脚本家プロンプトを切り落とすのではなく、ゼロから組む。
 * 1 回の生成で話すのは 1 人。システム指示は [MAX_SYSTEM_CHARS] 文字以内で、
 * 「誰か・どこで話しているか・相手の呼び方・口調の例・出力の約束」だけを書く。
 * ユーザー側は「状況（必要なときだけ 1 文）・直近 3〜4 行の会話（表示名: 発言）・「{表示名} の返事:」」。
 * 内部の ID（kaede など）はモデルに見せない。言語は [ChatRequest.locale] に従う。
 */
object SmallModelPrompt {
    const val MAX_SYSTEM_CHARS = 400
    const val MAX_HISTORY = 4
    const val MAX_EXAMPLES = 3
    private const val MAX_VOICE_CHARS = 70
    private const val MAX_SITUATION_CHARS = 80
    private const val MIN_EXAMPLES_SHOWN = 0

    /** 生成 1 回分の入力。 */
    data class Built(val system: String, val prompt: String)

    /** [speaker]（ペルソナ ID）が話す 1 回分。[spoken] は同じリクエストで先に話した行（文脈に足す）。 */
    fun build(
        request: ChatRequest,
        speaker: String,
        spoken: List<SpokenLine> = emptyList(),
    ): Built {
        val ja = request.locale != "en"
        val brief = briefOf(request, speaker)
        return Built(system(brief, ja), user(request, brief, ja, spoken))
    }

    /** 設定画面の「テスト発話」用: Kaede にプレイヤーの挨拶へ一言返させるリクエスト。 */
    fun testRequest(
        data: GameData,
        locale: String,
    ): ChatRequest {
        val kaede = data.personas.persona(TEST_SPEAKER) ?: data.personas.personas.first()
        val call = data.templates.labels["call.default"] ?: "領主さん"
        val greeting = if (locale == "en") "Hello everyone!" else "みなさん、こんにちは！"
        return ChatRequest(
            system = "",
            messages = listOf(ChatTurn("player", greeting)),
            event = ChatEvent.DailyGreeting,
            speakers = listOf(kaede.id),
            seed = 0,
            vars = ChatVars(emptyMap(), emptyMap(), call),
            personas = listOf(PromptContextBuilder.brief(kaede, call, TEST_FAVOR)),
            names = mapOf("player" to call, kaede.id to kaede.displayName),
            locale = locale,
            allianceLine = PromptContextBuilder.worldLine(data),
        )
    }

    private fun briefOf(
        request: ChatRequest,
        speaker: String,
    ): PersonaBrief =
        request.personas.firstOrNull { it.id == speaker }
            ?: PersonaBrief(speaker, request.names[speaker] ?: DEFAULT_NAME, "", "", emptyList())

    private fun system(
        brief: PersonaBrief,
        ja: Boolean,
    ): String {
        val call = brief.callName.ifBlank { if (ja) "領主さん" else "my lord" }
        for (count in brief.examples.size.coerceAtMost(MAX_EXAMPLES) downTo MIN_EXAMPLES_SHOWN) {
            val text = render(brief, call, ja, count, MAX_VOICE_CHARS)
            if (text.length <= MAX_SYSTEM_CHARS) return text
        }
        return render(brief, call, ja, 0, MAX_VOICE_CHARS / 2).take(MAX_SYSTEM_CHARS)
    }

    private fun render(
        brief: PersonaBrief,
        call: String,
        ja: Boolean,
        examples: Int,
        voiceChars: Int,
    ): String {
        val name = brief.displayName
        return if (ja) {
            buildString {
                append("あなたは「$name」。")
                if (brief.voice.isNotBlank()) append(brief.voice.take(voiceChars)).append("。")
                appendLine()
                appendLine("スマホの戦略ゲームの同盟チャットで話している。相手のことは「$call」と呼ぶ。")
                if (examples > 0) {
                    append("口調の例: ")
                    appendLine(brief.examples.take(examples).joinToString(" / ") { "「$it」" })
                }
                append("1〜2文で短く自然に。セリフだけを書き、名前・ラベル・記号・JSONは付けない。")
            }
        } else {
            // 口調の説明と例文は日本語なので、英語の返事を求めるときは入れない（日本語に引っ張られる）
            buildString {
                appendLine("You are \"$name\" in the alliance chat of a mobile strategy game.")
                appendLine("Call the other person \"$call\".")
                append("Reply in English with one or two short, natural sentences. ")
                append("Write only the line itself: no name, label, symbols or JSON.")
            }
        }
    }

    private fun user(
        request: ChatRequest,
        brief: PersonaBrief,
        ja: Boolean,
        spoken: List<SpokenLine>,
    ): String {
        val history =
            request.messages.filter { it.speaker != "system" && it.text.isNotBlank() }
                .map { nameOf(request, it.speaker) to it.text } +
                spoken.map { nameOf(request, it.speaker) to it.text }
        val lines = history.takeLast(MAX_HISTORY).joinToString("\n") { (who, text) -> "$who: ${text.trim()}" }
        val showSituation = ja && request.situation.isNotBlank() && request.event !is ChatEvent.PlayerFreeText
        return buildString {
            if (showSituation) appendLine("状況: ${firstSentence(request.situation)}")
            if (lines.isNotEmpty()) appendLine(lines)
            append(if (ja) "${brief.displayName} の返事:" else "${brief.displayName}'s reply:")
        }
    }

    private fun nameOf(
        request: ChatRequest,
        speaker: String,
    ): String =
        request.names[speaker]
            ?: request.personas.firstOrNull { it.id == speaker }?.displayName
            ?: if (speaker.startsWith("world:")) speaker.removePrefix("world:") else DEFAULT_NAME

    private fun firstSentence(text: String): String {
        val cut = text.indexOfFirst { it == '。' || it == '！' || it == '？' }
        val one = if (cut >= 0) text.substring(0, cut + 1) else text
        return one.take(MAX_SITUATION_CHARS)
    }

    private const val DEFAULT_NAME = "メンバー"
    private const val TEST_SPEAKER = "kaede"
    private const val TEST_FAVOR = 2
}
