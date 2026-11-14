package com.ttmrase.crownfall

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.hero.SummonKind
import com.ttmrase.crownfall.core.hero.Summoner
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 召喚（M10 の回帰）: 演出中のタップが下のボタンに抜けて課金されない。「もう一度！」のダブルタップで 1 回だけ召喚される。
 * 演出の各段階（チャージ、オーブ、SSR の全画面、10 連の一覧）のスクリーンショット。
 */
@RunWith(AndroidJUnit4::class)
class SummonUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private fun gold(h: AppHarness) = h.state().gems

    private val data = ClasspathDataSource.load()

    // 召喚画面の既定はプレミアム（ジェム消費）
    private fun costOfOne(): Long = Summoner.table(data, SummonKind.PREMIUM).gems

    private fun center(
        rect: DpRect,
        density: Float,
    ) = Offset((rect.left.value + rect.right.value) / 2 * density, (rect.top.value + rect.bottom.value) / 2 * density)

    /** 時計を止めたまま、[condition] が成り立つまで 1 フレームずつ進める（実時間で [timeoutMs] まで）。 */
    private fun framesUntil(
        timeoutMs: Long = 10_000,
        condition: () -> Boolean,
    ): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            rule.mainClock.advanceTimeByFrame()
            if (condition()) return true
            Thread.sleep(FRAME_WAIT_MS)
        }
        return condition()
    }

    @Test
    fun tapsDuringTheRitualDoNotFallThroughAndAgainSummonsOnce() {
        val h = AppHarness(rule)
        h.seed(Preset.GACHA_READY)
        h.launchToCity()
        h.open("summon")
        h.step("summon_setup") {
            h.waitText(h.str(R.string.summon_once), substring = true)
            Screenshots.save("summon_setup")
            val density = h.context.resources.displayMetrics.density
            val onceNode = rule.onAllNodesWithText(h.str(R.string.summon_once), substring = true)[0]
            val onceCenter = center(onceNode.getBoundsInRoot(), density)
            val before = gold(h)
            val cost = costOfOne()
            assertTrue("premium summon must cost gems here (no tickets in this preset)", cost > 0)

            // 単発 → 演出が始まる
            // 演出は有限アニメなので、時計を止めておかないと Compose が演出の終わりまで待ってしまう
            rule.mainClock.autoAdvance = false
            onceNode.performTouchInput { click() }
            // 召喚の結果は、別スレッドでの保存を経て届く。時計を止めているので、届くまで実時間で待ちながら 1 フレームずつ進める
            // （決まった 500ms だけ進めて確かめると、遅いエミュレータでは結果が届く前に確かめてしまう。ui run 151）
            assertTrue("the ritual (Skip) must be shown", framesUntil { h.hasText(h.str(R.string.summon_skip), true) })
            rule.mainClock.advanceTimeBy(300)
            val afterFirst = gold(h)
            assertEquals("one summon costs exactly one", before - cost, afterFirst)
            Screenshots.save("summon_ritual_charge")

            // 演出中に、下の「単発」ボタンがあった位置を何度もタップしても、増減しない
            repeat(4) { rule.onRoot().performTouchInput { click(onceCenter) } }
            rule.mainClock.advanceTimeBy(300)
            assertEquals("taps during the ritual must not summon again", afterFirst, gold(h))

            // 演出をスキップして結果の画面へ（時計を止めたまま戻る: 先に進めると演出が終わって、戻るが画面を閉じてしまう）
            Espresso.pressBack()
            rule.mainClock.advanceTimeBy(300)
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            h.waitText(h.str(R.string.summon_again), substring = true)
            Screenshots.save("summon_results")
            assertEquals("no extra spending while the results are shown", afterFirst, gold(h))

            // 「もう一度！」をダブルタップ → もう 1 回だけ
            rule.onAllNodesWithText(
                h.str(R.string.summon_again),
                substring = true,
            )[0].performTouchInput { doubleClick() }
            rule.waitForIdle()
            Thread.sleep(1_500)
            rule.waitForIdle()
            assertEquals("a double tap on 'again' must summon exactly once", afterFirst - cost, gold(h))
        }
    }

    @Test
    fun ritualStagesAndTheTenRollListAreCaptured() {
        val h = AppHarness(rule)
        h.seed(Preset.GACHA_READY)
        h.launchToCity()
        h.open("summon")
        h.step("summon_stages") {
            h.waitText(h.str(R.string.summon_ten_deal), substring = true)
            // 時計を止めてからタップし、段階ごとに撮る
            rule.mainClock.autoAdvance = false
            rule.onAllNodesWithText(h.str(R.string.summon_ten_deal), substring = true)[0].performTouchInput { click() }
            assertTrue("the ritual (Skip) must be shown", framesUntil { h.hasText(h.str(R.string.summon_skip), true) })
            for ((i, ms) in listOf(200L, 700L, 700L, 900L).withIndex()) {
                rule.mainClock.advanceTimeBy(ms)
                Screenshots.save("summon_ten_stage_${i + 1}")
            }
            Espresso.pressBack()
            rule.mainClock.advanceTimeBy(300)
            rule.mainClock.autoAdvance = true
            rule.waitForIdle()
            h.waitText(h.str(R.string.summon_again), substring = true)
            Screenshots.save("summon_ten_results")
            rule.onAllNodesWithText(h.str(R.string.summon_flip_all), substring = true)[0].performTouchInput { click() }
            rule.waitForIdle()
            Screenshots.save("summon_ten_all_flipped")
        }
    }

    private companion object {
        const val FRAME_WAIT_MS = 20L
    }
}
