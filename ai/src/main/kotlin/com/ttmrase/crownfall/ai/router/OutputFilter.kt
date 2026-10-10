package com.ttmrase.crownfall.ai.router

import com.ttmrase.crownfall.ai.SpokenLine

/** LLM 出力の簡易フィルタ（URL の除去、NG ワード、長さ、発話者の検査）。 */
class OutputFilter(
    private val ngWords: List<String> = DEFAULT_NG,
) {
    /** [lines] を整える。[allowed] 以外の発話者は [fallback] に置き換える。空になった行は捨てる。 */
    fun sanitize(
        lines: List<SpokenLine>,
        allowed: Set<String>,
        fallback: String,
    ): List<SpokenLine> =
        lines.mapNotNull { l ->
            val text = clean(l.text)
            if (text.isEmpty() || ngWords.any { text.contains(it, ignoreCase = true) }) return@mapNotNull null
            val speaker = if (l.speaker in allowed || l.speaker.startsWith("world:")) l.speaker else fallback
            SpokenLine(speaker, text)
        }.take(MAX_LINES)

    private fun clean(raw: String): String =
        raw.replace(URL, "").replace(CONTROL, "").replace(SPACES, " ").trim().take(MAX_LENGTH)

    companion object {
        const val MAX_LINES = 6
        const val MAX_LENGTH = 240
        private val URL = Regex("(?i)(https?://|www\\.)\\S+")
        private val CONTROL = Regex("[\\p{Cntrl}&&[^\\n]]")
        private val SPACES = Regex("\\s+")
        val DEFAULT_NG = listOf("死ね", "殺す", "ころす", "消えろ", "fuck", "shit")
    }
}
