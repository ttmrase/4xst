package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.shop.ShopLogic

/** クエストの受け取り（タップしたときだけ。自動では受け取らない）。 */
internal object QuestActions {
    private fun fail(reason: ActionFailure) = ActionResult.Failure(reason)

    /** [questId] の報酬（チャプター / デイリー項目のポイント / 同盟クエスト / 実績）を受け取る。 */
    fun claimQuest(
        state: GameState,
        questId: String,
        data: GameData,
    ): ActionResult {
        val q = data.quests
        val s0 = QuestLogic.rollDaily(state)
        q.chapters.indexOfFirst { it.id == questId }.takeIf { it >= 0 }?.let { index ->
            val chapter = q.chapters[index]
            if (questId in s0.quest.claimed) return fail(ActionFailure.AlreadyClaimed)
            val earlierDone = q.chapters.take(index).all { it.id in s0.quest.claimed }
            if (!earlierDone || !QuestLogic.chapterDone(chapter, s0, data)) return fail(ActionFailure.NothingToClaim)
            val granted = ShopLogic.grant(s0, chapter.reward, data)
            return ActionResult.Success(
                granted.copy(quest = granted.quest.copy(claimed = granted.quest.claimed + questId)),
            )
        }
        q.dailies.firstOrNull { it.id == questId }?.let { d ->
            if (questId in s0.quest.dailyClaimed) return fail(ActionFailure.AlreadyClaimed)
            if (!QuestLogic.dailyProgress(d, s0).done) return fail(ActionFailure.NothingToClaim)
            return ActionResult.Success(s0.copy(quest = s0.quest.copy(dailyClaimed = s0.quest.dailyClaimed + questId)))
        }
        val one =
            (q.alliance + q.achievements).firstOrNull { it.id == questId }
                ?: return fail(ActionFailure.QuestNotFound)
        if (questId in s0.quest.claimed) return fail(ActionFailure.AlreadyClaimed)
        if (!QuestLogic.progress(one.cond, s0, data).done) return fail(ActionFailure.NothingToClaim)
        val granted = ShopLogic.grant(s0, one.reward, data)
        return ActionResult.Success(granted.copy(quest = granted.quest.copy(claimed = granted.quest.claimed + questId)))
    }

    /** デイリー宝箱の段 [tier] を受け取る（ポイントが足りていて未受け取り）。 */
    fun claimDailyChest(
        state: GameState,
        tier: Int,
        data: GameData,
    ): ActionResult {
        val s0 = QuestLogic.rollDaily(state)
        val chest = data.quests.chests.firstOrNull { it.tier == tier } ?: return fail(ActionFailure.QuestNotFound)
        if (tier in s0.quest.dailyChests) return fail(ActionFailure.AlreadyClaimed)
        if (QuestLogic.dailyPoints(s0, data) < chest.points) return fail(ActionFailure.NothingToClaim)
        val granted = ShopLogic.grant(s0, chest.reward, data)
        return ActionResult.Success(
            granted.copy(quest = granted.quest.copy(dailyChests = granted.quest.dailyChests + tier)),
        )
    }

    /** ショップ画面を開いた（デイリーの「ショップを開く」用。状態は変えず、イベントだけ出す）。 */
    fun recordShopOpened(state: GameState): ActionResult = ActionResult.Success(state, listOf(GameEvent.ShopOpened))
}
