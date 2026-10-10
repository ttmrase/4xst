package com.ttmrase.crownfall.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.util.DurationFormat

/** 襲撃予告バナー（全画面の上部）。予告が出ていて到着前のときだけ表示し、残り時間を出す。 */
@Composable
fun RaidBanner(session: GameSession) {
    val state by session.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val left = s.map.nextRaidEpochSec - s.nowEpochSec
    if (!s.map.raidWarned || left <= 0) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.raid_banner, DurationFormat.clock(left)),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
