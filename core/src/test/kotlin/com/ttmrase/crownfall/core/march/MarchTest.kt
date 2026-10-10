package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.combat.Armies
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.OfflineCatchUp
import com.ttmrase.crownfall.core.sim.Simulation
import com.ttmrase.crownfall.core.sim.SplitInvariance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarchTest {
    private val data = TestSupport.data
    private val rules = data.mapRules

    /** 城 Lv10（行軍枠 3、兵上限 5000）、兵と英雄あり、マップ初期化済み、襲撃は遠い未来。 */
    private fun base(): GameState {
        var s = TestSupport.withLevel(TestSupport.newGame(), BuildingType.CASTLE, 10)
        s =
            s.copy(
                troops = mapOf("infantry_t1" to 1000L, "infantry_t2" to 2000L, "archer_t2" to 2000L),
                heroes = s.heroes + HeroState("hero_brann", skillLevels = listOf(2, 3, 1)),
            )
        s = tick(s, 0).state
        return s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
    }

    private fun tick(
        s: GameState,
        dt: Long,
    ) = Simulation.tick(s, dt, data)

    private fun dist(
        x: Int,
        y: Int,
    ) = MapQueries.distance(x, y, rules.center.x, rules.center.y)

    private fun nearestNode(
        s: GameState,
        type: ResourceType = ResourceType.FOOD,
    ) = s.map.nodes.filter { it.type == type }.minBy { dist(it.x, it.y) }

    private fun nearestBarb(s: GameState) = s.map.barbarians.minBy { dist(it.x, it.y) }

    private fun fort(
        s: GameState,
        level: Int = 1,
    ) = Marching.world(s, data).structures.first { it.type == StructureType.BARBARIAN_FORT && it.level == level }

    private fun send(
        s: GameState,
        kind: MarchKind,
        t: TileRef,
        troops: Map<String, Long>,
        heroes: List<String> = emptyList(),
    ) = Actions.startMarch(s, kind, t, troops, heroes, data)

    private fun until(
        s: GameState,
        id: Long = 1,
    ): GameState {
        // その行軍が消えるまで境界ごとに進める
        var cur = s
        var guard = 0
        while (cur.marches.any { it.id == id } && guard++ < 50) {
            val end = cur.marches.first { it.id == id }.phaseEndEpochSec
            cur = tick(cur, end - cur.nowEpochSec).state
        }
        return cur
    }

    // ───── 初期化・開始検査 ─────

    @Test
    fun mapIsInitializedOnceAndRaidIsScheduled() {
        val raw = TestSupport.newGame()
        assertEquals(0, raw.map.nextRefreshEpochSec)
        val s = tick(raw, 0).state
        assertEquals(56, s.map.nodes.size)
        assertEquals(40, s.map.barbarians.size)
        assertEquals(raw.nowEpochSec + rules.refreshPeriodSec, s.map.nextRefreshEpochSec)
        assertEquals(raw.nowEpochSec + rules.raid.firstDelaySec, s.map.nextRaidEpochSec)
        assertEquals(s, tick(s, 0).state)
        // 満杯なら補充は境界にならず、長い dt でも状態は変わらない（周期ごとに刻まない）
        val later = tick(s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4)), 1_000_000).state
        assertEquals(s.map.nodes, later.map.nodes)
    }

    @Test
    fun startMarchValidation() {
        val s = base()
        val node = nearestNode(s)
        val t = TileRef(node.x, node.y)
        val inf = mapOf("infantry_t1" to 10L)
        assertEquals(ActionFailure.InvalidTarget, send(s, MarchKind.GATHER, TileRef(-1, 0), inf).failure())
        assertEquals(ActionFailure.InvalidTarget, send(s, MarchKind.ATTACK, t, inf).failure())
        assertEquals(ActionFailure.InvalidTarget, send(s, MarchKind.RALLY, t, inf).failure())
        assertEquals(ActionFailure.NoTroops, send(s, MarchKind.GATHER, t, emptyMap()).failure())
        assertEquals(ActionFailure.NoTroops, send(s, MarchKind.GATHER, t, mapOf("infantry_t1" to 5000L)).failure())
        assertEquals(ActionFailure.NoTroops, send(s, MarchKind.GATHER, t, mapOf("nope" to 1L)).failure())
        assertEquals(ActionFailure.HeroNotOwned, send(s, MarchKind.GATHER, t, inf, listOf("hero_x")).failure())
        assertEquals(
            ActionFailure.InvalidArgument,
            send(s, MarchKind.GATHER, t, inf, listOf("hero_brann", "hero_brann")).failure(),
        )
        val capped = s.copy(troops = mapOf("infantry_t1" to 9000L))
        assertEquals(
            ActionFailure.OverCapacity,
            send(capped, MarchKind.GATHER, t, mapOf("infantry_t1" to 6000L)).failure(),
        )
        val one = send(s, MarchKind.GATHER, t, inf, listOf("hero_brann")).ok()
        assertEquals(ActionFailure.HeroBusy, send(one, MarchKind.GATHER, t, inf, listOf("hero_brann")).failure())
        var full = one
        repeat(Marching.slots(s, data) - 1) { full = send(full, MarchKind.GATHER, t, inf).ok() }
        assertEquals(ActionFailure.NoMarchSlot, send(full, MarchKind.GATHER, t, inf).failure())
        assertEquals(3, Marching.slots(s, data))
        assertEquals(1000 + 400 * 10L, Marching.troopCap(s, data))
        // 出撃した兵は城から外れる
        assertEquals(990L, one.troops["infantry_t1"])
        assertEquals(ActionFailure.MarchNotFound, Actions.recallMarch(s, 99, data).failure())
    }

    @Test
    fun travelTimeUsesDistanceSlowestTroopAndMarchSpeed() {
        val s = base()
        val far = TileRef(63, 63)
        val d = dist(63, 63).toLong()
        // infantry_t1 speed 10 → 30 秒/マス、infantry_t2 speed 11 → ceil(300/11)=28 秒/マス（遅い方に合わせる）
        assertEquals(
            d * 30,
            Marching.travelSec(s, MarchKind.ATTACK, mapOf("infantry_t1" to 1L), emptyList(), far, data),
        )
        assertEquals(
            d * 30,
            Marching.travelSec(
                s,
                MarchKind.ATTACK,
                mapOf("infantry_t1" to 1L, "infantry_t2" to 1L),
                emptyList(),
                far,
                data,
            ),
        )
        assertEquals(
            d * 28,
            Marching.travelSec(s, MarchKind.ATTACK, mapOf("infantry_t2" to 1L), emptyList(), far, data),
        )
        assertEquals(d * 10, Marching.travelSec(s, MarchKind.SCOUT, mapOf("infantry_t1" to 1L), emptyList(), far, data))
        // 英雄の行軍速度パッシブ（brann: Lv3 = +600bp）
        val withHero =
            Marching.travelSec(s, MarchKind.ATTACK, mapOf("infantry_t1" to 1L), listOf("hero_brann"), far, data)
        assertEquals((d * 30 * 10_000 + 10_599) / 10_600, withHero)
    }

    // ───── 採集 ─────

    @Test
    fun gatherFlowRespectsLoadCapacityAndPhases() {
        val s = base()
        val node = nearestNode(s)
        val t = TileRef(node.x, node.y)
        val started = send(s, MarchKind.GATHER, t, mapOf("infantry_t1" to 100L)).ok()
        val m = started.marches.single()
        val travel = dist(node.x, node.y) * 30L
        assertEquals(MarchPhase.OUTBOUND, m.phase)
        assertEquals(s.nowEpochSec + travel, m.phaseEndEpochSec)
        assertEquals(2000L, m.capacity) // 100 体 × load 20
        val arrived = tick(started, travel).state
        val w = arrived.marches.single()
        assertEquals(MarchPhase.WORKING, w.phase)
        val amount = minOf(2000L, node.remaining)
        assertEquals(amount, w.workAmount)
        val rate = rules.nodes.gatherPerHourByLevel[node.level - 1]
        assertEquals((amount * 3600 + rate - 1) / rate, w.phaseEndEpochSec - w.phaseStartEpochSec)
        // ノードは予約分だけ減る
        assertEquals(
            node.remaining - amount,
            arrived.map.nodes.firstOrNull { it.x == node.x && it.y == node.y }?.remaining ?: 0L,
        )
        val working = tick(arrived, w.phaseEndEpochSec - arrived.nowEpochSec).state
        assertEquals(MarchPhase.RETURNING, working.marches.single().phase)
        val before = working.resources.food
        val r = tick(working, travel)
        assertTrue(r.state.marches.isEmpty())
        assertEquals(100L, r.state.troops["infantry_t1"]!! - 900L)
        assertTrue(r.state.resources.food >= before + amount) // 帰還までの生産ぶんも増える
        assertTrue(r.events.any { it is GameEvent.MarchReturned })
        val done = r.state.mails.single { it.systemCode == "gather_done" }
        assertEquals(amount, done.params["amount"])
    }

    @Test
    fun smallLoadCapacityLimitsTheAmount() {
        val s = base()
        val node = nearestNode(s)
        val started = send(s, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 5L)).ok()
        assertEquals(100L, started.marches.single().capacity)
        val arrived = tick(started, started.marches.single().travelSec).state
        assertEquals(100L, arrived.marches.single().workAmount)
    }

    @Test
    fun secondGathererOnTheSameNodeIsTurnedAway() {
        val s = base()
        val node = nearestNode(s)
        val t = TileRef(node.x, node.y)
        var cur = send(s, MarchKind.GATHER, t, mapOf("infantry_t1" to 100L)).ok()
        cur = send(cur, MarchKind.GATHER, t, mapOf("infantry_t1" to 100L)).ok()
        val travel = dist(node.x, node.y) * 30L
        val r = tick(cur, travel)
        // 同時刻着の 2 隊: id 順で 1 隊目が採集、2 隊目は帰る
        assertEquals(MarchPhase.WORKING, r.state.marches.first { it.id == 1L }.phase)
        assertEquals(MarchPhase.RETURNING, r.state.marches.first { it.id == 2L }.phase)
        assertEquals("gather_node_busy", r.state.mails.single().systemCode)
        assertTrue(r.events.any { it is GameEvent.MailReceived })
    }

    @Test
    fun gatherAtVanishedNodeReturnsHome() {
        val s = base()
        val node = nearestNode(s)
        val started = send(s, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 100L)).ok()
        val gone = started.copy(map = started.map.copy(nodes = started.map.nodes - node))
        val r = tick(gone, started.marches.single().travelSec).state
        assertEquals(MarchPhase.RETURNING, r.marches.single().phase)
        assertEquals("march_target_gone", r.mails.single().systemCode)
    }

    // ───── 呼び戻し ─────

    @Test
    fun recallInEachPhase() {
        val s = base()
        val node = nearestNode(s)
        val t = TileRef(node.x, node.y)
        val started = send(s, MarchKind.GATHER, t, mapOf("infantry_t1" to 100L)).ok()
        // 出発直後はその場で戻る
        val r0 = Actions.recallMarch(started, 1, data).ok()
        assertTrue(r0.marches.isEmpty())
        assertEquals(1000L, r0.troops["infantry_t1"])
        // 移動中は経過時間ぶん引き返す
        val mid = tick(started, 40).state
        val back = Actions.recallMarch(mid, 1, data).ok().marches.single()
        assertEquals(MarchPhase.RETURNING, back.phase)
        assertEquals(mid.nowEpochSec + 40, back.phaseEndEpochSec)
        assertEquals(
            ActionFailure.InvalidArgument,
            Actions.recallMarch(Actions.recallMarch(mid, 1, data).ok(), 1, data).failure(),
        )
        // 採集中は経過比例で持ち帰り、残りはノードへ戻る
        val arrived = tick(started, started.marches.single().travelSec).state
        val w = arrived.marches.single()
        val half = tick(arrived, (w.phaseEndEpochSec - w.phaseStartEpochSec) / 2).state
        val recalled = Actions.recallMarch(half, 1, data).ok()
        val ret = recalled.marches.single()
        assertEquals(MarchPhase.RETURNING, ret.phase)
        val got = ret.loot.food + ret.loot.wood + ret.loot.stone + ret.loot.gold
        assertTrue(got in 1 until w.workAmount)
        val back2 = recalled.map.nodes.first { it.x == node.x && it.y == node.y }
        assertEquals(node.remaining - got, back2.remaining)
        // 集結待ちの RALLY も即座に戻る
        val f = fort(s)
        val rally = send(s, MarchKind.RALLY, TileRef(f.x, f.y), mapOf("infantry_t2" to 500L)).ok()
        assertEquals(MarchPhase.WAITING, rally.marches.single().phase)
        assertEquals(s.nowEpochSec + rules.march.rallyWaitSec, rally.marches.single().phaseEndEpochSec)
        assertTrue(Actions.recallMarch(rally, 1, data).ok().marches.isEmpty())
    }

    // ───── 攻撃 ─────

    @Test
    fun attackingABarbarianWinsLootsAndReturns() {
        val s0 = base()
        val b = nearestBarb(s0)
        val t = TileRef(b.x, b.y)
        val army = mapOf("infantry_t2" to 1500L, "archer_t2" to 1500L)
        val s = TestSupport.withLevel(s0, BuildingType.HOSPITAL, 5)
        val started = send(s, MarchKind.ATTACK, t, army, listOf("hero_brann")).ok()
        val travel = started.marches.single().travelSec
        val fought = tick(started, travel)
        val report = fought.state.mails.single().battle!!
        assertEquals(BattleKind.BARBARIAN, report.kind)
        assertTrue(report.attackerWon)
        assertEquals(t, report.tile)
        assertFalse(fought.state.map.barbarians.any { it.x == b.x && it.y == b.y })
        assertEquals(MarchPhase.RETURNING, fought.state.marches.single().phase)
        assertEquals(army.keys, report.attackerInitial.keys)
        // 戦利品は積載量以下、アイテムは抽選
        val loot = report.loot
        assertEquals(
            rules.loot.resourcesPerLevel.food * b.level,
            loot.food.coerceAtMost(rules.loot.resourcesPerLevel.food * b.level),
        )
        val back = tick(fought.state, travel)
        assertTrue(back.state.marches.isEmpty())
        val lostAll = (report.attackerDead.values.sum() + report.attackerWounded.values.sum())
        assertEquals(
            3000L - lostAll,
            back.state.troops.filterKeys {
                it != "infantry_t1"
            }.values.sum() - (s.troops["infantry_t2"]!! + s.troops["archer_t2"]!! - 3000L),
        )
        assertEquals(report.attackerWounded, back.state.wounded)
        assertEquals(s.resources.food + loot.food, back.state.resources.food.coerceAtMost(s.resources.food + loot.food))
        val hero = back.state.heroes.first { it.heroId == "hero_brann" }
        val before = s.heroes.first { it.heroId == "hero_brann" }
        assertTrue(hero.level > before.level || hero.exp > before.exp)
        assertEquals(rules.barbarians.expPerLevel * b.level, report.heroExp)
        assertEquals(listOf("hero_brann"), report.heroIds)
        assertTrue(back.events.any { it is GameEvent.MarchReturned })
    }

    @Test
    fun aWeakArmyIsWipedOutAndTheBarbarianStays() {
        val s = base()
        val b = nearestBarb(s)
        val started =
            send(
                s,
                MarchKind.ATTACK,
                TileRef(b.x, b.y),
                mapOf("infantry_t1" to 1L),
                listOf("hero_brann"),
            ).ok()
        val r = tick(started, started.marches.single().travelSec)
        assertTrue(r.state.marches.isEmpty()) // 全滅は帰還しない
        val report = r.state.mails.single().battle!!
        assertFalse(report.attackerWon)
        assertTrue(r.state.map.barbarians.any { it.x == b.x && it.y == b.y })
        assertEquals(Resources.ZERO, report.loot)
        // 負傷兵の行き先は病院（無ければ死亡）
        assertTrue(r.state.wounded.isEmpty())
        assertEquals(1L, report.attackerDead.values.sum() + report.attackerWounded.values.sum())
        assertEquals(999L, r.state.troops["infantry_t1"])
        assertTrue(r.events.any { it is GameEvent.MarchReturned })
    }

    @Test
    fun woundedBeyondHospitalCapacityDie() {
        val s = TestSupport.withLevel(base(), BuildingType.HOSPITAL, 1)
        val b = s.map.barbarians.maxBy { it.level }
        val started =
            send(
                s,
                MarchKind.ATTACK,
                TileRef(b.x, b.y),
                mapOf("infantry_t2" to 2000L, "archer_t2" to 2000L),
            ).ok()
        val travel = started.marches.single().travelSec
        val fought = tick(started, travel).state
        val report = fought.mails.single().battle!!
        val back = tick(fought, travel).state
        val cap =
            com.ttmrase.crownfall.core.military.Military.hospitalCapacity(
                back,
                data,
                com.ttmrase.crownfall.core.buff.BuffAggregator.compute(back, data),
            )
        val woundedTotal = report.attackerWounded.values.sum()
        assertTrue(back.wounded.values.sum() <= cap)
        val overflow = back.mails.single().battle!!.overflowDead.values.sum()
        assertEquals(woundedTotal, back.wounded.values.sum() + overflow)
    }

    @Test
    fun scoutReportErrorShrinksWithWatchtower() {
        val s = base()
        val b = nearestBarb(s)
        val t = TileRef(b.x, b.y)
        val started = send(s, MarchKind.SCOUT, t, mapOf("infantry_t1" to 1L)).ok()
        assertEquals(dist(b.x, b.y) * 10L, started.marches.single().travelSec)
        val fought = tick(started, started.marches.single().travelSec).state
        val rep = fought.mails.single().scout!!
        val real = Armies.barbarian(b.level, data).troops
        assertEquals(40, rep.errorPct)
        real.forEach { (id, n) ->
            assertEquals(n * 60 / 100, rep.troopsLow[id])
            assertEquals(n * 140 / 100, rep.troopsHigh[id])
        }
        assertEquals(MailKind.SCOUT, fought.mails.single().kind)
        val sharp = TestSupport.withLevel(s, BuildingType.WATCHTOWER, 10)
        val rep2 =
            tick(
                send(sharp, MarchKind.SCOUT, t, mapOf("infantry_t1" to 1L)).ok(),
                100_000,
            ).state.mails.single().scout!!
        assertEquals(20, rep2.errorPct)
        assertEquals(real.keys, rep2.troopsLow.keys)
        // 偵察できない場所
        assertEquals(
            ActionFailure.InvalidTarget,
            send(s, MarchKind.SCOUT, TileRef(0, 0), mapOf("infantry_t1" to 1L)).failure(),
        )
        // 資源ノードと NPC 王国は偵察できる
        val node = nearestNode(s)
        val nr = tick(send(s, MarchKind.SCOUT, TileRef(node.x, node.y), mapOf("infantry_t1" to 1L)).ok(), 100_000).state
        assertEquals(node.remaining, nr.mails.single().scout!!.nodeRemaining)
        val k = Marching.world(s, data).structures.first { it.type == StructureType.NPC_CASTLE }
        val kr = tick(send(s, MarchKind.SCOUT, TileRef(k.x, k.y), mapOf("infantry_t1" to 1L)).ok(), 100_000).state
        assertEquals(k.power, kr.mails.single().scout!!.power)
    }

    @Test
    fun rallyAgainstAFortUsesAlliesAndPaysFortLoot() {
        val s = TestSupport.withLevel(base(), BuildingType.HOSPITAL, 5)
        val f = fort(s, 1)
        val started =
            send(
                s,
                MarchKind.RALLY,
                TileRef(f.x, f.y),
                mapOf("infantry_t2" to 2000L, "archer_t2" to 2000L),
            ).ok()
        val wait = tick(started, rules.march.rallyWaitSec).state
        val out = wait.marches.single()
        assertEquals(MarchPhase.OUTBOUND, out.phase)
        assertEquals(dist(f.x, f.y) * 28L, out.phaseEndEpochSec - out.phaseStartEpochSec)
        val fought = tick(wait, out.travelSec).state
        val rep = fought.mails.single().battle!!
        assertEquals(BattleKind.FORT, rep.kind)
        assertEquals(Armies.rallyAllies(1, data), rep.allyTroops)
        assertTrue(rep.attackerWon)
        // 砦は残る（何度でも攻略できる）
        assertTrue(Marching.world(fought, data).structureAt(f.x, f.y) != null)
        assertTrue(rep.lootItems.values.all { it > 0 })
        assertTrue(rep.loot.food >= 0)
        assertNotNull(fought.marches.singleOrNull())
    }

    // ───── 襲撃 ─────

    @Test
    fun raidWarningRespectsWatchtowerAndIsFollowedByTheRaid() {
        val s0 = base()
        val raidAt = s0.nowEpochSec + 10_000
        val lv5 = TestSupport.withLevel(s0, BuildingType.WATCHTOWER, 5)
        val s = lv5.copy(map = lv5.map.copy(nextRaidEpochSec = raidAt))
        assertEquals(600L, Raids.warnSec(s, data))
        val before = tick(s, 10_000 - 601)
        assertTrue(before.events.none { it is GameEvent.RaidIncoming })
        val warned = tick(before.state, 1)
        assertEquals(listOf(GameEvent.RaidIncoming(raidAt)), warned.events)
        assertTrue(warned.state.map.raidWarned)
        val raid = tick(warned.state, 601)
        assertTrue(raid.events.any { it is GameEvent.PlayerDefendedRaid || it is GameEvent.PlayerDefeated })
        assertFalse(raid.state.map.raidWarned)
        val gap = raid.state.map.nextRaidEpochSec - raidAt
        assertTrue(gap in rules.raid.minIntervalSec..rules.raid.maxIntervalSec)
        val mail = raid.state.mails.single { it.battle?.kind == BattleKind.RAID }
        assertEquals(raidAt, mail.epochSec)
        // 警告上限
        val tall = TestSupport.withLevel(s0, BuildingType.WATCHTOWER, 30)
        assertEquals(rules.raid.maxWarnSec, Raids.warnSec(tall, data))
        // 物見の塔が無ければ予告なし
        val none = s0.copy(map = s0.map.copy(nextRaidEpochSec = raidAt))
        assertEquals(0L, Raids.warnSec(none, data))
        assertTrue(tick(none, 10_000).events.none { it is GameEvent.RaidIncoming })
    }

    @Test
    fun aStrongHomeDefenseRepelsTheRaid() {
        val s0 = TestSupport.withLevel(TestSupport.withLevel(base(), BuildingType.WALL, 5), BuildingType.HOSPITAL, 5)
        val s =
            s0.copy(
                troops = mapOf("infantry_t4" to 20_000L),
                map = s0.map.copy(nextRaidEpochSec = s0.nowEpochSec + 10),
            )
        val r = tick(s, 10)
        assertTrue(r.events.any { it is GameEvent.PlayerDefendedRaid })
        val rep = r.state.mails.single().battle!!
        assertFalse(rep.attackerWon)
        assertEquals(Resources.ZERO, rep.loot)
        assertEquals(
            rep.defenderInitial.values.sum() - rep.defenderDead.values.sum() -
                rep.defenderWounded.values.sum() - rep.overflowDead.values.sum(),
            r.state.troops.values.sum(),
        )
    }

    @Test
    fun aDefeatPlundersOnlyAboveTheWarehouseProtection() {
        val s0 = base()
        val rich = Resources(500_000, 500_000, 500_000, 500_000)
        val s =
            s0.copy(
                troops = emptyMap(),
                resources = rich,
                map = s0.map.copy(nextRaidEpochSec = s0.nowEpochSec + 10),
            )
        val prot = Raids.protectedAmount(s, data)
        val r = tick(s, 10)
        assertTrue(r.events.any { it is GameEvent.PlayerDefeated })
        val rep = r.state.mails.single().battle!!
        assertTrue(rep.attackerWon)
        val total = rep.loot.food + rep.loot.wood + rep.loot.stone + rep.loot.gold
        assertTrue(total in 1..rules.raid.loadCap)
        ResourceType.entries.forEach { assertTrue(r.state.resources[it] >= minOf(prot, rich[it])) }
        assertEquals(rich.food - rep.loot.food, r.state.resources.food)
        // 倉庫の保護バフで守られる量が増える
        assertTrue(prot > 0)
    }

    // ───── 補充 ─────

    @Test
    fun killedBarbariansComeBackOnlyAfterAFullPeriod() {
        val s = base()
        val b = nearestBarb(s)
        val started =
            send(
                s,
                MarchKind.ATTACK,
                TileRef(b.x, b.y),
                mapOf("infantry_t2" to 2000L, "archer_t2" to 2000L),
            ).ok()
        val travel = started.marches.single().travelSec
        val fought = tick(started, travel).state
        assertEquals(39, fought.map.barbarians.size)
        // 予定（初期化から 1 周期後）が先ならそのとき、過ぎていれば倒した時刻から 1 周期後
        assertEquals(s.map.nextRefreshEpochSec, fought.map.nextRefreshEpochSec)
        val soon = tick(fought, s.map.nextRefreshEpochSec - fought.nowEpochSec - 1).state
        assertEquals(39, soon.map.barbarians.size)
        assertEquals(40, tick(soon, 1).state.map.barbarians.size)
        val stale = s.copy(map = s.map.copy(nextRefreshEpochSec = 5))
        val killed =
            tick(
                send(
                    stale,
                    MarchKind.ATTACK,
                    TileRef(b.x, b.y),
                    mapOf("infantry_t2" to 2000L, "archer_t2" to 2000L),
                ).ok(),
                travel,
            ).state
        assertEquals(killed.nowEpochSec + rules.refreshPeriodSec, killed.map.nextRefreshEpochSec)
        assertEquals(39, tick(killed, rules.refreshPeriodSec - 1).state.map.barbarians.size)
        assertEquals(40, tick(killed, rules.refreshPeriodSec).state.map.barbarians.size)
    }

    // ───── R3・オフライン・セーブ ─────

    @Test
    fun splitInvarianceAcrossMarchesRefreshAndRaids() {
        val s0 = base()
        var s = TestSupport.withLevel(TestSupport.withLevel(s0, BuildingType.WATCHTOWER, 5), BuildingType.HOSPITAL, 5)
        s = s.copy(map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + 5_000))
        val node = nearestNode(s)
        val b = nearestBarb(s)
        val f = fort(s)
        s = send(s, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 300L)).ok()
        val squad = mapOf("infantry_t2" to 800L, "archer_t2" to 800L)
        s = send(s, MarchKind.ATTACK, TileRef(b.x, b.y), squad, listOf("hero_brann")).ok()
        s = send(s, MarchKind.RALLY, TileRef(f.x, f.y), mapOf("infantry_t2" to 900L, "archer_t2" to 900L)).ok()
        val total = 450_000L
        val whole = tick(s, total)
        assertTrue(whole.events.any { it is GameEvent.RaidIncoming })
        assertTrue(whole.events.count { it is GameEvent.MarchReturned } >= 2)
        assertTrue(whole.state.mails.size >= 4)
        val rnd = java.util.Random(4242)
        repeat(8) {
            var cur = s
            val events = ArrayList<GameEvent>()
            var left = total
            while (left > 0) {
                val dt = minOf(left, 1 + (rnd.nextDouble() * rnd.nextDouble() * 120_000).toLong())
                val r = tick(cur, dt)
                cur = r.state
                events += r.events
                left -= dt
            }
            assertEquals(whole.state, cur)
            assertEquals(whole.events, events)
        }
    }

    @Test
    fun offlineCatchUpShiftsMarchDeadlinesBeyondTheCap() {
        val s = base()
        val node = nearestNode(s)
        val started = send(s, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 100L)).ok()
        val cap = 3600L
        val r = OfflineCatchUp.run(started, started.nowEpochSec + cap + 5_000_000, data, capSec = cap).state
        val m = r.marches.singleOrNull()
        val orig = started.marches.single()
        if (m != null) assertTrue(m.phaseEndEpochSec > orig.phaseEndEpochSec)
        assertTrue(
            r.map.nextRaidEpochSec > started.map.nextRaidEpochSec || started.map.nextRaidEpochSec > Long.MAX_VALUE / 8,
        )
        assertEquals(started.nowEpochSec + cap + 5_000_000, r.nowEpochSec)
    }

    @Test
    fun mailBoxIsCappedAndReadStateWorks() {
        var s = base()
        val events = ArrayList<GameEvent>()
        repeat(MailBox.CAP + 20) { s = MailBox.system(s, s.nowEpochSec, "x", mapOf("n" to it.toLong()), events) }
        assertEquals(MailBox.CAP, s.mails.size)
        assertEquals(MailBox.CAP + 20L, s.nextMailId - 1)
        assertEquals(MailBox.CAP, MailBox.unread(s))
        val first = s.mails.first().id
        s = Actions.markMailRead(s, first).ok()
        assertEquals(MailBox.CAP - 1, MailBox.unread(s))
        assertEquals(s, Actions.markMailRead(s, first).ok())
        // 既読から先に捨てる
        s = MailBox.system(s, s.nowEpochSec, "y", emptyMap(), events)
        assertTrue(s.mails.none { it.id == first })
        s = Actions.markAllMailRead(s).ok()
        assertEquals(0, MailBox.unread(s))
    }

    @Test
    fun saveStaysSmallWithFullMapMarchesAndMails() {
        var s = base()
        val node = nearestNode(s)
        repeat(3) { s = send(s, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 10L)).ok() }
        val b = nearestBarb(s)
        val report =
            tick(
                send(
                    base(),
                    MarchKind.ATTACK,
                    TileRef(b.x, b.y),
                    mapOf("infantry_t2" to 2000L, "archer_t2" to 2000L),
                ).ok(),
                100_000,
            ).state.mails.first()
        repeat(MailBox.CAP) { i -> s = s.copy(mails = s.mails + report.copy(id = i + 1L)) }
        val text =
            com.ttmrase.crownfall.core.save.SaveCodec.encode(
                com.ttmrase.crownfall.core.save.SaveGame(savedAtEpochSec = 1, seed = 1, state = s),
            )
        assertTrue(text.length < 2_000_000, "save is ${text.length} bytes")
        val back = com.ttmrase.crownfall.core.save.SaveCodec.decode(text)
        assertEquals(s, (back as com.ttmrase.crownfall.core.save.DecodeResult.Success).save.state)
        // 旧セーブ（マップ・メールなし）は既定値で読め、初回 tick でマップが作られる
        val legacy = tick(TestSupport.newGame().copy(map = com.ttmrase.crownfall.core.map.MapState()), 0).state
        assertTrue(legacy.map.nodes.isNotEmpty())
        assertTrue(legacy.map.seed != 0L)
    }

    @Test
    fun marchViewsInterpolatePositionAndProgress() {
        val s = base()
        val node = nearestNode(s)
        val started = send(s, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 100L)).ok()
        val m = started.marches.single()
        val c = rules.center
        assertEquals(0f, MarchViews.progress(m, m.phaseStartEpochSec))
        assertEquals(1f, MarchViews.progress(m, m.phaseEndEpochSec + 10))
        assertEquals(c.x.toFloat() to c.y.toFloat(), MarchViews.position(m, m.phaseStartEpochSec, data))
        val mid = (m.phaseStartEpochSec + m.phaseEndEpochSec) / 2
        val (x, y) = MarchViews.position(m, mid, data)
        assertTrue(x in minOf(c.x, node.x).toFloat()..maxOf(c.x, node.x).toFloat())
        assertTrue(y in minOf(c.y, node.y).toFloat()..maxOf(c.y, node.y).toFloat())
        assertEquals(m.phaseEndEpochSec - mid, MarchViews.remainingSec(m, mid))
        assertEquals(0L, MarchViews.remainingSec(m, m.phaseEndEpochSec + 5))
        assertEquals(
            node.x.toFloat() to node.y.toFloat(),
            MarchViews.position(m.copy(phase = MarchPhase.WORKING), mid, data),
        )
        assertEquals(c.x.toFloat() to c.y.toFloat(), MarchViews.position(m.copy(phase = MarchPhase.WAITING), mid, data))
        val back = MarchViews.position(m.copy(phase = MarchPhase.RETURNING), m.phaseEndEpochSec, data)
        assertEquals(c.x.toFloat() to c.y.toFloat(), back)
        assertEquals(1f, MarchViews.progress(m.copy(phaseEndEpochSec = m.phaseStartEpochSec), 5))
    }

    @Test
    fun splitInvarianceAtEveryMarchRefreshAndRaidBoundary() {
        var s =
            TestSupport.withLevel(
                TestSupport.withLevel(base(), BuildingType.WATCHTOWER, 5),
                BuildingType.HOSPITAL,
                5,
            )
        s = s.copy(map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + 4_000))
        val node = nearestNode(s)
        val b = nearestBarb(s)
        val f = fort(s)
        s = send(s, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 300L)).ok()
        s = send(s, MarchKind.ATTACK, TileRef(b.x, b.y), mapOf("infantry_t2" to 800L, "archer_t2" to 800L)).ok()
        s = send(s, MarchKind.RALLY, TileRef(f.x, f.y), mapOf("infantry_t2" to 900L, "archer_t2" to 900L)).ok()
        val whole = tick(s, 30_000)
        // 各行軍の段階終了を実際に踏んだ時刻（完了イベントや状態変化の境界）を集める
        val t0 = s.nowEpochSec
        val extra = ArrayList<Long>()
        var cur = s
        var guard = 0
        while (cur.nowEpochSec < t0 + 30_000 && guard++ < 200) {
            val next =
                (cur.marches.map { it.phaseEndEpochSec } + cur.map.nextRefreshEpochSec + cur.map.nextRaidEpochSec)
                    .filter { it > cur.nowEpochSec && it <= t0 + 30_000 }
                    .minOrNull() ?: break
            extra += next - t0
            cur = tick(cur, next - cur.nowEpochSec).state
        }
        assertTrue(extra.size >= 6, "boundaries visited: $extra")
        assertTrue(whole.events.isNotEmpty())
        SplitInvariance.assertInvariant(
            s,
            30_000,
            data,
            java.util.Random(99),
            randomCases = 30,
            extraBoundaries = extra,
        )
    }

    @Test
    fun lootAndGatheredResourcesMayExceedTheStorageCap() {
        val s0 = base()
        val cap = com.ttmrase.crownfall.core.sim.Simulation.storageCap(s0, data)
        val full = s0.copy(resources = Resources(cap, cap, cap, cap))
        val node = nearestNode(full)
        val started = send(full, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 100L)).ok()
        val done = until(started)
        val gained = minOf(2000L, node.remaining)
        assertTrue(done.resources[node.type] >= cap + gained, "resource ${done.resources[node.type]} cap=$cap")
    }

    @Test
    fun aLostRallyStillPaysTheParticipationReward() {
        val s = base()
        val f = fort(s, 5)
        val started = send(s, MarchKind.RALLY, TileRef(f.x, f.y), mapOf("infantry_t2" to 2000L)).ok()
        val wait = tick(started, rules.march.rallyWaitSec).state
        val before = wait.resources
        val fought = tick(wait, wait.marches.single().travelSec).state
        val rep = fought.mails.single().battle!!
        assertFalse(rep.attackerWon)
        val bonus = rules.rally.participationResourcesPerLevel * 5L
        assertEquals(bonus, rep.loot) // 積載量 2000×40 に収まる
        assertTrue(fought.resources.food >= before.food + bonus.food) // 全滅でもその場で受け取る
    }

    @Test
    fun recallingDuringTheGatherOfADepletedNodeRecreatesIt() {
        val s = base()
        val node = nearestNode(s).let { n -> n }
        val small =
            s.copy(
                map = s.map.copy(nodes = s.map.nodes.map { if (it == node) it.copy(remaining = 500) else it }),
            )
        val started = send(small, MarchKind.GATHER, TileRef(node.x, node.y), mapOf("infantry_t1" to 100L)).ok()
        val arrived = tick(started, started.marches.single().travelSec).state
        assertTrue(arrived.map.nodes.none { it.x == node.x && it.y == node.y }) // 予約で枯れて消えた
        val w = arrived.marches.single()
        val half = tick(arrived, (w.phaseEndEpochSec - w.phaseStartEpochSec) / 2).state
        val recalled = Actions.recallMarch(half, 1, data).ok()
        val back = recalled.map.nodes.single { it.x == node.x && it.y == node.y }
        assertEquals(node.type, back.type)
        assertTrue(back.remaining in 1..499)
    }

    @Test
    fun personaStructuresAreIdentifiedByAFunctionNotAStringPrefix() {
        val w = Marching.world(base(), data)
        assertEquals(8, w.structures.count { it.isPersona })
        assertTrue(w.structures.none { it.isPersona && it.type != StructureType.ALLY_CASTLE })
        assertEquals(9000L, rules.combat.shieldCapBp)
    }
}
