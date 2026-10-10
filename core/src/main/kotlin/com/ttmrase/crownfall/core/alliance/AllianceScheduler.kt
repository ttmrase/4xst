package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PersonaDef
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.AiRally
import com.ttmrase.crownfall.core.model.AllianceRequest
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.GiftBox
import com.ttmrase.crownfall.core.model.HelpEntry
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.RequestKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.util.Mix

/**
 * 同盟スケジューラ（03 §3）。毎分 0 秒のグリッドで評価し、乱数は状態を持たないハッシュで引く。
 * 評価は「その分に 1 回だけ」（[AllianceState.evalMinute]）なので、tick の分割に依存しない（R3）。
 */
object AllianceScheduler {
    private const val PERIOD = 60L
    private const val PERMILLE = 1000
    private const val SEED_SALT = 0x414C4C49L
    private const val MAX_SPEAKERS = 3
    private const val AMOUNT_ROUND = 1000L
    private const val SECONDS_PER_DAY = 86_400L
    private const val SCOUT_WEIGHT = 3
    private val AMBIENT_TOPICS = listOf("weather", "event", "ranking", "life", "game")

    private const val K_AMBIENT = 100L
    private const val K_NEEDS = 200L
    private const val K_SCOUT = 300L
    private const val K_PURCHASE = 400L
    private const val K_RALLY = 500L
    private const val K_QUIZ = 600L
    private const val K_YUKI = 700L
    private const val K_HELP_REQ = 800L
    private const val K_WORLD = 900L
    private const val K_AI_HELP = 1000L
    private const val K_PICK = 5000L

    /** [nowEpochSec] より後で最初のグリッド時刻。 */
    fun nextGrid(nowEpochSec: Long): Long {
        val next = (Math.floorDiv(nowEpochSec, PERIOD) + 1) * PERIOD
        return if (next <= nowEpochSec) Long.MAX_VALUE else next // 桁あふれ時は境界なし
    }

    /** 未初期化なら種を決める。 */
    private fun init(state: GameState): GameState {
        if (state.alliance.seed != 0L) return state
        val seed = Mix.mix(state.rngState xor SEED_SALT).let { if (it == 0L) 1L else it }
        return state.copy(alliance = state.alliance.copy(seed = seed, startEpochSec = state.nowEpochSec))
    }

    /** 現在時刻がグリッド上で、その分を未評価なら評価する。 */
    fun settle(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val s = init(state)
        val now = s.nowEpochSec
        if (Math.floorMod(now, PERIOD) != 0L) return s
        val minute = now / PERIOD
        if (minute <= s.alliance.evalMinute) return s
        val marked = s.copy(alliance = s.alliance.copy(evalMinute = minute))
        return evaluate(marked, now, minute, data, events)
    }

    private fun roll(
        s: GameState,
        minute: Long,
        key: Long,
        bound: Int = PERMILLE,
    ): Int = Mix.roll(s.alliance.seed, minute, key, bound)

    private fun chance(
        s: GameState,
        minute: Long,
        key: Long,
        permille: Int,
    ): Boolean = permille > 0 && roll(s, minute, key) < permille

    private fun speakersFor(
        primary: PersonaDef?,
        active: List<PersonaDef>,
    ): List<String> {
        val rest = active.filter { it.id != primary?.id }.map { it.id }
        return (listOfNotNull(primary?.id) + rest).take(MAX_SPEAKERS)
    }

    @Suppress("LongMethod")
    private fun evaluate(
        start: GameState,
        t: Long,
        minute: Long,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val rules = data.allianceRules
        val chances = rules.chancePermillePerMinute
        val offset = start.settings.utcOffsetSec
        val day = Personas.localDay(t, offset)
        val active = Personas.active(data, t, offset)
        var s = start.copy(alliance = AllianceLogic.rollDay(expire(start.alliance, t), day))

        s = aiHelp(s, t, minute, active, data, events)

        if (s.alliance.lastGreetingDay != day && active.isNotEmpty()) {
            val a = s.alliance.copy(lastGreetingDay = day)
            val who = speakersFor(active.first(), active)
            s = s.copy(alliance = AllianceLogic.enqueue(a, ChatEvent.DailyGreeting, t, who, ChatKeys.ALLIANCE, data))
        }

        data.personas.personas.forEachIndexed { i, p ->
            val idx = i.toLong()
            if (Personas.startsAt(p, t, offset) && p.id == YUKI && roll(s, minute, K_YUKI) < chances.yukiReturn) {
                val since = t - rules.log.helpWindowSec
                val count = AllianceLogic.logCount(s.alliance, LogKind.HELPED_MEMBER, since, YUKI)
                s = enqueue(s, ChatEvent.YukiReturn(count), t, listOf(YUKI), ChatKeys.ALLIANCE, data)
            }
            if (p !in active) return@forEachIndexed
            if (chance(s, minute, K_AMBIENT + idx, chances.ambient)) {
                val topic = AMBIENT_TOPICS[roll(s, minute, K_PICK + idx, AMBIENT_TOPICS.size)]
                s = enqueue(s, ChatEvent.Ambient(topic), t, speakersFor(p, active), ChatKeys.ALLIANCE, data)
            }
            val roomForRequest = s.alliance.requests.size < rules.caps.openRequests
            if (roomForRequest && chance(s, minute, K_NEEDS + idx, chances.needsHelp)) {
                s = addResourceRequest(s, p, t, minute, idx, data)
            }
            if (s.alliance.requests.size < rules.caps.openRequests &&
                chance(s, minute, K_SCOUT + idx, chances.asksScout * (if (p.specialty == "war") SCOUT_WEIGHT else 1))
            ) {
                s = addScoutRequest(s, p, t, minute, idx, data)
            }
            val buy = if (p.specialty == "spender") chances.johnnyPurchase else chances.purchase
            if (chance(s, minute, K_PURCHASE + idx, buy)) s = addPurchase(s, p, t, minute, idx, data)
            if (p.id in rules.rally.hosts && s.alliance.rallies.size < rules.caps.openRallies &&
                chance(s, minute, K_RALLY + idx, chances.aiRally)
            ) {
                s = addRally(s, p, t, minute, active, data)
            }
            if (p.id == PONZU && chance(s, minute, K_QUIZ + idx, chances.quiz) &&
                t - s.alliance.lastQuizEpochSec >= rules.quiz.minIntervalSec
            ) {
                s = addQuiz(s, p, t, data)
            }
        }
        val roomForHelp = s.alliance.helpPool.size < rules.caps.helpPool
        if (roomForHelp && chance(s, minute, K_HELP_REQ, chances.memberHelpRequest)) {
            s = addHelpEntry(s, t, minute, active, data)
        }
        if (chance(s, minute, K_WORLD, chances.worldAmbient)) {
            val topic = AMBIENT_TOPICS[roll(s, minute, K_PICK + K_WORLD, AMBIENT_TOPICS.size)]
            s = enqueue(s, ChatEvent.WorldAmbient(topic), t, emptyList(), ChatKeys.WORLD, data)
        }
        return s
    }

    private fun enqueue(
        s: GameState,
        event: ChatEvent,
        t: Long,
        speakers: List<String>,
        channel: String,
        data: GameData,
    ): GameState = s.copy(alliance = AllianceLogic.enqueue(s.alliance, event, t, speakers, channel, data))

    private fun expire(
        a: com.ttmrase.crownfall.core.model.AllianceState,
        t: Long,
    ) = a.copy(
        requests = a.requests.filter { it.expiresEpochSec > t },
        helpPool = a.helpPool.filter { it.expiresEpochSec > t },
        gifts = a.gifts.filter { it.expiresEpochSec > t },
        rallies = a.rallies.filter { it.joinDeadlineEpochSec > t },
    )

    /** ヘルプを求めたタイマーに、活動中のメンバー（いなければ名前だけのメンバー）がヘルプする。 */
    private fun aiHelp(
        start: GameState,
        t: Long,
        minute: Long,
        active: List<PersonaDef>,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        var s = start
        val rules = data.allianceRules
        val perTimer =
            maxOf(
                rules.help.minHelpsPerTimer.toLong(),
                data.building(
                    BuildingType.ALLIANCE_CENTER,
                ).effect(Marching.levelOf(s, BuildingType.ALLIANCE_CENTER), "help_count"),
            ).toInt()
        for (job in s.timers.sortedBy { it.id }) {
            if (!job.helpRequested || job.helpCount >= perTimer || job.endEpochSec <= t) continue
            if (!chance(s, minute, K_AI_HELP + job.id, rules.chancePermillePerMinute.aiHelpOnPlayerTimer)) continue
            val pool = active.map { it.id } + data.personas.fillerMembers.map { it.name }
            val helper = pool[roll(s, minute, K_PICK + job.id, pool.size)]
            val remaining = job.endEpochSec - t
            val reduction =
                minOf(remaining, maxOf(remaining * rules.help.reductionPct / 100, rules.help.minReductionSec))
            val helped = job.copy(endEpochSec = job.endEpochSec - reduction, helpCount = job.helpCount + 1)
            s = s.copy(timers = s.timers.map { if (it.id == job.id) helped else it })
            s = s.copy(alliance = s.alliance.copy(helpReceivedTotal = s.alliance.helpReceivedTotal + 1))
            if (data.personas.persona(helper) != null) s = AllianceLogic.addFavor(s, helper, 1, data)
            events.add(GameEvent.HelpReceived(helper, job.id, reduction))
        }
        return s
    }

    private fun addResourceRequest(
        s0: GameState,
        p: PersonaDef,
        t: Long,
        minute: Long,
        idx: Long,
        data: GameData,
    ): GameState {
        val rules = data.allianceRules
        val res = ResourceType.entries[roll(s0, minute, K_PICK + K_NEEDS + idx, ResourceType.entries.size)]
        val raw = rules.request.amountBase + rules.request.amountPerCastleLv * Marching.castleLevel(s0)
        val amount = (raw / AMOUNT_ROUND).coerceAtLeast(1) * AMOUNT_ROUND
        val (id, a) = AllianceLogic.nextId(s0.alliance)
        val req =
            AllianceRequest(
                id,
                p.id,
                RequestKind.RESOURCE,
                resource = res,
                amount = amount,
                expiresEpochSec = t + rules.durationsSec.request,
            )
        val b =
            AllianceLogic.enqueue(
                a.copy(requests = a.requests + req),
                ChatEvent.NpcNeedsHelp(p.id, res.name.lowercase(), amount, id),
                t,
                listOf(p.id),
                ChatKeys.ALLIANCE,
                data,
            )
        return s0.copy(alliance = b)
    }

    private fun addScoutRequest(
        s0: GameState,
        p: PersonaDef,
        t: Long,
        minute: Long,
        idx: Long,
        data: GameData,
    ): GameState {
        val targets = s0.map.barbarians
        if (targets.isEmpty()) return s0
        val b = targets[roll(s0, minute, K_PICK + K_SCOUT + idx, targets.size)]
        val tile = TileRef(b.x, b.y)
        if (s0.alliance.requests.any { it.tile == tile }) return s0
        val (id, a) = AllianceLogic.nextId(s0.alliance)
        val req =
            AllianceRequest(
                id,
                p.id,
                RequestKind.SCOUT,
                tile = tile,
                expiresEpochSec = t + data.allianceRules.durationsSec.request,
            )
        val c =
            AllianceLogic.enqueue(
                a.copy(requests = a.requests + req),
                ChatEvent.NpcAsksScout(p.id, tile, id),
                t,
                listOf(p.id),
                ChatKeys.ALLIANCE,
                data,
            )
        return s0.copy(alliance = c)
    }

    private fun addPurchase(
        s0: GameState,
        p: PersonaDef,
        t: Long,
        minute: Long,
        idx: Long,
        data: GameData,
    ): GameState {
        if (s0.alliance.gifts.size >= data.allianceRules.caps.gifts) return s0
        val skus = data.shopRules.socialProofSkus
        val sku = skus[roll(s0, minute, K_PICK + K_PURCHASE + idx, skus.size)]
        val (id, a) = AllianceLogic.nextId(s0.alliance)
        val gift = GiftBox(id, p.id, sku, t, t + data.allianceRules.durationsSec.gift)
        val b =
            AllianceLogic.enqueue(
                a.copy(gifts = a.gifts + gift),
                ChatEvent.NpcPurchased(p.id, sku, id),
                t,
                listOf(p.id),
                ChatKeys.ALLIANCE,
                data,
            )
        return s0.copy(alliance = b)
    }

    private fun addRally(
        s0: GameState,
        host: PersonaDef,
        t: Long,
        minute: Long,
        active: List<PersonaDef>,
        data: GameData,
    ): GameState {
        val r = data.allianceRules.rally
        val maxLv = minOf(r.maxFortLevel, 1 + Marching.castleLevel(s0) / r.castleLvPerFortLevel)
        val forts =
            Marching.world(
                s0,
                data,
            ).structures.filter { it.type == StructureType.BARBARIAN_FORT && it.level <= maxLv }
        if (forts.isEmpty()) return s0
        val fort = forts[roll(s0, minute, K_PICK + K_RALLY, forts.size)]
        val others =
            active.filter { it.id != host.id }.map { it.id } +
                data.personas.personas.map { it.id }.filter {
                    it != host.id &&
                        active.none {
                                a ->
                            a.id == it
                        }
                }
        val participants = listOf(host.id) + others.take(r.extraParticipants)
        val (id, a) = AllianceLogic.nextId(s0.alliance)
        val rally =
            AiRally(
                id,
                host.id,
                TileRef(fort.x, fort.y),
                fort.level,
                t + data.allianceRules.durationsSec.rallyJoin,
                participants,
            )
        val b =
            AllianceLogic.enqueue(
                a.copy(rallies = a.rallies + rally),
                ChatEvent.NpcCallsRally(host.id, id, fort.level, rally.target),
                t,
                speakersFor(host, active),
                ChatKeys.ALLIANCE,
                data,
            )
        return s0.copy(alliance = b)
    }

    private fun addQuiz(
        s0: GameState,
        p: PersonaDef,
        t: Long,
        data: GameData,
    ): GameState {
        val quizzes = data.allianceRules.quizzes
        val quiz = quizzes[s0.alliance.quizIndex % quizzes.size]
        val a = s0.alliance.copy(lastQuizEpochSec = t, quizIndex = s0.alliance.quizIndex + 1)
        return s0.copy(
            alliance = AllianceLogic.enqueue(a, ChatEvent.PonzuQuiz(quiz.id), t, listOf(p.id), ChatKeys.ALLIANCE, data),
        )
    }

    private fun addHelpEntry(
        s0: GameState,
        t: Long,
        minute: Long,
        active: List<PersonaDef>,
        data: GameData,
    ): GameState {
        val fillers = data.personas.fillerMembers
        val total = active.size + fillers.size
        val pick = roll(s0, minute, K_PICK + K_HELP_REQ, total)
        val isPersona = pick < active.size
        val member = if (isPersona) active[pick].id else fillers[pick - active.size].name
        val kind = if (roll(s0, minute, K_PICK + K_HELP_REQ + 1, 2) == 0) TimerKind.BUILD else TimerKind.RESEARCH
        val (id, a) = AllianceLogic.nextId(s0.alliance)
        val entry = HelpEntry(id, member, isPersona, kind, t + data.allianceRules.durationsSec.helpEntry)
        return s0.copy(alliance = a.copy(helpPool = a.helpPool + entry))
    }

    private const val YUKI = "yuki"
    private const val PONZU = "ponzu"

    /** 現地日の始まりの秒（テスト用）。 */
    internal fun dayStart(day: Long): Long = day * SECONDS_PER_DAY
}
