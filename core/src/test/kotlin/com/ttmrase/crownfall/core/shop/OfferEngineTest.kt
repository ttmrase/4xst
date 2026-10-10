package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.data.TriggerDef
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Ledger
import com.ttmrase.crownfall.core.model.LedgerRecord
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.shop.ShopTestSupport.DAY
import com.ttmrase.crownfall.core.shop.ShopTestSupport.HOUR
import com.ttmrase.crownfall.core.shop.ShopTestSupport.base
import com.ttmrase.crownfall.core.shop.ShopTestSupport.skus
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfferEngineTest {
    private val data = ShopTestSupport.data
    private val castleSlot get() = base().slots.first { it.type == BuildingType.CASTLE }.slotId

    private fun castleUp(
        s: GameState,
        lv: Int,
    ) = ShopHooks.onEvents(s, listOf(GameEvent.BuildingLevelUp(castleSlot, BuildingType.CASTLE, lv)), data)

    // ───── トリガーの発火 / 非発火 ─────

    @Test
    fun triggerMatchingForEveryKindAtItsBoundaries() {
        fun m(
            t: TriggerDef,
            c: TriggerCtx,
        ) = OfferEngine.matches(t, t.kind, c)
        assertTrue(m(TriggerDef("login"), TriggerCtx()))
        assertTrue(m(TriggerDef("castleUp", minLv = 5), TriggerCtx(lv = 5)))
        assertFalse(m(TriggerDef("castleUp", minLv = 5), TriggerCtx(lv = 4)))
        assertTrue(m(TriggerDef("longTimer", minSec = 8 * HOUR), TriggerCtx(sec = 8 * HOUR)))
        assertFalse(m(TriggerDef("longTimer", minSec = 8 * HOUR), TriggerCtx(sec = 8 * HOUR - 1)))
        assertTrue(m(TriggerDef("vipNear", withinPt = 700), TriggerCtx(remainingPt = 700)))
        assertFalse(m(TriggerDef("vipNear", withinPt = 700), TriggerCtx(remainingPt = 701)))
        assertFalse(m(TriggerDef("vipNear", withinPt = 700), TriggerCtx(remainingPt = 0)))
        assertTrue(m(TriggerDef("comeback", minAwaySec = 2 * DAY), TriggerCtx(awaySec = 2 * DAY)))
        assertFalse(m(TriggerDef("comeback", minAwaySec = 2 * DAY), TriggerCtx(awaySec = 2 * DAY - 1)))
        assertTrue(m(TriggerDef("afterPurchase", chainOf = "any"), TriggerCtx(purchased = "gems_s")))
        assertTrue(m(TriggerDef("afterPurchase", chainOf = "pack_chain_1"), TriggerCtx(purchased = "pack_chain_1")))
        assertFalse(m(TriggerDef("afterPurchase", chainOf = "pack_chain_1"), TriggerCtx(purchased = "gems_s")))
        for (k in listOf(
            "defeated",
            "rankingNear",
            "exitIntent",
            "piggyFull",
        )) assertTrue(m(TriggerDef(k), TriggerCtx()))
        assertFalse(OfferEngine.matches(TriggerDef("defeated"), "login", TriggerCtx()))
    }

    @Test
    fun castleUpQueuesTheMemorialPackScaledByLevel() {
        val s = castleUp(base(), 6)
        val offer = OfferEngine.active(s).single { it.sku == "pack_castle" }
        val pack = data.offers.pack("pack_castle")!!
        assertEquals(pack.contents.gems * 6, offer.contents.gems)
        assertEquals(6L, offer.params["lv"])
        assertEquals(pack.contents.resources.food * 6, offer.contents.resources.food)
        val lv10 = OfferEngine.active(castleUp(base(), 10)).single { it.sku == "pack_castle" }
        assertTrue(lv10.contents.gems > offer.contents.gems) // Lv が高いほど豪華
        assertEquals(base().nowEpochSec + 2 * DAY, offer.expiresEpochSec)
        assertEquals("castleUp", offer.trigger)
        // minLv 未満では出ない
        assertTrue(OfferEngine.active(castleUp(base(), 1)).none { it.sku == "pack_castle" })
    }

    @Test
    fun castleUpFromARealCompletedUpgradeQueuesOfferAndFillsPiggy() {
        var s = base()
        val slot = castleSlot
        val target = s.slots.first { it.slotId == slot }.level + 1
        val job = TimerJob(1, TimerKind.BUILD, slot, target, s.nowEpochSec, s.nowEpochSec + 100)
        s = s.copy(timers = listOf(job), nextJobId = 2)
        val r = Simulation.tick(s, 100, data)
        assertTrue("pack_castle" in skus(r.state))
        assertEquals(data.shopRules.piggy.gemsPerBuild, r.state.shop.piggyGems)
    }

    @Test
    fun defeatQueuesRevengePack() {
        val s = ShopHooks.onEvents(base(), listOf(GameEvent.PlayerDefeated(1)), data)
        assertEquals(listOf("pack_revenge"), skus(s))
    }

    @Test
    fun longTimerOnlyFromEightHours() {
        assertTrue(skus(ShopHooks.onTimerStarted(base(), 8 * HOUR - 1, data)).isEmpty())
        assertEquals(listOf("pack_speed_bundle"), skus(ShopHooks.onTimerStarted(base(), 8 * HOUR, data)))
    }

    @Test
    fun startingARealLongTimerQueuesTheBundle() {
        val s = base()
        val r = Actions.startUpgrade(s, s.slots.first { it.type == BuildingType.CASTLE }.slotId, data).ok()
        val dur = r.timers.single().let { it.endEpochSec - it.startEpochSec }
        assertEquals(dur >= 8 * HOUR, "pack_speed_bundle" in skus(r), "duration=$dur")
        val short = Actions.startUpgrade(s, s.slots.first { it.type == BuildingType.FARM }.slotId, data).ok()
        assertFalse("pack_speed_bundle" in skus(short))
    }

    @Test
    fun rankingExitComebackPiggyAndVipNearTriggers() {
        val r = Actions.offerForRanking(base(), 2, 340, data).ok()
        val o = OfferEngine.active(r).single()
        assertEquals("pack_ranking", o.sku)
        assertEquals(2L, o.params["rank"])
        assertEquals(340L, o.params["gap"])
        assertEquals(listOf("pack_wait"), skus(Actions.exitOffer(base(), data).ok()))
        val cb = OfflineCatchUp.run(base(), base().nowEpochSec + 2 * DAY, data).state
        assertTrue("pack_comeback" in skus(cb))
        val cb2 = OfflineCatchUp.run(base(), base().nowEpochSec + 2 * DAY - 1, data).state
        assertTrue("pack_comeback" !in skus(cb2))
        val near =
            base().let {
                it.copy(
                    shop = it.shop.copy(vipPoints = 100 - 50),
                )
            } // VIP1 まであと 50pt（vipNear は 700pt 以内）
        val vn = OfferEngine.onVipChanged(near, data)
        val vo = OfferEngine.active(vn).single { it.sku == "pack_vip" }
        assertEquals(1L, vo.params["vip"])
        assertEquals(50L, vo.params["pt"])
        val far = base().let { it.copy(shop = it.shop.copy(vipPoints = 150_000 + 1)) }
        assertTrue(skus(OfferEngine.onVipChanged(far, data)).isEmpty())
    }

    @Test
    fun piggyFullQueuesTheNudgeOnceWhenCrossingTheCap() {
        val cap = data.shopRules.piggy.cap
        val almost = base().let { it.copy(shop = it.shop.copy(piggyGems = cap - 1)) }
        val full = ShopHooks.onEvents(almost, listOf(GameEvent.BuildingLevelUp(0, BuildingType.FARM, 2)), data)
        assertEquals(cap, full.shop.piggyGems)
        assertTrue("pack_piggy_nudge" in skus(full))
        // すでに満杯なら新しくは積まない
        val again =
            ShopHooks.onEvents(
                full.copy(shop = full.shop.copy(offerQueue = emptyList())),
                listOf(GameEvent.BuildingLevelUp(0, BuildingType.FARM, 3)),
                data,
            )
        assertTrue(skus(again).isEmpty())
    }

    // ───── 不足ちょうど ─────

    @Test
    fun shortagePackContentsEqualTheMissingAmountAndPriceFollowsTheTable() {
        val missing = Resources(1200, 0, 3400, 50)
        val q = OfferEngine.shortageOffer(missing, data)!!
        assertEquals(missing, q.contents.resources)
        assertEquals(0L, q.contents.gems)
        assertEquals(4650L, q.totalMissing)
        assertEquals(160L, q.priceYen) // 3000 < 4650 <= 10000
        assertNull(OfferEngine.shortageOffer(Resources.ZERO, data))
        assertEquals(120L, OfferEngine.shortageOffer(Resources(3000, 0, 0, 0), data)!!.priceYen)
        assertEquals(160L, OfferEngine.shortageOffer(Resources(3001, 0, 0, 0), data)!!.priceYen)
        assertEquals(12000L, OfferEngine.shortageOffer(Resources(Long.MAX_VALUE / 8, 0, 0, 0), data)!!.priceYen)
        val prices =
            listOf(1L, 5000L, 20_000L, 100_000L, 400_000L, 2_000_000L, 9_000_000L)
                .map { OfferEngine.shortageOffer(Resources(it, 0, 0, 0), data)!!.priceYen }
        assertEquals(prices.sorted(), prices) // 不足が増えて価格が下がることは無い
    }

    @Test
    fun shortageHookQueuesAnExactOfferAndRetapUpdatesIt() {
        val s1 = Actions.offerForShortage(base(), Resources(0, 800, 0, 0), data).ok()
        val o1 = OfferEngine.active(s1).single()
        assertEquals(OfferEngine.SHORTAGE_SKU, o1.sku)
        assertEquals(Resources(0, 800, 0, 0), o1.contents.resources)
        assertEquals(800L, o1.params["wood"])
        assertEquals(1L, o1.params["main"]) // wood
        val s2 = Actions.offerForShortage(s1, Resources(0, 1500, 0, 0), data).ok()
        val o2 = OfferEngine.active(s2).single()
        assertEquals(o1.id, o2.id)
        assertEquals(1500L, o2.params["wood"])
        // 閉じても再タップで再表示される
        val closed = Actions.dismissOffer(s2, o2.id).ok()
        assertNull(OfferEngine.next(closed))
        assertNotNull(OfferEngine.next(Actions.offerForShortage(closed, Resources(0, 900, 0, 0), data).ok()))
        assertTrue(skus(Actions.offerForShortage(base(), Resources.ZERO, data).ok()).isEmpty())
    }

    @Test
    fun gemShortageOffersTheSmallestSkuThatCoversTheGap() {
        fun pick(n: Long) = OfferEngine.gemShortageSku(n, data).sku
        assertEquals("gems_s", pick(1))
        assertEquals("gems_s", pick(100))
        assertEquals("gems_m", pick(101))
        assertEquals("gems_m", pick(550))
        assertEquals("gems_l", pick(551))
        assertEquals("gems_max", pick(1_000_000))
        val s = Actions.offerForGemShortage(base(), 120, data).ok()
        val o = OfferEngine.active(s).single()
        assertEquals("gems_m", o.sku)
        assertEquals(120L, o.params["missing"])
        assertEquals(610L, o.params["yen"])
        assertTrue(skus(Actions.offerForGemShortage(base(), 0, data).ok()).isEmpty())
    }

    @Test
    fun secondBuilderTapOffersABuilderPack() {
        val s = Actions.offerForSecondBuilder(base(), data).ok()
        val o = OfferEngine.active(s).single()
        assertTrue(o.contents.secondBuilderDays > 0)
    }

    // ───── 頻度・クールダウン・連鎖・復活 ─────

    private fun burst(s0: GameState): GameState {
        // 条件の違うトリガーを次々に発火させて、セッション上限を試す（どれも別 SKU）
        var s = s0
        s = castleUp(s, 6)
        s = ShopHooks.onEvents(s, listOf(GameEvent.PlayerDefeated(1)), data)
        s = ShopHooks.onTimerStarted(s, 9 * HOUR, data)
        s = Actions.offerForRanking(s, 3, 10, data).ok()
        s = Actions.exitOffer(s, data).ok()
        s = ShopHooks.onReturn(s, 3 * DAY, data)
        return s
    }

    @Test
    fun sessionCapDependsOnFrequencyAndBeginSessionResetsIt() {
        assertEquals(2, skus(burst(base(OfferFrequency.LOW))).size)
        assertEquals(5, skus(burst(base(OfferFrequency.STANDARD))).size)
        assertEquals(6, skus(burst(base(OfferFrequency.MASS))).size)
        val low = burst(base(OfferFrequency.LOW))
        assertEquals(2, low.shop.sessionOffers)
        val next = Actions.beginSession(low, data).ok()
        assertEquals(
            setOf("pack_daily", "pack_vip"),
            next.shop.offerQueue.map {
                it.sku
            }.toSet() - low.shop.offerQueue.map { it.sku }.toSet(),
        )
        assertEquals(2, next.shop.sessionOffers) // 上限はセッションごとにやり直し（本日限定 + VIP 昇格間近）
        assertEquals(Int.MAX_VALUE, OfferEngine.sessionCap(OfferFrequency.MASS, data))
    }

    @Test
    fun cooldownBlocksTheSameSkuUntilItHasPassed() {
        val pack = data.offers.pack("pack_castle")!!
        val first = castleUp(base(), 6)
        val gone = first.copy(shop = first.shop.copy(offerQueue = emptyList())) // 買った / 消えた想定（残数は使っていない）
        val cd = pack.cooldownSec
        assertTrue(skus(castleUp(gone.copy(nowEpochSec = gone.nowEpochSec + cd - 1), 7)).isEmpty())
        assertEquals(listOf("pack_castle"), skus(castleUp(gone.copy(nowEpochSec = gone.nowEpochSec + cd), 7)))
        // 期限内に同じ SKU が残っているあいだは重ねて積まない
        assertEquals(1, first.shop.offerQueue.size)
        assertEquals(1, castleUp(first.copy(nowEpochSec = first.nowEpochSec + cd + 5), 7).shop.offerQueue.size)
    }

    @Test
    fun dailyStockStopsOffersAndResetsOnTheNextLocalDay() {
        var s = castleUp(base(), 6)
        val id = OfferEngine.active(s).single().id
        s = Actions.fakePurchase(s, ProductIds.offer(id), data).ok()
        s = s.copy(shop = s.shop.copy(offerQueue = emptyList()))
        val later = s.copy(nowEpochSec = s.nowEpochSec + 3 * HOUR) // クールダウンは過ぎたが、残数 1 を使い切っている
        assertTrue(skus(castleUp(later, 7)).none { it == "pack_castle" })
        val tomorrow = later.copy(nowEpochSec = (ShopTestSupport.DAY0 + 1) * DAY + HOUR)
        assertTrue("pack_castle" in skus(castleUp(tomorrow, 7)))
    }

    @Test
    fun chainRunsThroughThreeTiersAndStops() {
        var s = Actions.fakePurchase(base(), "gems_s", data).ok()
        assertTrue("pack_chain_1" in skus(s))
        for (tier in 1..3) {
            val o = OfferEngine.active(s).single { it.sku == "pack_chain_$tier" }
            s = Actions.fakePurchase(s, ProductIds.offer(o.id), data).ok()
            assertEquals(tier, s.shop.chainRun)
            if (tier < 3) {
                assertTrue("pack_chain_${tier + 1}" in skus(s), "tier ${tier + 1} unlocked")
            } else {
                assertTrue(skus(s).none { it.startsWith("pack_chain_") })
            }
        }
        // 連鎖の後に普通の購入をすると連鎖は最初からやり直せる（翌日以降の残数は別）
        assertEquals(3, data.shopRules.frequency.chainMax)
    }

    @Test
    fun expiredOffersAreRevivedOnlyInMassAndOnlyOnce() {
        val mass = castleUp(base(OfferFrequency.MASS), 6)
        val orig = OfferEngine.active(mass).single()
        val after = mass.copy(nowEpochSec = orig.expiresEpochSec + 1)
        val revived = OfferEngine.refresh(after, data)
        val r = OfferEngine.active(revived).single()
        assertTrue(r.revived)
        assertEquals(orig.sku, r.sku)
        assertEquals(orig.contents, r.contents)
        assertEquals(after.nowEpochSec + data.shopRules.frequency.reviveDurationSec, r.expiresEpochSec)
        assertTrue(r.id != orig.id)
        // 復活したものが切れたら、もう復活しない
        val gone = OfferEngine.refresh(revived.copy(nowEpochSec = r.expiresEpochSec + 1), data)
        assertTrue(gone.shop.offerQueue.isEmpty())
        // 標準 / 低では復活しない
        for (f in listOf(OfferFrequency.STANDARD, OfferFrequency.LOW)) {
            val s = castleUp(base(f), 6)
            val o = OfferEngine.active(s).single()
            assertTrue(
                OfferEngine.refresh(s.copy(nowEpochSec = o.expiresEpochSec + 1), data).shop.offerQueue.isEmpty(),
                f.name,
            )
        }
        // 閉じていた場合も量産型では復活する
        val closed = Actions.dismissOffer(mass, orig.id).ok()
        assertNotNull(OfferEngine.next(OfferEngine.refresh(closed.copy(nowEpochSec = orig.expiresEpochSec + 1), data)))
    }

    @Test
    fun queueIsBoundedAndKeepsTheNewest() {
        var s = base()
        val cap = data.shopRules.frequency.queueCap
        repeat(cap + 8) { i ->
            val o = OfferEngine.makeOffer(s, data.offers.pack("pack_castle")!!, "castleUp", data).copy(sku = "x$i")
            s = OfferEngine.enqueue(s, o, 0, data)
        }
        assertEquals(cap, s.shop.offerQueue.size)
        assertEquals("x${cap + 7}", s.shop.offerQueue.last().sku)
    }

    // ───── ログイン・退出 ─────

    @Test
    fun loginQueuesTodaysPackUntilLocalMidnightAndAwardsStreakPoints() {
        val s1 = Actions.beginSession(base(), data)
        val first = s1.ok()
        val o = OfferEngine.active(first).single { it.sku == "pack_daily" }
        assertEquals((ShopTestSupport.DAY0 + 1) * DAY, o.expiresEpochSec)
        assertEquals(50L, first.shop.vipPoints)
        assertEquals(1, first.shop.loginStreak)
        // 同じ日に再度始めても、ポイントも本日限定も増えない
        val again = Actions.beginSession(first, data).ok()
        assertEquals(50L, again.shop.vipPoints)
        assertEquals(1, again.shop.offerQueue.count { it.sku == "pack_daily" })
        // 連続日数で増え、途切れるとリセット
        var s = first
        val expected = listOf(100L, 150L, 200L, 250L, 300L, 300L)
        for ((i, add) in expected.withIndex()) {
            s = Actions.beginSession(s.copy(nowEpochSec = s.nowEpochSec + DAY), data).ok()
            assertEquals(i + 2, s.shop.loginStreak)
            assertEquals(add, VipCalculator.loginPoints(s.shop.loginStreak, data))
        }
        val broken = Actions.beginSession(s.copy(nowEpochSec = s.nowEpochSec + 3 * DAY), data).ok()
        assertEquals(1, broken.shop.loginStreak)
        assertEquals(50L, VipCalculator.loginPoints(1, data))
    }

    @Test
    fun loginDayFollowsTheLocalTimeZone() {
        val s = base(utc = 9 * 3600) // 正午 UTC = 21:00 JST。3 時間後に日付が変わる
        val a = Actions.beginSession(s, data).ok()
        val o = OfferEngine.active(a).single { it.sku == "pack_daily" }
        assertEquals(s.nowEpochSec + 3 * HOUR, o.expiresEpochSec)
        val b = Actions.beginSession(a.copy(nowEpochSec = a.nowEpochSec + 2 * HOUR), data).ok()
        assertEquals(1, b.shop.loginStreak) // まだ同じ現地日
        val c = Actions.beginSession(a.copy(nowEpochSec = a.nowEpochSec + 3 * HOUR), data).ok()
        assertEquals(2, c.shop.loginStreak)
    }

    @Test
    fun exitOfferAppearsOncePerLocalDay() {
        val a = Actions.exitOffer(base(), data).ok()
        assertEquals(listOf("pack_wait"), skus(a))
        val cleared = a.copy(shop = a.shop.copy(offerQueue = emptyList()))
        assertTrue(skus(Actions.exitOffer(cleared.copy(nowEpochSec = cleared.nowEpochSec + HOUR), data).ok()).isEmpty())
        assertEquals(
            listOf("pack_wait"),
            skus(Actions.exitOffer(cleared.copy(nowEpochSec = (ShopTestSupport.DAY0 + 1) * DAY + 5), data).ok()),
        )
    }

    // ───── 台帳に依存しない / R3 ─────

    @Test
    fun offerEngineNeverReadsTheLedger() {
        fun flow(ledger: Ledger): GameState {
            var s = base().let { it.copy(shop = it.shop.copy(ledger = ledger)) }
            s = Actions.beginSession(s, data).ok()
            s = castleUp(s, 6)
            s = Actions.fakePurchase(s, "gems_m", data).ok()
            s = ShopHooks.onEvents(s, listOf(GameEvent.PlayerDefeated(1)), data)
            return s
        }
        val big =
            Ledger(
                records = (1..50).map { LedgerRecord(it.toLong(), "gems_s", 160, "login") },
                count = 50,
                totalYen = 8000,
            )
        val a = flow(Ledger())
        val b = flow(big)
        assertEquals(a.shop.offerQueue, b.shop.offerQueue)
        assertEquals(a.shop.vipPoints, b.shop.vipPoints)
        assertEquals(a.gems, b.gems)
    }

    @Test
    fun splitInvarianceHoldsWithOffersAndPiggyBoundaries() {
        var s = base()
        val castle = castleSlot
        val farm = s.slots.first { it.type == BuildingType.FARM }.slotId
        s =
            s.copy(
                timers =
                    listOf(
                        TimerJob(1, TimerKind.BUILD, castle, 6, s.nowEpochSec, s.nowEpochSec + 500),
                        TimerJob(2, TimerKind.BUILD, farm, 3, s.nowEpochSec, s.nowEpochSec + 900),
                    ),
                nextJobId = 3,
            )
        SplitInvariance.assertInvariant(s, 2000, data, java.util.Random(3), randomCases = 40)
    }
}
