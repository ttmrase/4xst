package com.ttmrase.crownfall.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ai.AiService
import com.ttmrase.crownfall.ai.providers.OnDeviceDebugLog
import com.ttmrase.crownfall.core.data.GameData
import kotlinx.coroutines.launch

/**
 * 端末内 LLM のデバッグ（1.1.2）。「テスト発話」は今のモデルで Kaede に一言言わせて生成時間も出す。
 * 「直近の入出力を表示」は最新 5 件の入力・出力を見せる（メモリ上だけで、保存しない）。
 */
@Composable
fun OnDeviceDebugSection(
    ai: AiService,
    data: GameData?,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiService.TestUtterance?>(null) }
    var showIo by rememberSaveable { mutableStateOf(false) }
    // 入出力の一覧は、開いたときと、テスト発話が終わったときに読み直す
    var refresh by remember { mutableStateOf(0) }
    val entries = remember(showIo, refresh) { if (showIo) ai.debugLog.recent() else emptyList() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.on_device_debug_title), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(
            enabled = data != null && !running,
            onClick = {
                val d = data ?: return@OutlinedButton
                running = true
                scope.launch {
                    result = ai.testUtterance(d)
                    running = false
                    refresh++
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.on_device_test_utterance)) }
        if (running) Text(stringResource(R.string.on_device_test_running), style = MaterialTheme.typography.bodySmall)
        result?.let {
            Text(testText(it), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    R.string.on_device_test_info,
                    it.profile,
                    it.backend.ifEmpty {
                        "-"
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OutlinedButton(onClick = { showIo = !showIo }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (showIo) R.string.on_device_hide_io else R.string.on_device_show_io))
        }
        if (showIo) {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.on_device_io_none),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            entries.forEach { IoEntry(it) }
        }
    }
}

@Composable
private fun testText(r: AiService.TestUtterance): String {
    val text = r.text.orEmpty()
    return when {
        r.error == null -> stringResource(R.string.on_device_test_result, text, r.millis)
        r.error == "rejected" -> stringResource(R.string.on_device_test_rejected, text, r.millis)
        else -> stringResource(R.string.on_device_test_failed, r.error, r.millis)
    }
}

@Composable
private fun IoEntry(e: OnDeviceDebugLog.Entry) {
    val used = e.cleaned ?: stringResource(R.string.on_device_io_rejected)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.on_device_io_entry, e.millis, used), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.on_device_io_system), style = MaterialTheme.typography.labelSmall)
        Text(e.system, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.on_device_io_prompt), style = MaterialTheme.typography.labelSmall)
        Text(e.prompt, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.on_device_io_raw), style = MaterialTheme.typography.labelSmall)
        Text(e.raw, style = MaterialTheme.typography.bodySmall)
    }
}
