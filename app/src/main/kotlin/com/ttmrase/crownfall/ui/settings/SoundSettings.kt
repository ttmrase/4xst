package com.ttmrase.crownfall.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.ui.common.Bgm
import com.ttmrase.crownfall.ui.common.BgmScene
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 設定の効果音（演出の音。既定は ON。端末がマナー/サイレントのときは ON でも鳴らさない）。 */
@Composable
fun SoundSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Sfx.isEnabled(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.settings_sound_title), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_sound_effects), modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    Sfx.setEnabled(context, it)
                    if (it) Sfx.play(context, SfxId.BUTTON)
                },
            )
        }
        var vibration by remember { mutableStateOf(Sfx.isVibrationEnabled(context)) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_vibration), modifier = Modifier.weight(1f))
            Switch(
                checked = vibration,
                onCheckedChange = {
                    vibration = it
                    Sfx.setVibrationEnabled(context, it)
                },
            )
        }
        var sfxVolume by remember { mutableStateOf(Sfx.volume(context).toFloat()) }
        Text(stringResource(R.string.settings_sfx_volume, sfxVolume.toInt()))
        Slider(
            value = sfxVolume,
            onValueChange = { sfxVolume = it },
            onValueChangeFinished = {
                Sfx.setVolume(context, sfxVolume.toInt())
                Sfx.play(context, SfxId.BUTTON)
            },
            valueRange = 0f..100f,
        )
        Text(stringResource(R.string.settings_sound_note), style = MaterialTheme.typography.bodySmall)
        BgmSettings()
    }
}

/** BGM の ON/OFF・音量と、自分の曲（全体用と場面ごと）の設定。 */
@Composable
private fun BgmSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Bgm.isEnabled(context)) }
    var volume by remember { mutableStateOf(Bgm.volume(context).toFloat()) }
    var version by remember { mutableStateOf(0) }
    // 回転しても選択中の場面を忘れない（"" は全体用、それ以外は BgmScene の名前）
    var pickKey by rememberSaveable { mutableStateOf("") }
    var failedRes by remember { mutableStateOf<Int?>(null) }
    var importedBytes by remember { mutableStateOf<Long?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = displayName(context, uri)
                val scene = BgmScene.entries.firstOrNull { it.name == pickKey }
                failedRes = null
                importedBytes = 0L
                importJob =
                    scope.launch {
                        try {
                            val result = Bgm.importCustom(context, scene, uri, name) { importedBytes = it }
                            failedRes =
                                when (result) {
                                    Bgm.ImportResult.OK -> null
                                    Bgm.ImportResult.TOO_BIG -> R.string.settings_bgm_import_too_big
                                    Bgm.ImportResult.NO_SPACE -> R.string.settings_bgm_import_no_space
                                    Bgm.ImportResult.FAILED -> R.string.settings_bgm_import_failed
                                }
                        } finally {
                            importedBytes = null
                            importJob = null
                            version++
                        }
                    }
            }
        }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_bgm), modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = {
                enabled = it
                Bgm.setEnabled(context, it)
            })
        }
        Text(stringResource(R.string.settings_bgm_volume, volume.toInt()))
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                Bgm.setVolume(context, it.toInt())
            },
            valueRange = 0f..100f,
        )
        Text(stringResource(R.string.settings_bgm_own_title), style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.settings_bgm_own_note), style = MaterialTheme.typography.bodySmall)
        failedRes?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        importedBytes?.let { bytes ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.settings_bgm_importing, bytes / BYTES_PER_MB_TENTH / TENTH),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { importJob?.cancel() }) { Text(stringResource(R.string.common_cancel)) }
            }
        }
        // version を読んで、登録・解除のあとに表示を更新する
        key(version) {
            OwnBgmRow(stringResource(R.string.settings_bgm_all), Bgm.customName(context, null), {
                pickKey = ""
                picker.launch(arrayOf("audio/*"))
            }, {
                Bgm.clearCustom(context, null)
                version++
            })
            BgmScene.entries.forEach { scene ->
                OwnBgmRow(stringResource(sceneLabel(scene)), Bgm.customName(context, scene), {
                    pickKey = scene.name
                    picker.launch(arrayOf("audio/*"))
                }, {
                    Bgm.clearCustom(context, scene)
                    version++
                })
            }
        }
    }
}

@Composable
private fun OwnBgmRow(
    label: String,
    custom: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                custom ?: stringResource(R.string.settings_bgm_default),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        OutlinedButton(onClick = onPick) { Text(stringResource(R.string.settings_bgm_choose)) }
        if (custom != null) TextButton(onClick = onClear) { Text(stringResource(R.string.settings_bgm_clear)) }
    }
}

private fun sceneLabel(scene: BgmScene): Int =
    when (scene) {
        BgmScene.TITLE -> R.string.bgm_scene_title
        BgmScene.CITY -> R.string.bgm_scene_city
        BgmScene.MAP -> R.string.bgm_scene_map
        BgmScene.BATTLE -> R.string.bgm_scene_battle
        BgmScene.SHOP -> R.string.bgm_scene_shop
        BgmScene.SUMMON -> R.string.bgm_scene_summon
    }

private fun displayName(
    context: Context,
    uri: Uri,
): String =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

private const val BYTES_PER_MB_TENTH = 104_858L
private const val TENTH = 10.0
