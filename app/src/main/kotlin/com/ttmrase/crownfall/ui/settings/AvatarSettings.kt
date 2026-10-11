package com.ttmrase.crownfall.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.portrait.PortraitKeys
import com.ttmrase.crownfall.ui.portrait.PortraitView
import kotlinx.coroutines.launch

/** プレイヤーのアバター選び（生成済みの [Actions.AVATAR_COUNT] 種。選んだものがチャット・順位表に出る）。 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AvatarSettingsSection(session: GameSession) {
    val state by session.collectCalmState()
    val s = state ?: return
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_avatar_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(Actions.AVATAR_COUNT) { i ->
                val selected = i == s.avatar
                PortraitView(
                    PortraitKeys.avatar(i),
                    s.playerName,
                    52.dp,
                    Modifier.clickable { scope.launch { session.dispatch { Actions.setAvatar(it, i) } } }.padding(2.dp),
                    rarity = if (selected) com.ttmrase.crownfall.core.model.HeroRarity.SSR else null,
                )
            }
        }
    }
}
