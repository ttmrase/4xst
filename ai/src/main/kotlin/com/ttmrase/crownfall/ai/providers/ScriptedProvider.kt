package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.AiProvider
import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatResponse
import com.ttmrase.crownfall.ai.SpokenLine
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.util.Mix

/**
 * テンプレートだけで返す常時利用可のプロバイダ（API キー不要）。
 * 乱数は [ChatRequest.seed] から決定論的に引く。直近に使った文面は避ける。
 */
class ScriptedProvider(private val data: GameData) : AiProvider {
    override val id: String = ID

    override suspend fun complete(request: ChatRequest): AiResult = AiResult.Success(ChatResponse(generate(request)))

    /** 同期版（テスト・フォールバック用）。 */
    fun generate(request: ChatRequest): List<SpokenLine> =
        when (val ev = request.event) {
            is ChatEvent.WorldAmbient -> worldLines(request)
            is ChatEvent.PonzuQuiz -> listOf(SpokenLine(PONZU, quizQuestion(ev)))
            else -> personaLines(request, ev)
        }

    private fun quizQuestion(ev: ChatEvent.PonzuQuiz): String =
        data.templates.quizzes[ev.quizId]?.question ?: pickFallback(ChatKeys.PONZU_QUIZ)

    private fun pickFallback(key: String): String = data.templates.templates[key]?.get(PONZU)?.firstOrNull() ?: ""

    private fun templateKey(ev: ChatEvent): String =
        if (ev is ChatEvent.PlayerFreeText) {
            "${ChatKeys.PLAYER_FREE_TEXT}.${FreeTextClassifier.classify(ev.text)}"
        } else {
            ev.key
        }

    /** イベントが発話者を指定しているならそのペルソナ。 */
    private fun explicitSpeaker(ev: ChatEvent): String? =
        when (ev) {
            is ChatEvent.NpcNeedsHelp -> ev.persona
            is ChatEvent.NpcAsksScout -> ev.persona
            is ChatEvent.NpcPurchased -> ev.persona
            is ChatEvent.NpcCallsRally -> ev.persona
            is ChatEvent.NpcThanks -> ev.persona
            is ChatEvent.FavorMilestone -> ev.persona
            is ChatEvent.YukiReturn -> YUKI
            is ChatEvent.QuizResult -> PONZU
            else -> null
        }

    private fun lineCount(
        request: ChatRequest,
        ev: ChatEvent,
        speakerCount: Int,
    ): Int =
        when (ev) {
            is ChatEvent.Ambient -> MIN_AMBIENT + Mix.roll(request.seed, SALT_COUNT, 0, AMBIENT_SPAN)
            is ChatEvent.NpcNeedsHelp, is ChatEvent.NpcAsksScout, is ChatEvent.NpcPurchased,
            is ChatEvent.NpcCallsRally, is ChatEvent.NpcThanks, is ChatEvent.FavorMilestone,
            -> 1
            else -> minOf(1 + Mix.roll(request.seed, SALT_COUNT, 0, 2), maxOf(1, speakerCount))
        }

    private fun personaLines(
        request: ChatRequest,
        ev: ChatEvent,
    ): List<SpokenLine> {
        val key = templateKey(ev)
        val explicit = explicitSpeaker(ev)
        val primary = explicit ?: request.speakers.firstOrNull() ?: DEFAULT_SPEAKER
        val others = request.speakers.filter { it != primary }
        val order = listOf(primary) + others
        val n = lineCount(request, ev, order.size)
        val used = request.recent.map(::norm).toMutableSet()
        val lines = ArrayList<SpokenLine>()
        for (i in 0 until n) {
            val persona = order[i % order.size]
            val text = pick(key, persona, request, i, used) ?: continue
            used += norm(text)
            lines += SpokenLine(persona, styled(text, persona, request.seed, i))
        }
        return lines
    }

    private fun candidates(
        key: String,
        persona: String,
    ): List<String> {
        val per = data.templates.templates[key].orEmpty()
        return per[persona] ?: per["*"] ?: per.values.firstOrNull().orEmpty()
    }

    private fun pick(
        key: String,
        persona: String,
        request: ChatRequest,
        index: Int,
        used: Set<String>,
    ): String? {
        val list = candidates(key, persona)
        if (list.isEmpty()) return null
        val start = Mix.roll(request.seed, SALT_PICK + index, persona.hashCode().toLong(), list.size)
        var fallback: String? = null
        for (k in list.indices) {
            val filled = fill(list[(start + k) % list.size], request.vars, persona)
            if (fallback == null) fallback = filled
            if (norm(filled) !in used) return filled
        }
        return fallback
    }

    private fun styled(
        text: String,
        persona: String,
        seed: Long,
        index: Int,
    ): String {
        val def = data.personas.persona(persona) ?: return text
        return StyleApplier.apply(text, def, seed, SALT_STYLE + index)
    }

    private fun worldLines(request: ChatRequest): List<SpokenLine> {
        val names = data.personas.worldNpcNames
        val pool = data.templates.templates[ChatKeys.WORLD_AMBIENT]?.get("*").orEmpty()
        if (names.isEmpty() || pool.isEmpty()) return emptyList()
        val n = 1 + Mix.roll(request.seed, SALT_COUNT, 1, WORLD_LINES)
        val used = request.recent.map(::norm).toMutableSet()
        val out = ArrayList<SpokenLine>()
        for (i in 0 until n) {
            val start = Mix.roll(request.seed, SALT_PICK + i, WORLD_SALT, pool.size)
            val rotated = pool.indices.map { pool[(start + it) % pool.size] }
            val text = rotated.firstOrNull { norm(it) !in used } ?: pool[start]
            used += norm(text)
            val name = names[Mix.roll(request.seed, SALT_WORLD_NAME + i, 0, names.size)]
            out += SpokenLine("world:$name", text)
        }
        return out
    }

    /** `{name}` を [vars] で置き換える。未知のプレースホルダは空文字にする。 */
    private fun fill(
        template: String,
        vars: ChatVars,
        persona: String,
    ): String =
        PLACEHOLDER.replace(template) { m ->
            val name = m.groupValues[1]
            if (name == "nickname") vars.nicknameFor(persona) else vars.values[name].orEmpty()
        }

    /** 比較用に文字と数字だけにする（絵文字・句読点の違いは同じ文とみなす）。 */
    private fun norm(s: String): String = s.filter { it.isLetterOrDigit() }

    companion object {
        const val ID = "scripted"
        private const val PONZU = "ponzu"
        private const val YUKI = "yuki"
        private const val DEFAULT_SPEAKER = "kaede"
        private const val MIN_AMBIENT = 2
        private const val AMBIENT_SPAN = 3
        private const val WORLD_LINES = 3
        private const val SALT_COUNT = 11L
        private const val SALT_PICK = 200L
        private const val SALT_STYLE = 300L
        private const val SALT_WORLD_NAME = 400L
        private const val WORLD_SALT = 77L
        private val PLACEHOLDER = Regex("\\{(\\w+)\\}")
    }
}
