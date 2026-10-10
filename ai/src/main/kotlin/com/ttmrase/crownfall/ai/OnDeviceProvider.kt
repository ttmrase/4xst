package com.ttmrase.crownfall.ai

/**
 * 端末内で動く LLM のプロバイダ（M9 の T7.5 で実装する予定。M7 時点では実装を持たない）。
 * モデルのダウンロード・読み込みが済むまでは [isConfigured] が false を返し、ルーターに飛ばされる。
 */
interface OnDeviceProvider : AiProvider {
    override val id: String
        get() = ID

    /** モデルが端末に入っていて使える状態か。 */
    fun isModelReady(): Boolean

    override fun isConfigured(): Boolean = isModelReady()

    companion object {
        const val ID = "on_device"
    }
}
