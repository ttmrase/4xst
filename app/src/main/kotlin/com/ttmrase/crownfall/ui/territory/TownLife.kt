package com.ttmrase.crownfall.ui.territory

import com.ttmrase.crownfall.core.model.BuildingType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 住民の役割。見た目（荷物・道具・槍）と歩く速さが変わる。 */
internal enum class WalkerRole { TOWNSFOLK, CARRIER, WORKER, GUARD }

/** [Walker.poseAt] の結果の入れ物。描画のたびに作らず、使い回す。 */
internal class WalkerPose {
    var gx = 0f
    var gy = 0f

    /** 進む向き（グリッド単位の単位ベクトル）。止まっている間は、最後に歩いた向き。 */
    var dirX = 1f
    var dirY = 0f
    var moving = false

    /** 行き（道筋の始点 → 終点）なら true。荷運びは行きだけ荷を担ぐ。 */
    var outbound = true

    /** 道筋に沿って歩いた距離（グリッド単位）。足の振りの位相に使うので、足が地面を滑らない。 */
    var stride = 0f
}

/**
 * 通りを歩く住民 1 人。道筋は通りの上の折れ線 ([xs], [ys]。グリッド単位) で、端で [pauseSec] 秒止まって引き返す。
 * 城 Lv が [minLevel]..[maxLevel] の間だけ現れる。[fromSlot] / [toSlot] は行き先の区画（-1 は区画ではない）で、
 * 描く側はその区画が建っているときだけ歩かせる（森に覆われた区画へ通う人は出さない）。
 */
internal class Walker(
    val role: WalkerRole,
    val xs: FloatArray,
    val ys: FloatArray,
    val speed: Float,
    val phase: Float,
    val pauseSec: Float,
    val minLevel: Int,
    val maxLevel: Int = Int.MAX_VALUE,
    val fromSlot: Int = -1,
    val toSlot: Int = -1,
    val look: Int = 0,
) {
    private val cum = FloatArray(xs.size)

    /** 道筋の長さ（グリッド単位）。 */
    val length: Float

    /** 1 往復にかかる秒数（歩き 2 回＋両端の休み）。 */
    val cycleSec: Float

    init {
        require(xs.size == ys.size && xs.size >= 2) { "a walker needs at least two waypoints" }
        var sum = 0f
        for (i in 1 until xs.size) {
            sum += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
            cum[i] = sum
        }
        length = sum
        cycleSec = 2f * (length / speed + pauseSec)
    }

    /** 時刻 [seconds] の位置と向きを [out] に入れる。同じ時刻なら必ず同じ結果（状態を持たない）。 */
    fun poseAt(
        seconds: Float,
        out: WalkerPose,
    ) {
        val walk = length / speed
        var tau = (seconds + phase * cycleSec) % cycleSec
        if (tau < 0f) tau += cycleSec
        val d: Float
        val outbound: Boolean
        val moving: Boolean
        when {
            tau < walk -> {
                d = tau * speed
                outbound = true
                moving = true
            }
            tau < walk + pauseSec -> {
                d = length
                outbound = true
                moving = false
            }
            tau < 2f * walk + pauseSec -> {
                d = length - (tau - walk - pauseSec) * speed
                outbound = false
                moving = true
            }
            else -> {
                d = 0f
                outbound = false
                moving = false
            }
        }
        var i = 1
        while (i < cum.size - 1 && cum[i] < d) i++
        val seg = cum[i] - cum[i - 1]
        val f = if (seg > 0f) ((d - cum[i - 1]) / seg).coerceIn(0f, 1f) else 0f
        val dx = xs[i] - xs[i - 1]
        val dy = ys[i] - ys[i - 1]
        out.gx = xs[i - 1] + dx * f
        out.gy = ys[i - 1] + dy * f
        val sign = if (outbound) 1f else -1f
        if (seg > 0f) {
            out.dirX = sign * dx / seg
            out.dirY = sign * dy / seg
        }
        out.moving = moving
        out.outbound = outbound
        out.stride = d
    }

    private companion object {
        fun dist(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
    }
}

/**
 * 街の住民の道筋を作る（見た目だけ。GameState には一切触れない）。
 * 通りは区画の隙間（[streetCoord]）で、道筋はその上の折れ線: 建物の戸口・井戸のそば・城下町の端を結ぶ。
 * 出力は区画の並びと [SEED] だけで決まる（決定論）。人数は城 Lv でゆるやかに増える（[townsfolkAt]）。
 */
internal object TownLife {
    /** 住民の配置の種（シーンの種と同じ値から派生）。 */
    const val SEED = 20_261_010L xor 0x5EEDL

    /** 城の前の広場（井戸 / 噴水が立つ交差点）。道筋はここを横切らない。 */
    const val PLAZA = 4.5f

    /** 広場の中心から、道筋が保つ距離。 */
    const val PLAZA_CLEAR = 1.0f

    /** 道筋が通りの中心線から外れてよい幅（通りの幅は 1）。 */
    const val LANE_MAX = 0.3f

    /** 道筋が城壁から保つ距離。 */
    const val WALL_MARGIN = 1.0f

    /** 住民（衛兵を除く）の最大数。 */
    const val TOWNSFOLK = 32

    /** 見た目の種類の数（服の色）。 */
    const val LOOKS = 8

    private const val ATTEMPTS = 24
    private const val MIN_ROUTE = 5f
    private const val MAX_ROUTE = 34f
    private const val WELL_STAND = 1.15f
    private const val ALWAYS_OPEN = 3
    private const val LEVELS = 25
    private const val LEVEL_SPREAD = 30
    private const val FOOTPRINT = 4f
    private const val DOOR_AT = 2f
    private const val DOOR_STREET = 4.5f
    private const val OUTER_FROM = 15.5f
    private const val OUTER_MARGIN = 2.2f
    private const val GUARD_LANE = 0.28f
    private const val GUARD_SPEED = 0.55f
    private const val GUARD_PAUSE = 2.6f
    private const val CASTLE_BEAT_FROM = 0.4f
    private const val CASTLE_BEAT_TO = 3.4f
    private const val SECOND_GUARD_LEVEL = 6

    /** 門の見張りが歩く範囲（門の角からの距離）。門楼の真後ろは画面では隠れるので、その両脇を歩く。 */
    private const val GATE_NEAR = 3.2f
    private const val GATE_FAR = 6.6f
    private const val SECOND_GATE_GUARD_LEVEL = 3
    private const val BAND_LEVELS = 5
    private const val JOIN_EPS = 0.05f

    /** 通り [k] の中心線の座標（区画の隙間の中央）。 */
    fun streetCoord(k: Int): Float = k * PlotLayout.CELL - 0.5f

    /** 帯 [band] で敷かれている通りの番号の範囲（[TerritoryScene] の通りの Path と同じ条件）。 */
    fun streetRange(band: Int): IntRange {
        val h = PlotLayout.WALL_HALF[band.coerceIn(1, PlotLayout.BANDS) - 1] + STREET_REACH
        val lo = PlotLayout.MID - h
        val hi = PlotLayout.MID + h
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (k in STREET_MIN..STREET_MAX) {
            val v = streetCoord(k)
            if (v - 0.5f > lo && v + 0.5f < hi) {
                first = min(first, k)
                last = max(last, k)
            }
        }
        return first..last
    }

    /** 城 Lv [castleLevel] で歩いている住民の数の上限（行き先が未建設の人は、さらに減る）。 */
    fun townsfolkAt(castleLevel: Int): Int = (0 until TOWNSFOLK).count { minLevelOf(it) <= castleLevel }

    /** [index] 番目の住民が現れる城 Lv。最初の数人は Lv1 から、あとは Lv25 までにゆるやかに増える。 */
    fun minLevelOf(index: Int): Int = (1 + max(0, index - 2) * LEVELS / LEVEL_SPREAD).coerceAtMost(LEVELS)

    /** 住民（衛兵＋町の人）の一覧。[slotCells] は [PlotLayout.cellsFor] の結果。 */
    fun walkers(
        slotCells: List<Triple<Int, Pair<Int, Int>, BuildingType>>,
        seed: Long = SEED,
    ): List<Walker> {
        val rng = Lcg(seed)
        val out = ArrayList<Walker>(TOWNSFOLK + GUARD_ROOM)
        addGuards(out, slotCells, rng)
        val plotDoors = ArrayList<Door>()
        for ((slotId, cell, type) in slotCells) {
            val x0 = cell.first * PlotLayout.CELL
            val y0 = cell.second * PlotLayout.CELL
            // 手前の大通り（前に建物が無く、歩く姿がよく見える）に面した戸口は、行き先に選ばれやすくする
            repeat(if (cell.second == CELL_MAX) FRONT_WEIGHT else 1) {
                plotDoors += Door(x0 + DOOR_AT, y0 + DOOR_STREET, true, slotId, type)
            }
            repeat(if (cell.first == CELL_MAX) FRONT_WEIGHT else 1) {
                plotDoors += Door(x0 + DOOR_STREET, y0 + DOOR_AT, false, slotId, type)
            }
        }
        val used = slotCells.map { it.second }.toSet()
        val openDoors = ArrayList<Door>()
        for (i in CELL_MIN..CELL_MAX) {
            for (j in CELL_MIN..CELL_MAX) {
                if ((i to j) in used) continue
                openDoors += Door(i * PlotLayout.CELL + DOOR_AT, j * PlotLayout.CELL + DOOR_STREET, true)
                openDoors += Door(i * PlotLayout.CELL + DOOR_STREET, j * PlotLayout.CELL + DOOR_AT, false)
            }
        }
        // 井戸（広場）のそばの立ち位置。通りの 4 方向から寄れる
        openDoors += Door(PLAZA - WELL_STAND, PLAZA, true)
        openDoors += Door(PLAZA + WELL_STAND, PLAZA, true)
        openDoors += Door(PLAZA, PLAZA - WELL_STAND, false)
        openDoors += Door(PLAZA, PLAZA + WELL_STAND, false)
        val castle = slotCells.firstOrNull { it.third == BuildingType.CASTLE }
        if (castle != null) plotDoors.filter { it.slot == castle.first }.forEach { openDoors += it }

        for (k in 0 until TOWNSFOLK) {
            val minLevel = minLevelOf(k)
            val band = PlotLayout.bandOfCastle(minLevel)
            var made: Walker? = null
            var tries = 0
            while (made == null && tries < ATTEMPTS) {
                tries++
                val a: Door
                val b: Door
                if (k < ALWAYS_OPEN || openDoors.isEmpty() || plotDoors.isEmpty()) {
                    // 最初の数人は、いつでも歩ける行き先だけ（未建設の区画に左右されない）
                    a = pick(openDoors, rng)
                    b = pick(openDoors, rng)
                } else {
                    a = if (band >= 2 && rng.next() < OUTER_SHARE) outerStop(band, rng) else pick(plotDoors, rng)
                    b = if (rng.next() < OPEN_SHARE) pick(openDoors, rng) else pick(plotDoors, rng)
                }
                if (a === b || (a.slot >= 0 && a.slot == b.slot)) continue
                val lane = (rng.next() - 0.5f) * 2f * LANE_MAX * LANE_USE
                val pts = route(a, b, lane, band, rng) ?: continue
                val role = roleFor(a.type, b.type, rng.next())
                made =
                    Walker(
                        role = role,
                        xs = pts.first,
                        ys = pts.second,
                        speed = speedFor(role, rng.next()),
                        phase = rng.next(),
                        pauseSec = PAUSE_MIN + rng.next() * PAUSE_SPAN,
                        minLevel = minLevel,
                        fromSlot = a.slot,
                        toSlot = b.slot,
                        look = (rng.next() * LOOKS).toInt().coerceIn(0, LOOKS - 1),
                    )
            }
            if (made != null) out += made
        }
        return out
    }

    /** 戸口（または立ち止まる場所）。[alongX] は、その通りが x 方向に延びる（y が一定）こと。 */
    private class Door(
        val x: Float,
        val y: Float,
        val alongX: Boolean,
        val slot: Int = -1,
        val type: BuildingType? = null,
    )

    private fun pick(
        doors: List<Door>,
        rng: Lcg,
    ): Door = doors[(rng.next() * doors.size).toInt().coerceIn(0, doors.size - 1)]

    /** 城下町（区画の外側の輪）の通りの上の点。帯 [band] の城壁の内側で、区画の並びの外。 */
    private fun outerStop(
        band: Int,
        rng: Lcg,
    ): Door {
        val c = PlotLayout.MID
        val reach = PlotLayout.WALL_HALF[band - 1] - OUTER_MARGIN
        val streets = streetRange(band)
        repeat(ATTEMPTS) {
            val k = streets.first + (rng.next() * (streets.last - streets.first + 1)).toInt().coerceAtMost(streets.count() - 1)
            val s = streetCoord(k)
            val p = c + (rng.next() * 2f - 1f) * reach
            val alongX = rng.next() < 0.5f
            if (max(abs(p - c), abs(s - c)) >= OUTER_FROM && abs(s - c) <= reach) {
                return if (alongX) Door(p, s, true) else Door(s, p, false)
            }
        }
        // 見つからなければ（狭い帯）、区画の並びのすぐ外の通りの端
        return Door(c + reach, streetCoord(streets.last), true)
    }

    /**
     * [a] から [b] への通りの上の折れ線（最大 4 点）。通りの中心線から [lane] だけずらす。
     * 広場を横切る・短すぎる・長すぎる・城壁に近すぎるなら null。
     */
    private fun route(
        a: Door,
        b: Door,
        lane: Float,
        band: Int,
        rng: Lcg,
    ): Pair<FloatArray, FloatArray>? {
        val streets = streetRange(band)
        val xs = FloatArray(MAX_POINTS)
        val ys = FloatArray(MAX_POINTS)
        var best: Pair<FloatArray, FloatArray>? = null
        var bestScore = Float.MAX_VALUE

        fun consider(
            n: Int,
            score: Float,
        ) {
            val made = clean(xs, ys, n, band) ?: return
            if (score < bestScore) {
                bestScore = score
                best = made
            }
        }
        when {
            a.alongX && b.alongX ->
                if (a.y == b.y) {
                    put(xs, ys, 0, a.x, a.y + lane)
                    put(xs, ys, 1, b.x, b.y + lane)
                    consider(2, 0f)
                } else {
                    for (k in streets) {
                        val s = streetCoord(k)
                        put(xs, ys, 0, a.x, a.y + lane)
                        put(xs, ys, 1, s + lane, a.y + lane)
                        put(xs, ys, 2, s + lane, b.y + lane)
                        put(xs, ys, 3, b.x, b.y + lane)
                        consider(MAX_POINTS, abs(a.x - s) + abs(b.x - s) + rng.next() * TIE_NOISE - s * FRONT_PULL)
                    }
                }
            !a.alongX && !b.alongX ->
                if (a.x == b.x) {
                    put(xs, ys, 0, a.x + lane, a.y)
                    put(xs, ys, 1, b.x + lane, b.y)
                    consider(2, 0f)
                } else {
                    for (k in streets) {
                        val s = streetCoord(k)
                        put(xs, ys, 0, a.x + lane, a.y)
                        put(xs, ys, 1, a.x + lane, s + lane)
                        put(xs, ys, 2, b.x + lane, s + lane)
                        put(xs, ys, 3, b.x + lane, b.y)
                        consider(MAX_POINTS, abs(a.y - s) + abs(b.y - s) + rng.next() * TIE_NOISE - s * FRONT_PULL)
                    }
                }
            a.alongX -> {
                put(xs, ys, 0, a.x, a.y + lane)
                put(xs, ys, 1, b.x + lane, a.y + lane)
                put(xs, ys, 2, b.x + lane, b.y)
                consider(CORNER_POINTS, 0f)
            }
            else -> {
                put(xs, ys, 0, a.x + lane, a.y)
                put(xs, ys, 1, a.x + lane, b.y + lane)
                put(xs, ys, 2, b.x, b.y + lane)
                consider(CORNER_POINTS, 0f)
            }
        }
        return best
    }

    private fun put(
        xs: FloatArray,
        ys: FloatArray,
        i: Int,
        x: Float,
        y: Float,
    ) {
        xs[i] = x
        ys[i] = y
    }

    /** 重なった点を除き、条件（長さ・広場・城壁）を満たす折れ線だけを返す。 */
    private fun clean(
        xs: FloatArray,
        ys: FloatArray,
        n: Int,
        band: Int,
    ): Pair<FloatArray, FloatArray>? {
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        var m = 0
        for (i in 0 until n) {
            if (m > 0 && abs(xs[i] - ox[m - 1]) < JOIN_EPS && abs(ys[i] - oy[m - 1]) < JOIN_EPS) continue
            ox[m] = xs[i]
            oy[m] = ys[i]
            m++
        }
        if (m < 2) return null
        val c = PlotLayout.MID
        val reach = PlotLayout.WALL_HALF[band - 1] - WALL_MARGIN
        var length = 0f
        for (i in 0 until m) {
            if (abs(ox[i] - c) > reach || abs(oy[i] - c) > reach) return null
            if (i == 0) continue
            length += abs(ox[i] - ox[i - 1]) + abs(oy[i] - oy[i - 1])
            if (segmentDistance(PLAZA, PLAZA, ox[i - 1], oy[i - 1], ox[i], oy[i]) < PLAZA_CLEAR) return null
        }
        if (length < MIN_ROUTE || length > MAX_ROUTE) return null
        return ox.copyOf(m) to oy.copyOf(m)
    }

    /** 点 ([px], [py]) から線分 a-b までの距離。 */
    fun segmentDistance(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        val cx = ax + dx * t
        val cy = ay + dy * t
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    private fun roleFor(
        a: BuildingType?,
        b: BuildingType?,
        roll: Float,
    ): WalkerRole =
        when {
            a in CARRIER_SITES || b in CARRIER_SITES -> if (roll < ROLE_SHARE) WalkerRole.CARRIER else WalkerRole.TOWNSFOLK
            a in WORKER_SITES || b in WORKER_SITES -> if (roll < ROLE_SHARE) WalkerRole.WORKER else WalkerRole.TOWNSFOLK
            roll < STRAY_CARRIER -> WalkerRole.CARRIER
            else -> WalkerRole.TOWNSFOLK
        }

    private fun speedFor(
        role: WalkerRole,
        roll: Float,
    ): Float =
        when (role) {
            WalkerRole.CARRIER -> 0.7f + roll * 0.2f
            WalkerRole.WORKER -> 0.8f + roll * 0.25f
            WalkerRole.GUARD -> GUARD_SPEED
            WalkerRole.TOWNSFOLK -> 0.85f + roll * 0.4f
        }

    /** 衛兵: 城の正面の 2 辺を行き来する 2 人と、帯ごとの門の内側を見回る 2 人。 */
    private fun addGuards(
        out: MutableList<Walker>,
        slotCells: List<Triple<Int, Pair<Int, Int>, BuildingType>>,
        rng: Lcg,
    ) {
        val castle = slotCells.firstOrNull { it.third == BuildingType.CASTLE }
        if (castle != null) {
            val x0 = castle.second.first * PlotLayout.CELL
            val y0 = castle.second.second * PlotLayout.CELL
            val front = DOOR_STREET - GUARD_LANE
            out +=
                guard(
                    floatArrayOf(x0 + CASTLE_BEAT_FROM, x0 + CASTLE_BEAT_TO),
                    floatArrayOf(y0 + front, y0 + front),
                    1,
                    Int.MAX_VALUE,
                    rng,
                )
            out +=
                guard(
                    floatArrayOf(x0 + front, x0 + front),
                    floatArrayOf(y0 + CASTLE_BEAT_TO, y0 + CASTLE_BEAT_FROM),
                    SECOND_GUARD_LEVEL,
                    Int.MAX_VALUE,
                    rng,
                )
        }
        for (band in 1..PlotLayout.BANDS) {
            val hi = PlotLayout.MID + PlotLayout.WALL_HALF[band - 1]
            val from = (band - 1) * BAND_LEVELS + 1
            val until = if (band == PlotLayout.BANDS) Int.MAX_VALUE else band * BAND_LEVELS
            val inside = hi - WALL_MARGIN
            out +=
                guard(
                    floatArrayOf(inside, inside),
                    floatArrayOf(hi - GATE_NEAR, hi - GATE_FAR),
                    from,
                    until,
                    rng,
                )
            out +=
                guard(
                    floatArrayOf(hi - GATE_NEAR, hi - GATE_FAR),
                    floatArrayOf(inside, inside),
                    max(from, SECOND_GATE_GUARD_LEVEL),
                    until,
                    rng,
                )
        }
    }

    private fun guard(
        xs: FloatArray,
        ys: FloatArray,
        minLevel: Int,
        maxLevel: Int,
        rng: Lcg,
    ): Walker =
        Walker(
            role = WalkerRole.GUARD,
            xs = xs,
            ys = ys,
            speed = GUARD_SPEED,
            phase = rng.next(),
            pauseSec = GUARD_PAUSE,
            minLevel = minLevel,
            maxLevel = maxLevel,
        )

    private val CARRIER_SITES =
        setOf(BuildingType.FARM, BuildingType.WAREHOUSE, BuildingType.TRADING_POST, BuildingType.GOLD_MINE)
    private val WORKER_SITES =
        setOf(BuildingType.SAWMILL, BuildingType.QUARRY, BuildingType.BUILDERS_HUT, BuildingType.SIEGE_WORKSHOP)
    private const val STREET_MIN = -8
    private const val STREET_MAX = 9
    private const val STREET_REACH = 0.4f
    private const val CELL_MIN = -2
    private const val CELL_MAX = 3
    private const val GUARD_ROOM = 12
    private const val MAX_POINTS = 4
    private const val CORNER_POINTS = 3
    private const val TIE_NOISE = 0.5f

    /** 曲がる通りを選ぶとき、手前（画面の下）の通りを少しだけ優先する（建物の陰に入りにくい）。 */
    private const val FRONT_PULL = 0.08f
    private const val FRONT_WEIGHT = 3
    private const val OUTER_SHARE = 0.4f
    private const val OPEN_SHARE = 0.3f
    private const val LANE_USE = 0.85f
    private const val ROLE_SHARE = 0.7f
    private const val STRAY_CARRIER = 0.12f
    private const val PAUSE_MIN = 1.5f
    private const val PAUSE_SPAN = 3.5f
}
