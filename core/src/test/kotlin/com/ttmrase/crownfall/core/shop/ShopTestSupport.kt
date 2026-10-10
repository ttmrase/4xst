package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.sim.Simulation

/** ショップのテスト共通: 現地正午（UTC+0）の城 Lv5 の状態。 */
internal object ShopTestSupport {
    const val HOUR = 3600L
    const val DAY = 86_400L
    val data = TestSupport.data
    const val DAY0 = 20_000L

    fun base(
        freq: OfferFrequency = OfferFrequency.MASS,
        utc: Int = 0,
    ): GameState {
        var s = TestSupport.advanced(level = 5, castle = 5, now = DAY0 * DAY + 12 * HOUR)
        s = s.copy(rngState = 7, resources = TestSupport.RICH, gems = 0)
        s = s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
        s = s.copy(settings = s.settings.copy(utcOffsetSec = utc))
        s = s.copy(shop = s.shop.copy(settings = s.shop.settings.copy(frequency = freq)))
        return Simulation.tick(s, 0, data).state
    }

    fun at(
        s: GameState,
        sec: Long,
    ): GameState = s.copy(nowEpochSec = sec)

    fun skus(s: GameState): List<String> = OfferEngine.active(s).map { it.sku }
}
