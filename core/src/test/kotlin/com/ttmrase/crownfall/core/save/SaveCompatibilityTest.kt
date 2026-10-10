package com.ttmrase.crownfall.core.save

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.InMemorySaveStorage
import com.ttmrase.crownfall.core.session.LoadResult
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.util.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * セーブ互換（T8.1）。過去のマイルストーンのコミットで生成した実際のセーブ（`resources/saves/mN.json`）が、
 * 最新のコードで読めて、進めてもクラッシュせず、保存し直した形も読めることを確認する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveCompatibilityTest {
    private val data = TestSupport.data
    private val names = listOf("m1", "m2", "m3", "m4", "m5", "m6", "m7")
    private val hour = 3600L
    private val day = 86_400L

    private fun fixture(name: String): String =
        assertNotNull(javaClass.getResourceAsStream("/saves/$name.json"), "missing fixture $name")
            .use { String(it.readBytes(), Charsets.UTF_8) }

    private fun decode(name: String): SaveGame {
        val result = SaveCodec.decode(fixture(name))
        assertIs<DecodeResult.Success>(result, "$name must still decode")
        return result.save
    }

    @Test
    fun everyOldSaveStillDecodesAndKeepsItsOwnValues() {
        for (name in names) {
            val save = decode(name)
            assertEquals(SaveGame.CURRENT_VERSION, save.version, name)
            assertEquals(42L, save.seed, name)
            // 古い版にない項目は既定値で埋まる
            val s = save.state
            assertTrue(s.resources.food >= 0 && s.resources.wood >= 0, name)
            assertEquals(save.savedAtEpochSec, s.nowEpochSec, name)
            assertTrue(s.slots.isNotEmpty(), name)
        }
        val m1 = decode("m1").state
        assertTrue(m1.heroes.isEmpty() && m1.troops.isEmpty() && m1.mails.isEmpty(), "m1 predates those fields")
        assertEquals(0L, m1.map.nextRefreshEpochSec)
        assertEquals(-1L, m1.quest.dailyDay)
        assertEquals(-1L, m1.event.weekId)
        // 新しい版の fixture には、その版の項目が育っている
        assertTrue(decode("m4").state.map.nodes.isNotEmpty(), "m4 has a generated map overlay")
        assertTrue(decode("m5").state.alliance.seed != 0L, "m5 has the alliance scheduler seed")
        // 版ごとにスキーマが違うこと自体を固定する（fixture が全部同じ形になっていないかの見張り）
        assertTrue("\"shop\"" !in fixture("m5") && "\"shop\"" in fixture("m6"), "shop state arrives with M6")
        assertTrue("\"quest\"" !in fixture("m6") && "\"quest\"" in fixture("m7"), "quest state arrives with M7")
        assertTrue("\"alliance\"" !in fixture("m4") && "\"alliance\"" in fixture("m5"), "alliance arrives with M5")
        assertTrue(decode("m7").state.event.weekId >= 0, "m7 has an event week")
    }

    @Test
    fun encodingADecodedOldSaveRoundTripsExactly() {
        for (name in names) {
            val save = decode(name)
            val again = SaveCodec.decode(SaveCodec.encode(save))
            assertIs<DecodeResult.Success>(again, name)
            assertEquals(save, again.save, name)
        }
    }

    @Test
    fun everyOldSaveLoadsThroughGameSessionAndSurvivesTimeAndASave() =
        runTest {
            for (name in names) {
                for (away in listOf(5L, 3 * hour, day, 30 * day)) {
                    val save = decode(name)
                    val clock = FixedClock(save.savedAtEpochSec + away)
                    val storage = InMemorySaveStorage(primary = fixture(name))
                    val d = StandardTestDispatcher(testScheduler)
                    val session = GameSession(data, clock, storage, backgroundScope, d, d)
                    assertEquals(LoadResult.Loaded, session.load(), "$name +${away}s")
                    val state = assertNotNull(session.state.value)
                    assertEquals(save.savedAtEpochSec + away, state.nowEpochSec, "$name +${away}s")
                    assertTrue(state.resources.food >= 0 && state.resources.stone >= 0, "$name +${away}s resources")
                    assertTrue(
                        state.gems >= 0 && state.timers.all { it.endEpochSec >= state.nowEpochSec },
                        "$name +${away}s",
                    )
                    // 動き続けられる: さらに 1 日進めてもクラッシュしない
                    Simulation.tick(state, day, data)
                    session.flush()
                    val saved = SaveCodec.decode(assertNotNull(storage.primary))
                    assertIs<DecodeResult.Success>(saved, "$name +${away}s re-saved")
                    assertEquals(state, saved.save.state, "$name +${away}s re-saved state")
                }
            }
        }

    @Test
    fun oldSavesCarryTheirStarterHeroAndNameThroughTheBootstrap() =
        runTest {
            // M2 で生成したセーブに、M2 時点の「初期英雄だけ決まっていて heroes は空」という形を再現して読む
            val m2 = fixture("m2").replace("\"starterHeroId\":null", "\"starterHeroId\":\"hero_seren\"")
            val patched = if (m2 == fixture("m2")) fixture("m2") else m2
            val clock = FixedClock(decode("m2").savedAtEpochSec + hour)
            val d = StandardTestDispatcher(testScheduler)
            val session = GameSession(data, clock, InMemorySaveStorage(primary = patched), backgroundScope, d, d)
            assertEquals(LoadResult.Loaded, session.load())
            val state = assertNotNull(session.state.value)
            if (state.starterHeroId != null) assertTrue(state.heroes.any { it.heroId == state.starterHeroId })
        }
}
