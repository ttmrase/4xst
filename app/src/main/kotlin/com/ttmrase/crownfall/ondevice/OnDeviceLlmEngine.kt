package com.ttmrase.crownfall.ondevice

/**
 * 端末内 LLM の実行エンジン（ランタイムはこのインターフェースの後ろに閉じ込める。LiteRT-LM → tasks-genai に
 * 差し替えられる）。呼び出しは 1 件ずつ。モデルは初回の生成で読み込み、[release] で解放する。
 */
interface OnDeviceLlmEngine {
    /** 使えるモデルが入っていて、読み込みに失敗していないか。 */
    fun isModelReady(): Boolean

    /** [system] と [prompt] から 1 回生成して全文を返す。失敗は例外。 */
    suspend fun generate(
        system: String,
        prompt: String,
    ): String

    /** メモリを空ける（背景に入るとき）。 */
    fun release()
}
