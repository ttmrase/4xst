package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.shop.ShopTestSupport.base
import com.ttmrase.crownfall.core.sim.Actions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** タップして呼んだオファー（request）は、自動ポップアップの抑制を受けず必ず表示される（1.1.1 のホットフィックス）。 */
class OfferRequestTest {
    private val data = ShopTestSupport.data
    private val builderSku get() = data.offers.packs.first { it.contents.secondBuilderDays > 0 }.id

    /** ログインのオファーが出て、「戻る」で閉じられ、自動ポップアップがセッション中は止まっている状態。 */
    private fun stoppedByBack(): GameState {
        val s = OfferEngine.beginSession(base(), data)
        val first = checkNotNull(OfferEngine.nextPopup(s, data))
        val back = OfferEngine.dismiss(s, first.id, byBack = true)
        assertTrue(back.shop.popupsStopped)
        assertNull(OfferEngine.nextPopup(back, data), "auto popups are stopped")
        return back
    }

    @Test
    fun aRequestedOfferIsReturnedEvenWhenAutoPopupsAreStoppedAndInsideTheGap() {
        val back = stoppedByBack()
        assertEquals(back.nowEpochSec, back.shop.lastPopupClosedEpochSec, "still inside the gap")
        val r = Actions.offerForSecondBuilder(back, data).ok()
        val popup = assertNotNull(OfferEngine.nextPopup(r, data))
        assertEquals(builderSku, popup.sku)
        assertEquals(r.shop.requestedOfferId, popup.id)
    }

    @Test
    fun aClosedOfferOfTheSameSkuIsShownAgainWhenRequested() {
        val s1 = Actions.offerForSecondBuilder(base(), data).ok()
        val o1 = OfferEngine.active(s1).single()
        val closed = Actions.dismissOffer(s1, o1.id).ok()
        assertNull(OfferEngine.next(closed))
        val again = Actions.offerForSecondBuilder(closed, data).ok()
        val o2 = OfferEngine.active(again).single()
        assertEquals(o1.id, o2.id)
        assertFalse(o2.dismissed)
        assertEquals(o2.id, assertNotNull(OfferEngine.nextPopup(again, data)).id)
    }

    @Test
    fun theRequestIgnoresCooldownAndTheSessionCapAndDoesNotCountAsASessionOffer() {
        var s = base(OfferFrequency.LOW)
        val cap = OfferEngine.sessionCap(OfferFrequency.LOW, data)
        s =
            s.copy(
                shop =
                    s.shop.copy(
                        sessionOffers = cap,
                        lastShownEpochSec = s.shop.lastShownEpochSec + (builderSku to s.nowEpochSec),
                    ),
            )
        val r = Actions.offerForSecondBuilder(s, data).ok()
        assertEquals(builderSku, OfferEngine.active(r).single().sku)
        assertEquals(cap, r.shop.sessionOffers)
        assertNotNull(OfferEngine.nextPopup(r, data))
    }

    @Test
    fun closingARequestedOfferWithBackDoesNotStopTheAutoPopups() {
        val s0 = base()
        val r = Actions.offerForSecondBuilder(s0, data).ok()
        val id = checkNotNull(r.shop.requestedOfferId)
        val back = Actions.dismissOffer(r, id, byBack = true).ok()
        assertFalse(back.shop.popupsStopped)
        assertNull(back.shop.requestedOfferId)
        assertNull(OfferEngine.requested(back))
        // 要求されていないものを「戻る」で閉じたときは、これまでどおり止まる
        val login = OfferEngine.beginSession(base(), data)
        val other = checkNotNull(OfferEngine.next(login))
        assertTrue(OfferEngine.dismiss(login, other.id, byBack = true).shop.popupsStopped)
    }

    @Test
    fun theRequestedOfferComesBeforeTheHeadOfTheQueue() {
        val s = OfferEngine.beginSession(base(), data)
        val head = checkNotNull(OfferEngine.next(s))
        val r = Actions.offerForSecondBuilder(s, data).ok()
        val popup = checkNotNull(OfferEngine.nextPopup(r, data))
        assertEquals(builderSku, popup.sku)
        assertTrue(popup.id != head.id)
    }

    @Test
    fun shortageTapsAreRequestsToo() {
        val back = stoppedByBack()
        val res = Actions.offerForShortage(back, Resources(0, 500, 0, 0), data).ok()
        assertEquals(OfferEngine.SHORTAGE_SKU, assertNotNull(OfferEngine.nextPopup(res, data)).sku)
        val gem = Actions.offerForGemShortage(back, 120, data).ok()
        assertEquals("gems_m", assertNotNull(OfferEngine.nextPopup(gem, data)).sku)
    }

    @Test
    fun anExhaustedDailyStockMakesTheBuilderPackUnavailableSoTheCallerCanFallBack() {
        val pack = data.offers.packs.first { it.id == builderSku }
        var s = ShopLogic.rollDay(base())
        assertTrue(OfferEngine.canRequestSecondBuilder(s, data))
        s = s.copy(shop = s.shop.copy(stockUsed = s.shop.stockUsed + (builderSku to pack.dailyStock)))
        assertFalse(OfferEngine.canRequestSecondBuilder(s, data))
        val r = Actions.offerForSecondBuilder(s, data).ok()
        assertTrue(OfferEngine.active(r).isEmpty())
        assertNull(r.shop.requestedOfferId)
    }

    @Test
    fun aNewSessionClearsTheRequest() {
        val r = Actions.offerForSecondBuilder(base(), data).ok()
        assertNotNull(r.shop.requestedOfferId)
        assertNull(OfferEngine.beginSession(r, data).shop.requestedOfferId)
    }
}
