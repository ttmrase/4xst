package com.ttmrase.crownfall.shots

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerfProbeTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Before
    fun onlyWhenAsked() {
        assumeTrue(ShotRig.dir() != null)
    }

    private fun probe(preset: Preset, name: String) {
        val rig = ShotRig(rule).also { it.seed(preset) }
        rig.launchToCity()
        rig.shot("probe_$name")
        System.setProperty("crownfall.perf", ShotRig.dir() + "/perf_$name.txt")
        repeat(30) {
            rig.settle(16)
            rig.shot("probe_$name", warmups = 0)
        }
        System.clearProperty("crownfall.perf")
    }

    @Test
    fun fresh() = probe(Preset.FRESH_START, "fresh")

    @Test
    fun late() = probe(Preset.LATE_GAME, "late")
}
