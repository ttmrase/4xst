package com.ttmrase.crownfall.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ttmrase.crownfall.core.chat.RevealAi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiStore: DataStore<Preferences> by preferencesDataStore(name = "ai_settings")

/** [AiSettings] の DataStore 保存。API キーは [SecretCipher] で暗号化して保存する。 */
class AppSettings(context: Context, private val cipher: SecretCipher = SecretCipher()) {
    private val store = context.applicationContext.aiStore

    /** 現在の設定（変更のたびに流れる）。 */
    val flow: Flow<AiSettings> = store.data.map { read(it) }

    private fun read(p: Preferences): AiSettings {
        val base = AiSettings()
        val order = p[ORDER]?.split(',')?.filter { it in AiSettings.DEFAULT_ORDER }.orEmpty()
        val full = (order + AiSettings.DEFAULT_ORDER).distinct()
        return AiSettings(
            order = full,
            disabled = p[DISABLED]?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: base.disabled,
            geminiKey = p[GEMINI_KEY]?.let(cipher::decrypt).orEmpty(),
            geminiModel = p[GEMINI_MODEL]?.takeIf { it.isNotBlank() } ?: base.geminiModel,
            openAiBaseUrl = p[OPENAI_URL] ?: base.openAiBaseUrl,
            openAiModel = p[OPENAI_MODEL] ?: base.openAiModel,
            openAiKey = p[OPENAI_KEY]?.let(cipher::decrypt).orEmpty(),
            hourlyBudget = p[HOURLY] ?: base.hourlyBudget,
            dailyBudget = p[DAILY] ?: base.dailyBudget,
            revealAi = if (p[REVEAL_ADMIT] == true) RevealAi.ADMIT else RevealAi.AMBIGUOUS,
        )
    }

    /** [transform] で現在の設定を書き換えて保存する。 */
    suspend fun update(transform: (AiSettings) -> AiSettings) {
        store.edit { p ->
            val next = transform(read(p))
            p[ORDER] = next.order.joinToString(",")
            p[DISABLED] = next.disabled.joinToString(",")
            p[GEMINI_KEY] = cipher.encrypt(next.geminiKey.trim())
            p[GEMINI_MODEL] = next.geminiModel.trim()
            p[OPENAI_URL] = next.openAiBaseUrl.trim()
            p[OPENAI_MODEL] = next.openAiModel.trim()
            p[OPENAI_KEY] = cipher.encrypt(next.openAiKey.trim())
            p[HOURLY] = next.hourlyBudget.coerceIn(0, AiSettings.MAX_BUDGET)
            p[DAILY] = next.dailyBudget.coerceIn(0, AiSettings.MAX_BUDGET)
            p[REVEAL_ADMIT] = next.revealAi == RevealAi.ADMIT
        }
    }

    private companion object {
        val ORDER = stringPreferencesKey("order")
        val DISABLED = stringPreferencesKey("disabled")
        val GEMINI_KEY = stringPreferencesKey("gemini_key_enc")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val OPENAI_URL = stringPreferencesKey("openai_base_url")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_KEY = stringPreferencesKey("openai_key_enc")
        val HOURLY = intPreferencesKey("hourly_budget")
        val DAILY = intPreferencesKey("daily_budget")
        val REVEAL_ADMIT = booleanPreferencesKey("reveal_ai_admit")
    }
}
