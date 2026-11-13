package com.ttmrase.crownfall.ui.title

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.session.LoadResult
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.Startup
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GameButton
import com.ttmrase.crownfall.ui.kit.GameButtonSize
import com.ttmrase.crownfall.ui.kit.GameButtonStyle
import com.ttmrase.crownfall.ui.kit.GameDialog
import com.ttmrase.crownfall.ui.kit.GameProgressBar
import com.ttmrase.crownfall.ui.kit.GameTextButton
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.theme.CrownFonts
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

    val context = LocalContext.current
    val version =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    0,
                ).versionName
            }.getOrNull().orEmpty()
        }
    Box(Modifier.fillMaxSize()) {
        TitleBackdrop()
        TitleLogo(
            stringResource(R.string.app_name),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 44.dp),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 36.dp, end = 36.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val current = startup) {
                Startup.Loading -> {
                    GameProgressBar(modifier = Modifier.fillMaxWidth().height(10.dp))
                    Text(stringResource(R.string.title_loading), color = Cf.Ivory, style = TitleNote)
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
                            color = if (current.hasSave) Cf.Ivory else Color(0xFFFFB3A8),
                            textAlign = TextAlign.Center,
                            style = TitleNote,
                            modifier =
                                Modifier
                                    .gameFrame(FrameStyle.Inset, corner = 10.dp, alpha = 0.85f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (current.hasSave) {
                        GameButton(
                            onClick = onEnterGame,
                            modifier = Modifier.fillMaxWidth(),
                            style = GameButtonStyle.Emerald,
                            size = GameButtonSize.Large,
                            sound = true,
                        ) {
                            Text(stringResource(R.string.title_continue), fontSize = 18.sp)
                        }
                    }
                    // 新しい版のセーブは上書きしない（新規ゲームも出さない）。
                    if (current.load !is LoadResult.TooNew) {
                        GameButton(
                            onClick = { if (current.hasSave) confirmOverwrite = true else showNewGame = true },
                            modifier = Modifier.fillMaxWidth(),
                            style = if (current.hasSave) GameButtonStyle.Ghost else GameButtonStyle.Gold,
                            size = if (current.hasSave) GameButtonSize.Normal else GameButtonSize.Large,
                        ) {
                            Text(stringResource(R.string.title_new_game))
                        }
                    }
                }
            }
            if (version.isNotEmpty()) {
                Text(
                    "v$version",
                    color = Cf.Muted.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = CrownFonts.Numeric,
                )
            }
        }
    }

    if (confirmOverwrite) {
        GameDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text(stringResource(R.string.title_overwrite_title)) },
            text = { Text(stringResource(R.string.title_overwrite_body)) },
            confirmButton = {
                GameTextButton(
                    onClick = {
                        confirmOverwrite = false
                        showNewGame = true
                    },
                ) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = {
                GameTextButton(
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

private val TitleNote =
    TextStyle(fontSize = 13.sp, lineHeight = 18.sp, shadow = Shadow(Color(0xFF04060D), Offset(0f, 1.5f), 3f))
