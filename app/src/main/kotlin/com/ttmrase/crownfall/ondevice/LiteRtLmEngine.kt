package com.ttmrase.crownfall.ondevice

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LiteRT-LM による [OnDeviceLlmEngine]。専用のシングルスレッドで動かし、同時に走るのは 1 件だけ（実行中に来た依頼は
 * すぐ失敗にして次のプロバイダへ回す）。読み込みに失敗したモデルは、ファイルが差し替わるまで再試行しない。
 */
class LiteRtLmEngine(
    context: Context,
    private val store: OnDeviceModelStore,
) : OnDeviceLlmEngine {
    private val app = context.applicationContext
    private val dispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "ondevice-llm") }.asCoroutineDispatcher()
    private val busy = AtomicBoolean(false)

    @Volatile private var engine: Engine? = null

    @Volatile private var loadedKey: String? = null

    @Volatile private var failedKey: String? = null

    override fun isModelReady(): Boolean {
        val file = store.current() ?: return false
        return keyOf(file) != failedKey
    }

    override suspend fun generate(
        system: String,
        prompt: String,
    ): String {
        check(busy.compareAndSet(false, true)) { "busy" }
        try {
            return withContext(dispatcher) {
                val e = ensureEngine()
                e.createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { c ->
                    c.sendMessage(prompt).toString()
                }
            }
        } finally {
            busy.set(false)
        }
    }

    private fun ensureEngine(): Engine {
        val file = store.current() ?: error("no_model")
        val key = keyOf(file)
        engine?.let { if (loadedKey == key) return it }
        closeEngine()
        return try {
            val config = EngineConfig(modelPath = file.path, backend = Backend.CPU(), cacheDir = app.cacheDir.path)
            Engine(config).also {
                it.initialize()
                engine = it
                loadedKey = key
            }
        } catch (e: Exception) {
            failedKey = key
            throw e
        }
    }

    override fun release() {
        if (engine == null) return
        // 生成中なら完了を待たずに解放しない（次の背景化で解放される）
        if (busy.get()) return
        dispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext, Runnable { closeEngine() })
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
    }

    private fun keyOf(file: File): String = "${file.name}:${file.length()}:${file.lastModified()}"
}
