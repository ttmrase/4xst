package com.ttmrase.crownfall.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.OfferFrequency
import com.ttmrase.crownfall.core.model.ShopSettings
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.FakeLedger
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.namedString
import com.ttmrase.crownfall.ui.shop.formatYen
import kotlinx.coroutines.launch

/** 設定のショップ部分（T6.5）: オファー頻度、擬似決済の演出、レシートの表記、課金履歴（擬似・既定は非表示）。 */
@Composable
fun ShopSettingsSection(session: GameSession) {
    val state by session.collectCalmState()
    val scope = rememberCoroutineScope()
    var confirmLedger by rememberSaveable { mutableStateOf(false) }
    var showLedger by rememberSaveable { mutableStateOf(false) }
    val s = state ?: return
    val settings = s.shop.settings

    fun save(next: ShopSettings) {
        scope.launch { session.dispatch { Actions.setShopSettings(it, next) } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.settings_shop_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_frequency), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OfferFrequency.entries.forEach { f ->
                FilterChip(
                    selected = settings.frequency == f,
                    onClick = { save(settings.copy(frequency = f)) },
                    label = { Text(stringResource(frequencyName(f))) },
                )
            }
        }
        SwitchRow(
            R.string.settings_long_processing,
            settings.longProcessing,
        ) { save(settings.copy(longProcessing = it)) }
        SwitchRow(
            R.string.settings_error_animation,
            settings.errorAnimation,
        ) { save(settings.copy(errorAnimation = it)) }
        SwitchRow(R.string.settings_receipt_note, settings.receiptNote) { save(settings.copy(receiptNote = it)) }

        Text(stringResource(R.string.settings_ledger_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.settings_ledger_hint), style = MaterialTheme.typography.bodySmall)
        if (!showLedger) {
            OutlinedButton(onClick = { confirmLedger = true }) { Text(stringResource(R.string.settings_ledger_show)) }
        } else {
            LedgerPanel(s)
            OutlinedButton(onClick = { showLedger = false }) { Text(stringResource(R.string.settings_ledger_hide)) }
        }
    }

    if (confirmLedger) {
        AlertDialog(
            onDismissRequest = { confirmLedger = false },
            title = { Text(stringResource(R.string.settings_ledger_confirm_title)) },
            text = { Text(stringResource(R.string.settings_ledger_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLedger = false
                        showLedger = true
                    },
                ) { Text(stringResource(R.string.settings_ledger_confirm_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLedger = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun frequencyName(f: OfferFrequency): Int =
    when (f) {
        OfferFrequency.LOW -> R.string.frequency_low
        OfferFrequency.STANDARD -> R.string.frequency_standard
        OfferFrequency.MASS -> R.string.frequency_mass
    }

@Composable
private fun SwitchRow(
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

/** 課金履歴（擬似）の集計。ユーザー自身の振り返り用で、ゲームのロジックは読まない。 */
@Composable
private fun LedgerPanel(state: GameState) {
    val context = LocalContext.current
    val ledger = state.shop.ledger
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.ledger_total, formatYen(ledger.totalYen), ledger.count),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.ledger_actual), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.ledger_by_month), style = MaterialTheme.typography.titleSmall)
        ledger.byMonth.toSortedMap(reverseOrder()).forEach { (k, v) -> Text("$k  ${formatYen(v)}") }
        Text(stringResource(R.string.ledger_by_trigger), style = MaterialTheme.typography.titleSmall)
        ledger.byTrigger.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            Text(namedString(context, "ledger_trigger_", k) + "  " + formatYen(v))
        }
        Text(stringResource(R.string.ledger_by_sku), style = MaterialTheme.typography.titleSmall)
        ledger.bySku.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            Text(namedString(context, "offer_name_", k) + "  " + formatYen(v))
        }
        Button(
            onClick = {
                val send =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_TEXT, FakeLedger.exportJson(state))
                    }
                context.startActivity(Intent.createChooser(send, null))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.ledger_share)) }
    }
}
