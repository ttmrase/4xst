package com.ttmrase.crownfall.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
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
) : OnDeviceLlmEngine {
    private val app = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "ondevice-llm") }
    private val busy = AtomicBoolean(false)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var engine: Engine? = null

    @Volatile private var loadedKey: String? = null

    @Volatile private var failedKey: String? = null

    init {
        // 前回、読み込み中に落ちたモデルは失敗扱いにする（ネイティブのクラッシュで毎回落ちるのを防ぐ）
        val crashed = prefs.getString(KEY_LOADING, null)
        if (crashed != null) prefs.edit().putString(KEY_FAILED, crashed).remove(KEY_LOADING).commit()
        failedKey = prefs.getString(KEY_FAILED, null)
    }

    override fun isModelReady(): Boolean {
        val file = store.current() ?: return false
        return keyOf(file) != failedKey
    }

    override suspend fun generate(
        system: String,
        prompt: String,
    ): String {
        check(busy.compareAndSet(false, true)) { "busy" }
        val result = CompletableDeferred<String>()
        try {
            executor.execute {
                try {
                    val e = ensureEngine()
                    val text =
                        e.createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { c ->
                            c.sendMessage(prompt).toString()
                        }
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
        engine?.let { if (loadedKey == key) return it }
        closeEngine()
        prefs.edit().putString(KEY_LOADING, key).commit()
        return try {
            val config = EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = app.cacheDir.path)
            Engine(config).also {
                it.initialize()
                engine = it
                loadedKey = key
                prefs.edit().remove(KEY_LOADING).commit()
            }
        } catch (t: Throwable) {
            // OOM や UnsatisfiedLinkError も含めて、このモデルは失敗扱いにする
            failedKey = key
            prefs.edit().putString(KEY_FAILED, key).remove(KEY_LOADING).commit()
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
    }

    private companion object {
        const val PREFS = "ondevice_engine"
        const val KEY_LOADING = "loading_model"
        const val KEY_FAILED = "failed_model"
    }

    private fun keyOf(file: File): String = "${file.name}:${file.length()}:${file.lastModified()}"
}
