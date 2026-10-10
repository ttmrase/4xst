package com.ttmrase.crownfall.notify

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * チュートリアルが終わったあとに、通知の権限を一度だけ尋ねる（Android 13 以上）。
 * 拒否されても二度は尋ねない（設定画面で案内する）。
 */
@Composable
fun NotificationPermissionEffect(session: GameSession?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || session == null) return
    val context = LocalContext.current
    val prefs = remember { NotificationPrefs(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val stepFlow = remember(session) { session.state.map { it?.tutorialStep ?: 0 }.distinctUntilChanged() }
    val step by stepFlow.collectAsStateWithLifecycle(0)
    LaunchedEffect(step) {
        if (step >= Actions.TUTORIAL_DONE && !prefs.permissionAsked && !NoticeScheduler.canPost(context)) {
            prefs.permissionAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
