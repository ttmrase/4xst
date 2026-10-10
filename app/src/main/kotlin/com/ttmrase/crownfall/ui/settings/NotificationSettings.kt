package com.ttmrase.crownfall.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ttmrase.crownfall.core.notify.Intensity
import com.ttmrase.crownfall.core.notify.NoticeCategory
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.notify.NoticeScheduler
import com.ttmrase.crownfall.notify.NotificationPrefs

private val CATEGORY_LABELS =
    listOf(
        NoticeCategory.RAID to R.string.notify_cat_raid,
        NoticeCategory.TIMER to R.string.notify_cat_timer,
        NoticeCategory.ALLIANCE to R.string.notify_cat_alliance,
        NoticeCategory.OFFER to R.string.notify_cat_offer,
        NoticeCategory.EVENT to R.string.notify_cat_event,
        NoticeCategory.ENGAGEMENT to R.string.notify_cat_engagement,
        NoticeCategory.COMEBACK to R.string.notify_cat_comeback,
    )

/** 設定の通知部分（T7.3 / T9.2b）。強度・カテゴリ・おやすみ時間・権限（通知 / 正確なアラーム）。 */
@Composable
fun NotificationSettingsSection(session: GameSession?) {
    val context = LocalContext.current
    val prefs = remember { NotificationPrefs(context) }
    val state = session?.state?.collectAsStateWithLifecycle()?.value
    val low = state?.shop?.settings?.frequency == OfferFrequency.LOW
    var revision by remember { mutableIntStateOf(0) }
    val enabled = remember(low, revision) { NoticeCategory.entries.associateWith { prefs.enabled(it, low) } }
    var intensity by remember { mutableStateOf(prefs.intensity) }
    var quiet by remember { mutableStateOf(prefs.quietEnabled) }
    var raidQuiet by remember { mutableStateOf(prefs.raidThroughQuiet) }
    var quietFrom by remember { mutableIntStateOf(prefs.quietStartHour) }
    var quietTo by remember { mutableIntStateOf(prefs.quietEndHour) }
    // システム設定から戻ったときに権限の状態を取り直す
    var canPost by remember { mutableStateOf(NoticeScheduler.canPost(context)) }
    var canExact by remember { mutableStateOf(NoticeScheduler.canScheduleExact(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canPost = NoticeScheduler.canPost(context)
        canExact = NoticeScheduler.canScheduleExact(context)
    }

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
        if (canExact) {
            Text(stringResource(R.string.settings_notify_exact_ok), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(stringResource(R.string.settings_notify_exact_off), color = MaterialTheme.colorScheme.error)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                OutlinedButton(onClick = {
                    val uri = Uri.parse("package:${context.packageName}")
                    val open = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, uri)
                    context.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.settings_notify_exact_open)) }
            }
        }

        Text(stringResource(R.string.settings_notify_intensity), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Intensity.entries.forEach { i ->
                FilterChip(
                    selected = intensity == i,
                    onClick = {
                        intensity = i
                        prefs.intensity = i
                    },
                    label = { Text(stringResource(intensityName(i))) },
                )
            }
        }
        CATEGORY_LABELS.forEach { (category, label) ->
            SwitchLine(label, enabled[category] == true) {
                prefs.set(category, it)
                revision++
            }
        }

        SwitchLine(R.string.settings_notify_quiet, quiet) {
            quiet = it
            prefs.quietEnabled = it
        }
        if (quiet) {
            NumberField(R.string.settings_notify_quiet_from, quietFrom) {
                quietFrom = it.coerceIn(0, MAX_HOUR)
                prefs.quietStartHour = quietFrom
            }
            NumberField(R.string.settings_notify_quiet_to, quietTo) {
                quietTo = it.coerceIn(0, MAX_HOUR)
                prefs.quietEndHour = quietTo
            }
            SwitchLine(R.string.settings_notify_raid_quiet, raidQuiet) {
                raidQuiet = it
                prefs.raidThroughQuiet = it
            }
        }
    }
}

private const val MAX_HOUR = 23

private fun intensityName(i: Intensity): Int =
    when (i) {
        Intensity.LOW -> R.string.frequency_low
        Intensity.STANDARD -> R.string.frequency_standard
        Intensity.MASS -> R.string.frequency_mass
    }

@Composable
private fun SwitchLine(
    labelRes: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(labelRes), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
