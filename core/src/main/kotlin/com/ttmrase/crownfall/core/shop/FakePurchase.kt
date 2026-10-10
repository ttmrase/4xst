package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.GemSku
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.march.MailBox
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.LedgerRecord
import com.ttmrase.crownfall.core.model.Mail
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.Receipt
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.util.Mix

/** 商品 ID の組み立て（`Actions.fakePurchase` の引数）。 */
object ProductIds {
    /** キュー内のオファー（不足ちょうど・ジェム不足・各トリガーのパック）。 */
    fun offer(id: Long) = "offer:$id"

    /** 今日のデイリーディール。 */
    fun deal(packId: String) = "deal:$packId"
}

/** 擬似購入（04 §5）。実通信なし。内容・初回 2 倍・VIP pt・台帳・レシート・連鎖を 1 回で原子的に行う。 */
object FakePurchase {
    private const val SECONDS_PER_DAY = 86_400L
    private const val ORDER_RANGE = 1_679_616 // 36^4
    private const val ORDER_A = 11L
    private const val ORDER_B = 12L

    private enum class Kind { GEMS, PACK, MONTH_PASS, GROWTH_FUND, PIGGY, WHEEL_EXTRA }

    private class Product(
        val sku: String,
        val priceYen: Long,
        val contents: PackContents,
        val trigger: String,
        val kind: Kind,
        val offerId: Long? = null,
        val stock: Int? = null,
        val firstDoubleGems: Long = 0,
        val chainTier: Int = 0,
    )

    private fun fail(reason: ActionFailure) = ActionResult.Failure(reason)

    private fun resolve(
        s: GameState,
        id: String,
        data: GameData,
    ): Product? {
        val rules = data.shopRules
        if (id.startsWith("offer:")) {
            val offer =
                id.removePrefix("offer:").toLongOrNull()
                    ?.let { n -> OfferEngine.active(s).firstOrNull { it.id == n } } ?: return null
            data.offers.gem(offer.sku)?.let { return gemProduct(it, offer.trigger, offer.id) }
            val pack = data.offers.pack(offer.sku)
            return Product(
                offer.sku,
                offer.priceYen,
                offer.contents,
                offer.trigger,
                Kind.PACK,
                offer.id,
                stock = pack?.dailyStock,
                chainTier = pack?.tier ?: 0,
            )
        }
        if (id.startsWith("deal:")) {
            val card = DailyDeals.find(s, id.removePrefix("deal:"), data) ?: return null
            return Product(
                card.pack.id,
                card.priceYen,
                card.pack.contents,
                "deal",
                Kind.PACK,
                stock = card.pack.dailyStock,
            )
        }
        data.offers.gem(id)?.let { return gemProduct(it, "shop", null) }
        data.offers.pack(id)?.takeIf { it.shelf }?.let {
            val contents =
                if (it.lvScale) {
                    ShopLogic.scale(
                        it.contents,
                        ShopLogic.castleLevel(s).coerceAtLeast(1),
                    )
                } else {
                    it.contents
                }
            return Product(it.id, it.priceYen, contents, "shop", Kind.PACK, stock = it.dailyStock, chainTier = it.tier)
        }
        return when (id) {
            rules.monthPass.sku -> Product(id, rules.monthPass.priceYen, PackContents(), "shop", Kind.MONTH_PASS)
            rules.growthFund.sku -> Product(id, rules.growthFund.priceYen, PackContents(), "shop", Kind.GROWTH_FUND)
            rules.piggy.sku ->
                Product(
                    id,
                    rules.piggy.openYen,
                    PackContents(gems = s.shop.piggyGems),
                    "shop",
                    Kind.PIGGY,
                )
            rules.wheel.extraSku -> Product(id, rules.wheel.extraYen, PackContents(), "shop", Kind.WHEEL_EXTRA)
            else -> null
        }
    }

    private fun gemProduct(
        g: GemSku,
        trigger: String,
        offerId: Long?,
    ) = Product(
        g.sku,
        g.priceYen,
        PackContents(gems = g.gems + g.bonus),
        trigger,
        Kind.GEMS,
        offerId,
        firstDoubleGems = if (g.firstDouble) g.gems else 0,
    )

    /** [id] の商品を擬似購入する（ID は [ProductIds] またはカタログの SKU）。 */
    fun purchase(
        state: GameState,
        id: String,
        data: GameData,
    ): ActionResult {
        var s = ShopLogic.rollDay(state)
        val p = resolve(s, id, data) ?: return fail(ActionFailure.OfferUnavailable)
        if (p.stock != null && (s.shop.stockUsed[p.sku] ?: 0) >= p.stock) return fail(ActionFailure.SoldOut)
        if (p.kind == Kind.GROWTH_FUND && s.shop.growthFundOwned) return fail(ActionFailure.AlreadyOwned)
        if (p.kind == Kind.PIGGY && s.shop.piggyGems <= 0) return fail(ActionFailure.NothingToClaim)
        val events = ArrayList<GameEvent>()
        val doubled = p.kind == Kind.GEMS && p.firstDoubleGems > 0 && p.sku !in s.shop.firstPurchased
        val contents = if (doubled) p.contents.copy(gems = p.contents.gems + p.firstDoubleGems) else p.contents
        s = ShopLogic.grant(s, contents, data)
        s = applyKind(s, p, data)
        val orderA = Mix.roll(ShopLogic.seed(s), s.shop.purchaseCount, ORDER_A, ORDER_RANGE)
        val orderB = Mix.roll(ShopLogic.seed(s), s.shop.purchaseCount, ORDER_B, ORDER_RANGE)
        val receipt = Receipt(p.sku, p.priceYen, contents, orderA, orderB, doubled, s.shop.settings.receiptNote)
        val mailId = s.nextMailId
        s = MailBox.add(s, Mail(0, MailKind.RECEIPT, s.nowEpochSec, systemCode = "receipt", receipt = receipt), events)
        val ctx = buildMap { p.offerId?.let { put("offer", it) } }
        val record = LedgerRecord(s.nowEpochSec, p.sku, p.priceYen, p.trigger, ctx)
        val shop = s.shop
        s =
            s.copy(
                shop =
                    shop.copy(
                        ledger =
                            FakeLedger.record(shop.ledger, record, s.settings.utcOffsetSec, data.shopRules.ledgerCap),
                        stockUsed = bumpStock(shop.stockUsed, p),
                        firstPurchased = if (doubled) shop.firstPurchased + p.sku else shop.firstPurchased,
                        purchaseCount = shop.purchaseCount + 1,
                        offerQueue = shop.offerQueue.filterNot { it.id == p.offerId },
                        chainRun = p.chainTier,
                    ),
            )
        events.add(0, GameEvent.Purchased(p.sku, p.priceYen, mailId, contents.gems, orderA, orderB))
        s = VipCalculator.addPoints(s, p.priceYen / data.shopRules.yenPerVipPoint, data, events)
        s = OfferEngine.onVipChanged(s, data)
        s = OfferEngine.afterPurchase(s, p.sku, data)
        return ActionResult.Success(s, events)
    }

    private fun bumpStock(
        used: Map<String, Int>,
        p: Product,
    ): Map<String, Int> = if (p.stock == null) used else used + (p.sku to (used[p.sku] ?: 0) + 1)

    private fun applyKind(
        s: GameState,
        p: Product,
        data: GameData,
    ): GameState =
        when (p.kind) {
            Kind.MONTH_PASS -> {
                val base = maxOf(s.shop.monthPassUntilEpochSec, s.nowEpochSec)
                val until = satAdd(base, data.shopRules.monthPass.days * SECONDS_PER_DAY)
                s.copy(shop = s.shop.copy(monthPassUntilEpochSec = until))
            }
            Kind.GROWTH_FUND -> s.copy(shop = s.shop.copy(growthFundOwned = true))
            Kind.PIGGY -> s.copy(shop = s.shop.copy(piggyGems = 0))
            Kind.WHEEL_EXTRA -> s.copy(shop = s.shop.copy(wheelExtra = s.shop.wheelExtra + 1))
            Kind.GEMS, Kind.PACK -> s
        }

    /** 注文番号 ORD-XXXX-XXXX（36 進 4 桁 × 2）。 */
    fun orderNumber(
        a: Int,
        b: Int,
    ): String = "ORD-${base36(a)}-${base36(b)}"

    private fun base36(n: Int): String = n.toString(36).uppercase().padStart(4, '0')
}
