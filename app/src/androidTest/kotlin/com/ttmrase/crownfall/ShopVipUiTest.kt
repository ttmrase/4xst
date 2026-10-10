package com.ttmrase.crownfall

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.shop.VipCalculator
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
        Screenshots.save("pay_1_list")
        rule.onAllNodesWithText("¥", substring = true)[0].performClick()
        rule.waitForIdle()
        Screenshots.save("pay_2_after_click")
        h.click(h.str(R.string.billing_one_tap))
        Screenshots.save("pay_3_after_one_tap")
    }

    private val data = ClasspathDataSource.load()

    private fun vipInfo(h: AppHarness): Pair<Int, Long> {
        val s = h.state()
        return VipCalculator.level(s, data) to s.shop.vipPoints
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
            // 次の Lv まで 1 pt 手前にしてから払う（シードの値に頼らない。celebrated は現在の Lv のまま）
            h.update { s ->
                val next = data.vip.levels[VipCalculator.level(s, data)]
                s.copy(shop = s.shop.copy(vipPoints = next.pt - 1))
            }
            val before = vipInfo(h)
            payFirstYenButton(h)
            h.waitText(h.str(R.string.billing_done), timeoutMs = 30_000)
            val after = vipInfo(h)
            Screenshots.save("vip_after_purchase")
            Espresso.pressBack() // 決済シートを閉じる
            rule.waitForIdle()
            check(h.hasText(h.str(R.string.vip_promoted_title)) || after.first > before.first) {
                "the purchase must raise the VIP level (before=$before after=$after: level to points)"
            }
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
                h.hasText(h.str(R.string.raid_stage_banner).substringBefore('%'), true),
            )
            Screenshots.save("raid_banner")
        }
        h.open("settings")
        h.step("settings") {
            rule.waitForIdle()
            Thread.sleep(500)
            Screenshots.save("settings")
        }
    }
}
