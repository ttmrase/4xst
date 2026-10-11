package com.ttmrase.crownfall.core.feature

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.EventState
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.notify.NoticeType
import com.ttmrase.crownfall.core.notify.NotificationPlanner
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.PiggyBank
import com.ttmrase.crownfall.core.shop.ProductIds
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Director の REVIEW（T11.1 差し戻し T1〜T9）の回帰テスト。 */
class ReviewFixesTest {
    private val data = TestSupport.data
    private val day = 86_400L

    private fun at(
        lv: Int,
        now: Long = 20_000L * day,
    ): GameState {
        val s = TestSupport.advanced(level = lv, castle = lv, now = now).copy(resources = TestSupport.RICH)
        return s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
    }

    // ---- T1: 「最初の使用」は人の意図した行動でだけ付く ----
    @Test
    fun automaticOfferHousekeepingNeverMarksTheShopAsUsed() {
        val s =
            FeatureGate.initialize(
                at(3),
                data,
            ).copy(features = FeatureGate.initialize(at(3), data).features.copy(firstUsed = emptySet()))
        val refreshed = (Actions.refreshOffers(s, data) as? ActionResult.Success)?.state ?: s
        assertFalse(Feature.SHOP.id in refreshed.features.firstUsed)
        val exit = (Actions.exitOffer(s, data) as? ActionResult.Success)?.state ?: s
        assertFalse(Feature.SHOP.id in exit.features.firstUsed)
        val none = com.ttmrase.crownfall.core.model.Resources.ZERO
        val short = (Actions.offerForShortage(s, none, data) as? ActionResult.Success)?.state ?: s
        assertFalse(Feature.SHOP.id in short.features.firstUsed)
    }

    @Test
    fun openingTheShopScreenAndTheEventScreenAreTheFirstUses() {
        val base = at(8).let { it.copy(features = it.features.copy(firstUsed = emptySet())) }
        assertTrue(Feature.SHOP.id in Actions.recordShopOpened(base, data).ok().features.firstUsed)
        assertTrue(Feature.EVENTS.id in Actions.recordEventOpened(base, data).ok().features.firstUsed)
        val locked = at(2)
        assertFalse(Feature.SHOP.id in Actions.recordShopOpened(locked, data).ok().features.firstUsed)
        assertFalse(Feature.EVENTS.id in Actions.recordEventOpened(locked, data).ok().features.firstUsed)
    }

    @Test
    fun openingThePiggyIsThePiggysFirstUse() {
        var s = at(6).let { it.copy(features = it.features.copy(firstUsed = emptySet())) }
        s = PiggyBank.accrue(s, 100, data)
        val bought = Actions.fakePurchase(s, data.shopRules.piggy.sku, data).ok()
        assertTrue(Feature.PIGGY.id in bought.features.firstUsed)
    }

    // ---- T9: 購入済みの権利はゲートの対象外 ----
    @Test
    fun ownedEntitlementsCanBeClaimedEvenWhileTheShopIsLocked() {
        val s = at(1)
        val withPass = s.copy(shop = s.shop.copy(monthPassUntilEpochSec = s.nowEpochSec + 10 * day))
        assertIs<ActionResult.Success>(Actions.claimMonthPass(withPass, data))
        val withFund = s.copy(shop = s.shop.copy(growthFundOwned = true))
        val r = Actions.claimGrowthFund(withFund, data)
        // 受け取れる段が無い場合の失敗でも、FeatureLocked では断らない
        if (r is ActionResult.Failure) {
            assertFalse(
                r.reason is com.ttmrase.crownfall.core.sim.ActionFailure.FeatureLocked,
            )
        }
    }

    // ---- T2: 宝箱の閾値とデイリーの絞り込み ----
    @Test
    fun theChestBarShrinksWithTheVisibleDailies() {
        val lv3 = at(3)
        val visible = QuestLogic.dailies(lv3, data).sumOf { it.points }
        assertTrue(visible < data.quests.chests.maxOf { it.points })
        assertEquals(visible, QuestLogic.chestMax(lv3, data))
        val lv10 = at(10)
        assertEquals(data.quests.chests.maxOf { it.points }, QuestLogic.chestMax(lv10, data))
    }

    // ---- T3: 先読みの通知にもゲート ----
    @Test
    fun aFreshPlayerGetsNoNoticesForLockedFeatures() {
        val s = TestSupport.newGame(castle = 1)
        val types = NotificationPlanner.lookahead(s, data).map { it.type }.toSet()
        listOf(
            NoticeType.FREE_WHEEL,
            NoticeType.LOGIN_BONUS,
            NoticeType.DAILY_LEFT,
            NoticeType.OFFER_LAST_CALL,
        ).forEach {
            assertFalse(it in types, "$it must wait for its unlock")
        }
    }

    @Test
    fun theWheelAndLoginNoticesAppearOnceTheirFeaturesUnlock() {
        val s = TestSupport.newGame(castle = 6)
        val types = NotificationPlanner.lookahead(s, data).map { it.type }.toSet()
        assertTrue(NoticeType.FREE_WHEEL in types)
        assertTrue(NoticeType.LOGIN_BONUS in types)
    }

    @Test
    fun dailyLeftIsOnlyAskedWhileTheVisibleDailiesAreNotAllDone() {
        val rolled = QuestLogic.rollDaily(at(3))
        val done =
            rolled.copy(
                quest = rolled.quest.copy(dailyClaimed = QuestLogic.dailies(rolled, data).map { it.id }.toSet()),
            )
        assertEquals(QuestLogic.chestMax(done, data), QuestLogic.dailyPoints(done, data))
        assertFalse(NoticeType.DAILY_LEFT in NotificationPlanner.lookahead(done, data).map { it.type })
        assertTrue(NoticeType.DAILY_LEFT in NotificationPlanner.lookahead(rolled, data).map { it.type })
    }

    // ---- T4: イベントの解放で古い週の集計を捨てる ----
    @Test
    fun unlockingEventsResetsAnOldWeeksTally() {
        val s = at(7).copy(event = EventState(weekId = 3, selfScore = 999, basePower = 1))
        val castle = TestSupport.slotOf(s, BuildingType.CASTLE)
        val started = Actions.startUpgrade(s, castle, data).ok()
        val done = Simulation.tick(started, 10 * day, data).state
        assertEquals(8, FeatureGate.castleLevel(done))
        assertFalse(done.event.weekId == 3L && done.event.selfScore == 999L, "the stale tally must not survive")
    }

    // ---- T6: ラリーは RALLY、ワールドチャットは WORLD_CHAT まで作らない ----
    @Test
    fun noAiRalliesBeforeLv10AndNoWorldChatterBeforeLv8() {
        var s = at(5)
        repeat(3 * 24) { s = Simulation.tick(s, 3_600, data).state }
        assertTrue(s.alliance.rallies.isEmpty())
        assertTrue(
            s.alliance.history[ChatKeys.WORLD].orEmpty().isEmpty() &&
                s.alliance.pendingChat.none {
                    it.channel == ChatKeys.WORLD
                },
        )
    }

    // ---- T7: VIP の同盟チャットは同盟の解放から ----
    @Test
    fun vipLevelUpDoesNotPingTheAllianceChatBeforeItUnlocks() {
        val s = at(2)
        val bought = Actions.fakePurchase(OfferEngine.refresh(s, data), ProductIds.offer(0), data)
        // 購入は SHOP 未解放で断られるので、VIP pt を直接積んで確かめる
        val v = com.ttmrase.crownfall.core.shop.VipCalculator.addPoints(s, 1_000_000, data, mutableListOf())
        assertTrue(v.alliance.pendingChat.isEmpty())
        assertIs<ActionResult.Failure>(bought)
    }

    // ---- ピギーは PIGGY の解放から貯まる ----
    @Test
    fun thePiggyDoesNotFillBeforeItUnlocks() {
        assertEquals(0L, PiggyBank.accrue(at(2), 500, data).shop.piggyGems)
        assertTrue(PiggyBank.accrue(at(6), 500, data).shop.piggyGems > 0)
    }
}
