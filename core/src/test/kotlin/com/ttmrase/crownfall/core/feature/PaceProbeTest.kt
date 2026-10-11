package com.ttmrase.crownfall.core.feature

import com.ttmrase.crownfall.core.TestSupport
import kotlin.test.Test
import kotlin.test.assertTrue

/** 城 Lv ごとの到達時間（現実的なプレイヤーの試算）が、M11 T11.1 の目標の ±35% に収まっていること。 */
class PaceProbeTest {
    @Test
    fun castleLevelsAreReachedAtTheTargetPace() {
        val reached = PaceProbe.reach(TestSupport.data, 10)
        println("PACE " + reached.entries.joinToString(" ") { "Lv${it.key}=${it.value / 60}m" })
        // 目標（分）: Lv2 2 / Lv3 5 / Lv4 12 / Lv5 25 / Lv6 45 / Lv7 105 / Lv8 240 / Lv9 480 / Lv10 720..1440
        val targets = mapOf(2 to 2.0, 3 to 5.0, 4 to 12.0, 5 to 25.0, 6 to 45.0, 7 to 105.0, 8 to 240.0, 9 to 480.0)
        targets.forEach { (lv, minutes) ->
            val got = reached.getValue(lv) / 60.0
            assertTrue(got in minutes * 0.65..minutes * 1.35, "Lv$lv: $got min (target $minutes)")
        }
        val lv10 = reached.getValue(10) / 60.0
        assertTrue(lv10 in 720.0 * 0.8..1440.0 * 1.2, "Lv10: $lv10 min")
    }

    @Test
    fun noEarlyCeremonyFlood() {
        val reached = PaceProbe.reach(TestSupport.data, 5)
        // 解放の演出（Lv3 / Lv4 / Lv5）が 4 分に 3 回出ない（Lv3 → Lv5 は 10 分以上あく）
        assertTrue(reached.getValue(5) - reached.getValue(3) >= 10 * 60)
    }
}
