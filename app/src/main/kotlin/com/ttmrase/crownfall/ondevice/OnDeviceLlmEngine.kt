package com.ttmrase.crownfall.ondevice

import com.ttmrase.crownfall.ai.providers.OnDeviceProfile

/**
 * 端末内 LLM の実行エンジン（ランタイムはこのインターフェースの後ろに閉じ込める。LiteRT-LM → tasks-genai に
 * 差し替えられる）。呼び出しは 1 件ずつ。モデルは初回の生成で読み込み、[release] で解放する。
 */
interface OnDeviceLlmEngine {
    /** 使えるモデルが入っていて、読み込みに失敗していないか。 */
    fun isModelReady(): Boolean

    /** [system] と [prompt] から 1 回生成して全文を返す。サンプラーは [profile] のもの。失敗は例外。 */
    suspend fun generate(
        system: String,
        prompt: String,
        profile: OnDeviceProfile = OnDeviceProfile.STANDARD,
    ): String

    /** 先にモデルを読み込んでおく（チャットを開いたとき）。何もできないときは黙って戻る。 */
    fun warmUp()

    /** いまのモデルの GPU が、例外やクラッシュで使えなくなっているか（CPU で動作中）。 */
    val gpuFailed: Boolean

    /** GPU が落ちた記録を消して、次の読み込みでもう一度 GPU を試す。 */
    fun retryGpu()

    /** いま読み込まれているバックエンド（`cpu` / `gpu`。まだ読み込んでいなければ空）。 */
    val activeBackend: String

    /** メモリを空ける（背景に入るとき）。 */
    fun release()
}
