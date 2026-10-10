package com.ttmrase.crownfall.data

import android.content.Context
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.LoadResult
import com.ttmrase.crownfall.core.util.Clock
import com.ttmrase.crownfall.core.util.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 起動状態。 */
sealed interface Startup {
    /** データとセーブを読み込み中。 */
    data object Loading : Startup

    /** 読み込み完了。[load] はセーブの読み込み結果。 */
    data class Ready(val data: GameData, val session: GameSession, val load: LoadResult) : Startup {
        /** 続きから遊べるセーブがあるか。 */
        val hasSave: Boolean get() = load == LoadResult.Loaded || load == LoadResult.RecoveredFromBackup
    }
}

/**
 * [GameSession]（:core）に Android のライフサイクルとファイル I/O をつなぐ薄い層。
 * 前景にいる間だけ 1 秒 tick し、背景へ回るときに保存する。
 */
class GameRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val clock: Clock = SystemClock,
) {
    private val storage = FileSaveStorage(File(context.filesDir, "save"))
    private val _startup = MutableStateFlow<Startup>(Startup.Loading)
    private var foreground = false

    /** 起動状態。 */
    val startup: StateFlow<Startup> = _startup.asStateFlow()

    init {
        scope.launch {
            val data = withContext(Dispatchers.IO) { ClasspathDataSource.load() }
            val session = GameSession(data, clock, storage, scope)
            val result = session.load()
            _startup.value = Startup.Ready(data, session, result)
            if (foreground) session.startTicking()
        }
    }

    /** Activity が前景に来たとき。現在時刻まで進めて tick を始める。 */
    fun onForeground() {
        foreground = true
        val ready = _startup.value as? Startup.Ready ?: return
        scope.launch {
            ready.session.advanceToNow()
            ready.session.startTicking()
        }
    }

    /** Activity が背景に回ったとき。tick を止めて保存する。 */
    fun onBackground() {
        foreground = false
        val ready = _startup.value as? Startup.Ready ?: return
        ready.session.stopTicking()
        scope.launch { ready.session.flush() }
    }

    /** 新規ゲームを開始する（既存のセーブは上書き）。 */
    suspend fun startNewGame(
        playerName: String,
        starterHeroId: String,
    ) {
        val ready = _startup.value as? Startup.Ready ?: return
        val seed = System.nanoTime() xor clock.nowEpochSec()
        ready.session.newGame(playerName, starterHeroId, seed)
        _startup.value = ready.copy(load = LoadResult.Loaded)
        if (foreground) ready.session.startTicking()
    }
}
