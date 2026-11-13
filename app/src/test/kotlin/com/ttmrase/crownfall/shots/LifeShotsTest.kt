package com.ttmrase.crownfall.shots

import android.provider.Settings
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.March
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 開発用: 領地ビューとワールドマップの「飾りの動き」（住民・煙・旗・雲の影・鳥・川の光）を、時間をずらした数枚の
 * 連続写真にする（[ShotRig]）。動きは実時間の時計で進むので、撮るあいだに実時間で待つ。
 * `./gradlew :app:testDebugUnitTest --tests '*LifeShotsTest*' -Pshots=<dir>`。`-Pshots` が無ければ skip する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeShotsTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("pass -Pshots=<dir> to render screenshots", ShotRig.dir() != null)
    }

    private fun rig(preset: Preset): ShotRig = ShotRig(rule).also { it.seed(preset) }

    /** [name]_0 … を [frames] 枚、[gapMs] ずつ空けて撮る。 */
    private fun strip(
        rig: ShotRig,
        name: String,
        frames: Int = FRAMES,
        gapMs: Long = GAP_MS,
    ) {
        rig.shot("${name}_0")
        for (k in 1 until frames) {
            Thread.sleep(gapMs)
            rig.shot("${name}_$k", warmups = 1)
        }
    }

    /** 画面の中心 ([fx], [fy]) のまわりを 2 本指で広げて拡大する。 */
    private fun zoomIn(
        rig: ShotRig,
        fx: Float,
        fy: Float,
    ) {
        val dm = rig.app.resources.displayMetrics
        val c = Offset(dm.widthPixels * fx, dm.heightPixels * fy)
        val near = dm.widthPixels * PINCH_NEAR
        val far = dm.widthPixels * PINCH_FAR
        rule.onAllNodes(isRoot())[0].performTouchInput {
            pinch(Offset(c.x - near, c.y), Offset(c.x - far, c.y), Offset(c.x + near, c.y), Offset(c.x + far, c.y))
        }
        rig.settle()
        rig.settle()
    }

    @Test
    fun cityStrip() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        strip(rig, "life_city")
    }

    @Test
    fun cityZoomStrip() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        zoomIn(rig, 0.5f, 0.62f)
        strip(rig, "life_city_zoom")
    }

    @Test
    fun cityFreshStrip() {
        val rig = rig(Preset.FRESH_START)
        rig.launchToCity()
        strip(rig, "life_city_fresh", frames = 2)
    }

    @Test
    fun cityLateStrip() {
        val rig = rig(Preset.LATE_GAME)
        rig.launchToCity()
        strip(rig, "life_city_late", frames = 2)
        zoomIn(rig, 0.5f, 0.6f)
        strip(rig, "life_city_late_zoom", frames = 2)
    }

    /** 端末の「アニメーションを削除」（アニメーター倍率 0）では静止画になる: 時間を空けた 2 枚で、街の絵が変わらない。 */
    @Test
    fun cityStill() {
        val rig = rig(Preset.RICH_CASTLE)
        Settings.Global.putFloat(rig.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        rig.launchToCity()
        strip(rig, "life_city_still", frames = 2, gapMs = STILL_GAP_MS)
    }

    /** 行軍の見た目を撮るために、往路と復路の行軍を 1 つずつ置く（撮影用の状態。到着する前に撮り終える）。 */
    private fun addMarches(rig: ShotRig) {
        val center = ClasspathDataSource.load().mapRules.center
        rig.update { s ->
            val now = s.nowEpochSec
            s.copy(
                marches =
                    listOf(
                        March(
                            id = MARCH_ID,
                            kind = MarchKind.GATHER,
                            troops = emptyMap(),
                            target = TileRef(center.x + 4, center.y - 3),
                            phase = MarchPhase.OUTBOUND,
                            phaseStartEpochSec = now - MARCH_DONE_SEC,
                            phaseEndEpochSec = now + MARCH_LEFT_SEC,
                            travelSec = MARCH_DONE_SEC + MARCH_LEFT_SEC,
                        ),
                        March(
                            id = MARCH_ID + 1,
                            kind = MarchKind.ATTACK,
                            troops = emptyMap(),
                            target = TileRef(center.x - 4, center.y + 3),
                            phase = MarchPhase.RETURNING,
                            phaseStartEpochSec = now - MARCH_DONE_SEC,
                            phaseEndEpochSec = now + MARCH_LEFT_SEC,
                            travelSec = MARCH_DONE_SEC + MARCH_LEFT_SEC,
                        ),
                    ),
            )
        }
    }

    @Test
    fun mapStrip() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        addMarches(rig)
        rig.tapNav(rig.str(R.string.nav_map))
        rig.shot("life_map_0", warmups = MAP_WARMUPS)
        for (k in 1 until FRAMES) {
            Thread.sleep(GAP_MS)
            rig.shot("life_map_$k", warmups = 1)
        }
        zoomIn(rig, 0.5f, 0.5f)
        rig.shot("life_map_zoom_0", warmups = MAP_WARMUPS)
        Thread.sleep(GAP_MS)
        rig.shot("life_map_zoom_1", warmups = 1)
    }

    private companion object {
        const val FRAMES = 4
        const val GAP_MS = 600L
        const val STILL_GAP_MS = 1500L
        const val MAP_WARMUPS = 8
        const val PINCH_NEAR = 0.08f
        const val PINCH_FAR = 0.3f
        const val MARCH_ID = 9001L
        const val MARCH_DONE_SEC = 40L
        const val MARCH_LEFT_SEC = 80L
    }
}
