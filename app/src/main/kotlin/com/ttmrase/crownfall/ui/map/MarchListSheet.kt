package com.ttmrase.crownfall.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.MarchViews
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.nameRes

/** 行軍中の部隊の一覧（段階・残り時間・呼び戻し）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarchListSheet(
    state: GameState,
    data: GameData,
    onRecall: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.march_list_title), style = MaterialTheme.typography.headlineSmall)
            if (state.marches.isEmpty()) Text(stringResource(R.string.march_list_empty))
            state.marches.forEach { m ->
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(m.kind.nameRes()) + " — " + stringResource(m.phase.nameRes()),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text("(${m.target.x}, ${m.target.y})")
                }
                LinearProgressIndicator(
                    progress = { MarchViews.progress(m, state.nowEpochSec) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(
                            R.string.march_remaining,
                            DurationFormat.clock(MarchViews.remainingSec(m, state.nowEpochSec)),
                        ),
                    )
                    Text(stringResource(R.string.march_troops, ShortNumber.format(m.troops.values.sum())))
                }
                OutlinedButton(
                    onClick = { onRecall(m.id) },
                    enabled = m.phase != MarchPhase.RETURNING,
                ) { Text(stringResource(R.string.march_recall)) }
            }
        }
    }
}
