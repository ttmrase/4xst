package com.ttmrase.crownfall.navigation

import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 1.0.1 の S1 の回帰テスト（戻るの原則、docs/02 §17）。実際の NavHost と同じルート文字列のグラフで、
 * 下部ナビと通知の遷移（[navigateBottom] / [openPendingRoute]）を検証する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NavActionsTest {
    private lateinit var nav: TestNavHostController

    @Before
    fun setUp() {
        nav = TestNavHostController(ApplicationProvider.getApplicationContext())
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        nav.setGraph(
            nav.createGraph(startDestination = Screen.Title.route) {
                for (s in Screen.entries) {
                    // 兵舎だけは本物と同じく引数付きのルート
                    composable(if (s == Screen.Barracks) "${Screen.Barracks.route}?tab={tab}" else s.route) {}
                }
            },
            null,
        )
        // タイトル → 城内（タイトルは積まない）
        nav.navigate(Screen.City.route) { popUpTo(Screen.Title.route) { inclusive = true } }
    }

    private val route: String? get() = nav.currentDestination?.route?.substringBefore('?')

    private fun stack(): List<String> =
        nav.currentBackStack.value.mapNotNull { it.destination.route?.substringBefore('?') }
            .filter { it != "" && it != "null" && !it.startsWith("androidx") }

    @Test
    fun cityTabFromAChildScreenAlwaysLandsOnTheCityAndLeavesNoChildBehind() {
        nav.navigate(Screen.Chat.route)
        assertEquals("chat", route)
        nav.navigateBottom(BottomDestination.City)
        assertEquals("city", route)
        assertEquals(listOf("city"), stack())
        // 城内の戻るは、これ以上戻る先が無い（退出確認は BackHandler の仕事）
        assertFalse(nav.popBackStack())
    }

    @Test
    fun cityTabFromEveryScreenReturnsToTheCityInOneTap() {
        for (screen in Screen.entries.filter { it != Screen.Title && it != Screen.City }) {
            nav.navigateBottom(BottomDestination.Alliance)
            nav.navigate(if (screen == Screen.Barracks) "barracks?tab=1" else screen.route)
            nav.navigateBottom(BottomDestination.City)
            assertEquals("city from ${screen.route}", "city", route)
            assertEquals("stack after city tap from ${screen.route}", listOf("city"), stack())
        }
    }

    @Test
    fun cityToAllianceToChatThenBackWalksTheStackBack() {
        nav.navigateBottom(BottomDestination.Alliance)
        assertEquals("alliance", route)
        nav.navigate(Screen.Chat.route)
        assertEquals("chat", route)
        assertTrue(nav.popBackStack())
        assertEquals("alliance", route)
        assertTrue(nav.popBackStack())
        assertEquals("city", route)
        assertFalse(nav.popBackStack())
    }

    @Test
    fun reselectingTheAllianceTabInAChildScreenReturnsToTheAllianceRoot() {
        nav.navigateBottom(BottomDestination.Alliance)
        nav.navigate(Screen.Chat.route)
        nav.navigateBottom(BottomDestination.Alliance)
        assertEquals("alliance", route)
        assertEquals(listOf("city", "alliance"), stack())
        // 再選択を繰り返しても積み上がらない
        repeat(3) { nav.navigateBottom(BottomDestination.Alliance) }
        assertEquals(listOf("city", "alliance"), stack())
    }

    @Test
    fun switchingTabsNeverStacksMoreThanTheCityAndTheCurrentTab() {
        val order =
            listOf(
                BottomDestination.Map,
                BottomDestination.Alliance,
                BottomDestination.Heroes,
                BottomDestination.Shop,
                BottomDestination.Map,
                BottomDestination.City,
                BottomDestination.Shop,
                BottomDestination.City,
            )
        for (tab in order) {
            nav.navigateBottom(tab)
            assertEquals(tab.screen.route, route)
            val expected = if (tab == BottomDestination.City) listOf("city") else listOf("city", tab.screen.route)
            assertEquals("after $tab", expected, stack())
        }
    }

    @Test
    fun aTabDoesNotRestoreItsOldChildStackAfterVisitingTheCity() {
        nav.navigateBottom(BottomDestination.Map)
        nav.navigate(Screen.Mail.route) // マップの子画面（メール）
        nav.navigateBottom(BottomDestination.City)
        nav.navigateBottom(BottomDestination.Map)
        assertEquals("world_map", route)
        assertEquals(listOf("city", "world_map"), stack())
        assertTrue(nav.popBackStack())
        assertEquals("city", route)
    }

    @Test
    fun theBackStackAlwaysReachesTheCityAndNeverContainsTheTitle() {
        val rnd = java.util.Random(11)
        val tabs = BottomDestination.entries
        val children = listOf(Screen.Chat, Screen.Mail, Screen.Research, Screen.Quests, Screen.Settings, Screen.Event)
        repeat(300) {
            when (rnd.nextInt(3)) {
                0 -> nav.navigateBottom(tabs[rnd.nextInt(tabs.size)])
                1 -> nav.navigate(children[rnd.nextInt(children.size)].route) { launchSingleTop = true }
                else -> if (stack().size > 1) nav.popBackStack()
            }
            assertEquals("city", stack().first())
            assertTrue("title must be gone: ${stack()}", "title" !in stack())
        }
        // どこからでも「戻る」を繰り返せば城内に着く
        while (stack().size > 1) assertTrue(nav.popBackStack())
        assertEquals("city", route)
    }

    @Test
    fun aPendingRouteIsConsumedExactlyOnce() {
        val pending = MutableStateFlow<String?>(Screen.Mail.route)
        assertTrue(nav.openPendingRoute(pending))
        assertEquals("mail", route)
        assertNull(pending.value)
        val before = stack()
        // 2 回目は何も起きない
        assertFalse(nav.openPendingRoute(pending))
        assertEquals(before, stack())
        // 同じ画面を重ねて開かない
        pending.value = Screen.Mail.route
        assertTrue(nav.openPendingRoute(pending))
        assertEquals(before, stack())
        // 戻れば城内
        assertTrue(nav.popBackStack())
        assertEquals("city", route)
    }

    @Test
    fun anUnknownPendingRouteIsDroppedWithoutNavigating() {
        val pending = MutableStateFlow<String?>("no_such_screen")
        assertFalse(nav.openPendingRoute(pending))
        assertNull(pending.value)
        assertEquals("city", route)
        assertFalse(nav.openPendingRoute(MutableStateFlow(null)))
    }
}
