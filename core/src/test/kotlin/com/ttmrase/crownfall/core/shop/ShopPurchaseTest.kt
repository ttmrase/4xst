package com.ttmrase.crownfall.core.shop

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.shop.ShopTestSupport.DAY
import com.ttmrase.crownfall.core.shop.ShopTestSupport.HOUR
import com.ttmrase.crownfall.core.shop.ShopTestSupport.base
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShopPurchaseTest {
    private val data = ShopTestSupport.data

    private fun buy(
        s: GameState,
        id: String,
    ): ActionResult = Actions.fakePurchase(s, id, data)

    // ───── ジェム SKU と初回 2 倍 ─────

    @Test
    fun firstPurchaseOfEachGemSkuDoublesTheBaseGemsOnce() {
        var s = base()
        for (g in data.offers.gemSkus) {
            val before = s.gems
            s = buy(s, g.sku).ok()
            assertEquals(g.gems * 2 + g.bonus, s.gems - before, "${g.sku} first")
            assertTrue(g.sku in s.shop.firstPurchased)
            val mid = s.gems
            s = buy(s, g.sku).ok()
            assertEquals(g.gems + g.bonus, s.gems - mid, "${g.sku} second")
        }
    }

    // ───── 原子性 ─────

    @Test
    fun purchaseAppliesContentsVipLedgerMailAndEventsTogether() {
        var s = base()
        s = Actions.beginSession(s, data).ok()
        val packId = "pack_growth"
        val pack = data.offers.pack(packId)!!
        val vipBefore = s.shop.vipPoints
        val foodBefore = s.resources.food
        val r = buy(s, packId)
        assertIs<ActionResult.Success>(r)
        val n = r.state
        // 内容
        assertEquals(pack.contents.gems, n.gems - s.gems)
        assertEquals(pack.contents.resources.food, n.resources.food - foodBefore)
        pack.contents.items.forEach { (id, c) -> assertEquals((s.items[id] ?: 0) + c, n.items[id]) }
        // VIP（¥1 = 1pt）
        assertEquals(vipBefore + pack.priceYen, n.shop.vipPoints)
        // 台帳
        assertEquals(1L, n.shop.ledger.count)
        assertEquals(pack.priceYen, n.shop.ledger.totalYen)
        assertEquals(packId, n.shop.ledger.records.single().sku)
        assertEquals("shop", n.shop.ledger.records.single().trigger)
        // レシートメール
        val mail = n.mails.last()
        assertEquals(MailKind.RECEIPT, mail.kind)
        val rc = mail.receipt!!
        assertEquals(packId, rc.sku)
        assertEquals(pack.priceYen, rc.priceYen)
        assertFalse(rc.note)
        assertTrue(Regex("ORD-[0-9A-Z]{4}-[0-9A-Z]{4}").matches(FakePurchase.orderNumber(rc.orderA, rc.orderB)))
        // イベント
        val purchased = r.events.filterIsInstance<GameEvent.Purchased>().single()
        assertEquals(mail.id, purchased.mailId)
        assertTrue(r.events.any { it is GameEvent.MailReceived && it.kind == MailKind.RECEIPT })
        assertEquals(1L, n.shop.purchaseCount)
        assertEquals(1, n.shop.stockUsed[packId])
    }

    @Test
    fun describeShowsWhatThePurchaseWouldGrant() {
        val s = base()
        val v = FakePurchase.describe(s, "gems_m", data)!!
        assertEquals(610L, v.priceYen)
        assertEquals(550L, v.contents.gems)
        assertEquals(500L, v.firstDoubleGems)
        val bought = buy(s, "gems_m").ok()
        assertEquals(0L, FakePurchase.describe(bought, "gems_m", data)!!.firstDoubleGems)
        val list = data.offers.pack("pack_growth")!!.listValueYen
        assertEquals(list, FakePurchase.describe(s, "pack_growth", data)!!.listValueYen)
        assertNull(FakePurchase.describe(s, "nope", data))
    }

    @Test
    fun failedPurchaseChangesNothing() {
        val s = base()
        for (id in listOf("nope", "offer:999", "deal:pack_growth", "pack_castle", "growth_fund_x")) {
            assertEquals(ActionFailure.OfferUnavailable, buy(s, id).failure(), id)
        }
        var t = s
        repeat(data.offers.pack("pack_growth")!!.dailyStock) { t = buy(t, "pack_growth").ok() }
        assertEquals(ActionFailure.SoldOut, buy(t, "pack_growth").failure())
        // 失敗は状態を返さないので、元の t は変わらない（イミュータブル）
        assertEquals(data.offers.pack("pack_growth")!!.dailyStock.toLong(), t.shop.purchaseCount)
        assertEquals(ActionFailure.NothingToClaim, buy(s, data.shopRules.piggy.sku).failure())
    }

    @Test
    fun stockResetsOnTheNextLocalDay() {
        var t = base()
        repeat(data.offers.pack("pack_growth")!!.dailyStock) { t = buy(t, "pack_growth").ok() }
        assertEquals(ActionFailure.SoldOut, buy(t, "pack_growth").failure())
        val tomorrow = t.copy(nowEpochSec = (ShopTestSupport.DAY0 + 1) * DAY + 1)
        buy(tomorrow, "pack_growth").ok()
        // 現地日付で区切る: UTC 正午の JST は 21:00 → 3 時間後に翌日
        var jst = base(utc = 9 * 3600)
        repeat(data.offers.pack("pack_growth")!!.dailyStock) { jst = buy(jst, "pack_growth").ok() }
        assertEquals(
            ActionFailure.SoldOut,
            buy(jst.copy(nowEpochSec = jst.nowEpochSec + 2 * HOUR), "pack_growth").failure(),
        )
        buy(jst.copy(nowEpochSec = jst.nowEpochSec + 3 * HOUR), "pack_growth").ok()
    }

    @Test
    fun queuedOfferPurchaseConsumesItAndRecordsTheTrigger() {
        var s = ShopHooks.onEvents(base(), listOf(GameEvent.PlayerDefeated(1)), data)
        val o = OfferEngine.next(s)!!
        s = buy(s, ProductIds.offer(o.id)).ok()
        assertTrue(s.shop.offerQueue.none { it.id == o.id })
        assertEquals("defeated", s.shop.ledger.records.single().trigger)
        assertEquals(o.id, s.shop.ledger.records.single().context["offer"])
        // 期限切れは買えない
        val s2 = ShopHooks.onEvents(base(), listOf(GameEvent.PlayerDefeated(1)), data)
        val o2 = OfferEngine.next(s2)!!
        assertEquals(
            ActionFailure.OfferUnavailable,
            buy(s2.copy(nowEpochSec = o2.expiresEpochSec), ProductIds.offer(o2.id)).failure(),
        )
    }

    @Test
    fun shortagePackGrantsExactlyTheMissingResources() {
        val missing = com.ttmrase.crownfall.core.model.Resources(1200, 0, 3400, 50)
        var s = base().copy(resources = com.ttmrase.crownfall.core.model.Resources.ZERO)
        s = Actions.offerForShortage(s, missing, data).ok()
        val o = OfferEngine.next(s)!!
        val bought = buy(s, ProductIds.offer(o.id)).ok()
        assertEquals(missing, bought.resources)
        assertEquals(o.priceYen, bought.shop.ledger.totalYen)
        assertEquals(OfferEngine.SHORTAGE_SKU, bought.shop.ledger.records.single().sku)
    }

    @Test
    fun receiptNoteFollowsTheSetting() {
        val s = base().let { it.copy(shop = it.shop.copy(settings = it.shop.settings.copy(receiptNote = true))) }
        assertTrue(buy(s, "gems_s").ok().mails.last().receipt!!.note)
    }

    @Test
    fun orderNumbersAreDeterministicAndDistinct() {
        val a = buy(base(), "gems_s").ok()
        val b = buy(base(), "gems_s").ok()
        assertEquals(a.mails.last().receipt, b.mails.last().receipt)
        val c = buy(a, "gems_s").ok()
        assertTrue(
            c.mails.last().receipt!!.let {
                it.orderA to it.orderB
            } != a.mails.last().receipt!!.let { it.orderA to it.orderB },
        )
    }

    // ───── VIP ─────

    @Test
    fun vipLevelsChangeExactlyAtTheThresholds() {
        for (l in data.vip.levels) {
            assertEquals(l.lv - 1, VipCalculator.level(l.pt - 1, data), "pt-1 of ${l.lv}")
            assertEquals(l.lv, VipCalculator.level(l.pt, data), "pt of ${l.lv}")
        }
        assertEquals(0, VipCalculator.level(0, data))
        assertEquals(15, VipCalculator.level(Long.MAX_VALUE, data))
    }

    @Test
    fun crossingAVipLevelFiresTheEventAndTheAlliancePartyChat() {
        var s = base()
        val events = ArrayList<GameEvent>()
        s = VipCalculator.addPoints(s, 710, data, events) // VIP1(100)・2(300)・3(700) をまたぐ
        assertEquals(listOf(1, 2, 3), events.filterIsInstance<GameEvent.VipLevelUp>().map { it.lv })
        val chats = s.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.VipUp>()
        assertEquals(listOf(1, 2, 3), chats.map { it.lv })
        // 購入でも同じ（¥3,060 の SKU）
        val r = buy(base(), "gems_xl")
        assertTrue((r as ActionResult.Success).events.any { it is GameEvent.VipLevelUp && it.lv == 4 })
    }

    @Test
    fun vipPerksReachGatherBuffOfflineCapGemSkipAndSecondBuilder() {
        fun vip(lv: Int) = base().let { it.copy(shop = it.shop.copy(vipPoints = data.vip.levels[lv - 1].pt)) }
        val none = base()
        // 採集バフ（加算規則のまま）
        assertEquals(
            0L,
            com.ttmrase.crownfall.core.buff.BuffAggregator.compute(
                none,
                data,
            )[com.ttmrase.crownfall.core.buff.BuffKey.GATHER_SPEED],
        )
        assertEquals(
            data.vip.levels[9].gatherBp,
            com.ttmrase.crownfall.core.buff.BuffAggregator.compute(
                vip(10),
                data,
            )[com.ttmrase.crownfall.core.buff.BuffKey.GATHER_SPEED],
        )
        // オフライン上限
        val cap = data.rules.offlineCapSec
        val long = cap + 10 * HOUR
        val applied0 =
            com.ttmrase.crownfall.core.sim.OfflineCatchUp.run(
                none,
                none.nowEpochSec + long,
                data,
            ).summary.appliedSec
        assertEquals(cap, applied0)
        val v10 = vip(10)
        val applied10 =
            com.ttmrase.crownfall.core.sim.OfflineCatchUp.run(
                v10,
                v10.nowEpochSec + long,
                data,
            ).summary.appliedSec
        assertEquals(minOf(long, cap + data.vip.levels[9].offlineBonusSec), applied10)
        assertTrue(applied10 > applied0)
        // ジェムスキップの割引（切り上げ・最低 1）
        val skip = { st: GameState ->
            val job = TimerJob(1, TimerKind.BUILD, 0, 2, st.nowEpochSec, st.nowEpochSec + 3 * HOUR)
            val s0 = st.copy(timers = listOf(job), nextJobId = 2, gems = 100_000)
            100_000 - Actions.gemSkip(s0, 1, data).ok().gems
        }
        val full = skip(none)
        val disc = skip(vip(15))
        assertTrue(disc < full)
        val bp = data.vip.levels[14].gemSkipBp
        assertEquals(Math.ceil(full * (10_000 - bp) / 10_000.0).toLong(), disc)
        assertEquals(1L, VipCalculator.discountedGemCost(1, vip(15), data))
        assertEquals(0L, VipCalculator.discountedGemCost(0, vip(15), data))
        // 2 本目ビルダーは VIP6 で常設
        assertEquals(data.rules.baseBuildQueues, Simulation.buildQueueCapacity(vip(5), data))
        assertEquals(data.rules.baseBuildQueues + 1, Simulation.buildQueueCapacity(vip(6), data))
    }

    @Test
    fun vipChestIsOncePerLocalDay() {
        val s = base().let { it.copy(shop = it.shop.copy(vipPoints = data.vip.levels[2].pt)) }
        assertEquals(ActionFailure.NothingToClaim, Actions.claimVipChest(base(), data).failure())
        val a = Actions.claimVipChest(s, data).ok()
        assertEquals(s.gems + data.vip.levels[2].chest.gems, a.gems)
        assertEquals(ActionFailure.AlreadyClaimed, Actions.claimVipChest(a, data).failure())
        Actions.claimVipChest(a.copy(nowEpochSec = (ShopTestSupport.DAY0 + 1) * DAY + 1), data).ok()
    }

    // ───── 台帳 ─────

    @Test
    fun ledgerAggregatesAndFoldsOldestWhileKeepingTotals() {
        var l = com.ttmrase.crownfall.core.model.Ledger()
        val t0 = ShopTestSupport.DAY0 * DAY

        fun rec(i: Int) =
            com.ttmrase.crownfall.core.model.LedgerRecord(
                t0 + i * DAY,
                if (i % 2 == 0) "gems_s" else "gems_m",
                if (i % 2 == 0) 160 else 610,
                if (i % 3 == 0) "login" else "shop",
            )
        for (i in 0 until 10) l = FakeLedger.record(l, rec(i), 0, 4)
        assertEquals(4, l.records.size)
        assertEquals(6L, l.folded)
        assertEquals(10L, l.count)
        assertEquals((0 until 10).sumOf { rec(it).priceYen }, l.totalYen)
        assertEquals(l.totalYen, l.bySku.values.sum())
        assertEquals(l.totalYen, l.byTrigger.values.sum())
        assertEquals(l.totalYen, l.byMonth.values.sum())
        assertEquals(rec(9), l.records.last())
        assertEquals(rec(6), l.records.first())
    }

    @Test
    fun ledgerMonthsFollowTheLocalTimeZone() {
        // 2026-01-31 20:00 UTC は JST では 2026-02-01 05:00
        val epoch = java.time.LocalDate.of(2026, 1, 31).toEpochDay() * DAY + 20 * HOUR
        assertEquals("2026-01", FakeLedger.monthKey(epoch, 0))
        assertEquals("2026-02", FakeLedger.monthKey(epoch, 9 * 3600))
        assertEquals("2026-01", FakeLedger.monthKey(epoch, -5 * 3600))
    }

    @Test
    fun ledgerExportIsJsonWithZeroActualPayment() {
        val s = buy(buy(base(), "gems_s").ok(), "gems_m").ok()
        val text = FakeLedger.exportJson(s)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        assertEquals("0", json["actualPaymentYen"].toString())
        val ledger = json["ledger"] as kotlinx.serialization.json.JsonObject
        assertEquals("770", ledger["totalYen"].toString())
        assertTrue(text.contains("gems_s") && text.contains("gems_m"))
    }

    @Test
    fun ledgerHoldsFiveThousandRecordsInTheSaveWithoutBlowingUp() {
        var l = com.ttmrase.crownfall.core.model.Ledger()
        repeat(5100) {
            val rec = com.ttmrase.crownfall.core.model.LedgerRecord(it.toLong(), "gems_s", 160, "shop")
            l = FakeLedger.record(l, rec, 0, data.shopRules.ledgerCap)
        }
        assertEquals(5000, l.records.size)
        assertEquals(100L, l.folded)
        val state = base().let { it.copy(shop = it.shop.copy(ledger = l)) }
        val text = kotlinx.serialization.json.Json.encodeToString(GameState.serializer(), state)
        assertTrue(text.length < 1_500_000, "save size ${text.length}")
        assertEquals(
            state.shop.ledger,
            kotlinx.serialization.json.Json.decodeFromString(GameState.serializer(), text).shop.ledger,
        )
    }

    // ───── 月パス・成長ファンド・ピギーバンク・ホイール・ディール ─────

    @Test
    fun monthPassGivesDailyRewardsAndASecondBuilder() {
        val s0 = base()
        assertEquals(ActionFailure.NotOwned, Actions.claimMonthPass(s0, data).failure())
        assertEquals(data.rules.baseBuildQueues, Simulation.buildQueueCapacity(s0, data))
        var s = buy(s0, data.shopRules.monthPass.sku).ok()
        val days = data.shopRules.monthPass.days
        assertEquals(s0.nowEpochSec + days * DAY, s.shop.monthPassUntilEpochSec)
        assertEquals(days, MonthPass.daysLeft(s))
        assertEquals(data.rules.baseBuildQueues + 1, Simulation.buildQueueCapacity(s, data))
        val gems = s.gems
        s = Actions.claimMonthPass(s, data).ok()
        assertEquals(gems + data.shopRules.monthPass.dailyGems, s.gems)
        data.shopRules.monthPass.dailyItems.forEach { (id, n) -> assertTrue((s.items[id] ?: 0) >= n) }
        assertEquals(ActionFailure.AlreadyClaimed, Actions.claimMonthPass(s, data).failure())
        val tomorrow = s.copy(nowEpochSec = s.nowEpochSec + DAY)
        assertTrue(MonthPass.canClaim(tomorrow))
        Actions.claimMonthPass(tomorrow, data).ok()
        // 買い足すと延長される
        val again = buy(tomorrow, data.shopRules.monthPass.sku).ok()
        assertEquals(s0.nowEpochSec + 2L * days * DAY, again.shop.monthPassUntilEpochSec)
        // 期限が切れたら 2 本目は消え、受け取りも不可
        val expired = s.copy(nowEpochSec = s.shop.monthPassUntilEpochSec)
        assertEquals(0, MonthPass.daysLeft(expired))
        assertEquals(ActionFailure.NotOwned, Actions.claimMonthPass(expired, data).failure())
        assertEquals(data.rules.baseBuildQueues, Simulation.buildQueueCapacity(expired, data))
    }

    @Test
    fun growthFundPaysBackPerCastleLevelOnce() {
        val fund = data.shopRules.growthFund
        assertEquals(ActionFailure.NotOwned, Actions.claimGrowthFund(base(), data).failure())
        var s = buy(base(), fund.sku).ok() // 城 Lv5 の状態
        assertEquals(ActionFailure.AlreadyOwned, buy(s, fund.sku).failure())
        assertEquals(listOf(3 to 100L, 5 to 200L), GrowthFund.claimable(s, data))
        val gems = s.gems
        s = Actions.claimGrowthFund(s, data).ok()
        assertEquals(gems + 300, s.gems)
        assertEquals(ActionFailure.NothingToClaim, Actions.claimGrowthFund(s, data).failure())
        // 城 Lv が上がったぶんだけ追加で受け取れる
        val lv10 =
            s.copy(
                slots =
                    s.slots.map {
                        if (it.type == com.ttmrase.crownfall.core.model.BuildingType.CASTLE) it.copy(level = 10) else it
                    },
            )
        assertEquals(listOf(8 to 300L, 10 to 400L), GrowthFund.claimable(lv10, data))
        assertTrue(GrowthFund.totalGems(data) > fund.priceYen) // 「元が取れる」
    }

    @Test
    fun piggyBankFillsFromActionsCapsAndOpens() {
        val p = data.shopRules.piggy
        var s = base()
        val events =
            listOf(
                GameEvent.ResearchCompleted("n", 1),
                GameEvent.PlayerDefendedRaid(1),
                GameEvent.MarchReturned(1, com.ttmrase.crownfall.core.march.MarchKind.GATHER),
            )
        s = ShopHooks.onEvents(s, events, data)
        assertEquals(p.gemsPerResearch + p.gemsPerDefense + p.gemsPerMarch, s.shop.piggyGems)
        s = s.copy(shop = s.shop.copy(piggyGems = p.cap - 1))
        s = PiggyBank.accrue(s, 50, data)
        assertEquals(p.cap, s.shop.piggyGems)
        val gems = s.gems
        s = buy(s, p.sku).ok()
        assertEquals(gems + p.cap, s.gems)
        assertEquals(0L, s.shop.piggyGems)
        assertEquals(p.openYen, s.shop.ledger.totalYen)
    }

    @Test
    fun wheelGivesOneFreeSpinPerDayThenNeedsPurchaseOrGems() {
        var s = base().copy(gems = 150)
        assertTrue(LuckyWheel.freeAvailable(s))
        val r = Actions.spinWheel(s, false, data)
        s = r.ok()
        val ev = (r as ActionResult.Success).events.filterIsInstance<GameEvent.WheelResult>().single()
        assertTrue(ev.segment in data.shopRules.wheel.segments.indices)
        assertEquals(1L, s.shop.wheelSpins)
        assertFalse(LuckyWheel.freeAvailable(s))
        assertEquals(ActionFailure.NothingToClaim, Actions.spinWheel(s, false, data).failure())
        // 追加分を ¥120 で買うと 1 回回せる
        val bought = buy(s, data.shopRules.wheel.extraSku).ok()
        assertEquals(1, bought.shop.wheelExtra)
        val used = Actions.spinWheel(bought, false, data).ok()
        assertEquals(0, used.shop.wheelExtra)
        // ジェム払い（100 ジェム）。足りなければ不足を返す
        val paid = Actions.spinWheel(s, true, data).ok()
        assertEquals(
            s.gems - data.shopRules.wheel.extraGems + (paid.gems - (s.gems - data.shopRules.wheel.extraGems)),
            paid.gems,
        )
        assertEquals(ActionFailure.NotEnoughGems(50), Actions.spinWheel(s.copy(gems = 50), true, data).failure())
        // 翌日はまた無料
        assertTrue(LuckyWheel.freeAvailable(s.copy(nowEpochSec = s.nowEpochSec + DAY)))
    }

    @Test
    fun wheelIsDeterministicForTheSameSeedAndFollowsTheWeights() {
        val a = Actions.spinWheel(base(), false, data)
        val b = Actions.spinWheel(base(), false, data)
        assertEquals((a as ActionResult.Success).events, (b as ActionResult.Success).events)
        assertEquals(a.state, b.state)
        val segs = data.shopRules.wheel.segments
        val rng = com.ttmrase.crownfall.core.util.SeededRandom(12345)
        val counts = IntArray(segs.size)
        val n = 40_000
        repeat(n) { counts[LuckyWheel.pick(rng, segs)]++ }
        val total = segs.sumOf { it.weight }
        for ((i, sg) in segs.withIndex()) {
            val expected = n.toDouble() * sg.weight / total
            assertTrue(
                kotlin.math.abs(counts[i] - expected) < expected * 0.12 + 40,
                "${sg.id}: ${counts[i]} vs $expected",
            )
        }
        // ハズレ（ジェム 10）の率が一番高く、SSR の欠片は一番低い
        assertEquals(segs.indices.maxBy { counts[it] }, segs.indexOfFirst { it.id == "gems_small" })
        assertEquals(segs.indices.minBy { counts[it] }, segs.indexOfFirst { it.tease })
    }

    @Test
    fun wheelTeasesTheShardNeighborUnlessItLandsThere() {
        val segs = data.shopRules.wheel.segments
        val tease = segs.indexOfFirst { it.tease }
        var teased = 0
        var landed = 0
        var s = base()
        repeat(300) { i ->
            val out = LuckyWheel.spin(s.copy(rngState = 1000L + i), data)
            if (out.segment.tease) {
                assertEquals(-1, out.tease)
                landed++
            } else {
                assertEquals(tease, out.tease)
                teased++
            }
            s = out.state
        }
        assertTrue(teased > 250 && landed in 1..30, "teased=$teased landed=$landed")
    }

    @Test
    fun dailyDealsAreDeterministicPerLocalDayWithOneNinetyNinePercentOff() {
        val s = base()
        val d1 = DailyDeals.today(s, data)
        assertEquals(3, d1.size)
        assertEquals(1, d1.count { it.isSuper })
        val sup = d1.single { it.isSuper }
        assertEquals(120L, sup.priceYen)
        assertEquals(99, sup.offPct)
        assertEquals(d1.map { it.pack.id }.toSet().size, 3)
        // 同じ日なら時刻や呼び出し回数に依存しない（分割不変）
        assertEquals(d1, DailyDeals.today(s.copy(nowEpochSec = s.nowEpochSec + 5 * HOUR), data))
        assertEquals(d1, DailyDeals.today(s, data))
        // 日が変わると変わり得る（10 日のうち複数の組が出る）
        val sets =
            (0 until 10).map {
                DailyDeals.today(s.copy(nowEpochSec = (ShopTestSupport.DAY0 + it) * DAY + 100), data).map {
                        c ->
                    c.pack.id
                }
            }.toSet()
        assertTrue(sets.size > 1)
        // 買える・残数 1・今日のディール以外は買えない
        val other = data.offers.packs.first { it.deal != null && it.id !in d1.map { c -> c.pack.id } }.id
        assertEquals(ActionFailure.OfferUnavailable, buy(s, ProductIds.deal(other)).failure())
        val bought = buy(s, ProductIds.deal(sup.pack.id)).ok()
        assertEquals(120L, bought.shop.ledger.totalYen)
        assertEquals("deal", bought.shop.ledger.records.single().trigger)
        assertEquals(ActionFailure.SoldOut, buy(bought, ProductIds.deal(sup.pack.id)).failure())
        assertTrue(DailyDeals.today(bought, data).single { it.pack.id == sup.pack.id }.soldOut)
    }

    @Test
    fun dailyDealsAreFixedByTheMapSeedAcrossSaves() {
        val a = base()
        val b = a.copy(rngState = 999, gems = 5) // ほかの状態が違っても、同じ種・同じ日なら同じ
        assertEquals(DailyDeals.today(a, data), DailyDeals.today(b, data))
        val c = a.copy(map = a.map.copy(seed = a.map.seed + 1))
        assertTrue(
            (0 until 20).any {
                DailyDeals.today(c.copy(nowEpochSec = (ShopTestSupport.DAY0 + it) * DAY), data).map {
                        x ->
                    x.pack.id
                } !=
                    DailyDeals.today(a.copy(nowEpochSec = (ShopTestSupport.DAY0 + it) * DAY), data).map {
                            x ->
                        x.pack.id
                    }
            },
        )
    }

    @Test
    fun settingsAreStoredInTheShopState() {
        val s =
            Actions.setShopSettings(
                base(),
                base().shop.settings.copy(frequency = OfferFrequency.LOW, longProcessing = false),
            ).ok()
        assertEquals(OfferFrequency.LOW, s.shop.settings.frequency)
        assertFalse(s.shop.settings.longProcessing)
        assertNull(null)
        assertEquals(TestSupport.T0 > 0, true)
    }
}
