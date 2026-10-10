package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.ShopSettings
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.shop.FakePurchase
import com.ttmrase.crownfall.core.shop.GrowthFund
import com.ttmrase.crownfall.core.shop.LuckyWheel
import com.ttmrase.crownfall.core.shop.MonthPass
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.ShopLogic
import com.ttmrase.crownfall.core.shop.VipCalculator

/** ショップのアクション（`Actions` から委譲）。 */
internal object ShopActions {
    private fun fail(reason: ActionFailure) = ActionResult.Failure(reason)

    private fun ok(
        state: GameState,
        events: List<GameEvent> = emptyList(),
    ) = ActionResult.Success(state, events)

    /** 新しいセッション（前景になったとき）。ログインポイント（連続日数で 50〜300）と本日限定を処理する。 */
    fun beginSession(
        state: GameState,
        data: GameData,
    ): ActionResult {
        val events = ArrayList<GameEvent>()
        var s = ShopLogic.rollDay(state)
        val day = ShopLogic.localDay(s)
        if (s.shop.lastLoginDay != day) {
            val streak = if (s.shop.lastLoginDay == day - 1) s.shop.loginStreak + 1 else 1
            s = s.copy(shop = s.shop.copy(lastLoginDay = day, loginStreak = streak))
            s = VipCalculator.addPoints(s, VipCalculator.loginPoints(streak, data), data, events)
        }
        s = OfferEngine.beginSession(s, data)
        s = OfferEngine.onVipChanged(s, data)
        return ok(s, events)
    }

    fun refreshOffers(
        state: GameState,
        data: GameData,
    ): ActionResult = ok(OfferEngine.refresh(state, data))

    fun dismissOffer(
        state: GameState,
        offerId: Long,
        byBack: Boolean = false,
    ): ActionResult = ok(OfferEngine.dismiss(state, offerId, byBack))

    fun offerForShortage(
        state: GameState,
        missing: Resources,
        data: GameData,
    ): ActionResult = ok(OfferEngine.onResourceShortage(state, missing, data))

    fun offerForGemShortage(
        state: GameState,
        missing: Long,
        data: GameData,
    ): ActionResult = ok(OfferEngine.onGemShortage(state, missing, data))

    fun offerForSecondBuilder(
        state: GameState,
        data: GameData,
    ): ActionResult = ok(OfferEngine.onSecondBuilderLocked(state, data))

    fun offerForRanking(
        state: GameState,
        rank: Int,
        gapPt: Long,
        data: GameData,
    ): ActionResult = ok(OfferEngine.onRankingNear(state, rank, gapPt, data))

    fun exitOffer(
        state: GameState,
        data: GameData,
    ): ActionResult = ok(OfferEngine.onExit(state, data))

    fun fakePurchase(
        state: GameState,
        offerId: String,
        data: GameData,
    ): ActionResult = FakePurchase.purchase(state, offerId, data)

    fun claimVipChest(
        state: GameState,
        data: GameData,
    ): ActionResult {
        val s = ShopLogic.rollDay(state)
        val day = ShopLogic.localDay(s)
        val chest = VipCalculator.chest(s, data)
        if (chest.isEmpty) return fail(ActionFailure.NothingToClaim)
        if (s.shop.vipChestDay == day) return fail(ActionFailure.AlreadyClaimed)
        val granted = ShopLogic.grant(s, chest, data)
        return ok(granted.copy(shop = granted.shop.copy(vipChestDay = day)))
    }

    fun claimMonthPass(
        state: GameState,
        data: GameData,
    ): ActionResult {
        val s = ShopLogic.rollDay(state)
        if (!MonthPass.active(s)) return fail(ActionFailure.NotOwned)
        if (!MonthPass.canClaim(s)) return fail(ActionFailure.AlreadyClaimed)
        val granted = ShopLogic.grant(s, MonthPass.daily(data), data)
        return ok(granted.copy(shop = granted.shop.copy(monthPassClaimedDay = ShopLogic.localDay(s))))
    }

    fun claimGrowthFund(
        state: GameState,
        data: GameData,
    ): ActionResult {
        if (!state.shop.growthFundOwned) return fail(ActionFailure.NotOwned)
        val claimable = GrowthFund.claimable(state, data)
        if (claimable.isEmpty()) return fail(ActionFailure.NothingToClaim)
        val gems = claimable.sumOf { it.second }
        val s = state.copy(gems = satAdd(state.gems, gems))
        return ok(s.copy(shop = s.shop.copy(growthFundClaimed = s.shop.growthFundClaimed + claimable.map { it.first })))
    }

    /**
     * ラッキーホイールを回す。無料の 1 回 → 購入済みの追加分 → [payGems] ならジェム、の順に使う。
     * 結果（当たりと「惜しい」位置）は [GameEvent.WheelResult]。
     */
    fun spinWheel(
        state: GameState,
        payGems: Boolean,
        data: GameData,
    ): ActionResult {
        var s = ShopLogic.rollDay(state)
        val day = ShopLogic.localDay(s)
        val rules = data.shopRules.wheel
        s =
            when {
                s.shop.wheelFreeDay != day -> s.copy(shop = s.shop.copy(wheelFreeDay = day))
                s.shop.wheelExtra > 0 -> s.copy(shop = s.shop.copy(wheelExtra = s.shop.wheelExtra - 1))
                payGems -> {
                    if (s.gems < rules.extraGems) return fail(ActionFailure.NotEnoughGems(rules.extraGems - s.gems))
                    s.copy(gems = s.gems - rules.extraGems)
                }
                else -> return fail(ActionFailure.NothingToClaim)
            }
        val out = LuckyWheel.spin(s, data)
        val done = out.state.copy(shop = out.state.shop.copy(wheelSpins = out.state.shop.wheelSpins + 1))
        return ok(done, listOf(GameEvent.WheelResult(out.index, out.tease, out.segment.id)))
    }

    fun setShopSettings(
        state: GameState,
        settings: ShopSettings,
    ): ActionResult = ok(state.copy(shop = state.shop.copy(settings = settings)))
}
