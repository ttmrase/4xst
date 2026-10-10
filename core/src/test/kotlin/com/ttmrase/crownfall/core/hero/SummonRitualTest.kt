package com.ttmrase.crownfall.core.hero

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.data.SummonPresentation
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.SummonEntry
import com.ttmrase.crownfall.core.model.SummonResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SummonRitualTest {
    private fun result(
        vararg rarities: HeroRarity,
        seed: Int = 0,
    ) = SummonResult(rarities.mapIndexed { i, r -> SummonEntry("h$seed$i", r, true, 0) }, seed)

    private val always = SummonPresentation(100, 100)
    private val never = SummonPresentation(0, 0)

    @Test
    fun dataHasPresentationRatios() {
        val p = TestSupport.data.heroRules.presentation
        assertTrue(p.promotionPct in 1..100 && p.nearMissPct in 1..100)
    }

    @Test
    fun lowRarityIsAlwaysBlueOnly() {
        val plan = SummonRitual.plan(result(HeroRarity.N, HeroRarity.R), always)
        assertEquals(RitualKind.PLAIN, plan.kind)
        assertEquals(listOf(OrbColor.BLUE), plan.flashes)
    }

    @Test
    fun ssrPromotionOnlyForSsr() {
        val ssr = SummonRitual.plan(result(HeroRarity.SSR), always)
        assertEquals(RitualKind.PROMOTION, ssr.kind)
        assertEquals(OrbColor.GOLD, ssr.flashes.last())
        assertEquals(RitualKind.PLAIN, SummonRitual.plan(result(HeroRarity.SSR), never).kind)
    }

    @Test
    fun nearMissNeverAppliesToSsrOrLower() {
        val sr = SummonRitual.plan(result(HeroRarity.SR), always)
        assertEquals(RitualKind.NEAR_MISS, sr.kind)
        assertEquals(HeroRarity.SR, sr.top)
        assertEquals(RitualKind.PLAIN, SummonRitual.plan(result(HeroRarity.R), always).kind)
        assertEquals(RitualKind.PLAIN, SummonRitual.plan(result(HeroRarity.SR), never).kind)
    }

    @Test
    fun deterministicAndRatioRoughlyHonoured() {
        val p = SummonPresentation(0, 50)
        val a = SummonRitual.plan(result(HeroRarity.SR, seed = 7), p)
        assertEquals(a, SummonRitual.plan(result(HeroRarity.SR, seed = 7), p))
        val hits =
            (0 until 400).count {
                SummonRitual.plan(result(HeroRarity.SR, seed = it), p).kind == RitualKind.NEAR_MISS
            }
        assertTrue(hits in 120..280, "hits=$hits")
    }

    @Test
    fun theSameHeroDoesNotAlwaysGetTheSameRitualWhenTheSaltChanges() {
        val p = SummonPresentation(50, 50)
        val one = result(HeroRarity.SSR, seed = 3)
        val kinds = (0L until 200L).map { SummonRitual.plan(one, p, salt = it).kind }.toSet()
        assertEquals(setOf(RitualKind.PROMOTION, RitualKind.PLAIN), kinds)
    }
}
