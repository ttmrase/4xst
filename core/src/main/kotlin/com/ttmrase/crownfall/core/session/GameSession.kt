package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.hero.HeroBootstrap
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.save.DecodeResult
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.save.SaveGame
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.NewGame
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.OfflineSummary
import com.ttmrase.crownfall.core.sim.Tracking
import com.ttmrase.crownfall.core.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [GameSession.load] の結果。 */
sealed interface LoadResult {
    /** 現行セーブを読み込んだ。 */
    data object Loaded : LoadResult

    /** セーブが無い（新規ゲームを開始する）。 */
    data object NoSave : LoadResult

    /** セーブが新しい版で書かれている。書き込みは一切しない。 */
    data class TooNew(val version: Int) : LoadResult

    /** 現行が壊れていたためバックアップから復旧した。 */
    data object RecoveredFromBackup : LoadResult

    /** 現行もバックアップも読めない（新規ゲームを促す）。 */
    data object Corrupt : LoadResult
}

/**
 * ゲーム状態の保持者。Android 依存なし（kotlinx.coroutines のみ）。
 * 操作は [dispatch] で Mutex により直列化され、適用前に必ず現在時刻まで進める。
 * 保存は「dirty かつ前回保存から [SAVE_INTERVAL_SEC] 秒以上」でスロットリングし、[flush] で即時保存する。
 */
class GameSession(
    private val data: GameData,
    private val clock: Clock,
    private val storage: SaveStorage,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<GameState?>(null)
    private val _events = MutableSharedFlow<GameEvent>(replay = 0, extraBufferCapacity = EVENT_BUFFER)
    private val _offlineSummary = MutableStateFlow<OfflineSummary?>(null)
    private val _revision = MutableStateFlow(0L)

    private var seed = 0L
    private var dirty = false
    private var lastSaveEpochSec = Long.MIN_VALUE / 2
    private var blockedByTooNew = false
    private var tickJob: Job? = null

    /** 現在の状態（未ロード・新規開始前は null）。 */
    val state: StateFlow<GameState?> = _state.asStateFlow()

    /** 時間経過ではなく、操作・ロード・新規開始で状態が変わるたびに増える番号（UI が即時に反映するための目印）。 */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** ゲームイベント（replay なし）。 */
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()

    /** 表示待ちの不在サマリー。表示後に [consumeOfflineSummary] で消す。 */
    val offlineSummary: StateFlow<OfflineSummary?> = _offlineSummary.asStateFlow()

    /** 不在サマリーを消す。 */
    fun consumeOfflineSummary() {
        _offlineSummary.value = null
    }

    /**
     * セーブを読み込む。現行が壊れていれば退避してバックアップを試す。
     * [LoadResult.TooNew] の場合は何も書き込まない。成功時は現在時刻までオフライン進行する。
     */
    suspend fun load(): LoadResult =
        mutex.withLock {
            val primary = withContext(ioDispatcher) { storage.readPrimary() }
            var recovered = false
            var decoded: DecodeResult? = null
            if (primary != null) {
                decoded = SaveCodec.decode(primary)
                if (decoded is DecodeResult.Corrupt) {
                    withContext(ioDispatcher) { storage.quarantinePrimary() }
                    decoded = null
                    recovered = true
                }
            }
            if (decoded == null) {
                val backup = withContext(ioDispatcher) { storage.readBackup() }
                if (backup == null) return@withLock if (primary == null) LoadResult.NoSave else LoadResult.Corrupt
                decoded = SaveCodec.decode(backup)
                recovered = true
            }
            when (decoded) {
                is DecodeResult.TooNew -> {
                    blockedByTooNew = true
                    LoadResult.TooNew(decoded.version)
                }
                is DecodeResult.Corrupt -> LoadResult.Corrupt
                is DecodeResult.Success -> {
                    seed = decoded.save.seed
                    _state.value = HeroBootstrap.ensureStarter(decoded.save.state)
                    _revision.value++
                    dirty = true
                    advanceLocked()
                    if (recovered) LoadResult.RecoveredFromBackup else LoadResult.Loaded
                }
            }
        }

    /** 新規ゲームを開始して即時保存する。TooNew 検出後は呼べない。 */
    suspend fun newGame(
        playerName: String,
        starterHeroId: String,
        seed: Long,
    ): GameState =
        mutex.withLock {
            check(!blockedByTooNew) { "cannot start a new game over a newer save" }
            val state = NewGame.create(data, clock.nowEpochSec(), seed, playerName, starterHeroId)
            this.seed = seed
            _state.value = state
            _revision.value++
            _offlineSummary.value = null
            dirty = true
            saveLocked()
            state
        }

    /** 現在時刻まで進めてから [action] を適用する。成功なら状態を更新してイベントを流す。 */
    suspend fun dispatch(action: (GameState) -> ActionResult): ActionResult =
        mutex.withLock {
            advanceLocked()
            val current = _state.value ?: return@withLock ActionResult.Failure(ActionFailure.InvalidArgument)
            val result = action(current)
            if (result is ActionResult.Success) {
                _state.value = Tracking.apply(result.state, result.events, data)
                _revision.value++
                result.events.forEach { _events.tryEmit(it) }
                dirty = true
                maybeSaveLocked()
            }
            result
        }

    /** 現在時刻まで進める。時計が巻き戻っていれば何もしない。 */
    suspend fun advanceToNow() {
        mutex.withLock {
            advanceLocked()
            maybeSaveLocked()
        }
    }

    /** 1 秒ごとに [advanceToNow] を呼ぶ。前景にいる間だけ（呼び出しは :app）。 */
    fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob =
            scope.launch {
                while (isActive) {
                    delay(TICK_MILLIS)
                    advanceToNow()
                }
            }
    }

    /** ticking を止める。 */
    fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
    }

    /** 変更があれば即時保存する（:app の onStop から呼ぶ）。 */
    suspend fun flush() {
        mutex.withLock { if (dirty && _state.value != null) saveLocked() }
    }

    private suspend fun advanceLocked() {
        val s = _state.value ?: return
        val now = clock.nowEpochSec()
        if (now <= s.nowEpochSec) return
        val result = OfflineCatchUp.run(s, now, data)
        _state.value = Tracking.apply(result.state, result.events, data)
        result.events.forEach { _events.tryEmit(it) }
        dirty = true
        if (result.summary.elapsedSec >= data.rules.offlineSummaryMinSec) {
            _offlineSummary.value = result.summary
        }
    }

    private suspend fun maybeSaveLocked() {
        if (!dirty) return
        val sinceLastSave = clock.nowEpochSec() - lastSaveEpochSec
        // 時計が巻き戻されて差が負のときは、巻き戻し分の時間だけ保存が止まらないよう即保存する
        if (sinceLastSave < 0 || sinceLastSave >= SAVE_INTERVAL_SEC) saveLocked()
    }

    private suspend fun saveLocked() {
        val s = _state.value ?: return
        val now = clock.nowEpochSec()
        val text =
            withContext(defaultDispatcher) {
                SaveCodec.encode(SaveGame(savedAtEpochSec = now, seed = seed, state = s))
            }
        try {
            withContext(ioDispatcher) { storage.writeAtomic(text) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 書き込み失敗（容量不足など）でセッションを落とさない。dirty のまま残し、次の間隔で再試行する。
            lastSaveEpochSec = now
            return
        }
        dirty = false
        lastSaveEpochSec = now
    }

    private companion object {
        const val EVENT_BUFFER = 64
        const val TICK_MILLIS = 1_000L
        const val SAVE_INTERVAL_SEC = 5L
    }
}
