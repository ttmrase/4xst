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

    private fun AppHarness.scrollToText(text: String) {
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
            h.open("map")
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
            h.open("map")
            h.waitMapLoaded()
            Screenshots.save("t114_map_night")
            h.open("settings")
            rule.waitForIdle()
            rule.onNodeWithText(h.str(R.string.settings_map_day)).performScrollTo()
            rule.onNodeWithText(h.str(R.string.settings_map_day)).performClick()
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
            h.click(h.str(R.string.shop_tab_skins))
            rule.waitForIdle()
            Thread.sleep(500)
            Screenshots.save("t114_skins_top")
            h.scrollToText(h.str(R.string.skin_regular_title))
            Screenshots.save("t114_skins_regular")
            h.scrollToText(h.str(R.string.skin_bag_title))
            Screenshots.save("t114_skins_bag")
            h.scrollToText(h.str(R.string.skin_exchange_title).substringBefore("%1"))
            Screenshots.save("t114_skins_exchange")
            h.scrollToText(h.str(R.string.skin_wardrobe_title))
            Screenshots.save("t114_skins_wardrobe")
        }
        h.step("skins_preview") {
            h.scrollToText(h.str(R.string.skin_week_title).substringBefore(" ("))
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
            h.click(h.str(R.string.shop_tab_skins))
            rule.waitForIdle()
            val ten = h.str(R.string.skin_bag_ten).substringBefore("%1")
            h.scrollToText(ten)
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
                "territory_ice",
                "trail_flame",
                "frame_crystal",
                "plate_frost",
                "bubble_sakura",
            )
        h.step("castle_skins") {
            for (castle in listOf("castle_ice", "castle_gold", "castle_sakura")) {
                h.wearSkins(all, mapOf("castle" to castle, "territory" to "territory_ice"))
                h.open("city")
                rule.waitForIdle()
                Thread.sleep(800)
                Screenshots.save("t114_city_$castle")
            }
        }
        h.step("map_with_skins") {
            h.wearSkins(all, mapOf("castle" to "castle_ice", "territory" to "territory_ice", "trail" to "trail_flame"))
            h.open("map")
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
            rule.onNode(hasSetTextAction()).performTextInput("Hello")
            h.click(h.str(R.string.chat_send))
            h.waitText("Hello")
            Thread.sleep(2_000)
            Screenshots.save("t114_chat_skins")
        }
    }
}
