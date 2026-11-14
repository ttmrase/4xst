package com.ttmrase.crownfall.shots

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.skin.Skins
import com.ttmrase.crownfall.debug.DebugStateSeeder.Preset
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 開発用: 地面（領地の草地・道・広場・川、ワールドマップの地形）の見た目を確かめる画面キャプチャ（[ShotRig]）。
 * 城の帯（Lv 1 / 6 / 12 / 16 / 24）ごとの街、拡大、門と川、領地のテーマ（秋・冬）、地図の拡大縮小と雪原・砂漠。
 * `./gradlew :app:testDebugUnitTest --tests '*GroundShotsTest*' -Pshots=<dir>`。`-Pshots` が無ければ skip する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w412dp-h892dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroundShotsTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("pass -Pshots=<dir> to render screenshots", ShotRig.dir() != null)
    }

    private fun rig(preset: Preset): ShotRig = ShotRig(rule).also { it.seed(preset) }

    private fun center(
        rig: ShotRig,
        fx: Float,
        fy: Float,
    ): Offset {
        val dm = rig.app.resources.displayMetrics
        return Offset(dm.widthPixels * fx, dm.heightPixels * fy)
    }

    /** ([fx], [fy]) のまわりを 2 本指で広げる（[out] が true なら縮める）。 */
    private fun pinch(
        rig: ShotRig,
        fx: Float,
        fy: Float,
        out: Boolean = false,
    ) {
        val c = center(rig, fx, fy)
        val w = rig.app.resources.displayMetrics.widthPixels
        val near = w * PINCH_NEAR
        val far = w * PINCH_FAR
        val from = if (out) far else near
        val to = if (out) near else far
        rule.onAllNodes(isRoot())[0].performTouchInput {
            pinch(Offset(c.x - from, c.y), Offset(c.x - to, c.y), Offset(c.x + from, c.y), Offset(c.x + to, c.y))
        }
        rig.settle()
        rig.settle()
    }

    /** 画面の割合で ([dx], [dy]) だけ指でなぞる（カメラは逆へ動く）。 */
    private fun drag(
        rig: ShotRig,
        dx: Float,
        dy: Float,
    ) {
        val from = center(rig, HALF, HALF)
        val dm = rig.app.resources.displayMetrics
        val to = Offset(from.x + dm.widthPixels * dx, from.y + dm.heightPixels * dy)
        rule.onAllNodes(isRoot())[0].performTouchInput { swipe(from, to, SWIPE_MS) }
        rig.settle()
        rig.settle()
    }

    /** 城を [level] にする（撮影用。帯ごとの街の見た目を見る）。 */
    private fun castleTo(
        rig: ShotRig,
        level: Int,
    ) {
        rig.update { s ->
            s.copy(slots = s.slots.map { if (it.type == BuildingType.CASTLE) it.copy(level = level) else it })
        }
    }

    @Test
    fun cityHamlet() {
        val rig = rig(Preset.FRESH_START)
        rig.launchToCity()
        rig.shot("ground_city_b1")
        pinch(rig, HALF, CITY_FOCUS_Y)
        rig.shot("ground_city_b1_zoom")
    }

    @Test
    fun cityTown() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        rig.shot("ground_city_b3")
        pinch(rig, HALF, CITY_FOCUS_Y)
        rig.shot("ground_city_b3_zoom")
        // 手前の門と、その外の道
        drag(rig, 0f, -DRAG_FAR)
        rig.shot("ground_city_b3_gate")
        pinch(rig, HALF, HALF, out = true)
        pinch(rig, HALF, HALF, out = true)
        rig.shot("ground_city_b3_far")
    }

    @Test
    fun cityBands() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        castleTo(rig, BAND2_LEVEL)
        rig.clearPopups()
        rig.settle(BAND_SETTLE_MS)
        rig.shot("ground_city_b2")
        castleTo(rig, BAND4_LEVEL)
        rig.clearPopups()
        // 帯の拡張の途中（前の帯の上に、次の帯の草地と道が現れてくる）
        rig.shot("ground_city_b4_growing", warmups = 1)
        rig.settle(BAND_SETTLE_MS)
        rig.shot("ground_city_b4")
        pinch(rig, HALF, CITY_FOCUS_Y)
        rig.shot("ground_city_b4_zoom")
    }

    @Test
    fun cityCapital() {
        val rig = rig(Preset.LATE_GAME)
        rig.launchToCity()
        rig.shot("ground_city_b5")
        pinch(rig, HALF, CITY_FOCUS_Y)
        rig.shot("ground_city_b5_zoom")
        // 右の端: 城壁の外の森と川
        pinch(rig, HALF, HALF, out = true)
        drag(rig, -DRAG_FAR, 0f)
        drag(rig, -DRAG_FAR, 0f)
        rig.shot("ground_city_b5_river")
    }

    @Test
    fun citySeasons() {
        val data = ClasspathDataSource.load()
        for (season in listOf("autumn", "winter", "spring")) {
            val rig = rig(Preset.RICH_CASTLE)
            rig.update { s -> Skins.grant(s.copy(shop = s.shop.copy(skins = s.shop.skins.copy())), "territory_$season", data) }
            val scenario = rig.launchToCity()
            rig.shot("ground_city_$season")
            scenario.close()
        }
    }

    @Test
    fun map() {
        val rig = rig(Preset.RICH_CASTLE)
        rig.launchToCity()
        rig.tapNav(rig.str(R.string.nav_map))
        rig.shot("ground_map", warmups = MAP_WARMUPS)
        pinch(rig, HALF, HALF)
        rig.shot("ground_map_zoom", warmups = MAP_WARMUPS)
        pinch(rig, HALF, HALF, out = true)
        pinch(rig, HALF, HALF, out = true)
        pinch(rig, HALF, HALF, out = true)
        rig.shot("ground_map_far", warmups = MAP_WARMUPS)
        // 北の雪原へ
        repeat(MAP_DRAGS) { drag(rig, 0f, DRAG_FAR) }
        rig.shot("ground_map_snow", warmups = MAP_WARMUPS)
        // 南東の砂漠へ
        repeat(MAP_DRAGS * 2) { drag(rig, -DRAG_FAR, -DRAG_FAR) }
        rig.shot("ground_map_desert", warmups = MAP_WARMUPS)
        pinch(rig, HALF, HALF)
        rig.shot("ground_map_desert_zoom", warmups = MAP_WARMUPS)
    }

    private companion object {
        const val HALF = 0.5f
        const val CITY_FOCUS_Y = 0.6f
        const val PINCH_NEAR = 0.08f
        const val PINCH_FAR = 0.3f
        const val DRAG_FAR = 0.3f
        const val SWIPE_MS = 300L
        const val BAND2_LEVEL = 6
        const val BAND4_LEVEL = 16
        const val BAND_SETTLE_MS = 2500L
        const val MAP_WARMUPS = 8
        const val MAP_DRAGS = 6
    }
}
