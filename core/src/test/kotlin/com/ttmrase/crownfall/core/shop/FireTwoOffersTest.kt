package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.shop.ShopTestSupport.base
import kotlin.test.Test
import kotlin.test.assertTrue

/** :app のテストが公開 API だけで 2 つのオファーを積めること（defeated と castleUp のトリガー）。 */
class FireTwoOffersTest {
    @Test
    fun defeatedAndCastleUpTriggersQueueAtLeastTwoOffers() {
        val data = ShopTestSupport.data
        val s = OfferEngine.fire(base(), "defeated", TriggerCtx(), data)
        val t = OfferEngine.fire(s, "castleUp", TriggerCtx(lv = 3, params = mapOf("lv" to 3L)), data)
        assertTrue(OfferEngine.active(t).size >= 2, OfferEngine.active(t).map { it.sku }.toString())
    }
}
