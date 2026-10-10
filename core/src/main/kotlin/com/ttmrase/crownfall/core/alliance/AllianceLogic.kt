package com.ttmrase.crownfall.core.alliance

import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.ActionLogEntry
import com.ttmrase.crownfall.core.model.AllianceState
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.GiftBox
import com.ttmrase.crownfall.core.model.LogKind
import com.ttmrase.crownfall.core.model.PendingChat

/** 同盟状態の共通操作（キュー・ID・好感度・日次カウンタ・ログ）。すべて純関数。 */
object AllianceLogic {
    /** 新しい ID と、その ID を消費した状態。 */
    fun nextId(a: AllianceState): Pair<Long, AllianceState> = a.nextId to a.copy(nextId = a.nextId + 1)

    /** 現地日が変わっていれば日次カウンタをリセットする。 */
    fun rollDay(
        a: AllianceState,
        day: Long,
    ): AllianceState =
        if (a.day == day) a else a.copy(day = day, helpedToday = 0, donatedToday = 0, shopBought = emptyMap())

    /**
     * チャットのきっかけを積む。上限を超えたら Ambient 系の古いものから、無ければ最古を捨てる。
     * [speakers] の先頭が主発話者。
     */
    fun enqueue(
        a: AllianceState,
        event: ChatEvent,
        epochSec: Long,
        speakers: List<String>,
        channel: String,
        data: GameData,
    ): AllianceState {
        val (id, b) = nextId(a)
        var list = b.pendingChat + PendingChat(id, event, epochSec, speakers, channel)
        val cap = data.allianceRules.caps.pendingChat
        while (list.size > cap) {
            val victim =
                list.firstOrNull { it.event is ChatEvent.Ambient || it.event is ChatEvent.WorldAmbient } ?: list.first()
            list = list - victim
        }
        return b.copy(pendingChat = list, chatQueuedTotal = b.chatQueuedTotal + 1)
    }

    /** 行動ログを足す（直近 [AllianceCaps.actionLog] 件）。 */
    fun log(
        a: AllianceState,
        entry: ActionLogEntry,
        data: GameData,
    ): AllianceState = a.copy(actionLog = (a.actionLog + entry).takeLast(data.allianceRules.caps.actionLog))

    /** [LogKind.HELPED_MEMBER] などの直近 [windowSec] 秒の回数（[persona] 指定時はその相手だけ）。 */
    fun logCount(
        a: AllianceState,
        kind: LogKind,
        sinceEpochSec: Long,
        persona: String? = null,
    ): Int =
        a.actionLog.count {
            it.kind == kind && it.epochSec >= sinceEpochSec && (persona == null || it.persona == persona)
        }

    /**
     * 好感度を [delta] 加え、レベルが上がったら FavorMilestone を DM に積み、ギフト箱を 1 つ付ける
     * （同じレベルでは 1 回だけ）。
     */
    fun addFavor(
        state: GameState,
        persona: String,
        delta: Int,
        data: GameData,
    ): GameState {
        val def = data.personas.persona(persona) ?: return state
        if (delta <= 0) return state
        var a = state.alliance
        val giftExpiry = state.nowEpochSec + data.allianceRules.durationsSec.gift
        val now = (a.favor[persona] ?: 0) + delta
        a = a.copy(favor = a.favor + (persona to now))
        val before = a.favorLevel[persona] ?: 0
        val after = Personas.favorLevel(def, now)
        if (after > before) {
            a = a.copy(favorLevel = a.favorLevel + (persona to after))
            for (lv in before + 1..after) {
                val event = ChatEvent.FavorMilestone(persona, lv)
                a = enqueue(a, event, state.nowEpochSec, listOf(persona), ChatKeys.dm(persona), data)
                val (id, b) = nextId(a)
                a =
                    b.copy(
                        gifts =
                            (b.gifts + GiftBox(id, persona, "favor_$lv", state.nowEpochSec, giftExpiry))
                                .takeLast(data.allianceRules.caps.gifts),
                    )
            }
        }
        return state.copy(alliance = a)
    }
}
