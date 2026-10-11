package com.ttmrase.crownfall.core.hero

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.TestSupport.RICH
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.economy.PowerCalculator
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.SummonResult
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.NewGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HeroTest {
    private val data = TestSupport.data

    private fun tavern(gems: Long = 1_000_000): GameState =
        TestSupport.withLevel(
            TestSupport.newGame(castle = 7),
            BuildingType.TAVERN,
            1,
        ).copy(gems = gems, resources = RICH)

    private fun summonResult(r: ActionResult): SummonResult =
        (r as ActionResult.Success).events.filterIsInstance<GameEvent.HeroSummoned>().single().result

    /** 指定のレアリティだけが出るように確率表を差し替えた GameData。 */
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

    // ───── summon ─────

    @Test
    fun sameSeedGivesTheSameResults() {
        val a = Actions.summon(tavern(), SummonKind.PREMIUM, 10, data)
        val b = Actions.summon(tavern(), SummonKind.PREMIUM, 10, data)
        assertEquals(summonResult(a), summonResult(b))
        assertEquals(a.ok(), b.ok())
        val other = Actions.summon(tavern().copy(rngState = 999), SummonKind.PREMIUM, 10, data)
        assertNotEquals(summonResult(a), summonResult(other))
        assertNotEquals(tavern().rngState, a.ok().rngState) // 状態が進む
    }

    @Test
    fun rngStateIsThreadedThroughConsecutiveSummons() {
        val first = Actions.summon(tavern(), SummonKind.PREMIUM, 1, data).ok()
        val second = Actions.summon(first, SummonKind.PREMIUM, 1, data).ok()
        val both = Actions.summon(tavern(), SummonKind.PREMIUM, 10, data).ok()
        assertNotEquals(first.rngState, second.rngState)
        assertEquals(10, summonResult(Actions.summon(tavern(), SummonKind.PREMIUM, 10, data)).results.size)
        assertTrue(both.heroes.isNotEmpty())
    }

    @Test
    fun premiumRatesAreRoughlyTheTable() {
        var s = tavern(gems = 100_000_000)
        val counts = HashMap<HeroRarity, Int>()
        repeat(4000) {
            val r = Actions.summon(s, SummonKind.PREMIUM, 1, data)
            summonResult(r).results.forEach { counts.merge(it.rarity, 1, Int::plus) }
            s = r.ok()
        }
        val ssr = counts[HeroRarity.SSR] ?: 0
        assertTrue(ssr in 40..160, "SSR=$ssr of 4000 (expected ~2% + pity)") // 2% = 80
        assertTrue((counts[HeroRarity.N] ?: 0) in 1800..2200, "N=${counts[HeroRarity.N]}")
        assertTrue((counts[HeroRarity.SR] ?: 0) in 400..650, "SR=${counts[HeroRarity.SR]}")
    }

    @Test
    fun normalSummonNeverGivesSsr() {
        var s = tavern()
        repeat(1500) {
            val r = Actions.summon(s, SummonKind.NORMAL, 1, data)
            assertTrue(summonResult(r).results.none { it.rarity == HeroRarity.SSR })
            s = r.ok()
        }
        assertEquals(0, s.summonPity) // 通常召喚は天井に影響しない
    }

    @Test
    fun pityGuaranteesSsrOnThe100thRoll() {
        val d = only(HeroRarity.N)
        var s = tavern()
        val rarities = ArrayList<HeroRarity>()
        repeat(100) {
            val r = Actions.summon(s, SummonKind.PREMIUM, 1, d)
            rarities.addAll(summonResult(r).results.map { it.rarity })
            s = r.ok()
        }
        assertEquals(List(99) { HeroRarity.N } + HeroRarity.SSR, rarities)
        assertEquals(0, s.summonPity)
        // 101 回目からまた数え直し
        val next = Actions.summon(s, SummonKind.PREMIUM, 1, d).ok()
        assertEquals(1, next.summonPity)
    }

    @Test
    fun pityCounterShowsHowManyRollsRemain() {
        val s = tavern().copy(summonPity = 40)
        assertEquals(60, Summoner.untilPity(s, data))
        assertEquals(null, Summoner.untilPity(s, data, SummonKind.NORMAL))
        val ten = Actions.summon(s, SummonKind.PREMIUM, 10, only(HeroRarity.N)).ok()
        assertEquals(50, ten.summonPity)
        // 99 まで進めた状態の 10 連は、その 1 回目で SSR になり 0 から数え直す
        val edge = tavern().copy(summonPity = 99)
        val res = summonResult(Actions.summon(edge, SummonKind.PREMIUM, 10, only(HeroRarity.N)))
        assertEquals(HeroRarity.SSR, res.results.first().rarity)
        assertEquals(9, res.pityAfter)
    }

    @Test
    fun tenRollGuaranteesSrOrBetterByReplacingTheLastSlot() {
        val d = only(HeroRarity.N, pityAt = 0)
        val res = summonResult(Actions.summon(tavern(), SummonKind.PREMIUM, 10, d))
        assertEquals(9, res.results.take(9).count { it.rarity == HeroRarity.N })
        assertEquals(HeroRarity.SR, res.results.last().rarity)
        // 通常の 10 連は R 以上を保証
        val normal = summonResult(Actions.summon(tavern(), SummonKind.NORMAL, 10, d))
        assertEquals(HeroRarity.R, normal.results.last().rarity)
        // すでに保証を満たしていれば差し替えない
        val sr = summonResult(Actions.summon(tavern(), SummonKind.PREMIUM, 10, only(HeroRarity.SR, 0)))
        assertTrue(sr.results.all { it.rarity == HeroRarity.SR })
    }

    @Test
    fun duplicatesTurnIntoShardsAndNewHeroesStartAtLv1Star1() {
        val d = only(HeroRarity.N, pityAt = 0)
        val s = tavern()
        val r = Actions.summon(s, SummonKind.PREMIUM, 10, d)
        val res = summonResult(r)
        val owned = r.ok().heroes
        assertEquals(res.results.count { it.isNew }, owned.size)
        owned.forEach { assertEquals(HeroState(it.heroId), it) }
        // N の重複 = 5 欠片。N は 3 体しかいないので 10 連（うち 9 枠が N）では必ず重複する
        val dups = res.results.filterNot { it.isNew }
        assertTrue(dups.isNotEmpty())
        dups.filter { it.rarity == HeroRarity.N }.forEach { assertEquals(5, it.shards) }
        val shardTotal = r.ok().heroShards.values.sum()
        assertEquals(dups.sumOf { it.shards }, shardTotal)
        res.results.filter { it.isNew }.forEach { assertEquals(0, it.shards) }
    }

    @Test
    fun alreadyOwnedStarterConvertsToShards() {
        val s = tavern().copy(heroes = listOf(HeroState("hero_tobias")))
        val d = only(HeroRarity.N, pityAt = 0)
        var cur = s
        var shards = 0
        repeat(40) {
            cur = Actions.summon(cur, SummonKind.PREMIUM, 1, d).ok()
        }
        shards = cur.heroShards.getOrDefault("hero_tobias", 0)
        assertEquals(3, cur.heroes.size) // N は 3 体で打ち止め
        assertTrue(shards > 0 && shards % 5 == 0)
    }

    @Test
    fun ticketsArePreferredOverCurrency() {
        val withTickets = tavern().copy(items = mapOf("summon_ticket_premium" to 12, "skill_book" to 1), gems = 10)
        val single = Actions.summon(withTickets, SummonKind.PREMIUM, 1, data).ok()
        assertEquals(11, single.items["summon_ticket_premium"])
        assertEquals(10, single.gems)
        val ten = Actions.summon(withTickets, SummonKind.PREMIUM, 10, data).ok()
        assertEquals(2, ten.items["summon_ticket_premium"])
        // チケットがちょうど尽きる
        val exact =
            Actions.summon(
                withTickets.copy(items = mapOf("summon_ticket_premium" to 10)),
                SummonKind.PREMIUM,
                10,
                data,
            )
        assertEquals(null, exact.ok().items["summon_ticket_premium"])
        // 足りない分は混ぜず、通貨で一括払い（チケットは減らない）
        val short =
            Actions.summon(
                withTickets.copy(items = mapOf("summon_ticket_premium" to 5), gems = 5000),
                SummonKind.PREMIUM,
                10,
                data,
            ).ok()
        assertEquals(5, short.items["summon_ticket_premium"])
        assertEquals(5000 - 2700, short.gems)
    }

    @Test
    fun premiumPaysGemsAndNormalPaysGold() {
        val s = tavern(gems = 5000)
        assertEquals(5000 - 300, Actions.summon(s, SummonKind.PREMIUM, 1, data).ok().gems)
        assertEquals(5000 - 2700, Actions.summon(s, SummonKind.PREMIUM, 10, data).ok().gems)
        val gold = Actions.summon(s, SummonKind.NORMAL, 1, data).ok().resources.gold
        assertEquals(RICH.gold - data.heroRules.normal.gold, gold)
        assertEquals(
            RICH.gold - data.heroRules.normal.goldTen,
            Actions.summon(s, SummonKind.NORMAL, 10, data).ok().resources.gold,
        )
    }

    @Test
    fun summonFailures() {
        assertEquals(
            ActionFailure.NotEnoughGems(300 - 100),
            Actions.summon(tavern(gems = 100), SummonKind.PREMIUM, 1, data).failure(),
        )
        assertEquals(
            ActionFailure.NotEnoughGems(2700 - 2699),
            Actions.summon(tavern(gems = 2699), SummonKind.PREMIUM, 10, data).failure(),
        )
        val poor = tavern().copy(resources = Resources(gold = 500))
        assertEquals(
            ActionFailure.NotEnoughResources(Resources(gold = data.heroRules.normal.gold - 500)),
            Actions.summon(poor, SummonKind.NORMAL, 1, data).failure(),
        )
        assertEquals(ActionFailure.InvalidArgument, Actions.summon(tavern(), SummonKind.PREMIUM, 2, data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.summon(tavern(), SummonKind.PREMIUM, 0, data).failure())
        val noTavern = TestSupport.newGame(castle = 7).copy(gems = 99_999)
        assertEquals(
            ActionFailure.PrereqNotMet(listOf(Prereq(BuildingType.TAVERN, 1))),
            Actions.summon(noTavern, SummonKind.PREMIUM, 1, data).failure(),
        )
    }

    // ───── growth ─────

    private fun owned(
        hero: HeroState = HeroState("hero_aldric"),
        items: Map<String, Int> =
            mapOf("hero_exp_1k" to 10, "hero_exp_5k" to 10, "hero_exp_20k" to 10, "skill_book" to 5),
        shards: Map<String, Int> = emptyMap(),
    ) = TestSupport.newGame(castle = 7).copy(heroes = listOf(hero), items = items, heroShards = shards)

    @Test
    fun expLevelsUpAndCarriesTheRemainder() {
        val need1 = data.heroRules.expToNext[0]
        val need2 = data.heroRules.expToNext[1]
        val r = Actions.useHeroExp(owned(), "hero_aldric", "hero_exp_1k", 1, data).ok()
        val h = r.heroes.single()
        assertTrue(h.level > 1)
        assertEquals(9, r.items["hero_exp_1k"])
        // 手計算: 1000 exp から Lv1→2、Lv2→3 ... を順に引く
        var level = 1
        var exp = 1000L
        while (exp >= data.heroRules.expToNext[level - 1]) {
            exp -= data.heroRules.expToNext[level - 1]
            level++
        }
        assertEquals(HeroState("hero_aldric", level = level, exp = exp), h)
        assertTrue(need1 > 0 && need2 > 0)
    }

    @Test
    fun levelStopsAtTheStarCapAndExcessIsDiscarded() {
        val r = Actions.useHeroExp(owned(), "hero_aldric", "hero_exp_20k", 10, data).ok()
        val h = r.heroes.single()
        assertEquals(20, h.level) // ★1 の上限
        assertEquals(0, h.exp) // 余りは捨てる
        assertEquals(null, r.items["hero_exp_20k"]) // 指定数は消費される
        assertEquals(ActionFailure.MaxLevel, Actions.useHeroExp(r, "hero_aldric", "hero_exp_1k", 1, data).failure())
        assertEquals(10, r.items["hero_exp_5k"]) // 失敗時は消費しない
    }

    @Test
    fun starUpRaisesTheCapAndConsumesShards() {
        val maxed = owned(HeroState("hero_aldric", level = 20), shards = mapOf("hero_aldric" to 25))
        val up = Actions.starUp(maxed, "hero_aldric", data).ok()
        assertEquals(2, up.heroes.single().star)
        assertEquals(5, up.heroShards["hero_aldric"])
        // ★2 なら Lv30 まで上がる
        val grown =
            Actions.useHeroExp(
                up.copy(items = mapOf("hero_exp_20k" to 100)),
                "hero_aldric",
                "hero_exp_20k",
                100,
                data,
            ).ok()
        assertEquals(30, grown.heroes.single().level)
        // ちょうど使い切ると欠片エントリが消える
        val exact = Actions.starUp(owned(shards = mapOf("hero_aldric" to 20)), "hero_aldric", data).ok()
        assertEquals(null, exact.heroShards["hero_aldric"])
    }

    @Test
    fun starUpFailures() {
        assertEquals(ActionFailure.NotEnoughShards(20), Actions.starUp(owned(), "hero_aldric", data).failure())
        assertEquals(
            ActionFailure.NotEnoughShards(1),
            Actions.starUp(owned(shards = mapOf("hero_aldric" to 19)), "hero_aldric", data).failure(),
        )
        assertEquals(
            ActionFailure.MaxStar,
            Actions.starUp(owned(HeroState("hero_aldric", star = 5)), "hero_aldric", data).failure(),
        )
        assertEquals(ActionFailure.HeroNotOwned, Actions.starUp(owned(), "hero_ostrik", data).failure())
    }

    @Test
    fun skillUpConsumesBooksUpToTheMaximum() {
        val s = owned()
        val one = Actions.skillUp(s, "hero_aldric", 1, data).ok()
        assertEquals(listOf(1, 2, 1), one.heroes.single().skillLevels)
        assertEquals(4, one.items["skill_book"])
        var cur = one
        repeat(3) { cur = Actions.skillUp(cur, "hero_aldric", 1, data).ok() }
        assertEquals(5, cur.heroes.single().skillLevels[1])
        assertEquals(ActionFailure.MaxLevel, Actions.skillUp(cur, "hero_aldric", 1, data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.skillUp(s, "hero_aldric", 3, data).failure())
        assertEquals(ActionFailure.InvalidArgument, Actions.skillUp(s, "hero_aldric", -1, data).failure())
        assertEquals(
            ActionFailure.ItemNotOwned,
            Actions.skillUp(owned(items = emptyMap()), "hero_aldric", 0, data).failure(),
        )
        assertEquals(ActionFailure.HeroNotOwned, Actions.skillUp(s, "hero_ostrik", 0, data).failure())
        val last = Actions.skillUp(owned(items = mapOf("skill_book" to 1)), "hero_aldric", 0, data).ok()
        assertEquals(null, last.items["skill_book"])
    }

    @Test
    fun expFailures() {
        val s = owned()
        assertEquals(
            ActionFailure.InvalidArgument,
            Actions.useHeroExp(s, "hero_aldric", "hero_exp_1k", 0, data).failure(),
        )
        assertEquals(ActionFailure.HeroNotOwned, Actions.useHeroExp(s, "hero_ostrik", "hero_exp_1k", 1, data).failure())
        assertEquals(
            ActionFailure.ItemNotOwned,
            Actions.useHeroExp(s, "hero_aldric", "hero_exp_1k", 11, data).failure(),
        )
        assertEquals(ActionFailure.ItemNotOwned, Actions.useHeroExp(s, "hero_aldric", "skill_book", 1, data).failure())
        assertEquals(ActionFailure.ItemNotOwned, Actions.useHeroExp(s, "hero_aldric", "nope", 1, data).failure())
    }

    // ───── starter & power ─────

    @Test
    fun ensureStarterIsIdempotent() {
        val noStarter = TestSupport.newGame(castle = 7)
        assertEquals(noStarter, HeroBootstrap.ensureStarter(noStarter))
        val old = noStarter.copy(starterHeroId = "hero_seren", playerName = "A") // M2 のセーブ相当（heroes が空）
        val fixed = HeroBootstrap.ensureStarter(old)
        assertEquals(listOf(HeroState("hero_seren")), fixed.heroes)
        assertEquals(fixed, HeroBootstrap.ensureStarter(fixed))
        // 育成済みの英雄は上書きしない
        val grown = fixed.copy(heroes = listOf(HeroState("hero_seren", level = 12)))
        assertEquals(grown, HeroBootstrap.ensureStarter(grown))
    }

    @Test
    fun newGameGrantsTheStarterHero() {
        val s = NewGame.create(data, 1000, 5, "Lord", "hero_brann")
        assertEquals(listOf(HeroState("hero_brann")), s.heroes)
        assertEquals("Lord", s.playerName)
        assertEquals("hero_brann", s.starterHeroId)
    }

    @Test
    fun heroPowerFollowsLevelStarAndSkills() {
        val rules = data.heroRules
        val h = HeroState("hero_aldric", level = 10, star = 2, skillLevels = listOf(3, 1, 1))
        val s = TestSupport.newGame(castle = 7).copy(heroes = listOf(h, HeroState("ghost")))
        val expected =
            rules.rarityPower.getValue(HeroRarity.R) * 2 +
                10 * rules.powerPerLevel +
                5 * rules.powerPerSkillLevel
        assertEquals(expected, HeroPower.total(s, data))
        assertEquals(PowerCalculator.buildingPower(s, data) + expected, PowerCalculator.total(s, data))
        assertEquals(0, HeroPower.total(TestSupport.newGame(castle = 7), data))
    }
}
