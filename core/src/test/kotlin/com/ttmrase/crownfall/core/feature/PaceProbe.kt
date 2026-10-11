package com.ttmrase.crownfall.core.feature

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.shop.ShopLogic
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation

/**
 * 「現実的なプレイヤー」が城を上げていく所要時間の試算（M11 T11.1 の到達時間の調整用）。
 * 城・前提の城壁と倉庫を最優先で上げ、資源が足りなければ今のチャプターの建物と、資源の元の生産施設を上げる。
 * チャプター報酬（建物以外の条件は満たしたものとする）と加速アイテムを使い、同盟の解放後は助けを求める。
 * ビルダー 1 本、ジェムは使わない、バブルなし、オフラインなし。
 */
object PaceProbe {
    var lastState: GameState? = null
    private var chainSteps = 0
    private val producerTypes =
        setOf(BuildingType.FARM, BuildingType.SAWMILL, BuildingType.QUARRY, BuildingType.GOLD_MINE)

    private const val STEP = 10L
    private const val LIMIT_SEC = 3L * 24 * 3600

    fun reach(
        data: GameData,
        upTo: Int,
        rewards: Boolean = true,
    ): Map<Int, Long> {
        chainSteps = 0
        var s = TestSupport.newGame()
        val t0 = s.nowEpochSec
        val out = linkedMapOf<Int, Long>()
        var lastCastle = 1
        while (s.nowEpochSec - t0 < LIMIT_SEC && lastCastle < upTo) {
            s = Simulation.tick(s, STEP, data).state
            if (rewards) s = claimChapters(s, data)
            s = useSpeedups(s, data)
            s = helpAndBuild(s, data)
            val lv = FeatureGate.castleLevel(s)
            if (lv > lastCastle) {
                for (l in lastCastle + 1..lv) out[l] = s.nowEpochSec - t0
                lastCastle = lv
            }
        }
        return out
    }

    private fun claimChapters(
        state: GameState,
        data: GameData,
    ): GameState {
        var s = state
        while (true) {
            val ch = data.quests.chapters.firstOrNull { it.id !in s.quest.claimed } ?: return s
            val buildingsDone =
                ch.tasks.filter { it.cond.type == "building" }.all { QuestLogic.progress(it.cond, s, data).done }
            if (!buildingsDone) return s
            val granted = ShopLogic.grant(s, ch.reward, data)
            s = granted.copy(quest = granted.quest.copy(claimed = granted.quest.claimed + ch.id))
        }
    }

    private fun useSpeedups(
        state: GameState,
        data: GameData,
    ): GameState {
        var s = state
        val job = s.timers.firstOrNull { it.kind == TimerKind.BUILD } ?: return s
        val speedups = data.items.entries.filter { it.value.category != null }.sortedByDescending { it.value.seconds }
        for ((id, def) in speedups) {
            while ((s.items[id] ?: 0) > 0) {
                val left = (s.timers.firstOrNull { it.id == job.id }?.endEpochSec ?: break) - s.nowEpochSec
                if (left < def.seconds / 2) break
                val r = Actions.applySpeedupItem(s, job.id, id, 1, data)
                s = (r as? ActionResult.Success)?.state ?: break
            }
        }
        return s
    }

    private fun helpAndBuild(
        state: GameState,
        data: GameData,
    ): GameState {
        var s = state
        s.timers.filter { it.kind == TimerKind.BUILD && !it.helpRequested }.forEach {
            val r = Actions.requestHelp(s, it.id, data)
            if (r is ActionResult.Success) s = r.state
        }
        if (s.timers.any { it.kind == TimerKind.BUILD }) return s
        val castleSlot = s.slots.first { it.type == BuildingType.CASTLE }
        for (candidate in candidates(s, data, castleSlot.level)) {
            val r = Actions.startUpgrade(s, candidate, data)
            if (r is ActionResult.Success) {
                val started = r.state.slots.first { it.slotId == r.state.timers.last().slotId }.type
                chainSteps = if (started in producerTypes) 0 else chainSteps + 1
                return r.state
            }
        }
        return s
    }

    /** 上げたい順（城の前提 → 城 → 城の道で一番足りない資源の生産施設 → 今のチャプターの建物）。 */
    private fun candidates(
        s: GameState,
        data: GameData,
        castleLv: Int,
    ): List<Int> {
        val out = mutableListOf<Int>()

        fun slotOf(t: BuildingType) = s.slots.filter { it.type == t }.minByOrNull { it.level }
        val castle = s.slots.first { it.type == BuildingType.CASTLE }
        val target = data.building(BuildingType.CASTLE).level(castleLv + 1)
        var cost = target.cost
        for (p in target.prereqs) {
            val slot = slotOf(p.type)
            if (slot != null && slot.level < p.level) {
                out += slot.slotId
                cost = data.building(p.type).level(slot.level + 1).cost
                break
            }
        }
        if (out.isEmpty()) out += castle.slotId
        // 城の道を 3 回進めるごとに、一番低い生産施設を 1 つ上げる（先行投資。報酬の有無にかかわらず）
        if (chainSteps >= 3) {
            val prod =
                s.slots.filter { it.type in producerTypes && it.level < castleLv }.minByOrNull { it.level }
            if (prod != null) out.add(0, prod.slotId)
        }
        val r = s.resources
        val lack =
            listOf(
                BuildingType.FARM to cost.food - r.food,
                BuildingType.SAWMILL to cost.wood - r.wood,
                BuildingType.QUARRY to cost.stone - r.stone,
                BuildingType.GOLD_MINE to cost.gold - r.gold,
            ).filter { it.second > 0 }.maxByOrNull { it.second }
        lack?.let { slotOf(it.first)?.let { slot -> out += slot.slotId } }
        val chapter = data.quests.chapters.firstOrNull { it.id !in s.quest.claimed }
        chapter?.tasks?.filter { it.cond.type == "building" && !QuestLogic.progress(it.cond, s, data).done }?.forEach {
                t ->
            slotOf(BuildingType.valueOf(t.cond.building!!.uppercase()))?.let { out += it.slotId }
        }
        return out
    }
}
