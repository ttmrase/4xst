package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.core.data.PersonaDef
import com.ttmrase.crownfall.core.util.Mix

/** ペルソナの属性から句読点・波線・絵文字を機械的に付ける（口調の差を安定して出す）。 */
object StyleApplier {
    private const val PERMILLE = 1000
    private const val TILDE_CHANCE = 250
    private const val PUNCT_CHANCE = 700
    private val TERMINALS = "。！？!?…〜~♪".toSet()
    private val DEFAULT_EMOJI = listOf("😊", "✨", "👍")
    private val EMOJI =
        mapOf(
            "kaede" to listOf("😆", "✨", "👍"),
            "marcus" to listOf("⚔️", "💪", "🔥"),
            "ponzu" to listOf("😭", "✨", "🙏", "😊"),
            "johnny" to listOf("🎉", "🔥", "😆", "💎"),
            "yuki" to listOf("🌸", "😊"),
            "lin" to listOf("📊", "👍"),
            "sato" to listOf("🌾", "👍"),
        )

    /** [text] に [persona] の口調を付けて返す。[seed] と [salt] で決定論的。 */
    fun apply(
        text: String,
        persona: PersonaDef,
        seed: Long,
        salt: Long,
    ): String {
        val style = persona.speechStyle
        var out = text.trim()
        if (out.isEmpty()) return out
        val needsPunct = out.last() !in TERMINALS && style.punctuation.isNotEmpty()
        if (needsPunct && Mix.roll(seed, salt, 1, PERMILLE) < PUNCT_CHANCE) {
            out += style.punctuation
        }
        if (style.tilde && out.last() in "！!" && Mix.roll(seed, salt, 2, PERMILLE) < TILDE_CHANCE) {
            out = out.dropLast(1) + "〜" + out.last()
        }
        if (style.emojiPermille > 0 && Mix.roll(seed, salt, 3, PERMILLE) < style.emojiPermille) {
            val pool = EMOJI[persona.id] ?: DEFAULT_EMOJI
            out += pool[Mix.roll(seed, salt, 4, pool.size)]
        }
        return out
    }
}
