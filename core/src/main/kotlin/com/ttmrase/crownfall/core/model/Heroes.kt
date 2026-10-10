package com.ttmrase.crownfall.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 英雄のレアリティ。 */
@Serializable
enum class HeroRarity {
    @SerialName("N")
    N,

    @SerialName("R")
    R,

    @SerialName("SR")
    SR,

    @SerialName("SSR")
    SSR,
}

/** 所持している英雄。[exp] は現在レベル内の経験値、[skillLevels] はスキル 3 つの Lv。 */
@Serializable
data class HeroState(
    val heroId: String,
    val level: Int = 1,
    val exp: Long = 0,
    val star: Int = 1,
    val skillLevels: List<Int> = listOf(1, 1, 1),
)

/** 召喚 1 回分の結果。[shards] は重複時に欠片へ変換した量（新規なら 0）。 */
data class SummonEntry(val heroId: String, val rarity: HeroRarity, val isNew: Boolean, val shards: Int)

/** 召喚の結果。[pityAfter] は天井カウンタ（SSR が出ないまま続けた回数）。 */
data class SummonResult(val results: List<SummonEntry>, val pityAfter: Int)
