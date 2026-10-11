package com.ttmrase.crownfall

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M11 T11.1（段階解放）の実アプリでの確認と、解放の画面のスクリーンショット。
 * 鍵のかかったタブ（無反応にならない・戻るが効く）、解放の演出（戻るで全スキップ）、初回の使用の報酬（1 回だけ）。
 */
@RunWith(AndroidJUnit4::class)
class FeatureUnlockUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val data = ClasspathDataSource.load()

    private fun AppHarness.feature(
        id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    private fun AppHarness.waitUnlockCeremony() = waitText(str(R.string.feature_unlock_title), timeoutMs = 60_000)

    @Test
    fun freshStartShowsLocksAndEveryLockedTabAnswersAndBackWorks() {
        val h = AppHarness(rule)
        h.seed(Preset.FRESH_START)
        h.launchToCity()
        h.step("fresh_city") {
            rule.waitForIdle()
            val chip = h.str(R.string.feature_next_chip).substringBefore("%1")
            assertTrue("the next-unlock chip must be shown", h.hasText(chip, true))
            Screenshots.save("feat_fresh_city")
        }
        h.step("locked_tabs") {
            val tabs =
                listOf(
                    h.str(R.string.nav_alliance) to R.string.feature_name_alliance,
                    h.str(R.string.nav_shop) to R.string.feature_name_shop,
                    h.str(R.string.nav_heroes) to R.string.feature_name_heroes,
                    h.str(R.string.nav_map) to R.string.feature_name_world_map,
                )
            for ((label, feature) in tabs) {
                h.tapNav(label)
                val title = h.feature(R.string.feature_locked_title, h.str(feature))
                h.waitText(title, timeoutMs = 10_000)
                Screenshots.save("feat_locked_${label.lowercase()}")
                Espresso.pressBack()
                rule.waitForIdle()
                assertTrue("back must leave the locked $label", !h.hasText(title))
                assertTrue("the city must be shown again", h.hasText(h.str(R.string.power_label), true))
            }
        }
    }

    @Test
    fun theLevelThreeUnlockCeremonyAndTheFirstUseRewardAreClaimedOnce() {
        val h = AppHarness(rule)
        h.seed(Preset.UNLOCK_EVE_LV3)
        h.launchToCity(keepOfferPopup = true)
        h.step("unlock_lv3") {
            h.waitUnlockCeremony()
            var shots = 0
            // 解放の演出は 1 つずつ（クエスト → ショップ）。OK で次へ。途中（アイコンが飛ぶ間）も撮る
            while (h.hasText(h.str(R.string.feature_unlock_title)) && shots < 4) {
                // 解放の演出の最中は、オファーのポップアップ（閉じるボタン）が出ない
                assertTrue(
                    "no offer popup while the unlock ceremony is shown",
                    !h.hasDescription(h.str(R.string.common_close)),
                )
                Screenshots.save("feat_unlock_lv3_${shots + 1}")
                h.click(h.str(R.string.feature_unlock_ok))
                Thread.sleep(250)
                Screenshots.save("feat_unlock_lv3_flight_${shots + 1}")
                rule.waitForIdle()
                Thread.sleep(1_200)
                shots++
            }
            assertTrue("the ceremony must run for the unlocked features", shots >= 1)
            val celebrated = h.state().features.celebrated
            assertTrue(Feature.QUESTS.id in celebrated && Feature.SHOP.id in celebrated)
            Screenshots.save("feat_after_ceremony")
        }
        h.step("first_use_reward") {
            val gemsBefore = h.state().gems
            h.tapNav(h.str(R.string.nav_shop))
            h.waitText(h.str(R.string.shop_tab_gems), timeoutMs = 20_000)
            Screenshots.save("feat_shop_first_visit")
            h.tapNav(h.str(R.string.nav_city))
            val card = h.feature(R.string.feature_reward_card, h.str(R.string.feature_name_shop))
            h.waitText(card, timeoutMs = 20_000)
            Screenshots.save("feat_reward_card")
            h.click(h.str(R.string.feature_reward_claim))
            rule.waitForIdle()
            val gain = h.state().gems - gemsBefore
            val reward = FeatureGate.reward(data, Feature.SHOP).gems.toLong()
            assertEquals("the first-use reward is paid once", reward, gain)
            assertTrue("the card must be gone after claiming", !h.hasText(card))
            assertTrue(Feature.SHOP.id in h.state().features.firstUseClaimed)
            // もう一度受け取れない
            assertTrue(!FeatureGate.rewardClaimable(h.state(), data, Feature.SHOP))
        }
    }

    @Test
    fun theFirstCastleUpgradeOfAFreshGameShowsItsDuration() {
        val h = AppHarness(rule)
        h.seed(Preset.FRESH_START)
        h.launchToCity()
        h.step("fresh_castle_sheet") {
            val castle = h.str(R.string.building_castle)
            rule.waitUntil(20_000) {
                rule.onAllNodesWithContentDescription(castle, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onAllNodesWithContentDescription(castle, substring = true)[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            h.waitText(h.str(R.string.sheet_upgrade), timeoutMs = 20_000)
            Screenshots.save("feat_fresh_castle_upgrade")
        }
    }

    @Test
    fun backSkipsTheWholeCeremonyAndTheAllianceUnlocksAtLevelFour() {
        val h = AppHarness(rule)
        h.seed(Preset.UNLOCK_EVE_LV4)
        h.launchToCity(keepOfferPopup = true)
        h.step("unlock_lv4") {
            h.waitUnlockCeremony()
            Screenshots.save("feat_unlock_lv4_alliance")
            Espresso.pressBack()
            rule.waitForIdle()
            assertTrue("back must close the ceremony", !h.hasText(h.str(R.string.feature_unlock_title)))
            assertTrue(Feature.ALLIANCE.id in h.state().features.celebrated)
            Screenshots.save("feat_after_skip")
            // 同盟のタブはもう鍵ではなく、開ける
            h.tapNav(h.str(R.string.nav_alliance))
            rule.waitForIdle()
            val locked = h.feature(R.string.feature_locked_title, h.str(R.string.feature_name_alliance))
            assertTrue("the alliance must open now", !h.hasText(locked))
            Screenshots.save("feat_alliance_open")
        }
    }

    @Test
    fun settingsShowsTheLockCardBelowLevelFour() {
        val h = AppHarness(rule)
        h.seed(Preset.FRESH_START)
        h.launchToCity()
        h.step("settings_lock") {
            h.open("settings")
            rule.waitForIdle()
            Thread.sleep(500)
            Screenshots.save("feat_settings_ai_locked")
        }
    }

    @Test
    fun theShopShowsLockCardsForTheWheelAndThePiggyBankAtCastleLevelFour() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.update { s ->
            s.copy(slots = s.slots.map { if (it.type == BuildingType.CASTLE) it.copy(level = 4) else it })
        }
        h.step("shop_lock_cards") {
            h.open("shop")
            h.waitText(h.str(R.string.shop_tab_gems), timeoutMs = 20_000)
            val tabs =
                listOf(
                    R.string.shop_tab_recommended,
                    R.string.shop_tab_gems,
                    R.string.shop_tab_packs,
                    R.string.shop_tab_deals,
                    R.string.shop_tab_vip,
                    R.string.shop_tab_pass,
                )
            var sawLockCard = false
            for ((i, id) in tabs.withIndex()) {
                if (!h.hasText(h.str(id))) continue
                h.click(h.str(id))
                rule.waitForIdle()
                Screenshots.save("feat_shop_lv4_tab_${i + 1}")
                // 鍵のカードは一覧の下の方にあるので、見つかるところまでスクロールする
                val short = h.str(R.string.feature_locked_short).substringBefore("%1")
                runCatching {
                    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(short, substring = true))
                }
                rule.waitForIdle()
                if (h.hasText(short, true)) {
                    sawLockCard = true
                    Screenshots.save("feat_shop_lock_cards_tab_${i + 1}")
                }
            }
            assertTrue("some shop tab must show a lock card (wheel / piggy bank unlock at Lv6)", sawLockCard)
        }
    }
}
