package com.ttmrase.crownfall.ui.settings

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ai.ondevice.ModelTransfer
import com.ttmrase.crownfall.ondevice.OnDeviceModelStore
import com.ttmrase.crownfall.ondevice.TransferState

/**
 * 設定の端末内 LLM の部分（T9.1）。モデルファイルを読み込む / https の URL からダウンロードする。
 * アプリはモデルをホストしない。端末が要件を満たさなくても、警告を出したうえで使える。
 */
@Composable
fun OnDeviceSettingsSection(
    store: OnDeviceModelStore,
    chargingOnly: Boolean,
    timeoutSec: Int,
    onChargingOnly: (Boolean) -> Unit,
    onTimeoutSec: (Int) -> Unit,
) {
    val context = LocalContext.current
    val state by store.state.collectAsStateWithLifecycle()
    val revision by store.revision.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var sha by rememberSaveable { mutableStateOf("") }
    val model = remember(revision) { store.current() }
    val info = remember(revision, state) { store.deviceInfo() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(store::import) }
    val running = state is TransferState.Running

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.settings_on_device), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.settings_on_device_note), style = MaterialTheme.typography.bodySmall)
        Text(
            if (model != null) {
                val size = Formatter.formatFileSize(context, model.length())
                stringResource(R.string.on_device_model_current, model.name, size)
            } else {
                stringResource(R.string.on_device_model_none)
            },
        )
        Text(
            stringResource(
                R.string.on_device_device_info,
                Formatter.formatFileSize(context, info.totalRamBytes),
                Formatter.formatFileSize(context, info.freeBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        ModelTransfer.assess(info.totalRamBytes, info.freeBytes, model?.length() ?: 0L).forEach { advice ->
            Text(
                stringResource(adviceRes(advice)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running, onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.on_device_import))
            }
            OutlinedButton(enabled = !running && model != null, onClick = { store.delete() }) {
                Text(stringResource(R.string.on_device_delete))
            }
        }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.on_device_url)) },
            supportingText = { Text(stringResource(R.string.on_device_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sha,
            onValueChange = { sha = it },
            label = { Text(stringResource(R.string.on_device_sha)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !running && url.isNotBlank() && ModelTransfer.validateUrl(url) == null,
            onClick = { store.download(url, sha) },
        ) { Text(stringResource(R.string.on_device_download)) }

        when (val s = state) {
            is TransferState.Running -> {
                if (s.total > 0) {
                    LinearProgressIndicator(
                        progress = { s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    stringResource(
                        R.string.on_device_progress,
                        Formatter.formatFileSize(context, s.done),
                        if (s.total > 0) Formatter.formatFileSize(context, s.total) else "?",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { store.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            }
            is TransferState.Failed ->
                Text(stringResource(failureRes(s.code)), color = MaterialTheme.colorScheme.error)
            is TransferState.Done -> Text(stringResource(R.string.on_device_done, s.name))
            TransferState.Idle -> Unit
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.on_device_charging_only), modifier = Modifier.weight(1f))
            Switch(checked = chargingOnly, onCheckedChange = onChargingOnly)
        }
        NumberField(R.string.on_device_timeout, timeoutSec, onTimeoutSec)
    }
}

private fun adviceRes(advice: ModelTransfer.Advice): Int =
    when (advice) {
        ModelTransfer.Advice.LOW_RAM -> R.string.on_device_advice_ram
        ModelTransfer.Advice.LOW_STORAGE -> R.string.on_device_advice_storage
        ModelTransfer.Advice.MODEL_BIGGER_THAN_RAM_SHARE -> R.string.on_device_advice_model_big
    }

private fun failureRes(code: String): Int =
    when {
        code == "no_space" -> R.string.on_device_err_no_space
        code == "size_mismatch" -> R.string.on_device_err_size
        code == "sha_mismatch" -> R.string.on_device_err_sha
        code == "bad_url" -> R.string.on_device_err_url
        code == "bad_name" -> R.string.on_device_err_name
        code.startsWith("http_") -> R.string.on_device_err_http
        else -> R.string.on_device_err_io
    }
