package com.ttmrase.crownfall.ui.title

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.session.LoadResult
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.Startup
import kotlinx.coroutines.launch

/** タイトル画面（T2.5）。セーブの有無で「続きから」「新規ゲーム」を出し、エラー時は文言を出す。 */
@Composable
fun TitleScreen(
    repository: GameRepository,
    onEnterGame: () -> Unit,
) {
    val startup by repository.startup.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmOverwrite by remember { mutableStateOf(false) }
    var showNewGame by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
        when (val current = startup) {
            Startup.Loading -> {
                CircularProgressIndicator()
                Text(stringResource(R.string.title_loading))
            }
            is Startup.Ready -> {
                val message =
                    when (val load = current.load) {
                        is LoadResult.TooNew -> R.string.title_error_too_new
                        LoadResult.Corrupt -> R.string.title_error_corrupt
                        LoadResult.RecoveredFromBackup -> R.string.title_recovered
                        else -> null
                    }
                if (message != null) {
                    Text(
                        stringResource(message),
                        color =
                            if (current.hasSave) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.tertiary
                            },
                        textAlign = TextAlign.Center,
                    )
                }
                if (current.hasSave) {
                    Button(onClick = onEnterGame, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.title_continue))
                    }
                }
                // 新しい版のセーブは上書きしない（新規ゲームも出さない）。
                if (current.load !is LoadResult.TooNew) {
                    OutlinedButton(
                        onClick = { if (current.hasSave) confirmOverwrite = true else showNewGame = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.title_new_game))
                    }
                }
            }
        }
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text(stringResource(R.string.title_overwrite_title)) },
            text = { Text(stringResource(R.string.title_overwrite_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOverwrite = false
                        showNewGame = true
                    },
                ) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmOverwrite = false },
                ) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    val ready = startup as? Startup.Ready
    if (showNewGame && ready != null) {
        NewGameDialog(
            starterHeroIds = ready.data.rules.starterHeroes,
            onDismiss = { showNewGame = false },
            onStart = { name, heroId ->
                showNewGame = false
                scope.launch {
                    repository.startNewGame(name, heroId)
                    onEnterGame()
                }
            },
        )
    }
}
