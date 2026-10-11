package com.ttmrase.crownfall

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M11 T11.2（収穫バブル・獲得演出）と T11.3（城のレベルアップ演出・サウンド設定・称号）のスクリーンショットと確認。 */
@RunWith(AndroidJUnit4::class)
class HarvestSoundUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun tappingAHarvestBubbleCollectsItAndFliesToTheHud() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("bubbles") {
            rule.waitForIdle()
            Thread.sleep(1_000)
            Screenshots.save("t112_bubbles")
            val before = h.state().lastBubbleCollect
            val density = h.context.resources.displayMetrics.density
            val names =
                listOf(
                    R.string.building_farm,
                    R.string.building_sawmill,
                    R.string.building_quarry,
                    R.string.building_gold_mine,
                )
                    .map { h.str(it) }
            var collected = false
            // バブルは建物の少し上に描かれる（キャンバスの描画でノードは無い）。生産施設のノードの上の方を順にタップして探す
            search@ for (name in names) {
                val nodes = rule.onAllNodesWithContentDescription(name, substring = true)
                val count = nodes.fetchSemanticsNodes().size
                for (i in 0 until count) {
                    val b = nodes[i].getBoundsInRoot()
                    val cx = (b.left.value + b.right.value) / 2 * density
                    val cy = (b.top.value + b.bottom.value) / 2 * density
                    for (offsetDp in listOf(-10f, -20f, -32f, -46f, -62f)) {
                        rule.onRoot().performTouchInput { click(Offset(cx, cy + offsetDp * density)) }
                        rule.waitForIdle()
                        if (h.state().lastBubbleCollect != before) {
                            collected = true
                            break@search
                        }
                    }
                }
            }
            assertTrue("a harvest bubble must be collectable by tapping above a producer", collected)
            Screenshots.save("t112_bubble_flight_1")
            Screenshots.save("t112_bubble_flight_2")
            Thread.sleep(1_200)
            Screenshots.save("t112_after_bubble")
        }
    }

    @Test
    fun theChestCeremonyShowsItsStages() {
        val h = AppHarness(rule)
        h.seed(Preset.VIP_NEAR)
        h.launchToCity()
        h.step("chest") {
            h.open("shop")
            h.click(h.str(R.string.shop_tab_vip))
            // 演出（揺れ → ひび → 光 → 中身）は有限アニメなので、時計を止めて段階ごとに撮る
            h.waitText(h.str(R.string.vip_chest_claim))
            rule.mainClock.autoAdvance = false
            rule.onAllNodesWithText(h.str(R.string.vip_chest_claim))[0].performClick()
            for ((i, ms) in listOf(150L, 450L, 450L, 700L, 1_200L).withIndex()) {
                rule.mainClock.advanceTimeBy(ms)
                Screenshots.save("t112_chest_stage_${i + 1}")
            }
            rule.mainClock.autoAdvance = true
            h.waitText(h.str(R.string.common_ok), timeoutMs = 20_000)
            Screenshots.save("t112_chest_done")
            Espresso.pressBack()
            rule.waitForIdle()
        }
        h.step("quest_claim") {
            h.open("quests")
            rule.waitForIdle()
            Thread.sleep(800)
            Screenshots.save("t112_quests")
            if (h.hasText(h.str(R.string.quest_claim))) {
                rule.onAllNodesWithText(h.str(R.string.quest_claim))[0].performClick()
                for (i in 1..3) Screenshots.save("t112_quest_chest_$i")
            }
        }
    }

    @Test
    fun settingsShowTheSoundAndTitleSections() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("settings_sound") {
            h.open("settings")
            rule.waitForIdle()
            rule.onNodeWithText(h.str(R.string.settings_bgm)).performScrollTo()
            rule.waitForIdle()
            Thread.sleep(300)
            Screenshots.save("t113_settings_sound")
            rule.onNodeWithText(h.str(R.string.settings_bgm_own_title)).performScrollTo()
            rule.waitForIdle()
            Thread.sleep(300)
            Screenshots.save("t113_settings_own_music")
        }
        h.step("settings_title") {
            rule.onNodeWithText(h.str(R.string.settings_title_auto)).performScrollTo()
            rule.waitForIdle()
            Thread.sleep(300)
            Screenshots.save("t113_settings_title")
        }
    }

    @Test
    fun theCastleLevelUpCeremonyShowsTheNewLevelAndWhatItUnlocks() {
        val h = AppHarness(rule)
        h.seed(Preset.UNLOCK_EVE_LV3)
        h.launchToCity(keepOfferPopup = true)
        h.step("castle_up") {
            val reached = h.str(R.string.castle_up_title).substringBefore("%1")
            h.waitText(reached, timeoutMs = 60_000, substring = true)
            Screenshots.save("t113_castle_up")
            var shots = 0
            while (shots < 6 && (h.hasText(reached, true) || h.hasText(h.str(R.string.feature_unlock_title)))) {
                if (h.hasText(h.str(R.string.feature_unlock_ok))) {
                    h.click(h.str(R.string.feature_unlock_ok))
                } else {
                    break
                }
                Thread.sleep(600)
                Screenshots.save("t113_castle_up_next_${shots + 1}")
                shots++
            }
        }
    }
}
