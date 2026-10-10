package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.GemSku
import com.ttmrase.crownfall.core.data.OfferBadge
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.data.PackDef
import com.ttmrase.crownfall.core.data.TriggerDef
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Offer
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.satAdd

/** 不足ちょうどのパックの見積もり（中身は不足量そのもの、価格は換算表で決まる）。 */
data class ShortageQuote(
    val contents: PackContents,
    val priceYen: Long,
    val missing: Resources,
    val totalMissing: Long,
)

/** トリガー評価時の文脈。使う項目はトリガーの種類による。 */
data class TriggerCtx(
    val lv: Int = 0,
    val sec: Long = 0,
    val purchased: String? = null,
    val remainingPt: Long = 0,
    val awaySec: Long = 0,
    val params: Map<String, Long> = emptyMap(),
)

/**
 * オファーエンジン（04 §3）。純関数・決定論で、**台帳は読まない**。
 * トリガー（城 Lv アップ・敗北・長時間タイマーなど）と、頻度（セッション上限・クールダウン・連鎖・復活）を判定して
 * `shop.offerQueue` に積む。表示は :app の OfferPresenter が先頭から行う。
 */
object OfferEngine {
    const val SHORTAGE_SKU = "shortage"
    private const val SECONDS_PER_DAY = 86_400L
    private const val PERMILLE = 1000L
    private const val MIN_LOGIN_OFFER_SEC = 60L
    private val RESOURCE_KEYS = listOf("food", "wood", "stone", "gold")

    /** 1 セッションに積める数（量産型は無制限）。 */
    fun sessionCap(
        freq: OfferFrequency,
        data: GameData,
    ): Int =
        when (freq) {
            OfferFrequency.LOW -> data.shopRules.frequency.sessionCapLow
            OfferFrequency.STANDARD -> data.shopRules.frequency.sessionCapStandard
            OfferFrequency.MASS -> Int.MAX_VALUE
        }

    /** [t] が [kind] の文脈 [c] で発火するか。 */
    fun matches(
        t: TriggerDef,
        kind: String,
        c: TriggerCtx,
    ): Boolean {
        if (t.kind != kind) return false
        return when (kind) {
            "castleUp" -> c.lv >= t.minLv
            "longTimer" -> c.sec >= t.minSec
            "vipNear" -> c.remainingPt in 1..t.withinPt
            "comeback" -> c.awaySec >= t.minAwaySec
            "afterPurchase" -> t.chainOf == "any" || t.chainOf == c.purchased
            else -> true
        }
    }

    /** 期限内のオファー。 */
    fun active(state: GameState): List<Offer> = state.shop.offerQueue.filter { it.expiresEpochSec > state.nowEpochSec }

    /** 表示すべき先頭のオファー（閉じていない、期限内）。 */
    fun next(state: GameState): Offer? = active(state).firstOrNull { !it.dismissed }

    /** パック [pack] から、いまの状態のオファーを作る（城 Lv 記念は内容が Lv 倍）。 */
    fun makeOffer(
        state: GameState,
        pack: PackDef,
        trigger: String,
        data: GameData,
        params: Map<String, Long> = emptyMap(),
    ): Offer {
        val lv = (params["lv"]?.toInt() ?: ShopLogic.castleLevel(state)).coerceAtLeast(1)
        val scaled = if (pack.lvScale) ShopLogic.scale(pack.contents, lv) else pack.contents
        // 逆転パック: 1 位との差を埋めて少し上回るだけのイベントポイントを付ける（買えば 1 位を取れる）
        val gap = params["gap"]
        val contents =
            if (trigger == "rankingNear" && gap != null) {
                val margin = gap * data.events.bonusMarginPermille / PERMILLE + 1
                scaled.copy(eventPoints = satAdd(gap, margin))
            } else {
                scaled
            }
        val ends =
            if (trigger == "login") {
                maxOf(nextLocalMidnight(state), state.nowEpochSec + MIN_LOGIN_OFFER_SEC)
            } else {
                satAdd(state.nowEpochSec, pack.durationSec)
            }
        val extra = if (pack.lvScale) params + ("lv" to lv.toLong()) else params
        return Offer(
            id = state.shop.nextOfferId,
            sku = pack.id,
            trigger = trigger,
            createdEpochSec = state.nowEpochSec,
            expiresEpochSec = ends,
            priceYen = pack.priceYen,
            listValueYen = pack.listValueYen,
            badge = pack.badge,
            contents = contents,
            params = extra,
        )
    }

    private fun nextLocalMidnight(state: GameState): Long {
        val off = state.settings.utcOffsetSec
        val day = Math.floorDiv(state.nowEpochSec + off, SECONDS_PER_DAY)
        return (day + 1) * SECONDS_PER_DAY - off
    }

    /**
     * [offer] をキューに積む。次のいずれかなら積まない（状態は変わらない）:
     * 同じ SKU が期限内で残っている / クールダウン中 / セッション上限 / 今日の残数なし。
     * [bypass] は復活など、これらを免除する積み方（上限やクールダウンの対象にしない）。
     */
    fun enqueue(
        state: GameState,
        offer: Offer,
        cooldownSec: Long,
        data: GameData,
        stock: Int = Int.MAX_VALUE,
        bypass: Boolean = false,
    ): GameState {
        val s0 = ShopLogic.rollDay(state)
        val shop = s0.shop
        if (!bypass) {
            if (active(s0).any { it.sku == offer.sku }) return s0
            val last = shop.lastShownEpochSec[offer.sku]
            if (last != null && s0.nowEpochSec - last < cooldownSec) return s0
            if (shop.sessionOffers >= sessionCap(shop.settings.frequency, data)) return s0
            if ((shop.stockUsed[offer.sku] ?: 0) >= stock) return s0
        }
        val withId = offer.copy(id = shop.nextOfferId)
        val queue = (shop.offerQueue + withId).takeLast(data.shopRules.frequency.queueCap)
        return s0.copy(
            shop =
                shop.copy(
                    offerQueue = queue,
                    nextOfferId = shop.nextOfferId + 1,
                    sessionOffers = shop.sessionOffers + if (bypass) 0 else 1,
                    lastShownEpochSec = shop.lastShownEpochSec + (offer.sku to s0.nowEpochSec),
                ),
        )
    }

    /** パックのトリガー種 [kind] が [ctx] で発火するものをすべて積む。 */
    fun fire(
        state: GameState,
        kind: String,
        ctx: TriggerCtx,
        data: GameData,
    ): GameState {
        var s = state
        for (pack in data.offers.packs) {
            val t = pack.trigger ?: continue
            if (!matches(t, kind, ctx)) continue
            if (kind == "afterPurchase" && s.shop.chainRun >= data.shopRules.frequency.chainMax) continue
            val offer = makeOffer(s, pack, kind, data, ctx.params)
            s = enqueue(s, offer, pack.cooldownSec, data, pack.dailyStock)
        }
        return s
    }

    /** 不足 [missing] ちょうどの中身で、換算表から価格を決める。不足が無ければ null。 */
    fun shortageOffer(
        missing: Resources,
        data: GameData,
    ): ShortageQuote? {
        val total = satAdd(satAdd(missing.food, missing.wood), satAdd(missing.stone, missing.gold))
        if (total <= 0) return null
        val table = data.shopRules.shortage.resourceYenTable
        val price = (table.firstOrNull { total <= it[0] } ?: table.last())[1]
        return ShortageQuote(PackContents(resources = missing), price, missing, total)
    }

    /** 不足ジェム [missing] 以上で最小のジェム SKU（ジェム + ボーナスで判定。無ければ最大の SKU）。 */
    fun gemShortageSku(
        missing: Long,
        data: GameData,
    ): GemSku =
        data.offers.gemSkus.sortedBy { it.priceYen }.firstOrNull { it.gems + it.bonus >= missing }
            ?: data.offers.gemSkus.maxBy { it.priceYen }

    /** 資源不足のタップ。不足ちょうどのパックを積む。 */
    fun onResourceShortage(
        state: GameState,
        missing: Resources,
        data: GameData,
    ): GameState {
        val q = shortageOffer(missing, data) ?: return state
        val params = RESOURCE_KEYS.zip(listOf(missing.food, missing.wood, missing.stone, missing.gold)).toMap()
        val main = listOf(missing.food, missing.wood, missing.stone, missing.gold).withIndex().maxBy { it.value }
        val offer =
            Offer(
                id = 0,
                sku = SHORTAGE_SKU,
                trigger = "resourceShortage",
                createdEpochSec = state.nowEpochSec,
                expiresEpochSec = satAdd(state.nowEpochSec, data.shopRules.shortage.durationSec),
                priceYen = q.priceYen,
                listValueYen = q.priceYen * 2,
                badge = OfferBadge.RECOMMENDED,
                contents = q.contents,
                params = params + ("main" to main.index.toLong()) + ("total" to q.totalMissing),
            )
        return replaceOrEnqueue(state, offer, data)
    }

    /** ジェム不足のタップ。不足以上で最小の SKU を積む。 */
    fun onGemShortage(
        state: GameState,
        missing: Long,
        data: GameData,
    ): GameState {
        if (missing <= 0) return state
        val sku = gemShortageSku(missing, data)
        val offer =
            Offer(
                id = 0,
                sku = sku.sku,
                trigger = "gemShortage",
                createdEpochSec = state.nowEpochSec,
                expiresEpochSec = satAdd(state.nowEpochSec, data.shopRules.shortage.durationSec),
                priceYen = sku.priceYen,
                listValueYen = sku.priceYen,
                badge = OfferBadge.RECOMMENDED,
                contents = PackContents(gems = sku.gems + sku.bonus),
                params = mapOf("missing" to missing, "yen" to sku.priceYen),
            )
        return replaceOrEnqueue(state, offer, data)
    }

    /** 同じ SKU が期限内に残っていれば中身だけ最新に差し替えて再表示し、無ければ通常どおり積む（不足は押すたびに変わるため）。 */
    private fun replaceOrEnqueue(
        state: GameState,
        offer: Offer,
        data: GameData,
    ): GameState {
        val existing =
            active(state).firstOrNull { it.sku == offer.sku }
                ?: return enqueue(state, offer, data.shopRules.shortage.cooldownSec, data)
        val updated = offer.copy(id = existing.id, createdEpochSec = existing.createdEpochSec, dismissed = false)
        val queue = state.shop.offerQueue.map { if (it.id == existing.id) updated else it }
        return state.copy(shop = state.shop.copy(offerQueue = queue))
    }

    /** 2 本目ビルダー枠のタップ。ビルダー付きパックを積む。 */
    fun onSecondBuilderLocked(
        state: GameState,
        data: GameData,
    ): GameState {
        val pack = data.offers.packs.firstOrNull { it.contents.secondBuilderDays > 0 } ?: return state
        return enqueue(state, makeOffer(state, pack, "secondBuilder", data), pack.cooldownSec, data, pack.dailyStock)
    }

    /** 退出前（戻る → タイトル確認の前）。1 日 1 回、「待って！」を積む。積めたら true を返す側で扱う。 */
    fun onExit(
        state: GameState,
        data: GameData,
    ): GameState {
        val s0 = ShopLogic.rollDay(state)
        val day = ShopLogic.localDay(s0)
        if (s0.shop.exitOfferDay == day) return s0
        val pack = data.offers.pack(data.shopRules.exitPackSku) ?: return s0
        val queued = enqueue(s0, makeOffer(s0, pack, "exitIntent", data), pack.cooldownSec, data, pack.dailyStock)
        return queued.copy(shop = queued.shop.copy(exitOfferDay = day))
    }

    /** イベント集計で 2〜4 位になった。 */
    fun onRankingNear(
        state: GameState,
        rank: Int,
        gapPt: Long,
        data: GameData,
    ): GameState = fire(state, "rankingNear", TriggerCtx(params = mapOf("rank" to rank.toLong(), "gap" to gapPt)), data)

    /** 長時間タイマーを開始した。 */
    fun onLongTimer(
        state: GameState,
        durationSec: Long,
        data: GameData,
    ): GameState = fire(state, "longTimer", TriggerCtx(sec = durationSec, params = mapOf("sec" to durationSec)), data)

    /** 長期不在から戻った。 */
    fun onComeback(
        state: GameState,
        awaySec: Long,
        data: GameData,
    ): GameState = fire(state, "comeback", TriggerCtx(awaySec = awaySec, params = mapOf("away" to awaySec)), data)

    /** VIP 昇格間近の判定（pt が変わったとき）。 */
    fun onVipChanged(
        state: GameState,
        data: GameData,
    ): GameState {
        val (lv, left) = VipCalculator.toNext(state, data) ?: return state
        return fire(
            state,
            "vipNear",
            TriggerCtx(remainingPt = left, params = mapOf("vip" to lv.toLong(), "pt" to left)),
            data,
        )
    }

    /** 購入直後の連鎖。[sku] は買ったもの（連鎖パックならその ID）。 */
    fun afterPurchase(
        state: GameState,
        sku: String,
        data: GameData,
    ): GameState = fire(state, "afterPurchase", TriggerCtx(purchased = sku), data)

    /** 期限切れを片付ける。量産型なら一度だけ「復活！」として出し直す。 */
    fun refresh(
        state: GameState,
        data: GameData,
    ): GameState {
        val shop = state.shop
        val now = state.nowEpochSec
        if (shop.offerQueue.none { it.expiresEpochSec <= now }) return state
        val keep = shop.offerQueue.filter { it.expiresEpochSec > now }
        var s = state.copy(shop = shop.copy(offerQueue = keep))
        if (shop.settings.frequency == OfferFrequency.MASS) {
            val expired = shop.offerQueue.filter { it.expiresEpochSec <= now && !it.revived }
            for (o in expired) {
                val revived =
                    o.copy(
                        createdEpochSec = now,
                        expiresEpochSec = satAdd(now, data.shopRules.frequency.reviveDurationSec),
                        dismissed = false,
                        revived = true,
                    )
                s = enqueue(s, revived, 0, data, bypass = true)
            }
        }
        return s
    }

    /** オファーを閉じる（期限までは棚に残る）。 */
    fun dismiss(
        state: GameState,
        offerId: Long,
    ): GameState =
        state.copy(
            shop =
                state.shop.copy(
                    offerQueue = state.shop.offerQueue.map { if (it.id == offerId) it.copy(dismissed = true) else it },
                ),
        )

    /** 新しいセッション。上限カウンタを戻し、期限切れを片付け、ログイン時の本日限定を積む。 */
    fun beginSession(
        state: GameState,
        data: GameData,
    ): GameState {
        val s0 = ShopLogic.rollDay(state)
        val s1: GameState = s0.copy(shop = s0.shop.copy(sessionOffers = 0))
        val s2 = refresh(s1, data)
        val day = ShopLogic.localDay(s2)
        if (s2.shop.loginPopupDay == day) return s2
        val fired = fire(s2, "login", TriggerCtx(), data)
        return fired.copy(shop = fired.shop.copy(loginPopupDay = day))
    }
}
