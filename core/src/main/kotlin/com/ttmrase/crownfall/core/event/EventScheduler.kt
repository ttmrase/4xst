package com.ttmrase.crownfall.core.event

import com.ttmrase.crownfall.core.alliance.AllianceLogic
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.march.MailBox
import com.ttmrase.crownfall.core.model.EventState
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.ShopLogic

/**
 * 週替わりイベントの tick 境界処理。週の終わり（集計・報酬メール・次の週の開始）と、終了 24h 前の処理
 * （2〜4 位なら逆転パック、同盟チャット）を、その時刻ちょうどに行う（R3）。
 */
internal object EventScheduler {
    private const val PODIUM_MIN = 2
    private const val PODIUM_MAX = 4
    private const val PERMILLE = 1000L

    /** 次の境界（週の終わり、または終了 24h 前）。常に現在より後。 */
    fun nextBoundary(
        state: GameState,
        data: GameData,
    ): Long {
        val end = WeeklyEvents.endEpochSec(state)
        // 時刻が Long の上限に近いと週の終わりを表せない。境界は無いものとして扱う（時計が壊れていても止まらない）
        if (end <= state.nowEpochSec) return Long.MAX_VALUE
        val warnAt = end - data.events.warnBeforeEndSec
        val current = WeeklyEvents.weekId(state.nowEpochSec, state.settings.utcOffsetSec)
        return if (state.event.weekId == current && !state.event.warned && warnAt > state.nowEpochSec) warnAt else end
    }

    fun settle(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val off = state.settings.utcOffsetSec
        val week = WeeklyEvents.weekId(state.nowEpochSec, off)
        var s = state
        if (s.event.weekId == week) return warn(s, data)
        if (s.event.weekId >= 0) s = settleWeek(s, data, events)
        return s.copy(event = EventState(weekId = week, basePower = PowerCalculator.total(s, data)))
    }

    /** 終了 24h 前: 2〜4 位なら「1 位との差」の逆転パック、自力で参加していれば同盟チャットで励ます。 */
    private fun warn(
        state: GameState,
        data: GameData,
    ): GameState {
        val end = WeeklyEvents.endEpochSec(state)
        if (state.event.warned || state.nowEpochSec < end - data.events.warnBeforeEndSec) return state
        var s = state.copy(event = state.event.copy(warned = true))
        if (WeeklyEvents.selfScore(s, data) <= 0) return s
        val rank = WeeklyEvents.rank(s, data)
        if (rank in PODIUM_MIN..PODIUM_MAX) {
            s = OfferEngine.onRankingNear(s, rank, WeeklyEvents.gapToFirst(s, data), data)
        }
        return AllianceLogic.enqueueReactive(s, ChatEvent.EventRanking(rank), listOf(CHEER_LEADER), data)
    }

    /** 週の終わりの集計。順位報酬を付与し、メールと同盟チャットで知らせる（参加した人だけ・1 回だけ）。 */
    private fun settleWeek(
        state: GameState,
        data: GameData,
        events: MutableList<GameEvent>,
    ): GameState {
        val week = state.event.weekId
        val def = WeeklyEvents.definition(week, data)
        val self =
            if (def.kind == "POWER") {
                maxOf(0L, PowerCalculator.total(state, data) - state.event.basePower)
            } else {
                state.event.selfScore
            }
        if (self < data.events.participation.minScore) return state
        val total = self + state.event.bonusScore
        val npcs = WeeklyEvents.npcRows(state, data, week, WeeklyEvents.followSelf(state, self), PERMILLE)
        val rank = npcs.count { it.score > total } + 1
        val reward = data.events.rewards.first { rank in it.from..it.to }.reward
        var s = ShopLogic.grant(state, reward, data)
        val params =
            mapOf(
                "rank" to rank.toLong(),
                "score" to total,
                "event" to data.events.events.indexOf(def).toLong(),
                "gems" to reward.gems,
            )
        s = MailBox.system(s, s.nowEpochSec, "event_result", params, events)
        return AllianceLogic.enqueueReactive(s, ChatEvent.EventRanking(rank), listOf(CHEER_LEADER), data)
    }

    private const val CHEER_LEADER = "kaede"
}
