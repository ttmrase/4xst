package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatResponse
import com.ttmrase.crownfall.ai.OnDeviceProvider
import com.ttmrase.crownfall.ai.SpokenLine
import kotlinx.coroutines.CancellationException

/** 端末内の LLM に 1 回の生成を頼む口（:app が LiteRT-LM で実装する）。失敗は例外で表す。 */
fun interface OnDeviceGenerator {
    suspend fun generate(
        system: String,
        prompt: String,
    ): String
}

/**
 * 端末内 LLM のプロバイダ。小型モデルは長いプロンプトと JSON が苦手なので、[SmallModelPrompt] の短いプロンプトで
 * 1 回の生成に 1 人だけ話させ、複数話者のイベントは 1 人ずつ順に生成する（前の発言を文脈に足す）。
 * 出力は [ResponseParser.plain] で整え、使えない出力は失敗として返してルーターが次へ回す。
 * 使えない条件（モデル無し・充電中のみ・連続生成の間隔）も失敗として返す。間隔はリクエストの最初の生成の前にだけ見る
 * （同じリクエストの 2 人目は免除）。1 リクエストで話すのは最大 [MAX_SPEAKERS] 人で、残りはルーターが Scripted で埋める。
 */
class OnDeviceChatProvider(
    private val ready: () -> Boolean,
    private val generator: OnDeviceGenerator,
    private val chargingOnly: () -> Boolean = { false },
    private val charging: () -> Boolean = { true },
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val debug: OnDeviceDebugLog? = null,
    private val profile: () -> OnDeviceProfile = { OnDeviceProfile.STANDARD },
) : OnDeviceProvider {
    private var lastFinishedMs = Long.MIN_VALUE / 2

    override fun isModelReady(): Boolean = ready()

    override suspend fun complete(request: ChatRequest): AiResult {
        if (!ready()) return AiResult.Failure("no_model", false)
        if (chargingOnly() && !charging()) return AiResult.Failure("not_charging", false)
        // 連続生成の間隔を空ける（発熱・電池への配慮）。同じリクエストの 2 人目以降は免除する
        if (nowMs() - lastFinishedMs < minIntervalMs) return AiResult.Failure("cooldown", true)
        val prof = profile()
        val all = request.speakers.ifEmpty { listOf(DEFAULT_SPEAKER) }
        // 標準: まず 1 回の生成で複数話者の「表示名: セリフ」を試す。読めなければ 1 人ずつ（小型と同じ経路）へ
        if (prof == OnDeviceProfile.STANDARD && all.size >= 2) {
            val lines = tryMulti(request, all, prof)
            if (lines != null) return AiResult.Success(ChatResponse(lines))
        }
        val speakers = all.take(MAX_SPEAKERS)
        val spoken = ArrayList<SpokenLine>()
        for (speaker in speakers) {
            val built =
                if (prof == OnDeviceProfile.STANDARD) {
                    StandardPrompt.single(request, speaker, spoken)
                } else {
                    SmallModelPrompt.build(request, speaker, spoken)
                }
            val started = nowMs()
            val raw =
                try {
                    generator.generate(built.system, built.prompt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (spoken.isEmpty()) return AiResult.Failure("on_device_${e::class.simpleName}", true)
                    break
                } finally {
                    lastFinishedMs = nowMs()
                }
            val previous = request.recent + request.messages.map { it.text } + spoken.map { it.text }
            val cleaned =
                ResponseParser.plain(
                    raw,
                    request.locale,
                    built.system + "\n" + built.prompt,
                    previous,
                    prof.maxSentences,
                    prof.maxChars,
                )
            debug?.record(OnDeviceDebugLog.Entry(speaker, built.system, built.prompt, raw, cleaned, nowMs() - started))
            if (cleaned == null) {
                if (spoken.isEmpty()) return AiResult.Failure("bad_output", true)
                break
            }
            spoken += SpokenLine(speaker, cleaned)
        }
        return AiResult.Success(ChatResponse(spoken))
    }

    /** 標準プロファイルの複数話者: 1 回の生成で最大 3 行。読めた行が無ければ null（1 人ずつにフォールバック）。 */
    private suspend fun tryMulti(
        request: ChatRequest,
        speakers: List<String>,
        prof: OnDeviceProfile,
    ): List<SpokenLine>? {
        val built = StandardPrompt.multi(request, speakers)
        val started = nowMs()
        val raw =
            try {
                generator.generate(built.system, built.prompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return null
            } finally {
                lastFinishedMs = nowMs()
            }
        val previous = request.recent + request.messages.map { it.text }
        val lines =
            ResponseParser.multiLine(
                raw,
                request.locale,
                StandardPrompt.nameTable(request, speakers),
                built.system + "\n" + built.prompt,
                previous,
                prof.maxSentences,
                prof.maxChars,
            )
        val joined = lines.joinToString(" / ") { it.text }.ifEmpty { null }
        debug?.record(OnDeviceDebugLog.Entry("multi", built.system, built.prompt, raw, joined, nowMs() - started))
        return lines.ifEmpty { null }
    }

    companion object {
        const val MAX_SPEAKERS = 2
        const val MIN_INTERVAL_MS = 3_000L
        private const val DEFAULT_SPEAKER = "kaede"
    }
}
