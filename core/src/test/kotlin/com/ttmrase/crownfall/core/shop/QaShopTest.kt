package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.shop.ShopTestSupport.DAY
import com.ttmrase.crownfall.core.shop.ShopTestSupport.HOUR
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による M6 の境界テスト（二重購入、期限、頻度の上限、R3、不在からの復帰）。 */
class QaShopTest {
    private val data = ShopTestSupport.data

    private fun base(freq: OfferFrequency = OfferFrequency.MASS) = ShopTestSupport.base(freq)

    // ───── 二重購入・期限 ─────

    @Test
    fun aQueuedOfferCanBeBoughtOnlyOnce() {
        var s = Actions.offerForGemShortage(base(), 80, data).ok()
        val offer = OfferEngine.active(s).single()
        val id = ProductIds.offer(offer.id)
        s = Actions.fakePurchase(s, id, data).ok()
        assertTrue(OfferEngine.active(s).none { it.id == offer.id })
        assertEquals(ActionFailure.OfferUnavailable, Actions.fakePurchase(s, id, data).failure())
        // 二度目の失敗は何も変えない
        assertEquals(s.shop.purchaseCount, 1L)
        assertEquals(1L, s.shop.ledger.count)
    }

    @Test
    fun anOfferIsBuyableUntilTheSecondBeforeItExpires() {
        val s = Actions.offerForGemShortage(base(), 80, data).ok()
        val offer = OfferEngine.active(s).single()
        val id = ProductIds.offer(offer.id)
        val last = ShopTestSupport.at(s, offer.expiresEpochSec - 1)
        Actions.fakePurchase(last, id, data).ok()
        val expired = ShopTestSupport.at(s, offer.expiresEpochSec)
        assertEquals(ActionFailure.OfferUnavailable, Actions.fakePurchase(expired, id, data).failure())
    }

    @Test
    fun everyShelfProductIsAtomicEvenWhenBoughtTwiceInARow() {
        var s = base()
        val ids =
            data.offers.gemSkus.map { it.sku } +
                listOf(data.shopRules.wheel.extraSku, data.shopRules.monthPass.sku, data.shopRules.growthFund.sku)
        var expectedCount = 0L
        for (id in ids) {
            val r = Actions.fakePurchase(s, id, data)
            if (r is com.ttmrase.crownfall.core.sim.ActionResult.Success) {
                val after = r.state
                expectedCount++
                assertEquals(expectedCount, after.shop.purchaseCount, id)
                assertEquals(expectedCount, after.shop.ledger.count, id)
                assertEquals(1, after.mails.size - s.mails.size, "$id exactly one receipt")
                assertTrue(r.events.first() is GameEvent.Purchased, "$id Purchased event comes first")
                s = after
            }
        }
        assertTrue(expectedCount >= ids.size - 1, "most shelf products must be purchasable")
    }

    @Test
    fun orderNumbersUseTheOwnFormatAndNeverRepeat() {
        var s = base()
        val seen = HashSet<String>()
        val sku = data.offers.gemSkus.first().sku
        repeat(600) {
            val r = Actions.fakePurchase(s, sku, data) as com.ttmrase.crownfall.core.sim.ActionResult.Success
            val p = r.events.filterIsInstance<GameEvent.Purchased>().single()
            val order = FakePurchase.orderNumber(p.orderA, p.orderB)
            assertTrue(Regex("^ORD-[0-9A-Z]{4}-[0-9A-Z]{4}$").matches(order), order)
            assertTrue(seen.add(order), "duplicate order number $order")
            s = r.state
        }
        assertEquals(600L, s.shop.ledger.count)
        assertEquals(0L, FakeLedger.ACTUAL_PAYMENT_YEN)
    }

    @Test
    fun ledgerKeepsRecordsOldestFirstAndTotalsMatchTheSumOfPrices() {
        var s = base()
        var yen = 0L
        for (g in data.offers.gemSkus + data.offers.gemSkus) {
            s = ShopTestSupport.at(s, s.nowEpochSec + 90)
            s = Actions.fakePurchase(s, g.sku, data).ok()
            yen += g.priceYen
        }
        val l = s.shop.ledger
        assertEquals(yen, l.totalYen)
        assertEquals(l.records.map { it.epochSec }, l.records.map { it.epochSec }.sorted(), "oldest first")
        assertEquals(s.nowEpochSec, l.records.last().epochSec)
    }

    // ───── 頻度の上限 ─────

    @Test
    fun randomTriggerStormNeverBreaksCapsCooldownOrUniqueness() {
        for (freq in OfferFrequency.entries) {
            val rnd = java.util.Random(freq.ordinal + 5L)
            var s = Actions.beginSession(base(freq), data).ok()
            val cap = OfferEngine.sessionCap(freq, data)
            val sessionStart = s.shop.sessionOffers
            repeat(300) {
                s = ShopTestSupport.at(s, s.nowEpochSec + rnd.nextInt(600))
                val before = s.shop.sessionOffers
                s =
                    when (rnd.nextInt(6)) {
                        0 -> OfferEngine.onLongTimer(s, (rnd.nextInt(30) * HOUR), data)
                        1 -> {
                            val missing = Resources(rnd.nextInt(90_000).toLong() + 1, 0, 0, 0)
                            Actions.offerForShortage(s, missing, data).ok()
                        }
                        2 -> Actions.offerForGemShortage(s, rnd.nextInt(5000).toLong() + 1, data).ok()
                        3 -> Actions.exitOffer(s, data).ok()
                        4 -> Actions.offerForRanking(s, 2 + rnd.nextInt(3), rnd.nextInt(5000).toLong(), data).ok()
                        else -> Actions.offerForSecondBuilder(s, data).ok()
                    }
                val active = OfferEngine.active(s)
                assertEquals(active.size, active.map { it.sku }.toSet().size, "$freq: duplicate active SKU")
                assertTrue(s.shop.offerQueue.size <= data.shopRules.frequency.queueCap, "$freq queue cap")
                assertTrue(s.shop.sessionOffers >= before, "$freq counter never goes backwards")
                if (cap != Int.MAX_VALUE) {
                    assertTrue(
                        s.shop.sessionOffers <= cap,
                        "$freq sessionOffers=${s.shop.sessionOffers}",
                    )
                }
            }
            assertTrue(s.shop.sessionOffers >= sessionStart)
        }
    }

    // ───── R3 と不在からの復帰 ─────

    private fun busy(): GameState {
        var s = Actions.beginSession(base(), data).ok()
        s = TestSupport.withLevel(s, BuildingType.WATCHTOWER, 1)
        s = s.copy(resources = TestSupport.RICH, gems = 500)
        s = Actions.grantSecondBuilder(s, 3 * DAY).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.CASTLE), data).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.FARM), data).ok()
        // 軍がいないので襲撃に負け、敗北のオファーが出る
        return s.copy(map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + 2 * HOUR, raidWarned = false))
    }

    @Test
    fun shopHooksKeepTickSplitInvariant() {
        val s = busy()
        SplitInvariance.assertInvariant(s, 2 * DAY, data, java.util.Random(31), randomCases = 40)
        val whole = Simulation.tick(s, 2 * DAY, data)
        assertTrue(whole.state.shop.lastShownEpochSec.isNotEmpty(), "scenario must produce offers")
    }

    @Test
    fun aDayOfCatchUpEqualsOneBigTickIncludingOffersAndPiggy() {
        val s = busy()
        val whole = Simulation.tick(s, DAY, data)
        val caught = OfflineCatchUp.run(s, s.nowEpochSec + DAY, data)
        assertEquals(whole.state, caught.state)
        assertEquals(whole.events, caught.events)
    }

    @Test
    fun longAbsenceShiftsTheMonthPassAndQueuesTheComebackPackOnlyAfterTheThreshold() {
        val s0 = base()
        val pass = Actions.fakePurchase(s0, data.shopRules.monthPass.sku, data).ok()
        val until = pass.shop.monthPassUntilEpochSec
        assertTrue(until > pass.nowEpochSec)
        val cap = data.rules.offlineCapSec
        val long = OfflineCatchUp.run(pass, pass.nowEpochSec + 5 * DAY, data)
        // 上限を超えた不在分は月パスの残りを減らさない
        assertEquals(until - pass.nowEpochSec - cap, long.state.shop.monthPassUntilEpochSec - long.state.nowEpochSec)
        assertTrue(OfferEngine.active(long.state).any { it.trigger == "comeback" }, "comeback pack after 5 days")
        val short = OfflineCatchUp.run(pass, pass.nowEpochSec + 6 * HOUR, data)
        assertTrue(OfferEngine.active(short.state).none { it.trigger == "comeback" }, "no comeback pack after 6h")
    }
}
