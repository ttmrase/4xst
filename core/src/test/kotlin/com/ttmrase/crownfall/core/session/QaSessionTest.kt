package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.save.DecodeResult
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.Clock
import com.ttmrase.crownfall.core.util.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 書き込みを失敗させられる SaveStorage。 */
private class FlakyStorage(var failWrites: Boolean = false) : SaveStorage {
    var primary: String? = null
    var backup: String? = null
    var writes = 0

    override fun readPrimary() = primary

    override fun readBackup() = backup

    override fun writeAtomic(text: String) {
        if (failWrites) throw IOException("disk full")
        backup = primary ?: backup
        primary = text
        writes++
    }

    override fun quarantinePrimary() {
        primary = null
    }
}

/** QA による GameSession の境界テスト（保存・復旧・時計・同時実行）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaSessionTest {
    private val data = TestSupport.data

    private fun TestScope.session(
        storage: SaveStorage,
        clock: Clock,
    ): GameSession {
        val d = StandardTestDispatcher(testScheduler)
        return GameSession(data, clock, storage, backgroundScope, d, d)
    }

    @Test
    fun savingResumesAfterClockIsSetBackBeyondTheThrottleWindow() =
        runTest {
            val storage = FlakyStorage()
            val clock = FixedClock(T0)
            val s = session(storage, clock)
            s.newGame("A", "hero_aldric", 1)
            assertEquals(1, storage.writes)
            // ユーザーが時計を 1 時間戻した。以後の変更は「新しい時計で 5 秒後」には保存されるべき。
            clock.epochSec = T0 - 3600
            s.dispatch { Actions.addGems(it, 1) }
            clock.advance(10)
            s.dispatch { Actions.addGems(it, 1) }
            assertTrue(storage.writes >= 2, "no autosave for 10s after clock rewind (writes=${storage.writes})")
        }

    @Test
    fun writeFailureDoesNotLoseStateOrBreakDispatch() =
        runTest {
            val storage = FlakyStorage()
            val clock = FixedClock(T0)
            val s = session(storage, clock)
            s.newGame("A", "hero_aldric", 1)
            storage.failWrites = true
            clock.advance(10)
            val result = runCatching { s.dispatch { Actions.addGems(it, 7) } }
            assertTrue(result.isSuccess, "dispatch threw on write failure: ${result.exceptionOrNull()}")
            assertEquals(207, s.state.value?.gems) // 状態はメモリ上で進んでいる
            // ディスクが復活したら次の flush で保存される
            storage.failWrites = false
            s.flush()
            val saved = SaveCodec.decode(assertNotNull(storage.primary))
            assertIs<DecodeResult.Success>(saved)
            assertEquals(207, saved.save.state.gems)
        }

    @Test
    fun tickingSurvivesTransientWriteFailure() =
        runTest {
            val storage = FlakyStorage()
            val clock = FixedClock(T0)
            val s = session(storage, clock)
            s.newGame("A", "hero_aldric", 1)
            storage.failWrites = true
            s.startTicking()
            repeat(8) {
                clock.advance(1)
                testScheduler.advanceTimeBy(1_000)
                testScheduler.runCurrent()
            }
            storage.failWrites = false
            val before = s.state.value?.nowEpochSec
            clock.advance(1)
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            assertEquals(before!! + 1, s.state.value?.nowEpochSec, "ticking stopped after write failure")
            s.stopTicking()
        }

    @Test
    fun brokenPrimaryShapesAreAllTreatedAsCorruptAndRecoverFromBackup() =
        runTest {
            val clock = FixedClock(T0)
            val good = FlakyStorage()
            session(good, clock).newGame("A", "hero_aldric", 1)
            val goodText = assertNotNull(good.primary)
            val half = goodText.take(goodText.length / 2)
            val shapes =
                listOf(
                    "",
                    " ",
                    "null",
                    "[]",
                    "42",
                    "{",
                    half,
                    """{"version":"x"}""",
                )
            for (broken in shapes) {
                val storage =
                    FlakyStorage().also {
                        it.primary = broken
                        it.backup = goodText
                    }
                val s = session(storage, clock)
                assertEquals(LoadResult.RecoveredFromBackup, s.load(), "primary=<$broken>")
                assertEquals("A", s.state.value?.playerName)
            }
        }

    @Test
    fun bothCorruptReportsCorruptWritesNothingAndAllowsNewGame() =
        runTest {
            val storage =
                FlakyStorage().also {
                    it.primary = "{"
                    it.backup = "}"
                }
            val s = session(storage, FixedClock(T0))
            assertEquals(LoadResult.Corrupt, s.load())
            assertEquals(0, storage.writes)
            s.flush()
            s.advanceToNow()
            assertEquals(0, storage.writes)
            s.newGame("B", "hero_aldric", 5)
            assertEquals(1, storage.writes)
        }

    @Test
    fun concurrentDispatchesAreSerialized() =
        runTest {
            val storage = FlakyStorage()
            val s = session(storage, FixedClock(T0))
            s.newGame("A", "hero_aldric", 1)
            val start = s.state.value!!.gems
            (1..50).map { async { s.dispatch { Actions.addGems(it, 1) } } }.awaitAll()
            assertEquals(start + 50, s.state.value?.gems)
        }

    @Test
    fun loadedSessionFlushWritesAndKeepsBackupOfPreviousSave() =
        runTest {
            val clock = FixedClock(T0)
            val storage = FlakyStorage()
            session(storage, clock).newGame("A", "hero_aldric", 1)
            val first = assertNotNull(storage.primary)
            clock.advance(100)
            val s = session(storage, clock)
            assertEquals(LoadResult.Loaded, s.load())
            s.flush()
            assertEquals(first, storage.backup)
            val saved = SaveCodec.decode(assertNotNull(storage.primary))
            assertIs<DecodeResult.Success>(saved)
            assertEquals(T0 + 100, saved.save.state.nowEpochSec)
            assertEquals(1L, saved.save.seed)
            assertTrue(s.dispatch { Actions.addGems(it, 1) }.ok().gems > 0)
        }

    @Test
    fun missingPrimaryWithBackupOnlyRecoversAndNextSaveRestoresPrimary() =
        runTest {
            val clock = FixedClock(T0)
            val seedStorage = FlakyStorage()
            session(seedStorage, clock).newGame("A", "hero_aldric", 3)
            // primary → bak へ回した直後・置換前にクラッシュした状態
            val storage =
                FlakyStorage().also {
                    it.primary = null
                    it.backup = seedStorage.primary
                }
            val s = session(storage, clock)
            assertEquals(LoadResult.RecoveredFromBackup, s.load())
            assertEquals("A", s.state.value?.playerName)
            s.flush()
            assertEquals(1, storage.writes)
            assertIs<DecodeResult.Success>(SaveCodec.decode(assertNotNull(storage.primary)))
        }

    @Test
    fun freshlyLoadedSessionIsDirtyAndFlushWritesOnce() =
        runTest {
            val clock = FixedClock(T0)
            val storage = FlakyStorage()
            session(storage, clock).newGame("A", "hero_aldric", 1)
            val s = session(storage, clock)
            assertEquals(LoadResult.Loaded, s.load())
            val before = storage.writes
            s.flush()
            assertEquals(before + 1, storage.writes)
            s.flush()
            assertEquals(before + 1, storage.writes)
        }
}
