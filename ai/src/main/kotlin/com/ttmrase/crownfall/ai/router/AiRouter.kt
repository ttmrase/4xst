package com.ttmrase.crownfall.ai.router

import com.ttmrase.crownfall.ai.AiProvider
import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatResponse
import com.ttmrase.crownfall.ai.OnDeviceProvider
import com.ttmrase.crownfall.ai.providers.ScriptedProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ルーターの設定。[order] は試す順（Scripted は含めない。最後の砦として常に使う）、
 * [disabled] は一時的に無効にしたプロバイダ。
 */
data class RouterConfig(
    val order: List<String> = listOf("gemini"),
    val disabled: Set<String> = emptySet(),
    val hourlyBudget: Int = DEFAULT_HOURLY,
    val dailyBudget: Int = DEFAULT_DAILY,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** 端末内 LLM のタイムアウト（ローカルなので長め）。 */
    val onDeviceTimeoutMs: Long = DEFAULT_ON_DEVICE_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_HOURLY = 20
        const val DEFAULT_DAILY = 200
        const val DEFAULT_TIMEOUT_MS = 8_000L
        const val DEFAULT_ON_DEVICE_TIMEOUT_MS = 30_000L

        /** 予算の対象外のプロバイダ（端末内で動くので通信も料金もかからない）。 */
        val LOCAL_PROVIDERS: Set<String> = setOf(OnDeviceProvider.ID)
    }
}

/**
 * AI ルーター（03 §5）。設定順にプロバイダを試す。429・5xx・ネットワーク失敗・タイムアウトなら次へ。
 * 予算（毎時・日次）を超えたら Scripted。全部失敗しても Scripted。失敗はユーザーに見せない（ログと 24h 集計のみ）。
 */
class AiRouter(
    private val providers: Map<String, AiProvider>,
    private val scripted: ScriptedProvider,
    private val config: () -> RouterConfig,
    private val usage: UsageLog,
    private val log: (String) -> Unit = {},
    private val filter: OutputFilter = OutputFilter(),
) {
    /** 常に発話を返す（最後は Scripted）。 */
    suspend fun complete(request: ChatRequest): ChatResponse {
        val cfg = config()
        for (id in cfg.order) {
            val provider = providers[id] ?: continue
            if (id in cfg.disabled || !provider.isConfigured()) continue
            if (id !in RouterConfig.LOCAL_PROVIDERS && overBudget(cfg)) {
                log("budget exceeded; skipping $id")
                continue
            }
            val lines = attempt(provider, request, cfg)
            if (lines != null) return ChatResponse(lines + fillRest(id, lines, request))
        }
        return ChatResponse(scripted.generate(request))
    }

    /** 端末内 LLM は 1 リクエストで話せる人数に上限がある。話者が余ったぶんは Scripted で埋める。 */
    private fun fillRest(
        providerId: String,
        lines: List<com.ttmrase.crownfall.ai.SpokenLine>,
        request: ChatRequest,
    ): List<com.ttmrase.crownfall.ai.SpokenLine> {
        if (providerId != OnDeviceProvider.ID) return emptyList()
        val rest = request.speakers.drop(lines.size)
        return if (rest.isEmpty()) emptyList() else scripted.generate(request.copy(speakers = rest))
    }

    private fun overBudget(cfg: RouterConfig): Boolean =
        usage.countSince(UsageLog.HOUR, RouterConfig.LOCAL_PROVIDERS) >= cfg.hourlyBudget ||
            usage.countSince(UsageLog.DAY, RouterConfig.LOCAL_PROVIDERS) >= cfg.dailyBudget

    private suspend fun attempt(
        provider: AiProvider,
        request: ChatRequest,
        cfg: RouterConfig,
    ): List<com.ttmrase.crownfall.ai.SpokenLine>? {
        val result =
            try {
                val limit = if (provider.id == OnDeviceProvider.ID) cfg.onDeviceTimeoutMs else cfg.timeoutMs
                withTimeoutOrNull(limit) { provider.complete(request) } ?: AiResult.Failure("timeout", true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AiResult.Failure("exception_${e::class.simpleName}", true)
            }
        return when (result) {
            is AiResult.Success -> {
                val allowed = request.speakers.toSet() + explicitSpeakers(request)
                val fallback = request.speakers.firstOrNull() ?: DEFAULT_SPEAKER
                val lines = filter.sanitize(result.response.lines, allowed, fallback)
                usage.record(provider.id, lines.isNotEmpty())
                if (lines.isEmpty()) log("${provider.id}: empty after filter") else return lines
                null
            }
            is AiResult.Failure -> {
                usage.record(provider.id, false)
                log("${provider.id}: ${result.reason}")
                null
            }
        }
    }

    /** 全ペルソナ ID（LLM が登場人物の別の id を使ったときに弾きすぎないため、既知の id は通す）。 */
    private fun explicitSpeakers(request: ChatRequest): Set<String> = request.knownSpeakers

    private companion object {
        const val DEFAULT_SPEAKER = "kaede"
    }
}
