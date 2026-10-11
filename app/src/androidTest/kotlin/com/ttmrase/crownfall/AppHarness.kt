package com.ttmrase.crownfall

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.data.Startup
import com.ttmrase.crownfall.debug.DebugStateSeeder
import com.ttmrase.crownfall.notify.NotificationPrefs
import kotlinx.coroutines.runBlocking

/**
 * 実アプリ（MainActivity）をエミュレータで動かす androidTest の共通部品。DebugStateSeeder で状態を作り、
 * タイトルの「続きから」で城内へ進み、画面を pendingRoute で開く。失敗したときは画面を撮ってから投げ直す。
 */
class AppHarness(private val rule: ComposeTestRule) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val app get() = context.applicationContext as CrownfallApp

    fun str(id: Int): String = context.getString(id)

    fun state(): GameState {
        val ready = app.container.repository.startup.value as Startup.Ready
        return checkNotNull(ready.session.state.value)
    }

    /** 実行中のセッションの状態を直接書き換える（シードでは足りない細かい前提を作る）。 */
    fun update(block: (GameState) -> GameState) {
        val ready = app.container.repository.startup.value as Startup.Ready
        runBlocking { ready.session.dispatch { s -> ActionResult.Success(block(s), tracked = true) } }
    }

    fun seed(preset: DebugStateSeeder.Preset) {
        // チュートリアル完了のシードで通知権限のシステムダイアログが出て、以降のテストがフォーカスを失うのを防ぐ
        NotificationPrefs(context).permissionAsked = true
        runBlocking { DebugStateSeeder.seed(app.container, preset) }
        // 決済の「エラー: もう一度お試しください」演出は 2% で乱数で出る（設計どおり）。テストが不安定にならないよう切る
        update { s -> s.copy(shop = s.shop.copy(settings = s.shop.settings.copy(errorAnimation = false))) }
    }

    fun hasText(
        text: String,
        substring: Boolean = false,
    ) = noHierarchyIsFalse { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    fun hasDescription(text: String) =
        noHierarchyIsFalse { rule.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() }

    /** Activity が起動する前（Compose の階層がまだ無い）は例外ではなく「まだ無い」として待たせる。 */
    private fun noHierarchyIsFalse(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (_: IllegalStateException) {
            false
        }

    fun waitText(
        text: String,
        timeoutMs: Long = 30_000,
        substring: Boolean = false,
    ) {
        rule.waitUntil(timeoutMs) { hasText(text, substring) }
    }

    /** 下部ナビの項目を押す（同じ文言が画面の上の方に出る画面もあるので、最後の 1 つ = 下端のナビを押す）。 */
    fun tapNav(label: String) {
        waitText(label)
        val nodes = rule.onAllNodesWithText(label)
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
        rule.waitForIdle()
    }

    fun click(
        text: String,
        substring: Boolean = false,
    ) {
        waitText(text, 20_000, substring)
        rule.onAllNodesWithText(text, substring = substring)[0].performClick()
    }

    /** タイトルから「続きから」で城内へ。ログイン時のポップアップ（本日限定・お知らせ・ギフト）は閉じて、下部ナビが見える状態にする。 */
    fun launchToCity(keepOfferPopup: Boolean = false): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        step("launch") {
            click(str(R.string.title_continue))
            waitText(str(R.string.nav_city))
        }
        if (!keepOfferPopup) clearPopups()
        return scenario
    }

    /** 画面を覆うポップアップを閉じる（オファーは戻るで、お知らせは OK で）。 */
    fun clearPopups() {
        repeat(8) {
            rule.waitForIdle()
            when {
                hasDescription(str(R.string.common_close)) -> Espresso.pressBack()
                hasText(str(R.string.common_ok)) -> rule.onAllNodesWithText(str(R.string.common_ok))[0].performClick()
                hasText(
                    str(R.string.login_gift_later),
                ) -> rule.onNodeWithText(str(R.string.login_gift_later)).performClick()
                else -> return
            }
        }
    }

    fun open(route: String) {
        app.container.pendingRoute.value = route
        rule.waitForIdle()
    }

    /** [block] が落ちたら、そのときの画面を `FAIL_<name>` で撮ってから投げ直す。 */
    fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            Screenshots.save("FAIL_$name")
            throw t
        }
    }
}
