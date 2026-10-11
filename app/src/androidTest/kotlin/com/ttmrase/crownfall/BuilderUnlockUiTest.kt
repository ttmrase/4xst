package com.ttmrase.crownfall

import androidx.compose.ui.test.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.1.1 の BUG 2（2 本目の建設キューを購入が無反応）の回帰: 戻るでオファーの自動ポップアップを止めた状態でも、
 * 建物の詳細の「2 本目のビルダーを解放」から、パック → 決済シートまで進める（ユーザー起点の導線は抑制されない）。
 * あわせて、兵種アイコン（兵舎）のスクリーンショット。
 */
@RunWith(AndroidJUnit4::class)
class BuilderUnlockUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Test
    fun secondBuilderUnlockReachesThePackAndTheBillingSheetAfterBackStoppedThePopups() {
        val h = AppHarness(rule)
        h.seed(Preset.BUILDING_NOW) // 農場を建築中 = 建設キューが埋まっている
        h.launchToCity()
        h.step("builder_unlock") {
            // 戻るで自動ポップアップを止めた状態（02 §17）にする
            h.update { s ->
                s.copy(shop = s.shop.copy(popupsStopped = true, lastPopupClosedEpochSec = s.nowEpochSec))
            }
            rule.waitForIdle()
            // 城をタップして詳細シートを開く（領地の各建物には TalkBack 用のノードがある）
            val castle = h.str(R.string.building_castle)
            rule.waitUntil(20_000) {
                rule.onAllNodesWithContentDescription(castle, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onAllNodesWithContentDescription(castle, substring = true)[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            h.waitText(h.str(R.string.sheet_unlock_builder), timeoutMs = 20_000)
            Screenshots.save("builder_sheet")
            h.click(h.str(R.string.sheet_unlock_builder))
            // パック（または直接、決済シート）が出る = 無反応ではない
            val packName = h.str(R.string.offer_name_pack_builder)
            rule.waitUntil(20_000) {
                h.hasText(h.str(R.string.billing_title)) || h.hasText(packName, true) || h.hasText("¥", true)
            }
            Screenshots.save("builder_pack")
            if (!h.hasText(h.str(R.string.billing_title))) {
                rule.onAllNodesWithText("¥", substring = true)[0].performClick()
                h.waitText(h.str(R.string.billing_title), timeoutMs = 20_000)
            }
            Screenshots.save("builder_billing")
            Espresso.pressBack()
            rule.waitForIdle()
            assertTrue("back must close the billing sheet", !h.hasText(h.str(R.string.billing_title)))
        }
    }

    @Test
    fun barracksShowsTheTroopIcons() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("barracks") {
            h.open("barracks")
            h.waitText(h.str(R.string.screen_barracks), substring = true)
            Screenshots.save("barracks_troop_icons")
        }
    }
}
