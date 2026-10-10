package com.ttmrase.crownfall.core.notify

import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.Raids
import com.ttmrase.crownfall.core.military.Military
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.sim.Simulation

/** 通知のカテゴリ（強度とユーザー設定で ON/OFF を決める単位）。 */
enum class NoticeCategory { RAID, TIMER, ALLIANCE, OFFER, EVENT, ENGAGEMENT, COMEBACK, SUMMARY }

/** 通知チャンネル（Android の通知チャンネルに対応）。 */
enum class NoticeChannel { TIMER, OFFER, ALLIANCE, RAID, NOTICE }

/** 通知の種類。[category] は強度の判定に、[channel] は配送に使う。 */
enum class NoticeType(val category: NoticeCategory, val channel: NoticeChannel) {
    BUILD_DONE(NoticeCategory.TIMER, NoticeChannel.TIMER),
    RESEARCH_DONE(NoticeCategory.TIMER, NoticeChannel.TIMER),
    TRAIN_DONE(NoticeCategory.TIMER, NoticeChannel.TIMER),
    HEAL_DONE(NoticeCategory.TIMER, NoticeChannel.TIMER),
    MARCH_RETURN(NoticeCategory.TIMER, NoticeChannel.TIMER),
    STORAGE_FULL(NoticeCategory.TIMER, NoticeChannel.TIMER),
    OFFER_EXPIRING(NoticeCategory.OFFER, NoticeChannel.OFFER),
    OFFER_LAST_CALL(NoticeCategory.OFFER, NoticeChannel.OFFER),
    OFFER_REVIVED(NoticeCategory.OFFER, NoticeChannel.OFFER),
    TROOPS_IDLE(NoticeCategory.TIMER, NoticeChannel.TIMER),
    RAID_WARNING(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_ARRIVAL(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_SCOUTED(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_MARCHING(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_1H(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_15M(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_STARTED(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_DEFENDED(NoticeCategory.RAID, NoticeChannel.RAID),
    RAID_DEFEATED(NoticeCategory.RAID, NoticeChannel.RAID),
    RALLY_DEADLINE(NoticeCategory.ALLIANCE, NoticeChannel.ALLIANCE),
    REQUEST_EXPIRING(NoticeCategory.ALLIANCE, NoticeChannel.ALLIANCE),
    ALLIANCE_HELP(NoticeCategory.ALLIANCE, NoticeChannel.ALLIANCE),
    ALLIANCE_DM(NoticeCategory.ALLIANCE, NoticeChannel.ALLIANCE),
    EVENT_ENDING(NoticeCategory.EVENT, NoticeChannel.NOTICE),
    EVENT_DROPPED(NoticeCategory.EVENT, NoticeChannel.NOTICE),
    DAILY_LEFT(NoticeCategory.ENGAGEMENT, NoticeChannel.NOTICE),
    LOGIN_BONUS(NoticeCategory.ENGAGEMENT, NoticeChannel.NOTICE),
    FREE_WHEEL(NoticeCategory.ENGAGEMENT, NoticeChannel.NOTICE),
    COMEBACK(NoticeCategory.COMEBACK, NoticeChannel.NOTICE),
    SUMMARY(NoticeCategory.SUMMARY, NoticeChannel.NOTICE),
}

/**
 * 予定された通知 1 件。[epochSec] に出す。[count] は同じ時刻・同じ種類をまとめた件数、
 * [param] は種類ごとの補足（STORAGE_FULL なら資源の序数）。
 */
data class PlannedNotice(
    val epochSec: Long,
    val type: NoticeType,
    val channel: NoticeChannel,
    val count: Int = 1,
    val param: Int = 0,
)

/**
 * 通知の計画（純関数）。onStop の時点で確定している未来の時刻だけを列挙する。
 * 背景でシミュレーションは回さないので、未来の AI のメンション・DM は予測できず、通知しない（既知の制約）。
 * 過去の時刻は含めず、同じ時刻・同じ種類はまとめる。
 */
object NotificationPlanner {
    private const val HOUR = 3600L

    /** 先読み（[Lookahead]）。[horizonSec] 先までの通知を、オフライン上限を超えない範囲で予測して列挙する。 */
    fun lookahead(
        state: GameState,
        data: GameData,
        horizonSec: Long = data.notifyRules.lookaheadHorizonSec,
    ): List<PlannedNotice> = Lookahead.run(state, data, horizonSec)

    private const val OFFER_LEAD_SEC = HOUR
    private const val RALLY_LEAD_SEC = 300L
    private const val REQUEST_LEAD_SEC = 600L
    private const val FULL_HORIZON_SEC = 48 * HOUR
    private const val MAX_NOTICES = 60

    fun plan(
        state: GameState,
        data: GameData,
    ): List<PlannedNotice> {
        val now = state.nowEpochSec
        val raw = ArrayList<PlannedNotice>()

        fun add(
            at: Long,
            type: NoticeType,
            channel: NoticeChannel,
            param: Int = 0,
        ) {
            if (at > now) raw.add(PlannedNotice(at, type, channel, 1, param))
        }
        for (t in state.timers) {
            val type =
                when (t.kind) {
                    TimerKind.BUILD -> NoticeType.BUILD_DONE
                    TimerKind.RESEARCH -> NoticeType.RESEARCH_DONE
                    TimerKind.TRAIN -> NoticeType.TRAIN_DONE
                    TimerKind.HEAL -> NoticeType.HEAL_DONE
                }
            add(t.endEpochSec, type, NoticeChannel.TIMER)
        }
        for (m in state.marches) {
            if (m.phase == MarchPhase.RETURNING) add(m.phaseEndEpochSec, NoticeType.MARCH_RETURN, NoticeChannel.TIMER)
        }
        storageFull(state, data).forEach {
                (at, res) ->
            add(at, NoticeType.STORAGE_FULL, NoticeChannel.TIMER, res.ordinal)
        }
        for (o in OfferEngine.active(state)) {
            add(o.expiresEpochSec - OFFER_LEAD_SEC, NoticeType.OFFER_EXPIRING, NoticeChannel.OFFER)
        }
        val raid = state.map.nextRaidEpochSec
        if (raid > now && raid != Long.MAX_VALUE) {
            val warn = raid - Raids.warnSec(state, data)
            if (!state.map.raidWarned && warn < raid && warn > now) {
                add(
                    warn,
                    NoticeType.RAID_WARNING,
                    NoticeChannel.RAID,
                )
            }
            add(raid, NoticeType.RAID_ARRIVAL, NoticeChannel.RAID)
        }
        for (r in state.alliance.rallies) {
            add(r.joinDeadlineEpochSec - RALLY_LEAD_SEC, NoticeType.RALLY_DEADLINE, NoticeChannel.ALLIANCE)
        }
        for (q in state.alliance.requests) {
            add(q.expiresEpochSec - REQUEST_LEAD_SEC, NoticeType.REQUEST_EXPIRING, NoticeChannel.ALLIANCE)
        }
        return raw.groupBy { Triple(it.epochSec, it.type, it.param) }
            .map { (k, v) -> PlannedNotice(k.first, k.second, v.first().channel, v.size, k.third) }
            .sortedWith(compareBy({ it.epochSec }, { it.type.ordinal }, { it.param }))
            .take(MAX_NOTICES)
    }

    /** 各資源が上限に達する見込みの時刻（生産だけが続くとして、48 時間以内のものだけ）。 */
    private fun storageFull(
        state: GameState,
        data: GameData,
    ): List<Pair<Long, ResourceType>> {
        val cap = Simulation.storageCap(state, data)
        if (cap <= 0) return emptyList()
        val buffs = BuffAggregator.compute(state, data)
        val upkeep = Military.upkeepPerHour(state, data, buffs)
        return ResourceType.entries.mapNotNull { type ->
            val have = state.resources[type]
            val gross = Simulation.hourlyRate(state, type, data, buffs)
            val net = if (type == ResourceType.FOOD) gross - upkeep else gross
            if (have >= cap || net <= 0) return@mapNotNull null
            val remainder = state.productionRemainder[type]
            val needUnits = (cap - have) * HOUR - remainder
            val secs = (needUnits + net - 1) / net
            if (secs !in 1..FULL_HORIZON_SEC) null else state.nowEpochSec + secs to type
        }
    }
}
