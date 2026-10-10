package com.ttmrase.crownfall.core.sim

import com.ttmrase.crownfall.core.TestSupport
import com.ttmrase.crownfall.core.map.MapQueries
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HeroState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.ok
import com.ttmrase.crownfall.core.save.SaveCodec
import com.ttmrase.crownfall.core.save.SaveGame
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 性能ベンチ（docs/02 §6）: tick 1 回 < 2ms、OfflineCatchUp 24h < 300ms、セーブ JSON < 2MB。
 * 判定は目標値そのもの（実測は目標の 1/10 以下の余裕がある: tick 約 8us、24h catch-up 約 25ms。CI の揺れは複数回の最小値で吸収）。
 * 実測値は標準出力に必ず出す（`BENCH ...`）。JIT のウォームアップ後に計測する。マップ描画は対象外（実機が無い）。
 */
class PerformanceBenchTest {
    private val data = TestSupport.data
    private val day = 86_400L

    /** 終盤相当: 建物 Lv12、兵・英雄・研究・行軍・同盟・ショップ・クエストが動いている状態。 */
    private fun lateGame(): GameState {
        var s = TestSupport.advanced(level = 12, castle = 12, now = 20_000L * day + 20 * 3600)
        s =
            s.copy(
                slots =
                    s.slots.map {
                        val level = if (it.type == BuildingType.CASTLE) 12 else 11
                        it.copy(level = minOf(level, data.building(it.type).maxLevel))
                    },
            )
        s =
            s.copy(
                resources = TestSupport.RICH,
                gems = 100_000,
                troops = data.troops.keys.associateWith { 2_000L },
                heroes = s.heroes + listOf(HeroState("hero_brann"), HeroState("hero_seren")),
                research = data.research.keys.associateWith { 3 },
            )
        s = Simulation.tick(s, 0, data).state
        s = s.copy(map = s.map.copy(nextRaidEpochSec = s.nowEpochSec + 6 * 3600))
        val center = data.mapRules.center
        val node =
            s.map.nodes.filter {
                it.type == ResourceType.FOOD
            }.minBy { MapQueries.distance(it.x, it.y, center.x, center.y) }
        val gatherers = mapOf("archer_t2" to 500L)
        s = Actions.startMarch(s, MarchKind.GATHER, TileRef(node.x, node.y), gatherers, emptyList(), data).ok()
        s = Actions.grantSecondBuilder(s, 5 * day).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.FARM, 0), data).ok()
        s = Actions.startUpgrade(s, TestSupport.slotOf(s, BuildingType.SAWMILL, 0), data).ok()
        return s
    }

    private inline fun nanos(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }

    @Test
    fun oneSecondTickIsFast() {
        var s = lateGame()
        repeat(3_000) { s = Simulation.tick(s, 1, data).state } // JIT のウォームアップ
        val rounds = 5
        val perTickMicros =
            List(rounds) {
                val n = 2_000
                var cur = s
                val t = nanos { repeat(n) { cur = Simulation.tick(cur, 1, data).state } }
                t / n / 1_000.0
            }
        val best = perTickMicros.min()
        println(
            "BENCH tick(1s): best=%.0f us, all=%s us (target < 2000 us)".format(
                best,
                perTickMicros.map { it.toLong() },
            ),
        )
        assertTrue(best < 2_000, "tick(1s) best average is $best us")
    }

    @Test
    fun oneMinuteTickIsFast() {
        var s = lateGame()
        repeat(200) { s = Simulation.tick(s, 60, data).state }
        val n = 500
        var cur = s
        val perTickMicros = nanos { repeat(n) { cur = Simulation.tick(cur, 60, data).state } } / n / 1_000.0
        println("BENCH tick(60s): %.0f us".format(perTickMicros))
        assertTrue(perTickMicros < 2_000, "tick(60s) average is $perTickMicros us")
    }

    @Test
    fun catchUpOfADayIsFast() {
        val s = lateGame()
        repeat(3) { OfflineCatchUp.run(s, s.nowEpochSec + day, data) } // ウォームアップ
        val ms = List(7) { nanos { OfflineCatchUp.run(s, s.nowEpochSec + day, data) } / 1_000_000.0 }
        println(
            "BENCH catch-up 24h: best=%.1f ms, all=%s ms (target < 300 ms)".format(ms.min(), ms.map { it.toLong() }),
        )
        assertTrue(ms.min() < 300, "24h catch-up best is ${ms.min()} ms")
    }

    @Test
    fun saveEncodeDecodeIsFastAndSmall() {
        val s = Simulation.tick(lateGame(), 3 * day, data).state
        val save = SaveGame(savedAtEpochSec = s.nowEpochSec, seed = 42, state = s)
        repeat(5) { SaveCodec.decode(SaveCodec.encode(save)) }
        var text = ""
        val encodeMs = List(5) { nanos { text = SaveCodec.encode(save) } / 1_000_000.0 }
        val decodeMs = List(5) { nanos { SaveCodec.decode(text) } / 1_000_000.0 }
        val bytes = text.toByteArray(Charsets.UTF_8).size
        println(
            "BENCH save: size=%d bytes, encode best=%.1f ms, decode best=%.1f ms".format(
                bytes,
                encodeMs.min(),
                decodeMs.min(),
            ),
        )
        assertTrue(bytes < 2 * 1024 * 1024, "save is $bytes bytes")
        assertTrue(
            encodeMs.min() < 500 && decodeMs.min() < 500,
            "encode ${encodeMs.min()} / decode ${decodeMs.min()} ms",
        )
    }
}
