package com.ttmrase.crownfall.core.title

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.quest.QuestLogic

/**
 * 称号（M11 T11.3）。名乗れるかは状態から導く（城の Lv・英雄の数・研究・討伐数は減らないので、保存は選んだ称号だけ）。
 */
object Titles {
    /** いま名乗れる称号の id（titles.json の並び順）。 */
    fun owned(
        state: GameState,
        data: GameData,
    ): List<String> =
        data.titles.titles.filter { t ->
            t.cond != null && QuestLogic.progress(t.cond, state, data).done
        }.map { it.id }

    /** 名前の横に出す称号（選んだもの、無ければ名乗れるうちで一番格の高いもの）。 */
    fun displayed(
        state: GameState,
        data: GameData,
    ): String? {
        val owned = owned(state, data)
        return state.selectedTitle?.takeIf { it in owned } ?: owned.lastOrNull()
    }

    /** 称号 [id]（null で「おまかせ」）を選ぶ。名乗れないものは選べない（null を返す）。 */
    fun select(
        state: GameState,
        id: String?,
        data: GameData,
    ): GameState? = if (id == null || id in owned(state, data)) state.copy(selectedTitle = id) else null
}
