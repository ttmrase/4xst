package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M2 DoD: NewGame から、初期資源と生産だけで Castle を Lv5 まで上げられること。 */
class CastleProgressionTest {
    private val data = TestSupport.data

    /** 次に上げるべき (種類, 目標 Lv)。Castle Lv(n+1) には Wall・Warehouse が Lv n 以上必要。 */
    private fun nextStep(
        s: GameState,
        castleGoal: Int,
    ): BuildingType? {
        fun level(t: BuildingType) = s.slots.filter { it.type == t }.maxOf { it.level }
        val castle = level(BuildingType.CASTLE)
        if (castle >= castleGoal) return null
        return when {
            level(BuildingType.WALL) < castle -> BuildingType.WALL
            level(BuildingType.WAREHOUSE) < castle -> BuildingType.WAREHOUSE
            else -> BuildingType.CASTLE
        }
    }

    @Test
    fun newGameReachesCastleLv5WithoutAnyPurchase() {
        var s = TestSupport.newGame()
        val start = s.nowEpochSec
        val order = mutableListOf<String>()
        var guard = 0
        while (true) {
            val type = nextStep(s, castleGoal = 5) ?: break
            check(++guard < 50) { "no progress: $order" }
            val slot = TestSupport.slotOf(s, type)
            when (val r = Actions.startUpgrade(s, slot, data)) {
                is ActionResult.Success -> {
                    order.add("$type->${r.state.timers.single().targetLevel}")
                    val job = r.state.timers.single()
                    s = Simulation.tick(r.state, job.endEpochSec - r.state.nowEpochSec, data).state
                }
                is ActionResult.Failure -> {
                    // 資源不足だけは時間経過（生産）で解消できる必要がある。それ以外の失敗は設計ミス。
                    assertTrue(r.reason is ActionFailure.NotEnoughResources, "unexpected ${r.reason} at $order")
                    s = Simulation.tick(s, 60, data).state
                }
            }
        }
        assertEquals(5, s.slots.first { it.type == BuildingType.CASTLE }.level)
        assertEquals(
            listOf(
                "CASTLE->2", "WALL->2", "WAREHOUSE->2",
                "CASTLE->3", "WALL->3", "WAREHOUSE->3",
                "CASTLE->4", "WALL->4", "WAREHOUSE->4",
                "CASTLE->5",
            ),
            order,
        )
        val elapsed = s.nowEpochSec - start
        System.err.println("CASTLE5 game-time=${elapsed}s (${elapsed / 60} min)")
        // M11 T11.1 の調整で序盤を延ばした（クエスト報酬を使わない自動ビルダーで Lv5 まで約 70 分。現実的なプレイヤーは約 25 分）
        assertTrue(elapsed < 2 * 3600, "took ${elapsed}s")
        assertEquals(0, s.gems - data.rules.newGame.gems) // ジェムは一切使っていない
    }
}
