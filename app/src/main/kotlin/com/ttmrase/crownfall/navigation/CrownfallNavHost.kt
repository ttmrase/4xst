package com.ttmrase.crownfall.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
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
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.shop.Entitlements
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
import com.ttmrase.crownfall.ui.ceremony.AchievementToastHost
import com.ttmrase.crownfall.ui.ceremony.CastleCeremonyHost
import com.ttmrase.crownfall.ui.chat.ChatScreen
import com.ttmrase.crownfall.ui.city.CityScreen
import com.ttmrase.crownfall.ui.common.BgmHost
import com.ttmrase.crownfall.ui.common.LocalClock
import com.ttmrase.crownfall.ui.common.RaidBanner
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.SoundEvents
import com.ttmrase.crownfall.ui.common.calmStateFlow
import com.ttmrase.crownfall.ui.common.collectClock
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.event.EventScreen
import com.ttmrase.crownfall.ui.eventMessage
import com.ttmrase.crownfall.ui.feature.FeatureOverlays
import com.ttmrase.crownfall.ui.feature.LocalCastleLevel
import com.ttmrase.crownfall.ui.feature.LocalCastleUpgrade
import com.ttmrase.crownfall.ui.feature.LockedFeatureDialog
import com.ttmrase.crownfall.ui.feature.LockedScreenBody
import com.ttmrase.crownfall.ui.heroes.HeroesScreen
import com.ttmrase.crownfall.ui.heroes.SummonScreen
import com.ttmrase.crownfall.ui.mail.MailScreen
import com.ttmrase.crownfall.ui.map.MapScreen
import com.ttmrase.crownfall.ui.quest.QuestScreen
import com.ttmrase.crownfall.ui.research.ResearchScreen
import com.ttmrase.crownfall.ui.reward.LocalRewardFly
import com.ttmrase.crownfall.ui.reward.RewardFlyLayer
import com.ttmrase.crownfall.ui.reward.RewardFlyState
import com.ttmrase.crownfall.ui.reward.trackTaps
import com.ttmrase.crownfall.ui.screens.StubScreen
import com.ttmrase.crownfall.ui.settings.SettingsScreen
import com.ttmrase.crownfall.ui.shop.FakeBillingSheet
import com.ttmrase.crownfall.ui.shop.OfferPresenter
import com.ttmrase.crownfall.ui.shop.ShopClaimOnly
import com.ttmrase.crownfall.ui.shop.ShopScreen
import com.ttmrase.crownfall.ui.title.TitleScreen
import com.ttmrase.crownfall.ui.vip.VipCinematicHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 単一 Activity 用の NavHost と下部ナビ（Title では非表示）。 */
private const val CLAIM_PERIOD_MS = 30_000L
private const val LOCKED_ALPHA = 0.45f
private const val TRANSITION_MS = 180
private const val SLIDE_DIV = 12

@Composable
fun CrownfallNavHost(
    repository: GameRepository,
    ai: AiService,
    chat: ChatCoordinator,
    sessionCoordinator: SessionCoordinator,
    pendingRoute: MutableStateFlow<String?>,
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
    var billingId by rememberSaveable { mutableStateOf<String?>(null) }
    val hooks: OfferHooks =
        remember(ready?.session) {
            if (ready != null) {
                ShopOfferHooks(
                    session = ready.session,
                    data = ready.data,
                    scope = uiScope,
                    openProduct = { billingId = it },
                    notify = { res -> uiScope.launch { snackbarHostState.showReplacing(context.getString(res)) } },
                    notifyLocked = { lv ->
                        uiScope.launch {
                            snackbarHostState.showReplacing(context.getString(R.string.failure_feature_locked, lv))
                        }
                    },
                )
            } else {
                NoOpOfferHooks
            }
        }
    var vipActive by remember { mutableStateOf(false) }
    var featureActive by remember { mutableStateOf(false) }
    var ceremonyActive by remember { mutableStateOf(false) }
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
    // 購入済みの権利（月パス・VIP チェスト・成長ファンド）が受け取れるか。ショップが未解放でも請求画面へ入れる
    val entitlementFlow =
        remember(ready?.session) {
            ready?.let { r ->
                r.session.calmStateFlow(CLAIM_PERIOD_MS)
                    .map { s -> s != null && Entitlements.claimable(s, r.data) }
                    .distinctUntilChanged()
            } ?: flowOf(false)
        }
    val hasEntitlement by entitlementFlow.collectAsStateWithLifecycle(false)
    val clock = readySession?.collectClock()

    // 段階解放: 城 Lv と、タップで開くロックのダイアログ、城のアップグレードを開く要求
    // null は「まだ読み込み中」（0 と区別する。コールドスタートの直後に一瞬ロック画面が出ないように）
    val castleFlow =
        remember(readySession) {
            readySession?.state?.map { s -> s?.let { FeatureGate.castleLevel(it) } }?.distinctUntilChanged()
                ?: flowOf(null)
        }
    val castleLvOrNull by castleFlow.collectAsStateWithLifecycle(null)
    val castleLv = castleLvOrNull ?: 0
    var lockedFeature by remember { mutableStateOf<Feature?>(null) }
    var castleRequest by rememberSaveable { mutableIntStateOf(0) }

    fun isLocked(feature: Feature?): Boolean =
        feature != null && ready != null && castleLvOrNull != null &&
            !FeatureGate.unlockedAt(ready.data, castleLv, feature)
    val upgradeCastle = {
        castleRequest++
        navController.navigateBottom(BottomDestination.City)
    }

    // 全画面共通のトースト（ヘルプ受領・ギフト・行軍の帰還など）。
    LaunchedEffect(readySession) {
        readySession?.events?.collect { event ->
            val message = eventMessage(context, event) ?: return@collect
            launch { snackbarHostState.showReplacing(message) }
        }
    }

    // 通知のタップで開く画面（準備ができていて、タイトルでなければ開く）
    val route by pendingRoute.collectAsStateWithLifecycle()
    LaunchedEffect(route, ready != null, destination?.route) {
        val target = route
        if (target != null && ready != null && destination != null && destination.route != Screen.Title.route) {
            navController.openPendingRoute(pendingRoute)
        }
    }

    // 不在中の襲撃（未読の結果メール）は起動時に最前面で知らせる
    val raidMailFlow =
        remember(readySession) {
            readySession?.state
                ?.map { s ->
                    s?.mails
                        ?.filter { !it.read && it.battle?.kind == BattleKind.RAID }
                        ?.maxByOrNull { it.id }
                        ?.let { it.id to (it.battle?.attackerWon == false) }
                }
                ?.distinctUntilChanged() ?: flowOf(null)
        }
    val raidMail by raidMailFlow.collectAsStateWithLifecycle(null)
    var raidAlertSeen by rememberSaveable { mutableLongStateOf(0L) }
    val alertId = raidMail?.first
    if (alertId != null && alertId > raidAlertSeen && destination?.route == Screen.City.route) {
        val defended = raidMail?.second == true
        AlertDialog(
            onDismissRequest = { raidAlertSeen = alertId },
            title = { Text(stringResource(R.string.raid_alert_title)) },
            text = {
                Text(stringResource(if (defended) R.string.raid_alert_defended else R.string.raid_alert_defeated))
            },
            confirmButton = {
                TextButton(onClick = {
                    raidAlertSeen = alertId
                    navController.navigate(Screen.Mail.route) { launchSingleTop = true }
                }) { Text(stringResource(R.string.raid_alert_open)) }
            },
            dismissButton = {
                TextButton(onClick = { raidAlertSeen = alertId }) { Text(stringResource(R.string.common_close)) }
            },
        )
    }

    // 「待って！」の待ち（1）のまま何も出ていないときも、戻るは必ず効く（確認へ進む）。オファーが出ている間は
    // ダイアログが先に戻るを受けるので、ここに届くのは出ていないときだけ。
    BackHandler(enabled = exitStage != 2 && destination?.route == Screen.City.route && ready != null) {
        val r = ready
        if (exitStage == 1) {
            exitStage = 2
        } else if (r != null) {
            uiScope.launch {
                r.session.dispatch { Actions.exitOffer(it, r.data) }
                // 自動で出せるオファーがあるときだけ「待って！」を待つ（間隔内・停止中は待たずに確認へ進む）
                val waiting =
                    r.session.state.value?.let { st ->
                        OfferEngine.nextPopup(st, r.data) != null
                    } == true
                exitStage = if (waiting) 1 else 2
            }
        }
    }

    val flyState = remember { RewardFlyState() }
    BgmHost(destination?.route, ready?.session)
    CompositionLocalProvider(
        LocalClock provides clock,
        LocalRewardFly provides flyState,
        LocalCastleUpgrade provides upgradeCastle,
        LocalCastleLevel provides castleLv,
    ) {
        var rootOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { rootOrigin = it.positionInWindow() }
                .trackTaps(flyState, { rootOrigin }) { Sfx.play(context, SfxId.TAP) },
        ) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    if (destination != null && destination.route != Screen.Title.route) {
                        NavigationBar {
                            BottomDestination.entries.forEach { item ->
                                val locked = isLocked(item.feature)
                                NavigationBarItem(
                                    selected = destination.hierarchy.any { it.route == item.screen.route },
                                    onClick = {
                                        if (locked && !(item == BottomDestination.Shop && hasEntitlement)) {
                                            lockedFeature = item.feature
                                        } else {
                                            navController.navigateBottom(item)
                                        }
                                    },
                                    modifier = Modifier.alpha(if (locked) LOCKED_ALPHA else 1f),
                                    icon = {
                                        val isAlliance = item == BottomDestination.Alliance
                                        val isCity = item == BottomDestination.City
                                        val count =
                                            when {
                                                isAlliance -> unread
                                                isCity -> claimable
                                                else -> 0
                                            }
                                        BadgedBox(
                                            badge = {
                                                if (locked) {
                                                    Badge { Text("🔒") }
                                                } else if (count > 0) {
                                                    Badge { Text(count.toString()) }
                                                }
                                            },
                                        ) {
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
                            RaidBanner(it.session, it.data)
                        }
                    }
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Title.route,
                        modifier = Modifier.weight(1f),
                        enterTransition = {
                            fadeIn(tween(TRANSITION_MS)) + slideInHorizontally(tween(TRANSITION_MS)) { it / SLIDE_DIV }
                        },
                        exitTransition = { fadeOut(tween(TRANSITION_MS)) },
                        popEnterTransition = { fadeIn(tween(TRANSITION_MS)) },
                        popExitTransition = {
                            fadeOut(tween(TRANSITION_MS)) +
                                slideOutHorizontally(tween(TRANSITION_MS)) { it / SLIDE_DIV }
                        },
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
                                    onOpenBarracks = {
                                            tab ->
                                        navController.navigate("${Screen.Barracks.route}?tab=$tab")
                                    },
                                    castleRequest = castleRequest,
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
                            Guard(Screen.Barracks, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { ready ->
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
                            Guard(Screen.Research, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { ready ->
                                ResearchScreen(ready.session, ready.data, hooks, snackbarHostState)
                            }
                        }
                        composable(Screen.Heroes.route) {
                            Guard(Screen.Heroes, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { ready ->
                                HeroesScreen(
                                    ready.session,
                                    ready.data,
                                    snackbarHostState,
                                ) { navController.navigate(it.route) }
                            }
                        }
                        composable(Screen.Summon.route) {
                            Guard(Screen.Summon, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { ready ->
                                SummonScreen(ready.session, ready.data, hooks, snackbarHostState)
                            }
                        }
                        composable(Screen.WorldMap.route) {
                            Guard(Screen.WorldMap, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { ready ->
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
                            Guard(Screen.Alliance, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { ready ->
                                AllianceScreen(ready.session, ready.data, snackbarHostState) {
                                    navController.navigate(Screen.Chat.route)
                                }
                            }
                        }
                        composable(Screen.Chat.route) {
                            Guard(Screen.Chat, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { ready ->
                                ChatScreen(
                                    ready.session,
                                    ready.data,
                                    chat.typing,
                                    snackbarHostState,
                                    ai::warmUpOnDevice,
                                )
                            }
                        }
                        composable(Screen.Shop.route) {
                            Guard(Screen.Shop, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { r ->
                                ShopScreen(r.session, r.data, snackbarHostState) { billingId = it }
                            }
                        }
                        composable(Screen.Quests.route) {
                            Guard(Screen.Quests, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { r ->
                                QuestScreen(r.session, r.data, snackbarHostState)
                            }
                        }
                        composable(Screen.Event.route) {
                            Guard(Screen.Event, startup as? Startup.Ready, castleLvOrNull, upgradeCastle) { r ->
                                EventScreen(r.session, r.data)
                            }
                        }
                        composable(Screen.Settings.route) {
                            val r = startup as? Startup.Ready
                            SettingsScreen(ai, r?.session, snackbarHostState, r?.data)
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
            RewardFlyLayer(flyState)
        }
    }

    // オファーのポップアップ、決済シート、退出前の確認（Title では出さない）。
    if (ready != null && destination != null && destination.route != Screen.Title.route) {
        OfferPresenter(
            session = ready.session,
            data = ready.data,
            coordinator = sessionCoordinator,
            busy = billingId != null || vipActive || featureActive || ceremonyActive,
            onBuy = {
                billingId = it
                // 退出オファーを買いに行ったら、タイトルへの確認は出さず城内に残る
                if (exitStage == 1) exitStage = 0
            },
            onIdle = { if (exitStage == 1) exitStage = 2 },
        )
        CompositionLocalProvider(LocalRewardFly provides flyState) {
            VipCinematicHost(ready.session, ready.data, hold = billingId != null, onActive = { vipActive = it })
            CastleCeremonyHost(ready.session, ready.data) { ceremonyActive = it }
            SoundEvents(ready.session)
            AchievementToastHost(ready.session, ready.data, Modifier.fillMaxSize().statusBarsPadding())
            FeatureOverlays(
                session = ready.session,
                data = ready.data,
                target = { f -> BottomDestination.entries.indexOfFirst { it.feature == f }.takeIf { it >= 0 } },
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                onActive = { featureActive = it },
                hold = ceremonyActive,
            )
        }
        lockedFeature?.let { f ->
            LockedFeatureDialog(
                feature = f,
                data = ready.data,
                castleLv = castleLv,
                onUpgrade = {
                    lockedFeature = null
                    castleRequest++
                    navController.navigateBottom(BottomDestination.City)
                },
                onDismiss = { lockedFeature = null },
            )
        }
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

/** 未解放の画面は中身を作らず、ロックの案内を出す（通知などで直接開かれたときも同じ）。 */
@Composable
private fun Guard(
    screen: Screen,
    ready: Startup.Ready?,
    castleLv: Int?,
    onUpgrade: () -> Unit,
    content: @Composable (Startup.Ready) -> Unit,
) {
    if (ready == null || castleLv == null) return
    val feature = screen.requiredFeature()
    if (feature != null && !FeatureGate.unlockedAt(ready.data, castleLv, feature)) {
        Column(Modifier.fillMaxSize()) {
            LockedScreenBody(feature, ready.data, castleLv, onUpgrade, Modifier.weight(1f).fillMaxWidth())
            // 古いセーブで買ってある権利（月パス・VIP チェスト・成長ファンド）は、ショップが未解放でも受け取れる
            if (screen == Screen.Shop) ShopClaimOnly(ready.session, ready.data)
        }
    } else {
        content(ready)
    }
}
