package com.ttmrase.crownfall.ui.common

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.GameSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** 画面の既定の更新間隔。毎秒の再構図を避け、時間の経過だけでは間引く。 */
const val CALM_PERIOD_MS = 5_000L

/**
 * 時間経過による状態の更新は [periodMs] に 1 回まで間引き、操作・ロード・新規開始（[GameSession.revision] の変化）は
 * すぐに流す。毎秒変わる表示（残り時間）は [LocalClock] を使う小さな Composable に閉じ込める。
 */
fun GameSession.calmStateFlow(periodMs: Long = CALM_PERIOD_MS): Flow<GameState?> =
    flow {
        var lastRevision = -1L
        var lastAt = Long.MIN_VALUE / 2
        combine(state, revision) { s, r -> s to r }.collect { (s, r) ->
            val now = SystemClock.elapsedRealtime()
            if (r != lastRevision || now - lastAt >= periodMs) {
                lastRevision = r
                lastAt = now
                emit(s)
            }
        }
    }

/** [calmStateFlow] を Compose の State として購読する。 */
@Suppress("StateFlowValueCalledInComposition")
@Composable
fun GameSession.collectCalmState(periodMs: Long = CALM_PERIOD_MS): State<GameState?> {
    val session = this
    val flow = remember(session, periodMs) { session.calmStateFlow(periodMs) }
    return flow.collectAsStateWithLifecycle(initialValue = session.state.value)
}

/** 秒単位の現在時刻（エポック秒）。`LocalClock.current.value` を読む Composable だけが毎秒再構図される。 */
val LocalClock = compositionLocalOf<State<Long>?> { null }

/** NavHost で 1 度だけ作る時刻の State。 */
@Suppress("StateFlowValueCalledInComposition")
@Composable
fun GameSession.collectClock(): State<Long> {
    val session = this
    val flow = remember(session) { session.state.map { it?.nowEpochSec ?: 0L }.distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(initialValue = session.state.value?.nowEpochSec ?: 0L)
}

/**
 * [endEpochSec] までの残り秒数。読んだ側の Composable（小さなもの）だけが毎秒再構図される。
 * 時計が無い（プレビューなど）ときは [fallbackNow] を使う。
 */
@Composable
fun remainingUntil(
    endEpochSec: Long,
    fallbackNow: Long,
): Long = (endEpochSec - (LocalClock.current?.value ?: fallbackNow)).coerceAtLeast(0)
