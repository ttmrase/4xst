package com.ttmrase.crownfall.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.troopNameById
import kotlin.math.roundToLong

/** 部隊編成シート。兵種ごとのスライダー、英雄の選択、所要時間と積載量の見積もり。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormationSheet(
    state: GameState,
    data: GameData,
    kind: MarchKind,
    target: TileRef,
    onSend: (Map<String, Long>, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    val counts = remember { mutableStateMapOf<String, Float>() }
    val heroes = remember { mutableStateListOf<String>() }
    val available = state.troops.filterValues { it > 0 }.keys.sorted()
    val busy = Marching.busyHeroes(state)
    val maxHeroes = data.mapRules.march.maxHeroes
    val picked =
        available.associateWith { (counts[it] ?: 0f).roundToLong().coerceIn(0L, state.troops.getValue(it)) }
            .filterValues { it > 0 }
    val total = picked.values.sum()
    val cap = Marching.troopCap(state, data)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.formation_title) + " — " + stringResource(kind.nameRes()),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.formation_target, target.x, target.y))
            HorizontalDivider()
            Text(stringResource(R.string.formation_troops), style = MaterialTheme.typography.titleSmall)
            if (available.isEmpty()) Text(stringResource(R.string.formation_no_troops))
            available.forEach { id ->
                val owned = state.troops.getValue(id)
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(troopNameById(context, id))
                    Text("${ShortNumber.format(picked[id] ?: 0L)} / ${ShortNumber.format(owned)}")
                }
                Slider(
                    value = counts[id] ?: 0f,
                    onValueChange = { counts[id] = it },
                    valueRange = 0f..owned.toFloat(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        var left = cap
                        available.forEach { id ->
                            val n = minOf(left, state.troops.getValue(id))
                            counts[id] = n.toFloat()
                            left -= n
                        }
                    },
                ) { Text(stringResource(R.string.formation_all)) }
                OutlinedButton(onClick = { counts.clear() }) { Text(stringResource(R.string.formation_reset)) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.formation_heroes, maxHeroes), style = MaterialTheme.typography.titleSmall)
            state.heroes.forEach { h ->
                val name = heroNameRes(h.heroId)?.let { stringResource(it) } ?: h.heroId
                val isBusy = h.heroId in busy
                val on = h.heroId in heroes
                val label = if (isBusy) name + " (" + stringResource(R.string.formation_hero_busy) + ")" else name
                if (on) {
                    Button(onClick = { heroes.remove(h.heroId) }) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { if (heroes.size < maxHeroes) heroes.add(h.heroId) },
                        enabled = !isBusy,
                    ) { Text(label) }
                }
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.formation_total, ShortNumber.format(total), ShortNumber.format(cap)),
                color = if (total > cap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (total > 0) {
                val travel = Marching.travelSec(state, kind, picked, heroes.toList(), target, data)
                Text(stringResource(R.string.formation_travel, DurationFormat.clock(travel)))
                if (kind == MarchKind.GATHER) {
                    val load = Marching.capacity(state, picked, heroes.toList(), data)
                    Text(stringResource(R.string.formation_capacity, ShortNumber.format(load)))
                }
            }
            Button(
                onClick = { onSend(picked, heroes.toList()) },
                enabled = total > 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.formation_send)) }
        }
    }
}
