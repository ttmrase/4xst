package com.ttmrase.crownfall.ui.research

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.ResearchPreview
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.buffText
import com.ttmrase.crownfall.ui.city.SpeedupHost
import com.ttmrase.crownfall.ui.common.CostRows
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.researchNodeName
import kotlinx.coroutines.launch

private val Branches = listOf("economy", "military", "development")

private fun branchRes(branch: String): Int =
    when (branch) {
        "economy" -> R.string.research_branch_economy
        "military" -> R.string.research_branch_military
        else -> R.string.research_branch_development
    }

/** 研究画面（T3.5）。3 系統タブ・ノード一覧・詳細シート・研究開始・加速。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchScreen(
    session: GameSession,
    data: GameData,
    hooks: OfferHooks,
    snackbarHostState: SnackbarHostState,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var speedupJobId by rememberSaveable { mutableStateOf<Long?>(null) }
    val s = state ?: return

    val nodes = data.research.values.filter { it.branch == Branches[tab] }
    val running = UiQueries.researchJob(s)

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Branches.forEachIndexed { index, branch ->
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(stringResource(branchRes(branch))) },
                )
            }
        }
        if (running != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp).clickable { speedupJobId = running.id },
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            R.string.research_running_node,
                            researchNodeName(context, running.researchId ?: ""),
                            running.targetLevel ?: 0,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LinearProgressIndicator(
                        progress = { UiQueries.progress(s, running) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        DurationFormat.clock(UiQueries.remainingSec(s, running)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(nodes, key = { it.id }) { node ->
                val row = UiQueries.researchRow(s, node.id, data) ?: return@items
                Card(
                    modifier =
                        Modifier.fillMaxWidth().alpha(
                            if (row.locked) 0.6f else 1f,
                        ).clickable { selected = node.id },
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            researchNodeName(context, node.id).let {
                                if (row.locked) stringResource(R.string.locked_label, it) else it
                            },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.research_level, row.currentLevel, node.maxLevel),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    val nodeId = selected
    val preview = nodeId?.let { UiQueries.researchPreview(s, it, data) }
    if (nodeId != null && preview == null) {
        LaunchedEffect(nodeId) { selected = null }
    }
    if (nodeId != null && preview != null) {
        ResearchSheet(
            state = s,
            preview = preview,
            onStart = {
                scope.launch {
                    when (val r = session.dispatch { Actions.startResearch(it, nodeId, data) }) {
                        is ActionResult.Success -> Unit
                        is ActionResult.Failure -> snackbarHostState.showReplacing(failureMessage(context, r.reason))
                    }
                }
            },
            onSpeedup = { speedupJobId = it },
            onResourceShortage = hooks::onResourceShortage,
            onDismiss = { selected = null },
        )
    }

    val jobId = speedupJobId
    if (jobId != null) {
        SpeedupHost(
            session = session,
            data = data,
            hooks = hooks,
            snackbarHostState = snackbarHostState,
            jobId = jobId,
            onSuccess = {},
            onDismiss = { speedupJobId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResearchSheet(
    state: com.ttmrase.crownfall.core.model.GameState,
    preview: ResearchPreview,
    onStart: () -> Unit,
    onSpeedup: (Long) -> Unit,
    onResourceShortage: (com.ttmrase.crownfall.core.model.Resources) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val node = preview.node
    val next = preview.next
    val blocker = preview.blocker
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier.fillMaxWidth().verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(researchNodeName(context, node.id), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.research_level, preview.currentLevel, node.maxLevel),
                color = MaterialTheme.colorScheme.primary,
            )
            HorizontalDivider()
            if (preview.currentLevel > 0) {
                val now = node.level(preview.currentLevel)
                Text(stringResource(R.string.research_effect_now, buffText(context, now.effectKey, now.effectBp)))
            }
            if (next == null) {
                Text(stringResource(R.string.research_max), color = MaterialTheme.colorScheme.primary)
            } else {
                Text(stringResource(R.string.research_effect_next, buffText(context, next.effectKey, next.effectBp)))
                HorizontalDivider()
                Text(stringResource(R.string.sheet_cost), style = MaterialTheme.typography.titleSmall)
                CostRows(have = state.resources, cost = next.cost, missing = preview.missing)
                Text(stringResource(R.string.sheet_duration, DurationFormat.clock(preview.durationSec)))
                preview.missingAcademy?.let {
                    Text(stringResource(R.string.research_need_academy, it), color = MaterialTheme.colorScheme.error)
                }
                preview.missingNodes.forEach {
                    Text(
                        stringResource(R.string.research_need_node, researchNodeName(context, it.nodeId), it.level),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider()
            val running = preview.running
            when {
                running != null -> {
                    Text(
                        stringResource(R.string.research_running) + "  " +
                            DurationFormat.clock(UiQueries.remainingSec(state, running)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Button(onClick = { onSpeedup(running.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sheet_speedup))
                    }
                }
                blocker is ActionFailure.NotEnoughResources ->
                    OutlinedButton(
                        onClick = { onResourceShortage(blocker.missing) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.research_not_enough), color = MaterialTheme.colorScheme.error)
                    }
                blocker == ActionFailure.QueueFull ->
                    Text(stringResource(R.string.research_queue_full), color = MaterialTheme.colorScheme.error)
                blocker == null ->
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.research_start)) }
                else -> Unit
            }
            Column(modifier = Modifier.padding(bottom = 24.dp)) {}
        }
    }
}
