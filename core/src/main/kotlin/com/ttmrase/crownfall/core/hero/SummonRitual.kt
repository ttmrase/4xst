package com.ttmrase.crownfall.core.hero

import com.ttmrase.crownfall.core.data.SummonPresentation
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.SummonEntry
import com.ttmrase.crownfall.core.model.SummonResult

/** 召喚の宝珠が順に光る色。 */
enum class OrbColor { BLUE, PURPLE, GOLD }

/** 演出の種類。 */
enum class RitualKind {
    /** 結果どおりの色だけを見せる。 */
    PLAIN,

    /** 紫→金に昇格する（SSR のときだけ）。 */
    PROMOTION,

    /** 金色の火花が散るが結果は SR（期待させる見せ方。結果には影響しない）。 */
    NEAR_MISS,
}

/**
 * 召喚演出の台本。[flashes] は宝珠が順に光る色、[top] は最終結果の最高レアリティ。
 * 演出は召喚結果が確定した後に決まり、確率・天井・乱数には一切関与しない。
 */
data class RitualPlan(
    val top: HeroRarity,
    val kind: RitualKind,
    val flashes: List<OrbColor>,
)

/** 結果だけから決定的に演出を導く純粋関数。 */
object SummonRitual {
    private const val PERCENT = 100

    /**
     * 結果 [result] の演出台本。同じ結果・比率・[salt] なら常に同じ台本になる。
     * [salt] は召喚ごとに変わる値（召喚後の rngState など）で、英雄ごとに演出が固定されて先読みされるのを防ぐ。
     */
    fun plan(
        result: SummonResult,
        presentation: SummonPresentation,
        salt: Long = 0L,
    ): RitualPlan {
        val top = result.results.maxOfOrNull { it.rarity } ?: HeroRarity.N
        val roll = roll(result, salt)
        return when (top) {
            HeroRarity.SSR ->
                if (roll < presentation.promotionPct) {
                    RitualPlan(top, RitualKind.PROMOTION, listOf(OrbColor.BLUE, OrbColor.PURPLE, OrbColor.GOLD))
                } else {
                    RitualPlan(top, RitualKind.PLAIN, listOf(OrbColor.BLUE, OrbColor.GOLD))
                }
            HeroRarity.SR ->
                if (roll < presentation.nearMissPct) {
                    RitualPlan(top, RitualKind.NEAR_MISS, listOf(OrbColor.BLUE, OrbColor.PURPLE, OrbColor.GOLD))
                } else {
                    RitualPlan(top, RitualKind.PLAIN, listOf(OrbColor.BLUE, OrbColor.PURPLE))
                }
            else -> RitualPlan(top, RitualKind.PLAIN, listOf(OrbColor.BLUE))
        }
    }

    /** 1 枚ごとのカード枠色の序列（演出用）。 */
    fun frameOf(entry: SummonEntry): OrbColor =
        when (entry.rarity) {
            HeroRarity.SSR -> OrbColor.GOLD
            HeroRarity.SR -> OrbColor.PURPLE
            else -> OrbColor.BLUE
        }

    /** 0..99。英雄 ID の並びと天井カウンタから作る安定ハッシュ（乱数状態は触らない）。 */
    private fun roll(
        result: SummonResult,
        salt: Long,
    ): Int {
        var h = 17 + (salt xor (salt ushr 32)).toInt()
        for (e in result.results) h = h * 31 + e.heroId.hashCode()
        h = h * 31 + result.pityAfter
        return Math.floorMod(h, PERCENT)
    }
}
