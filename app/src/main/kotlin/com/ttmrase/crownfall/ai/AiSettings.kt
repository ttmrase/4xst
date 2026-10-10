package com.ttmrase.crownfall.ai

import com.ttmrase.crownfall.ai.providers.GeminiProvider
import com.ttmrase.crownfall.ai.router.RouterConfig
import com.ttmrase.crownfall.core.chat.RevealAi

/**
 * AI 設定（DataStore に保存。キーは暗号化して保存し、ここでは平文で持つ）。
 * 初期状態は Gemini が選ばれていてキー未入力（= Scripted で動く）。
 */
data class AiSettings(
    /** 試す順。Scripted は常に最後（設定には出ない）。 */
    val order: List<String> = DEFAULT_ORDER,
    val disabled: Set<String> = emptySet(),
    val geminiKey: String = "",
    val geminiModel: String = GeminiProvider.DEFAULT_MODEL,
    val openAiBaseUrl: String = "",
    val openAiModel: String = "",
    val openAiKey: String = "",
    val hourlyBudget: Int = RouterConfig.DEFAULT_HOURLY,
    val dailyBudget: Int = RouterConfig.DEFAULT_DAILY,
    val revealAi: RevealAi = RevealAi.AMBIGUOUS,
    /** 端末内 LLM は充電中だけ使う。 */
    val onDeviceChargingOnly: Boolean = false,
    /** 端末内 LLM のタイムアウト（秒）。 */
    val onDeviceTimeoutSec: Int = DEFAULT_ON_DEVICE_TIMEOUT_SEC,
) {
    /** キーが空か、ヘッダに載せられる印字可能 ASCII か（例外メッセージにキーが載るのを防ぐ）。 */
    fun keysValid(): Boolean = PRINTABLE.matches(geminiKey.trim()) && PRINTABLE.matches(openAiKey.trim())

    /** キーを含めない（ログ・デバッガに出さない）。 */
    override fun toString(): String =
        "AiSettings(order=$order, disabled=$disabled, geminiModel=$geminiModel, openAiModel=$openAiModel, keys=***)"

    fun toRouterConfig(): RouterConfig =
        RouterConfig(
            order = order,
            disabled = disabled,
            hourlyBudget = hourlyBudget,
            dailyBudget = dailyBudget,
            onDeviceTimeoutMs = timeoutSec() * MILLIS,
        )

    private fun timeoutSec(): Int = onDeviceTimeoutSec.coerceIn(MIN_ON_DEVICE_TIMEOUT_SEC, MAX_ON_DEVICE_TIMEOUT_SEC)

    companion object {
        val DEFAULT_ORDER = listOf(GeminiProvider.ID, OnDeviceProvider.ID, "openai_compat")
        const val DEFAULT_ON_DEVICE_TIMEOUT_SEC = 30
        const val MIN_ON_DEVICE_TIMEOUT_SEC = 5
        const val MAX_ON_DEVICE_TIMEOUT_SEC = 300
        private const val MILLIS = 1000L
        const val MAX_BUDGET = 100_000
        private val PRINTABLE = Regex("[\\u0021-\\u007E]*")
    }
}
