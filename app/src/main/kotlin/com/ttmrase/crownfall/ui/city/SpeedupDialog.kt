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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.economy.GemSkip
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.speedupItemName

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
) {
    val job = state.timers.firstOrNull { it.id == jobId }
    LaunchedEffect(job == null) {
        if (job == null) onDismiss()
    }
    if (job == null) return
    val context = LocalContext.current
    val remaining = UiQueries.remainingSec(state, job)
    val gemCost = GemSkip.cost(remaining, data)
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
