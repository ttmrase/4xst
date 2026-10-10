package com.ttmrase.crownfall

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.debug.DebugStateSeeder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * エミュレータ上の起動の確認と、DebugStateSeeder の動作確認（T10.1 の足場）。UI テストの本体は Debugger が書く。
 * アプリが起動でき、シードした状態がセッションに入ることだけを見る。
 */
@RunWith(AndroidJUnit4::class)
class SeederSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun seededRichCastleReachesTheSession() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as CrownfallApp
        runBlocking { DebugStateSeeder.seed(app.container, DebugStateSeeder.Preset.OFFER_READY) }
        val ready = app.container.repository.startup.value as com.ttmrase.crownfall.data.Startup.Ready
        val state = checkNotNull(ready.session.state.value)
        assertEquals(12, state.slots.first { it.type == com.ttmrase.crownfall.core.model.BuildingType.CASTLE }.level)
        assertNotNull(OfferEngine.next(state))
    }
}
