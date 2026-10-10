package com.ttmrase.crownfall.ui.heroes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.SaveStorage
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.util.FixedClock
import com.ttmrase.crownfall.offers.NoOpOfferHooks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * レビュー M2 / M3 の回帰テスト: 演出中のタップが下の召喚ボタンに抜けない（追加の課金が起きない）。
 * 「もう一度！」の連打でも 1 回しか召喚されない。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SummonScreenTest {
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
            session.dispatch { s ->
                val slots = s.slots.map { if (it.type == BuildingType.TAVERN) it.copy(level = 1) else it }
                ActionResult.Success(s.copy(slots = slots, gems = START_GEMS, items = emptyMap()), tracked = true)
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun show() {
        rule.setContent {
            MaterialTheme { SummonScreen(session, data, NoOpOfferHooks, SnackbarHostState()) }
        }
        rule.waitForIdle()
    }

    private val gems get() = session.state.value!!.gems

    @Test
    fun tappingTheCoveredSummonButtonDuringTheRitualDoesNotSummonAgain() {
        show()
        val once = context.getString(R.string.summon_once)
        rule.onAllNodesWithText(once)[0].performClick()
        rule.waitForIdle()
        val afterFirst = gems
        assertEquals(START_GEMS - data.heroRules.premium.gems, afterFirst)
        // 演出（または結果）のオーバーレイの下にある「単発」を押しても、何も起きない
        rule.onAllNodesWithText(once)[0].performClick()
        rule.waitForIdle()
        assertEquals("the overlay must absorb the tap", afterFirst, gems)
    }

    @Test
    fun rapidDoubleTapOnAgainSummonsOnlyOnce() {
        show()
        rule.onAllNodesWithText(context.getString(R.string.summon_once))[0].performClick()
        rule.mainClock.advanceTimeBy(8_000)
        rule.waitForIdle()
        val before = gems
        val again = context.getString(R.string.summon_again)
        rule.onAllNodesWithText(again)[0].performClick()
        rule.onAllNodesWithText(again)[0].performClick()
        rule.waitForIdle()
        assertEquals("a double tap must cost exactly one summon", before - data.heroRules.premium.gems, gems)
    }

    private companion object {
        const val START_GEMS = 100_000L
    }
}
