package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.model.BattleReport
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Mail
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.ScoutReport

/** メールの追加・既読。受信箱は古い順に並べ、上限 [CAP] を超えたら既読の古いものから捨てる。 */
object MailBox {
    const val CAP = 200

    /** [mail]（id は無視して採番）を受信箱へ入れる。 */
    internal fun add(
        state: GameState,
        mail: Mail,
        events: MutableList<GameEvent>,
    ): GameState {
        val m = mail.copy(id = state.nextMailId)
        var list = state.mails + m
        if (list.size > CAP) {
            val drop = list.firstOrNull { it.read && it.id != m.id } ?: list.first()
            list = list - drop
        }
        events.add(GameEvent.MailReceived(m.id, m.kind))
        return state.copy(mails = list, nextMailId = state.nextMailId + 1)
    }

    internal fun system(
        state: GameState,
        at: Long,
        code: String,
        params: Map<String, Long>,
        events: MutableList<GameEvent>,
    ): GameState = add(state, Mail(0, MailKind.SYSTEM, at, systemCode = code, params = params), events)

    internal fun battle(
        state: GameState,
        at: Long,
        report: BattleReport,
        events: MutableList<GameEvent>,
    ): GameState = add(state, Mail(0, MailKind.BATTLE, at, battle = report), events)

    internal fun scout(
        state: GameState,
        at: Long,
        report: ScoutReport,
        events: MutableList<GameEvent>,
    ): GameState = add(state, Mail(0, MailKind.SCOUT, at, scout = report), events)

    /** メール [id] を既読にする（無ければ何もしない）。 */
    fun markRead(
        state: GameState,
        id: Long,
    ): GameState =
        if (state.mails.none { it.id == id && !it.read }) {
            state
        } else {
            state.copy(mails = state.mails.map { if (it.id == id) it.copy(read = true) else it })
        }

    /** すべて既読にする。 */
    fun markAllRead(state: GameState): GameState =
        if (state.mails.all { it.read }) state else state.copy(mails = state.mails.map { it.copy(read = true) })

    /** 未読数。 */
    fun unread(state: GameState): Int = state.mails.count { !it.read }
}
