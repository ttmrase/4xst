package com.ttmrase.crownfall

import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import com.ttmrase.crownfall.ui.territory.SpriteCache
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * 性能の計測（T10.8）。LATE_GAME の城内を約 10 秒パン・ピンチして、FrameMetrics の総所要時間の分布と
 * SpriteCache の実メモリを logcat（タグ CrownfallPerf）に出す。ui ジョブが result.txt / summary.txt に写す。
 * エミュレータの数字なので絶対値では判定せず、計測が走ったこと（フレーム数）だけを確かめる。
 */
@RunWith(AndroidJUnit4::class)
class PerfUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun lateGameCityPanFrameTimesAndSpriteMemory() {
        val h = AppHarness(rule)
        h.seed(Preset.LATE_GAME)
        val scenario = h.launchToCity()
        val frameMs = Collections.synchronizedList(ArrayList<Double>())
        val listener =
            Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
                frameMs.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / NANOS_PER_MS)
            }
        var window: Window? = null
        scenario.onActivity {
            window = it.window
            it.window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        }

        // 区間 [label] の間 [action] を繰り返して、その間のフレーム時間だけを数字にする（起動と初回の読み込みは除く）
        fun measure(
            label: String,
            durationNs: Long,
            action: (Int) -> Unit,
        ): Int {
            rule.waitForIdle()
            frameMs.clear()
            val start = System.nanoTime()
            var k = 0
            while (System.nanoTime() - start < durationNs) action(k++)
            val sorted = synchronized(frameMs) { frameMs.sorted() }

            fun pct(p: Double) = sorted.getOrElse(((sorted.size - 1) * p).toInt()) { Double.NaN }
            val janky = sorted.count { it > JANK_MS }
            Log.i(
                "CrownfallPerf",
                "$label ${durationNs / NANOS_PER_MS.toLong() / 1000}s: frames=${sorted.size} " +
                    "p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms janky(>16.7ms)=%d (%.1f%%)".format(
                        pct(0.5),
                        pct(0.95),
                        pct(0.99),
                        sorted.lastOrNull() ?: Double.NaN,
                        janky,
                        if (sorted.isEmpty()) 0.0 else 100.0 * janky / sorted.size,
                    ),
            )
            return sorted.size
        }

        h.step("perf_city") {
            // 触らない城内（24Hz の再描画だけ）
            measure("city LATE_GAME idle", IDLE_NS) { Thread.sleep(IDLE_STEP_MS) }
            val frames =
                measure("city LATE_GAME pan+pinch", MEASURE_NS) { k ->
                    val dir = if (k % 2 == 0) 1f else -1f
                    rule.onRoot().performTouchInput {
                        swipe(
                            Offset(width * (0.5f - 0.25f * dir), height * 0.5f),
                            Offset(width * (0.5f + 0.25f * dir), height * (0.5f + 0.1f * dir)),
                            durationMillis = SWIPE_MS,
                        )
                    }
                    if (k % 4 == 3) {
                        rule.onRoot().performTouchInput {
                            val c = Offset(width * 0.5f, height * 0.45f)
                            pinch(
                                Offset(c.x - width * 0.1f, c.y),
                                Offset(c.x - width * 0.25f, c.y),
                                Offset(c.x + width * 0.1f, c.y),
                                Offset(c.x + width * 0.25f, c.y),
                                durationMillis = SWIPE_MS,
                            )
                        }
                    }
                }
            val cache = SpriteCache.shared(h.context)
            val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            Log.i(
                "CrownfallPerf",
                "memory: spriteCache=%.1fMB/%d sprites pss=%.0fMB javaHeap=%.0fMB".format(
                    cache.usedBytes / MB,
                    cache.spriteCount,
                    mem.totalPss / KB,
                    (mem.getMemoryStat("summary.java-heap")?.toDoubleOrNull() ?: Double.NaN) / KB,
                ),
            )
            // 基準: 城内ではない画面（ショップ一覧のスクロール）。エミュレータ（ソフトウェア描画）自体の重さを見る
            h.open("shop")
            measure("shop list scroll", BASELINE_NS) { k ->
                val dir = if (k % 2 == 0) 1f else -1f
                rule.onRoot().performTouchInput {
                    swipe(
                        Offset(width * 0.5f, height * (0.5f + 0.2f * dir)),
                        Offset(width * 0.5f, height * (0.5f - 0.2f * dir)),
                        durationMillis = SWIPE_MS,
                    )
                }
            }
            assertTrue("frames measured: $frames", frames > MIN_FRAMES)
        }
        scenario.onActivity { window?.removeOnFrameMetricsAvailableListener(listener) }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000.0
        const val MEASURE_NS = 10_000_000_000L
        const val IDLE_NS = 5_000_000_000L
        const val BASELINE_NS = 5_000_000_000L
        const val IDLE_STEP_MS = 250L
        const val SWIPE_MS = 700L
        const val JANK_MS = 16.7
        const val MIN_FRAMES = 30
        const val MB = 1024.0 * 1024.0
        const val KB = 1024.0
    }
}
