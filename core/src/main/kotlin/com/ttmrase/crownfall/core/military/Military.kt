package com.ttmrase.crownfall.core.military

import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.buff.BuffSet
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TroopDef
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul

/** 兵の訓練・維持費・治療の計算（純関数）。バフは [BuffSet] で受け取る。 */
object Military {
    private const val PCT = 100L
    private const val BP_ONE = 10_000L
    private const val UPKEEP_DIVISOR = 100L * BP_ONE

    /** 訓練建物（兵種ごとに 1 つ）のスロット。 */
    fun trainingSlot(
        state: GameState,
        troop: TroopDef,
    ): BuildingSlot? = state.slots.filter { it.type == troop.type.trainingBuilding }.maxByOrNull { it.level }

    /** 兵種 [troop] を訓練する建物の Lv（未建設は 0）。 */
    fun trainingBuildingLevel(
        state: GameState,
        troop: TroopDef,
    ): Int = trainingSlot(state, troop)?.level ?: 0

    /** 1 回の訓練で作れる最大数（建物の `train_capacity`）。 */
    fun trainCapacity(
        state: GameState,
        troop: TroopDef,
        data: GameData,
    ): Long = data.building(troop.type.trainingBuilding).effect(trainingBuildingLevel(state, troop), "train_capacity")

    /** [count] 体の訓練コスト。 */
    fun trainingCost(
        troop: TroopDef,
        count: Long,
    ): Resources = troop.cost * count

    /** [count] 体の基本訓練秒（バフ適用前）。 */
    fun baseTrainSeconds(
        troop: TroopDef,
        count: Long,
    ): Long = satMul(troop.trainSec, count)

    /**
     * 毎時の Food 維持費 = Σ(兵数 × upkeepPer100PerHour) / 100 × (10000 − UPKEEP_REDUCTION) / 10000（切り捨て、0 以上）。
     * 軽減が 100% を超えても負にはならない。
     */
    fun upkeepPerHour(
        state: GameState,
        data: GameData,
        buffs: BuffSet,
    ): Long {
        var per100 = 0L
        for ((id, count) in state.troops) {
            val def = data.troops[id] ?: continue
            if (count > 0) per100 = satAdd(per100, satMul(count, def.upkeepPer100PerHour))
        }
        val factor = maxOf(0L, BP_ONE - buffs[BuffKey.UPKEEP_REDUCTION])
        return satMul(per100, factor) / UPKEEP_DIVISOR
    }

    /** 病院に収容できる負傷兵の上限（`hospital_capacity` の合計に HOSPITAL_CAPACITY を適用）。 */
    fun hospitalCapacity(
        state: GameState,
        data: GameData,
        buffs: BuffSet,
    ): Long {
        var base = 0L
        for (slot in state.slots) {
            if (slot.type == BuildingType.HOSPITAL) {
                base = satAdd(base, data.building(slot.type).effect(slot.level, "hospital_capacity"))
            }
        }
        return satMul(base, maxOf(0L, BP_ONE + buffs[BuffKey.HOSPITAL_CAPACITY])) / BP_ONE
    }

    /** 治療コスト = 訓練コスト × 数 × healCostPct / 100（成分ごと切り捨て）。 */
    fun healCost(
        batch: Map<String, Long>,
        data: GameData,
    ): Resources {
        var total = Resources.ZERO
        for ((id, count) in batch) {
            val troop = data.troops.getValue(id)
            val full = trainingCost(troop, count)
            total +=
                Resources(
                    satMul(full.food, data.rules.healCostPct) / PCT,
                    satMul(full.wood, data.rules.healCostPct) / PCT,
                    satMul(full.stone, data.rules.healCostPct) / PCT,
                    satMul(full.gold, data.rules.healCostPct) / PCT,
                )
        }
        return total
    }

    /** 治療の基本秒 = Σ(trainSec × 数) × healTimePct / 100（切り捨て。バフ適用前）。 */
    fun baseHealSeconds(
        batch: Map<String, Long>,
        data: GameData,
    ): Long {
        var total = 0L
        for ((id, count) in batch) total = satAdd(total, baseTrainSeconds(data.troops.getValue(id), count))
        return satMul(total, data.rules.healTimePct) / PCT
    }

    /** 生存兵の戦力（負傷兵は含めない）。 */
    fun troopPower(
        state: GameState,
        data: GameData,
    ): Long =
        state.troops.entries.fold(0L) { acc, (id, count) ->
            val def = data.troops[id]
            if (def == null || count <= 0) acc else satAdd(acc, satMul(count, def.power))
        }
}
