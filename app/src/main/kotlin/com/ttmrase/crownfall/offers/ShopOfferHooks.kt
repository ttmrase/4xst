package com.ttmrase.crownfall.offers

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [OfferHooks] の本実装。タップされたときだけ呼ばれ（M2 REVIEW の規則）、不足分ちょうどのパックなどを
 * オファーのキューに積む。表示は OfferPresenter が行う。
 */
class ShopOfferHooks(
    private val session: GameSession,
    private val data: GameData,
    private val scope: CoroutineScope,
) : OfferHooks {
    override fun onResourceShortage(missing: Resources) {
        scope.launch { session.dispatch { Actions.offerForShortage(it, missing, data) } }
    }

    override fun onGemShortage(missing: Long) {
        scope.launch { session.dispatch { Actions.offerForGemShortage(it, missing, data) } }
    }

    override fun onSecondBuilderLocked() {
        scope.launch { session.dispatch { Actions.offerForSecondBuilder(it, data) } }
    }
}
