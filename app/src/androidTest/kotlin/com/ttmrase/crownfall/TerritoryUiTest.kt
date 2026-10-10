package com.ttmrase.crownfall

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 城内（領地ビュー）: 初期カメラとズームのスクリーンショット、パンとピンチで落ちないこと（M10 の回帰: パンで coerceIn の上下逆）。
 */
@RunWith(AndroidJUnit4::class)
class TerritoryUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.dragAcross(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
    ) = performTouchInput {
        swipe(Offset(width * fromX, height * fromY), Offset(width * toX, height * toY), durationMillis = 350)
    }

    private fun zoom(
        spread: Float,
        toSpread: Float,
    ) = rule.onRoot().performTouchInput {
        val c = Offset(width * 0.5f, height * 0.45f)
        pinch(
            start0 = Offset(c.x - width * spread, c.y),
            end0 = Offset(c.x - width * toSpread, c.y),
            start1 = Offset(c.x + width * spread, c.y),
            end1 = Offset(c.x + width * toSpread, c.y),
            durationMillis = 400,
        )
    }

    private fun shootCity(
        h: AppHarness,
        preset: Preset,
    ) {
        h.seed(preset)
        h.launchToCity()
        h.step("city_${preset.name}") {
            rule.waitForIdle()
            Screenshots.save("city_${preset.name}_initial")
            zoom(0.08f, 0.3f)
            rule.waitForIdle()
            Screenshots.save("city_${preset.name}_zoom_in")
            // 1 回のピンチで戻るか（_zoom_out_1）と、続けて縮めた最小倍率（_zoom_out）の両方を撮る
            zoom(0.3f, 0.06f)
            rule.waitForIdle()
            Screenshots.save("city_${preset.name}_zoom_out_1")
            repeat(2) { zoom(0.3f, 0.06f) }
            rule.waitForIdle()
            Screenshots.save("city_${preset.name}_zoom_out")
        }
    }

    @Test
    fun territoryScreenshotsForRichLateAndBuildingStates() {
        val h = AppHarness(rule)
        shootCity(h, Preset.RICH_CASTLE)
        shootCity(h, Preset.LATE_GAME)
        shootCity(h, Preset.BUILDING_NOW)
    }

    @Test
    fun tutorialBannerSteps() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("tutorial") {
            for (step in 0..2) {
                h.update { it.copy(tutorialStep = step) }
                rule.waitForIdle()
                Thread.sleep(500)
                Screenshots.save("city_tutorial_step_$step")
            }
        }
    }

    @Test
    fun panAndPinchOnTheTerritoryDoNotCrash() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("pan_pinch") {
            val root = rule.onRoot()
            // 端から端まで何度も引く（カメラの範囲の上限・下限を超える向きも含む）
            repeat(6) { root.dragAcross(0.85f, 0.5f, 0.1f, 0.5f) }
            repeat(6) { root.dragAcross(0.1f, 0.5f, 0.9f, 0.5f) }
            repeat(6) { root.dragAcross(0.5f, 0.8f, 0.5f, 0.2f) }
            repeat(6) { root.dragAcross(0.5f, 0.2f, 0.5f, 0.8f) }
            repeat(3) { zoom(0.05f, 0.35f) }
            repeat(3) { zoom(0.35f, 0.04f) }
            repeat(3) { root.dragAcross(0.8f, 0.6f, 0.2f, 0.3f) }
            rule.waitForIdle()
            Screenshots.save("city_after_pan_pinch")
            // 落ちていれば計測プロセスごと終わる。ここまで来て、まだ城内の下部ナビが見えていること
            assertTrue("the city screen must still be shown", h.hasText(h.str(R.string.nav_city)))
        }
    }
}
