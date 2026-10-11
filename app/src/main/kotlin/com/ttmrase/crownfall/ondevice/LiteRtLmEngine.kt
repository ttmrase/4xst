package com.ttmrase.crownfall.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.ttmrase.crownfall.ai.providers.OnDeviceProfile
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiteRT-LM による [OnDeviceLlmEngine]。専用のシングルスレッドで動かし、同時に走るのは 1 件だけ（実行中に来た依頼は
 * すぐ失敗にして次のプロバイダへ回す）。生成はブロッキングのネイティブ呼び出しなので、呼び出し側のタイムアウトでは
 * 止められない。呼び出し側は [CompletableDeferred] を待つだけで先に戻り、busy はネイティブ呼び出しが終わるまで立てたままにする。
 * 読み込みに失敗したモデルは、ファイルが差し替わるまで再試行しない。読み込み中にプロセスごと落ちた場合に備えて、
 * 読み込み前に「読み込み中のモデル」を残し、次の起動でそれが残っていれば失敗扱いにする。
 */
class LiteRtLmEngine(
    context: Context,
    private val store: OnDeviceModelStore,
    /** 設定の「バックエンド」（`auto` / `cpu` / `gpu`）。生成のたびに最新を読む。 */
    private val backendPref: () -> String = { "auto" },
) : OnDeviceLlmEngine {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ondevice-llm") }
    private val busy = AtomicBoolean(false)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var engine: Engine? = null

    @Volatile private var loadedKey: String? = null

    @Volatile private var failedKey: String? = null

    /** GPU での読み込みに失敗した（例外、またはプロセスごと落ちた）モデル。自動のときは CPU を使う。 */
    @Volatile private var failedGpuKey: String? = null

    @Volatile override var activeBackend: String = ""
        private set

    init {
        // 前回、読み込み中に落ちたモデルは失敗扱いにする（ネイティブのクラッシュで毎回落ちるのを防ぐ）。
        // GPU で落ちたなら、そのモデルは GPU だけを失敗扱いにして、CPU では読めるようにする。
        val crashed = prefs.getString(KEY_LOADING, null)
        if (crashed != null) {
            val edit = prefs.edit().remove(KEY_LOADING)
            if (crashed.endsWith(GPU_SUFFIX)) {
                edit.putString(KEY_FAILED_GPU, crashed.removeSuffix(GPU_SUFFIX))
            } else {
                edit.putString(KEY_FAILED, crashed.removeSuffix(CPU_SUFFIX))
            }
            edit.commit()
        }
        failedKey = prefs.getString(KEY_FAILED, null)
        failedGpuKey = prefs.getString(KEY_FAILED_GPU, null)
    }

    override fun isModelReady(): Boolean {
        val file = store.current() ?: return false
        return keyOf(file) != failedKey
    }

    override suspend fun generate(
        system: String,
        prompt: String,
        profile: OnDeviceProfile,
    ): String {
        check(busy.compareAndSet(false, true)) { "busy" }
        val result = CompletableDeferred<String>()
        try {
            executor.execute {
                try {
                    val e = ensureEngine()
                    val text =
                        e.createConversation(
                            ConversationConfig(
                                systemInstruction = Contents.of(system),
                                samplerConfig =
                                    SamplerConfig(
                                        topK = profile.topK,
                                        topP = profile.topP,
                                        temperature = profile.temperature,
                                    ),
                            ),
                        ).use { c -> c.sendMessage(prompt).toString() }
                    result.complete(text)
                } catch (t: Throwable) {
                    result.completeExceptionally(t)
                } finally {
                    busy.set(false)
                }
            }
        } catch (t: Throwable) {
            busy.set(false)
            throw t
        }
        return result.await()
    }

    private fun ensureEngine(): Engine {
        val file = store.current() ?: error("no_model")
        val key = keyOf(file)
        val pref = backendPref()
        engine?.let { if (loadedKey == key && backendMatches(pref)) return it }
        closeEngine()
        // 自動: GPU を先に試し、例外（またはクラッシュの記録）があれば CPU。GPU 指定は記録を無視してもう一度試す。
        val tryGpu = pref == "gpu" || (pref == "auto" && failedGpuKey != key)
        if (tryGpu) {
            try {
                return load(file, key, gpu = true)
            } catch (t: Throwable) {
                failedGpuKey = key
                prefs.edit().putString(KEY_FAILED_GPU, key).remove(KEY_LOADING).commit()
                if (pref == "gpu") throw t
            }
        }
        return load(file, key, gpu = false)
    }

    private fun backendMatches(pref: String): Boolean =
        when (pref) {
            "gpu" -> activeBackend == "gpu"
            "cpu" -> activeBackend == "cpu"
            else -> true
        }

    private fun load(
        file: File,
        key: String,
        gpu: Boolean,
    ): Engine {
        prefs.edit().putString(KEY_LOADING, key + if (gpu) GPU_SUFFIX else CPU_SUFFIX).commit()
        return try {
            val backend = if (gpu) Backend.GPU() else Backend.CPU()
            val config =
                EngineConfig(
                    modelPath = file.path,
                    backend = backend,
                    maxNumTokens = MAX_NUM_TOKENS,
                    cacheDir = app.cacheDir.path,
                )
            Engine(config).also {
                it.initialize()
                engine = it
                loadedKey = key
                activeBackend = if (gpu) "gpu" else "cpu"
                prefs.edit().remove(KEY_LOADING).commit()
            }
        } catch (t: Throwable) {
            // GPU の失敗は呼び出し側が CPU に戻す。CPU の失敗（OOM や UnsatisfiedLinkError も含む）はモデルを失敗扱いにする
            if (!gpu) {
                failedKey = key
                prefs.edit().putString(KEY_FAILED, key).remove(KEY_LOADING).commit()
            }
            throw t
        }
    }

    override fun release() {
        if (engine == null) return
        // 生成中なら完了を待たずに解放しない（次の背景化で解放される）
        if (busy.get()) return
        executor.execute { closeEngine() }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
        activeBackend = ""
    }

    private companion object {
        const val PREFS = "ondevice_engine"
        const val KEY_LOADING = "loading_model"
        const val KEY_FAILED = "failed_model"

        /** 読み込み時の KV キャッシュなどの上限（プロンプト 600〜700 トークン + 生成 96 トークンが収まる大きさ）。 */
        const val MAX_NUM_TOKENS = 1280
        const val KEY_FAILED_GPU = "failed_gpu_model"
        const val GPU_SUFFIX = "|gpu"
        const val CPU_SUFFIX = "|cpu"
    }

    private fun keyOf(file: File): String = "${file.name}:${file.length()}:${file.lastModified()}"
}
