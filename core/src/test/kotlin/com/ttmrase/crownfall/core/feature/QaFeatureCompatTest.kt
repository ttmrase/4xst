package com.ttmrase.crownfall.core.feature

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.FeatureProgress
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.save.DecodeResult
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.save.SaveGame
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.InMemorySaveStorage
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.util.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M11 の DoD（QA）: 1.1.x のセーブ（`features` を持たない JSON。城 Lv 2 / 5 / 12 の 3 種類）を、実際の読み込みの経路
 * （SaveCodec → GameSession）で読み込み、長く開けなかった場合も含めて次を確かめる。
 * 解放の演出の洪水が起きない、初回の使用の報酬が二重に取れない、同盟（Lv4 未満は止まり、以上はそのまま）・襲撃・イベントが
 * 城 Lv どおりに動く。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaFeatureCompatTest {
    private val data = TestSupport.data
    private val hour = 3600L
    private val day = 86_400L
    private val t0 = 20_000L * day

    /** 1.1.x のセーブ相当: 同盟が動いていて、襲撃が 1 時間後に予定されている、城 Lv [castle] の状態。 */
    private fun old11(castle: Int): GameState {
        // 同盟のスケジューラを動かしておく（1.1.x は Lv に関係なく動いていた）ために、いったん Lv12 で進める
        val base = TestSupport.advanced(level = 12, castle = 12, now = t0).copy(resources = TestSupport.RICH)
        val running =
            Simulation.tick(
                base.copy(map = base.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4)),
                3 * day,
                data,
            )
        var s = running.state
        assertNotEquals(0L, s.alliance.seed, "the alliance must be running in the 1.1.x save")
        s = TestSupport.withLevel(s, BuildingType.CASTLE, castle)
        s = s.copy(map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + hour), features = FeatureProgress())
        return s
    }

    /** `features` の項目を持たない（1.1.x の形の）JSON を作って、実際のデコードを通す。 */
    private fun decodeAsOldSave(state: GameState): SaveGame {
        val text = SaveCodec.encode(SaveGame(savedAtEpochSec = state.nowEpochSec, seed = 42, state = state))
        val root = Json.parseToJsonElement(text).jsonObject
        val st = root.getValue("state").jsonObject
        assertTrue("features" in st, "the current encoder writes the features key")
        val stripped = JsonObject(root + ("state" to JsonObject(st - "features"))).toString()
        assertFalse("\"celebrated\"" in stripped, "no feature progress in the old-format JSON")
        val result = SaveCodec.decode(stripped)
        assertIs<DecodeResult.Success>(result)
        return result.save
    }

    private fun unlockedIds(s: GameState) = FeatureGate.unlockedSet(s, data).map { it.id }.toSet()

    @Test
    fun anOldSaveDecodesToUninitializedProgressAndInitializesWithoutAFloodOrARewardAtEveryAbsence() {
        for (castle in listOf(2, 5, 12)) {
            val save = decodeAsOldSave(old11(castle))
            val old = save.state
            assertEquals(FeatureProgress(), old.features, "castle=$castle")
            val ids = unlockedIds(old)
            for (away in listOf(1L, hour, day, 30 * day)) {
                val r = Simulation.tick(old, away, data)
                val tag = "castle=$castle away=$away"
                assertTrue(r.events.none { it is GameEvent.FeatureUnlocked }, "$tag: no unlock ceremony flood")
                val f = r.state.features
                assertTrue(f.initialized, tag)
                assertEquals(ids, f.celebrated, tag)
                assertEquals(ids, f.firstUsed, tag)
                assertEquals(ids, f.firstUseClaimed, tag)
                for (feature in Feature.entries) {
                    assertFalse(FeatureGate.rewardClaimable(r.state, data, feature), "$tag: ${feature.id} reward twice")
                }
                // 初期化そのものは何も与えない: 最初から初期化済みの同じ状態を進めた結果と、機能の印以外は同じ
                val control =
                    Simulation.tick(
                        old.copy(features = FeatureProgress(ids, ids, ids, initialized = true)),
                        away,
                        data,
                    )
                assertEquals(control.state, r.state, "$tag: initializing must not change anything else")
                assertEquals(control.state.gems, r.state.gems, tag)
            }
        }
    }

    @Test
    fun theAllianceStopsBelowLevelFourAndKeepsRunningFromLevelFour() {
        for (castle in listOf(2, 5, 12)) {
            val old = decodeAsOldSave(old11(castle)).state
            val after = Simulation.tick(old, 3 * day, data).state
            if (castle < 4) {
                assertEquals(old.alliance, after.alliance, "castle=$castle: the alliance is frozen below Lv4")
            } else {
                assertNotEquals(old.alliance, after.alliance, "castle=$castle: the alliance keeps running")
            }
        }
    }

    @Test
    fun raidsAndWeeklyEventsFollowTheCastleLevel() {
        for (castle in listOf(2, 5, 12)) {
            val old = decodeAsOldSave(old11(castle)).state
            val after = Simulation.tick(old, 2 * hour, data).state
            if (castle < 5) {
                assertEquals(old.map.nextRaidEpochSec, after.map.nextRaidEpochSec, "castle=$castle: no raid below Lv5")
                assertTrue(after.mails.size <= old.mails.size, "castle=$castle: no raid mail below Lv5")
            } else {
                assertNotEquals(old.map.nextRaidEpochSec, after.map.nextRaidEpochSec, "castle=$castle: raid runs")
            }
            val week = Simulation.tick(old, 9 * day, data).state.event.weekId
            if (castle < 8) {
                assertEquals(old.event.weekId, week, "castle=$castle: no weekly event below Lv8")
            } else {
                assertNotEquals(-1L, week, "castle=$castle: the weekly event runs from Lv8")
            }
        }
    }

    @Test
    fun loadingThroughTheSessionAfterAMonthAwayInitializesOnceAndKeepsTheCaps() =
        runTest {
            for (castle in listOf(2, 5, 12)) {
                val save = decodeAsOldSave(old11(castle))
                val text = SaveCodec.encode(save)
                val storage = InMemorySaveStorage(primary = text)
                val clock = FixedClock(save.savedAtEpochSec + 30 * day)
                val d = StandardTestDispatcher(testScheduler)
                val session = GameSession(data, clock, storage, backgroundScope, d, d)
                session.load()
                val state = assertNotNull(session.state.value, "castle=$castle")
                assertTrue(state.features.initialized, "castle=$castle")
                assertEquals(unlockedIds(state), state.features.celebrated, "castle=$castle")
                assertEquals(unlockedIds(state), state.features.firstUseClaimed, "castle=$castle")
                // 同じセーブをもう一度読んでも、同じ結果（読み込みを繰り返しても報酬や演出が増えない）
                val again =
                    GameSession(
                        data,
                        FixedClock(clock.epochSec),
                        InMemorySaveStorage(primary = text),
                        backgroundScope,
                        d,
                        d,
                    )
                again.load()
                assertEquals(state.features, assertNotNull(again.state.value).features, "castle=$castle")
            }
        }
}
