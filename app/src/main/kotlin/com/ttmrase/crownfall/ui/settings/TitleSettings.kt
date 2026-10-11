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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.title.Titles
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.titleLabel
import kotlinx.coroutines.launch

/** 名乗る称号の選択（名乗れるものと「おまかせ」）。選んだものがチャットの名前とメンバー一覧に出る。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TitleSettingsSection(
    session: GameSession,
    data: GameData,
) {
    val state by session.collectCalmState()
    val s = state ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val owned = Titles.owned(s, data)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_title_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = s.selectedTitle == null,
                onClick = { scope.launch { session.dispatch { Actions.selectTitle(it, null, data) } } },
                label = { Text(stringResource(R.string.settings_title_auto)) },
            )
            owned.forEach { id ->
                FilterChip(
                    selected = s.selectedTitle == id,
                    onClick = { scope.launch { session.dispatch { Actions.selectTitle(it, id, data) } } },
                    label = { Text(titleLabel(context, id) ?: id) },
                )
            }
        }
    }
}
