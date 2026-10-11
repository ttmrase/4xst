package com.ttmrase.crownfall.ai.providers

/**
 * 端末内 LLM のプロンプトと後処理の 2 段のプロファイル。
 * [STANDARD] は Gemma 3n E2B / E4B クラス向け（自然な文章のシステム指示、履歴 8〜10 行、1〜3 文）。
 * [TINY] は 1B / 270m クラス向け（短い指示、1 人 1 回、1〜2 文）。
 * サンプラーとトークン上限もプロファイルごとに持つ（:app のエンジンが使う）。
 */
enum class OnDeviceProfile(
    val id: String,
    val maxSentences: Int,
    val maxChars: Int,
    val temperature: Double,
    val topK: Int,
    val topP: Double,
    val maxTokens: Int,
) {
    STANDARD("standard", 3, 160, 0.9, 40, 0.95, 96),
    TINY("tiny", 2, 120, 0.85, 40, 0.95, 64),
    ;

    companion object {
        const val AUTO = "auto"
        private val ONE_B = Regex("(?<![0-9a-z])1b(?![0-9a-z])")

        /** モデルのファイル名から判定する（大文字小文字は無視）。e2b / e4b → 標準、1b / 270m → 小型、判定できなければ標準。 */
        fun detect(modelName: String?): OnDeviceProfile {
            val n = modelName.orEmpty().lowercase()
            return when {
                "e2b" in n || "e4b" in n -> STANDARD
                "270m" in n || ONE_B.containsMatchIn(n) -> TINY
                else -> STANDARD
            }
        }

        /** 設定の値（`auto` / `standard` / `tiny`）とモデルのファイル名からプロファイルを決める。 */
        fun resolve(
            setting: String,
            modelName: String?,
        ): OnDeviceProfile = entries.firstOrNull { it.id == setting } ?: detect(modelName)
    }
}
