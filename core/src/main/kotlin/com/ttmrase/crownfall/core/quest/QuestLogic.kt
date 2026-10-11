package com.ttmrase.crownfall.core.quest

import com.ttmrase.crownfall.core.data.ChapterDef
import com.ttmrase.crownfall.core.data.ChestDef
import com.ttmrase.crownfall.core.data.DailyDef
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.QuestCond
import com.ttmrase.crownfall.core.data.QuestDef
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.QuestState
import com.ttmrase.crownfall.core.shop.ShopLogic

/** 進捗（[current] / [target]）。 */
data class Progress(val current: Long, val target: Long) {
    /** 達成しているか。 */
    val done: Boolean get() = current >= target

    /** 0.0〜1.0。 */
    val fraction: Float get() = if (target <= 0) 1f else (current.toFloat() / target.toFloat()).coerceIn(0f, 1f)
}

/** クエストの判定（純関数）。カウンタは `QuestTracker` が GameEvent から数え、状態から判定できる条件はここで都度判定する。 */
object QuestLogic {
    /** その日のカウンタを持っていなければ（日付が変わっていれば）デイリーをリセットした状態にする。 */
    fun rollDaily(state: GameState): GameState {
        val day = ShopLogic.localDay(state)
        if (state.quest.dailyDay == day) return state
        return state.copy(
            quest =
                state.quest.copy(
                    dailyDay = day,
                    dailyCounts = emptyMap(),
                    dailyClaimed = emptySet(),
                    dailyChests = emptySet(),
                ),
        )
    }

    /** 表示 / 判定用の、日付をまたいでいたらリセット済みに見えるデイリー状態（状態は変えない）。 */
    fun view(state: GameState): QuestState = rollDaily(state).quest

    /** 条件 [c] の進捗。[power] は戦力の計算（必要なときだけ呼ぶ）。 */
    fun progress(
        c: QuestCond,
        state: GameState,
        data: GameData,
        power: () -> Long = { PowerCalculator.total(state, data) },
    ): Progress =
        when (c.type) {
            "building" -> {
                val level = state.slots.filter { it.type.name.lowercase() == c.building }.maxOfOrNull { it.level } ?: 0
                Progress(level.toLong(), c.lv.toLong())
            }
            "power" -> Progress(power(), c.value)
            "research" -> Progress(state.research.values.sumOf { it.toLong() }, c.total.toLong())
            "heroes" -> Progress(state.heroes.size.toLong(), c.count.toLong())
            else -> Progress(state.quest.counters[c.key] ?: 0L, c.target)
        }

    /** デイリー項目 [d] の今日の進捗。 */
    fun dailyProgress(
        d: DailyDef,
        state: GameState,
    ): Progress = Progress(view(state).dailyCounts[d.key] ?: 0L, d.target)

    /** いま出すデイリー項目（解放済みの機能のものだけ）。 */
    fun dailies(
        state: GameState,
        data: GameData,
    ): List<DailyDef> =
        data.quests.dailies.filter { d ->
            val f = d.feature?.let { Feature.fromId(it) }
            f == null || FeatureGate.unlocked(state, data, f)
        }

    /** 今日のデイリーのポイント（受け取り済みの項目の合計）。 */
    fun dailyPoints(
        state: GameState,
        data: GameData,
    ): Int {
        val q = view(state)
        return dailies(state, data).filter { it.id in q.dailyClaimed }.sumOf { it.points }
    }

    /**
     * 宝箱の段 [chest] を開けるのに要るポイント。出ている項目の合計点に比例させる
     * （全部こなせば最上段に届く。全項目が出ているときは宝箱の定義のまま）。
     */
    fun chestThreshold(
        chest: ChestDef,
        state: GameState,
        data: GameData,
    ): Int {
        val top = data.quests.chests.maxOfOrNull { it.points } ?: return chest.points
        val total = dailies(state, data).sumOf { it.points }
        if (top <= 0 || total >= top) return chest.points
        return maxOf(1, (chest.points * total + top - 1) / top).coerceAtMost(total.coerceAtLeast(1))
    }

    /** メインの現在のチャプター（最初の未受け取り）。全部受け取り済みなら null。 */
    fun currentChapter(
        state: GameState,
        data: GameData,
    ): ChapterDef? = data.quests.chapters.firstOrNull { it.id !in state.quest.claimed }

    /** チャプター [c] の全タスクが達成済みか。 */
    fun chapterDone(
        c: ChapterDef,
        state: GameState,
        data: GameData,
    ): Boolean {
        val power = lazy { PowerCalculator.total(state, data) }
        return c.tasks.all { progress(it.cond, state, data) { power.value }.done }
    }

    /** 受け取れる宝箱の段（ポイントに達していて未受け取り）。 */
    fun claimableChests(
        state: GameState,
        data: GameData,
    ): List<ChestDef> {
        val points = dailyPoints(state, data)
        val q = view(state)
        return data.quests.chests.filter { points >= chestThreshold(it, state, data) && it.tier !in q.dailyChests }
    }

    /** 達成済みで未受け取りの同盟クエスト・実績。 */
    fun claimableOneShots(
        state: GameState,
        data: GameData,
    ): List<QuestDef> {
        val power = lazy { PowerCalculator.total(state, data) }
        return (data.quests.alliance + data.quests.achievements).filter {
            it.id !in state.quest.claimed && progress(it.cond, state, data) { power.value }.done
        }
    }

    /** 未受け取りの数（城内とクエストの赤バッジ）。 */
    fun claimableCount(
        state: GameState,
        data: GameData,
    ): Int {
        var n = 0
        currentChapter(state, data)?.let { if (chapterDone(it, state, data)) n++ }
        val q = view(state)
        n += dailies(state, data).count { it.id !in q.dailyClaimed && dailyProgress(it, state).done }
        n += claimableChests(state, data).size
        n += claimableOneShots(state, data).size
        return n
    }
}
