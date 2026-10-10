package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.event.WeeklyEvents
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.session.BuildingSection
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.navigation.Screen
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.remainingUntil
import com.ttmrase.crownfall.ui.event.eventNameRes
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.researchNodeName
import com.ttmrase.crownfall.ui.titleRes
import com.ttmrase.crownfall.ui.troopNameById
import kotlinx.coroutines.launch

/** 開催中の週替わりイベントのバナー（タップでイベント画面）。 */
@Composable
private fun EventBanner(
    s: GameState,
    data: GameData,
    onClick: () -> Unit,
) {
    val def = WeeklyEvents.current(s, data)
    val remaining = remainingUntil(WeeklyEvents.endEpochSec(s), s.nowEpochSec)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onClick) {
            Text(
                stringResource(R.string.event_banner, stringResource(eventNameRes(def))) +
                    " · " + DurationFormat.clock(remaining),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

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
) {
    val state by session.collectCalmState()
    val summary by session.offlineSummary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sheetSlotId by rememberSaveable { mutableStateOf<Int?>(null) }
    var speedupJobId by rememberSaveable { mutableStateOf<Long?>(null) }
    var healOpen by rememberSaveable { mutableStateOf(false) }

    val s = state ?: return

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

    Column(modifier = Modifier.fillMaxSize()) {
        StatusBar(session, data)
        EventBanner(s, data) { onNavigate(Screen.Event) }
        TimerBar(
            session = session,
            data = data,
            highlight = s.tutorialStep == TUTORIAL_SPEEDUP_STEP,
            onTimerClick = { speedupJobId = it.id },
            onLockedClick = hooks::onSecondBuilderLocked,
            onIdleClick = onNavigate,
            onOpenBarracks = onOpenBarracks,
        )
        Box(modifier = Modifier.weight(1f)) {
            BuildingGrid(
                state = s,
                session = session,
                data = data,
                onTileClick = { slot ->
                    sheetSlotId = slot.slotId
                    if (slot.slotId == tutorialTargetSlotId(s)) advanceTutorial(TUTORIAL_TAP_TILE_STEP)
                },
            )
        }
        if (s.tutorialStep < Actions.TUTORIAL_DONE) {
            TutorialBanner(step = s.tutorialStep, onSkip = {
                scope.launch { session.dispatch { Actions.setTutorialStep(it, Actions.TUTORIAL_DONE) } }
            })
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            val claimable = QuestLogic.claimableCount(s, data)
            QuickLinks.forEach { screen ->
                TextButton(onClick = { onNavigate(screen) }) {
                    val count = if (screen == Screen.Quests) claimable else 0
                    BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
                        Text(stringResource(screen.titleRes))
                    }
                }
            }
        }
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
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    stringResource(R.string.power_label) + "  " +
                        ShortNumber.format(PowerCalculator.total(state, data)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(R.string.res_gems) + "  " + ShortNumber.format(state.gems),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                ResourceType.entries.forEach { type ->
                    val full = cap > 0 && state.resources[type] >= cap
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(type.nameRes()), style = MaterialTheme.typography.labelSmall)
                        Text(
                            ShortNumber.format(state.resources[type]),
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                if (full) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimerBar(
    session: GameSession,
    data: GameData,
    highlight: Boolean,
    onTimerClick: (TimerJob) -> Unit,
    onLockedClick: () -> Unit,
    onIdleClick: (Screen) -> Unit,
    onOpenBarracks: (Int) -> Unit,
) {
    val context = LocalContext.current
    val live by session.state.collectAsStateWithLifecycle()
    val state = live ?: return
    val builds = state.timers.filter { it.kind == TimerKind.BUILD }.sortedBy { it.endEpochSec }
    val capacity = Simulation.buildQueueCapacity(state, data)
    val firstJob = builds.getOrNull(0)
    val secondJob = builds.getOrNull(1)
    val secondLocked = capacity < 2 && secondJob == null
    val research = UiQueries.researchJob(state)
    val heal = UiQueries.healJob(state)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TimerCell(
                label = stringResource(R.string.timers_build1),
                job = firstJob,
                jobName = buildJobName(context, state, firstJob),
                session = session,
                highlight = highlight && firstJob != null,
                modifier = Modifier.weight(1f),
            ) { firstJob?.let(onTimerClick) ?: Unit }
            TimerCell(
                label = stringResource(R.string.timers_build2),
                job = secondJob,
                jobName = buildJobName(context, state, secondJob),
                session = session,
                modifier = Modifier.weight(1f),
                locked = secondLocked,
            ) { if (secondLocked) onLockedClick() else secondJob?.let(onTimerClick) ?: Unit }
            TimerCell(
                label = stringResource(R.string.timers_research),
                job = research,
                jobName = research?.researchId?.let { researchNodeName(context, it) },
                session = session,
                modifier = Modifier.weight(1f),
            ) { research?.let(onTimerClick) ?: onIdleClick(Screen.Research) }
            TimerCell(
                label = stringResource(R.string.timers_heal),
                job = heal,
                jobName = if (heal != null) stringResource(R.string.timer_heal_job) else null,
                session = session,
                modifier = Modifier.weight(1f),
            ) { heal?.let(onTimerClick) ?: Unit }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TroopType.entries.forEachIndexed { index, type ->
                val slot = state.slots.filter { it.type == type.trainingBuilding }.maxByOrNull { it.level }
                val job = slot?.let { UiQueries.trainJob(state, it.slotId) }
                TimerCell(
                    label = stringResource(R.string.timers_train_slot, stringResource(type.nameRes())),
                    job = job,
                    jobName = job?.troopId?.let { troopNameById(context, it) },
                    session = session,
                    modifier = Modifier.weight(1f),
                    dim = (slot?.level ?: 0) == 0,
                ) { job?.let(onTimerClick) ?: onOpenBarracks(index) }
            }
        }
    }
}

private fun buildJobName(
    context: android.content.Context,
    state: GameState,
    job: TimerJob?,
): String? {
    val type = job?.slotId?.let { id -> state.slots.firstOrNull { it.slotId == id }?.type } ?: return null
    return context.getString(type.nameRes())
}

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

@Composable
private fun TimerCell(
    label: String,
    job: TimerJob?,
    jobName: String?,
    session: GameSession,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    locked: Boolean = false,
    dim: Boolean = false,
    onClick: () -> Unit,
) {
    TutorialHighlight(active = highlight, modifier = modifier) {
        Card(
            modifier = Modifier.fillMaxWidth().alpha(if (dim) 0.45f else 1f).clickable(onClick = onClick),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                when {
                    locked -> Text("🔒", style = MaterialTheme.typography.titleMedium)
                    job == null ->
                        Text(
                            stringResource(R.string.timer_idle),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    else -> {
                        if (jobName != null) {
                            Text(jobName, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        JobTicker(
                            session = session,
                            job = job,
                            barModifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            textStyle = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuildingGrid(
    state: GameState,
    session: GameSession,
    data: GameData,
    onTileClick: (BuildingSlot) -> Unit,
) {
    val grouped =
        BuildingSection.entries.map { section ->
            section to state.slots.filter { UiQueries.sectionOf(it.type) == section }
        }
    val jobBySlot = state.timers.filter { it.kind == TimerKind.BUILD }.associateBy { it.slotId }
    val tutorialTarget = tutorialTargetSlotId(state)
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        grouped.forEach { (section, slots) ->
            item(key = "header_${section.name}", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(section.titleRes()),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            items(slots, key = { it.slotId }) { slot ->
                BuildingTile(
                    slot = slot,
                    job = jobBySlot[slot.slotId],
                    session = session,
                    highlight = slot.slotId == tutorialTarget,
                    onClick = { onTileClick(slot) },
                )
            }
        }
    }
}

@Composable
private fun BuildingTile(
    slot: BuildingSlot,
    job: TimerJob?,
    session: GameSession,
    highlight: Boolean,
    onClick: () -> Unit,
) {
    val dim = slot.level == 0 && job == null
    TutorialHighlight(active = highlight) {
        Card(
            modifier = Modifier.fillMaxWidth().alpha(if (dim) 0.55f else 1f).clickable(onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            border = if (job != null) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BuildingIcon(slot.type)
                Text(
                    stringResource(slot.type.nameRes()),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
                Text(
                    if (slot.level == 0) {
                        stringResource(
                            R.string.tile_build,
                        )
                    } else {
                        stringResource(R.string.tile_level, slot.level)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (slot.level == 0) Color.Gray else MaterialTheme.colorScheme.primary,
                )
                if (job != null) {
                    JobTicker(
                        session = session,
                        job = job,
                        barModifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        textStyle = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
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
