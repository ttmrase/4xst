package com.ttmrase.crownfall

import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M11 T11.5（ポートレート）のスクリーンショット: 英雄一覧・詳細、チャット（吹き出し・DM ヘッダー）、同盟、設定のアバター。 */
@RunWith(AndroidJUnit4::class)
class PortraitUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val data = ClasspathDataSource.load()

    /** すべての英雄を所持にして、SSR も R も一覧に並べる。VIP の枠（リング）が出るよう VIP も上げておく。 */
    private fun AppHarness.grantAllHeroes() =
        update { s ->
            val owned = s.heroes.associateBy { it.heroId }
            val all = data.heroes.keys.map { id -> owned[id] ?: HeroState(heroId = id, level = 20, star = 3) }
            // VIP の昇格の演出が一覧にかぶらないよう、祝い済みの Lv も合わせて上げる
            val points = 40_000L
            val lv = VipCalculator.level(points, data)
            s.copy(heroes = all, shop = s.shop.copy(vipPoints = points, vipCelebrated = lv))
        }

    private fun scrollTo(text: String) {
        runCatching {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
        }
        rule.waitForIdle()
        Thread.sleep(300)
    }

    @Test
    fun heroListAndDetailShowThePortraitsWithRarityFrames() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.grantAllHeroes()
        h.step("heroes") {
            h.open("heroes")
            rule.waitForIdle()
            Thread.sleep(800)
            Screenshots.save("portrait_heroes_list")
            // 一覧の下の方（R / SR の英雄）も撮る
            val sr = data.heroes.values.filter { it.rarity.name == "SSR" }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Lv", substring = true))
            Screenshots.save("portrait_heroes_list_2")
            if (sr.isNotEmpty()) {
                // SSR の英雄の詳細（レアリティの表示 "SSR" のカードを押す）
                rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("SSR", substring = true))
                rule.onAllNodesWithText("SSR", substring = true)[0].performClick()
                rule.waitForIdle()
                Thread.sleep(500)
                Screenshots.save("portrait_hero_detail_ssr")
            }
        }
    }

    @Test
    fun chatAndDmShowTheAvatars() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("chat") {
            h.open("chat")
            h.waitText(h.str(R.string.chat_input_hint), substring = true)
            Thread.sleep(800)
            Screenshots.save("portrait_chat_alliance")
            h.click("DM ", substring = true)
            rule.waitForIdle()
            Thread.sleep(800)
            Screenshots.save("portrait_chat_dm")
            // 1 通送って返事（吹き出しのアバター）を待つ
            for (text in listOf("Hello", "How are you?", "Let us trade")) {
                rule.onNode(hasSetTextAction()).performTextInput(text)
                h.click(h.str(R.string.chat_send))
                h.waitText(text)
            }
            Espresso.closeSoftKeyboard()
            Thread.sleep(8_000) // ペルソナの返事（入力中の表示のあと）を待つ
            Screenshots.save("portrait_chat_bubbles")
        }
    }

    @Test
    fun allianceMembersAndSettingsAvatarPickerShowThePortraits() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("alliance_members") {
            h.open("alliance")
            rule.waitForIdle()
            Thread.sleep(800)
            Screenshots.save("portrait_alliance")
            scrollTo("VIP")
            Screenshots.save("portrait_alliance_members")
        }
        h.step("settings_avatar") {
            h.open("settings")
            rule.waitForIdle()
            // 設定は通常のスクロール列（遅延リストではない）なので、見出しのノードに scrollTo する
            rule.onNodeWithText(h.str(R.string.settings_avatar_title)).performScrollTo()
            rule.waitForIdle()
            Thread.sleep(300)
            Screenshots.save("portrait_settings_avatar")
        }
    }
}
