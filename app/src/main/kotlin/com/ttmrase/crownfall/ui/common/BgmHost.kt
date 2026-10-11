package com.ttmrase.crownfall.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.RaidStage
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.navigation.Screen
import kotlinx.coroutines.delay

private const val BATTLE_HOLD_MS = 90_000L

/** 画面の route から BGM の場面を決める。 */
fun bgmSceneFor(route: String?): BgmScene =
    when {
        route == null || route.startsWith(Screen.Title.route) -> BgmScene.TITLE
        route.startsWith(Screen.WorldMap.route) -> BgmScene.MAP
        route.startsWith(Screen.Shop.route) -> BgmScene.SHOP
        route.startsWith(Screen.Summon.route) -> BgmScene.SUMMON
        else -> BgmScene.CITY
    }

/**
 * BGM をアプリの前景・画面・襲撃に結びつける（M11 T11.3）。襲撃の段階が出たら戦闘の曲に切り替え、
 * 迎撃が終わるか 90 秒たてば元に戻す。画面が裏に回ったら止める。
 */
@Composable
fun BgmHost(
    route: String?,
    session: GameSession?,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> Bgm.setForeground(context, true)
                    Lifecycle.Event.ON_STOP -> Bgm.setForeground(context, false)
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) Bgm.setForeground(context, true)
        onDispose {
            lifecycle.removeObserver(observer)
            Bgm.stop(context)
        }
    }
    var battleStamp by remember { mutableStateOf(0L) }
    var battle by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        session?.events?.collect { e ->
            when (e) {
                is GameEvent.RaidStageReached ->
                    if (e.stage != RaidStage.RESULT) {
                        battle = true
                        battleStamp = System.currentTimeMillis()
                    }
                is GameEvent.PlayerDefendedRaid, is GameEvent.PlayerDefeated -> battle = false
                else -> Unit
            }
        }
    }
    LaunchedEffect(battleStamp) {
        if (battle) {
            delay(BATTLE_HOLD_MS)
            battle = false
        }
    }
    val scene =
        if (battle && route != null && !route.startsWith(Screen.Title.route)) {
            BgmScene.BATTLE
        } else {
            bgmSceneFor(
                route,
            )
        }
    LaunchedEffect(scene) { Bgm.setScene(context, scene) }
}
