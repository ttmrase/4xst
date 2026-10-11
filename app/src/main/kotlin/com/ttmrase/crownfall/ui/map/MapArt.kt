package com.ttmrase.crownfall.ui.map

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.Terrain
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.ui.territory.SpriteCache
import com.ttmrase.crownfall.ui.territory.SpriteIds
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ワールドマップの地面の絵（M11 T11.4）。すべて手続き的なベクター描画（外部画像なし）。
 * 8×8 タイルのチャンクごとにビットマップへ 1 度だけ焼き、毎フレームは貼るだけにする（[MapChunkCache]）。
 * - バイオーム: 低周波のノイズで草原の色味（緑 / 黄緑 / 乾いた土）、山の雪、森の濃淡を変える。
 * - 隣り合う地形の境目はぼかす（辺ごとのグラデーション。マーチングスクエアの代わりに 4 辺と 4 隅で近似）。
 * - 海岸: 陸の縁に砂と白い波、水は岸に近いほど浅い色。細い水の連なりは川（岸に砂）。
 * - 道: 自城から同盟の城・NPC 城・砦・聖地へ、水をよけて引く土の道。
 * - 飾り: 森の木、山の峰（雪帽子）、草原の草花と岩。ズームが遠いと省く（LOD）。
 * 出力は [seed] だけで決まる（決定論）。
 */
internal object MapArt {
    const val CHUNK = 8

    private val PLAIN_A = Color(0xFF6FA35A)
    private val PLAIN_B = Color(0xFF93B85A)
    private val PLAIN_DRY = Color(0xFFB4AE62)
    private val FOREST_A = Color(0xFF2F6B3A)
    private val FOREST_B = Color(0xFF3F7F3A)
    private val ROCK_A = Color(0xFF8A8579)
    private val ROCK_B = Color(0xFFA29C90)
    private val SNOW = Color(0xFFF1F4F8)
    private val SAND = Color(0xFFD8C690)
    private val SHALLOW = Color(0xFF5FA4D8)
    private val DEEP = Color(0xFF2E6AAE)
    private val FOAM = Color(0xCCFFFFFF)
    private val ROAD = Color(0xFFB8955A)
    private val ROAD_EDGE = Color(0xFF7E6338)
    private val SNOWFIELD = Color(0xFFE9EFF3)
    private val SNOW_FOREST = Color(0xFF9DB7AE)
    private val ICE = Color(0xFFBFDDEE)
    private val DESERT = Color(0xFFE0C987)
    private val DRY_FOREST = Color(0xFF8A8140)
    private val BANK = Color(0xFFBDB27C)

    /** 北の端の雪原の強さ 0..1（y が小さいほど強い。縁はノイズで揺らす）。 */
    fun snowT(
        x: Int,
        y: Int,
        size: Int,
        seed: Long,
    ): Float {
        val line = size * 0.14f + (noise(x * 0.07f, y * 0.07f, seed, 21) - 0.5f) * size * 0.08f
        return smooth((line - y) / 6f)
    }

    /** 南東の砂漠の強さ 0..1（(x + y) が大きいほど強い）。 */
    fun sandT(
        x: Int,
        y: Int,
        size: Int,
        seed: Long,
    ): Float {
        val line = 0.7f + (noise(x * 0.07f, y * 0.07f, seed, 22) - 0.5f) * 0.1f
        return smooth(((x + y) / (2f * size) - line) / 0.08f)
    }

    /** 川（細い水の連なり）か。まわり 5×5 の水が少ない水タイルを川とみなす（湖の縁は含めない）。 */
    fun isRiver(
        world: WorldMap,
        x: Int,
        y: Int,
    ): Boolean {
        if (!world.inBounds(x, y) || world.terrainAt(x, y) != Terrain.WATER) return false
        var water = 0
        for (dy in -2..2) for (dx in -2..2) {
            if (terrainAtOrSelf(world, x + dx, y + dy, Terrain.PLAIN) == Terrain.WATER) water++
        }
        return water <= RIVER_MAX_WATER
    }

    /**
     * 道（タイル座標の折れ線）。自城と主要な拠点（同盟の城・NPC 城・聖地）を、最小全域木とそこへ少しの追加の辺でつなぐ。
     * 水を避けて歩き、折れ線にゆるいゆらぎを付ける（見た目だけ。:core の行軍は直線で、一致しない）。世界ごとに 1 度だけ作る。
     */
    fun roads(world: WorldMap): List<List<Offset>> {
        val castle = world.structures.firstOrNull { it.type == StructureType.PLAYER_CASTLE } ?: return emptyList()
        val others = world.structures.filter { it !== castle && it.type != StructureType.BARBARIAN_FORT }
        val nodes = listOf(castle) + others
        if (nodes.size < 2) return emptyList()

        fun len(
            i: Int,
            j: Int,
        ): Float = hypot((nodes[i].x - nodes[j].x).toFloat(), (nodes[i].y - nodes[j].y).toFloat())
        // 最小全域木（Prim 法。出発は自城）
        val inTree = BooleanArray(nodes.size)
        val best = FloatArray(nodes.size) { Float.MAX_VALUE }
        val from = IntArray(nodes.size) { -1 }
        best[0] = 0f
        val edges = ArrayList<Pair<Int, Int>>()
        repeat(nodes.size) {
            var u = -1
            for (i in nodes.indices) if (!inTree[i] && (u < 0 || best[i] < best[u])) u = i
            inTree[u] = true
            if (from[u] >= 0) edges += from[u] to u
            for (v in nodes.indices) {
                if (!inTree[v] && len(u, v) < best[v]) {
                    best[v] = len(u, v)
                    from[v] = u
                }
            }
        }
        // 追加の辺: 木に無い組のうち、短いものを少しだけ（環ができて、道が網目らしくなる）
        val used = edges.map { (a, b) -> minOf(a, b) to maxOf(a, b) }.toSet()
        val extras =
            nodes.indices.flatMap { i -> (i + 1 until nodes.size).map { j -> i to j } }
                .filter { it !in used }
                .sortedBy { len(it.first, it.second) }
                .take(EXTRA_ROADS)
        val out = ArrayList<List<Offset>>()
        for ((i, j) in edges + extras) {
            val line =
                path(world, nodes[i].x, nodes[i].y, nodes[j].x, nodes[j].y)
                    ?: path(world, nodes[j].x, nodes[j].y, nodes[i].x, nodes[i].y)?.reversed()
            if (line != null) out += wobble(line, i * 31 + j)
        }
        return out
    }

    /** 折れ線の途中の点を、タイルの中でゆらがせる（両端は動かさない）。決定論。 */
    private fun wobble(
        line: List<Offset>,
        salt: Int,
    ): List<Offset> =
        line.mapIndexed { k, o ->
            if (k == 0 || k == line.lastIndex) {
                o
            } else {
                Offset(
                    o.x + (hash(k, salt, 7L, 31) - 0.5f) * ROAD_WOBBLE,
                    o.y + (hash(k, salt, 7L, 32) - 0.5f) * ROAD_WOBBLE,
                )
            }
        }

    private fun path(
        world: WorldMap,
        sx: Int,
        sy: Int,
        tx: Int,
        ty: Int,
    ): List<Offset>? {
        var x = sx
        var y = sy
        val pts = arrayListOf(Offset(x + 0.5f, y + 0.5f))
        var guard = world.size * 4
        while ((x != tx || y != ty) && guard-- > 0) {
            val dx = Integer.signum(tx - x)
            val dy = Integer.signum(ty - y)
            val options = listOf(dx to dy, dx to 0, 0 to dy, dx to -dy, -dx to dy).filter { (a, b) -> a != 0 || b != 0 }
            val next =
                options.firstOrNull { (a, b) ->
                    world.inBounds(x + a, y + b) && world.terrainAt(x + a, y + b) != Terrain.WATER &&
                        (abs(tx - (x + a)) + abs(ty - (y + b)) < abs(tx - x) + abs(ty - y))
                } ?: return null
            x += next.first
            y += next.second
            pts += Offset(x + 0.5f, y + 0.5f)
        }
        return pts
    }

    // ---- ノイズ（seed だけで決まる）----
    private fun hash(
        x: Int,
        y: Int,
        seed: Long,
        salt: Int = 0,
    ): Float {
        var h = (x * 374761393L) xor (y * 668265263L) xor (seed * 1274126177L) xor (salt * 2246822519L)
        h = (h xor (h ushr 13)) * 1274126177L
        h = h xor (h ushr 16)
        return ((h and 0xFFFF).toInt()) / 65535f
    }

    /** 低周波の値ノイズ（0..1）。 */
    fun noise(
        x: Float,
        y: Float,
        seed: Long,
        salt: Int = 0,
    ): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = hash(x0, y0, seed, salt)
        val b = hash(x0 + 1, y0, seed, salt)
        val c = hash(x0, y0 + 1, seed, salt)
        val d = hash(x0 + 1, y0 + 1, seed, salt)
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
    }

    private fun terrainAtOrSelf(
        world: WorldMap,
        x: Int,
        y: Int,
        fallback: Terrain,
    ): Terrain = if (world.inBounds(x, y)) world.terrainAt(x, y) else fallback

    private fun baseColor(
        world: WorldMap,
        x: Int,
        y: Int,
        seed: Long,
        t: Terrain,
    ): Color {
        val n = noise(x * 0.18f, y * 0.18f, seed, 1)
        val jitter = (hash(x, y, seed, 2) - 0.5f) * 0.08f
        val c =
            when (t) {
                Terrain.PLAIN -> {
                    val dry = noise(x * 0.09f, y * 0.09f, seed, 3)
                    lerp(lerp(PLAIN_A, PLAIN_B, n), PLAIN_DRY, ((dry - 0.55f) * 2f).coerceIn(0f, 0.7f))
                }
                Terrain.FOREST -> lerp(FOREST_A, FOREST_B, n)
                Terrain.MOUNTAIN -> lerp(ROCK_A, ROCK_B, n)
                Terrain.WATER -> {
                    var near = 3
                    for (r in 1..2) {
                        for (dy in -r..r) for (dx in -r..r) {
                            if (world.inBounds(x + dx, y + dy) && world.terrainAt(x + dx, y + dy) != Terrain.WATER) {
                                near = minOf(near, maxOf(abs(dx), abs(dy)))
                            }
                        }
                    }
                    lerp(SHALLOW, DEEP, ((near - 1) / 2f).coerceIn(0f, 1f) * 0.9f + n * 0.1f)
                }
            }
        val size = world.size
        val snow = snowT(x, y, size, seed)
        val sand = sandT(x, y, size, seed)
        var out = c
        if (snow > 0f) {
            out =
                when (t) {
                    Terrain.PLAIN -> lerp(out, SNOWFIELD, snow * 0.92f)
                    Terrain.FOREST -> lerp(out, SNOW_FOREST, snow * 0.65f)
                    Terrain.WATER -> lerp(out, ICE, snow * 0.55f)
                    Terrain.MOUNTAIN -> lerp(out, SNOW, snow * 0.5f)
                }
        }
        if (sand > 0f) {
            out =
                when (t) {
                    Terrain.PLAIN -> lerp(out, DESERT, sand * 0.95f)
                    Terrain.FOREST -> lerp(out, DRY_FOREST, sand * 0.75f)
                    Terrain.MOUNTAIN -> lerp(out, Color(0xFFB59A74), sand * 0.5f)
                    Terrain.WATER -> out
                }
        }
        return shift(out, jitter)
    }

    private fun shift(
        c: Color,
        d: Float,
    ): Color =
        Color(
            (c.red + d).coerceIn(0f, 1f),
            (c.green + d).coerceIn(0f, 1f),
            (c.blue + d).coerceIn(0f, 1f),
            c.alpha,
        )

    /**
     * チャンク ([cx], [cy]) を [px]（1 タイルの px）で描く。呼び出し側が (0,0)〜(CHUNK*px) のキャンバスを用意する。
     * [detail] が false なら飾りを省く（遠いズーム）。
     */
    fun DrawScope.drawChunk(
        world: WorldMap,
        cx: Int,
        cy: Int,
        px: Float,
        seed: Long,
        roads: List<List<Offset>>,
        detail: Boolean,
        sprites: SpriteCache? = null,
    ) {
        val x0 = cx * CHUNK
        val y0 = cy * CHUNK
        // 地面（隣のタイルへ少しはみ出して描き、チャンクの継ぎ目を消す）
        for (ty in -1..CHUNK) {
            for (tx in -1..CHUNK) {
                val x = x0 + tx
                val y = y0 + ty
                if (!world.inBounds(x, y)) continue
                val t = world.terrainAt(x, y)
                drawRect(baseColor(world, x, y, seed, t), Offset(tx * px, ty * px), Size(px + 1f, px + 1f))
            }
        }
        // 川: 川のタイルを岸の色にして、その上につながった水の線（丸い端）を引く
        drawRivers(world, x0, y0, px)
        // 境目のぼかし・海岸
        for (ty in 0 until CHUNK) {
            for (tx in 0 until CHUNK) {
                val x = x0 + tx
                val y = y0 + ty
                if (!world.inBounds(x, y)) continue
                edges(world, x, y, tx * px, ty * px, px, seed)
            }
        }
        // 道
        if (px >= ROAD_MIN_PX) drawRoads(roads, x0, y0, px)
        // 飾り
        if (detail) {
            for (ty in 0 until CHUNK) {
                for (tx in 0 until CHUNK) {
                    val x = x0 + tx
                    val y = y0 + ty
                    if (!world.inBounds(x, y) || world.structureAt(x, y) != null) continue
                    decor(
                        world.terrainAt(x, y), x, y, tx * px, ty * px, px, seed,
                        snowT(x, y, world.size, seed), sandT(x, y, world.size, seed), sprites == null,
                    )
                }
            }
            if (sprites != null) drawForest(world, x0, y0, px, seed, sprites)
        }
    }

    private fun DrawScope.drawRivers(
        world: WorldMap,
        x0: Int,
        y0: Int,
        px: Float,
    ) {
        for (ty in -1..CHUNK) {
            for (tx in -1..CHUNK) {
                if (isRiver(world, x0 + tx, y0 + ty)) drawRect(BANK, Offset(tx * px, ty * px), Size(px + 1f, px + 1f))
            }
        }
        val w = px * 0.62f
        for (ty in -1..CHUNK) {
            for (tx in -1..CHUNK) {
                val x = x0 + tx
                val y = y0 + ty
                if (!isRiver(world, x, y)) continue
                val c = Offset((tx + 0.5f) * px, (ty + 0.5f) * px)
                var linked = false
                for ((dx, dy) in RIVER_LINKS) {
                    val nx = x + dx
                    val ny = y + dy
                    if (world.inBounds(nx, ny) && world.terrainAt(nx, ny) == Terrain.WATER) {
                        linked = true
                        val o = Offset((tx + dx + 0.5f) * px, (ty + dy + 0.5f) * px)
                        drawLine(DEEP, c, o, strokeWidth = w, cap = StrokeCap.Round)
                        drawLine(SHALLOW, c, o, strokeWidth = w * 0.55f, cap = StrokeCap.Round)
                    }
                }
                if (!linked) drawCircle(DEEP, w / 2f, c)
            }
        }
    }

    private val RIVER_LINKS = listOf(1 to 0, 0 to 1, -1 to 0, 0 to -1)

    private fun DrawScope.edges(
        world: WorldMap,
        x: Int,
        y: Int,
        ox: Float,
        oy: Float,
        px: Float,
        seed: Long,
    ) {
        val t = world.terrainAt(x, y)
        val dirs = arrayOf(intArrayOf(0, -1), intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0))
        for ((i, d) in dirs.withIndex()) {
            val nx = x + d[0]
            val ny = y + d[1]
            if (!world.inBounds(nx, ny)) continue
            val nt = world.terrainAt(nx, ny)
            if (nt == t) continue
            val strip = px * 0.28f
            val (rx, ry, rw, rh) =
                when (i) {
                    0 -> arrayOf(ox, oy, px, strip)
                    1 -> arrayOf(ox + px - strip, oy, strip, px)
                    2 -> arrayOf(ox, oy + px - strip, px, strip)
                    else -> arrayOf(ox, oy, strip, px)
                }
            val land = t != Terrain.WATER
            if (land && nt == Terrain.WATER) {
                // 陸の縁: 砂と波の白い線
                val sand = px * 0.12f
                val (sx, sy, sw, sh) =
                    when (i) {
                        0 -> arrayOf(ox, oy, px, sand)
                        1 -> arrayOf(ox + px - sand, oy, sand, px)
                        2 -> arrayOf(ox, oy + px - sand, px, sand)
                        else -> arrayOf(ox, oy, sand, px)
                    }
                drawRect(SAND, Offset(sx, sy), Size(sw, sh))
                val foam = px * 0.05f
                val (fx, fy, fw, fh) =
                    when (i) {
                        0 -> arrayOf(ox, oy - foam, px, foam * 2)
                        1 -> arrayOf(ox + px - foam, oy, foam * 2, px)
                        2 -> arrayOf(ox, oy + px - foam, px, foam * 2)
                        else -> arrayOf(ox - foam, oy, foam * 2, px)
                    }
                if (!isRiver(world, nx, ny)) drawRect(FOAM, Offset(fx, fy), Size(fw, fh))
            } else if (!land && nt != Terrain.WATER) {
                if (isRiver(world, x, y)) continue
                // 水の縁: 浅い色へ
                val shallow =
                    Brush.linearGradient(
                        listOf(SHALLOW.copy(alpha = 0.7f), Color.Transparent),
                        start = Offset(rx + if (i == 1) rw else 0f, ry + if (i == 2) rh else 0f),
                        end =
                            Offset(
                                rx +
                                    if (i == 3) {
                                        rw
                                    } else if (i == 1) {
                                        0f
                                    } else {
                                        rw / 2f
                                    },
                                ry +
                                    if (i == 0) {
                                        rh
                                    } else if (i == 2) {
                                        0f
                                    } else {
                                        rh / 2f
                                    },
                            ),
                    )
                drawRect(shallow, Offset(rx, ry), Size(rw, rh))
            } else {
                // 陸どうしの境目: 隣の色をにじませる
                val c = baseColor(world, nx, ny, seed, nt).copy(alpha = 0.55f)
                val edgeStart = Offset(rx + if (i == 1) rw else 0f, ry + if (i == 2) rh else 0f)
                val edgeEnd =
                    Offset(
                        rx +
                            if (i == 3) {
                                rw
                            } else if (i == 1) {
                                0f
                            } else {
                                rw / 2f
                            },
                        ry +
                            if (i == 0) {
                                rh
                            } else if (i == 2) {
                                0f
                            } else {
                                rh / 2f
                            },
                    )
                drawRect(
                    Brush.linearGradient(listOf(c, Color.Transparent), edgeStart, edgeEnd),
                    Offset(rx, ry),
                    Size(rw, rh),
                )
            }
        }
    }

    private fun DrawScope.drawRoads(
        roads: List<List<Offset>>,
        x0: Int,
        y0: Int,
        px: Float,
    ) {
        val bounds = Size(CHUNK * px, CHUNK * px)
        clipRect(0f, 0f, bounds.width, bounds.height) {
            for (line in roads) {
                if (line.none { it.x in (x0 - 1f)..(x0 + CHUNK + 1f) && it.y in (y0 - 1f)..(y0 + CHUNK + 1f) }) continue
                val p = Path()

                // 点の列をなめらかな曲線にする（各点を制御点に、隣り合う点の中点どうしをつなぐ）
                fun tx(o: Offset) = (o.x - x0) * px

                fun ty(o: Offset) = (o.y - y0) * px
                p.moveTo(tx(line[0]), ty(line[0]))
                for (k in 1 until line.lastIndex) {
                    val mx = (tx(line[k]) + tx(line[k + 1])) / 2f
                    val my = (ty(line[k]) + ty(line[k + 1])) / 2f
                    p.quadraticTo(tx(line[k]), ty(line[k]), mx, my)
                }
                p.lineTo(tx(line.last()), ty(line.last()))
                drawPath(p, ROAD_EDGE, style = Stroke(px * 0.26f, cap = StrokeCap.Round))
                drawPath(p, ROAD, style = Stroke(px * 0.18f, cap = StrokeCap.Round))
            }
        }
    }

    /**
     * 森の木のスプライト（城内の飾りと同じ絵）の群れ。木は上へ伸びるので、下の列の木が隣のチャンクにはみ出す分も
     * このチャンクへ描く（継ぎ目で木が切れない）。奥の行から手前の行へ重ねる。
     */
    private fun DrawScope.drawForest(
        world: WorldMap,
        x0: Int,
        y0: Int,
        px: Float,
        seed: Long,
        sprites: SpriteCache,
    ) {
        val s = px / TREE_TILE_DIV
        for (ty in -1..CHUNK + 2) {
            for (tx in -1..CHUNK) {
                val x = x0 + tx
                val y = y0 + ty
                if (!world.inBounds(x, y) || world.structureAt(x, y) != null) continue
                val terrain = world.terrainAt(x, y)
                if (terrain == Terrain.MOUNTAIN) {
                    // 山のふもとの岩（峰の絵に添える）
                    if (hash(x, y, seed, 80) < ROCK_CHANCE) {
                        val rock = sprites.get(ROCKS[(hash(x, y, seed, 81) * ROCKS.size).toInt().coerceAtMost(1)])
                        if (rock != null) {
                            val rx = (tx + 0.15f + 0.7f * hash(x, y, seed, 82)) * px
                            val ry = (ty + 0.9f) * px
                            drawImage(
                                rock.image,
                                srcOffset = IntOffset.Zero,
                                srcSize = IntSize(rock.image.width, rock.image.height),
                                dstOffset = IntOffset((rx - rock.ax * s).roundToInt(), (ry - rock.ay * s).roundToInt()),
                                dstSize =
                                    IntSize(
                                        (rock.vw * s).roundToInt().coerceAtLeast(1),
                                        (rock.vh * s).roundToInt().coerceAtLeast(1),
                                    ),
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                    }
                    continue
                }
                if (terrain != Terrain.FOREST) continue
                val cold = snowT(x, y, world.size, seed) > 0.4f
                val n = 1 + (hash(x, y, seed, 8) * 1.99f).toInt()
                for (k in 0 until n) {
                    val pick = hash(x, y, seed, 70 + k)
                    val ids = if (cold) TREES_COLD else TREES
                    val sp = sprites.get(ids[(pick * ids.size).toInt().coerceAtMost(ids.size - 1)]) ?: continue
                    val fx = (tx + 0.2f + 0.6f * hash(x, y, seed, 10 + k)) * px
                    val fy = (ty + 0.45f + 0.5f * hash(x, y, seed, 20 + k)) * px
                    drawImage(
                        sp.image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(sp.image.width, sp.image.height),
                        dstOffset = IntOffset((fx - sp.ax * s).roundToInt(), (fy - sp.ay * s).roundToInt()),
                        dstSize =
                            IntSize(
                                (sp.vw * s).roundToInt().coerceAtLeast(1),
                                (sp.vh * s).roundToInt().coerceAtLeast(1),
                            ),
                        filterQuality = FilterQuality.Medium,
                    )
                }
            }
        }
    }

    /** 森に立てる木のスプライト（温帯 / 寒い北では松だけ）。 */
    val TREES =
        intArrayOf(
            SpriteIds.TREE_OAK_A,
            SpriteIds.TREE_OAK_B,
            SpriteIds.TREE_OAK_C,
            SpriteIds.TREE_PINE_A,
            SpriteIds.TREE_PINE_B,
            SpriteIds.TREE_BIRCH,
        )
    val TREES_COLD = intArrayOf(SpriteIds.TREE_PINE_A, SpriteIds.TREE_PINE_B)
    private val ROCKS = intArrayOf(SpriteIds.ROCK_A, SpriteIds.ROCK_B)
    private const val ROCK_CHANCE = 0.55f

    /** 焼く前に読み込んでおくスプライト（木と岩）。 */
    val BAKE_SPRITES: IntArray = TREES + ROCKS
    private const val TREE_TILE_DIV = 36f

    private fun DrawScope.decor(
        t: Terrain,
        x: Int,
        y: Int,
        ox: Float,
        oy: Float,
        px: Float,
        seed: Long,
        snow: Float,
        sand: Float,
        procTrees: Boolean,
    ) {
        val r = hash(x, y, seed, 7)
        when (t) {
            Terrain.FOREST -> {
                if (!procTrees) return
                val n = 2 + (hash(x, y, seed, 8) * 2.99f).toInt()
                for (k in 0 until n) {
                    val fx = ox + px * (0.15f + 0.7f * hash(x, y, seed, 10 + k))
                    val fy = oy + px * (0.3f + 0.6f * hash(x, y, seed, 20 + k))
                    val tree = px * (0.22f + 0.1f * hash(x, y, seed, 30 + k))
                    drawRect(Color(0xFF5A3A1E), Offset(fx - tree * 0.12f, fy), Size(tree * 0.24f, tree * 0.6f))
                    val g = if (k % 2 == 0) Color(0xFF1F5A2C) else Color(0xFF2E7A3A)
                    drawCircle(g, tree * 0.62f, Offset(fx, fy - tree * 0.1f))
                    drawCircle(shift(g, 0.08f), tree * 0.34f, Offset(fx - tree * 0.15f, fy - tree * 0.3f))
                    if (snow > 0.5f) {
                        // 雪をかぶった木
                        drawCircle(Color(0xE6FFFFFF), tree * 0.42f, Offset(fx, fy - tree * 0.38f))
                    }
                }
            }
            Terrain.MOUNTAIN -> {
                val cx = ox + px * (0.3f + 0.4f * hash(x, y, seed, 40))
                val base = oy + px * 0.82f
                val h = px * (0.5f + 0.25f * hash(x, y, seed, 41))
                val w = px * 0.34f
                val peak =
                    Path().apply {
                        moveTo(cx - w, base)
                        lineTo(cx, base - h)
                        lineTo(cx + w, base)
                        close()
                    }
                drawPath(peak, Color(0xFF6E695E))
                drawPath(
                    Path().apply {
                        moveTo(cx, base - h)
                        lineTo(cx + w, base)
                        lineTo(cx + w * 0.2f, base)
                        close()
                    },
                    Color(0x33000000),
                )
                if (noise(x * 0.25f, y * 0.25f, seed, 42) > 0.45f) {
                    drawPath(
                        Path().apply {
                            moveTo(cx - w * 0.38f, base - h * 0.62f)
                            lineTo(cx, base - h)
                            lineTo(cx + w * 0.38f, base - h * 0.62f)
                            lineTo(cx + w * 0.1f, base - h * 0.5f)
                            lineTo(cx - w * 0.1f, base - h * 0.58f)
                            close()
                        },
                        SNOW,
                    )
                }
            }
            Terrain.PLAIN -> {
                if (sand > 0.5f && r < 0.09f) {
                    if (r < 0.03f) {
                        // サボテン
                        val bx = ox + px * 0.5f
                        val by = oy + px * 0.82f
                        val cw = px * 0.09f
                        drawRect(Color(0xFF4E8A4A), Offset(bx - cw, by - px * 0.42f), Size(cw * 2f, px * 0.42f))
                        drawRect(Color(0xFF4E8A4A), Offset(bx - cw * 3.2f, by - px * 0.3f), Size(cw * 2.2f, cw * 1.4f))
                        drawRect(Color(0xFF4E8A4A), Offset(bx + cw * 1f, by - px * 0.24f), Size(cw * 2.2f, cw * 1.4f))
                    } else {
                        // 砂丘の筋
                        val a = Offset(ox + px * 0.15f, oy + px * (0.4f + 0.3f * r * 10f))
                        drawLine(Color(0x55B79A55), a, a + Offset(px * 0.6f, -px * 0.06f), strokeWidth = px * 0.05f)
                    }
                } else if (snow > 0.5f && r < 0.1f) {
                    // 雪だまり
                    drawOval(
                        Color(0xFFFFFFFF),
                        Offset(ox + px * 0.2f, oy + px * 0.5f),
                        Size(px * 0.5f, px * 0.2f),
                    )
                    drawOval(
                        Color(0x33708CA8),
                        Offset(ox + px * 0.25f, oy + px * 0.64f),
                        Size(px * 0.42f, px * 0.07f),
                    )
                } else if (r < 0.06f) {
                    // 草花
                    val c = if (r < 0.03f) Color(0xFFFFF3B0) else Color(0xFFFFFFFF)
                    for (k in 0 until 3) {
                        drawCircle(
                            c,
                            px * 0.035f,
                            Offset(
                                ox + px * (0.2f + 0.6f * hash(x, y, seed, 50 + k)),
                                oy + px * (0.2f + 0.6f * hash(x, y, seed, 60 + k)),
                            ),
                        )
                    }
                } else if (r < 0.09f) {
                    // 岩
                    drawRoundRect(
                        Color(0xFF8B8A84),
                        Offset(ox + px * 0.35f, oy + px * 0.5f),
                        Size(px * 0.22f, px * 0.14f),
                        CornerRadius(px * 0.06f),
                    )
                } else if (r > 0.94f) {
                    // 草むら
                    drawCircle(Color(0xFF4F8A45), px * 0.1f, Offset(ox + px * 0.5f, oy + px * 0.62f))
                }
            }
            Terrain.WATER -> {
                if (r < 0.1f) {
                    val a = Offset(ox + px * 0.25f, oy + px * 0.5f)
                    drawLine(Color(0x55FFFFFF), a, a + Offset(px * 0.22f, -px * 0.04f), strokeWidth = px * 0.03f)
                }
            }
        }
    }

    private const val ROAD_MIN_PX = 8f
    private const val EXTRA_ROADS = 2
    private const val ROAD_WOBBLE = 0.36f
    private const val RIVER_MAX_WATER = 11

    /** 2 点間の距離。 */
    fun dist(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float = hypot(ax - bx, ay - by)

    /** 0〜1 のなめらかな立ち上がり。 */
    fun smooth(v: Float): Float {
        val u = v.coerceIn(0f, 1f)
        return u * u * (3 - 2 * u)
    }

    /** ゆっくりした往復（雲・炎の揺れに）。 */
    fun wave(
        t: Float,
        speed: Float,
        phase: Float = 0f,
    ): Float = sin(t * speed + phase)
}
