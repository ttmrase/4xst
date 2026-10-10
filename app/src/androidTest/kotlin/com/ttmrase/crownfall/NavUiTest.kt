package com.ttmrase.crownfall

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 戻るの原則（02 §17）の実アプリでの回帰: 下部ナビの「城内」は、どの画面からでも City に戻る。 */
@RunWith(AndroidJUnit4::class)
class NavUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private fun tapBottomNav(
        h: AppHarness,
        label: String,
    ) {
        h.waitText(label)
        val nodes = rule.onAllNodesWithText(label)
        // 下部ナビの項目は画面の下端にある（同じ文言が画面の上の方に出る画面もあるので、最後の 1 つを押す）
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        rule.waitForIdle()
    }

    @Test
    fun cityTabFromChatReturnsToTheCityAndAnotherTabAndBackWorks() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("chat_to_city") {
            h.open("chat")
            h.waitText(h.str(R.string.chat_input_hint), substring = true)
            Screenshots.save("nav_chat")
            tapBottomNav(h, h.str(R.string.nav_city))
            h.waitText(h.str(R.string.power_label), substring = true)
            val chatGone = !h.hasText(h.str(R.string.chat_input_hint), true)
            assertTrue("the chat must be gone after tapping City", chatGone)
            Screenshots.save("nav_city_after_chat")
            // 続けて別のタブ → 城内 でも戻れる（状態の保存・復元で前の画面に戻らない）
            tapBottomNav(h, h.str(R.string.nav_shop))
            h.waitText(h.str(R.string.shop_tab_gems))
            tapBottomNav(h, h.str(R.string.nav_city))
            h.waitText(h.str(R.string.power_label), substring = true)
            assertTrue("the city must be shown", h.hasText(h.str(R.string.power_label), true))
            assertFalse("the shop must be gone", h.hasText(h.str(R.string.shop_tab_gems)))
        }
    }
}
