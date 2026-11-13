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

    @Test
    fun title() {
        val rig = ShotRig(rule)
        rig.seed(Preset.RICH_CASTLE)
        rig.launch()
        rig.waitText(rig.str(R.string.title_continue))
        rig.shot("title")
    }

    @Test
    fun city() {
        val rig = ShotRig(rule)
        rig.seed(Preset.RICH_CASTLE)
        rig.launchToCity()
        rig.shot("city_rich")
    }
}
