package com.ttmrase.crownfall.ai

import android.content.Context
import android.os.BatteryManager
import com.ttmrase.crownfall.ai.providers.GeminiProvider
import com.ttmrase.crownfall.ai.providers.OnDeviceChatProvider
import com.ttmrase.crownfall.ai.providers.OnDeviceDebugLog
import com.ttmrase.crownfall.ai.providers.OnDeviceProfile
import com.ttmrase.crownfall.ai.providers.OpenAiCompatProvider
import com.ttmrase.crownfall.ai.providers.ResponseParser
import com.ttmrase.crownfall.ai.providers.ScriptedProvider
import com.ttmrase.crownfall.ai.providers.SmallModelPrompt
import com.ttmrase.crownfall.ai.providers.StandardPrompt
import com.ttmrase.crownfall.ai.router.AiRouter
import com.ttmrase.crownfall.ai.router.UsageLog
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.util.SystemClock
import com.ttmrase.crownfall.ondevice.LiteRtLmEngine
import com.ttmrase.crownfall.ondevice.OnDeviceLlmEngine
import com.ttmrase.crownfall.ondevice.OnDeviceModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
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
    private val app = context.applicationContext

    /** 端末内 LLM のモデルファイルの置き場。 */
    val modelStore = OnDeviceModelStore(context, scope)

    /** 端末内 LLM の実行エンジン（ランタイムはここに閉じ込める）。 */
    private val onDeviceEngine: OnDeviceLlmEngine = LiteRtLmEngine(context, modelStore) { current().onDeviceBackend }

    /** 端末内 LLM の直近の入出力（設定のデバッグ表示用。メモリ上だけで、保存しない）。 */
    val debugLog = OnDeviceDebugLog()

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
                    OnDeviceChatProvider(
                        ready = onDeviceEngine::isModelReady,
                        generator = { system, prompt -> onDeviceEngine.generate(system, prompt, onDeviceProfile()) },
                        chargingOnly = { current().onDeviceChargingOnly },
                        charging = ::isCharging,
                        debug = debugLog,
                        profile = ::onDeviceProfile,
                    ),
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

    /** いまのプロンプトの種類（設定が自動ならモデルのファイル名で決める）。 */
    fun onDeviceProfile(): OnDeviceProfile =
        OnDeviceProfile.resolve(
            current().onDeviceProfile,
            modelStore.current()?.name,
        )

    /** 設定の「テスト発話」の結果。[text] が null なら失敗（[error] に理由）。[millis] は生成にかかった時間。 */
    data class TestUtterance(
        val text: String?,
        val millis: Long,
        val error: String? = null,
        val profile: String = "",
        val backend: String = "",
    )

    /** いまのモデルで Kaede に一言言わせる（ルーターも予算も通さない）。生成時間も返す。 */
    suspend fun testUtterance(data: GameData): TestUtterance {
        if (!onDeviceEngine.isModelReady()) return TestUtterance(null, 0, "no_model")
        val locale = Locale.getDefault().language
        val profile = onDeviceProfile()
        val request = SmallModelPrompt.testRequest(data, locale)
        val speaker = request.speakers.first()
        val built =
            if (profile == OnDeviceProfile.STANDARD) {
                StandardPrompt.single(request, speaker)
            } else {
                SmallModelPrompt.build(request, speaker)
            }
        val started = System.nanoTime()
        return try {
            val raw = onDeviceEngine.generate(built.system, built.prompt, profile)
            val millis = (System.nanoTime() - started) / NANOS_PER_MS
            val prompt = built.system + "\n" + built.prompt
            val cleaned = ResponseParser.plain(raw, locale, prompt, emptyList(), profile.maxSentences, profile.maxChars)
            debugLog.record(OnDeviceDebugLog.Entry(speaker, built.system, built.prompt, raw, cleaned, millis))
            val shown = cleaned ?: raw.trim().take(RAW_PREVIEW_CHARS)
            TestUtterance(
                shown,
                millis,
                if (cleaned == null) "rejected" else null,
                profile.id,
                onDeviceEngine.activeBackend,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val millis = (System.nanoTime() - started) / NANOS_PER_MS
            TestUtterance(null, millis, e::class.simpleName ?: "error", profile.id, onDeviceEngine.activeBackend)
        }
    }

    /** 背景に入るとき、端末内 LLM のメモリを空ける。 */
    fun releaseOnDevice() = onDeviceEngine.release()

    private fun isCharging(): Boolean = app.getSystemService(BatteryManager::class.java)?.isCharging ?: true

    private companion object {
        const val CONNECT_SEC = 5L
        const val READ_SEC = 10L
        const val CALL_SEC = 12L
        const val RAW_PREVIEW_CHARS = 200
        const val NANOS_PER_MS = 1_000_000L
    }
}
