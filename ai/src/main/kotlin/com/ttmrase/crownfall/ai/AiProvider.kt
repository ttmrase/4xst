package com.ttmrase.crownfall.ai

import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.chat.PersonaBrief

/** 直近チャットの 1 行。 */
data class ChatTurn(val speaker: String, val text: String)

/**
 * プロバイダへの依頼。LLM は [system] と [messages] を使い、Scripted は構造化された [event] / [speakers] / [vars] / [seed] を使う。
 * [recent] は直近に出した文面（同じ文を避けるため）。
 */
data class ChatRequest(
    val system: String,
    val messages: List<ChatTurn>,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val temperature: Double = DEFAULT_TEMPERATURE,
    val jsonSchemaHint: String? = null,
    val event: ChatEvent,
    val speakers: List<String>,
    val seed: Long,
    val recent: List<String> = emptyList(),
    val vars: ChatVars,
    /** 全ペルソナ ID（LLM が使ってよい発話者）。空なら [speakers] だけ。 */
    val knownSpeakers: Set<String> = emptySet(),
    /** 小型モデル向け: 発言者ごとの要約（表示名・呼び方・口調・例文）。[speakers] と同じ順。 */
    val personas: List<PersonaBrief> = emptyList(),
    /** 小型モデル向け: 会話履歴の発言者 ID → 表示名。 */
    val names: Map<String, String> = emptyMap(),
    /** 小型モデル向け: きっかけの説明（1 文）。空なら書かない。 */
    val situation: String = "",
    /** アプリの言語（`ja` / `en`）。小型モデルは、この言語で返事をする。 */
    val locale: String = "ja",
) {
    companion object {
        const val DEFAULT_MAX_TOKENS = 300
        const val DEFAULT_TEMPERATURE = 0.9
    }
}

/** 発話 1 行。[speaker] はペルソナ ID か `world:<名前>`。 */
data class SpokenLine(val speaker: String, val text: String)

data class ChatResponse(val lines: List<SpokenLine>)

/** 失敗の理由。[retryable] が true なら次のプロバイダを試す価値がある。 */
sealed interface AiResult {
    data class Success(val response: ChatResponse) : AiResult

    data class Failure(val reason: String, val retryable: Boolean) : AiResult
}

/** 文面を作るプロバイダ。 */
interface AiProvider {
    /** 設定・ログで使う ID（`gemini`、`openai_compat`、`scripted` など）。 */
    val id: String

    /** 使える状態か（API キー未入力などは false。ルーターは数えずに飛ばす）。 */
    fun isConfigured(): Boolean = true

    /** [request] に対する発話を返す。例外ではなく [AiResult.Failure] で失敗を表す。 */
    suspend fun complete(request: ChatRequest): AiResult
}
