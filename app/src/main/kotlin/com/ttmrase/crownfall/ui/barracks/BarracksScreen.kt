package com.ttmrase.crownfall.ui.barracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.city.SpeedupHost
import com.ttmrase.crownfall.ui.common.CostRows
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.troopName
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/** 兵舎画面（T3.5）。兵種タブ・Tier 選択・数量スライダー・コストと時間・訓練・加速。 */
@Composable
fun BarracksScreen(
    session: GameSession,
    data: GameData,
    hooks: OfferHooks,
    snackbarHostState: SnackbarHostState,
    initialTab: Int,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, TroopType.entries.lastIndex)) }
    var tierIndex by rememberSaveable { mutableIntStateOf(0) }
    var amount by rememberSaveable { mutableFloatStateOf(1f) }
    var speedupJobId by rememberSaveable { mutableStateOf<Long?>(null) }
    val s = state ?: return

    val type = TroopType.entries[tab]
    val tiers = UiQueries.troopTiers(s, type, data)
    val view = tiers.getOrNull(tierIndex.coerceIn(0, maxOf(0, tiers.lastIndex))) ?: return
    val troop = view.troop
    val maxCount = view.maxTrainable
    val count = if (maxCount <= 0) 1L else amount.roundToLong().coerceIn(1L, maxCount)
    val preview = UiQueries.trainPreview(s, troop.id, count, data)
    val blocker = preview?.blocker

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            TroopType.entries.forEachIndexed { index, t ->
                val built = UiQueries.troopTiers(s, t, data).firstOrNull()?.let { !it.locked } ?: false
                Tab(
                    selected = tab == index,
                    onClick = {
                        tab = index
                        tierIndex = 0
                        amount = 1f
                    },
                    text = {
                        val name = stringResource(t.nameRes())
                        Text(if (built) name else stringResource(R.string.locked_label, name))
                    },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiers.forEachIndexed { index, t ->
                    val tier = stringResource(R.string.tier_label, t.troop.tier)
                    val label = if (t.locked) stringResource(R.string.locked_label, tier) else tier
                    if (index == tierIndex) {
                        Button(onClick = {}) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = {
                                tierIndex = index
                                amount = 1f
                            },
                        ) { Text(label) }
                    }
                }
            }
            Text(troopName(context, troop), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.barracks_owned, ShortNumber.format(view.owned)) +
                    "  " + stringResource(R.string.barracks_wounded, ShortNumber.format(view.wounded)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    R.string.troop_stats,
                    ShortNumber.format(troop.attack),
                    ShortNumber.format(troop.defense),
                    ShortNumber.format(troop.hp),
                    ShortNumber.format(troop.speed),
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            HorizontalDivider()
            if (view.locked) {
                Text(
                    stringResource(
                        R.string.troop_unlock_at,
                        stringResource(troop.type.trainingBuilding.nameRes()),
                        view.requiredBuildingLevel,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                if (maxCount > 1) {
                    Text(
                        stringResource(R.string.barracks_amount, ShortNumber.format(count)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = amount.coerceIn(1f, maxCount.toFloat()),
                        onValueChange = { amount = it },
                        valueRange = 1f..maxCount.toFloat(),
                    )
                } else {
                    Text(
                        stringResource(R.string.barracks_amount, ShortNumber.format(count)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (preview != null) {
                    Text(stringResource(R.string.barracks_cost), style = MaterialTheme.typography.titleSmall)
                    CostRows(have = s.resources, cost = preview.cost, missing = preview.missing)
                    Text(stringResource(R.string.barracks_duration, DurationFormat.clock(preview.durationSec)))
                }
            }
            val running = preview?.running
            if (running != null) {
                Text(stringResource(R.string.barracks_training), style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(
                    progress = { UiQueries.progress(s, running) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(DurationFormat.clock(UiQueries.remainingSec(s, running)))
                    Button(onClick = { speedupJobId = running.id }) { Text(stringResource(R.string.sheet_speedup)) }
                }
            }
            if (!view.locked) {
                when {
                    blocker is ActionFailure.NotEnoughResources ->
                        OutlinedButton(
                            onClick = { hooks.onResourceShortage(blocker.missing) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.barracks_not_enough),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    blocker == ActionFailure.QueueFull ->
                        Text(stringResource(R.string.barracks_queue_full), color = MaterialTheme.colorScheme.error)
                    blocker != null ->
                        Text(failureMessage(context, blocker), color = MaterialTheme.colorScheme.error)
                    else ->
                        Button(
                            onClick = {
                                scope.launch {
                                    when (
                                        val r =
                                            session.dispatch {
                                                Actions.startTraining(
                                                    it,
                                                    troop.id,
                                                    count,
                                                    data,
                                                )
                                            }
                                    ) {
                                        is ActionResult.Success -> amount = 1f
                                        is ActionResult.Failure ->
                                            snackbarHostState.showReplacing(
                                                failureMessage(context, r.reason),
                                            )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.barracks_train)) }
                }
            }
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
            onSuccess = {},
            onDismiss = { speedupJobId = null },
        )
    }
}
