package com.ttmrase.crownfall

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ショップ・オファー・決済の完了・VIP 昇格・報酬ダイアログ・襲撃のバナー・設定のスクリーンショットと、
 * VIP シネマと報酬ダイアログが戻るで閉じること（M10 の回帰）。
 */
@RunWith(AndroidJUnit4::class)
class ShopVipUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private fun payFirstYenButton(h: AppHarness) {
        h.waitText("¥", substring = true)
        rule.onAllNodesWithText("¥", substring = true)[0].performClick()
        h.click(h.str(R.string.billing_one_tap))
    }

    @Test
    fun offerPopupAndShopTabsAndPurchaseCelebrationAreCaptured() {
        val h = AppHarness(rule)
        h.seed(Preset.OFFER_READY)
        h.launchToCity(keepOfferPopup = true)
        h.step("offer_popup") {
            rule.waitUntil(30_000) { h.hasDescription(h.str(R.string.common_close)) }
            Screenshots.save("offer_popup")
            Espresso.pressBack()
            rule.waitForIdle()
            assertFalse("back must close the offer popup", h.hasDescription(h.str(R.string.common_close)))
        }
        h.clearPopups()
        h.open("shop")
        h.step("shop_tabs") {
            h.waitText(h.str(R.string.shop_tab_gems))
            for ((i, id) in listOf(
                R.string.shop_tab_recommended,
                R.string.shop_tab_gems,
                R.string.shop_tab_packs,
                R.string.shop_tab_deals,
                R.string.shop_tab_vip,
                R.string.shop_tab_pass,
            ).withIndex()) {
                h.click(h.str(id))
                rule.waitForIdle()
                Screenshots.save("shop_tab_${i + 1}")
            }
            h.click(h.str(R.string.shop_tab_gems))
            rule.waitForIdle()
            payFirstYenButton(h)
            h.waitText(h.str(R.string.billing_done), timeoutMs = 30_000)
            Screenshots.save("purchase_done")
            Espresso.pressBack()
            rule.waitForIdle()
        }
    }

    @Test
    fun vipCinematicAndRewardDialogCloseWithBack() {
        val h = AppHarness(rule)
        h.seed(Preset.VIP_NEAR)
        h.launchToCity()
        h.open("shop")
        h.step("vip_cinematic") {
            h.click(h.str(R.string.shop_tab_gems))
            rule.waitForIdle()
            payFirstYenButton(h)
            h.waitText(h.str(R.string.billing_done), timeoutMs = 30_000)
            Espresso.pressBack() // 決済シートを閉じる
            h.waitText(h.str(R.string.vip_promoted_title), timeoutMs = 20_000)
            Screenshots.save("vip_cinematic")
            Espresso.pressBack()
            rule.waitForIdle()
            assertFalse("back must close the VIP cinematic", h.hasText(h.str(R.string.vip_promoted_title)))
        }
        h.step("reward_dialog") {
            h.clearPopups()
            h.click(h.str(R.string.shop_tab_vip))
            h.click(h.str(R.string.vip_chest_claim))
            h.waitText(h.str(R.string.reward_chest_title), timeoutMs = 20_000)
            Screenshots.save("reward_dialog")
            Espresso.pressBack()
            rule.waitForIdle()
            assertFalse("back must close the reward dialog", h.hasText(h.str(R.string.reward_chest_title)))
        }
    }

    @Test
    fun raidBannerAndSettingsAreCaptured() {
        val h = AppHarness(rule)
        h.seed(Preset.RAID_IN_15_MIN)
        h.launchToCity()
        h.step("raid_banner") {
            rule.waitForIdle()
            assertTrue(
                "the raid banner must be shown",
                h.hasText(h.str(R.string.raid_banner).substringBefore('%'), true),
            )
            Screenshots.save("raid_banner")
        }
        h.open("settings")
        h.step("settings") {
            h.waitText(h.str(R.string.screen_settings), substring = true)
            Screenshots.save("settings")
        }
    }
}
