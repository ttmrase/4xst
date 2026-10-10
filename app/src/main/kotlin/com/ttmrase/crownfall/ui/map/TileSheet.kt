package com.ttmrase.crownfall.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.Scouting
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.nameRes

/** タイル詳細シート（内容・距離・実行できる行軍）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TileSheet(
    state: GameState,
    world: WorldMap,
    data: GameData,
    tile: TileRef,
    onAction: (MarchKind) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val info = MapQueries.tileAt(world, state.map, tile.x, tile.y)
    val center = data.mapRules.center
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val node = info?.node
            val barbarian = info?.barbarian
            val structure = info?.structure
            val title =
                when {
                    node != null -> stringResource(R.string.tile_node, stringResource(node.type.nameRes()), node.level)
                    barbarian != null -> stringResource(R.string.tile_barbarian, barbarian.level)
                    structure != null ->
                        when (structure.type) {
                            StructureType.PLAYER_CASTLE -> stringResource(R.string.tile_player_castle)
                            StructureType.ALLY_CASTLE ->
                                stringResource(
                                    if (structure.tag.startsWith("persona_slot_")) {
                                        R.string.tile_ally_persona
                                    } else {
                                        R.string.tile_ally_castle
                                    },
                                )
                            StructureType.NPC_CASTLE -> stringResource(R.string.tile_npc_castle)
                            StructureType.BARBARIAN_FORT -> stringResource(R.string.tile_fort, structure.level)
                            StructureType.HOLY_LAND -> stringResource(R.string.tile_holy_land)
                        }
                    else -> stringResource(R.string.tile_empty)
                }
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                "(${tile.x}, ${tile.y})  " + (info?.terrain?.let { stringResource(it.nameRes()) } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(R.string.tile_distance, MapQueries.distance(tile.x, tile.y, center.x, center.y)),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (node != null) {
                Text(stringResource(R.string.tile_node_remaining, ShortNumber.format(node.remaining)))
            }
            if (structure != null && structure.power > 0) {
                Text(stringResource(R.string.tile_power, ShortNumber.format(structure.power)))
            }
            val busy = state.marches.any { it.phase == MarchPhase.WORKING && it.target == tile }
            if (node != null && busy) Text(stringResource(R.string.tile_busy_gathering))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (node != null && !busy) {
                    Button(onClick = { onAction(MarchKind.GATHER) }) { Text(stringResource(R.string.action_gather)) }
                }
                if (barbarian != null) {
                    Button(onClick = { onAction(MarchKind.ATTACK) }) { Text(stringResource(R.string.action_attack)) }
                }
                if (structure?.type == StructureType.BARBARIAN_FORT) {
                    Button(onClick = { onAction(MarchKind.RALLY) }) { Text(stringResource(R.string.action_rally)) }
                }
                if (Scouting.isScoutable(state, tile, data)) {
                    OutlinedButton(onClick = { onAction(MarchKind.SCOUT) }) {
                        Text(stringResource(R.string.action_scout))
                    }
                }
            }
        }
    }
}
