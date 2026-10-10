package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.save.DecodeResult
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.save.SaveGame
import com.ttmrase.crownfall.core.shop.ShopTestSupport.at
import com.ttmrase.crownfall.core.sim.Actions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** QA による 1.0.1 の回帰テスト（戻るの原則、02 §17: オファーの自動ポップアップの間隔と、戻るでの停止）。 */
class QaPopupTest {
    private val data = ShopTestSupport.data
    private val gap = data.shopRules.frequency.popupGapSec

    private fun twoOffers() =
        Actions.offerForGemShortage(Actions.offerForGemShortage(ShopTestSupport.base(), 80, data).ok(), 3000, data).ok()

    @Test
    fun theGapIsAtLeastTwentySecondsAndAppliesToTheNextPopupOnly() {
        assertTrue(gap >= 20, "popupGapSec=$gap")
        val s = twoOffers()
        val first = assertNotNull(OfferEngine.nextPopup(s, data), "a fresh offer is shown")
        val closed = Actions.dismissOffer(s, first.id).ok()
        // 閉じた直後は、棚にオファーが残っていても自動ポップアップは出ない
        assertTrue(OfferEngine.active(closed).any { it.id != first.id && !it.dismissed })
        for (dt in listOf(0L, 1L, gap / 2, gap - 1)) {
            assertNull(OfferEngine.nextPopup(at(closed, closed.nowEpochSec + dt), data), "dt=$dt")
        }
        // 間隔ちょうどで次が出る（棚の中身は閉じたものと別）
        val next = OfferEngine.nextPopup(at(closed, closed.nowEpochSec + gap), data)
        assertNotNull(next)
        assertTrue(next.id != first.id)
    }

    @Test
    fun closingWithBackStopsTheRestOfTheSessionButKeepsTheShelfAndTheNextSessionStartsAgain() {
        val s = twoOffers()
        val first = assertNotNull(OfferEngine.nextPopup(s, data))
        val back = Actions.dismissOffer(s, first.id, byBack = true).ok()
        assertTrue(back.shop.popupsStopped)
        // 何時間たっても、そのセッションの自動ポップアップは出ない
        for (dt in listOf(gap, 10 * gap, 3600L, 6 * 3600L)) {
            assertNull(OfferEngine.nextPopup(at(back, back.nowEpochSec + dt), data), "dt=$dt")
        }
        // 棚（active）には残る。買える
        val rest = OfferEngine.active(back).filter { it.id != first.id }
        assertTrue(rest.isNotEmpty())
        Actions.fakePurchase(back, ProductIds.offer(rest.first().id), data).ok()
        // 新しいセッションで再開する
        val again = Actions.beginSession(at(back, back.nowEpochSec + 3600), data).ok()
        assertEquals(false, again.shop.popupsStopped)
        assertEquals(0L, again.shop.lastPopupClosedEpochSec)
    }

    @Test
    fun closingWithTheCrossOnlyDelaysAndNeverStopsThePopups() {
        val s = twoOffers()
        val first = assertNotNull(OfferEngine.nextPopup(s, data))
        val closed = Actions.dismissOffer(s, first.id, byBack = false).ok()
        assertEquals(false, closed.shop.popupsStopped)
        assertEquals(closed.nowEpochSec, closed.shop.lastPopupClosedEpochSec)
        assertNotNull(OfferEngine.nextPopup(at(closed, closed.nowEpochSec + gap), data))
        // 一度戻るで止めた後に × で別のオファーを閉じても、止まったまま（解除されない）
        val stopped = Actions.dismissOffer(s, first.id, byBack = true).ok()
        val other = OfferEngine.active(stopped).first { it.id != first.id }
        assertEquals(true, Actions.dismissOffer(stopped, other.id, byBack = false).ok().shop.popupsStopped)
    }

    @Test
    fun dismissingNeverBreaksTheOfferOrThePurchaseAndTheStateSurvivesASave() {
        val s = twoOffers()
        val first = assertNotNull(OfferEngine.nextPopup(s, data))
        val back = Actions.dismissOffer(s, first.id, byBack = true).ok()
        // 閉じても購入は 1 回だけできる。購入しても止まり方は変わらない
        val bought = Actions.fakePurchase(back, ProductIds.offer(first.id), data).ok()
        assertEquals(1L, bought.shop.purchaseCount)
        assertEquals(true, bought.shop.popupsStopped)
        // セーブに入って戻る（起動後も同じ。ただし起動時の beginSession で解除される）
        val text = SaveCodec.encode(SaveGame(savedAtEpochSec = bought.nowEpochSec, seed = 1, state = bought))
        val loaded = assertIs<DecodeResult.Success>(SaveCodec.decode(text)).save.state
        assertEquals(bought.shop.popupsStopped, loaded.shop.popupsStopped)
        assertEquals(bought.shop.lastPopupClosedEpochSec, loaded.shop.lastPopupClosedEpochSec)
        // 存在しない id を閉じても何も変わらない
        val noop = Actions.dismissOffer(back, 999_999L, byBack = true).ok()
        assertEquals(back.shop.offerQueue, noop.shop.offerQueue)
    }
}
