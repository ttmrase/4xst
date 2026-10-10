package com.ttmrase.crownfall.ai

import com.ttmrase.crownfall.core.chat.ChatContext
import com.ttmrase.crownfall.core.chat.PromptContextBuilder
import com.ttmrase.crownfall.core.chat.RevealAi
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.PendingChat
import com.ttmrase.crownfall.core.util.Mix

/** 状態とチャットのきっかけから [ChatRequest] を作る（乱数の種は同盟の種ときっかけ ID から決まる）。 */
object ChatRequestFactory {
    /** [recent] は直近に出した文面（同じ文を避ける）。 */
    fun create(
        state: GameState,
        pending: PendingChat,
        data: GameData,
        recent: List<String> = emptyList(),
        reveal: RevealAi = RevealAi.AMBIGUOUS,
    ): ChatRequest {
        val prompt = PromptContextBuilder.build(state, pending, data, reveal)
        return ChatRequest(
            system = prompt.system,
            messages = prompt.turns.map { ChatTurn(it.speaker, it.text) },
            jsonSchemaHint = SCHEMA_HINT,
            event = pending.event,
            speakers = pending.speakers,
            seed = Mix.mix(state.alliance.seed xor pending.id),
            recent = recent,
            vars = ChatContext.vars(state, pending.event, data),
            knownSpeakers = data.personas.personas.map { it.id }.toSet(),
        )
    }

    private const val SCHEMA_HINT = "{\"lines\":[{\"speaker\":\"<id>\",\"text\":\"<text>\"}]}"
}
