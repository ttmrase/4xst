package com.ttmrase.crownfall.core.session

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.Clock
import com.ttmrase.crownfall.core.util.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GameSessionTest {
    private val data = TestSupport.data

    private fun TestScope.session(
        storage: InMemorySaveStorage,
        clock: Clock,
    ): GameSession {
        val d = StandardTestDispatcher(testScheduler)
        return GameSession(data, clock, storage, backgroundScope, d, d)
    }

    @Test
    fun newGameSavesAndLoadsBack() =
        runTest {
            val storage = InMemorySaveStorage()
            val clock = FixedClock(T0)
            val s1 = session(storage, clock)
            val created = s1.newGame("Alice", "hero_aldric", 99)
            assertEquals("Alice", created.playerName)
            assertEquals("hero_aldric", created.starterHeroId)
            assertEquals(99, created.rngState)
            assertEquals(1, storage.writes)

            val s2 = session(storage, clock)
            assertEquals(LoadResult.Loaded, s2.load())
            assertEquals(created, s2.state.value)
        }

    @Test
    fun noSaveAndCorrupt() =
        runTest {
            val clock = FixedClock(T0)
            assertEquals(LoadResult.NoSave, session(InMemorySaveStorage(), clock).load())
            assertEquals(LoadResult.Corrupt, session(InMemorySaveStorage(primary = "garbage"), clock).load())
            val both = InMemorySaveStorage(primary = "garbage", backup = "{also bad")
            assertEquals(LoadResult.Corrupt, session(both, clock).load())
            assertEquals(1, both.quarantined)
            val backupOnlyBad = InMemorySaveStorage(primary = null, backup = "{also bad")
            assertEquals(LoadResult.Corrupt, session(backupOnlyBad, clock).load())
        }

    @Test
    fun recoversFromBackupWhenPrimaryIsCorrupt() =
        runTest {
            val clock = FixedClock(T0)
            val storage = InMemorySaveStorage()
            session(storage, clock).newGame("Bob", "hero_seren", 1)
            val good = storage.primary
            val broken = InMemorySaveStorage(primary = "{truncated", backup = good)
            val s = session(broken, clock)
            assertEquals(LoadResult.RecoveredFromBackup, s.load())
            assertEquals("Bob", s.state.value?.playerName)
            assertEquals(1, broken.quarantined)
            // 現行が無くバックアップだけ（rename の合間にクラッシュ）でも復旧できる
            val onlyBackup = InMemorySaveStorage(primary = null, backup = good)
            assertEquals(LoadResult.RecoveredFromBackup, session(onlyBackup, clock).load())
        }

    @Test
    fun tooNewNeverWrites() =
        runTest {
            val clock = FixedClock(T0)
            val newer = """{"version":2,"savedAtEpochSec":1,"seed":1,"state":{}}"""
            val storage = InMemorySaveStorage(primary = newer)
            val s = session(storage, clock)
            assertEquals(LoadResult.TooNew(2), s.load())
            assertNull(s.state.value)
            s.flush()
            s.advanceToNow()
            assertEquals(0, storage.writes)
            assertEquals(newer, storage.primary)
            assertFailsWith<IllegalStateException> { s.newGame("x", "hero_aldric", 1) }
            // 現行が壊れていて backup が新しい版でも書かない
            val viaBackup = InMemorySaveStorage(primary = "bad", backup = newer)
            assertEquals(LoadResult.TooNew(2), session(viaBackup, clock).load())
            assertEquals(0, viaBackup.writes)
        }

    @Test
    fun offlineSummaryAppearsOnlyAtThreshold() =
        runTest {
            val storage = InMemorySaveStorage()
            val clock = FixedClock(T0)
            session(storage, clock).newGame("A", "hero_aldric", 1)

            clock.advance(299)
            val shortAway = session(storage, clock)
            shortAway.load()
            assertNull(shortAway.offlineSummary.value)

            val storage2 = InMemorySaveStorage()
            val c2 = FixedClock(T0)
            session(storage2, c2).newGame("A", "hero_aldric", 1)
            c2.advance(300)
            val away = session(storage2, c2)
            away.load()
            val summary = assertNotNull(away.offlineSummary.value)
            assertEquals(300, summary.elapsedSec)
            away.consumeOfflineSummary()
            assertNull(away.offlineSummary.value)
        }

    @Test
    fun dispatchAdvancesTimeFirst() =
        runTest {
            val clock = FixedClock(T0)
            val s = session(InMemorySaveStorage(), clock)
            s.newGame("A", "hero_aldric", 1)
            clock.advance(100)
            val r = s.dispatch { Actions.addGems(it, 5) }
            assertEquals(T0 + 100, r.ok().nowEpochSec)
            assertEquals(T0 + 100, s.state.value?.nowEpochSec)
            assertEquals(205, s.state.value?.gems)
        }

    @Test
    fun failedActionKeepsStateButStillAdvancesTime() =
        runTest {
            val clock = FixedClock(T0)
            val s = session(InMemorySaveStorage(), clock)
            s.newGame("A", "hero_aldric", 1)
            clock.advance(10)
            assertEquals(ActionFailure.SlotNotFound, s.dispatch { Actions.startUpgrade(it, 999, data) }.failure())
            assertEquals(T0 + 10, s.state.value?.nowEpochSec)
            val unloaded = session(InMemorySaveStorage(), clock)
            assertEquals(ActionFailure.InvalidArgument, unloaded.dispatch { Actions.addGems(it, 1) }.failure())
        }

    @Test
    fun clockRewindDoesNothing() =
        runTest {
            val clock = FixedClock(T0)
            val s = session(InMemorySaveStorage(), clock)
            s.newGame("A", "hero_aldric", 1)
            clock.advance(50)
            s.advanceToNow()
            val before = s.state.value
            clock.epochSec = T0 - 1_000
            s.advanceToNow()
            assertEquals(before, s.state.value)
            assertEquals(T0 + 50, s.dispatch { Actions.addGems(it, 1) }.ok().nowEpochSec)
        }

    @Test
    fun savesAreThrottledToFiveSecondsAndFlushIsImmediate() =
        runTest {
            val storage = InMemorySaveStorage()
            val clock = FixedClock(T0)
            val s = session(storage, clock)
            s.newGame("A", "hero_aldric", 1)
            assertEquals(1, storage.writes)
            clock.advance(1)
            s.dispatch { Actions.addGems(it, 1) }
            clock.advance(3)
            s.dispatch { Actions.addGems(it, 1) }
            assertEquals(1, storage.writes) // 4 秒後はまだ保存しない
            clock.advance(1)
            s.dispatch { Actions.addGems(it, 1) }
            assertEquals(2, storage.writes) // 5 秒経過で保存
            clock.advance(1)
            s.dispatch { Actions.addGems(it, 1) }
            assertEquals(2, storage.writes)
            s.flush()
            assertEquals(3, storage.writes)
            s.flush()
            assertEquals(3, storage.writes) // 変更が無ければ何もしない
            val saved =
                (
                    com.ttmrase.crownfall.core.save.SaveCodec.decode(
                        storage.primary!!,
                    )
                ) as com.ttmrase.crownfall.core.save.DecodeResult.Success
            assertEquals(s.state.value, saved.save.state)
        }

    @Test
    fun tickingAdvancesOncePerSecondAndStops() =
        runTest {
            val clock = Clock { T0 + testScheduler.currentTime / 1000 }
            val s = session(InMemorySaveStorage(), clock)
            s.newGame("A", "hero_aldric", 1)
            s.startTicking()
            s.startTicking() // 二重起動しない
            advanceTimeBy(3_500)
            runCurrent()
            assertEquals(T0 + 3, s.state.value?.nowEpochSec)
            s.stopTicking()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(T0 + 3, s.state.value?.nowEpochSec)
        }

    @Test
    fun eventsAreEmittedWhenTimeAdvancesPastACompletion() =
        runTest {
            val clock = FixedClock(T0)
            val s = session(InMemorySaveStorage(), clock)
            s.newGame("A", "hero_aldric", 1)
            val seen = mutableListOf<GameEvent>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.events.collect { seen.add(it) } }
            val castle = TestSupport.slotOf(s.state.value!!, BuildingType.CASTLE)
            s.dispatch { Actions.startUpgrade(it, castle, data) }
            clock.advance(60)
            s.advanceToNow()
            assertTrue(seen.any { it is GameEvent.BuildingLevelUp && it.newLevel == 2 }, "$seen")
            assertIs<GameEvent.TimerCompleted>(seen.first { it is GameEvent.TimerCompleted })
        }

    @Test
    fun loadedSessionCatchesUpProduction() =
        runTest {
            val storage = InMemorySaveStorage()
            val clock = FixedClock(T0)
            val s1 = session(storage, clock)
            val created = s1.newGame("A", "hero_aldric", 1)
            clock.advance(3600)
            val s2 = session(storage, clock)
            s2.load()
            assertEquals(created.resources.food + 300, s2.state.value?.resources?.food)
            assertEquals(Resources.ZERO, Resources.ZERO)
        }

    @Test
    fun loadingAnM2SaveGrantsTheStarterHero() =
        runTest {
            // M2 のセーブ: starterHeroId はあるが heroes が空
            val legacy = TestSupport.newGame().copy(playerName = "Old", starterHeroId = "hero_seren")
            val text =
                com.ttmrase.crownfall.core.save.SaveCodec.encode(
                    com.ttmrase.crownfall.core.save.SaveGame(savedAtEpochSec = T0, seed = 3, state = legacy),
                )
            val s = session(InMemorySaveStorage(primary = text), FixedClock(T0))
            assertEquals(LoadResult.Loaded, s.load())
            assertEquals(listOf("hero_seren"), s.state.value?.heroes?.map { it.heroId })
        }

    @Test
    fun newGameGrantsTheStarterHero() =
        runTest {
            val s = session(InMemorySaveStorage(), FixedClock(T0))
            val created = s.newGame("A", "hero_brann", 7)
            assertEquals(listOf("hero_brann"), created.heroes.map { it.heroId })
        }
}
