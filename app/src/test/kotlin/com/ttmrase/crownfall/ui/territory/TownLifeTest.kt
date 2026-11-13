package com.ttmrase.crownfall.ui.territory

import com.ttmrase.crownfall.core.data.ClasspathDataSource
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.sim.NewGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class TownLifeTest {
    private val cells = PlotLayout.cellsFor(NewGame.create(ClasspathDataSource.load(), 1_000_000L, 42L).slots)
    private val walkers = TownLife.walkers(cells)
    private val townsfolk = walkers.filter { it.role != WalkerRole.GUARD }
    private val guards = walkers.filter { it.role == WalkerRole.GUARD }

    @Test
    fun theSameLayoutAlwaysGivesTheSameWalkers() {
        val again = TownLife.walkers(cells)
        assertEquals(walkers.size, again.size)
        for (i in walkers.indices) {
            assertEquals(walkers[i].role, again[i].role)
            assertTrue(walkers[i].xs.contentEquals(again[i].xs) && walkers[i].ys.contentEquals(again[i].ys))
            assertEquals(walkers[i].speed, again[i].speed, 0f)
            assertEquals(walkers[i].phase, again[i].phase, 0f)
        }
        // 種が違えば道筋も変わる（種から決まっている）
        val other = TownLife.walkers(cells, seed = 7L).filter { it.role != WalkerRole.GUARD }
        assertTrue(other.indices.any { !other[it].xs.contentEquals(townsfolk.getOrNull(it)?.xs) })
    }

    @Test
    fun theCrowdGrowsGentlyWithTheCastleLevel() {
        assertTrue(TownLife.townsfolkAt(1) in 2..5)
        assertEquals(TownLife.TOWNSFOLK, TownLife.townsfolkAt(25))
        for (lv in 2..25) {
            val grown = TownLife.townsfolkAt(lv) - TownLife.townsfolkAt(lv - 1)
            assertTrue("level $lv adds $grown", grown in 0..3)
        }
        // 生成された住民も同じ並び: 現れる Lv は後ろほど遅く、ほぼ全員の道筋が見つかる
        assertTrue(townsfolk.size >= TownLife.TOWNSFOLK - 2)
        assertTrue(townsfolk.zipWithNext().all { (a, b) -> a.minLevel <= b.minLevel })
        assertTrue(townsfolk.any { it.role == WalkerRole.CARRIER } && townsfolk.any { it.role == WalkerRole.WORKER })
    }

    @Test
    fun theFirstTownsfolkNeverDependOnUnbuiltPlots() {
        val castle = cells.first { it.third == BuildingType.CASTLE }.first
        val early = townsfolk.filter { it.minLevel == 1 }
        assertTrue(early.isNotEmpty())
        assertTrue(
            early.take(3).all { (it.fromSlot < 0 || it.fromSlot == castle) && (it.toSlot < 0 || it.toSlot == castle) },
        )
    }

    @Test
    fun routesStayOnStreetsInsideTheWallAndNeverCrossAPlotOrThePlaza() {
        for (w in townsfolk) {
            val band = PlotLayout.bandOfCastle(w.minLevel)
            val reach = PlotLayout.WALL_HALF[band - 1] - TownLife.WALL_MARGIN + EPS
            val streets = TownLife.streetRange(band).map { TownLife.streetCoord(it) }
            for (i in w.xs.indices) {
                assertTrue(
                    "inside the wall",
                    abs(w.xs[i] - PlotLayout.MID) <= reach && abs(w.ys[i] - PlotLayout.MID) <= reach,
                )
                if (i == 0) continue
                val ax = w.xs[i - 1]
                val ay = w.ys[i - 1]
                val bx = w.xs[i]
                val by = w.ys[i]
                // 各区間は軸に平行で、どれかの通りの中心線から LANE_MAX 以内
                assertTrue("axis aligned", abs(ax - bx) < EPS || abs(ay - by) < EPS)
                val fixed = if (abs(ax - bx) < EPS) ax else ay
                assertTrue("on a street: $fixed", streets.any { abs(it - fixed) <= TownLife.LANE_MAX + EPS })
                val toPlaza = TownLife.segmentDistance(TownLife.PLAZA, TownLife.PLAZA, ax, ay, bx, by)
                assertTrue("clear of the plaza", toPlaza >= TownLife.PLAZA_CLEAR - EPS)
                // 途中の点が、どの区画の足元にも入らない
                for (step in 0..SAMPLES) {
                    val x = ax + (bx - ax) * step / SAMPLES
                    val y = ay + (by - ay) * step / SAMPLES
                    for ((_, cell, _) in cells) {
                        val (x0, y0) = PlotLayout.footprintOrigin(cell)
                        assertTrue("plot $cell", !(x > x0 && x < x0 + 4f && y > y0 && y < y0 + 4f))
                    }
                }
            }
        }
    }

    @Test
    fun guardsPatrolByTheCastleAndInsideEveryGate() {
        val castle = cells.first { it.third == BuildingType.CASTLE }.second
        val (cx, cy) = PlotLayout.centerOf(castle)
        val atCastle = guards.filter { g -> g.xs.indices.all { hypot(g.xs[it] - cx, g.ys[it] - cy) < CASTLE_NEAR } }
        assertTrue(atCastle.isNotEmpty() && atCastle.any { it.minLevel == 1 })
        for (band in 1..PlotLayout.BANDS) {
            val level = (band - 1) * 5 + 3
            val hi = PlotLayout.MID + PlotLayout.WALL_HALF[band - 1]
            val atGate =
                guards.filter { g ->
                    val inside = g.xs.indices.all { g.xs[it] < hi && g.ys[it] < hi }
                    val near = g.xs.indices.all { hypot(g.xs[it] - hi, g.ys[it] - hi) < GATE_NEAR }
                    level in g.minLevel..g.maxLevel && g !in atCastle && inside && near
                }
            assertEquals("gate guards in band $band", 2, atGate.size)
        }
        // 帯が変わると、前の帯の門の見張りはいなくなる
        assertTrue(
            guards.count { 12 in it.minLevel..it.maxLevel } == guards.count { 1 in it.minLevel..it.maxLevel } + 2,
        )
    }

    @Test
    fun aWalkerMovesContinuouslyPausesAtBothEndsAndComesBack() {
        val pose = WalkerPose()
        val next = WalkerPose()
        for (w in walkers) {
            var paused = 0
            var t = 0f
            while (t < w.cycleSec * 2f) {
                w.poseAt(t, pose)
                w.poseAt(t + DT, next)
                val moved = hypot(next.gx - pose.gx, next.gy - pose.gy)
                assertTrue("no jumps: $moved", moved <= w.speed * DT + EPS)
                if (!pose.moving) paused++
                // 向きは単位ベクトルで、道筋の上にいる
                assertEquals(1f, hypot(pose.dirX, pose.dirY), EPS)
                val onRoute =
                    (1 until w.xs.size).any {
                        TownLife.segmentDistance(pose.gx, pose.gy, w.xs[it - 1], w.ys[it - 1], w.xs[it], w.ys[it]) < EPS
                    }
                assertTrue(onRoute)
                t += DT
            }
            assertTrue(paused > 0)
            // 1 往復で元の場所に戻る
            w.poseAt(0f, pose)
            w.poseAt(w.cycleSec, next)
            assertEquals(pose.gx, next.gx, LOOP_EPS)
            assertEquals(pose.gy, next.gy, LOOP_EPS)
        }
    }

    @Test
    fun streetsMatchTheBandsOfTheScene() {
        assertEquals(-2..4, TownLife.streetRange(1))
        assertEquals(-2..4, TownLife.streetRange(2))
        assertEquals(-3..5, TownLife.streetRange(3))
        assertEquals(-5..7, TownLife.streetRange(5))
        assertEquals(TownLife.PLAZA, TownLife.streetCoord(1), 0f)
    }

    private companion object {
        const val EPS = 0.01f
        const val LOOP_EPS = 0.05f
        const val DT = 0.1f
        const val SAMPLES = 8
        const val CASTLE_NEAR = 4f
        const val GATE_NEAR = 8f
    }
}
