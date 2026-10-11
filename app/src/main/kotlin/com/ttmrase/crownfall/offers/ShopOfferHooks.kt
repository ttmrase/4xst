package com.ttmrase.crownfall.offers

import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.MonthPass
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
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
    private val notify: (Int) -> Unit = {},
    private val notifyLocked: (Int) -> Unit = {},
) : OfferHooks {
    /** ショップが未解放（城 Lv3 未満）なら、無反応にせず「城 Lv n で解放」を知らせる。 */
    private fun reportLocked(result: ActionResult) {
        val reason = (result as? ActionResult.Failure)?.reason
        if (reason is ActionFailure.FeatureLocked) notifyLocked(reason.requiredLv)
    }

    override fun onResourceShortage(missing: Resources) {
        scope.launch { reportLocked(session.dispatch { Actions.offerForShortage(it, missing, data) }) }
    }

    override fun onGemShortage(missing: Long) {
        scope.launch { reportLocked(session.dispatch { Actions.offerForGemShortage(it, missing, data) }) }
    }

    /**
     * 2 本目の建設キュー: ビルダーのパックを呼ぶ。今日の在庫が無ければ月パス（期間中は 2 本目ビルダー）の購入へ、
     * それも出せなければ「本日分は売り切れです」。何も起きない、は無い。
     */
    override fun onSecondBuilderLocked() {
        scope.launch {
            val s = session.state.value ?: return@launch
            when {
                !FeatureGate.unlocked(s, data, Feature.SHOP) ->
                    notifyLocked(FeatureGate.requiredLv(data, Feature.SHOP))
                OfferEngine.canRequestSecondBuilder(s, data) ->
                    reportLocked(session.dispatch { Actions.offerForSecondBuilder(it, data) })
                !MonthPass.active(s) -> openProduct(data.shopRules.monthPass.sku)
                else -> notify(R.string.offer_sold_out_today)
            }
        }
    }
}
