package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.SpokenLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * LLM の出力 JSON `{"lines":[{"speaker":"kaede","text":"..."}]}` を読む。
 * プレーンテキストなら全体を 1 行、壊れた JSON は完結した text だけ拾う（無ければ空 = 失敗扱い）。
 */
object ResponseParser {
    private val json = Json { ignoreUnknownKeys = true }
    private val FENCE = Regex("^```[a-zA-Z]*\\s*|\\s*```$")

    @Serializable
    private class Lines(val lines: List<Line> = emptyList())

    @Serializable
    private class Line(val speaker: String = "", val text: String = "")

    /** [raw] を発話にする。[fallbackSpeaker] は壊れた出力を 1 行にするときの発話者。 */
    fun parse(
        raw: String,
        fallbackSpeaker: String,
    ): List<SpokenLine> {
        val text = raw.trim().replace(FENCE, "").trim()
        if (text.isEmpty()) return emptyList()
        val parsed =
            runCatching { json.decodeFromString<Lines>(text) }.getOrNull()?.lines
                ?.filter { it.text.isNotBlank() }
                ?.map { SpokenLine(it.speaker.ifBlank { fallbackSpeaker }, it.text.trim()) }
        if (!parsed.isNullOrEmpty()) return parsed
        // 壊れた JSON（途中で切れたなど）は生のまま見せない。完結している "text" だけ拾い、無ければ失敗扱い。
        if (text.startsWith("{") || text.startsWith("[")) return salvage(text, fallbackSpeaker)
        return listOf(SpokenLine(fallbackSpeaker, text))
    }

    private val PAIR = Regex("\"speaker\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"text\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")")
    private val TEXT_ONLY = Regex("\"text\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")")

    private fun unquote(literal: String): String? =
        runCatching { json.decodeFromString<String>(literal) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun salvage(
        text: String,
        fallbackSpeaker: String,
    ): List<SpokenLine> {
        val pairs =
            PAIR.findAll(text).mapNotNull { m ->
                unquote(m.groupValues[2])?.let { SpokenLine(m.groupValues[1].ifBlank { fallbackSpeaker }, it) }
            }.toList()
        if (pairs.isNotEmpty()) return pairs
        return TEXT_ONLY.findAll(text).mapNotNull { m ->
            unquote(m.groupValues[1])?.let { SpokenLine(fallbackSpeaker, it) }
        }.toList()
    }

    private val JA_CHARS = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")
    private val EN_CHARS = Regex("[A-Za-z]")
    private val LEADING_MARKUP = Regex("^[\\s>＞#*\\-•`_~]+")
    private val NAME_LABEL = Regex("^[\\p{L}\\p{N}_ \\u3000・\\-]{1,20}[:：]\\s*")
    private val OPEN_QUOTES = Regex("^[\"'“”‘「『（(]+")
    private val CLOSE_QUOTES = Regex("[\"'“”’」』）)]+$")
    private val MARKUP_INSIDE = Regex("(\\*\\*|__|`|~~)")
    private const val SENTENCE_ENDS = "。！？!?"
    private const val CLOSERS = "」』）)\"'”’"
    private const val MAX_SENTENCES = 2
    private const val MIN_CHARS = 2
    private const val MAX_CHARS = 120
    private const val ECHO_WINDOW = 12

    /**
     * 小型モデルのプレーンテキスト出力を、1 人分のセリフ（最大 2 文）に整える。使えない出力は null（次のプロバイダへ回す）。
     * 最初の空でない行だけを採り、先頭の「名前:」「名前：」「>」「"」「「」や Markdown の記号を剥がす。
     * 次のどれかなら null: `{` `}` "speaker" を含む / [locale] の文字（ja はかな・漢字、en は英字）が無い /
     * 2 文字未満か 120 文字超 / [prompt] の反復 / [previous]（直前の行）と同じ。
     */
    fun plain(
        text: String,
        locale: String,
        prompt: String = "",
        previous: List<String> = emptyList(),
    ): String? {
        if ('{' in text || '}' in text || text.contains("speaker", ignoreCase = true)) return null
        val first =
            text.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("```") } ?: return null
        var line = first.replace(LEADING_MARKUP, "")
        line = line.replace(NAME_LABEL, "").replace(OPEN_QUOTES, "").replace(MARKUP_INSIDE, "").trim()
        line = firstSentences(line).replace(CLOSE_QUOTES, "").trim()
        val chars = if (locale == "en") EN_CHARS else JA_CHARS
        val bad =
            line.length < MIN_CHARS || line.length > MAX_CHARS || !chars.containsMatchIn(line) ||
                echoesPrompt(line, prompt) || previous.any { same(it, line) }
        return if (bad) null else line
    }

    private fun firstSentences(s: String): String {
        var count = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val end = c in SENTENCE_ENDS || (c == '.' && (i + 1 == s.length || s[i + 1].isWhitespace()))
            if (!end) {
                i++
                continue
            }
            var j = i + 1
            while (j < s.length && (s[j] in SENTENCE_ENDS || s[j] in CLOSERS)) j++
            if (++count == MAX_SENTENCES) return s.substring(0, j)
            i = j
        }
        return s
    }

    private fun normalize(s: String): String = s.filter { it.isLetterOrDigit() }.lowercase()

    private fun same(
        a: String,
        b: String,
    ): Boolean = normalize(a).let { it.isNotEmpty() && it == normalize(b) }

    /** 返事の中の [ECHO_WINDOW] 文字以上が、そのままプロンプトにあれば反復とみなす（短い返事は完全一致だけ）。 */
    private fun echoesPrompt(
        line: String,
        prompt: String,
    ): Boolean {
        if (prompt.isEmpty()) return false
        val n = normalize(line)
        val p = normalize(prompt)
        if (n.length < ECHO_WINDOW) return false
        return (0..n.length - ECHO_WINDOW).any { p.contains(n.substring(it, it + ECHO_WINDOW)) }
    }
}
