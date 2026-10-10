package com.ttmrase.crownfall.core

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.NewGame
import kotlin.test.assertIs

/** テスト共通の実データと小道具。 */
object TestSupport {
    const val T0 = 1_000_000L

    val data: GameData by lazy { ClasspathDataSource.load() }

    fun newGame(
        now: Long = T0,
        seed: Long = 42,
    ): GameState = NewGame.create(data, now, seed)

    fun slotOf(
        state: GameState,
        type: BuildingType,
        index: Int = 0,
    ): Int =
        state.slots.filter {
            it.type == type
        }[index].slotId

    /** Castle・Wall・Warehouse を [level] にした状態（他の建物の Lv2 以上の建設には Castle の Lv が要る）。 */
    fun advanced(
        level: Int = 2,
        castle: Int = level,
        now: Long = T0,
    ): GameState {
        val s = newGame(now)
        val lifted = setOf(BuildingType.CASTLE, BuildingType.WALL, BuildingType.WAREHOUSE)

        fun levelOf(slot: BuildingSlot): Int =
            when (slot.type) {
                BuildingType.CASTLE -> castle
                in lifted -> level
                else -> slot.level
            }
        return s.copy(slots = s.slots.map { it.copy(level = levelOf(it)) })
    }

    /** [type] の [index] 番目のスロットを [level] にした状態。 */
    fun withLevel(
        state: GameState,
        type: BuildingType,
        level: Int,
        index: Int = 0,
    ): GameState {
        val id = slotOf(state, type, index)
        return state.copy(slots = state.slots.map { if (it.slotId == id) it.copy(level = level) else it })
    }

    /** Academy を [level] にした状態。 */
    fun withAcademy(
        state: GameState,
        level: Int,
    ): GameState =
        state.copy(
            slots = state.slots.map { if (it.type == BuildingType.ACADEMY) it.copy(level = level) else it },
        )

    val RICH = Resources(1_000_000_000, 1_000_000_000, 1_000_000_000, 1_000_000_000)
}

/** 成功を期待して state を取り出す。 */
fun ActionResult.ok(): GameState {
    assertIs<ActionResult.Success>(this, "expected Success but was $this")
    return state
}

/** 失敗を期待して理由を取り出す。 */
fun ActionResult.failure(): ActionFailure {
    assertIs<ActionResult.Failure>(this, "expected Failure but was $this")
    return reason
}
