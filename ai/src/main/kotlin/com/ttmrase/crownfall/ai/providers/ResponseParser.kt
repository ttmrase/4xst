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
}
