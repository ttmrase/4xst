package com.ttmrase.crownfall.core.military

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.buff.BuffSet
import com.ttmrase.crownfall.core.data.ResearchLevel
import com.ttmrase.crownfall.core.data.ResearchNodeDef
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Buffs
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MilitaryTest {
    private val data = TestSupport.data
    private val t1 = data.troops.getValue("infantry_t1")

    private fun barracks(level: Int = 1) = TestSupport.withLevel(TestSupport.advanced(), BuildingType.BARRACKS, level)

    private fun rich(s: GameState) = s.copy(resources = RICH)

    // ───── training ─────

    @Test
    fun trainingChargesCostAndSchedulesTheBatch() {
        val s = rich(barracks())
        val r = Actions.startTraining(s, "infantry_t1", 50, data).ok()
        assertEquals(RICH.minusOrNull(t1.cost * 50), r.resources)
        val job = r.timers.single()
        assertEquals(TimerKind.TRAIN, job.kind)
        assertEquals("infantry_t1", job.troopId)
        assertEquals(50, job.count)
        assertEquals(TestSupport.slotOf(s, BuildingType.BARRACKS), job.slotId)
        assertEquals(s.nowEpochSec + t1.trainSec * 50, job.endEpochSec)
    }

    @Test
    fun completionAddsTroopsAtTheExactBoundary() {
        val s = Actions.startTraining(rich(barracks()), "infantry_t1", 10, data).ok().copy(resources = Resources.ZERO)
        val job = s.timers.single()
        val dur = job.endEpochSec - s.nowEpochSec
        val before = Simulation.tick(s, dur - 1, data)
        assertEquals(null, before.state.troops["infantry_t1"])
        val done = Simulation.tick(s, dur, data)
        assertEquals(10, done.state.troops["infantry_t1"])
        assertTrue(done.state.timers.isEmpty())
        assertEquals(listOf(GameEvent.TroopsTrained("infantry_t1", 10), GameEvent.TimerCompleted(job)), done.events)
        // 続けて訓練すると加算される
        val again = Actions.startTraining(rich(done.state), "infantry_t1", 5, data).ok()
        val end = Simulation.tick(again, 10_000, data).state
        assertEquals(15, end.troops["infantry_t1"])
    }

    @Test
    fun failuresAreReportedInTheDocumentedOrder() {
        val s = rich(barracks())
        assertEquals(ActionFailure.InvalidArgument, Actions.startTraining(s, "nope", 5, data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.startTraining(s, "infantry_t1", 0, data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.startTraining(s, "infantry_t1", -3, data).failure())
        // Tier2 は建物 Lv6、未建設の訓練建物は Tier1 の Lv1 を要求
        assertEquals(ActionFailure.TroopLocked(6), Actions.startTraining(s, "infantry_t2", 1, data).failure())
        assertEquals(ActionFailure.TroopLocked(1), Actions.startTraining(s, "archer_t1", 1, data).failure())
        // 上限: Lv1 の train_capacity は 100
        val cap = data.building(BuildingType.BARRACKS).effect(1, "train_capacity")
        assertEquals(100, cap)
        assertEquals(ActionFailure.InvalidArgument, Actions.startTraining(s, "infantry_t1", cap + 1, data).failure())
        Actions.startTraining(s, "infantry_t1", cap, data).ok()
    }

    @Test
    fun queueIsPerTrainingBuilding() {
        val s = rich(TestSupport.withLevel(barracks(), BuildingType.STABLE, 1))
        val first = Actions.startTraining(s, "infantry_t1", 5, data).ok()
        assertEquals(ActionFailure.QueueFull, Actions.startTraining(first, "infantry_t1", 5, data).failure())
        // 別の訓練建物は並行して進められる。建設キューとも独立。
        val parallel = Actions.startTraining(first, "cavalry_t1", 5, data).ok()
        assertEquals(2, parallel.timers.size)
        val withBuild = Actions.startUpgrade(parallel, TestSupport.slotOf(parallel, BuildingType.FARM), data).ok()
        assertEquals(3, withBuild.timers.size)
    }

    @Test
    fun notEnoughResourcesReportsExactShortfall() {
        val s = barracks().copy(resources = Resources(food = 100, wood = 0, stone = 0, gold = 0))
        val cost = t1.cost * 10
        assertEquals(
            ActionFailure.NotEnoughResources(cost.missingFrom(s.resources)),
            Actions.startTraining(s, "infantry_t1", 10, data).failure(),
        )
    }

    @Test
    fun trainSpeedIsAppliedAndRoundedUp() {
        val s = rich(barracks()).copy(research = mapOf("dev_train" to 10)) // +3000bp
        val job = Actions.startTraining(s, "infantry_t1", 7, data).ok().timers.single()
        val base = t1.trainSec * 7
        assertEquals(Buffs.speedSeconds(base, 3000), job.endEpochSec - job.startEpochSec)
        assertEquals((base * 10_000 + 12_999) / 13_000, job.endEpochSec - job.startEpochSec)
    }

    // ───── upkeep ─────

    private fun upkeep(
        s: GameState,
        buffs: BuffSet = BuffAggregator.compute(s, data),
    ) = Military.upkeepPerHour(s, data, buffs)

    @Test
    fun upkeepIsPer100TroopsAndReducedByResearch() {
        val s = TestSupport.newGame()
        assertEquals(0, upkeep(s))
        assertEquals(10, upkeep(s.copy(troops = mapOf("infantry_t1" to 1000))))
        assertEquals(10 + 30, upkeep(s.copy(troops = mapOf("infantry_t1" to 1000, "archer_t2" to 1000))))
        assertEquals(0, upkeep(s.copy(troops = mapOf("ghost" to 5000, "infantry_t1" to 0))))
        val big = s.copy(troops = mapOf("infantry_t1" to 10_000))
        assertEquals(100, upkeep(big))
        assertEquals(60, upkeep(big.copy(research = mapOf("mil_upkeep" to 10)))) // -40%
        // 軽減が 100% を超えても負にはならない
        val over = data.copy(research = data.research + ("x" to node(BuffKey.UPKEEP_REDUCTION, 20_000)))
        val s2 = big.copy(research = mapOf("x" to 1))
        assertEquals(0, Military.upkeepPerHour(s2, over, BuffAggregator.compute(s2, over)))
    }

    private fun node(
        key: BuffKey,
        bp: Long,
    ) = ResearchNodeDef("x", "t", 1, listOf(ResearchLevel(1, Resources.ZERO, 1, 0, emptyList(), key, bp, 0)))

    @Test
    fun foodGrowsByTheNetRate() {
        val s = TestSupport.newGame().copy(troops = mapOf("infantry_t1" to 10_000)) // 維持費 100/時、生産 300/時
        val after = Simulation.tick(s, 3600, data).state
        assertEquals(s.resources.food + 200, after.resources.food)
    }

    @Test
    fun foodDecreasesTowardZeroWithSignedRemainder() {
        val s = TestSupport.newGame().copy(troops = mapOf("infantry_t1" to 120_000)) // 維持費 1200 > 生産 300 → 正味 -900/時
        assertEquals(-900, Simulation.hourlyRate(s, ResourceType.FOOD, data) - upkeep(s))
        val one = Simulation.tick(s, 1, data).state
        assertEquals(s.resources.food - 1, one.resources.food) // floor: 0.25 でも 1 減る
        assertEquals(3600 - 900, one.productionRemainder.food)
        val hour = Simulation.tick(s, 3600, data).state
        assertEquals(s.resources.food - 900, hour.resources.food)
        assertEquals(0, hour.productionRemainder.food)
    }

    @Test
    fun foodDepletionFiresOnceAtTheExactTime() {
        val poor =
            TestSupport.newGame().copy(
                troops = mapOf("infantry_t1" to 120_000),
                resources = Resources(10, 5, 5, 5),
            )
        // 1 秒ずつ進めて、食料が 0 になる最初の秒を求める（floor のため「ちょうど」より早い）
        var cur = poor
        var firstZero = -1
        for (sec in 1..200) {
            cur = Simulation.tick(cur, 1, data).state
            if (cur.resources.food == 0L && firstZero < 0) firstZero = sec
        }
        assertTrue(firstZero in 1..200)
        // 一括でも 1 回だけ。時刻順の並びは他のイベントとの相対で検証する（下の分割テスト）。
        val whole = Simulation.tick(poor, 200, data)
        assertEquals(1, whole.events.count { it == GameEvent.FoodDepleted })
        assertEquals(0, whole.state.resources.food)
        assertEquals(0, whole.state.productionRemainder.food)
        assertTrue(whole.state.foodDepleted)
        val later = Simulation.tick(whole.state, 5000, data)
        assertTrue(later.events.none { it == GameEvent.FoodDepleted })
        assertEquals(0, later.state.resources.food)
        // 区切りを firstZero の前後にしても同じ結果
        for (cut in listOf(firstZero - 1L, firstZero.toLong(), firstZero + 1L)) {
            val a = Simulation.tick(poor, cut, data)
            val b = Simulation.tick(a.state, 200 - cut, data)
            assertEquals(whole.state, b.state, "cut=$cut")
            assertEquals(whole.events, a.events + b.events, "cut=$cut")
        }
        // 食料が 0 のまま始めても、区間の開始で 1 回だけ出す
        val atZero = Simulation.tick(poor.copy(resources = Resources.ZERO), 10, data)
        assertEquals(listOf<GameEvent>(GameEvent.FoodDepleted), atZero.events)
    }

    @Test
    fun depletionFlagClearsOnceFoodIsPositiveAgain() {
        val poor =
            TestSupport.newGame().copy(
                troops = mapOf("infantry_t1" to 120_000),
                resources = Resources(10, 5, 5, 5),
            )
        val dead = Simulation.tick(poor, 600, data).state
        assertTrue(dead.foodDepleted)
        val recovered = Actions.addResources(dead.copy(troops = emptyMap()), Resources(food = 1_000)).ok()
        val r = Simulation.tick(recovered, 10, data).state
        assertTrue(!r.foodDepleted)
        // 再び枯渇すれば、もう一度だけ出る
        val again =
            Simulation.tick(
                r.copy(troops = mapOf("infantry_t1" to 120_000), resources = Resources(3, 0, 0, 0)),
                600,
                data,
            )
        assertEquals(1, again.events.count { it == GameEvent.FoodDepleted })
    }

    @Test
    fun splitInvarianceHoldsWithUpkeepAndOtherEvents() {
        val rnd = java.util.Random(4242)
        var base = rich(barracks(1)).copy(troops = mapOf("infantry_t1" to 150_000))
        base = Actions.startTraining(base, "infantry_t1", 40, data).ok()
        base = Actions.startUpgrade(base, TestSupport.slotOf(base, BuildingType.FARM), data).ok()
        // 食料は少なめ・他は上限近く（StorageFull と FoodDepleted が同じ区間で起きる）
        val cap = Simulation.storageCap(base, data)
        base = base.copy(resources = Resources(food = 40, wood = cap - 5, stone = cap - 5, gold = 10))
        repeat(120) { n ->
            val total = (rnd.nextInt(60_000) + 1).toLong()
            val whole = Simulation.tick(base, total, data)
            var cur = base
            val events = ArrayList<GameEvent>()
            var left = total
            while (left > 0) {
                val piece = if (rnd.nextInt(5) == 0) 0L else minOf(left, (rnd.nextInt(700) + 1).toLong())
                val r = Simulation.tick(cur, piece, data)
                cur = r.state
                events.addAll(r.events)
                left -= piece
            }
            assertEquals(whole.state, cur, "state differs (case $n, total=$total)")
            assertEquals(whole.events, events, "events differ (case $n, total=$total)")
        }
    }

    @Test
    fun upkeepAppliesDuringOfflineCatchUp() {
        val s =
            TestSupport.newGame().copy(
                troops = mapOf("infantry_t1" to 120_000),
                resources = Resources(5000, 0, 0, 0),
            )
        val r = com.ttmrase.crownfall.core.sim.OfflineCatchUp.run(s, T0 + 86_400, data)
        assertEquals(0, r.state.resources.food)
        assertEquals(1, r.events.count { it == GameEvent.FoodDepleted })
        assertEquals(Simulation.tick(s, 86_400, data).state, r.state)
    }

    // ───── hospital ─────

    private fun hospital(
        level: Int = 1,
        wounded: Map<String, Long> = mapOf("infantry_t1" to 100L, "archer_t1" to 50L),
    ) = rich(TestSupport.withLevel(TestSupport.advanced(), BuildingType.HOSPITAL, level)).copy(wounded = wounded)

    @Test
    fun healMovesWoundedBackAtCompletion() {
        val s = hospital()
        val batch = mapOf("infantry_t1" to 60L, "archer_t1" to 50L)
        val r = Actions.startHeal(s, batch, data).ok()
        val cost = Military.healCost(batch, data)
        assertEquals(RICH.minusOrNull(cost), r.resources)
        assertEquals(mapOf("infantry_t1" to 40L), r.wounded)
        val job = r.timers.single()
        assertEquals(TimerKind.HEAL, job.kind)
        assertEquals(batch.toSortedMap().toMap(), job.healBatch)
        val dur = job.endEpochSec - r.nowEpochSec
        assertEquals(Buffs.speedSeconds(Military.baseHealSeconds(batch, data), 0), dur)
        val done = Simulation.tick(r.copy(resources = Resources.ZERO), dur, data)
        assertEquals(60, done.state.troops["infantry_t1"])
        assertEquals(50, done.state.troops["archer_t1"])
        assertTrue(done.events.contains(GameEvent.TroopsHealed(job.healBatch)))
        val before = Simulation.tick(r.copy(resources = Resources.ZERO), dur - 1, data)
        assertNull(before.state.troops["infantry_t1"])
    }

    @Test
    fun healCostAndTimeUseTheRulePercentages() {
        val batch = mapOf("infantry_t1" to 100L)
        val full = t1.cost * 100
        val cost = Military.healCost(batch, data)
        assertEquals(full.food * data.rules.healCostPct / 100, cost.food)
        assertEquals(t1.trainSec * 100 * data.rules.healTimePct / 100, Military.baseHealSeconds(batch, data))
        val fast = hospital().copy(research = mapOf("dev_heal" to 10))
        val job = Actions.startHeal(fast, batch, data).ok().timers.single()
        assertEquals(
            Buffs.speedSeconds(Military.baseHealSeconds(batch, data), 3000),
            job.endEpochSec - fast.nowEpochSec,
        )
    }

    @Test
    fun healFailures() {
        val s = hospital()
        assertEquals(ActionFailure.InvalidArgument, Actions.startHeal(s, emptyMap(), data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.startHeal(s, mapOf("infantry_t1" to 0L), data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.startHeal(s, mapOf("infantry_t1" to 101L), data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.startHeal(s, mapOf("ghost" to 1L), data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.startHeal(s, mapOf("cavalry_t1" to 1L), data).failure())
        val none = TestSupport.advanced().copy(resources = RICH, wounded = mapOf("infantry_t1" to 5L))
        assertEquals(
            ActionFailure.PrereqNotMet(listOf(Prereq(BuildingType.HOSPITAL, 1))),
            Actions.startHeal(none, mapOf("infantry_t1" to 5L), data).failure(),
        )
        val running = Actions.startHeal(s, mapOf("infantry_t1" to 10L), data).ok()
        assertEquals(ActionFailure.QueueFull, Actions.startHeal(running, mapOf("infantry_t1" to 10L), data).failure())
        val poor = s.copy(resources = Resources.ZERO)
        val batch = mapOf("infantry_t1" to 10L)
        assertEquals(
            ActionFailure.NotEnoughResources(Military.healCost(batch, data)),
            Actions.startHeal(poor, batch, data).failure(),
        )
    }

    @Test
    fun hospitalCapacityAppliesTheBuff() {
        val s = hospital(level = 3)
        val base = data.building(BuildingType.HOSPITAL).effect(3, "hospital_capacity")
        assertEquals(base, Military.hospitalCapacity(s, data, BuffAggregator.compute(s, data)))
        val buffed = s.copy(research = mapOf("mil_hospital" to 5)) // +2000bp
        assertEquals(base * 12 / 10, Military.hospitalCapacity(buffed, data, BuffAggregator.compute(buffed, data)))
        assertEquals(
            0,
            Military.hospitalCapacity(TestSupport.newGame(), data, BuffAggregator.compute(TestSupport.newGame(), data)),
        )
    }

    // ───── power ─────

    @Test
    fun aliveTroopsAddPowerButWoundedDoNot() {
        val s = TestSupport.newGame()
        val base = PowerCalculator.total(s, data)
        val withTroops =
            s.copy(
                troops = mapOf("infantry_t1" to 100, "ghost" to 9, "archer_t2" to 0),
                wounded = mapOf("infantry_t1" to 999),
            )
        assertEquals(100 * t1.power, Military.troopPower(withTroops, data))
        assertEquals(base + 100 * t1.power, PowerCalculator.total(withTroops, data))
        assertEquals(T0, s.nowEpochSec)
    }
}
