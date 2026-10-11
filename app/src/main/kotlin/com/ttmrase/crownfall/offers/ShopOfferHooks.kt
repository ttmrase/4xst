package com.ttmrase.crownfall.offers

import androidx.annotation.StringRes
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.MonthPass
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.sim.Actions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * [OfferHooks] の本実装。タップされたときだけ呼ばれ（M2 REVIEW の規則）、不足分ちょうどのパックなどを
 * [OfferEngine.request] で呼ぶ。プレイヤーがタップしたものは、自動ポップアップの抑制（間隔・停止・クールダウン・上限）の
 * 対象外で、必ず表示される（02 §17）。表示は OfferPresenter が行う。
 * [openProduct] は決済シートを開く（月パスなど、オファーを通さない商品）。[notify] はスナックバーを出す。
 */
class ShopOfferHooks(
    private val session: GameSession,
    private val data: GameData,
    private val scope: CoroutineScope,
    private val openProduct: (String) -> Unit = {},
    private val notify: (@StringRes Int) -> Unit = {},
) : OfferHooks {
    override fun onResourceShortage(missing: Resources) {
        scope.launch { session.dispatch { Actions.offerForShortage(it, missing, data) } }
    }

    override fun onGemShortage(missing: Long) {
        scope.launch { session.dispatch { Actions.offerForGemShortage(it, missing, data) } }
    }

    /**
     * 2 本目の建設キュー: ビルダーのパックを呼ぶ。今日の在庫が無ければ月パス（期間中は 2 本目ビルダー）の購入へ、
     * それも出せなければ「本日分は売り切れです」。何も起きない、は無い。
     */
    override fun onSecondBuilderLocked() {
        scope.launch {
            val s = session.state.value ?: return@launch
            when {
                OfferEngine.canRequestSecondBuilder(s, data) ->
                    session.dispatch { Actions.offerForSecondBuilder(it, data) }
                !MonthPass.active(s) -> openProduct(data.shopRules.monthPass.sku)
                else -> notify(R.string.offer_sold_out_today)
            }
        }
    }
}
