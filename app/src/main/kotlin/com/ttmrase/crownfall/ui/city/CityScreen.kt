package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.bubble.HarvestBubble
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.SkinCategory
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.event.WeeklyEvents
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.skin.Skins
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.navigation.Screen
import com.ttmrase.crownfall.navigation.requiredFeature
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.common.LocalClock
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.remainingUntil
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.event.eventNameRes
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.feature.LockedFeatureDialog
import com.ttmrase.crownfall.ui.feature.NextUnlockChip
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.CountBadge
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GameOutlinedButton
import com.ttmrase.crownfall.ui.kit.GameProgressBar
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.GlyphMedallion
import com.ttmrase.crownfall.ui.kit.Pill
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.reward.FlyTarget
import com.ttmrase.crownfall.ui.reward.LocalRewardFly
import com.ttmrase.crownfall.ui.reward.flyTarget
import com.ttmrase.crownfall.ui.territory.TerritoryView
import com.ttmrase.crownfall.ui.theme.AnimatedNumber
import com.ttmrase.crownfall.ui.theme.CrownFonts
import com.ttmrase.crownfall.ui.theme.GoldText
import com.ttmrase.crownfall.ui.titleRes
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val QuickLinks =
    listOf(Screen.Mail, Screen.Quests, Screen.Research, Screen.Barracks, Screen.Chat, Screen.Settings)

/** 城内画面（T2.2）。上部バー・タイマーバー・建物グリッド・詳細シート・加速ダイアログ・不在サマリー・チュートリアル。 */
@Composable
fun CityScreen(
    session: GameSession,
    data: GameData,
    hooks: OfferHooks,
    snackbarHostState: SnackbarHostState,
    onNavigate: (Screen) -> Unit,
    onOpenBarracks: (Int) -> Unit,
    castleRequest: Int = 0,
) {
    val state by session.collectCalmState()
    val summary by session.offlineSummary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sheetSlotId by rememberSaveable { mutableStateOf<Int?>(null) }
    var speedupJobId by rememberSaveable { mutableStateOf<Long?>(null) }
    var healOpen by rememberSaveable { mutableStateOf(false) }
    var lockedFeature by remember { mutableStateOf<Feature?>(null) }
    var handledCastleRequest by rememberSaveable { mutableIntStateOf(0) }

    val s = state ?: return

    fun openCastleSheet() {
        s.slots.firstOrNull { it.type == BuildingType.CASTLE }?.let { sheetSlotId = it.slotId }
    }
    // 他の画面の「城をアップグレード」から来たら、城の詳細シートを開く
    LaunchedEffect(castleRequest) {
        if (castleRequest > handledCastleRequest) {
            handledCastleRequest = castleRequest
            openCastleSheet()
        }
    }

    fun showFailure(reason: ActionFailure) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(failureMessage(context, reason))
        }
    }

    fun advanceTutorial(from: Int) {
        scope.launch {
            if (session.state.value?.tutorialStep == from) {
                session.dispatch { Actions.setTutorialStep(it, from + 1) }
            }
        }
    }

    val secondBuilderLocked = s.secondBuilderUntilEpochSec <= s.nowEpochSec
    val locks = remember(s.slots) { UiQueries.lockedSlots(s, data) }

    val upgradable = remember(s.slots, s.resources, s.timers) { upgradableSlots(s, data) }
    // 収穫バブル: 出ているかは「最後に回収した時刻」と毎秒の時計から導く（状態には持たない）
    val clock = LocalClock.current
    val fly = LocalRewardFly.current
    val stamps by remember(session) {
        session.state.map { it?.lastBubbleCollect }.filterNotNull().distinctUntilChanged()
    }.collectAsStateWithLifecycle(s.lastBubbleCollect)
    val bubbleKinds by remember(s.slots, stamps) {
        derivedStateOf {
            val view = s.copy(lastBubbleCollect = stamps)
            HarvestBubble.ready(view, data, clock?.value ?: s.nowEpochSec)
                .associateWith { id -> s.slots.first { it.slotId == id }.type.producedResource!! }
        }
    }
    // 領地ビューを全画面の背景にし、HUD（資源・キュー・イベント・クイックリンク）は上に重ねる
    Box(modifier = Modifier.fillMaxSize()) {
        TerritoryView(
            state = s,
            clock = LocalClock.current,
            locks = locks,
            upgradable = upgradable,
            events = session.events,
            live = session.state,
            tutorialSlotId = tutorialTargetSlotId(s),
            bubbles = bubbleKinds,
            castleSkin = Skins.equipped(s, data)[SkinCategory.CASTLE]?.theme,
            territorySeason = Skins.equipped(s, data)[SkinCategory.TERRITORY]?.theme,
            onBubbleTap = { slotId, pos ->
                scope.launch {
                    val before = session.state.value
                    val r = session.dispatch { Actions.collectBubble(it, slotId, data) }
                    val after = session.state.value
                    if (r is ActionResult.Success && before != null && after != null) {
                        Sfx.play(context, SfxId.BUTTON)
                        // 倉庫に入った分だけ飛ばす（一部しか入らないときに、満額のアイコンを飛ばさない）
                        val gained =
                            ResourceType.entries.fold(Resources.ZERO) { acc, t ->
                                acc.with(t, (after.resources[t] - before.resources[t]).coerceAtLeast(0))
                            }
                        fly.flyAll(pos, gained)
                    } else if (r is ActionResult.Failure && r.reason == ActionFailure.NothingToClaim) {
                        // 倉庫がいっぱいで 1 つも入らない
                        snackbarHostState.showReplacing(context.getString(R.string.bubble_storage_full))
                    }
                }
            },
            onTileClick = { slot ->
                sheetSlotId = slot.slotId
                if (slot.slotId == tutorialTargetSlotId(s)) advanceTutorial(TUTORIAL_TAP_TILE_STEP)
            },
        )
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).hudScrim()) {
            StatusBar(session, data)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QueueRow(
                    session = session,
                    data = data,
                    highlight = s.tutorialStep == TUTORIAL_SPEEDUP_STEP,
                    onTimerClick = { speedupJobId = it.id },
                    onLockedClick = hooks::onSecondBuilderLocked,
                    onIdleClick = onNavigate,
                    onOpenBarracks = onOpenBarracks,
                    modifier = Modifier.weight(1f),
                )
            }
            if (FeatureGate.unlocked(s, data, Feature.EVENTS)) EventChip(s, data) { onNavigate(Screen.Event) }
            NextUnlockChip(s, data, onClick = ::openCastleSheet)
        }
        QuickLinkColumn(
            claimable = QuestLogic.claimableCount(s, data),
            locked =
                QuickLinks.filter {
                        sc ->
                    sc.requiredFeature()?.let { !FeatureGate.unlocked(s, data, it) } == true
                },
            onNavigate = { sc ->
                val need = sc.requiredFeature()
                if (need != null && !FeatureGate.unlocked(s, data, need)) lockedFeature = need else onNavigate(sc)
            },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
        )
        if (s.tutorialStep < Actions.TUTORIAL_DONE) {
            TutorialBanner(
                step = s.tutorialStep,
                onSkip = { scope.launch { session.dispatch { Actions.setTutorialStep(it, Actions.TUTORIAL_DONE) } } },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    lockedFeature?.let { f ->
        LockedFeatureDialog(
            feature = f,
            data = data,
            castleLv = FeatureGate.castleLevel(s),
            onUpgrade = {
                lockedFeature = null
                openCastleSheet()
            },
            onDismiss = { lockedFeature = null },
        )
    }

    val slotId = sheetSlotId
    if (slotId != null) {
        val preview = UiQueries.upgradePreview(s, slotId, data)
        if (preview == null) {
            sheetSlotId = null
        } else {
            UpgradeSheet(
                state = s,
                preview = preview,
                secondBuilderLocked = secondBuilderLocked,
                tutorialBuildStep = s.tutorialStep == TUTORIAL_PRESS_BUILD_STEP,
                onUpgrade = {
                    scope.launch {
                        when (val r = session.dispatch { Actions.startUpgrade(it, slotId, data) }) {
                            is ActionResult.Success -> {
                                Sfx.play(context, SfxId.BUILD_START)
                                advanceTutorial(TUTORIAL_PRESS_BUILD_STEP)
                            }
                            is ActionResult.Failure -> showFailure(r.reason)
                        }
                    }
                },
                onSpeedup = {
                    sheetSlotId = null
                    speedupJobId = it
                },
                onResourceShortage = hooks::onResourceShortage,
                onUnlockBuilder = hooks::onSecondBuilderLocked,
                onDismiss = { sheetSlotId = null },
                extraActions = {
                    BuildingExtraActions(
                        type = preview.slot.type,
                        hasWounded = s.wounded.isNotEmpty(),
                        onGo = {
                            sheetSlotId = null
                            it()
                        },
                        onOpenBarracks = onOpenBarracks,
                        onNavigate = onNavigate,
                        onHeal = { healOpen = true },
                    )
                },
            )
        }
    }

    val jobId = speedupJobId
    if (jobId != null) {
        SpeedupHost(
            session = session,
            data = data,
            hooks = hooks,
            snackbarHostState = snackbarHostState,
            jobId = jobId,
            onSuccess = { advanceTutorial(TUTORIAL_SPEEDUP_STEP) },
            onDismiss = { speedupJobId = null },
        )
    }

    if (healOpen) {
        HealDialog(
            state = s,
            data = data,
            onHeal = { batch ->
                scope.launch {
                    when (val r = session.dispatch { Actions.startHeal(it, batch, data) }) {
                        is ActionResult.Success -> healOpen = false
                        is ActionResult.Failure -> {
                            val reason = r.reason
                            if (reason is ActionFailure.NotEnoughResources) hooks.onResourceShortage(reason.missing)
                            showFailure(reason)
                        }
                    }
                }
            },
            onDismiss = { healOpen = false },
        )
    }

    summary?.let {
        OfflineSummaryDialog(summary = it, state = s, onDismiss = session::consumeOfflineSummary)
    }
}

/** チュートリアル step0 で指す未建設の Farm スロット。該当しなければ null。 */
private fun tutorialTargetSlotId(state: GameState): Int? =
    if (state.tutorialStep == TUTORIAL_TAP_TILE_STEP) {
        state.slots.firstOrNull { it.type.producedResource == ResourceType.FOOD && it.level == 0 }?.slotId
    } else {
        null
    }

private const val TUTORIAL_TAP_TILE_STEP = 0
private const val TUTORIAL_PRESS_BUILD_STEP = 1
private const val TUTORIAL_SPEEDUP_STEP = 2

@Composable
private fun StatusBar(
    session: GameSession,
    data: GameData,
) {
    val live by session.state.collectAsStateWithLifecycle()
    val state = live ?: return
    val cap = Simulation.storageCap(state, data)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 1 段目: 戦力の銘板（左）とジェム（右）
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier =
                    Modifier
                        .gameFrame(FrameStyle.Panel, corner = 20.dp, alpha = HUD_ALPHA)
                        .padding(start = 4.dp, end = 14.dp, top = 3.dp, bottom = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.POWER, size = 30.dp)
                Column {
                    Text(
                        stringResource(R.string.power_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = Cf.Muted,
                        lineHeight = 11.sp,
                        fontSize = 10.sp,
                    )
                    GoldText(
                        ShortNumber.format(PowerCalculator.total(state, data)),
                        style =
                            MaterialTheme.typography.titleMedium.copy(
                                fontFamily = CrownFonts.Numeric,
                                fontSize = 18.sp,
                                lineHeight = 20.sp,
                                shadow = Shadow(Cf.Ink, Offset(0f, 2f), 2f),
                            ),
                    )
                }
            }
            Box(Modifier.weight(1f))
            Row(
                modifier =
                    Modifier
                        .flyTarget(FlyTarget.GEMS)
                        .gameFrame(FrameStyle.Royal, corner = 18.dp, alpha = HUD_ALPHA)
                        .semantics(mergeDescendants = true) {}
                        .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.GEM, size = 24.dp)
                val gemsLabel = stringResource(R.string.res_gems)
                AnimatedNumber(
                    state.gems,
                    modifier = Modifier.semantics { contentDescription = gemsLabel },
                    style = HudNumber.copy(fontSize = 16.sp),
                    color = Color.White,
                )
            }
        }
        // 2 段目: 資源 4 種（アイコン + 数。満杯は赤い札）
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .gameFrame(FrameStyle.Panel, corner = 12.dp, alpha = HUD_ALPHA)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            ResourceType.entries.forEach { type ->
                val full = cap > 0 && state.resources[type] >= cap
                val name = stringResource(type.nameRes())
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .flyTarget(FlyTarget.of(type))
                            .padding(horizontal = 2.dp)
                            .gameFrame(FrameStyle.Inset, corner = 9.dp, accent = if (full) FULL_RED else null)
                            .semantics(mergeDescendants = true) { contentDescription = name }
                            .padding(start = 3.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(type.glyph(), size = 22.dp)
                    AnimatedNumber(
                        state.resources[type],
                        modifier = Modifier.weight(1f),
                        style = HudNumber.copy(textAlign = TextAlign.End),
                        color = if (full) Color(0xFFFFB3A8) else Cf.Ivory,
                    )
                }
            }
        }
    }
}

private val HudNumber =
    TextStyle(
        fontFamily = CrownFonts.Numeric,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        shadow = Shadow(Color(0xFF04060D), Offset(0f, 1.5f), 2f),
    )

/** キュー 8 本（建設 2・研究・治療・訓練 4）を、小さなアイコンの横 1 列にする。稼働中だけ残り時間を出す。 */
@Composable
private fun QueueRow(
    session: GameSession,
    data: GameData,
    highlight: Boolean,
    onTimerClick: (TimerJob) -> Unit,
    onLockedClick: () -> Unit,
    onIdleClick: (Screen) -> Unit,
    onOpenBarracks: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live by session.state.collectAsStateWithLifecycle()
    val state = live ?: return
    val builds = state.timers.filter { it.kind == TimerKind.BUILD }.sortedBy { it.endEpochSec }
    val capacity = Simulation.buildQueueCapacity(state, data)
    val firstJob = builds.getOrNull(0)
    val secondJob = builds.getOrNull(1)
    val secondLocked = capacity < 2 && secondJob == null
    val research = UiQueries.researchJob(state)
    val heal = UiQueries.healJob(state)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        QueueChip(
            stringResource(R.string.timers_build1),
            Glyph.BUILD,
            firstJob,
            session,
            Modifier.weight(1f),
            highlight = highlight && firstJob != null,
        ) {
            firstJob?.let(onTimerClick) ?: Unit
        }
        QueueChip(
            stringResource(R.string.timers_build2),
            Glyph.BUILD,
            secondJob,
            session,
            Modifier.weight(1f),
            locked = secondLocked,
        ) {
            if (secondLocked) onLockedClick() else secondJob?.let(onTimerClick) ?: Unit
        }
        QueueChip(stringResource(R.string.timers_research), Glyph.RESEARCH, research, session, Modifier.weight(1f)) {
            research?.let(onTimerClick) ?: onIdleClick(Screen.Research)
        }
        QueueChip(stringResource(R.string.timers_heal), Glyph.HEAL, heal, session, Modifier.weight(1f)) {
            heal?.let(onTimerClick) ?: Unit
        }
        TroopType.entries.forEachIndexed { index, type ->
            val slot = state.slots.filter { it.type == type.trainingBuilding }.maxByOrNull { it.level }
            val job = slot?.let { UiQueries.trainJob(state, it.slotId) }
            QueueChip(
                label = stringResource(R.string.timers_train_slot, stringResource(type.nameRes())),
                glyph = type.glyph(),
                job = job,
                session = session,
                modifier = Modifier.weight(1f),
                dim = (slot?.level ?: 0) == 0,
            ) { job?.let(onTimerClick) ?: onOpenBarracks(index) }
        }
    }
}

/** タイマー 1 本の小さなチップ。稼働中は金の枠と残り時間。毎秒ここだけが再構図される。 */
@Composable
private fun QueueChip(
    label: String,
    glyph: Glyph,
    job: TimerJob?,
    session: GameSession,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    locked: Boolean = false,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    TutorialHighlight(active = highlight, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .gameFrame(
                        if (job != null) FrameStyle.Panel else FrameStyle.Inset,
                        corner = 10.dp,
                        alpha = QUEUE_ALPHA,
                    )
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = label }
                    .padding(top = 3.dp, bottom = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.Center) {
                GlyphIcon(
                    glyph,
                    size = 24.dp,
                    dimmed = dim || locked,
                    alpha = if (dim || locked) QUEUE_DIM_ALPHA else 1f,
                )
                if (locked) {
                    GlyphIcon(
                        Glyph.LOCK,
                        Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 3.dp),
                        size = 15.dp,
                    )
                }
            }
            if (job != null) {
                JobTicker(
                    session = session,
                    job = job,
                    barModifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(3.dp),
                    textStyle = HudNumber.copy(fontSize = 9.sp, lineHeight = 11.sp, color = Cf.GoldHi),
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    if (locked || dim) " " else stringResource(R.string.queue_idle),
                    fontSize = 9.sp,
                    lineHeight = 14.sp,
                    color = Cf.EmeraldHi,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 開催中のイベントを小さなチップにする（タップでイベント画面）。 */
@Composable
private fun EventChip(
    s: GameState,
    data: GameData,
    onClick: () -> Unit,
) {
    val def = WeeklyEvents.current(s, data)
    val remaining = remainingUntil(WeeklyEvents.endEpochSec(s), s.nowEpochSec)
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
        Pill(glyph = Glyph.TROPHY, onClick = onClick) {
            Text(
                stringResource(R.string.event_banner, stringResource(eventNameRes(def))) +
                    " · " + DurationFormat.clock(remaining),
            )
        }
    }
}

private val QUICK_ICONS =
    mapOf(
        Screen.Mail to Glyph.MAIL,
        Screen.Quests to Glyph.QUEST,
        Screen.Research to Glyph.RESEARCH,
        Screen.Barracks to Glyph.BARRACKS,
        Screen.Chat to Glyph.CHAT,
        Screen.Settings to Glyph.SETTINGS,
    )

/** クイックリンクは右端の縦のアイコン列にする（地図を覆わない）。 */
@Composable
private fun QuickLinkColumn(
    claimable: Int,
    locked: List<Screen>,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickLinks.forEach { screen ->
            val count = if (screen == Screen.Quests) claimable else 0
            val name = stringResource(screen.titleRes)
            val isLocked = screen in locked
            Box {
                GlyphMedallion(
                    glyph = QUICK_ICONS[screen] ?: Glyph.INFO,
                    onClick = { onNavigate(screen) },
                    modifier = Modifier.semantics { contentDescription = name },
                    size = 44.dp,
                    locked = isLocked,
                ) {
                    if (isLocked) GlyphIcon(Glyph.LOCK, Modifier.align(Alignment.BottomEnd), size = 17.dp)
                }
                if (count > 0) CountBadge(count, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
            }
        }
    }
}

/** HUD の背に敷く、上から透ける暗い帯（地図の上でも文字が読める）。 */
private fun Modifier.hudScrim(): Modifier =
    this.background(Brush.verticalGradient(listOf(Color(0xE6060A16), Color(0x99060A16), Color(0x00060A16))))

/** 今すぐアップグレードを始められる区画（資源・前提・キューが足りている）。小さな印を出す対象。 */
private fun upgradableSlots(
    s: GameState,
    data: GameData,
): Set<Int> =
    s.slots.filter { it.level > 0 }.mapNotNull { slot ->
        UiQueries.upgradePreview(s, slot.slotId, data)?.takeIf {
            it.nextLevel != null && it.blocker == null
        }?.let { slot.slotId }
    }.toSet()

private const val HUD_ALPHA = 0.92f
private const val QUEUE_ALPHA = 0.9f
private const val QUEUE_DIM_ALPHA = 0.55f
private val FULL_RED = Color(0xFFE5484D)

/** タイマー 1 本の進捗バーと残り時間。毎秒ここだけが再構図される。 */
@Composable
private fun JobTicker(
    session: GameSession,
    job: TimerJob,
    barModifier: Modifier,
    textStyle: androidx.compose.ui.text.TextStyle,
    textAlign: TextAlign? = null,
) {
    val live by session.state.collectAsStateWithLifecycle()
    val state = live ?: return
    GameProgressBar(progress = { UiQueries.progress(state, job) }, modifier = barModifier)
    Text(DurationFormat.clock(UiQueries.remainingSec(state, job)), style = textStyle, textAlign = textAlign)
}

/** 建物詳細シートの下に出す、建物種ごとの導線（訓練・研究・召喚・治療）。 */
@Composable
private fun BuildingExtraActions(
    type: BuildingType,
    hasWounded: Boolean,
    onGo: (() -> Unit) -> Unit,
    onOpenBarracks: (Int) -> Unit,
    onNavigate: (Screen) -> Unit,
    onHeal: () -> Unit,
) {
    val trainTab = TroopType.entries.indexOfFirst { it.trainingBuilding == type }
    Column(modifier = Modifier.padding(bottom = 16.dp)) {
        when {
            trainTab >= 0 ->
                GameOutlinedButton(
                    onClick = { onGo { onOpenBarracks(trainTab) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sheet_go_barracks))
                }
            type == BuildingType.ACADEMY ->
                GameOutlinedButton(
                    onClick = { onGo { onNavigate(Screen.Research) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sheet_go_research))
                }
            type == BuildingType.TAVERN ->
                GameOutlinedButton(
                    onClick = { onGo { onNavigate(Screen.Summon) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sheet_go_summon))
                }
            type == BuildingType.HOSPITAL && hasWounded ->
                GameOutlinedButton(onClick = onHeal, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.heal_open))
                }
        }
    }
}
