package com.ttmrase.crownfall.core.hero

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.TestSupport.T0
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.model.SummonResult
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.save.DecodeResult
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.LoadResult
import com.ttmrase.crownfall.core.session.SaveStorage
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.FixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** QA による召喚の天井・10 連保証と、M2 セーブの読み込み補完の境界テスト。 */
@OptIn(ExperimentalCoroutinesApi::class)
class QaHeroTest {
    private val data = TestSupport.data

    private fun tavern(): GameState =
        TestSupport.withLevel(TestSupport.newGame(), BuildingType.TAVERN, 1).copy(gems = 100_000_000, resources = RICH)

    private fun only(
        rarity: HeroRarity,
        pityAt: Int = 100,
    ): GameData {
        val rates = mapOf(rarity to 10_000)
        val rules = data.heroRules
        return data.copy(
            heroRules =
                rules.copy(
                    premium = rules.premium.copy(rates = rates, pityAt = pityAt),
                    normal = rules.normal.copy(rates = rates),
                ),
        )
    }

    private fun result(r: ActionResult): SummonResult =
        (r as ActionResult.Success).events.filterIsInstance<GameEvent.HeroSummoned>().single().result

    @Test
    fun pityAcrossMixedSingleAndTenRollsHitsSsrExactlyEveryHundredthPull() {
        val d = only(HeroRarity.N)
        val rnd = java.util.Random(5)
        var s = tavern()
        val all = ArrayList<HeroRarity>()
        while (all.size < 3000) {
            val times = if (rnd.nextInt(3) == 0) 10 else 1
            val r = Actions.summon(s, SummonKind.PREMIUM, times, d)
            all.addAll(result(r).results.map { it.rarity })
            s = r.ok()
        }
        val ssrAt = all.indices.filter { all[it] == HeroRarity.SSR }.map { it + 1 }
        assertTrue(ssrAt.isNotEmpty())
        // 1 始まりの累計回数で、100 の倍数の回だけが SSR（10 連保証の差し替えは SR で、pity を壊さない）
        assertEquals((1..all.size / 100).map { it * 100 }, ssrAt.filter { it <= (all.size / 100) * 100 })
        assertEquals(all.size % 100, s.summonPity)
    }

    @Test
    fun tenRollWherePityLandsOnTheLastSlotKeepsTheSsrAndResetsPity() {
        val d = only(HeroRarity.N)
        val res = result(Actions.summon(tavern().copy(summonPity = 90), SummonKind.PREMIUM, 10, d))
        assertEquals(List(9) { HeroRarity.N } + HeroRarity.SSR, res.results.map { it.rarity })
        assertEquals(0, res.pityAfter)
    }

    @Test
    fun tenRollWherePityLandsMidRollCountsTheRestFromZero() {
        val d = only(HeroRarity.N)
        // pity=95 → 5 回目が SSR、残り 5 回は N（SSR があるので SR 保証の差し替えは起きない）
        val res = result(Actions.summon(tavern().copy(summonPity = 95), SummonKind.PREMIUM, 10, d))
        val expected = List(4) { HeroRarity.N } + HeroRarity.SSR + List(5) { HeroRarity.N }
        assertEquals(expected, res.results.map { it.rarity })
        assertEquals(5, res.pityAfter)
    }

    @Test
    fun guaranteedSlotDoesNotResetOrAdvancePityIncorrectly() {
        val d = only(HeroRarity.N, pityAt = 100)
        val res = result(Actions.summon(tavern().copy(summonPity = 10), SummonKind.PREMIUM, 10, d))
        assertEquals(HeroRarity.SR, res.results.last().rarity)
        assertEquals(20, res.pityAfter) // SR は SSR ではないので 10 回ぶんそのまま加算
    }

    @Test
    fun normalSummonHasNoPityAndNeverTouchesTheCounter() {
        val s = tavern().copy(summonPity = 42)
        val after = Actions.summon(s, SummonKind.NORMAL, 10, only(HeroRarity.N)).ok()
        assertEquals(42, after.summonPity)
    }

    // ───── M2 セーブの読み込み ─────

    private class MemStorage(var primary: String?) : SaveStorage {
        var writes = 0

        override fun readPrimary() = primary

        override fun readBackup(): String? = null

        override fun writeAtomic(text: String) {
            primary = text
            writes++
        }

        override fun quarantinePrimary() {
            primary = null
        }
    }

    /** M3 で増えたフィールドを取り除いて M2 形式のセーブ JSON にする。 */
    private fun asM2Save(text: String): String {
        val root = Json.parseToJsonElement(text).jsonObject
        val state = root.getValue("state").jsonObject
        val m3Keys = setOf("heroes", "heroShards", "summonPity", "troops", "wounded", "research", "foodDepleted")
        val trimmed = JsonObject(state.filterKeys { it !in m3Keys })
        return JsonObject(root + ("state" to trimmed)).toString()
    }

    @Test
    fun m2SaveWithoutM3FieldsLoadsWithDefaultsAndTheStarterHero() =
        runTest {
            val clock = FixedClock(T0)
            val d = StandardTestDispatcher(testScheduler)
            val seed = MemStorage(null)
            GameSession(data, clock, seed, backgroundScope, d, d).newGame("Alice", "hero_seren", 7)
            val m2 = asM2Save(assertNotNull(seed.primary))
            assertTrue("heroes" !in m2.substringAfter("\"state\""), "heroes key must be stripped")
            // 補完前の M2 形式として単体でもデコードできる
            val decoded = SaveCodec.decode(m2)
            assertIs<DecodeResult.Success>(decoded)
            assertEquals(emptyList(), decoded.save.state.heroes)

            val storage = MemStorage(m2)
            val session = GameSession(data, clock, storage, backgroundScope, d, d)
            assertEquals(LoadResult.Loaded, session.load())
            val state = assertNotNull(session.state.value)
            assertEquals(listOf(HeroState("hero_seren")), state.heroes)
            assertEquals(0, state.summonPity)
            assertTrue(state.troops.isEmpty() && state.wounded.isEmpty() && state.research.isEmpty())
            session.flush()
            // 保存し直した後は heroes が入った現行形式になり、再読込しても二重付与されない
            val reloaded = GameSession(data, clock, storage, backgroundScope, d, d)
            assertEquals(LoadResult.Loaded, reloaded.load())
            assertEquals(listOf(HeroState("hero_seren")), reloaded.state.value?.heroes)
        }
}
