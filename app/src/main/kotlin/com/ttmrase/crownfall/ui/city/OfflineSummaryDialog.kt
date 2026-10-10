package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.sim.OfflineSummary
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.nameRes

/** 「あなたが不在の間に…」の不在サマリー。OK で閉じたら呼び出し側が clear する。 */
@Composable
fun OfflineSummaryDialog(
    summary: OfflineSummary,
    state: GameState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.offline_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.offline_elapsed, DurationFormat.clock(summary.elapsedSec)))
                if (summary.elapsedSec > summary.appliedSec) {
                    Text(stringResource(R.string.offline_cap_reached), color = MaterialTheme.colorScheme.tertiary)
                }
                Text(stringResource(R.string.offline_produced), style = MaterialTheme.typography.titleSmall)
                ResourceType.entries.forEach { type ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(type.nameRes()))
                        Text("+" + ShortNumber.format(summary.produced[type]))
                    }
                }
                Text(stringResource(R.string.offline_alliance), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.offline_help_received, summary.helpReceived))
                Text(stringResource(R.string.offline_chats_received, summary.chatsReceived))
                Text(stringResource(R.string.offline_completed), style = MaterialTheme.typography.titleSmall)
                if (summary.completedJobs.isEmpty()) {
                    Text(stringResource(R.string.offline_none))
                }
                summary.completedJobs.forEach { job ->
                    val type = state.slots.firstOrNull { it.slotId == job.slotId }?.type
                    if (type != null) {
                        Text(
                            stringResource(
                                R.string.offline_completed_item,
                                stringResource(type.nameRes()),
                                job.targetLevel ?: 0,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
    )
}
