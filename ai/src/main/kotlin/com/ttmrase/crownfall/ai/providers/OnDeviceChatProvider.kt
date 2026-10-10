package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatResponse
import com.ttmrase.crownfall.ai.OnDeviceProvider
import kotlinx.coroutines.CancellationException

/** 端末内の LLM に 1 回の生成を頼む口（:app が LiteRT-LM で実装する）。失敗は例外で表す。 */
fun interface OnDeviceGenerator {
    suspend fun generate(
        system: String,
        prompt: String,
    ): String
}

/**
 * 端末内 LLM のプロバイダ。小型モデルは長いプロンプトと JSON が苦手なので、発話者は 1〜2 名・1 行ずつに絞った
 * 短いプロンプトを作り、壊れた出力は [ResponseParser] で救う。使えない条件（モデル無し・充電中のみ・連続生成の間隔）は
 * 失敗として返し、ルーターが次へ回す。
 */
class OnDeviceChatProvider(
    private val ready: () -> Boolean,
    private val generator: OnDeviceGenerator,
    private val chargingOnly: () -> Boolean = { false },
    private val charging: () -> Boolean = { true },
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : OnDeviceProvider {
    private var lastFinishedMs = Long.MIN_VALUE / 2

    override fun isModelReady(): Boolean = ready()

    override suspend fun complete(request: ChatRequest): AiResult {
        if (!ready()) return AiResult.Failure("no_model", false)
        if (chargingOnly() && !charging()) return AiResult.Failure("not_charging", false)
        // 連続生成の間隔を空ける（発熱・電池への配慮）
        if (nowMs() - lastFinishedMs < minIntervalMs) return AiResult.Failure("cooldown", true)
        val speakers = request.speakers.take(MAX_SPEAKERS).ifEmpty { listOf(DEFAULT_SPEAKER) }
        val text =
            try {
                generator.generate(shortSystem(request, speakers), prompt(request, speakers))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return AiResult.Failure("on_device_${e::class.simpleName}", true)
            } finally {
                lastFinishedMs = nowMs()
            }
        val lines = ResponseParser.parse(text, speakers.first())
        return if (lines.isEmpty()) AiResult.Failure("empty_response", true) else AiResult.Success(ChatResponse(lines))
    }

    companion object {
        const val MAX_SPEAKERS = 2
        const val MAX_SYSTEM_CHARS = 700
        const val MAX_TURNS = 4
        const val MIN_INTERVAL_MS = 3_000L
        private const val DEFAULT_SPEAKER = "kaede"

        /** システム指示（先頭の [MAX_SYSTEM_CHARS] 文字）。 */
        internal fun shortSystem(
            request: ChatRequest,
            speakers: List<String>,
        ): String {
            val head = request.system.take(MAX_SYSTEM_CHARS)
            val who = speakers.joinToString("、")
            return "$head\n発話者は $who のみ。各 1 行・40 字以内。出力は JSON のみ: ${request.jsonSchemaHint.orEmpty()}"
        }

        /** 直近の会話（[MAX_TURNS] 件）と依頼。 */
        internal fun prompt(
            request: ChatRequest,
            speakers: List<String>,
        ): String {
            val turns = request.messages.takeLast(MAX_TURNS).joinToString("\n") { "${it.speaker}: ${it.text}" }
            val who = speakers.joinToString("、")
            return "$turns\n\n次の発話を $who が 1 行ずつ返す。"
        }
    }
}
