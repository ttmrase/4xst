package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.model.satMul

/** basis point 計算を 1 か所に集約する（端数は常に切り捨て）。 */
object Buffs {
    private const val BP_ONE = 10_000L

    /** `value × (10000 + bp) / 10000`（切り捨て、飽和）。倍率が 0 未満にはならない。 */
    fun applyBp(
        value: Long,
        bp: Long,
    ): Long = satMul(value, maxOf(0L, BP_ONE + bp)) / BP_ONE

    /** 速度系バフ適用後の所要秒 = `seconds × 10000 / (10000 + bp)`（切り上げ、最小 1 秒）。建設・研究・訓練・治療で共通。 */
    fun speedSeconds(
        seconds: Long,
        bp: Long,
    ): Long {
        val denom = maxOf(1L, BP_ONE + bp)
        val num = satMul(seconds, BP_ONE)
        return maxOf(1L, (num + denom - 1) / denom)
    }
}
