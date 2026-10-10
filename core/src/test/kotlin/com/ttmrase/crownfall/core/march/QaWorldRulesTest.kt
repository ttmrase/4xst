package com.ttmrase.crownfall.core.march

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.combat.Armies
import com.ttmrase.crownfall.core.combat.CombatResolver
import com.ttmrase.crownfall.core.failure
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.model.BattleReport
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Mail
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.save.SaveGame
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.sim.Simulation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QA による M4 のルール検証（セーブサイズ、recall、戦闘の整合、略奪の保護量）。 */
class QaWorldRulesTest {
    private val data = TestSupport.data
    private val rules = data.mapRules

    private fun tick(
        s: GameState,
        dt: Long,
    ) = Simulation.tick(s, dt, data)

    private fun base(): GameState {
        var s = TestSupport.withLevel(TestSupport.newGame(), BuildingType.CASTLE, 10)
        s = s.copy(troops = mapOf("infantry_t1" to 1000L, "infantry_t2" to 2000L, "archer_t2" to 2000L))
        s = tick(s, 0).state
        return s.copy(map = s.map.copy(nextRaidEpochSec = Long.MAX_VALUE / 4))
    }

    private fun dist(
        x: Int,
        y: Int,
    ) = MapQueries.distance(x, y, rules.center.x, rules.center.y)

    // ───── セーブサイズ ─────

    @Test
    fun saveWithFullMapAndTwoHundredBigMailsStaysUnderTwoMegabytes() {
        val s0 = base()
        val report =
            BattleReport(
                kind = BattleKind.BARBARIAN,
                tile = TileRef(3, 4),
                level = 30,
                attackerWon = true,
                rounds = 10,
                attackerInitial = data.troops.keys.associateWith { 123_456L },
                attackerDead = data.troops.keys.associateWith { 12_345L },
                attackerWounded = data.troops.keys.associateWith { 23_456L },
                defenderInitial = data.troops.keys.associateWith { 99_999L },
                defenderDead = data.troops.keys.associateWith { 88_888L },
                heroIds = listOf("hero_brann", "hero_seren"),
                heroExp = 123_456_789L,
            )
        var s = s0
        val events = ArrayList<GameEvent>()
        repeat(MailBox.CAP + 50) { i ->
            s = MailBox.add(s, Mail(0, MailKind.BATTLE, 1000L + i, battle = report), events)
        }
        assertEquals(MailBox.CAP, s.mails.size)
        val text = SaveCodec.encode(SaveGame(savedAtEpochSec = s.nowEpochSec, seed = 1, state = s))
        val bytes = text.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes < 2 * 1024 * 1024, "save is $bytes bytes")
    }

    // ───── recall の 3 フェーズ ─────

    private fun fortOf(s: GameState) =
        Marching.world(s, data).structures.first { it.type == StructureType.BARBARIAN_FORT && it.level == 1 }

    @Test
    fun recallWhileWaitingForRallyRestoresEveryTroopImmediately() {
        val s = base()
        val f = fortOf(s)
        val sent =
            Actions.startMarch(
                s,
                MarchKind.RALLY,
                TileRef(f.x, f.y),
                mapOf("infantry_t2" to 700L),
                emptyList(),
                data,
            ).ok()
        assertEquals(MarchPhase.WAITING, sent.marches.single().phase)
        val back = Actions.recallMarch(sent, 1, data).ok()
        assertTrue(back.marches.isEmpty())
        assertEquals(s.troops, back.troops)
    }

    @Test
    fun recallWhileOutboundTurnsBackAfterTheElapsedTimeAndReturnsAllTroops() {
        val s = base()
        val node = s.map.nodes.minBy { dist(it.x, it.y) }
        val sent =
            Actions.startMarch(
                s,
                MarchKind.GATHER,
                TileRef(node.x, node.y),
                mapOf("archer_t2" to 500L),
                emptyList(),
                data,
            ).ok()
        // 出発直後は即座に戻る
        assertEquals(s.troops, Actions.recallMarch(sent, 1, data).ok().troops)
        val mid = tick(sent, 20).state
        val recalled = Actions.recallMarch(mid, 1, data).ok()
        val m = recalled.marches.single()
        assertEquals(MarchPhase.RETURNING, m.phase)
        assertEquals(mid.nowEpochSec + 20, m.phaseEndEpochSec)
        val home = tick(recalled, 20).state
        assertTrue(home.marches.isEmpty())
        assertEquals(s.troops, home.troops)
        assertEquals(node.remaining, home.map.nodes.first { it.x == node.x && it.y == node.y }.remaining)
        // 帰還中は呼び戻せない / 存在しない ID
        assertEquals(ActionFailure.InvalidArgument, Actions.recallMarch(recalled, 1, data).failure())
        assertEquals(ActionFailure.MarchNotFound, Actions.recallMarch(recalled, 99, data).failure())
    }

    @Test
    fun recallWhileGatheringKeepsProportionalLootAndReturnsTheRestToTheNode() {
        val s = base()
        val node = s.map.nodes.filter { it.type == ResourceType.FOOD }.minBy { dist(it.x, it.y) }
        val sent =
            Actions.startMarch(
                s,
                MarchKind.GATHER,
                TileRef(node.x, node.y),
                mapOf("archer_t2" to 500L),
                emptyList(),
                data,
            ).ok()
        val m0 = sent.marches.single()
        val working = tick(sent, m0.phaseEndEpochSec - sent.nowEpochSec).state
        val w = working.marches.single()
        assertEquals(MarchPhase.WORKING, w.phase)
        val total = w.phaseEndEpochSec - w.phaseStartEpochSec
        val half = tick(working, total / 2).state
        val recalled = Actions.recallMarch(half, 1, data).ok()
        val r = recalled.marches.single()
        assertEquals(MarchPhase.RETURNING, r.phase)
        val got = r.loot[ResourceType.FOOD]
        val nodeNow = recalled.map.nodes.first { it.x == node.x && it.y == node.y }.remaining
        // 採れた分 + ノードへ戻した分 = 予約した量（量は保存される）
        assertEquals(
            w.workAmount,
            got + (
                nodeNow - (
                    working.map.nodes.firstOrNull {
                        it.x == node.x && it.y == node.y
                    }?.remaining ?: 0L
                )
            ),
        )
        assertTrue(got in 0..w.workAmount)
        // 立ち上がり直後（経過 0）は 0、完了間際は予約量にほぼ等しい
        assertEquals(0L, Actions.recallMarch(working, 1, data).ok().marches.single().loot[ResourceType.FOOD])
    }

    // ───── 戦闘の整合 ─────

    @Test
    fun combatIsDeterministicAndEveryTroopIsAccountedFor() {
        val s = base()
        for (level in listOf(1, 5, 10, 20, 30)) {
            val troops = mapOf("infantry_t2" to 800L, "archer_t2" to 600L, "infantry_t1" to 300L)
            val attacker = Armies.player(s, troops, emptyList(), data, emptyMap())
            val defender = Armies.barbarian(level, data)
            val a = CombatResolver.resolve(attacker, defender, data)
            assertEquals(a, CombatResolver.resolve(attacker, defender, data), "level=$level deterministic")
            for (side in listOf(a.attacker, a.defender)) {
                for ((id, init) in side.initial) {
                    val remaining = side.remaining[id] ?: 0L
                    val wounded = side.wounded[id] ?: 0L
                    val dead = side.dead[id] ?: 0L
                    assertEquals(init, remaining + wounded + dead, "level=$level $id")
                    assertTrue(remaining >= 0 && wounded >= 0 && dead >= 0)
                }
            }
            assertTrue(a.rounds in 1..rules.combat.maxRounds)
        }
    }

    // ───── 襲撃の略奪は保護量を超えた分だけ ─────

    @Test
    fun raidOnlyTakesWhatExceedsTheWarehouseProtection() {
        var s = TestSupport.withLevel(base().copy(troops = emptyMap()), BuildingType.WAREHOUSE, 5)
        val prot = Raids.protectedAmount(s, data)
        assertTrue(prot > 0)
        for (extra in listOf(0L, 1L, 500L, 1_000_000L)) {
            val res = com.ttmrase.crownfall.core.model.Resources(prot + extra, prot + extra, prot + extra, prot + extra)
            s = s.copy(resources = res, map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + 1, raidWarned = true))
            val after = tick(s, 2).state
            for (t in ResourceType.entries) {
                assertTrue(after.resources[t] >= prot, "extra=$extra $t fell below protection")
                assertTrue(res[t] - after.resources[t] <= extra, "extra=$extra $t took more than the exposed part")
            }
            if (extra == 0L) assertEquals(res, after.resources)
        }
    }
}
