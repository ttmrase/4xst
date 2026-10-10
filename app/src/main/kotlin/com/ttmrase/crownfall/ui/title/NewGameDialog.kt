package com.ttmrase.crownfall.ui.title

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.util.PlayerName
import com.ttmrase.crownfall.core.util.PlayerNameResult
import com.ttmrase.crownfall.ui.heroDescRes
import com.ttmrase.crownfall.ui.heroNameRes

/** 新規ゲームの導入: 名前入力（1〜12 文字）→ 初期英雄の 3 択。 */
@Composable
fun NewGameDialog(
    starterHeroIds: List<String>,
    onDismiss: () -> Unit,
    onStart: (name: String, heroId: String) -> Unit,
) {
    var rawName by remember { mutableStateOf("") }
    var validName by remember { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }
    val result = PlayerName.validate(rawName)
    val name = validName

    if (name == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.newgame_name_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = rawName,
                        onValueChange = {
                            rawName = it
                            showError = false
                        },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.newgame_name_hint)) },
                        isError = showError && result !is PlayerNameResult.Valid,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showError) {
                        val error =
                            when (result) {
                                PlayerNameResult.Empty -> R.string.newgame_error_empty
                                PlayerNameResult.TooLong -> R.string.newgame_error_too_long
                                PlayerNameResult.InvalidChars -> R.string.newgame_error_chars
                                is PlayerNameResult.Valid -> null
                            }
                        if (error != null) Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (result is PlayerNameResult.Valid) validName = result.name else showError = true
                    },
                ) { Text(stringResource(R.string.common_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.newgame_hero_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    starterHeroIds.forEach { heroId ->
                        Card(
                            modifier = Modifier.fillMaxWidth().clickable { onStart(name, heroId) },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    heroNameRes(heroId)?.let { stringResource(it) } ?: heroId,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                heroDescRes(
                                    heroId,
                                )?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { validName = null },
                ) { Text(stringResource(R.string.common_back)) }
            },
        )
    }
}
