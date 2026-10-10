package com.ttmrase.crownfall.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ai.AiService
import com.ttmrase.crownfall.chat.ChatCoordinator
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.shop.OfferEngine
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.SessionCoordinator
import com.ttmrase.crownfall.data.Startup
import com.ttmrase.crownfall.offers.NoOpOfferHooks
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.offers.ShopOfferHooks
import com.ttmrase.crownfall.ui.alliance.AllianceScreen
import com.ttmrase.crownfall.ui.alliance.unreadTotal
import com.ttmrase.crownfall.ui.barracks.BarracksScreen
import com.ttmrase.crownfall.ui.chat.ChatScreen
import com.ttmrase.crownfall.ui.city.CityScreen
import com.ttmrase.crownfall.ui.common.LocalClock
import com.ttmrase.crownfall.ui.common.RaidBanner
import com.ttmrase.crownfall.ui.common.calmStateFlow
import com.ttmrase.crownfall.ui.common.collectClock
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.event.EventScreen
import com.ttmrase.crownfall.ui.eventMessage
import com.ttmrase.crownfall.ui.heroes.HeroesScreen
import com.ttmrase.crownfall.ui.heroes.SummonScreen
import com.ttmrase.crownfall.ui.mail.MailScreen
import com.ttmrase.crownfall.ui.map.MapScreen
import com.ttmrase.crownfall.ui.quest.QuestScreen
import com.ttmrase.crownfall.ui.research.ResearchScreen
import com.ttmrase.crownfall.ui.screens.StubScreen
import com.ttmrase.crownfall.ui.settings.SettingsScreen
import com.ttmrase.crownfall.ui.shop.FakeBillingSheet
import com.ttmrase.crownfall.ui.shop.OfferPresenter
import com.ttmrase.crownfall.ui.shop.ShopScreen
import com.ttmrase.crownfall.ui.title.TitleScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 単一 Activity 用の NavHost と下部ナビ（Title では非表示）。 */
private const val CLAIM_PERIOD_MS = 30_000L

@Composable
fun CrownfallNavHost(
    repository: GameRepository,
    ai: AiService,
    chat: ChatCoordinator,
    sessionCoordinator: SessionCoordinator,
) {
    val navController = rememberNavController()
    val startup by repository.startup.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val context = LocalContext.current
    val readySession = (startup as? Startup.Ready)?.session
    val uiScope = rememberCoroutineScope()
    val ready = startup as? Startup.Ready
    val hooks: OfferHooks =
        remember(ready?.session) {
            if (ready != null) ShopOfferHooks(ready.session, ready.data, uiScope) else NoOpOfferHooks
        }
    var billingId by rememberSaveable { mutableStateOf<String?>(null) }
    // 退出前オファーの流れ: 0 = なし、1 = 「待って！」を出している、2 = 閉じ終えたのでタイトルへ戻る確認
    var exitStage by rememberSaveable { mutableIntStateOf(0) }
    // 毎秒変わる状態を NavHost で読まない。必要な値だけを取り出し、変わったときだけ再構図する。
    val unreadFlow =
        remember(readySession) {
            readySession?.state
                ?.map { s -> s?.alliance?.let { unreadTotal(it.history, it.chatRead) } ?: 0 }
                ?.distinctUntilChanged() ?: flowOf(0)
        }
    val unread by unreadFlow.collectAsStateWithLifecycle(0)

    // クエストの未受け取り数。戦力の計算を伴うので 30 秒に 1 回だけ数え直す
    val claimFlow =
        remember(ready?.session) {
            ready?.let { r ->
                r.session.calmStateFlow(CLAIM_PERIOD_MS)
                    .map { s -> if (s != null) QuestLogic.claimableCount(s, r.data) else 0 }
                    .distinctUntilChanged()
            } ?: flowOf(0)
        }
    val claimable by claimFlow.collectAsStateWithLifecycle(0)
    val clock = readySession?.collectClock()

    // 全画面共通のトースト（ヘルプ受領・ギフト・行軍の帰還など）。
    LaunchedEffect(readySession) {
        readySession?.events?.collect { event ->
            val message = eventMessage(context, event) ?: return@collect
            launch { snackbarHostState.showReplacing(message) }
        }
    }

    BackHandler(enabled = exitStage == 0 && destination?.route == Screen.City.route && ready != null) {
        val r = ready
        if (r != null) {
            uiScope.launch {
                r.session.dispatch { Actions.exitOffer(it, r.data) }
                val waiting =
                    r.session.state.value?.let { st ->
                        OfferEngine.active(st).any { it.trigger == "exitIntent" && !it.dismissed }
                    } == true
                exitStage = if (waiting) 1 else 2
            }
        }
    }

    CompositionLocalProvider(LocalClock provides clock) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (destination != null && destination.route != Screen.Title.route) {
                    NavigationBar {
                        BottomDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination.hierarchy.any { it.route == item.screen.route },
                                onClick = {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(Screen.City.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    val isAlliance = item == BottomDestination.Alliance
                                    val isCity = item == BottomDestination.City
                                    val count =
                                        when {
                                            isAlliance -> unread
                                            isCity -> claimable
                                            else -> 0
                                        }
                                    BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                                        Icon(item.icon, contentDescription = null)
                                    }
                                },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                (startup as? Startup.Ready)?.let {
                    if (destination?.route != Screen.Title.route) {
                        RaidBanner(
                            it.session,
                        )
                    }
                }
                NavHost(
                    navController = navController,
                    startDestination = Screen.Title.route,
                    modifier = Modifier.weight(1f),
                ) {
                    composable(Screen.Title.route) {
                        TitleScreen(
                            repository = repository,
                            onEnterGame = {
                                navController.navigate(Screen.City.route) {
                                    popUpTo(Screen.Title.route) { inclusive = true }
                                }
                            },
                        )
                    }
                    composable(Screen.City.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) {
                            CityScreen(
                                session = ready.session,
                                data = ready.data,
                                hooks = hooks,
                                snackbarHostState = snackbarHostState,
                                onNavigate = { navController.navigate(it.route) },
                                onOpenBarracks = { tab -> navController.navigate("${Screen.Barracks.route}?tab=$tab") },
                            )
                        }
                    }
                    composable(
                        route = "${Screen.Barracks.route}?tab={tab}",
                        arguments =
                            listOf(
                                navArgument("tab") {
                                    type = NavType.IntType
                                    defaultValue = 0
                                },
                            ),
                    ) { entry ->
                        val ready = startup as? Startup.Ready
                        if (ready != null) {
                            BarracksScreen(
                                ready.session,
                                ready.data,
                                hooks,
                                snackbarHostState,
                                entry.arguments?.getInt("tab") ?: 0,
                            )
                        }
                    }
                    composable(Screen.Research.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) ResearchScreen(ready.session, ready.data, hooks, snackbarHostState)
                    }
                    composable(Screen.Heroes.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) {
                            HeroesScreen(
                                ready.session,
                                ready.data,
                                snackbarHostState,
                            ) { navController.navigate(it.route) }
                        }
                    }
                    composable(Screen.Summon.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) SummonScreen(ready.session, ready.data, hooks, snackbarHostState)
                    }
                    composable(Screen.WorldMap.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) {
                            MapScreen(
                                ready.session,
                                ready.data,
                                snackbarHostState,
                            ) { navController.navigate(Screen.Mail.route) }
                        }
                    }
                    composable(Screen.Mail.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) MailScreen(ready.session, ready.data)
                    }
                    composable(Screen.Alliance.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) {
                            AllianceScreen(ready.session, ready.data, snackbarHostState) {
                                navController.navigate(Screen.Chat.route)
                            }
                        }
                    }
                    composable(Screen.Chat.route) {
                        val ready = startup as? Startup.Ready
                        if (ready != null) ChatScreen(ready.session, ready.data, chat.typing, snackbarHostState)
                    }
                    composable(Screen.Shop.route) {
                        val r = startup as? Startup.Ready
                        if (r != null) ShopScreen(r.session, r.data, snackbarHostState) { billingId = it }
                    }
                    composable(Screen.Quests.route) {
                        val r = startup as? Startup.Ready
                        if (r != null) QuestScreen(r.session, r.data, snackbarHostState)
                    }
                    composable(Screen.Event.route) {
                        val r = startup as? Startup.Ready
                        if (r != null) EventScreen(r.session, r.data)
                    }
                    composable(Screen.Settings.route) {
                        val r = startup as? Startup.Ready
                        SettingsScreen(ai, r?.session, snackbarHostState)
                    }
                    val implemented =
                        setOf(
                            Screen.Title,
                            Screen.City,
                            Screen.Barracks,
                            Screen.Research,
                            Screen.Heroes,
                            Screen.Summon,
                            Screen.WorldMap,
                            Screen.Mail,
                            Screen.Alliance,
                            Screen.Chat,
                            Screen.Settings,
                            Screen.Shop,
                            Screen.Quests,
                            Screen.Event,
                        )
                    Screen.entries
                        .filter { it !in implemented }
                        .forEach { screen ->
                            composable(screen.route) { StubScreen(screen) }
                        }
                }
            }
        }
    }

    // オファーのポップアップ、決済シート、退出前の確認（Title では出さない）。
    if (ready != null && destination != null && destination.route != Screen.Title.route) {
        OfferPresenter(
            session = ready.session,
            data = ready.data,
            coordinator = sessionCoordinator,
            busy = billingId != null,
            onBuy = {
                billingId = it
                // 退出オファーを買いに行ったら、タイトルへの確認は出さず城内に残る
                if (exitStage == 1) exitStage = 0
            },
            onIdle = { if (exitStage == 1) exitStage = 2 },
        )
        billingId?.let { id ->
            FakeBillingSheet(
                id,
                ready.session,
                ready.data,
                notify = { message -> uiScope.launch { snackbarHostState.showReplacing(message) } },
            ) { billingId = null }
        }
        if (exitStage == 2) {
            AlertDialog(
                onDismissRequest = { exitStage = 0 },
                title = { Text(stringResource(R.string.exit_title)) },
                text = { Text(stringResource(R.string.exit_body)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            exitStage = 0
                            navController.navigate(
                                Screen.Title.route,
                            ) { popUpTo(Screen.City.route) { inclusive = true } }
                        },
                    ) { Text(stringResource(R.string.common_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { exitStage = 0 }) { Text(stringResource(R.string.common_cancel)) }
                },
            )
        }
    }
}
