package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.data.GameData

/** 行軍の表示用計算（地図上のアイコン位置・進捗・残り時間）。 */
object MarchViews {
    /** 現在の段階の進捗 0..1。 */
    fun progress(
        march: March,
        now: Long,
    ): Float {
        val span = march.phaseEndEpochSec - march.phaseStartEpochSec
        if (span <= 0) return 1f
        return ((now - march.phaseStartEpochSec).toFloat() / span).coerceIn(0f, 1f)
    }

    /** 現在の段階の残り秒（0 以上）。 */
    fun remainingSec(
        march: March,
        now: Long,
    ): Long = maxOf(0L, march.phaseEndEpochSec - now)

    /** 地図上の位置（タイル座標。移動中は城と目的地の線分上を補間）。 */
    fun position(
        march: March,
        now: Long,
        data: GameData,
    ): Pair<Float, Float> {
        val c = data.mapRules.center
        val from = c.x.toFloat() to c.y.toFloat()
        val to = march.target.x.toFloat() to march.target.y.toFloat()
        val t = progress(march, now)
        return when (march.phase) {
            MarchPhase.WAITING -> from
            MarchPhase.WORKING -> to
            MarchPhase.OUTBOUND -> lerp(from, to, t)
            MarchPhase.RETURNING -> lerp(to, from, t)
        }
    }

    private fun lerp(
        a: Pair<Float, Float>,
        b: Pair<Float, Float>,
        t: Float,
    ): Pair<Float, Float> = (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
}
