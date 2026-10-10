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
) {
    fun toRouterConfig(): RouterConfig =
        RouterConfig(order = order, disabled = disabled, hourlyBudget = hourlyBudget, dailyBudget = dailyBudget)

    companion object {
        val DEFAULT_ORDER = listOf(GeminiProvider.ID, "openai_compat")
        const val MAX_BUDGET = 100_000
    }
}
