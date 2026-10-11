package com.ttmrase.crownfall.ui.shop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.SaveStorage
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.ShopHooks
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.util.FixedClock
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.SessionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 1.0.1 の S1 の回帰テスト（戻るの原則、docs/02 §17）: オファーのポップアップと擬似決済シートは、
 * システムの戻るで閉じる（決済の処理中だけは閉じない）。Robolectric で戻るのキーを送って確かめる。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackPressTest {
    @get:Rule
    val rule = createComposeRule()

    private val data = ClasspathDataSource.load()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: GameSession
    private val context get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private class MemoryStorage : SaveStorage {
        var primary: String? = null
        var backup: String? = null

        override fun readPrimary() = primary

        override fun readBackup() = backup

        override fun writeAtomic(text: String) {
            backup = primary
            primary = text
        }

        override fun quarantinePrimary() {
            primary = null
        }
    }

    @Before
    fun setUp() {
        session = GameSession(data, FixedClock(20_000L * 86_400 + 43_200), MemoryStorage(), scope)
        runBlocking {
            session.newGame("QA", "hero_aldric", 42)
            // 自動のトリガー（城 Lv アップ・敗北）で 2 つのオファーを積む。タップで呼ぶ request は抑制の対象外なので使わない
            session.dispatch { s ->
                val castle = s.slots.first { it.type == BuildingType.CASTLE }.slotId
                val events =
                    listOf(GameEvent.BuildingLevelUp(castle, BuildingType.CASTLE, 3), GameEvent.PlayerDefeated(1))
                ActionResult.Success(ShopHooks.onEvents(s, events, data), tracked = true)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun closeLabel() = context.getString(R.string.common_close)

    private fun presenter() {
        val coordinator = SessionCoordinator(GameRepository(context, scope), scope)
        rule.setContent {
            MaterialTheme {
                OfferPresenter(session, data, coordinator, busy = false, onBuy = {}, onIdle = {})
            }
        }
        rule.waitForIdle()
    }

    private fun popupShown() = runCatching { rule.onNodeWithContentDescription(closeLabel()).assertExists() }.isSuccess

    @Test
    fun systemBackClosesTheOfferPopupAndStopsTheRestOfTheSessionsAutoPopups() {
        assertTrue("two offers must be queued", OfferEngine.active(session.state.value!!).size >= 2)
        presenter()
        assertTrue("the offer popup must be shown", popupShown())
        Espresso.pressBack()
        rule.waitForIdle()
        assertFalse("back must close the popup", popupShown())
        val s = session.state.value!!
        assertTrue("back stops the auto popups", s.shop.popupsStopped)
        // 少し待っても、次のオファーが続けて出ない（棚にはまだ残っている）
        rule.mainClock.advanceTimeBy(30_000)
        rule.waitForIdle()
        assertFalse("no second popup after back", popupShown())
        assertTrue(OfferEngine.active(session.state.value!!).any { !it.dismissed })
    }

    @Test
    fun theCrossClosesThePopupWithoutStoppingTheSessionAndNoSecondPopupFollowsImmediately() {
        presenter()
        assertTrue(popupShown())
        rule.mainClock.advanceTimeBy(2_500) // × は 2 秒遅れて有効になる
        rule.onNodeWithContentDescription(closeLabel()).performClick()
        rule.waitForIdle()
        assertFalse("× must close the popup", popupShown())
        val s = session.state.value!!
        assertFalse("× does not stop the session's popups", s.shop.popupsStopped)
        assertEquals(s.nowEpochSec, s.shop.lastPopupClosedEpochSec)
        // 閉じた直後（popupGapSec 内）は次を出さない
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertFalse("no popup right after closing", popupShown())
    }

    /** 画面に出ている文字（ダイアログ/シートの root を含む）。失敗時に状況が分かるようにする。 */
    private fun shownTexts(): String =
        rule.onAllNodes(isRoot()).fetchSemanticsNodes().joinToString(" | ") { textsOf(it) }

    private fun textsOf(node: SemanticsNode): String {
        val own = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
        return (listOf(own) + node.children.map { textsOf(it) }).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun billing(onClosed: () -> Unit) {
        val sku = data.offers.gemSkus.first().sku
        rule.setContent {
            MaterialTheme { FakeBillingSheet(sku, session, data, notify = {}, onClose = onClosed) }
        }
        rule.waitForIdle()
    }

    @Test
    fun systemBackClosesTheBillingSheetBeforeAnyPayment() {
        var closed = false
        billing { closed = true }
        rule.onNodeWithText(context.getString(R.string.billing_title)).assertExists()
        Espresso.pressBack()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("back must close the sheet", closed)
        // 閉じただけでは購入にならない
        assertEquals(0L, session.state.value!!.shop.purchaseCount)
    }

    private fun pay() {
        rule.onNodeWithText(context.getString(R.string.billing_one_tap))
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
    }

    private fun showsProcessing() = shownTexts().contains(context.getString(R.string.billing_do_not_close))

    @Test
    fun theBillingSheetIgnoresBackWhileProcessing() {
        var closed = false
        billing { closed = true }
        pay()
        // 通常は「処理中…」、10% は「確認しています…」。どちらでも出る注意書きで処理中を判定する
        assertTrue("must be processing after paying; shown=[${shownTexts()}]", showsProcessing())
        Espresso.pressBack()
        rule.waitForIdle()
        assertFalse("processing must not be cancelled by back", closed)
        assertTrue("still processing after back; shown=[${shownTexts()}]", showsProcessing())
        assertEquals("nothing is bought by closing", 0L, session.state.value!!.shop.purchaseCount)
    }

    @Test
    fun theBillingSheetClosesWithBackOnceTheProcessingHasFinished() {
        var closed = false
        billing { closed = true }
        pay()
        assertTrue("must be processing after paying; shown=[${shownTexts()}]", showsProcessing())
        // 処理の待ち時間はメインのルーパー上の時間なので、そちらを進める（Compose の時計では進まない）
        // 最長 6 秒の処理が終わるまで（9 秒のガードには届かない）進め、購入の dispatch（実時間のスレッド）は待つ
        ShadowLooper.idleMainLooper(7, TimeUnit.SECONDS)
        val done = context.getString(R.string.billing_done)
        val error = context.getString(R.string.billing_error)
        runCatching {
            rule.waitUntil(timeoutMillis = 10_000) { shownTexts().let { it.contains(done) || it.contains(error) } }
        }
        val shown = shownTexts()
        val finished = shown.contains(done) || shown.contains(error)
        assertTrue("processing must finish (done or error); closed=$closed shown=[$shown]", finished)
        assertFalse("the sheet must stay open until the user closes it", closed)
        Espresso.pressBack()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("after processing, back must close the sheet", closed)
    }
}
