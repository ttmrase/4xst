package com.ttmrase.crownfall.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.ttmrase.crownfall.ai.providers.OnDeviceProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
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
    private val warming = AtomicBoolean(false)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var engine: Engine? = null

    @Volatile private var loadedKey: String? = null

    @Volatile private var failedKey: String? = null

    /** GPU での読み込みに失敗した（例外、またはプロセスごと落ちた）モデル。自動のときは CPU を使う。 */
    @Volatile private var failedGpuKey: String? = null

    /** 生成中の会話（時間切れ・キャンセルで取り消すため）。 */
    @Volatile private var activeConversation: Conversation? = null

    @Volatile override var activeBackend: String = ""
        private set

    /** 読み込み直後で、まだ生成に成功していない（読み込み中の記録を残している）。 */
    @Volatile private var firstGenerationPending = false

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
                        try {
                            generateStreaming(e, system, prompt, profile)
                        } catch (t: Throwable) {
                            onGenerationFailed()
                            throw t
                        }
                    confirmLoaded()
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
        try {
            return result.await()
        } catch (ce: CancellationException) {
            // ルーターの時間切れ・キャンセル。ネイティブ側の生成も止める（busy が残り続けないように）
            cancelGeneration()
            throw ce
        }
    }

    /** 実行中の会話の生成を取り消す。取り消せなくても、busy の間の要求は即 Failure("busy") で Scripted に回る。 */
    private fun cancelGeneration() {
        val c = activeConversation ?: return
        try {
            c.cancelProcess()
        } catch (_: Throwable) {
            // 取り消しに失敗しても、生成の終了を待つだけ
        }
    }

    /**
     * 会話 1 回分をストリーミングで受け取り、プロファイルの上限（文字数の目安 = maxTokens x 3）に達したら打ち切る。
     * 受け取る単位が差分でも累積でも読めるようにしてある（新しい断片が、いまの文字列で始まっていれば累積とみなす）。
     */
    private fun generateStreaming(
        e: Engine,
        system: String,
        prompt: String,
        profile: OnDeviceProfile,
    ): String {
        val config =
            ConversationConfig(
                systemInstruction = Contents.of(system),
                samplerConfig =
                    SamplerConfig(topK = profile.topK, topP = profile.topP, temperature = profile.temperature),
            )
        val limit = profile.maxTokens * CHARS_PER_TOKEN
        val sb = StringBuilder()
        e.createConversation(config).use { c ->
            activeConversation = c
            try {
                runBlocking {
                    c.sendMessageAsync(prompt).takeWhile { part ->
                        val chunk = part.toString()
                        if (sb.isNotEmpty() && chunk.startsWith(sb)) sb.setLength(0)
                        sb.append(chunk)
                        sb.length < limit
                    }.collect()
                }
            } finally {
                activeConversation = null
            }
        }
        return sb.toString()
    }

    /** この backend での最初の生成が成功した。落ちたら記録が残るよう、読み込み中の記録はここで消す。 */
    private fun confirmLoaded() {
        if (!firstGenerationPending) return
        firstGenerationPending = false
        prefs.edit().remove(KEY_LOADING).commit()
    }

    /** 最初の生成が例外で失敗した。GPU なら、そのモデルの GPU を失敗扱いにして、次からは CPU で読み込む。 */
    private fun onGenerationFailed() {
        if (!firstGenerationPending) return
        firstGenerationPending = false
        val key = loadedKey
        if (activeBackend == "gpu" && key != null) {
            failedGpuKey = key
            prefs.edit().putString(KEY_FAILED_GPU, key).remove(KEY_LOADING).commit()
        } else {
            prefs.edit().remove(KEY_LOADING).commit()
        }
        closeEngine()
    }

    private fun ensureEngine(): Engine {
        val file = store.current() ?: error("no_model")
        val key = keyOf(file)
        check(key != failedKey) { "model_failed" }
        val pref = backendPref()
        engine?.let { if (loadedKey == key && backendMatches(pref, key)) return it }
        closeEngine()
        // GPU を使うのは、設定が CPU 以外で、このモデルの GPU が失敗していないとき。GPU が落ちた記録（例外・クラッシュ）が
        // あれば、設定が GPU でも CPU で読む（ユーザーが「GPU を再試行」を押したときだけ記録を消す）。
        if (pref != "cpu" && failedGpuKey != key) {
            try {
                return load(file, key, gpu = true)
            } catch (t: Throwable) {
                failedGpuKey = key
                prefs.edit().putString(KEY_FAILED_GPU, key).remove(KEY_LOADING).commit()
            }
        }
        return load(file, key, gpu = false)
    }

    /** 読み込み済みの engine が、いまの設定でそのまま使えるか。GPU が失敗したモデルでは CPU のままでよい。 */
    private fun backendMatches(
        pref: String,
        key: String,
    ): Boolean =
        when (pref) {
            "gpu" -> activeBackend == "gpu" || failedGpuKey == key
            "cpu" -> activeBackend == "cpu"
            else -> true
        }

    private fun load(
        file: File,
        key: String,
        gpu: Boolean,
    ): Engine {
        prefs.edit().putString(KEY_LOADING, key + if (gpu) GPU_SUFFIX else CPU_SUFFIX).commit()
        val backend = if (gpu) Backend.GPU() else Backend.CPU()
        val config =
            EngineConfig(
                modelPath = file.path,
                backend = backend,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = app.cacheDir.path,
            )
        val e = Engine(config)
        try {
            e.initialize()
        } catch (t: Throwable) {
            // 初期化に失敗した Engine は閉じる（GPU の資源を持ったまま CPU を読み込まない）
            runCatching { e.close() }
            // GPU の失敗は呼び出し側が CPU に戻す。CPU の失敗（OOM や UnsatisfiedLinkError も含む）はモデルを失敗扱いにする
            if (!gpu) {
                failedKey = key
                prefs.edit().putString(KEY_FAILED, key).remove(KEY_LOADING).commit()
            }
            throw t
        }
        engine = e
        loadedKey = key
        activeBackend = if (gpu) "gpu" else "cpu"
        // 読み込み中の記録は、この backend での最初の生成が成功するまで残す（GPU は最初の推論で落ちることが多い）
        firstGenerationPending = true
        return e
    }

    /** 先にモデルを読み込んでおく（チャットを開いたとき）。生成中や、読み込みに失敗したモデルでは何もしない。 */
    override fun warmUp() {
        if (!isModelReady() || engine != null) return
        if (!warming.compareAndSet(false, true)) return
        // 専用の 1 本のスレッドで読み込むので、読み込み中に来た生成は、その後ろに並んで待つ（busy にはしない）
        executor.execute {
            try {
                ensureEngine()
            } catch (_: Throwable) {
                // 失敗は次の生成で改めて扱う（記録は load が残す）
            } finally {
                warming.set(false)
            }
        }
    }

    override val gpuFailed: Boolean
        get() = store.current()?.let { keyOf(it) == failedGpuKey } == true

    /** 「GPU を再試行」。GPU が落ちた記録を消して、次の読み込みでもう一度 GPU を試す。 */
    override fun retryGpu() {
        failedGpuKey = null
        prefs.edit().remove(KEY_FAILED_GPU).commit()
        if (!busy.get()) executor.execute { closeEngine() }
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
        // きれいに閉じたので、「読み込み中」の記録（最初の生成の前に閉じた場合）は残さない
        if (firstGenerationPending) {
            firstGenerationPending = false
            prefs.edit().remove(KEY_LOADING).commit()
        }
    }

    private companion object {
        const val PREFS = "ondevice_engine"
        const val KEY_LOADING = "loading_model"
        const val KEY_FAILED = "failed_model"

        /** 全体のトークン数の上限（日本語のプロンプト 1,300 文字 ≒ 1,000〜1,300 トークン + 生成 96 が収まる大きさ。E2B の文脈長内）。 */
        const val MAX_NUM_TOKENS = 2048
        const val CHARS_PER_TOKEN = 3
        const val KEY_FAILED_GPU = "failed_gpu_model"
        const val GPU_SUFFIX = "|gpu"
        const val CPU_SUFFIX = "|cpu"
    }

    private fun keyOf(file: File): String = "${file.name}:${file.length()}:${file.lastModified()}"
}
