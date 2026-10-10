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
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
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
import com.ttmrase.crownfall.ui.eventMessage
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.nameRes
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
) {
    val state by session.state.collectAsStateWithLifecycle()
    val summary by session.offlineSummary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sheetSlotId by rememberSaveable { mutableStateOf<Int?>(null) }
    var speedupJobId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(session) {
        session.events.collect { event ->
            val message = eventMessage(context, event) ?: return@collect
            snackbarHostState.currentSnackbarData?.dismiss()
            launch { snackbarHostState.showSnackbar(message) }
        }
    }

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
        StatusBar(s, data)
        TimerBar(
            state = s,
            data = data,
            highlight = s.tutorialStep == TUTORIAL_SPEEDUP_STEP,
            onTimerClick = { speedupJobId = it.id },
            onLockedClick = hooks::onSecondBuilderLocked,
        )
        Box(modifier = Modifier.weight(1f)) {
            BuildingGrid(
                state = s,
                data = data,
                onTileClick = { slot ->
                    sheetSlotId = slot.slotId
                    if (s.tutorialStep == TUTORIAL_TAP_TILE_STEP) advanceTutorial(TUTORIAL_TAP_TILE_STEP)
                },
            )
        }
        if (s.tutorialStep < Actions.TUTORIAL_DONE) {
            TutorialBanner(step = s.tutorialStep, onSkip = {
                scope.launch { session.dispatch { Actions.setTutorialStep(it, Actions.TUTORIAL_DONE) } }
            })
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            QuickLinks.forEach { screen ->
                TextButton(onClick = { onNavigate(screen) }) { Text(stringResource(screen.titleRes)) }
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
            )
        }
    }

    val jobId = speedupJobId
    if (jobId != null) {
        SpeedupDialog(
            state = s,
            data = data,
            jobId = jobId,
            onUseItem = { itemId, count ->
                scope.launch {
                    when (val r = session.dispatch { Actions.applySpeedupItem(it, jobId, itemId, count, data) }) {
                        is ActionResult.Success -> advanceTutorial(TUTORIAL_SPEEDUP_STEP)
                        is ActionResult.Failure -> showFailure(r.reason)
                    }
                }
            },
            onGemSkip = {
                scope.launch {
                    when (val r = session.dispatch { Actions.gemSkip(it, jobId, data) }) {
                        is ActionResult.Success -> advanceTutorial(TUTORIAL_SPEEDUP_STEP)
                        is ActionResult.Failure -> {
                            val reason = r.reason
                            if (reason is ActionFailure.NotEnoughGems) hooks.onGemShortage(reason.missing)
                            showFailure(reason)
                        }
                    }
                }
            },
            onDismiss = { speedupJobId = null },
        )
    }

    summary?.let {
        OfflineSummaryDialog(summary = it, state = s, onDismiss = session::consumeOfflineSummary)
    }
}

private const val TUTORIAL_TAP_TILE_STEP = 0
private const val TUTORIAL_PRESS_BUILD_STEP = 1
private const val TUTORIAL_SPEEDUP_STEP = 2

@Composable
private fun StatusBar(
    state: GameState,
    data: GameData,
) {
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
    state: GameState,
    data: GameData,
    highlight: Boolean,
    onTimerClick: (TimerJob) -> Unit,
    onLockedClick: () -> Unit,
) {
    val builds = state.timers.filter { it.kind == TimerKind.BUILD }.sortedBy { it.endEpochSec }
    val capacity = Simulation.buildQueueCapacity(state, data)
    val firstJob = builds.getOrNull(0)
    val secondJob = builds.getOrNull(1)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TimerCell(
            R.string.timers_build1,
            firstJob,
            state,
            state.slots,
            highlight && firstJob != null,
            Modifier.weight(1f),
        ) {
            firstJob?.let(onTimerClick)
        }
        val secondLocked = capacity < 2 && secondJob == null
        TimerCell(
            labelRes = R.string.timers_build2,
            job = secondJob,
            state = state,
            slots = state.slots,
            highlight = false,
            modifier = Modifier.weight(1f),
            locked = secondLocked,
        ) {
            if (secondLocked) onLockedClick() else secondJob?.let(onTimerClick)
        }
        TimerCell(R.string.timers_research, null, state, state.slots, false, Modifier.weight(1f), soon = true) {}
        TimerCell(R.string.timers_train, null, state, state.slots, false, Modifier.weight(1f), soon = true) {}
    }
}

@Composable
private fun TimerCell(
    labelRes: Int,
    job: TimerJob?,
    state: GameState,
    slots: List<BuildingSlot>,
    highlight: Boolean,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
    soon: Boolean = false,
    onClick: () -> Unit,
) {
    TutorialHighlight(active = highlight, modifier = modifier) {
        Card(
            modifier =
                Modifier.fillMaxWidth().alpha(
                    if (soon) 0.45f else 1f,
                ).clickable(enabled = !soon, onClick = onClick),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
                when {
                    soon -> Text(stringResource(R.string.timer_soon), style = MaterialTheme.typography.labelMedium)
                    locked -> Text("🔒", style = MaterialTheme.typography.titleMedium)
                    job == null ->
                        Text(
                            stringResource(R.string.timer_idle),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    else -> {
                        val type = slots.firstOrNull { it.slotId == job.slotId }?.type
                        if (type != null) {
                            Text(
                                stringResource(type.nameRes()),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { UiQueries.progress(state, job) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        )
                        Text(
                            DurationFormat.clock(UiQueries.remainingSec(state, job)),
                            style = MaterialTheme.typography.labelMedium,
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
    data: GameData,
    onTileClick: (BuildingSlot) -> Unit,
) {
    val grouped =
        BuildingSection.entries.map { section ->
            section to state.slots.filter { UiQueries.sectionOf(it.type) == section }
        }
    val jobBySlot = state.timers.filter { it.kind == TimerKind.BUILD }.associateBy { it.slotId }
    val tutorialTarget =
        if (state.tutorialStep == TUTORIAL_TAP_TILE_STEP) {
            state.slots.firstOrNull { it.type.producedResource == ResourceType.FOOD && it.level == 0 }?.slotId
        } else {
            null
        }
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
                    state = state,
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
    state: GameState,
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
                    LinearProgressIndicator(
                        progress = { UiQueries.progress(state, job) },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                    Text(
                        DurationFormat.clock(UiQueries.remainingSec(state, job)),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
