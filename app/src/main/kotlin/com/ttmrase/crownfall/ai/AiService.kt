package com.ttmrase.crownfall.ai

import android.content.Context
import com.ttmrase.crownfall.ai.providers.GeminiProvider
import com.ttmrase.crownfall.ai.providers.OpenAiCompatProvider
import com.ttmrase.crownfall.ai.providers.ScriptedProvider
import com.ttmrase.crownfall.ai.router.AiRouter
import com.ttmrase.crownfall.ai.router.UsageLog
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.util.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * :ai のプロバイダとルーターを :app の設定につなぐ。設定は [AppSettings] を購読し、呼び出しごとに最新を読む。
 * キーはログに出さない（このクラスは何もログしない）。
 */
class AiService(
    context: Context,
    scope: CoroutineScope,
    val settingsStore: AppSettings = AppSettings(context),
) {
    private val _settings = MutableStateFlow<AiSettings?>(null)
    private val usage = UsageLog(PrefsUsageStore(context)) { SystemClock.nowEpochSec() }
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_SEC, TimeUnit.SECONDS)
            .build()

    /** 読み込み済みの設定（読み込み前は null）。 */
    val settings: StateFlow<AiSettings?> = _settings.asStateFlow()

    init {
        scope.launch { settingsStore.flow.collect { _settings.value = it } }
    }

    private fun current(): AiSettings = _settings.value ?: AiSettings()

    /** 設定が読み込まれるまで待つ。 */
    suspend fun awaitLoaded(): AiSettings = _settings.first { it != null } ?: AiSettings()

    /** 直近 24h の使用回数と失敗数。 */
    fun usageStats() = usage.stats()

    /** [data] に基づく Scripted（最後の砦。バックログの一括処理にも使う）。 */
    fun scripted(data: GameData) = ScriptedProvider(data)

    /** [data] 用のルーターを作る。 */
    fun router(data: GameData): AiRouter =
        AiRouter(
            providers =
                listOf(
                    GeminiProvider(client, { current().geminiKey }, { current().geminiModel }),
                    OpenAiCompatProvider(
                        client,
                        { current().openAiBaseUrl },
                        { current().openAiKey },
                        { current().openAiModel },
                    ),
                ).associateBy { it.id },
            scripted = scripted(data),
            config = { current().toRouterConfig() },
            usage = usage,
        )

    private companion object {
        const val CONNECT_SEC = 5L
        const val READ_SEC = 10L
        const val CALL_SEC = 12L
    }
}
