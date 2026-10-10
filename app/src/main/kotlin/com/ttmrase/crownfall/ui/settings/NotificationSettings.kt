package com.ttmrase.crownfall.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.notify.NoticeChannel
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.notify.NoticeScheduler
import com.ttmrase.crownfall.notify.NotificationPrefs

/** 設定の通知部分（T7.3）。チャンネルごとの ON/OFF。権限が無いときは案内する。 */
@Composable
fun NotificationSettingsSection(session: GameSession?) {
    val context = LocalContext.current
    val prefs = remember { NotificationPrefs(context) }
    val state = session?.state?.collectAsStateWithLifecycle()?.value
    val low = state?.shop?.settings?.frequency == OfferFrequency.LOW
    val values = remember(low) { mutableStateOf(NoticeChannel.entries.associateWith { prefs.enabled(it, low) }) }
    var current by values
    // システム設定から戻ったときに権限の状態を取り直す
    var canPost by remember { mutableStateOf(NoticeScheduler.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canPost = NoticeScheduler.canPost(context) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_notify_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_notify_hint), style = MaterialTheme.typography.bodySmall)
        if (!canPost) {
            Text(stringResource(R.string.settings_notify_denied), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) { Text(stringResource(R.string.settings_notify_open)) }
        }
        listOf(
            NoticeChannel.TIMER to R.string.settings_notify_timer,
            NoticeChannel.OFFER to R.string.settings_notify_offer,
            NoticeChannel.ALLIANCE to R.string.settings_notify_alliance,
            NoticeChannel.RAID to R.string.settings_notify_raid,
        ).forEach { (channel, label) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(label), modifier = Modifier.weight(1f))
                Switch(
                    checked = current[channel] == true,
                    onCheckedChange = {
                        prefs.set(channel, it)
                        current = current + (channel to it)
                    },
                )
            }
        }
    }
}
