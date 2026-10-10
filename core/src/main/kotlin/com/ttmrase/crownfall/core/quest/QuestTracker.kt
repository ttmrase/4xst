package com.ttmrase.crownfall.core.quest

import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState

/**
 * クエストのカウンタを GameEvent から数える（純関数）。通算とその日のカウンタの両方に足す。
 * 状態から判定できる条件（建物 Lv・戦力・研究・英雄数）はカウンタを持たない。
 * GameSession が dispatch と advanceToNow（catch-up を含む）の結果に必ず通す。
 */
object QuestTracker {
    /** [events] を数えた状態。数えるものが無ければ、日付が変わっているときだけデイリーをリセットして返す。 */
    fun track(
        state: GameState,
        events: List<GameEvent>,
    ): GameState {
        val s = QuestLogic.rollDaily(state)
        val add = HashMap<String, Long>()
        for (e in events) {
            when (e) {
                is GameEvent.HelpGiven -> add.merge("help", e.count.toLong(), Long::plus)
                is GameEvent.GatherCompleted -> add.merge("gather", 1L, Long::plus)
                is GameEvent.TroopsTrained -> {
                    add.merge("train", 1L, Long::plus)
                    add.merge("trainTroops", e.count, Long::plus)
                }
                is GameEvent.ResearchCompleted -> add.merge("research", 1L, Long::plus)
                is GameEvent.BuildingLevelUp -> add.merge("build", 1L, Long::plus)
                is GameEvent.HuntWon -> add.merge("hunt", 1L, Long::plus)
                is GameEvent.Donated -> add.merge("donate", 1L, Long::plus)
                is GameEvent.HeroSummoned -> add.merge("summon", e.result.results.size.toLong(), Long::plus)
                GameEvent.ShopOpened -> add.merge("shop", 1L, Long::plus)
                GameEvent.ChatPosted -> add.merge("chat", 1L, Long::plus)
                GameEvent.RallyJoined -> add.merge("rally", 1L, Long::plus)
                else -> Unit
            }
        }
        if (add.isEmpty()) return s
        val counters = s.quest.counters.toMutableMap()
        val daily = s.quest.dailyCounts.toMutableMap()
        for ((k, n) in add) {
            counters[k] = (counters[k] ?: 0L) + n
            daily[k] = (daily[k] ?: 0L) + n
        }
        return s.copy(quest = s.quest.copy(counters = counters, dailyCounts = daily))
    }
}
