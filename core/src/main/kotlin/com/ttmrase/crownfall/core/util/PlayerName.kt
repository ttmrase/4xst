package com.ttmrase.crownfall.core.util

/** プレイヤー名の検証結果。 */
sealed interface PlayerNameResult {
    /** 有効。[name] は前後の空白を除去済み。 */
    data class Valid(val name: String) : PlayerNameResult

    /** 空（空白のみを含む）。 */
    data object Empty : PlayerNameResult

    /** [MAX_LENGTH] 文字超過。 */
    data object TooLong : PlayerNameResult

    /** 制御文字を含む。 */
    data object InvalidChars : PlayerNameResult
}

/** プレイヤー名（1〜12 文字、前後の空白は除去）。 */
object PlayerName {
    const val MAX_LENGTH = 12

    /** [raw] を検証する。長さは Unicode コードポイント数で数える。 */
    fun validate(raw: String): PlayerNameResult {
        val name = raw.trim()
        if (name.isEmpty()) return PlayerNameResult.Empty
        if (name.any { it.isISOControl() }) return PlayerNameResult.InvalidChars
        if (name.codePointCount(0, name.length) > MAX_LENGTH) return PlayerNameResult.TooLong
        return PlayerNameResult.Valid(name)
    }
}
