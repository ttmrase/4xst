package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.speedupItemName
import kotlinx.coroutines.launch

/**
 * 加速ダイアログ。残り時間と使える加速アイテム、ジェムスキップを出す。
 * タイマーが完了（または消滅）したら自動で閉じる。操作は [onUseItem] / [onGemSkip] で呼び出し側が実行する。
 */
@Composable
fun SpeedupDialog(
    state: GameState,
    data: GameData,
    jobId: Long,
    onUseItem: (itemId: String, count: Int) -> Unit,
    onGemSkip: () -> Unit,
    onDismiss: () -> Unit,
    onRequestHelp: () -> Unit = {},
) {
    val job = state.timers.firstOrNull { it.id == jobId }
    LaunchedEffect(job == null) {
        if (job == null) onDismiss()
    }
    if (job == null) return
    val context = LocalContext.current
    val remaining = UiQueries.remainingSec(state, job)
    val gemCost = UiQueries.gemSkipCost(state, remaining, data)
    val items = UiQueries.usableItems(state, job, data)
    var confirmGems by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speedup_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.speedup_remaining), style = MaterialTheme.typography.labelMedium)
                Text(
                    DurationFormat.clock(remaining),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
                Text(stringResource(R.string.speedup_items), style = MaterialTheme.typography.titleSmall)
                if (items.isEmpty()) {
                    Text(stringResource(R.string.speedup_no_items), style = MaterialTheme.typography.bodyMedium)
                }
                items.forEach { usable ->
                    val needed = UiQueries.itemsNeeded(remaining, usable.item.seconds, usable.owned)
                    Column {
                        Text(
                            stringResource(
                                R.string.speedup_item_row,
                                speedupItemName(context, usable.item),
                                usable.owned,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedButton(onClick = { onUseItem(usable.item.id, 1) }) {
                                Text(stringResource(R.string.speedup_use_one))
                            }
                            if (needed > 1) {
                                Button(onClick = { onUseItem(usable.item.id, needed) }) {
                                    Text(stringResource(R.string.speedup_use_needed, needed))
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                if (Actions.canRequestHelp(job.kind)) {
                    if (job.helpRequested) {
                        Text(
                            stringResource(R.string.speedup_help_requested, job.helpCount),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        OutlinedButton(onClick = onRequestHelp, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.speedup_ask_help))
                        }
                    }
                }
                Button(onClick = { confirmGems = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.speedup_gems) + "  " +
                            stringResource(R.string.speedup_gem_cost, ShortNumber.format(gemCost)),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )

    if (confirmGems) {
        AlertDialog(
            onDismissRequest = { confirmGems = false },
            text = { Text(stringResource(R.string.speedup_gem_confirm, ShortNumber.format(gemCost))) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmGems = false
                        onGemSkip()
                    },
                ) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmGems = false },
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/**
 * [SpeedupDialog] に操作（アイテム使用・ジェムスキップ）とエラー表示をつないだもの。
 * 成功したら [onSuccess]（チュートリアルの進行など）を呼ぶ。ジェム不足は [OfferHooks.onGemShortage]。
 */
@Composable
fun SpeedupHost(
    session: GameSession,
    data: GameData,
    hooks: OfferHooks,
    snackbarHostState: SnackbarHostState,
    jobId: Long,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val s = state ?: return

    fun handle(result: ActionResult) {
        when (result) {
            is ActionResult.Success -> onSuccess()
            is ActionResult.Failure -> {
                val reason = result.reason
                if (reason is ActionFailure.NotEnoughGems) hooks.onGemShortage(reason.missing)
                scope.launch { snackbarHostState.showReplacing(failureMessage(context, reason)) }
            }
        }
    }
    SpeedupDialog(
        state = s,
        data = data,
        jobId = jobId,
        onUseItem = { itemId, count ->
            scope.launch { handle(session.dispatch { Actions.applySpeedupItem(it, jobId, itemId, count, data) }) }
        },
        onGemSkip = { scope.launch { handle(session.dispatch { Actions.gemSkip(it, jobId, data) }) } },
        onDismiss = onDismiss,
        onRequestHelp = {
            scope.launch {
                val result = session.dispatch { Actions.requestHelp(it, jobId) }
                if (result is ActionResult.Failure) {
                    snackbarHostState.showReplacing(failureMessage(context, result.reason))
                }
            }
        },
    )
}
