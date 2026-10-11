package com.ttmrase.crownfall.ai

import com.ttmrase.crownfall.ai.providers.ScriptedProvider
import com.ttmrase.crownfall.core.chat.ChatContext
import com.ttmrase.crownfall.core.chat.ChatVars
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
        locale: String = "ja",
    ): ChatRequest {
        val prompt = PromptContextBuilder.build(state, pending, data, reveal)
        val vars = ChatContext.vars(state, pending.event, data)
        val seed = Mix.mix(state.alliance.seed xor pending.id)
        return ChatRequest(
            system = prompt.system,
            messages = prompt.turns.map { ChatTurn(it.speaker, it.text) },
            jsonSchemaHint = SCHEMA_HINT,
            event = pending.event,
            speakers = pending.speakers,
            seed = seed,
            recent = recent,
            vars = vars,
            knownSpeakers =
                data.personas.personas.map { it.id }.toSet() +
                    data.personas.worldNpcNames.map { "world:$it" },
            personas = PromptContextBuilder.briefs(state, pending, data),
            names = PromptContextBuilder.names(state, data),
            situation = PromptContextBuilder.situation(state, pending, data),
            locale = locale,
            allianceLine = PromptContextBuilder.worldLine(data),
            status = PromptContextBuilder.statusLine(state, data),
            fewShot = fewShot(pending, data, vars, seed),
        )
    }

    /** 発言者ごとに、同じイベントの定型文を最大 2 本（標準プロファイルの few-shot）。 */
    private fun fewShot(
        pending: PendingChat,
        data: GameData,
        vars: ChatVars,
        seed: Long,
    ): Map<String, List<String>> {
        val scripted = ScriptedProvider(data)
        return pending.speakers.associateWith { scripted.examples(pending.event, it, vars, FEW_SHOT, seed) }
            .filterValues { it.isNotEmpty() }
    }

    private const val FEW_SHOT = 2
    private const val SCHEMA_HINT = "{\"lines\":[{\"speaker\":\"<id>\",\"text\":\"<text>\"}]}"
}
