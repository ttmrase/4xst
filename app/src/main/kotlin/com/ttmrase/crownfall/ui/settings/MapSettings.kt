package com.ttmrase.crownfall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ui.map.DayNight
import com.ttmrase.crownfall.ui.map.MapPrefs

/** マップの昼夜の色味（自動 = 端末の時刻、昼のまま、夜のまま）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MapSettingsSection() {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(MapPrefs.dayNight(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_map_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DayNight.AUTO to R.string.settings_map_auto,
                DayNight.DAY to R.string.settings_map_day,
                DayNight.NIGHT to R.string.settings_map_night,
            ).forEach { (m, label) ->
                FilterChip(
                    selected = mode == m.id,
                    onClick = {
                        mode = m.id
                        MapPrefs.setDayNight(context, m.id)
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
}
