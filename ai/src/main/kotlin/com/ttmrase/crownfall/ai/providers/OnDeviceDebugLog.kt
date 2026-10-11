package com.ttmrase.crownfall.ai.providers

/**
 * 端末内 LLM の直近の入出力（デバッグ用）。メモリ上だけに最新 [capacity] 件を持ち、保存しない。
 * 設定画面の「直近の入出力を表示」で見る。
 */
class OnDeviceDebugLog(private val capacity: Int = CAPACITY) {
    /** 1 回の生成。[cleaned] が null なら後処理で弾かれた（次のプロバイダへ回った）。 */
    data class Entry(
        val speaker: String,
        val system: String,
        val prompt: String,
        val raw: String,
        val cleaned: String?,
        val millis: Long,
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > capacity) entries.removeFirst()
    }

    /** 新しい順。 */
    @Synchronized
    fun recent(): List<Entry> = entries.toList().asReversed()

    companion object {
        const val CAPACITY = 5
    }
}
