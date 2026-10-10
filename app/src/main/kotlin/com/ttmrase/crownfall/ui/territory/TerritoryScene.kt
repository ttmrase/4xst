package com.ttmrase.crownfall.ui.territory

import androidx.compose.ui.graphics.Path
import com.ttmrase.crownfall.core.model.BuildingType
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 領地マップの配置（見た目の層だけ。GameState のスロットとレベルは変えない）。
 * 座標は「グリッド単位」: 区画は 4 x 4 の足元を 5 間隔で並べる（隙間 1 が通り）。
 * 画面への射影は sx = (gx - gy) * U、sy = (gx + gy) * V（スプライト px。U:V = 2:1）。
 */
internal object PlotLayout {
    const val U = 14f
    const val V = 7f
    const val CELL = 5f
    const val CENTER = 2f
    const val BANDS = 5

    /**
     * 領地の中心（全区画の外接矩形の中心。グリッド単位）。区画は i, j = -2..3 のセルにあり、足元は 5i..5i+4 なので
     * 外接は -10..19、中心は 4.5。
     */
    const val MID = 4.5f

    /**
     * 城 Lv の帯ごとの城壁の半幅（グリッド単位。中心は [MID]）。最初の帯の壁の内側に全 27 区画のフットプリントが
     * 余裕を持って収まる（壁は区画を横切らない）。広がるのは壁の外側の見た目だけ。
     */
    val WALL_HALF = floatArrayOf(16f, 20f, 24f, 28f, 32f)

    /** 初期カメラが狙う領地の見た目の中心（ワールド座標。区画の外接矩形の中心から、建物の高さぶん上へ）。 */
    const val INITIAL_CENTER_X = 0f
    const val VISUAL_LIFT = 12f
    const val INITIAL_CENTER_Y = (MID + MID) * V - VISUAL_LIFT

    /** 区画の足元の外接矩形の半幅（[MID] から）。 */
    const val PLOTS_HALF = 14.5f

    /** 森を開く余白（城壁の外側）。 */
    const val CLEAR_MARGIN = 1.2f

    private val CELLS: Map<BuildingType, List<Pair<Int, Int>>> =
        mapOf(
            BuildingType.CASTLE to listOf(0 to 0),
            BuildingType.FARM to listOf(0 to 1, 0 to 2, -1 to 2, 0 to 3),
            BuildingType.SAWMILL to listOf(1 to 0, 2 to -1, 2 to 0, 3 to 0),
            BuildingType.QUARRY to listOf(-1 to 0, -2 to 0, -2 to -1),
            BuildingType.GOLD_MINE to listOf(0 to -1, -1 to -2),
            BuildingType.WAREHOUSE to listOf(1 to 1),
            BuildingType.WALL to listOf(-1 to -1),
            BuildingType.ACADEMY to listOf(1 to -1),
            BuildingType.TAVERN to listOf(-1 to 1),
            BuildingType.WATCHTOWER to listOf(-2 to -2),
            BuildingType.HOSPITAL to listOf(0 to -2),
            BuildingType.SIEGE_WORKSHOP to listOf(1 to -2),
            BuildingType.ALLIANCE_CENTER to listOf(2 to -2),
            BuildingType.TRADING_POST to listOf(2 to 1),
            BuildingType.ARCHERY_RANGE to listOf(2 to 2),
            BuildingType.BARRACKS to listOf(1 to 2),
            BuildingType.STABLE to listOf(-2 to 2),
            BuildingType.BUILDERS_HUT to listOf(-2 to 1),
        )

    /** スロット列から (slotId, セル, 種類) を作る。同じ種類は並び順にセルを割り当てる。 */
    fun cellsFor(
        slots: List<com.ttmrase.crownfall.core.model.BuildingSlot>,
    ): List<Triple<Int, Pair<Int, Int>, BuildingType>> {
        val perType = HashMap<BuildingType, Int>()
        return slots.map { slot ->
            val idx = perType.getOrDefault(slot.type, 0)
            perType[slot.type] = idx + 1
            Triple(slot.slotId, cellOf(slot.type, idx), slot.type)
        }
    }

    /** セル (i, j) の足元の矩形 [x0, x0 + 4] x [y0, y0 + 4]（グリッド単位）の左上。 */
    fun footprintOrigin(cell: Pair<Int, Int>): Pair<Float, Float> = (cell.first * CELL) to (cell.second * CELL)

    /** 種類 [type] の [index] 番目のスロットが置かれるセル (i, j)。 */
    fun cellOf(
        type: BuildingType,
        index: Int,
    ): Pair<Int, Int> = CELLS[type]?.getOrNull(index) ?: ((4 + index) to 4)

    /** セル (i, j) の足元の中心（グリッド座標）。 */
    fun centerOf(cell: Pair<Int, Int>): Pair<Float, Float> =
        (cell.first * CELL + CENTER) to (cell.second * CELL + CENTER)

    /** 建物 Lv（0 は未建設）から見た目の段階 1..5。 */
    fun tierOfLevel(level: Int): Int = ((level - 1) / 5 + 1).coerceIn(1, 5)

    /** 城 Lv から領地の帯 1..5。 */
    fun bandOfCastle(level: Int): Int = tierOfLevel(level)

    fun wx(
        gx: Float,
        gy: Float,
    ): Float = (gx - gy) * U

    fun wy(
        gx: Float,
        gy: Float,
    ): Float = (gx + gy) * V

    /** 次の帯が開く城 Lv（最大帯なら null）。 */
    fun nextBandCastleLevel(band: Int): Int? = if (band >= BANDS) null else band * 5 + 1
}

/** 描く対象の種類。 */
internal enum class EntKind { TREE, DECOR, HOUSE, STALL, FIELD, MANOR, STATUE, WALL, TOWER, GATE, LAMP, SIGN, BUILDING }

/**
 * 1 つの描画対象。[gx] [gy] はスプライトの原点（建物は区画の足元の中心）。
 * [introBand] 以上の帯で現れ、[goneBand] 以上の帯で消える（森が切り開かれる・古い城壁が撤去される）。
 */
internal class Ent(
    val kind: EntKind,
    val gx: Float,
    val gy: Float,
    val sprite: Int,
    val introBand: Int = 1,
    val goneBand: Int = 99,
    val slotId: Int = -1,
    val seed: Float = 0f,
    /** 立て札などが属する帯（この札の「Lv n」は帯から決まる）。 */
    val band: Int = 0,
) {
    val depth: Float = gx + gy
}

/** 通りを歩く住民。 */
internal class Villager(
    val alongX: Boolean,
    val fixed: Float,
    val from: Float,
    val to: Float,
    val speed: Float,
    val phase: Float,
    val color: Int,
    val introBand: Int,
)

/** 領地マップの静的なシーン（決定論的に生成する）。帯ごとの地面・通りの Path もここで 1 度だけ作る。 */
internal class TerritoryScene(
    val ents: Array<Ent>,
    val villagers: List<Villager>,
    val lawn: List<Path>,
    val streets: List<Path>,
    val river: Path,
    val buildingEnts: List<Ent>,
) {
    companion object {
        private const val TREE_STEP = 2.4f
        private const val TREE_RANGE = 22
        private const val RIVER_X = 45f
        private const val TOWN_STEP = 2.5f

        fun build(slotCells: List<Triple<Int, Pair<Int, Int>, BuildingType>>): TerritoryScene {
            val rng = Lcg(20_261_010L)
            val ents = ArrayList<Ent>(1600)
            val c = PlotLayout.MID
            val half = PlotLayout.WALL_HALF

            fun bandInside(
                x: Float,
                y: Float,
                margin: Float,
            ): Int {
                val d = max(abs(x - c), abs(y - c))
                for (b in 1..PlotLayout.BANDS) if (d <= half[b - 1] + margin) return b
                return 99
            }

            fun onPlot(
                x: Float,
                y: Float,
                pad: Float,
            ): Boolean =
                slotCells.any { (_, cell, _) ->
                    val x0 = cell.first * PlotLayout.CELL
                    val y0 = cell.second * PlotLayout.CELL
                    x >= x0 - pad && x <= x0 + 4f + pad && y >= y0 - pad && y <= y0 + 4f + pad
                }

            fun nearStreet(
                v: Float,
                tol: Float,
            ): Boolean {
                val k = floor((v + 0.5f) / PlotLayout.CELL + 0.5f)
                return abs(v - (k * PlotLayout.CELL - 0.5f)) < tol
            }

            // 建物（区画）
            val buildings = ArrayList<Ent>()
            for ((slotId, cell, type) in slotCells) {
                val (gx, gy) = PlotLayout.centerOf(cell)
                val e = Ent(EntKind.BUILDING, gx, gy, SpriteIds.building(type, 1), slotId = slotId, seed = rng.next())
                buildings.add(e)
                ents.add(e)
            }

            // 森（帯が進むと切り開かれる）
            val treeSprites =
                intArrayOf(
                    SpriteIds.TREE_OAK_A,
                    SpriteIds.TREE_OAK_B,
                    SpriteIds.TREE_OAK_C,
                    SpriteIds.TREE_PINE_A,
                    SpriteIds.TREE_PINE_B,
                    SpriteIds.TREE_BIRCH,
                    SpriteIds.TREE_PINE_A,
                    SpriteIds.TREE_OAK_A,
                )
            for (a in -TREE_RANGE..TREE_RANGE) {
                for (b in -TREE_RANGE..TREE_RANGE) {
                    val x = c + a * TREE_STEP + (rng.next() - 0.5f) * 1.6f
                    val y = c + b * TREE_STEP + (rng.next() - 0.5f) * 1.6f
                    val gone = bandInside(x, y, PlotLayout.CLEAR_MARGIN)
                    if (gone == 1 || onPlot(x, y, 1.2f)) continue
                    val pick = rng.next()
                    val sprite = treeSprites[(pick * treeSprites.size).toInt().coerceIn(0, treeSprites.size - 1)]
                    ents.add(Ent(EntKind.TREE, x, y, sprite, goneBand = gone, seed = rng.next()))
                }
            }

            // 草地の小物（茂み・岩）。芝の見た目を単調にしない
            for (a in -12..12) {
                for (b in -12..12) {
                    val x = c + a * 4.1f + (rng.next() - 0.5f) * 2.5f
                    val y = c + b * 4.1f + (rng.next() - 0.5f) * 2.5f
                    val intro = bandInside(x, y, -1.5f)
                    if (intro > PlotLayout.BANDS || onPlot(x, y, 1.0f) || nearStreet(x, 1.4f) || nearStreet(y, 1.4f)) {
                        continue
                    }
                    val r = rng.next()
                    if (r < 0.34f) {
                        val s =
                            if (r < 0.17f) {
                                SpriteIds.BUSH
                            } else if (r < 0.26f) {
                                SpriteIds.ROCK_A
                            } else {
                                SpriteIds.ROCK_B
                            }
                        ents.add(Ent(EntKind.DECOR, x, y, s, introBand = intro, seed = rng.next()))
                    }
                }
            }

            // 城下町（壁の内側のリングが帯ごとに広がり、外側ほど家・市場・畑・屋敷・像が増えて豪華になる）
            val townKinds =
                arrayOf(
                    // 帯 2: 家・露店・畑
                    floatArrayOf(0.50f, 0.75f, 1.00f, 1.00f, 1.00f),
                    // 帯 3: 家・露店・畑・屋敷
                    floatArrayOf(0.50f, 0.70f, 0.82f, 1.00f, 1.00f),
                    // 帯 4: 家・露店・畑・屋敷・像
                    floatArrayOf(0.34f, 0.48f, 0.58f, 0.96f, 1.00f),
                    // 帯 5: 家・露店・畑・屋敷・像
                    floatArrayOf(0.22f, 0.32f, 0.36f, 0.86f, 1.00f),
                )
            val houseSprites = intArrayOf(SpriteIds.HOUSE_A, SpriteIds.HOUSE_B, SpriteIds.HOUSE_C)
            for (a in -15..15) {
                for (b in -15..15) {
                    val x = c + a * TOWN_STEP + (rng.next() - 0.5f) * 0.8f
                    val y = c + b * TOWN_STEP + (rng.next() - 0.5f) * 0.8f
                    val intro = bandInside(x, y, -2.0f)
                    if (intro !in 2..PlotLayout.BANDS) continue
                    if (onPlot(x, y, 1.7f) || nearStreet(x, 1.8f) || nearStreet(y, 1.8f)) continue
                    if (rng.next() > 0.30f + 0.06f * intro) continue
                    val cum = townKinds[intro - 2]
                    val k = rng.next()
                    val kind = cum.indexOfFirst { k <= it }
                    val house = houseSprites[(rng.next() * 3).toInt().coerceIn(0, 2)]
                    val (entKind, sprite) =
                        when (kind) {
                            0 -> EntKind.HOUSE to house
                            1 -> EntKind.STALL to SpriteIds.STALL
                            2 -> EntKind.FIELD to SpriteIds.FIELD
                            3 -> EntKind.MANOR to SpriteIds.MANOR
                            else -> EntKind.STATUE to SpriteIds.STATUE
                        }
                    ents.add(Ent(entKind, x, y, sprite, introBand = intro, seed = rng.next()))
                }
            }

            // 空き区画の広場（建物のない区画は、手前の市場街と広場にする。帯が上がると屋敷・噴水・像が増える）
            val used = slotCells.map { it.second }.toSet()
            val free =
                (-2..3).flatMap { i -> (-2..3).map { j -> i to j } }
                    .filter { it !in used }
                    .sortedWith(compareBy({ it.first + it.second }, { it.first }))
            val corner = free.lastOrNull()
            free.forEachIndexed { k, cell ->
                val (gx, gy) = PlotLayout.centerOf(cell)
                if (cell == corner) {
                    ents.add(Ent(EntKind.STALL, gx, gy, SpriteIds.STALL, goneBand = 2, seed = rng.next()))
                    ents.add(Ent(EntKind.DECOR, gx, gy, SpriteIds.FOUNTAIN, introBand = 2))
                    ents.add(Ent(EntKind.STATUE, gx - 2.6f, gy, SpriteIds.STATUE, introBand = 4, seed = rng.next()))
                    ents.add(Ent(EntKind.STATUE, gx, gy - 2.6f, SpriteIds.STATUE, introBand = 4, seed = rng.next()))
                } else if (k % 2 == 0) {
                    ents.add(Ent(EntKind.STALL, gx, gy, SpriteIds.STALL, goneBand = 3, seed = rng.next()))
                    ents.add(Ent(EntKind.MANOR, gx, gy, SpriteIds.MANOR, introBand = 3, seed = rng.next()))
                } else {
                    val house = houseSprites[k % houseSprites.size]
                    ents.add(Ent(EntKind.HOUSE, gx, gy, house, seed = rng.next()))
                }
            }

            // 街灯（交差点の一部）
            for (i in -7..8) {
                for (j in -7..8) {
                    if ((i + j) % 2 != 0) continue
                    val x = i * PlotLayout.CELL - 0.5f
                    val y = j * PlotLayout.CELL - 0.5f
                    val intro = bandInside(x, y, -1.0f)
                    if (intro <= PlotLayout.BANDS) {
                        ents.add(Ent(EntKind.LAMP, x + 0.35f, y + 0.35f, SpriteIds.LAMP, introBand = intro))
                    }
                }
            }

            // 広場: 井戸 → 噴水（城の前の交差点）
            ents.add(Ent(EntKind.DECOR, 4.5f, 4.5f, SpriteIds.WELL, introBand = 1, goneBand = 3))
            ents.add(Ent(EntKind.DECOR, 4.5f, 4.5f, SpriteIds.FOUNTAIN, introBand = 3))

            // 城壁（帯ごとの輪。古い輪は撤去され、新しい輪が現れる）
            for (band in 1..PlotLayout.BANDS) {
                val h = half[band - 1]
                val lo = c - h
                val hi = c + h
                var p = lo + 1f
                while (p < hi - 0.5f) {
                    ents.add(Ent(EntKind.WALL, p, lo, SpriteIds.wallX(band), band, band + 1))
                    ents.add(Ent(EntKind.WALL, p, hi, SpriteIds.wallX(band), band, band + 1))
                    ents.add(Ent(EntKind.WALL, lo, p, SpriteIds.wallY(band), band, band + 1))
                    ents.add(Ent(EntKind.WALL, hi, p, SpriteIds.wallY(band), band, band + 1))
                    p += 2f
                }
                ents.add(Ent(EntKind.TOWER, lo, lo, SpriteIds.wallTower(band), band, band + 1))
                ents.add(Ent(EntKind.TOWER, hi, lo, SpriteIds.wallTower(band), band, band + 1))
                ents.add(Ent(EntKind.TOWER, lo, hi, SpriteIds.wallTower(band), band, band + 1))
                ents.add(Ent(EntKind.GATE, hi, hi, SpriteIds.gate(band), band, band + 1))
                // 外側の帯ほど塔が増える（帯 3 以上は各辺の中央、帯 5 は四分点にも）
                if (band >= 3) {
                    val mid = (lo + hi) / 2f
                    ents.add(Ent(EntKind.TOWER, mid, lo, SpriteIds.wallTower(band), band, band + 1))
                    ents.add(Ent(EntKind.TOWER, lo, mid, SpriteIds.wallTower(band), band, band + 1))
                    ents.add(Ent(EntKind.TOWER, hi, mid, SpriteIds.wallTower(band), band, band + 1))
                    ents.add(Ent(EntKind.TOWER, mid, hi, SpriteIds.wallTower(band), band, band + 1))
                }
                if (band >= 5) {
                    val q1 = lo + (hi - lo) / 4f
                    val q3 = lo + (hi - lo) * 3f / 4f
                    for (q in floatArrayOf(q1, q3)) {
                        ents.add(Ent(EntKind.TOWER, q, lo, SpriteIds.wallTower(band), band, band + 1))
                        ents.add(Ent(EntKind.TOWER, lo, q, SpriteIds.wallTower(band), band, band + 1))
                        ents.add(Ent(EntKind.TOWER, hi, q, SpriteIds.wallTower(band), band, band + 1))
                        ents.add(Ent(EntKind.TOWER, q, hi, SpriteIds.wallTower(band), band, band + 1))
                    }
                }
                // 「Lv n で拡張」の立て札（次の帯がある間、前の角の外側）
                if (band < PlotLayout.BANDS) {
                    ents.add(Ent(EntKind.SIGN, hi + 3.2f, hi - 0.5f, SpriteIds.SIGNPOST, band, band + 1, band = band))
                }
            }

            ents.sortWith(compareBy<Ent> { it.depth }.thenBy { it.kind.ordinal })

            // 住民
            val villagers = ArrayList<Villager>()
            val colors = intArrayOf(0xFFE6B450.toInt(), 0xFFC8553D.toInt(), 0xFF4F8FD0.toInt(), 0xFF7FB85A.toInt())
            for (k in 0 until 14) {
                val street = (rng.next() * 6).toInt() - 2
                val fixed = street * PlotLayout.CELL - 0.5f
                val intro = 1 + k / 3
                villagers.add(
                    Villager(
                        alongX = k % 2 == 0,
                        fixed = fixed,
                        from = c - half[min(intro, 5) - 1] + 1.5f,
                        to = c + half[min(intro, 5) - 1] - 1.5f,
                        speed = 0.9f + rng.next() * 0.9f,
                        phase = rng.next(),
                        color = colors[k % colors.size],
                        introBand = intro,
                    ),
                )
            }

            return TerritoryScene(
                ents = ents.toTypedArray(),
                villagers = villagers,
                lawn = (1..PlotLayout.BANDS).map { lawnPath(half[it - 1] + PlotLayout.CLEAR_MARGIN) },
                streets = (1..PlotLayout.BANDS).map { streetsPath(half[it - 1] + 0.4f) },
                river = riverPath(),
                buildingEnts = buildings,
            )
        }

        private fun lawnPath(h: Float): Path =
            Path().apply {
                val c = PlotLayout.MID
                moveTo(c - h, c - h)
                lineTo(c + h, c - h)
                lineTo(c + h, c + h)
                lineTo(c - h, c + h)
                close()
            }

        /** 通り（隙間の幅 1）。区画の外へも、帯の範囲まで延ばす。 */
        private fun streetsPath(h: Float): Path =
            Path().apply {
                val c = PlotLayout.MID
                val lo = c - h
                val hi = c + h
                var k = -8
                while (k <= 9) {
                    val v = k * PlotLayout.CELL - 0.5f
                    if (v - 0.5f > lo && v + 0.5f < hi) {
                        addRectPoly(v - 0.5f, lo, v + 0.5f, hi)
                        addRectPoly(lo, v - 0.5f, hi, v + 0.5f)
                    }
                    k++
                }
            }

        private fun Path.addRectPoly(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
        ) {
            moveTo(x0, y0)
            lineTo(x1, y0)
            lineTo(x1, y1)
            lineTo(x0, y1)
            close()
        }

        /** 川の中心線（最大の城壁の外側をゆるく蛇行する）。 */
        private fun riverPath(): Path =
            Path().apply {
                var gy = -64f
                moveTo(RIVER_X + 1.6f * kotlin.math.sin(gy * 0.11f), gy)
                while (gy < 74f) {
                    gy += 2f
                    lineTo(RIVER_X + 1.6f * kotlin.math.sin(gy * 0.11f), gy)
                }
            }
    }
}

/** 決定論的な乱数（見た目の配置用。ゲームの結果には一切関わらない）。 */
internal class Lcg(seed: Long) {
    private var s = seed

    /** 0 以上 1 未満。 */
    fun next(): Float {
        s = (s * 6364136223846793005L + 1442695040888963407L)
        return ((s ushr 33).toInt() and 0x7FFFFFFF) / 2147483648f
    }
}
