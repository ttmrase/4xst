package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.SpokenLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** LLM の出力 JSON `{"lines":[{"speaker":"kaede","text":"..."}]}` を読む。壊れていたら全体を 1 行として扱う。 */
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
        return if (!parsed.isNullOrEmpty()) parsed else listOf(SpokenLine(fallbackSpeaker, text))
    }
}
