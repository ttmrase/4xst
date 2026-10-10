package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.event.WeeklyEvents
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.quest.QuestTracker

/**
 * アクションと tick が出した [GameEvent] を、クエストのカウンタとイベントのスコアに反映する（1 か所に集約）。
 * GameSession が dispatch の成功時と advanceToNow（catch-up を含む）の結果に必ず通す。
 * イベント列は tick を分割しても連結が同じなので、結果は分割の仕方に依らない（R3）。
 */
object Tracking {
    /** [events] を数えた状態。 */
    fun apply(
        state: GameState,
        events: List<GameEvent>,
        data: GameData,
    ): GameState = WeeklyEvents.track(QuestTracker.track(state, events), events, data)
}
