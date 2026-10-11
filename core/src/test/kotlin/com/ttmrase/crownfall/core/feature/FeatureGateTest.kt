package com.ttmrase.crownfall.core.feature

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.notify.NoticeCategory
import com.ttmrase.crownfall.core.notify.NotificationPlanner
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.ShopTestSupport
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.NewGame
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 段階解放（M11 T11.1）: 解放表・行動の拒否・既存セーブの初期化・各スケジューラのゲート・R3。 */
class FeatureGateTest {
    private val data = TestSupport.data
    private val day = 86_400L

    /** 城・城壁・倉庫を [lv] にして、地図の襲撃を遠くへ送った新規ゲーム（資源は潤沢）。 */
    private fun at(
        lv: Int,
        now: Long = 20_000L * day,
    ): GameState {
        val s = TestSupport.advanced(level = lv, castle = lv, now = now).copy(resources = TestSupport.RICH)
        return s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
    }

    private fun trainable() = TestSupport.withLevel(at(2), BuildingType.BARRACKS, 1)

    private fun failure(r: ActionResult): ActionFailure {
        assertIs<ActionResult.Failure>(r, "expected Failure but was $r")
        return r.reason
    }

    @Test
    fun theUnlockTableIsPinnedToTheDirectorsDecision() {
        val expected =
            mapOf(
                Feature.TRAINING to 2,
                Feature.QUESTS to 3,
                Feature.SHOP to 3,
                Feature.ALLIANCE to 4,
                Feature.WORLD_MAP to 5,
                Feature.RAIDS to 5,
                Feature.RESEARCH to 6,
                Feature.WHEEL to 6,
                Feature.PIGGY to 6,
                Feature.HEROES to 7,
                Feature.WORLD_CHAT to 8,
                Feature.EVENTS to 8,
                Feature.RALLY to 10,
            )
        assertEquals(expected, Feature.entries.associateWith { FeatureGate.requiredLv(data, it) })
    }

    @Test
    fun aFeatureIsUnlockedExactlyAtItsLevel() {
        for (f in Feature.entries) {
            val need = FeatureGate.requiredLv(data, f)
            assertFalse(FeatureGate.unlockedAt(data, need - 1, f), "$f at ${need - 1}")
            assertTrue(FeatureGate.unlockedAt(data, need, f), "$f at $need")
        }
        val s = TestSupport.newGame()
        assertTrue(FeatureGate.unlockedSet(s, data).isEmpty())
        assertEquals(Feature.TRAINING, FeatureGate.next(s, data))
        assertEquals(null, FeatureGate.next(TestSupport.newGame(castle = 10), data))
        assertEquals(listOf(Feature.QUESTS, Feature.SHOP), FeatureGate.newlyUnlocked(data, 2, 3))
        assertEquals(listOf(Feature.RESEARCH, Feature.WHEEL, Feature.PIGGY), FeatureGate.newlyUnlocked(data, 5, 6))
        assertEquals(Feature.entries.size, FeatureGate.newlyUnlocked(data, 1, 25).size)
    }

    @Test
    fun lockedActionsAreRefusedWithTheRequiredLevel() {
        val s = TestSupport.newGame().copy(resources = TestSupport.RICH, gems = 10_000)

        fun lock(
            r: ActionResult,
            f: Feature,
        ) = assertEquals(ActionFailure.FeatureLocked(f, FeatureGate.requiredLv(data, f)), failure(r), "$f")
        lock(Actions.startTraining(s, "infantry_t1", 1, data), Feature.TRAINING)
        lock(Actions.startHeal(s, mapOf("infantry_t1" to 1L), data), Feature.TRAINING)
        lock(Actions.startResearch(s, data.research.keys.first(), data), Feature.RESEARCH)
        lock(Actions.summon(s, com.ttmrase.crownfall.core.hero.SummonKind.NORMAL, 1, data), Feature.HEROES)
        lock(Actions.helpAll(s, data), Feature.ALLIANCE)
        lock(Actions.requestHelp(s, 1, data), Feature.ALLIANCE)
        lock(Actions.submitFreeText(s, "hi", ChatKeys.ALLIANCE, data), Feature.ALLIANCE)
        lock(Actions.submitFreeText(s, "hi", ChatKeys.dm("kaede"), data), Feature.WORLD_CHAT)
        lock(Actions.claimQuest(s, data.quests.chapters[0].id, data), Feature.QUESTS)
        lock(Actions.claimQuest(s, data.quests.alliance[0].id, data), Feature.RALLY)
        lock(Actions.claimDailyChest(s, 1, data), Feature.QUESTS)
        lock(Actions.fakePurchase(s, "gems_s", data), Feature.SHOP)
        lock(Actions.spinWheel(s, false, data), Feature.WHEEL)
        lock(Actions.offerForSecondBuilder(s, data), Feature.SHOP)
        lock(Actions.offerForShortage(s, Resources(food = 10), data), Feature.SHOP)
    }

    @Test
    fun marchesNeedTheMapAndFortsNeedLevelTen() {
        val s = at(4)
        val target = com.ttmrase.crownfall.core.map.TileRef(0, 0)
        val none = emptyMap<String, Long>()
        for (kind in listOf(
            com.ttmrase.crownfall.core.march.MarchKind.GATHER,
            com.ttmrase.crownfall.core.march.MarchKind.ATTACK,
            com.ttmrase.crownfall.core.march.MarchKind.SCOUT,
        )) {
            assertEquals(
                ActionFailure.FeatureLocked(Feature.WORLD_MAP, 5),
                failure(Actions.startMarch(s, kind, target, none, emptyList(), data)),
            )
        }
        assertEquals(
            ActionFailure.FeatureLocked(Feature.RALLY, 10),
            failure(
                Actions.startMarch(
                    at(9),
                    com.ttmrase.crownfall.core.march.MarchKind.RALLY,
                    target,
                    none,
                    emptyList(),
                    data,
                ),
            ),
        )
    }

    @Test
    fun unlockedActionsWorkAndMarkTheFirstUse() {
        val s = trainable()
        val trained = Actions.startTraining(s, "infantry_t1", 1, data)
        assertIs<ActionResult.Success>(trained)
        assertTrue(Feature.TRAINING.id in trained.state.features.firstUsed)
        assertTrue(Feature.RESEARCH.id !in trained.state.features.firstUsed)
    }

    @Test
    fun theFirstUseRewardIsClaimedOnceAndOnlyAfterTheFirstAction() {
        val s0 = trainable()
        assertFalse(FeatureGate.rewardClaimable(s0, data, Feature.TRAINING))
        assertEquals(ActionFailure.NothingToClaim, failure(Actions.claimFeatureReward(s0, Feature.TRAINING, data)))
        val s1 = (Actions.startTraining(s0, "infantry_t1", 1, data) as ActionResult.Success).state
        assertTrue(FeatureGate.rewardClaimable(s1, data, Feature.TRAINING))
        val reward: PackContents = FeatureGate.reward(data, Feature.TRAINING)
        val s2 = (Actions.claimFeatureReward(s1, Feature.TRAINING, data) as ActionResult.Success).state
        assertEquals(s1.gems + reward.gems, s2.gems)
        assertTrue(Feature.TRAINING.id in s2.features.firstUseClaimed)
        assertEquals(ActionFailure.AlreadyClaimed, failure(Actions.claimFeatureReward(s2, Feature.TRAINING, data)))
        // 未解放の機能の報酬は受け取れない
        assertEquals(
            ActionFailure.FeatureLocked(Feature.HEROES, 7),
            failure(Actions.claimFeatureReward(s2, Feature.HEROES, data)),
        )
    }

    @Test
    fun celebratingIsRecordedOnceAndOnlyForUnlockedFeatures() {
        val s = at(3)
        assertEquals(
            ActionFailure.FeatureLocked(Feature.ALLIANCE, 4),
            failure(Actions.markFeatureCelebrated(s, Feature.ALLIANCE, data)),
        )
        val c = (Actions.markFeatureCelebrated(s, Feature.SHOP, data) as ActionResult.Success).state
        assertEquals(setOf(Feature.SHOP.id), c.features.celebrated)
        assertEquals(c, (Actions.markFeatureCelebrated(c, Feature.SHOP, data) as ActionResult.Success).state)
    }

    // ───── セーブの初期化 ─────

    @Test
    fun aNewGameStartsInitializedWithNothingToCelebrate() {
        val s = NewGame.create(data, 1_000_000L, 1L)
        assertTrue(s.features.initialized)
        assertTrue(s.features.celebrated.isEmpty() && s.features.firstUsed.isEmpty())
        val t = Simulation.tick(s, 60, data).state
        assertEquals(s.features, t.features)
    }

    @Test
    fun anOldSaveMarksEverythingUnlockedAsCelebratedAndClaimedOnTheFirstTick() {
        val old = at(5).copy(features = com.ttmrase.crownfall.core.model.FeatureProgress())
        assertFalse(old.features.initialized)
        val r = Simulation.tick(old, 1, data)
        val unlocked = FeatureGate.unlockedSet(old, data).map { it.id }.toSet()
        assertTrue(unlocked.isNotEmpty())
        assertEquals(unlocked, r.state.features.celebrated)
        assertEquals(unlocked, r.state.features.firstUsed)
        assertEquals(unlocked, r.state.features.firstUseClaimed)
        assertTrue(r.state.features.initialized)
        assertTrue(r.events.none { it is GameEvent.FeatureUnlocked }, "no unlock flood for an old save")
        for (f in unlocked) assertFalse(
            FeatureGate.rewardClaimable(r.state, data, Feature.entries.first { it.id == f }),
        )
        // 解放前の機能はそのまま、あとで通常どおり解放される
        assertFalse(Feature.RESEARCH.id in r.state.features.celebrated)
    }

    @Test
    fun initializationIsIdempotentAndKeepsSplitInvariance() {
        val old = at(4).copy(features = com.ttmrase.crownfall.core.model.FeatureProgress())
        val once = Simulation.tick(old, 100, data).state
        val split = Simulation.tick(Simulation.tick(old, 40, data).state, 60, data).state
        assertEquals(once, split)
    }

    // ───── 解放の瞬間 ─────

    @Test
    fun finishingACastleUpgradeEmitsTheUnlockEventsInOrder() {
        val s = Actions.startUpgrade(at(5), TestSupport.slotOf(at(5), BuildingType.CASTLE), data)
        assertIs<ActionResult.Success>(s)
        val dur = s.state.timers.single().endEpochSec - s.state.nowEpochSec
        val before = Simulation.tick(s.state, dur - 1, data)
        assertTrue(before.events.none { it is GameEvent.FeatureUnlocked })
        val r = Simulation.tick(before.state, 1, data)
        val unlocked = r.events.filterIsInstance<GameEvent.FeatureUnlocked>().map { it.feature }
        assertEquals(listOf(Feature.RESEARCH, Feature.WHEEL, Feature.PIGGY), unlocked)
        assertTrue(Feature.RESEARCH.id !in r.state.features.celebrated, "celebration is recorded by the UI")
    }

    @Test
    fun unlockingStaysSplitInvariantAcrossTheCastleBoundary() {
        val base = at(3)
        val s =
            (
                Actions.startUpgrade(
                    base,
                    TestSupport.slotOf(base, BuildingType.CASTLE),
                    data,
                ) as ActionResult.Success
            ).state
        val dur = s.timers.single().endEpochSec - s.nowEpochSec
        SplitInvariance.assertInvariant(s, dur + 7_200, data, java.util.Random(11), randomCases = 40)
    }

    @Test
    fun theShopUnlockQueuesTheFirstDoubleGemOfferAtTheMomentOfUnlocking() {
        val base = at(2).copy(rngState = 3)
        val s =
            (
                Actions.startUpgrade(
                    base,
                    TestSupport.slotOf(base, BuildingType.CASTLE),
                    data,
                ) as ActionResult.Success
            ).state
        val dur = s.timers.single().endEpochSec - s.nowEpochSec
        val r = Simulation.tick(s, dur, data)
        assertTrue(Feature.SHOP.id !in FeatureGate.unlockedSet(s, data).map { it.id }.toSet())
        val offers = OfferEngine.active(r.state)
        assertTrue(offers.any { it.trigger == "featureUnlock" && it.sku == "gems_m" }, offers.map { it.sku }.toString())
    }

    // ───── スケジューラのゲート ─────

    @Test
    fun theAllianceDoesNotRunUntilLevelFourAndStartsAtTheUnlockTime() {
        val base = at(3).copy(rngState = 5)
        val idle = Simulation.tick(base, 2 * day, data).state
        assertEquals(0L, idle.alliance.seed)
        assertTrue(idle.alliance.pendingChat.isEmpty() && idle.alliance.helpPool.isEmpty())
        val up =
            (
                Actions.startUpgrade(
                    base,
                    TestSupport.slotOf(base, BuildingType.CASTLE),
                    data,
                ) as ActionResult.Success
            ).state
        val done = up.timers.single().endEpochSec
        val r = Simulation.tick(up, done - up.nowEpochSec + 600, data).state
        assertEquals(done, r.alliance.startEpochSec)
        assertNotEquals(0L, r.alliance.seed)
    }

    @Test
    fun raidsAreNotScheduledBeforeLevelFiveAndStartAfterTheFirstDelayOnUnlock() {
        val s = TestSupport.advanced(level = 4, castle = 4, now = 20_000L * day).copy(resources = TestSupport.RICH)
        val idle = Simulation.tick(s, 3 * day, data).state
        assertEquals(0L, idle.map.nextRaidEpochSec)
        val up =
            (
                Actions.startUpgrade(
                    s,
                    TestSupport.slotOf(s, BuildingType.CASTLE),
                    data,
                ) as ActionResult.Success
            ).state
        val done = up.timers.single().endEpochSec
        val r = Simulation.tick(up, done - up.nowEpochSec, data).state
        assertEquals(done + data.mapRules.raid.firstDelaySec, r.map.nextRaidEpochSec)
    }

    @Test
    fun aStalePastDueRaidIsPushedBackWhenRaidsUnlock() {
        val s0 = TestSupport.advanced(level = 4, castle = 4, now = 20_000L * day).copy(resources = TestSupport.RICH)
        val s = s0.copy(map = s0.map.copy(nextRaidEpochSec = s0.nowEpochSec - 10))
        val up =
            (
                Actions.startUpgrade(
                    s,
                    TestSupport.slotOf(s, BuildingType.CASTLE),
                    data,
                ) as ActionResult.Success
            ).state
        val done = up.timers.single().endEpochSec
        val r = Simulation.tick(up, done - up.nowEpochSec, data).state
        assertEquals(done + data.mapRules.raid.firstDelaySec, r.map.nextRaidEpochSec)
        assertTrue(r.mails.isEmpty())
    }

    @Test
    fun weeklyEventsDoNotScoreOrStartBeforeLevelEight() {
        val s = at(7)
        val r = Simulation.tick(s, 8 * day, data)
        assertEquals(-1L, r.state.event.weekId)
        val hunted = com.ttmrase.crownfall.core.sim.Tracking.apply(s, listOf(GameEvent.HuntWon(false, 1)), data)
        assertEquals(0L, hunted.event.selfScore)
        val u = at(8)
        assertNotEquals(-1L, Simulation.tick(u, 60, data).state.event.weekId)
    }

    @Test
    fun noAutomaticOffersAppearBeforeTheShopAndHeroPacksWaitForHeroes() {
        val low = ShopTestSupport.base(castle = 2)
        val fired = OfferEngine.fire(low, "castleUp", com.ttmrase.crownfall.core.shop.TriggerCtx(lv = 6), data)
        assertTrue(OfferEngine.active(fired).isEmpty())
        val six = ShopTestSupport.base(castle = 6)
        val packs = data.offers.packs.filter { it.feature == "heroes" }
        assertTrue(packs.isNotEmpty())
        val offered =
            packs.map { p ->
                OfferEngine.enqueue(six, OfferEngine.makeOffer(six, p, "test", data), 0, data)
            }
        assertTrue(offered.all { OfferEngine.active(it).isEmpty() })
        val seven = ShopTestSupport.base(castle = 7)
        val ok = OfferEngine.enqueue(seven, OfferEngine.makeOffer(seven, packs.first(), "test", data), 0, data)
        assertTrue(OfferEngine.active(ok).isNotEmpty())
    }

    @Test
    fun notificationsAreNotPlannedForLockedFeatures() {
        val s0 = ShopTestSupport.base(castle = 2)
        val withOffer = s0.copy(shop = s0.shop.copy(offerQueue = listOf(offerFor(s0))))
        val raidy = withOffer.copy(map = withOffer.map.copy(nextRaidEpochSec = withOffer.nowEpochSec + 3_600))
        val cats = NotificationPlanner.plan(raidy, data).map { it.type.category }.toSet()
        assertFalse(NoticeCategory.RAID in cats)
        assertFalse(NoticeCategory.OFFER in cats)
        val open = ShopTestSupport.base(castle = 5)
        val openRaid = open.copy(map = open.map.copy(nextRaidEpochSec = open.nowEpochSec + 3_600))
        assertTrue(NoticeCategory.RAID in NotificationPlanner.plan(openRaid, data).map { it.type.category })
    }

    private fun offerFor(s: GameState) =
        OfferEngine.makeOffer(s, data.offers.packs.first { it.id == "pack_res_s" }, "test", data)
            .copy(id = 1, expiresEpochSec = s.nowEpochSec + 7_200)

    // ───── クエスト ─────

    @Test
    fun dailiesOnlyListUnlockedFeaturesAndChestsScaleToTheirTotal() {
        val three = at(3)
        val keys = QuestLogic.dailies(three, data).map { it.key }.toSet()
        assertEquals(setOf("build", "train", "shop"), keys)
        val total = QuestLogic.dailies(three, data).sumOf { it.points }
        val top = data.quests.chests.maxOf { it.points }
        assertTrue(total < top)
        val thresholds = data.quests.chests.map { QuestLogic.chestThreshold(it, three, data) }
        assertEquals(total, thresholds.last(), "doing everything reaches the top chest")
        assertEquals(thresholds, thresholds.sorted())
        assertTrue(thresholds.all { it in 1..total })
        // 全項目が出ていれば元の閾値のまま
        val all = at(10)
        assertEquals(
            data.quests.chests.map { it.points },
            data.quests.chests.map { QuestLogic.chestThreshold(it, all, data) },
        )
        assertEquals(data.quests.dailies.size, QuestLogic.dailies(all, data).size)
    }

    @Test
    fun aScaledChestCanBeClaimedWithTheReducedPoints() {
        var s = at(3)
        val dailies = QuestLogic.dailies(s, data)
        val q = QuestLogic.rollDaily(s).quest
        s = QuestLogic.rollDaily(s).copy(quest = q.copy(dailyClaimed = dailies.map { it.id }.toSet()))
        assertEquals(dailies.sumOf { it.points }, QuestLogic.dailyPoints(s, data))
        val top = data.quests.chests.maxBy { it.points }
        assertTrue(QuestLogic.claimableChests(s, data).any { it.tier == top.tier })
        assertIs<ActionResult.Success>(Actions.claimDailyChest(s, top.tier, data))
    }

    // ───── 建物の解放 ─────

    @Test
    fun lockedBuildingsReportTheCastleLevelTheyNeed() {
        val s = TestSupport.newGame().copy(resources = TestSupport.RICH)
        val barracks = TestSupport.slotOf(s, BuildingType.BARRACKS)
        val r = failure(Actions.startUpgrade(s, barracks, data))
        assertEquals(
            ActionFailure.PrereqNotMet(listOf(com.ttmrase.crownfall.core.model.Prereq(BuildingType.CASTLE, 2))),
            r,
        )
        val s2 = TestSupport.newGame(castle = 2).copy(resources = TestSupport.RICH)
        assertIs<ActionResult.Success>(Actions.startUpgrade(s2, barracks, data))
        // 建て終わった建物は、城 Lv が足りなくても使える（既存のセーブ）
        val built = TestSupport.withLevel(s, BuildingType.BARRACKS, 1)
        val cur = built.slots.first { it.type == BuildingType.BARRACKS }
        assertEquals(1, cur.level)
    }
}
