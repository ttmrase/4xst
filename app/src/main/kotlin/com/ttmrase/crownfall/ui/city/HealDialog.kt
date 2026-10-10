package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.buff.BuffAggregator
import com.ttmrase.crownfall.core.buff.BuffKey
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.military.Military
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.Buffs
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.CostRows
import com.ttmrase.crownfall.ui.troopNameById

/** 負傷兵の治療ダイアログ。負傷兵をすべて治療する 1 ボタン（コストと所要時間つき）。 */
@Composable
fun HealDialog(
    state: GameState,
    data: GameData,
    onHeal: (Map<String, Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val batch = state.wounded.filterValues { it > 0 }
    val cost = Military.healCost(batch, data)
    val missing = cost.missingFrom(state.resources)
    val buffs = BuffAggregator.compute(state, data)
    val duration = Buffs.speedSeconds(Military.baseHealSeconds(batch, data), buffs[BuffKey.HEAL_SPEED])
    val capacity = Military.hospitalCapacity(state, data, buffs)
    val healing = UiQueries.healJob(state) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.heal_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.heal_capacity, ShortNumber.format(capacity)),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (batch.isEmpty()) {
                    Text(stringResource(R.string.heal_none))
                }
                batch.entries.sortedBy { it.key }.forEach { (id, count) ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(troopNameById(context, id))
                        Text(ShortNumber.format(count))
                    }
                }
                if (batch.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.heal_cost), style = MaterialTheme.typography.titleSmall)
                    CostRows(have = state.resources, cost = cost, missing = missing)
                    Text(stringResource(R.string.barracks_duration, DurationFormat.clock(duration)))
                    Button(onClick = { onHeal(batch) }, enabled = !healing, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.heal_all))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
