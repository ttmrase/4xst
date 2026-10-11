package com.ttmrase.crownfall.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.Screenshots
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.SaveStorage
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.shop.TriggerCtx
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.util.FixedClock
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.SessionCoordinator
import com.ttmrase.crownfall.ui.shop.FakeBillingSheet
import com.ttmrase.crownfall.ui.shop.OfferPresenter
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

/**
 * 戻るの原則（docs/02 §17）を、実際の Android（エミュレータ）のウィンドウ・戻るキーで確かめる。
 * Robolectric の BackPressTest と同じ筋書きに、スクリーンショットを添える。
 */
@RunWith(AndroidJUnit4::class)
class BackPressInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val data = ClasspathDataSource.load()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var session: GameSession

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
                val defeated = OfferEngine.fire(s, "defeated", TriggerCtx(), data)
                val castleUp = TriggerCtx(lv = 3, params = mapOf("lv" to 3L))
                ActionResult.Success(OfferEngine.fire(defeated, "castleUp", castleUp, data), tracked = true)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun closeLabel() = context.getString(R.string.common_close)

    private fun popupShown() = runCatching { rule.onNodeWithContentDescription(closeLabel()).assertExists() }.isSuccess

    private fun showPresenter() {
        val coordinator = SessionCoordinator(GameRepository(context, scope), scope)
        rule.setContent {
            MaterialTheme { OfferPresenter(session, data, coordinator, busy = false, onBuy = {}, onIdle = {}) }
        }
        rule.waitForIdle()
    }

    @Test
    fun systemBackClosesTheOfferPopupAndStopsTheRestOfTheSessionsAutoPopups() {
        assertTrue(OfferEngine.active(session.state.value!!).size >= 2)
        showPresenter()
        assertTrue("the offer popup must be shown", popupShown())
        Screenshots.save("offer_popup")
        Espresso.pressBack()
        rule.waitForIdle()
        assertFalse("back must close the popup", popupShown())
        assertTrue("back stops the auto popups", session.state.value!!.shop.popupsStopped)
        rule.mainClock.advanceTimeBy(30_000)
        rule.waitForIdle()
        assertFalse("no second popup after back", popupShown())
    }

    @Test
    fun systemBackClosesTheBillingSheetBeforeAnyPayment() {
        var closed = false
        val sku = data.offers.gemSkus.first().sku
        rule.setContent {
            MaterialTheme { FakeBillingSheet(sku, session, data, notify = {}, onClose = { closed = true }) }
        }
        rule.waitForIdle()
        rule.onNodeWithText(context.getString(R.string.billing_title)).assertExists()
        Screenshots.save("billing_confirm")
        Espresso.pressBack()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("back must close the sheet", closed)
        assertEquals(0L, session.state.value!!.shop.purchaseCount)
    }
}
