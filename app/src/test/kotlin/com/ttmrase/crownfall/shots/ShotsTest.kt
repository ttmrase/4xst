package com.ttmrase.crownfall.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 開発用の画面キャプチャ（[ShotRig]）。`./gradlew :app:testDebugUnitTest --tests '*ShotsTest*' -Pshots=<dir>`。
 * `-Pshots` が無ければ全て skip する（CI の単体テストでは動かない）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShotsTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("pass -Pshots=<dir> to render screenshots", ShotRig.dir() != null)
    }

    private fun rig(preset: Preset): ShotRig = ShotRig(rule).also { it.seed(preset) }

    @Test
    fun title() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launch()
        rig.waitText(rig.str(R.string.title_continue))
        rig.shot("title")
    }

    @Test
    fun city() {
        val rig = rig(Preset.BUILDING_NOW)
        rig.launchToCity()
        rig.shot("city")
    }

    @Test
    fun cityFresh() {
        val rig = rig(Preset.FRESH_START)
        rig.launchToCity()
        rig.shot("city_fresh")
    }

    @Test
    fun cityLate() {
        val rig = rig(Preset.LATE_GAME)
        rig.launchToCity()
        rig.shot("city_late")
    }

    @Test
    fun subScreens() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        for (route in listOf("quests", "barracks", "research", "mail", "chat", "settings", "event", "summon")) {
            rig.open(route)
            rig.shot("screen_$route")
        }
    }

    @Test
    fun tabs() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        rig.tapNav(rig.str(R.string.nav_alliance))
        rig.shot("tab_alliance")
        rig.tapNav(rig.str(R.string.nav_heroes))
        rig.shot("tab_heroes")
        rig.tapNav(rig.str(R.string.nav_shop))
        rig.shot("tab_shop")
        rig.tapNav(rig.str(R.string.nav_map))
        rig.shot("tab_map", warmups = 8)
    }

    @Test
    fun offer() {
        val rig = rig(Preset.OFFER_READY)
        rig.launchToCity(keepPopups = true)
        rig.waitFor { rig.hasDescription(rig.str(R.string.common_close)) }
        rig.shot("offer_popup")
    }

    @Test
    fun sheets() {
        val rig = rig(Preset.BUILDING_NOW)
        rig.launchToCity()
        rig.tapAt(0.5f, 0.5f)
        rig.shot("sheet_upgrade")
    }

    @Test
    fun sheetFresh() {
        val rig = rig(Preset.FRESH_START)
        rig.launchToCity()
        rig.tapAt(0.5f, 0.47f)
        rig.shot("sheet_fresh")
    }

    /** 日本語（既定の言語）での文字の収まりを見る。 */
    @Test
    @Config(sdk = [34], qualifiers = "ja-w412dp-h892dp-xhdpi")
    fun japanese() {
        val rig = rig(Preset.BUILDING_NOW)
        rig.launchToCity()
        rig.shot("ja_city")
        rig.tapAt(0.5f, 0.5f)
        rig.shot("ja_sheet")
        rig.back()
        rig.open("barracks")
        rig.shot("ja_barracks")
        rig.open("quests")
        rig.shot("ja_quests")
        rig.tapNav(rig.str(R.string.nav_shop))
        rig.shot("ja_shop")
    }

    @Test
    fun purchase() {
        val rig = rig(Preset.VIP_NEAR)
        rig.launchToCity()
        rig.tapNav(rig.str(R.string.nav_shop))
        rig.click(rig.str(R.string.shop_tab_gems))
        rig.click("¥", substring = true)
        rig.shot("pay_sheet")
        rig.click(rig.str(R.string.billing_one_tap))
        rig.shot("pay_processing")
        rig.waitText(rig.str(R.string.billing_done))
        rig.shot("pay_done")
        rig.back()
        rig.settle(1500)
        rig.shot("pay_after")
    }

    @Test
    fun summon() {
        val rig = rig(Preset.GACHA_READY)
        rig.launchToCity()
        rig.open("summon")
        rig.click(rig.str(R.string.summon_ten_deal))
        rig.settle(600)
        rig.shot("summon_1")
        rig.settle(2500)
        rig.shot("summon_2")
        rig.settle(4000)
        rig.shot("summon_3")
    }

    @Test
    fun dialogs() {
        val rig = rig(Preset.BUILDING_NOW)
        rig.launchToCity()
        rig.clickDescription(rig.str(R.string.timers_build1))
        rig.shot("dlg_speedup")
        rig.back()
        rig.settle(600)
        rig.back()
        rig.settle(600)
        rig.shot("dlg_exit")
        rig.back()
        rig.tapNav(rig.str(R.string.nav_alliance))
        rig.click(rig.str(R.string.alliance_tab_help))
        rig.shot("alliance_help")
        rig.click(rig.str(R.string.alliance_tab_tech))
        rig.shot("alliance_tech")
        rig.tapNav(rig.str(R.string.nav_heroes))
        rig.tapAt(0.17f, 0.2f)
        rig.shot("hero_detail")
    }
}
