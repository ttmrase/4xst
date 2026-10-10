package com.ttmrase.crownfall.core.notify

import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.event.WeeklyEvents
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.RaidStage
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.ShopLogic
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.sim.Simulation

/**
 * 通知の先読み（純関数）。onStop の時点の状態のコピーを、オフライン catch-up と同じ規則で先へ進めて、出る出来事を
 * 通知にする。進めるのはオフライン上限（VIP の延長を含む）までで、それを超える先の出来事は予告しない（帰ってきたときに
 * 起きていない出来事を通知しないため）。復帰の催促だけは、ゲーム内の出来事ではないので上限と無関係に予約する。
 */
internal object Lookahead {
    private const val STEP_SEC = 60L
    private const val DAY_SEC = 86_400L
    private const val MINUTE = 60L
    private const val EVENT_CHECK_SEC = 1_800L

    /** 訓練キューが空いてから「兵が待機中」を出すまで（暫定）。 */
    private const val TROOPS_IDLE_DELAY_SEC = 1_800L

    fun run(
        state: GameState,
        data: GameData,
        horizonSec: Long,
    ): List<PlannedNotice> {
        val now = state.nowEpochSec
        val cap =
            minOf(
                horizonSec,
                data.rules.offlineCapSec + VipCalculator.offlineBonusSec(state, data),
            ).coerceAtLeast(0)
        val limit = now + cap
        val out = ArrayList<PlannedNotice>()
        // いま確定している予定（タイマー・行軍・倉庫・オファー・ラリー・要請）。襲撃は段階で置き換える
        NotificationPlanner.plan(state, data)
            .filter { it.type.category != NoticeCategory.RAID && it.epochSec <= limit }
            .forEach { out.add(it) }
        offerLastCall(state, data, out, limit)
        offerRevived(state, out, limit)
        troopsIdle(state, out, limit)
        simulate(state, data, now, limit, out)
        eventNotices(state, data, limit, out)
        engagement(state, data, limit, out)
        comeback(state, data, out)
        return group(out, now)
    }

    private fun simulate(
        state: GameState,
        data: GameData,
        now: Long,
        limit: Long,
        out: MutableList<PlannedNotice>,
    ) {
        val known = state.alliance.pendingChat.map { it.id }.toHashSet()
        var s = state
        while (s.nowEpochSec < limit) {
            val dt = minOf(STEP_SEC, limit - s.nowEpochSec)
            val r = Simulation.tick(s, dt, data)
            s = r.state
            for (e in r.events) if (e is GameEvent.RaidStageReached) raidNotice(e, data, now, out)
        }
        for (p in s.alliance.pendingChat) {
            if (p.id in known || p.createdEpochSec <= now) continue
            val type =
                when {
                    p.channel.startsWith("dm_") -> NoticeType.ALLIANCE_DM
                    p.event is ChatEvent.NpcNeedsHelp || p.event is ChatEvent.NpcAsksScout ||
                        p.event is ChatEvent.NpcCallsRally -> NoticeType.ALLIANCE_HELP
                    else -> null
                }
            if (type != null) out.add(PlannedNotice(p.createdEpochSec, type, type.channel))
        }
    }

    private fun raidNotice(
        e: GameEvent.RaidStageReached,
        data: GameData,
        now: Long,
        out: MutableList<PlannedNotice>,
    ) {
        val offset = data.notifyRules.raid.stages.first { it.stage == e.stage.name }.offsetSec
        val at = e.raidAt - offset
        val type =
            when (e.stage) {
                RaidStage.SCOUTED -> NoticeType.RAID_SCOUTED
                RaidStage.MARCHING -> NoticeType.RAID_MARCHING
                RaidStage.T_MINUS_1H -> NoticeType.RAID_1H
                RaidStage.T_MINUS_15M -> NoticeType.RAID_15M
                RaidStage.STARTED -> NoticeType.RAID_STARTED
                RaidStage.RESULT -> if (e.defended) NoticeType.RAID_DEFENDED else NoticeType.RAID_DEFEATED
            }
        if (at > now) out.add(PlannedNotice(at, type, type.channel))
    }

    private fun offerLastCall(
        state: GameState,
        data: GameData,
        out: MutableList<PlannedNotice>,
        limit: Long,
    ) {
        for (o in OfferEngine.active(state)) {
            val at = o.expiresEpochSec - data.notifyRules.offerLeadSec.min()
            if (at > state.nowEpochSec && at <= limit) {
                out.add(
                    PlannedNotice(at, NoticeType.OFFER_LAST_CALL, NoticeChannel.OFFER),
                )
            }
        }
    }

    /** 量産型の頻度なら、期限切れのオファーは一度だけ「復活！」として出し直される（OfferEngine.refresh）。 */
    private fun offerRevived(
        state: GameState,
        out: MutableList<PlannedNotice>,
        limit: Long,
    ) {
        if (state.shop.settings.frequency != OfferFrequency.MASS) return
        for (o in OfferEngine.active(state)) {
            if (o.revived || o.expiresEpochSec > limit) continue
            out.add(PlannedNotice(o.expiresEpochSec, NoticeType.OFFER_REVIVED, NoticeChannel.OFFER))
        }
    }

    /** 訓練が全部終わって、キューが空いたまま放置されている（新しい訓練は入れないものとして予測する）。 */
    private fun troopsIdle(
        state: GameState,
        out: MutableList<PlannedNotice>,
        limit: Long,
    ) {
        val lastEnd = state.timers.filter { it.kind == TimerKind.TRAIN }.maxOfOrNull { it.endEpochSec } ?: return
        val at = lastEnd + TROOPS_IDLE_DELAY_SEC
        if (at > state.nowEpochSec && at <= limit) out.add(note(at, NoticeType.TROOPS_IDLE))
    }

    private fun eventNotices(
        state: GameState,
        data: GameData,
        limit: Long,
        out: MutableList<PlannedNotice>,
    ) {
        if (state.event.weekId < 0) return
        val now = state.nowEpochSec
        val end = WeeklyEvents.endEpochSec(state)
        val ending = end - data.notifyRules.eventEndingLeadSec
        if (ending > now && ending <= limit) {
            out.add(
                PlannedNotice(ending, NoticeType.EVENT_ENDING, NoticeType.EVENT_ENDING.channel),
            )
        }
        if (WeeklyEvents.selfScore(state, data) <= 0) return
        // 自分が動かない前提で、順位が落ちる最初の時刻（NPC の追従は決定論的）
        val rank0 = WeeklyEvents.rank(state, data)
        var t = now + EVENT_CHECK_SEC
        while (t <= minOf(limit, end - 1)) {
            val rank = WeeklyEvents.rank(state.copy(nowEpochSec = t), data)
            if (rank > rank0) {
                out.add(PlannedNotice(t, NoticeType.EVENT_DROPPED, NoticeType.EVENT_DROPPED.channel, 1, rank))
                break
            }
            t += EVENT_CHECK_SEC
        }
    }

    private fun engagement(
        state: GameState,
        data: GameData,
        limit: Long,
        out: MutableList<PlannedNotice>,
    ) {
        val r = data.notifyRules
        val off = state.settings.utcOffsetSec
        val now = state.nowEpochSec
        val chestMax = data.quests.chests.maxOfOrNull { it.points } ?: 0
        if (QuestLogic.dailyPoints(state, data) < chestMax) {
            nextLocal(
                now,
                off,
                r.dailyReminderLocalMinute,
            )?.let { if (it <= limit) out.add(note(it, NoticeType.DAILY_LEFT)) }
        }
        nextLocal(now, off, r.loginBonusLocalMinute, skipToday = true)?.let {
            if (it <= limit) out.add(note(it, NoticeType.LOGIN_BONUS))
        }
        val usedToday = state.shop.wheelFreeDay == ShopLogic.localDay(state)
        nextLocal(now, off, r.freeWheelLocalMinute, skipToday = usedToday)?.let {
            if (it <= limit) out.add(note(it, NoticeType.FREE_WHEEL))
        }
    }

    private fun comeback(
        state: GameState,
        data: GameData,
        out: MutableList<PlannedNotice>,
    ) {
        data.notifyRules.comebackDelaysSec.forEachIndexed { i, delay ->
            out.add(PlannedNotice(state.nowEpochSec + delay, NoticeType.COMEBACK, NoticeType.COMEBACK.channel, 1, i))
        }
    }

    private fun note(
        at: Long,
        type: NoticeType,
    ) = PlannedNotice(at, type, type.channel)

    /** 現地時刻の 1 日の [minuteOfDay] 分になる次の時刻（[skipToday] なら翌日以降）。 */
    internal fun nextLocal(
        now: Long,
        utcOffsetSec: Int,
        minuteOfDay: Int,
        skipToday: Boolean = false,
    ): Long? {
        val localNow = now + utcOffsetSec
        val dayStart = Math.floorDiv(localNow, DAY_SEC) * DAY_SEC
        var at = dayStart + minuteOfDay * MINUTE
        if (skipToday || at <= localNow) at += DAY_SEC
        return at - utcOffsetSec
    }

    private fun group(
        list: List<PlannedNotice>,
        now: Long,
    ): List<PlannedNotice> =
        list.filter { it.epochSec > now }
            .groupBy { Triple(it.epochSec, it.type, it.param) }
            .map { (k, v) -> PlannedNotice(k.first, k.second, v.first().channel, v.sumOf { it.count }, k.third) }
            .sortedWith(compareBy({ it.epochSec }, { it.type.ordinal }, { it.param }))
}
