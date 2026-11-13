package com.ttmrase.crownfall.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.work.Configuration
import androidx.work.WorkManager
import com.ttmrase.crownfall.CrownfallApp
import com.ttmrase.crownfall.MainActivity
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.data.Startup
import com.ttmrase.crownfall.debug.DebugStateSeeder
import com.ttmrase.crownfall.notify.NotificationPrefs
import com.ttmrase.crownfall.ui.territory.SpriteCache
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.FileOutputStream

/**
 * 開発用: 実アプリ（MainActivity）を Robolectric のネイティブ描画で動かし、画面を PNG に書き出す。
 * エミュレータが無い環境でも、見た目の変更をその場で確かめられる（docs/02 §21）。
 * `-Pshots=<出力先>` を付けたときだけ動く（付けなければテストは skip される。CI では動かさない）。
 */
class ShotRig(private val rule: ComposeTestRule) {
    val app: CrownfallApp get() = ApplicationProvider.getApplicationContext()
    private val outDir: File = File(checkNotNull(dir())).apply { mkdirs() }

    init {
        // Robolectric では androidx.startup の初期化が走らないので、通知の予約が使う WorkManager を手で用意する
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
    }

    fun str(id: Int): String = app.getString(id)

    fun state(): GameState {
        val ready = app.container.repository.startup.value as Startup.Ready
        return checkNotNull(ready.session.state.value)
    }

    fun update(block: (GameState) -> GameState) {
        val ready = app.container.repository.startup.value as Startup.Ready
        runBlocking { ready.session.dispatch { s -> ActionResult.Success(block(s), tracked = true) } }
        settle()
    }

    fun seed(preset: DebugStateSeeder.Preset) {
        NotificationPrefs(app).permissionAsked = true
        runBlocking { DebugStateSeeder.seed(app.container, preset) }
        val ready = app.container.repository.startup.value as Startup.Ready
        runBlocking {
            ready.session.dispatch { s ->
                ActionResult.Success(
                    s.copy(shop = s.shop.copy(settings = s.shop.settings.copy(errorAnimation = false))),
                    tracked = true,
                )
            }
        }
    }

    fun hasText(
        text: String,
        substring: Boolean = false,
    ): Boolean =
        try {
            rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) {
            false
        }

    fun hasDescription(text: String): Boolean =
        try {
            rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty()
        } catch (_: IllegalStateException) {
            false
        }

    /** 実時間で待ちながら、メインのルーパーと Compose の時計を進める。 */
    fun waitFor(
        timeoutMs: Long = 20_000,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            settle(FRAME_MS * 2)
            Thread.sleep(SLEEP_MS)
        }
    }

    fun waitText(
        text: String,
        substring: Boolean = false,
    ) = waitFor { hasText(text, substring) }

    /** 仮想の時計を [ms] 進めて、描画を落ち着かせる（無限アニメがあるので waitForIdle は使わない）。 */
    fun settle(ms: Long = SETTLE_MS) {
        shadowOf(Looper.getMainLooper()).idle()
        rule.mainClock.advanceTimeBy(ms)
        shadowOf(Looper.getMainLooper()).idle()
    }

    fun click(
        text: String,
        substring: Boolean = false,
        index: Int = 0,
    ) {
        waitText(text, substring)
        rule.onAllNodesWithText(text, substring = substring)[index].performClick()
        settle()
    }

    fun clickDescription(
        text: String,
        index: Int = 0,
    ) {
        waitFor { hasDescription(text) }
        rule.onAllNodesWithContentDescription(text)[index].performClick()
        settle()
    }

    fun tapNav(label: String) {
        waitText(label)
        val nodes = rule.onAllNodesWithText(label)
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        settle()
    }

    /** 画面の割合の位置（0..1）をタップする（領地の建物など、文言で探せないもの用）。 */
    fun tapAt(
        fx: Float,
        fy: Float,
    ) {
        val dm = app.resources.displayMetrics
        rule.onAllNodes(isRoot())[0].performTouchInput { click(Offset(dm.widthPixels * fx, dm.heightPixels * fy)) }
        settle()
        settle()
    }

    fun back() {
        Espresso.pressBack()
        settle()
    }

    fun launch(): ActivityScenario<MainActivity> {
        rule.mainClock.autoAdvance = false
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        settle()
        return scenario
    }

    fun launchToCity(keepPopups: Boolean = false): ActivityScenario<MainActivity> {
        val scenario = launch()
        click(str(R.string.title_continue))
        waitText(str(R.string.nav_city))
        settle()
        if (!keepPopups) clearPopups()
        return scenario
    }

    fun clearPopups() {
        repeat(8) {
            settle()
            when {
                hasDescription(str(R.string.common_close)) -> back()
                hasText(str(R.string.common_ok)) -> click(str(R.string.common_ok))
                hasText(str(R.string.login_gift_later)) -> click(str(R.string.login_gift_later))
                else -> return
            }
        }
    }

    fun open(route: String) {
        app.container.pendingRoute.value = route
        settle()
        settle()
    }

    /**
     * いま出ている全ウィンドウ（Activity・ダイアログ・シート・ポップアップ）を重ねて 1 枚にする。
     * スプライトやマップのチャンクは裏のスレッドで作られるので、描いては待つのを、枚数が落ち着くまで繰り返す。
     */
    fun shot(
        name: String,
        warmups: Int = MIN_WARMUPS,
    ) {
        val cache = SpriteCache.shared(app)
        var last = -1
        var stable = 0
        var rounds = 0
        while (rounds < MAX_WARMUPS && (rounds < warmups || stable < STABLE_ROUNDS)) {
            compose()
            Thread.sleep(WARMUP_SLEEP_MS)
            settle(FRAME_MS)
            val count = cache.spriteCount
            stable = if (count == last) stable + 1 else 0
            last = count
            rounds++
        }
        val out = compose()
        FileOutputStream(File(outDir, "$name.png")).use { out.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    }

    private fun compose(): Bitmap {
        val dm = app.resources.displayMetrics
        val out = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        for (view in WindowInspector.getGlobalWindowViews()) {
            if (view.width == 0 || view.height == 0 || view.visibility != View.VISIBLE) continue
            val lp = view.layoutParams as? WindowManager.LayoutParams
            if (lp != null && lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                canvas.drawColor(Color.argb((lp.dimAmount * MAX_ALPHA).toInt(), 0, 0, 0))
            }
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            // Robolectric はウィンドウの配置（中央寄せ）を再現しないので、画面より小さいダイアログは自分で中央に置く
            val x = if (loc[0] == 0 && view.width < dm.widthPixels) (dm.widthPixels - view.width) / 2 else loc[0]
            val y = if (loc[1] == 0 && view.height < dm.heightPixels) (dm.heightPixels - view.height) / 2 else loc[1]
            canvas.drawBitmap(render(view), x.toFloat(), y.toFloat(), null)
        }
        return out
    }

    private fun render(view: View): Bitmap {
        val w = view.width
        val h = view.height
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            val node = RenderNode("shot")
            node.setPosition(0, 0, w, h)
            val recording = node.beginRecording()
            view.draw(recording)
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().syncAndDraw()
            val image = reader.acquireNextImage()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(image.planes[0].buffer)
            image.close()
            return bitmap
        } finally {
            renderer.destroy()
            reader.close()
        }
    }

    companion object {
        private const val FRAME_MS = 16L
        private const val SETTLE_MS = 400L
        private const val SLEEP_MS = 15L
        private const val MAX_ALPHA = 255
        private const val PNG_QUALITY = 100
        private const val MIN_WARMUPS = 2
        private const val MAX_WARMUPS = 40
        private const val STABLE_ROUNDS = 2
        private const val WARMUP_SLEEP_MS = 120L

        /** 出力先（`-Pshots=<dir>`）。未指定なら null。 */
        fun dir(): String? = System.getProperty("crownfall.shots.dir")?.takeIf { it.isNotBlank() }
    }
}
