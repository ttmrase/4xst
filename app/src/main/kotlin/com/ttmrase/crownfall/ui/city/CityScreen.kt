package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.event.WeeklyEvents
import com.ttmrase.crownfall.core.feature.Feature
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.navigation.Screen
import com.ttmrase.crownfall.navigation.requiredFeature
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.common.LocalClock
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.remainingUntil
import com.ttmrase.crownfall.ui.event.eventNameRes
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.feature.LockedFeatureDialog
import com.ttmrase.crownfall.ui.feature.NextUnlockChip
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.territory.TerritoryView
import com.ttmrase.crownfall.ui.theme.AnimatedNumber
import com.ttmrase.crownfall.ui.theme.GoldPanel
import com.ttmrase.crownfall.ui.theme.GoldText
import com.ttmrase.crownfall.ui.titleRes
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
                            is ActionResult.Success -> advanceTutorial(TUTORIAL_PRESS_BUILD_STEP)
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
    GoldPanel(modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), alpha = HUD_ALPHA) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                GoldText(
                    stringResource(R.string.power_label) + "  " +
                        ShortNumber.format(PowerCalculator.total(state, data)),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.res_gems) + "  ", style = MaterialTheme.typography.titleSmall)
                    AnimatedNumber(state.gems, style = MaterialTheme.typography.titleSmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                ResourceType.entries.forEach { type ->
                    val full = cap > 0 && state.resources[type] >= cap
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(type.nameRes()), style = MaterialTheme.typography.labelSmall)
                        AnimatedNumber(state.resources[type], color = MaterialTheme.colorScheme.onSurface)
                        // 満杯は数字を明るいままにして、下線を赤にする（暗い背景で赤文字は読めない）
                        Box(
                            Modifier.height(2.dp).fillMaxWidth().background(if (full) FULL_RED else Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

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
            "🔨",
            firstJob,
            session,
            Modifier.weight(1f),
            highlight = highlight && firstJob != null,
        ) {
            firstJob?.let(onTimerClick) ?: Unit
        }
        QueueChip(
            stringResource(R.string.timers_build2),
            "🔨",
            secondJob,
            session,
            Modifier.weight(1f),
            locked = secondLocked,
        ) {
            if (secondLocked) onLockedClick() else secondJob?.let(onTimerClick) ?: Unit
        }
        QueueChip(stringResource(R.string.timers_research), "📖", research, session, Modifier.weight(1f)) {
            research?.let(onTimerClick) ?: onIdleClick(Screen.Research)
        }
        QueueChip(stringResource(R.string.timers_heal), "💊", heal, session, Modifier.weight(1f)) {
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
    glyph: String,
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
                    .alpha(if (dim) 0.45f else 1f)
                    .background(Color(0xCC121A2E), RoundedCornerShape(8.dp))
                    .border(
                        if (job != null) 1.5.dp else 0.5.dp,
                        if (job != null) QUEUE_GOLD else Color(0x66FFFFFF),
                        RoundedCornerShape(8.dp),
                    )
                    .clickable(onClick = onClick)
                    .semantics { contentDescription = label }
                    .padding(vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (locked) "🔒" else glyph, fontSize = 14.sp)
            if (job != null) {
                JobTicker(
                    session = session,
                    job = job,
                    barModifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp).height(2.dp),
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(" ", fontSize = 9.sp)
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
        Text(
            stringResource(R.string.event_banner, stringResource(eventNameRes(def))) +
                " · " + DurationFormat.clock(remaining),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFFE9A0),
            modifier =
                Modifier
                    .background(Color(0xAA121A2E), RoundedCornerShape(50))
                    .clickable(onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

private const val QUICK_LOCKED_ALPHA = 0.5f

private val QUICK_ICONS =
    mapOf(
        Screen.Mail to "✉",
        Screen.Quests to "📜",
        Screen.Research to "🔬",
        Screen.Barracks to "⚔",
        Screen.Chat to "💬",
        Screen.Settings to "⚙",
    )

/** クイックリンクは右端の縦のアイコン列にする（地図を覆わない）。 */
@Composable
private fun QuickLinkColumn(
    claimable: Int,
    locked: List<Screen>,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickLinks.forEach { screen ->
            val count = if (screen == Screen.Quests) claimable else 0
            val name = stringResource(screen.titleRes)
            BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .background(Color(0xCC121A2E), CircleShape)
                            .border(1.dp, QUEUE_GOLD, CircleShape)
                            .alpha(if (screen in locked) QUICK_LOCKED_ALPHA else 1f)
                            .clickable { onNavigate(screen) }
                            .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (screen in locked) "🔒" else QUICK_ICONS[screen] ?: "•", fontSize = 18.sp)
                }
            }
        }
    }
}

/** HUD の背に敷く、上から透ける暗い帯（地図の上でも文字が読める）。 */
private fun Modifier.hudScrim(): Modifier =
    this.background(Brush.verticalGradient(listOf(Color(0xCC0B1020), Color(0x000B1020))))

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

private const val HUD_ALPHA = 0.8f
private val FULL_RED = Color(0xFFE5484D)
private val QUEUE_GOLD = Color(0xFFC9A227)

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
    LinearProgressIndicator(progress = { UiQueries.progress(state, job) }, modifier = barModifier)
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
                OutlinedButton(onClick = { onGo { onOpenBarracks(trainTab) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sheet_go_barracks))
                }
            type == BuildingType.ACADEMY ->
                OutlinedButton(onClick = { onGo { onNavigate(Screen.Research) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sheet_go_research))
                }
            type == BuildingType.TAVERN ->
                OutlinedButton(onClick = { onGo { onNavigate(Screen.Summon) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sheet_go_summon))
                }
            type == BuildingType.HOSPITAL && hasWounded ->
                OutlinedButton(onClick = onHeal, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.heal_open))
                }
        }
    }
}
