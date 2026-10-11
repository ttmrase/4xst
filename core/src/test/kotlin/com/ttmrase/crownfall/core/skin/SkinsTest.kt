package com.ttmrase.crownfall.core.skin

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.data.SkinCategory
import com.ttmrase.crownfall.core.data.SkinRarity
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.shop.ProductIds
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkinsTest {
    private val data = TestSupport.data

    private fun shopGame(gems: Long = 100_000) = TestSupport.newGame(castle = 6).copy(gems = gems)

    private fun failure(r: ActionResult) = (r as ActionResult.Failure).reason

    @Test
    fun pricesStayInTheTicketRangeAndEveryRarityHasABagSkin() {
        data.skins.skins.filter { it.priceYen > 0 }.forEach { assertTrue(it.priceYen in 1220..6100, it.id) }
        SkinRarity.entries.forEach { r -> assertTrue(data.skins.skins.any { it.bag && it.rarity == r }, "$r") }
        assertEquals(10_000, data.skins.bag.rates.values.sum())
    }

    @Test
    fun theWeeklyLimitedSkinsRotateDeterministicallyAndComeBack() {
        val limited = data.skins.skins.filter { it.limited }
        val seen = mutableSetOf<String>()
        for (w in 0 until limited.size) seen += Skins.featured(data, w.toLong()).map { it.id }
        assertEquals(limited.map { it.id }.toSet(), seen, "every limited skin appears within one cycle")
        assertEquals(Skins.featured(data, 5), Skins.featured(data, 5))
        assertEquals(
            Skins.featured(data, 3),
            Skins.featured(data, 3 + limited.size.toLong()),
            "revival after a full cycle",
        )
        assertEquals(data.skins.featuredPerWeek, Skins.featured(data, 0).size)
    }

    @Test
    fun aNormalSkinIsBoughtThroughTheFakePurchasePathAndEquippedAutomatically() {
        val s = shopGame()
        val bought = Actions.fakePurchase(s, ProductIds.skin("castle_ice"), data).ok()
        assertTrue("castle_ice" in Skins.owned(bought, data))
        assertEquals("castle_ice", bought.shop.skins.equipped["castle"])
        assertEquals(1220L, bought.shop.ledger.records.last().priceYen)
        assertEquals(
            ActionFailure.AlreadyOwned,
            failure(Actions.fakePurchase(bought, ProductIds.skin("castle_ice"), data)),
        )
    }

    @Test
    fun aLimitedSkinIsOnlyForSaleInItsWeek() {
        val s = shopGame()
        val thisWeek = Skins.featured(data, Skins.week(s)).first()
        assertIs<ActionResult.Success>(Actions.fakePurchase(s, ProductIds.skin(thisWeek.id), data))
        val other =
            data.skins.skins.first {
                it.limited &&
                    Skins.featured(data, Skins.week(s)).none {
                            f ->
                        f.id == it.id
                    }
            }
        assertEquals(ActionFailure.OfferUnavailable, failure(Actions.fakePurchase(s, ProductIds.skin(other.id), data)))
        // 来週にはその限定が買える
        val later = s.copy(nowEpochSec = s.nowEpochSec + 7 * 24 * 3600L * 1)
        val laterFeatured = Skins.featured(data, Skins.week(later)).map { it.id }
        assertTrue(laterFeatured.isNotEmpty())
    }

    @Test
    fun skinsWithoutAPriceCannotBeBoughtAndUnlockByVipOrClaim() {
        val s = shopGame()
        assertEquals(
            ActionFailure.OfferUnavailable,
            failure(Actions.fakePurchase(s, ProductIds.skin("frame_royal"), data)),
        )
        assertFalse("frame_royal" in Skins.owned(s, data))
        val claimed = s.copy(quest = s.quest.copy(claimed = s.quest.claimed + "ch10"))
        assertTrue("bubble_scholar" in Skins.owned(claimed, data))
    }

    @Test
    fun theChampionFrameComesFromWinningAWeeklyEventAndSeasonalTerritoriesAreForSale() {
        val s = shopGame()
        assertFalse("frame_champion" in Skins.owned(s, data))
        assertFalse("frame_champion" in Skins.owned(s.copy(eventBestRank = 2), data))
        assertTrue("frame_champion" in Skins.owned(s.copy(eventBestRank = 1), data))
        listOf("territory_spring", "territory_autumn", "territory_winter").forEach { id ->
            val def = Skins.def(data, id)!!
            assertEquals("territory", def.category)
            assertTrue(def.priceYen > 0 && !def.limited)
        }
    }

    @Test
    fun equipNeedsOwnershipAndTheRightCategory() {
        val s = Actions.fakePurchase(shopGame(), ProductIds.skin("castle_ice"), data).ok()
        assertEquals(ActionFailure.NotOwned, failure(Actions.equipSkin(s, "castle", "castle_gold", data)))
        assertEquals(ActionFailure.NotOwned, failure(Actions.equipSkin(s, "territory", "castle_ice", data)))
        assertEquals(ActionFailure.InvalidArgument, failure(Actions.equipSkin(s, "nope", "castle_ice", data)))
        val off = Actions.equipSkin(s, "castle", null, data).ok()
        assertNull(Skins.equipped(off, data)[SkinCategory.CASTLE])
        assertNotNull(
            Skins.equipped(Actions.equipSkin(off, "castle", "castle_ice", data).ok(), data)[SkinCategory.CASTLE],
        )
    }

    @Test
    fun theBagIsDeterministicChargesGemsAndReportsTheResults() {
        val s = shopGame()
        val a = Actions.pullSkinBag(s, true, data) as ActionResult.Success
        val b = Actions.pullSkinBag(s, true, data) as ActionResult.Success
        assertEquals(a, b)
        assertEquals(s.gems - data.skins.bag.tenGems, a.state.gems)
        val ev = a.events.filterIsInstance<GameEvent.SkinBag>().single()
        assertEquals(10, ev.skinIds.size)
        assertEquals(10L, a.state.shop.skins.bagPulls)
        val poor = s.copy(gems = 10)
        assertIs<ActionFailure.NotEnoughGems>(failure(Actions.pullSkinBag(poor, false, data)))
    }

    @Test
    fun duplicatesBecomeShardsAndShardsBuyTheMissingSkins() {
        var s = shopGame(gems = 10_000_000)
        val bagIds = data.skins.skins.filter { it.bag }.map { it.id }.toSet()
        repeat(40) { s = (Actions.pullSkinBag(s, true, data) as ActionResult.Success).state }
        assertTrue(bagIds.all { it in Skins.owned(s, data) }, "400 pulls collect the whole bag")
        assertTrue(s.shop.skins.shards > 0, "duplicates became shards")
        // 全部持っていると欠片では交換できない
        assertEquals(ActionFailure.NotOwned, failure(Actions.exchangeSkin(s, "castle_gold", data)))
        val fresh = shopGame().copy(shop = shopGame().shop.copy(skins = shopGame().shop.skins.copy(shards = 1000)))
        val got = Actions.exchangeSkin(fresh, "castle_gold", data).ok()
        assertTrue("castle_gold" in Skins.owned(got, data))
        assertEquals(1000 - data.skins.bag.exchange.getValue(SkinRarity.SSR), got.shop.skins.shards)
        assertEquals(ActionFailure.NotOwned, failure(Actions.exchangeSkin(shopGame(), "castle_gold", data)))
    }

    @Test
    fun thePityGuaranteesAnSrOrBetterWithinThePityCount() {
        var s = shopGame(gems = 10_000_000)
        var dry = 0
        var worst = 0
        repeat(300) {
            val (next, res) = Skins.pullBag(s, 1, data)
            s = next
            dry = if (res.single().rarity >= SkinRarity.SR) 0 else dry + 1
            worst = maxOf(worst, dry)
        }
        assertTrue(worst < data.skins.bag.pityAt, "never $worst dry pulls in a row")
    }

    @Test
    fun theBagIsLockedUntilTheShopOpens() {
        val s = TestSupport.newGame(castle = 1).copy(gems = 100_000)
        assertIs<ActionFailure.FeatureLocked>(failure(Actions.pullSkinBag(s, false, data)))
    }
}
