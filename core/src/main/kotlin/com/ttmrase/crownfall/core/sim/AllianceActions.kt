package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.alliance.AllianceLogic
import com.ttmrase.crownfall.core.alliance.AllianceTech
import com.ttmrase.crownfall.core.alliance.DonationKind
import com.ttmrase.crownfall.core.alliance.Personas
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.chat.SpokenText
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.ChatMessage
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HelpEntry
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.RequestKind
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.util.Mix

/** 同盟まわりのアクション（検査は宣言順で、最初の失敗を返す）。 */
internal object AllianceActions {
    private const val BP_ONE = 10_000L
    private const val MAX_TEXT = 200
    private const val MAX_OFFSET = 14 * 3600
    private const val GIFT_SALT = 0x4749L
    private const val LEADER = "kaede"
    private const val ANALYST = "lin"
    private const val PONZU = "ponzu"
    private val HELPABLE = setOf(TimerKind.BUILD, TimerKind.RESEARCH, TimerKind.TRAIN)

    private fun fail(reason: ActionFailure) = ActionResult.Failure(reason)

    private fun rolled(s: GameState): GameState {
        val day = Personas.localDay(s.nowEpochSec, s.settings.utcOffsetSec)
        return s.copy(alliance = AllianceLogic.rollDay(s.alliance, day))
    }

    private fun missingOf(
        have: Resources,
        need: Resources,
    ) = Resources(
        maxOf(0, need.food - have.food),
        maxOf(0, need.wood - have.wood),
        maxOf(0, need.stone - have.stone),
        maxOf(0, need.gold - have.gold),
    )

    fun setUtcOffset(
        state: GameState,
        sec: Int,
    ): ActionResult {
        if (sec !in -MAX_OFFSET..MAX_OFFSET) return fail(ActionFailure.InvalidArgument)
        return ActionResult.Success(state.copy(settings = state.settings.copy(utcOffsetSec = sec)))
    }

    fun requestHelp(
        state: GameState,
        jobId: Long,
    ): ActionResult {
        val job = state.timers.firstOrNull { it.id == jobId } ?: return fail(ActionFailure.JobNotFound)
        if (job.kind !in HELPABLE) return fail(ActionFailure.InvalidArgument)
        if (job.helpRequested) return fail(ActionFailure.HelpAlreadyRequested)
        val marked = job.copy(helpRequested = true)
        return ActionResult.Success(state.copy(timers = state.timers.map { if (it.id == jobId) marked else it }))
    }

    fun helpAll(
        state: GameState,
        data: GameData,
    ): ActionResult {
        val s0 = rolled(state)
        val pool = s0.alliance.helpPool.filter { it.expiresEpochSec > s0.nowEpochSec }
        if (pool.isEmpty()) return fail(ActionFailure.NothingToHelp)
        val rules = data.allianceRules.help
        val room = rules.dailyLimit - s0.alliance.helpedToday
        if (room <= 0) return fail(ActionFailure.DailyLimit)
        val taken = pool.sortedBy { it.id }.take(room)
        val takenIds = taken.map { it.id }.toSet()
        var s =
            s0.copy(
                alliance =
                    s0.alliance.copy(
                        helpPool = s0.alliance.helpPool.filter { it.id !in takenIds },
                        helpedToday = s0.alliance.helpedToday + taken.size,
                    ),
            )
        s = AllianceLogic.addPoints(s, rules.pointsPerHelp * taken.size)
        for (e in taken) s = helpOne(s, e, data)
        val preferred = taken.filter { it.isPersona }.map { it.member }
        return ActionResult.Success(
            AllianceLogic.enqueueReactive(s, ChatEvent.PlayerHelped(taken.size), preferred, data),
        )
    }

    /** 1 件のヘルプ: ログと好感度（ペルソナのみ）。 */
    private fun helpOne(
        state: GameState,
        entry: HelpEntry,
        data: GameData,
    ): GameState {
        val persona = if (entry.isPersona) entry.member else null
        val s = AllianceLogic.logged(state, LogKind.HELPED_MEMBER, persona, 0, data)
        return if (persona == null) {
            s
        } else {
            AllianceLogic.addFavor(
                s,
                persona,
                data.allianceRules.help.favorPerHelp,
                data,
            )
        }
    }

    fun fulfillRequest(
        state: GameState,
        requestId: Long,
        data: GameData,
    ): ActionResult {
        val req =
            state.alliance.requests.firstOrNull { it.id == requestId && it.expiresEpochSec > state.nowEpochSec }
                ?: return fail(ActionFailure.RequestNotFound)
        val resource = req.resource
        if (req.kind != RequestKind.RESOURCE || resource == null) return fail(ActionFailure.InvalidArgument)
        val rules = data.allianceRules.request
        val tradingPost = Marching.levelOf(state, BuildingType.TRADING_POST)
        val feeBp = maxOf(rules.minFeeBp, rules.feeBpBase - rules.feeBpReductionPerTradingPostLevel * tradingPost)
        val cost = Resources.ZERO.with(resource, satAdd(req.amount, req.amount * feeBp / BP_ONE))
        val rest =
            state.resources.minusOrNull(cost)
                ?: return fail(ActionFailure.NotEnoughResources(missingOf(state.resources, cost)))
        var s = state.copy(resources = rest)
        s = s.copy(alliance = s.alliance.copy(requests = s.alliance.requests.filter { it.id != requestId }))
        s = AllianceLogic.addPoints(s, rules.points)
        s = AllianceLogic.addFavor(s, req.persona, rules.favor, data)
        s = AllianceLogic.logged(s, LogKind.SENT_RESOURCES, req.persona, req.amount, data)
        val thanks = ChatEvent.NpcThanks(req.persona, resource.name.lowercase(), req.amount)
        s = AllianceLogic.enqueueFrom(s, thanks, req.persona, ChatKeys.ALLIANCE, data)
        return ActionResult.Success(Simulation.syncStorageFlags(s, data))
    }

    fun donate(
        state: GameState,
        techId: String,
        kind: DonationKind,
        data: GameData,
    ): ActionResult {
        val tech = data.allianceRules.tech(techId) ?: return fail(ActionFailure.TechNotFound)
        val s0 = rolled(state)
        val rules = data.allianceRules.donate
        if (AllianceTech.isMax(s0, tech)) return fail(ActionFailure.MaxLevel)
        if (s0.alliance.donatedToday >= rules.dailyLimit) return fail(ActionFailure.DailyLimit)
        val resource = kind.resource
        val unit = if (resource != null) rules.resourceUnit else rules.gemUnit
        val exp = if (resource != null) rules.techExpPerResourceUnit else rules.techExpPerGemUnit
        val points = if (resource != null) rules.pointsPerResourceUnit else rules.pointsPerGemUnit
        var s = s0
        if (resource != null) {
            val cost = Resources.ZERO.with(resource, unit)
            val rest =
                s.resources.minusOrNull(cost)
                    ?: return fail(ActionFailure.NotEnoughResources(missingOf(s.resources, cost)))
            s = s.copy(resources = rest)
        } else {
            if (s.gems < unit) return fail(ActionFailure.NotEnoughGems(unit - s.gems))
            s = s.copy(gems = s.gems - unit)
        }
        val a = s.alliance
        s =
            s.copy(
                alliance =
                    a.copy(
                        techExp = a.techExp + (techId to satAdd(a.techExp[techId] ?: 0L, exp)),
                        donatedTotal = satAdd(a.donatedTotal, unit),
                        donatedToday = a.donatedToday + 1,
                    ),
            )
        s = AllianceLogic.addPoints(s, points)
        s = AllianceLogic.addFavor(s, LEADER, rules.favor, data)
        s = AllianceLogic.logged(s, LogKind.DONATED, LEADER, unit, data)
        s = AllianceLogic.enqueueReactive(s, ChatEvent.PlayerDonated(unit, techId), listOf(LEADER, ANALYST), data)
        return ActionResult.Success(Simulation.syncStorageFlags(s, data))
    }

    fun buyShop(
        state: GameState,
        shopId: String,
        data: GameData,
    ): ActionResult {
        val entry =
            data.allianceRules.shop.firstOrNull { it.id == shopId } ?: return fail(ActionFailure.ShopItemNotFound)
        val s0 = rolled(state)
        val bought = s0.alliance.shopBought[shopId] ?: 0
        if (bought >= entry.dailyLimit) return fail(ActionFailure.DailyLimit)
        if (s0.alliance.points < entry.cost) {
            return fail(ActionFailure.NotEnoughAlliancePoints(entry.cost - s0.alliance.points))
        }
        val a = s0.alliance
        var s =
            s0.copy(
                alliance = a.copy(points = a.points - entry.cost, shopBought = a.shopBought + (shopId to bought + 1)),
            )
        if (entry.item != null) s = s.copy(items = s.items + (entry.item to (s.items[entry.item] ?: 0) + entry.count))
        if (entry.resources != null) s = s.copy(resources = s.resources + entry.resources)
        return ActionResult.Success(Simulation.syncStorageFlags(s, data))
    }

    fun openGift(
        state: GameState,
        giftId: Long,
        data: GameData,
    ): ActionResult {
        val gift =
            state.alliance.gifts.firstOrNull { it.id == giftId && it.expiresEpochSec > state.nowEpochSec }
                ?: return fail(ActionFailure.GiftNotFound)
        val table = data.allianceRules.gifts
        var pick = Mix.roll(state.alliance.seed, giftId, GIFT_SALT, table.sumOf { it.weight })
        val reward =
            table.first {
                pick -= it.weight
                pick < 0
            }
        var s = state.copy(alliance = state.alliance.copy(gifts = state.alliance.gifts.filter { it.id != giftId }))
        reward.resources?.let { s = s.copy(resources = s.resources + it) }
        reward.item?.let { s = s.copy(items = s.items + (it to (s.items[it] ?: 0) + reward.count)) }
        if (reward.gems > 0) s = s.copy(gems = satAdd(s.gems, reward.gems))
        val opened =
            GameEvent.GiftOpened(
                giftId,
                gift.from,
                reward.resources ?: Resources.ZERO,
                reward.item,
                reward.count,
                reward.gems,
            )
        return ActionResult.Success(Simulation.syncStorageFlags(s, data), listOf(opened))
    }

    fun joinRally(
        state: GameState,
        rallyId: Long,
        troops: Map<String, Long>,
        heroIds: List<String>,
        data: GameData,
    ): ActionResult {
        val rally =
            state.alliance.rallies.firstOrNull { it.id == rallyId && it.joinDeadlineEpochSec > state.nowEpochSec }
                ?: return fail(ActionFailure.RallyNotFound)
        val result = MarchActions.start(state, MarchKind.RALLY, rally.target, troops, heroIds, data, host = rally)
        if (result !is ActionResult.Success) return result
        val s = result.state
        val rest = s.alliance.copy(rallies = s.alliance.rallies.filter { it.id != rallyId })
        return ActionResult.Success(s.copy(alliance = rest), result.events)
    }

    fun submitFreeText(
        state: GameState,
        text: String,
        channel: String,
        data: GameData,
    ): ActionResult {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_TEXT) return fail(ActionFailure.InvalidArgument)
        val dm = if (channel.startsWith("dm_")) channel.removePrefix("dm_") else null
        val speakers =
            when {
                channel == ChatKeys.ALLIANCE -> AllianceLogic.reactiveSpeakers(state, data)
                dm != null && data.personas.persona(dm) != null -> listOf(dm)
                else -> return fail(ActionFailure.ChannelNotWritable)
            }
        var s = addMessage(state, channel, "player", t, state.nowEpochSec, data)
        if (speakers.isNotEmpty()) {
            val queued =
                AllianceLogic.enqueue(
                    s.alliance,
                    ChatEvent.PlayerFreeText(t, channel),
                    s.nowEpochSec,
                    speakers,
                    channel,
                    data,
                )
            s = s.copy(alliance = queued)
        }
        return ActionResult.Success(s)
    }

    private fun addMessage(
        state: GameState,
        channel: String,
        speaker: String,
        text: String,
        at: Long,
        data: GameData,
        quizId: String? = null,
        choices: List<String> = emptyList(),
    ): GameState {
        val (id, a) = AllianceLogic.nextId(state.alliance)
        val msg = ChatMessage(id, channel, speaker, text, at, quizId, choices)
        val list = ((a.history[channel] ?: emptyList()) + msg).takeLast(data.allianceRules.caps.history)
        return state.copy(alliance = a.copy(history = a.history + (channel to list)))
    }

    fun appendChat(
        state: GameState,
        pendingId: Long,
        lines: List<SpokenText>,
        data: GameData,
    ): ActionResult {
        val pending =
            state.alliance.pendingChat.firstOrNull { it.id == pendingId } ?: return fail(ActionFailure.ChatNotFound)
        var s =
            state.copy(
                alliance =
                    state.alliance.copy(
                        pendingChat =
                            state.alliance.pendingChat.filter {
                                it.id != pendingId
                            },
                    ),
            )
        val quiz = pending.event as? ChatEvent.PonzuQuiz
        lines.filter { it.text.isNotBlank() }.forEachIndexed { i, line ->
            val q = quiz?.takeIf { i == 0 }
            val choices = q?.let { data.templates.quizzes[it.quizId]?.options }.orEmpty()
            val at = pending.createdEpochSec + i
            s = addMessage(s, pending.channel, line.speaker, line.text.trim(), at, data, q?.quizId, choices)
        }
        return ActionResult.Success(s)
    }

    fun answerQuiz(
        state: GameState,
        messageId: Long,
        option: Int,
        data: GameData,
    ): ActionResult {
        val found =
            state.alliance.history.entries.firstNotNullOfOrNull { (ch, list) ->
                list.firstOrNull { it.id == messageId && it.quizId != null }?.let { ch to it }
            } ?: return fail(ActionFailure.QuizNotFound)
        val (channel, msg) = found
        val quizId = msg.quizId ?: return fail(ActionFailure.QuizNotFound)
        if (msg.answered >= 0) return fail(ActionFailure.QuizAlreadyAnswered)
        if (option !in msg.choices.indices) return fail(ActionFailure.InvalidArgument)
        val rule = data.allianceRules.quizzes.firstOrNull { it.id == quizId } ?: return fail(ActionFailure.QuizNotFound)
        val correct = option == rule.correct
        val marked =
            state.alliance.history.getValue(
                channel,
            ).map { if (it.id == messageId) it.copy(answered = option) else it }
        var s = state.copy(alliance = state.alliance.copy(history = state.alliance.history + (channel to marked)))
        val quiz = data.allianceRules.quiz
        s = AllianceLogic.addFavor(s, PONZU, if (correct) quiz.correctFavor else quiz.wrongFavor, data)
        val others = AllianceLogic.reactiveSpeakers(s, data).filter { it != PONZU }
        val speakers = (listOf(PONZU) + others).take(2)
        val queued =
            AllianceLogic.enqueue(
                s.alliance,
                ChatEvent.QuizResult(quizId, correct),
                s.nowEpochSec,
                speakers,
                channel,
                data,
            )
        return ActionResult.Success(s.copy(alliance = queued))
    }

    fun markChannelRead(
        state: GameState,
        channel: String,
    ): ActionResult {
        val last = state.alliance.history[channel]?.lastOrNull()?.id ?: return ActionResult.Success(state)
        if ((state.alliance.chatRead[channel] ?: 0L) >= last) return ActionResult.Success(state)
        return ActionResult.Success(
            state.copy(alliance = state.alliance.copy(chatRead = state.alliance.chatRead + (channel to last))),
        )
    }
}
