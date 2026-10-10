package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.chat.SpokenText
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.AiRally
import com.ttmrase.crownfall.core.model.AllianceRequest
import com.ttmrase.crownfall.core.model.Boost
import com.ttmrase.crownfall.core.model.BoostKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.GiftBox
import com.ttmrase.crownfall.core.model.HelpEntry
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.RequestKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AllianceActionsTest {
    private val data = TestSupport.data
    private val hour = 3600L
    private val day = 86_400L
    private val rules = data.allianceRules

    /** 現地 21:00（kaede・ponzu・sato・lin が活動中）の新規ゲーム。城 Lv10・資源潤沢。 */
    private fun at21(): GameState {
        var s = TestSupport.advanced(level = 10, castle = 10, now = 20_000L * day + 21 * hour)
        s = s.copy(rngState = 11, resources = TestSupport.RICH, gems = 1000)
        s = s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
        s = Simulation.tick(s, 0, data).state
        return s.copy(alliance = s.alliance.copy(pendingChat = emptyList()))
    }

    private fun at3(): GameState = at21().let { it.copy(nowEpochSec = it.nowEpochSec - 18 * hour) }

    private fun queued(
        s: GameState,
        event: ChatEvent,
        speaker: String,
    ) = s.copy(
        alliance = AllianceLogic.enqueue(s.alliance, event, s.nowEpochSec, listOf(speaker), ChatKeys.ALLIANCE, data),
    )

    private fun help(
        id: Long,
        member: String = "kaede",
        persona: Boolean = true,
        exp: Long = Long.MAX_VALUE / 4,
    ) = HelpEntry(id, member, persona, TimerKind.BUILD, exp)

    // ───── ヘルプ ─────

    @Test
    fun requestHelpMarksTheTimerOnce() {
        var s = at21()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.CASTLE), data).ok()
        val id = s.timers.single().id
        val marked = Actions.requestHelp(s, id).ok()
        assertTrue(marked.timers.single().helpRequested)
        assertEquals(ActionFailure.HelpAlreadyRequested, Actions.requestHelp(marked, id).failure())
        assertEquals(ActionFailure.JobNotFound, Actions.requestHelp(s, 999).failure())
        val heal = s.timers.single().copy(kind = TimerKind.HEAL)
        assertEquals(ActionFailure.InvalidArgument, Actions.requestHelp(s.copy(timers = listOf(heal)), id).failure())
    }

    @Test
    fun helpAllGivesPointsFavorLogAndAThankYou() {
        var s = at21()
        s = s.copy(alliance = s.alliance.copy(helpPool = listOf(help(1), help(2, "Aoba", false), help(3, "ponzu"))))
        val r = Actions.helpAll(s, data).ok()
        assertTrue(r.alliance.helpPool.isEmpty())
        assertEquals(3, r.alliance.helpedToday)
        assertEquals(3 * rules.help.pointsPerHelp, r.alliance.points)
        assertEquals(3, AllianceLogic.logCount(r.alliance, LogKind.HELPED_MEMBER, 0))
        assertEquals(rules.help.favorPerHelp, r.alliance.favor["kaede"])
        assertNull(r.alliance.favor["Aoba"])
        val thanks = r.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.PlayerHelped>().single()
        assertEquals(3, thanks.count)
        assertEquals("kaede", r.alliance.pendingChat.last().speakers.first())
    }

    @Test
    fun helpAllFailuresAndDailyLimit() {
        val s = at21()
        assertEquals(ActionFailure.NothingToHelp, Actions.helpAll(s, data).failure())
        val expired = s.copy(alliance = s.alliance.copy(helpPool = listOf(help(1, exp = s.nowEpochSec - 1))))
        assertEquals(ActionFailure.NothingToHelp, Actions.helpAll(expired, data).failure())
        val full =
            s.copy(
                alliance =
                    s.alliance.copy(
                        helpPool = listOf(help(1)),
                        helpedToday = rules.help.dailyLimit,
                        day = Personas.localDay(s.nowEpochSec, 0),
                    ),
            )
        assertEquals(ActionFailure.DailyLimit, Actions.helpAll(full, data).failure())
        // 翌日になれば再びできる
        val next = full.copy(nowEpochSec = full.nowEpochSec + day)
        assertEquals(1, Actions.helpAll(next, data).ok().alliance.helpedToday)
        // 上限ぶんだけ受ける
        val many = s.copy(alliance = s.alliance.copy(helpPool = (1L..60L).map { help(it, "Aoba", false) }))
        val r = Actions.helpAll(many, data).ok()
        assertEquals(rules.help.dailyLimit, r.alliance.helpedToday)
        assertEquals(60 - rules.help.dailyLimit, r.alliance.helpPool.size)
    }

    @Test
    fun nobodyReactsWhenNoPersonaIsActive() {
        val s = at3()
        assertTrue(Personas.active(data, s.nowEpochSec, 0).isEmpty())
        val r = Actions.helpAll(s.copy(alliance = s.alliance.copy(helpPool = listOf(help(1)))), data).ok()
        assertTrue(r.alliance.pendingChat.isEmpty())
        assertEquals(1, r.alliance.helpedToday)
    }

    // ───── 要請 ─────

    private fun request(
        s: GameState,
        resource: ResourceType = ResourceType.STONE,
        amount: Long = 10_000,
        expires: Long = s.nowEpochSec + hour,
    ) = AllianceRequest(
        7,
        "ponzu",
        RequestKind.RESOURCE,
        resource = resource,
        amount = amount,
        expiresEpochSec = expires,
    )

    @Test
    fun fulfillingARequestSendsResourcesWithAFeeThatShrinksWithTradingPost() {
        var s = at21()
        s = s.copy(alliance = s.alliance.copy(requests = listOf(request(s))))
        val r = Actions.fulfillRequest(s, 7, data).ok()
        val fee = rules.request.feeBpBase
        assertEquals(s.resources.stone - 10_000 - 10_000 * fee / 10_000, r.resources.stone)
        assertTrue(r.alliance.requests.isEmpty())
        assertEquals(rules.request.points, r.alliance.points)
        assertEquals(rules.request.favor, r.alliance.favor["ponzu"])
        assertEquals(1, AllianceLogic.logCount(r.alliance, LogKind.SENT_RESOURCES, 0, "ponzu"))
        val thanks = r.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.NpcThanks>().single()
        assertEquals("stone", thanks.requestKind)
        // Trading Post Lv5 で手数料が下がる
        val tp = TestSupport.withLevel(s, BuildingType.TRADING_POST, 5)
        val cheaper = Actions.fulfillRequest(tp, 7, data).ok()
        assertTrue(cheaper.resources.stone > r.resources.stone)
        assertEquals(
            s.resources.stone - 10_000 - 10_000 * (fee - 5 * rules.request.feeBpReductionPerTradingPostLevel) / 10_000,
            cheaper.resources.stone,
        )
    }

    @Test
    fun fulfillRequestFailures() {
        val s = at21()
        assertEquals(ActionFailure.RequestNotFound, Actions.fulfillRequest(s, 7, data).failure())
        val withReq = s.copy(alliance = s.alliance.copy(requests = listOf(request(s, amount = 2_000_000_000))))
        val f = Actions.fulfillRequest(withReq, 7, data).failure()
        assertIs<ActionFailure.NotEnoughResources>(f)
        assertTrue(f.missing.stone > 0 && f.missing.food == 0L)
        val old = s.copy(alliance = s.alliance.copy(requests = listOf(request(s, expires = s.nowEpochSec))))
        assertEquals(ActionFailure.RequestNotFound, Actions.fulfillRequest(old, 7, data).failure())
        val scout =
            AllianceRequest(
                8,
                "marcus",
                RequestKind.SCOUT,
                tile = TileRef(1, 1),
                expiresEpochSec = s.nowEpochSec + hour,
            )
        assertEquals(
            ActionFailure.InvalidArgument,
            Actions.fulfillRequest(s.copy(alliance = s.alliance.copy(requests = listOf(scout))), 8, data).failure(),
        )
    }

    @Test
    fun aScoutMarchThatReturnsFulfilsTheScoutRequest() {
        var s = at21()
        val b = s.map.barbarians.minBy { com.ttmrase.crownfall.core.map.MapQueries.distance(it.x, it.y, 32, 32) }
        val tile = TileRef(b.x, b.y)
        val req =
            AllianceRequest(9, "marcus", RequestKind.SCOUT, tile = tile, expiresEpochSec = s.nowEpochSec + 6 * hour)
        s = s.copy(troops = mapOf("infantry_t1" to 10L), alliance = s.alliance.copy(requests = listOf(req)))
        s = Actions.startMarch(s, MarchKind.SCOUT, tile, mapOf("infantry_t1" to 1L), emptyList(), data).ok()
        val arrived = Simulation.tick(s, s.marches.single().travelSec, data).state
        assertTrue(arrived.alliance.requests.isEmpty())
        assertEquals(rules.request.scoutPoints, arrived.alliance.points)
        assertEquals(rules.request.scoutFavor, arrived.alliance.favor["marcus"])
        val thanks = arrived.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.NpcThanks>().single()
        assertEquals("scout", thanks.requestKind)
        assertEquals(1, AllianceLogic.logCount(arrived.alliance, LogKind.SCOUTED, 0))
    }

    // ───── 寄付・同盟テック ─────

    @Test
    fun donatingBuildsTechExpAndLevelsAddToTheBuffs() {
        var s = at21()
        val tech = rules.techs.first { it.id == "tech_production" }
        val firstLevelExp = tech.expPerLevel.first()
        val perDonation = rules.donate.techExpPerResourceUnit
        val times = ((firstLevelExp + perDonation - 1) / perDonation).toInt()
        repeat(times) { s = Actions.donate(s, tech.id, DonationKind.FOOD, data).ok() }
        assertEquals(1, AllianceTech.level(s, tech))
        assertEquals(times * rules.donate.resourceUnit, s.alliance.donatedTotal)
        assertEquals(times * rules.donate.pointsPerResourceUnit, s.alliance.points)
        assertEquals(times, s.alliance.donatedToday)
        assertTrue(s.alliance.pendingChat.any { it.event is ChatEvent.PlayerDonated })
        // バフは加算（Boost +1000bp と同じ量に足される）
        val boosted = s.copy(boosts = listOf(Boost("b", BoostKind.PRODUCTION, 1000, s.nowEpochSec + day)))
        assertEquals(1000L + tech.bpPerLevel[0], BuffAggregator.compute(boosted, data)[BuffKey.PRODUCTION_ALL])
        assertEquals(tech.bpPerLevel[0], BuffAggregator.compute(s, data)[BuffKey.PRODUCTION_ALL])
        assertEquals(0L, BuffAggregator.compute(at21(), data)[BuffKey.PRODUCTION_ALL])
    }

    @Test
    fun donateWithGemsAndFailures() {
        val s = at21()
        val g = Actions.donate(s, "tech_gather", DonationKind.GEMS, data).ok()
        assertEquals(s.gems - rules.donate.gemUnit, g.gems)
        assertEquals(rules.donate.techExpPerGemUnit, g.alliance.techExp["tech_gather"])
        assertEquals(ActionFailure.TechNotFound, Actions.donate(s, "nope", DonationKind.FOOD, data).failure())
        val poor = s.copy(resources = Resources.ZERO)
        val f = Actions.donate(poor, "tech_gather", DonationKind.WOOD, data).failure()
        assertIs<ActionFailure.NotEnoughResources>(f)
        assertEquals(rules.donate.resourceUnit, f.missing.wood)
        val noGems = Actions.donate(s.copy(gems = 3), "tech_gather", DonationKind.GEMS, data).failure()
        assertEquals(ActionFailure.NotEnoughGems(rules.donate.gemUnit - 3), noGems)
        val limited =
            s.copy(
                alliance =
                    s.alliance.copy(
                        donatedToday = rules.donate.dailyLimit,
                        day = Personas.localDay(s.nowEpochSec, 0),
                    ),
            )
        assertEquals(
            ActionFailure.DailyLimit,
            Actions.donate(limited, "tech_gather", DonationKind.FOOD, data).failure(),
        )
        val tech = rules.tech("tech_gather")!!
        val maxed = s.copy(alliance = s.alliance.copy(techExp = mapOf("tech_gather" to tech.expPerLevel.last())))
        assertEquals(ActionFailure.MaxLevel, Actions.donate(maxed, "tech_gather", DonationKind.FOOD, data).failure())
        assertTrue(AllianceTech.isMax(maxed, tech))
        assertEquals(tech.bpPerLevel.last(), AllianceTech.bp(maxed, tech))
    }

    // ───── 同盟ショップ・ギフト ─────

    @Test
    fun allianceShopSpendsPointsWithDailyLimits() {
        var s = at21()
        s = s.copy(alliance = s.alliance.copy(points = 1000))
        val speed = rules.shop.first { it.id == "ally_speedup_5m" }
        val bought = Actions.buyAllianceShop(s, speed.id, data).ok()
        assertEquals(1000 - speed.cost, bought.alliance.points)
        assertEquals((s.items[speed.item] ?: 0) + speed.count, bought.items[speed.item])
        val food = rules.shop.first { it.id == "ally_food" }
        val res = Actions.buyAllianceShop(s, food.id, data).ok()
        assertEquals(s.resources.food + food.resources!!.food, res.resources.food)
        assertEquals(ActionFailure.ShopItemNotFound, Actions.buyAllianceShop(s, "nope", data).failure())
        assertEquals(
            ActionFailure.NotEnoughAlliancePoints(speed.cost - 3),
            Actions.buyAllianceShop(s.copy(alliance = s.alliance.copy(points = 3)), speed.id, data).failure(),
        )
        var t = s
        repeat(speed.dailyLimit) { t = Actions.buyAllianceShop(t, speed.id, data).ok() }
        assertEquals(ActionFailure.DailyLimit, Actions.buyAllianceShop(t, speed.id, data).failure())
        val tomorrow = t.copy(nowEpochSec = t.nowEpochSec + day)
        Actions.buyAllianceShop(tomorrow, speed.id, data).ok()
    }

    @Test
    fun giftBoxesGiveADeterministicSmallReward() {
        var s = at21()
        val gift = GiftBox(5, "johnny", "pack_growth", s.nowEpochSec, s.nowEpochSec + day)
        s = s.copy(alliance = s.alliance.copy(gifts = listOf(gift)))
        val r1 = Actions.openGift(s, 5, data)
        val r2 = Actions.openGift(s, 5, data)
        assertEquals(r1, r2)
        val ok = r1 as com.ttmrase.crownfall.core.sim.ActionResult.Success
        assertTrue(ok.state.alliance.gifts.isEmpty())
        val ev = ok.events.single() as GameEvent.GiftOpened
        assertEquals(5, ev.giftId)
        val gained = ev.resources.food + ev.resources.wood + ev.resources.stone + ev.resources.gold + ev.count + ev.gems
        assertTrue(gained > 0)
        assertEquals(ActionFailure.GiftNotFound, Actions.openGift(s, 6, data).failure())
        val old = s.copy(nowEpochSec = gift.expiresEpochSec)
        assertEquals(ActionFailure.GiftNotFound, Actions.openGift(old, 5, data).failure())
        // 重みどおりに全種類が出る
        val seen = HashSet<String>()
        for (id in 1L..300L) {
            val g = s.copy(alliance = s.alliance.copy(gifts = listOf(gift.copy(id = id))))
            val e =
                (
                    Actions.openGift(
                        g,
                        id,
                        data,
                    ) as com.ttmrase.crownfall.core.sim.ActionResult.Success
                ).events.single() as GameEvent.GiftOpened
            seen += if (e.gems > 0) "gems" else e.item ?: "res"
        }
        assertTrue(seen.size >= 4, "$seen")
    }

    // ───── AI ラリー ─────

    private fun rallyState(): Pair<GameState, AiRally> {
        var s = at21()
        val fort =
            Marching.world(s, data).structures.first {
                it.type == com.ttmrase.crownfall.core.map.StructureType.BARBARIAN_FORT && it.level == 1
            }
        val rally =
            AiRally(3, "marcus", TileRef(fort.x, fort.y), 1, s.nowEpochSec + 1800, listOf("marcus", "kaede", "gen"))
        val troops = mapOf("infantry_t2" to 3000L, "archer_t2" to 3000L)
        s = s.copy(troops = troops, alliance = s.alliance.copy(rallies = listOf(rally)))
        return s to rally
    }

    @Test
    fun joiningAnAiRallyUsesPersonaTroopsAndPaysTheHost() {
        val (s0, rally) = rallyState()
        val s = Actions.joinRally(s0, 3, mapOf("infantry_t2" to 2000L, "archer_t2" to 2000L), emptyList(), data).ok()
        val m = s.marches.single()
        assertEquals(MarchKind.RALLY, m.kind)
        assertEquals(MarchPhase.OUTBOUND, m.phase) // 集結待ちは無い
        assertEquals(3L, m.hostId)
        assertEquals("marcus", m.hostPersona)
        assertEquals(AiRallies.allyTroops(s0, rally, data), m.allyTroops)
        assertTrue(m.allyTroops.values.sum() > 0)
        assertTrue(s.alliance.rallies.isEmpty())
        val done = Simulation.tick(s, m.travelSec, data).state
        val report = done.mails.last().battle!!
        assertEquals(m.allyTroops, report.allyTroops)
        assertTrue(done.alliance.favor["marcus"]!! >= rules.request.favor)
        assertEquals(1, AllianceLogic.logCount(done.alliance, LogKind.JOINED_RALLY, 0, "marcus"))
        val ev = done.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.PlayerJoinedRally>().single()
        assertEquals(report.attackerWon, ev.won)
        assertEquals("marcus", ev.host)
    }

    @Test
    fun joinRallyFailures() {
        val (s, _) = rallyState()
        assertEquals(
            ActionFailure.RallyNotFound,
            Actions.joinRally(s, 99, mapOf("infantry_t2" to 10L), emptyList(), data).failure(),
        )
        val late = s.copy(nowEpochSec = s.nowEpochSec + 1800)
        assertEquals(
            ActionFailure.RallyNotFound,
            Actions.joinRally(late, 3, mapOf("infantry_t2" to 10L), emptyList(), data).failure(),
        )
        assertEquals(
            ActionFailure.NoTroops,
            Actions.joinRally(s, 3, mapOf("infantry_t2" to 99_999L), emptyList(), data).failure(),
        )
    }

    @Test
    fun splitInvarianceWithAnAiRallyMarch() {
        val (s0, _) = rallyState()
        val s = Actions.joinRally(s0, 3, mapOf("infantry_t2" to 2000L, "archer_t2" to 2000L), emptyList(), data).ok()
        val travel = s.marches.single().travelSec
        SplitInvariance.assertInvariant(
            s,
            travel + 2 * hour,
            data,
            java.util.Random(3),
            randomCases = 40,
            extraBoundaries = listOf(travel, travel + 1),
        )
    }

    // ───── チャットの書き戻し・自由入力・クイズ ─────

    @Test
    fun appendChatMovesPendingIntoHistoryAndKeepsTheCap() {
        var s = at21()
        s = queued(s, ChatEvent.DailyGreeting, "kaede")
        val id = s.alliance.pendingChat.single().id
        val r =
            Actions.appendChat(
                s,
                id,
                listOf(SpokenText("kaede", " おはよう "), SpokenText("gen", ""), SpokenText("gen", "うむ")),
                data,
            ).ok()
        assertTrue(r.alliance.pendingChat.isEmpty())
        val msgs = r.alliance.history.getValue(ChatKeys.ALLIANCE)
        assertEquals(listOf("おはよう", "うむ"), msgs.map { it.text })
        assertEquals(listOf(s.nowEpochSec, s.nowEpochSec + 1), msgs.map { it.epochSec })
        assertEquals(ActionFailure.ChatNotFound, Actions.appendChat(r, id, emptyList(), data).failure())
        var t = r
        repeat(rules.caps.history + 20) {
            t = queued(t, ChatEvent.DailyGreeting, "kaede")
            t = Actions.appendChat(t, t.alliance.pendingChat.last().id, listOf(SpokenText("kaede", "m$it")), data).ok()
        }
        assertEquals(rules.caps.history, t.alliance.history.getValue(ChatKeys.ALLIANCE).size)
        assertEquals("m${rules.caps.history + 19}", t.alliance.history.getValue(ChatKeys.ALLIANCE).last().text)
    }

    @Test
    fun freeTextGoesToAllianceOrDmsOnly() {
        val s = at21()
        val a = Actions.submitFreeText(s, "  こんにちは  ", ChatKeys.ALLIANCE, data).ok()
        assertEquals("こんにちは", a.alliance.history.getValue(ChatKeys.ALLIANCE).single().text)
        assertEquals("player", a.alliance.history.getValue(ChatKeys.ALLIANCE).single().speaker)
        val q = a.alliance.pendingChat.single()
        assertEquals(ChatEvent.PlayerFreeText("こんにちは", ChatKeys.ALLIANCE), q.event)
        assertTrue(
            q.speakers.isNotEmpty() &&
                q.speakers.all {
                    Personas.isActive(data.personas.persona(it)!!, s.nowEpochSec, 0)
                },
        )
        val dm = Actions.submitFreeText(s, "ねぇ", ChatKeys.dm("gen"), data).ok()
        assertEquals(listOf("gen"), dm.alliance.pendingChat.single().speakers)
        assertEquals(ActionFailure.ChannelNotWritable, Actions.submitFreeText(s, "x", ChatKeys.WORLD, data).failure())
        assertEquals(ActionFailure.ChannelNotWritable, Actions.submitFreeText(s, "x", "dm_nobody", data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.submitFreeText(s, "   ", ChatKeys.ALLIANCE, data).failure())
        assertEquals(
            ActionFailure.InvalidArgument,
            Actions.submitFreeText(s, "あ".repeat(201), ChatKeys.ALLIANCE, data).failure(),
        )
        // 誰も活動していない時間帯は返答のきっかけを積まない（書き込みは残る）
        val quiet = Actions.submitFreeText(at3(), "zzz", ChatKeys.ALLIANCE, data).ok()
        assertTrue(quiet.alliance.pendingChat.isEmpty())
        assertEquals(1, quiet.alliance.history.getValue(ChatKeys.ALLIANCE).size)
    }

    private fun quizState(): Pair<GameState, Long> {
        var s = at21()
        s = queued(s, ChatEvent.PonzuQuiz("q_hospital"), "ponzu")
        val pid = s.alliance.pendingChat.single().id
        s = Actions.appendChat(s, pid, listOf(SpokenText("ponzu", "どこで治すんでしたっけ？")), data).ok()
        return s to s.alliance.history.getValue(ChatKeys.ALLIANCE).single().id
    }

    @Test
    fun ponzuQuizAnswersAdjustFavorAndQueueAReaction() {
        val (s, msgId) = quizState()
        val msg = s.alliance.history.getValue(ChatKeys.ALLIANCE).single()
        assertEquals(3, msg.choices.size)
        assertEquals("q_hospital", msg.quizId)
        val right = Actions.answerQuiz(s, msgId, 1, data).ok()
        assertEquals(1, right.alliance.history.getValue(ChatKeys.ALLIANCE).single().answered)
        assertEquals(rules.quiz.correctFavor, right.alliance.favor["ponzu"])
        val ev = right.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.QuizResult>().single()
        assertTrue(ev.correct)
        assertEquals("ponzu", right.alliance.pendingChat.single().speakers.first())
        val wrong = Actions.answerQuiz(s, msgId, 0, data).ok()
        assertEquals(rules.quiz.wrongFavor, wrong.alliance.favor["ponzu"])
        assertFalse((wrong.alliance.pendingChat.single().event as ChatEvent.QuizResult).correct)
        assertEquals(ActionFailure.QuizAlreadyAnswered, Actions.answerQuiz(right, msgId, 1, data).failure())
        assertEquals(ActionFailure.QuizNotFound, Actions.answerQuiz(s, 12345, 1, data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.answerQuiz(s, msgId, 3, data).failure())
        val plain =
            Actions.appendChat(
                s.copy(
                    alliance =
                        AllianceLogic.enqueue(
                            s.alliance,
                            ChatEvent.DailyGreeting,
                            s.nowEpochSec,
                            listOf("kaede"),
                            ChatKeys.ALLIANCE,
                            data,
                        ),
                ),
                s.alliance.nextId,
                listOf(SpokenText("kaede", "hi")),
                data,
            ).ok()
        val plainId = plain.alliance.history.getValue(ChatKeys.ALLIANCE).last().id
        assertEquals(ActionFailure.QuizNotFound, Actions.answerQuiz(plain, plainId, 0, data).failure())
    }

    @Test
    fun readMarkersAndUtcOffset() {
        val (s, msgId) = quizState()
        assertEquals(msgId, Actions.markChannelRead(s, ChatKeys.ALLIANCE).ok().alliance.chatRead[ChatKeys.ALLIANCE])
        val once = Actions.markChannelRead(s, ChatKeys.ALLIANCE).ok()
        assertEquals(once, Actions.markChannelRead(once, ChatKeys.ALLIANCE).ok())
        assertEquals(s, Actions.markChannelRead(s, "empty").ok())
        assertEquals(32_400, Actions.setUtcOffset(s, 32_400).ok().settings.utcOffsetSec)
        assertEquals(ActionFailure.InvalidArgument, Actions.setUtcOffset(s, 15 * 3600).failure())
    }

    // ───── 他処理からのきっかけ ─────

    @Test
    fun castleUpAndRaidResultsQueueReactions() {
        var s = at21()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.CASTLE), data).ok()
        val done = Simulation.tick(s, s.timers.single().endEpochSec - s.nowEpochSec, data).state
        val up = done.alliance.pendingChat.map { it.event }.filterIsInstance<ChatEvent.PlayerCastleUp>().single()
        assertEquals(11, up.lv)
        // 襲撃
        val raid = at21().let { it.copy(troops = emptyMap(), map = it.map.copy(nextRaidEpochSec = it.nowEpochSec + 5)) }
        val after = Simulation.tick(raid, 5, data).state
        assertTrue(after.alliance.pendingChat.any { it.event is ChatEvent.PlayerDefeated })
    }

    @Test
    fun offlineCatchUpShiftsAllianceExpiriesBeyondTheCap() {
        val s0 = at21()
        val s =
            s0.copy(
                alliance =
                    s0.alliance.copy(
                        requests = listOf(request(s0, expires = s0.nowEpochSec + 2 * hour)),
                        helpPool = listOf(help(1, exp = s0.nowEpochSec + 2 * hour)),
                        gifts = listOf(GiftBox(2, "johnny", "pack_growth", s0.nowEpochSec, s0.nowEpochSec + 2 * hour)),
                        rallies =
                            listOf(
                                AiRally(3, "marcus", TileRef(1, 1), 1, s0.nowEpochSec + 2 * hour, listOf("marcus")),
                            ),
                    ),
            )
        val cap = 3600L
        val extra = 5 * day
        val r =
            com.ttmrase.crownfall.core.sim.OfflineCatchUp.run(
                s,
                s.nowEpochSec + cap + extra,
                data,
                capSec = cap,
            ).state
        val req = r.alliance.requests.first { it.id == 7L }
        assertEquals(s.alliance.requests.single().expiresEpochSec + extra, req.expiresEpochSec)
        assertEquals(
            s.alliance.helpPool.single().expiresEpochSec + extra,
            r.alliance.helpPool.first {
                it.id == 1L
            }.expiresEpochSec,
        )
        assertEquals(
            s.alliance.gifts.single().expiresEpochSec + extra,
            r.alliance.gifts.first { it.id == 2L }.expiresEpochSec,
        )
        assertEquals(
            s.alliance.rallies.single().joinDeadlineEpochSec + extra,
            r.alliance.rallies.first {
                it.id == 3L
            }.joinDeadlineEpochSec,
        )
    }
}
