package com.ttmrase.crownfall.core.economy

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.satAdd
import com.ttmrase.crownfall.core.model.satMul

/** ジェムスキップの換算（speedup.json の区分テーブル。短いほど割高）。 */
object GemSkip {
    private const val SECONDS_PER_HOUR = 3600L

    /** 残り [remainingSec] 秒をスキップするジェム数。区分ごとに切り上げて合算する。残り 0 以下は 0。 */
    fun cost(
        remainingSec: Long,
        data: GameData,
    ): Long {
        if (remainingSec <= 0) return 0
        var total = 0L
        var lower = 0L
        for (tier in data.speedupTiers) {
            val upper = tier.upToSec ?: Long.MAX_VALUE
            if (remainingSec <= lower) break
            val inTier = minOf(remainingSec, upper) - lower
            val scaled = satMul(inTier, tier.gemsPerHour)
            total = satAdd(total, scaled / SECONDS_PER_HOUR + if (scaled % SECONDS_PER_HOUR > 0) 1 else 0)
            lower = upper
        }
        return total
    }
}
