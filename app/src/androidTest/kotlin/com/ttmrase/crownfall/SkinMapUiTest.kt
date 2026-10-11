package com.ttmrase.crownfall

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.SkinState
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** M11 T11.4（ワールドマップの見た目とスキン）のスクリーンショット。画像名は t114_*。 */
@RunWith(AndroidJUnit4::class)
class SkinMapUiTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val data = ClasspathDataSource.load()

    private fun pinchMap(
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

    private fun AppHarness.waitMapLoaded() {
        rule.waitForIdle()
        runCatching { rule.waitUntil(20_000) { !hasText(str(R.string.map_loading)) } }
        Thread.sleep(800)
    }

    /** タブの行は横に長い（7 つ）。画面の外のタブは、先にスクロールしてからでないと押せない。 */
    private fun openShopTab(label: String) {
        val node = rule.onAllNodesWithText(label)[0]
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    private fun scrollToText(text: String) {
        runCatching {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
        }
        rule.waitForIdle()
        Thread.sleep(300)
    }

    private fun AppHarness.wearSkins(
        owned: Set<String>,
        equipped: Map<String, String>,
    ) = update { s -> s.copy(shop = s.shop.copy(skins = SkinState(owned = owned, equipped = equipped, shards = 60))) }

    @Test
    fun worldMapAtThreeZoomsWithAMarchingTroopAndTheNightLook() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        // 行軍中（土ぼこり）を見るために、食料のノードへ採集の行軍を出しておく
        h.update { s ->
            val troops = mapOf("archer_t2" to 200L)
            val ready = s.copy(troops = s.troops + troops)
            val node =
                s.map.nodes.filter { it.type == ResourceType.FOOD }
                    .minBy { MapQueries.distance(it.x, it.y, data.mapRules.center.x, data.mapRules.center.y) }
            when (
                val r =
                    Actions.startMarch(
                        ready,
                        MarchKind.GATHER,
                        TileRef(node.x, node.y),
                        troops,
                        emptyList(),
                        data,
                    )
            ) {
                is ActionResult.Success -> r.state
                else -> ready
            }
        }
        h.step("map_zooms") {
            h.open("world_map")
            h.waitMapLoaded()
            Screenshots.save("t114_map_overview")
            pinchMap(0.08f, 0.3f)
            rule.waitForIdle()
            Screenshots.save("t114_map_mid_zoom")
            pinchMap(0.08f, 0.3f)
            rule.waitForIdle()
            Screenshots.save("t114_map_max_zoom")
        }
        h.step("map_night") {
            h.open("settings")
            rule.waitForIdle()
            rule.onNodeWithText(h.str(R.string.settings_map_night)).performScrollTo()
            Screenshots.save("t114_settings_map")
            rule.onNodeWithText(h.str(R.string.settings_map_night)).performClick()
            rule.waitForIdle()
            h.open("world_map")
            h.waitMapLoaded()
            Screenshots.save("t114_map_night")
            h.open("settings")
            rule.waitForIdle()
            rule.onNodeWithText(h.str(R.string.settings_map_day)).performScrollTo()
            rule.onNodeWithText(h.str(R.string.settings_map_day)).performClick()
        }
    }

    /** マップを [dx], [dy]（画面の幅・高さに対する割合）だけ、指で動かす（地図の内容が指の向きに動く）。 */
    private fun panMap(
        dx: Float,
        dy: Float,
    ) = rule.onRoot().performTouchInput {
        val from = Offset(width * (0.5f - dx / 2), height * (0.5f - dy / 2))
        val to = Offset(width * (0.5f + dx / 2), height * (0.5f + dy / 2))
        swipe(from, to, durationMillis = 350)
    }

    @Test
    fun theSnowDesertRiverAndTheOwnersOfTheCastlesAreShot() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("map_regions") {
            h.open("world_map")
            h.waitMapLoaded()
            // 城の持ち主（旗の色: 自城=金・同盟=青・NPC=赤・敵の砦=紫）と段階が分かる中ズーム
            pinchMap(0.08f, 0.13f)
            rule.waitForIdle()
            Screenshots.save("t114_map_owners")
            // 世界は 64x64、城は (32,32)。この倍率（約 1.6 倍）では、1 回のスワイプが横に約 6 タイル、縦（0.7 倍）で約 12 タイル。
            // 下部ナビの上（画面の下 12%）から始めるとスワイプが効かないので、割合は 0.7 以下にする
            // 北の雪原（y < 約 9 = 城から 23 タイル北）
            repeat(4) { panMap(0f, 0.7f) }
            rule.waitForIdle()
            Screenshots.save("t114_map_snow")
            // 川（北端で x が約 13〜26）: 北端から西へ
            repeat(2) { panMap(0.7f, 0f) }
            rule.waitForIdle()
            Screenshots.save("t114_map_river_north")
            // 城へ戻って、南東の砂漠（x + y > 約 90）へ
            rule.onAllNodesWithText(h.str(R.string.screen_city))[0].performClick()
            rule.waitForIdle()
            repeat(4) { panMap(-0.7f, -0.35f) }
            rule.waitForIdle()
            Screenshots.save("t114_map_desert")
            // 川の南の部分（南端で x が約 35〜48）: 西へ
            repeat(2) { panMap(0.7f, 0f) }
            rule.waitForIdle()
            Screenshots.save("t114_map_river_south")
        }
    }

    @Test
    fun theSkinsTabShowsTheWeeklyLimitedTheBagAndThePreview() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("skins_tab") {
            h.open("shop")
            h.waitText(h.str(R.string.shop_tab_gems), timeoutMs = 20_000)
            openShopTab(h.str(R.string.shop_tab_skins))
            rule.waitForIdle()
            Thread.sleep(500)
            Screenshots.save("t114_skins_top")
            scrollToText(h.str(R.string.skin_regular_title))
            Screenshots.save("t114_skins_regular")
            scrollToText(h.str(R.string.skin_bag_title))
            Screenshots.save("t114_skins_bag")
            scrollToText(h.str(R.string.skin_exchange_title).substringBefore("%1"))
            Screenshots.save("t114_skins_exchange")
            scrollToText(h.str(R.string.skin_wardrobe_title))
            Screenshots.save("t114_skins_wardrobe")
        }
        h.step("skins_preview") {
            scrollToText(h.str(R.string.skin_week_title).substringBefore(" ("))
            rule.onAllNodesWithText(h.str(R.string.skin_preview))[0].performClick()
            rule.waitForIdle()
            Thread.sleep(600)
            Screenshots.save("t114_skin_preview")
        }
    }

    @Test
    fun theLuckyBagTenPullShowsItsResult() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        h.step("bag_ten") {
            h.open("shop")
            h.waitText(h.str(R.string.shop_tab_gems), timeoutMs = 20_000)
            openShopTab(h.str(R.string.shop_tab_skins))
            rule.waitForIdle()
            val ten = h.str(R.string.skin_bag_ten).substringBefore("%1")
            scrollToText(ten)
            rule.onAllNodesWithText(ten, substring = true)[0].performClick()
            rule.waitForIdle()
            Thread.sleep(1_000)
            Screenshots.save("t114_bag_ten_result")
        }
    }

    @Test
    fun equippedCastleSkinsChangeTheCityAndTheMapAndChat() {
        val h = AppHarness(rule)
        h.seed(Preset.RICH_CASTLE)
        h.launchToCity()
        val all =
            setOf(
                "castle_ice",
                "castle_gold",
                "castle_sakura",
                "castle_dragon",
                "territory_ice",
                "trail_flame",
                "frame_crystal",
                "plate_frost",
                "bubble_sakura",
            )
        h.step("castle_skins") {
            for (castle in listOf("castle_ice", "castle_gold", "castle_sakura", "castle_dragon")) {
                h.wearSkins(all, mapOf("castle" to castle, "territory" to "territory_ice"))
                h.open("city")
                rule.waitForIdle()
                Thread.sleep(800)
                Screenshots.save("t114_city_$castle")
            }
        }
        h.step("seasonal_themes") {
            // 領地のテーマ（季節）: 衣装室で装備して切り替える方式。1 つずつ装備して城内を撮る
            for (season in listOf("spring", "winter", "autumn")) {
                h.wearSkins(all + "territory_$season", mapOf("territory" to "territory_$season"))
                h.open("city")
                rule.waitForIdle()
                Thread.sleep(1_500)
                Screenshots.save("t114_city_theme_$season")
            }
        }
        h.step("map_with_skins") {
            h.wearSkins(all, mapOf("castle" to "castle_ice", "territory" to "territory_ice", "trail" to "trail_flame"))
            h.open("world_map")
            h.waitMapLoaded()
            Screenshots.save("t114_map_skins")
        }
        h.step("chat_with_skins") {
            h.wearSkins(
                all,
                mapOf("frame" to "frame_crystal", "nameplate" to "plate_frost", "bubble" to "bubble_sakura"),
            )
            h.open("chat")
            h.waitText(h.str(R.string.chat_input_hint), substring = true)
            for (text in listOf("Hello", "How are you?", "Let us trade")) {
                rule.onNode(hasSetTextAction()).performTextInput(text)
                h.click(h.str(R.string.chat_send))
                h.waitText(text)
            }
            Espresso.closeSoftKeyboard()
            Thread.sleep(6_000)
            Screenshots.save("t114_chat_skins")
        }
    }
}
