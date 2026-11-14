package com.ttmrase.crownfall.ui.map

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.Terrain
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.ui.territory.GroundPlan
import com.ttmrase.crownfall.ui.territory.GroundTextures
import com.ttmrase.crownfall.ui.territory.SpriteCache
import com.ttmrase.crownfall.ui.territory.SpriteIds
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * ワールドマップの地面の絵（M11 T11.4）。8×8 タイルのチャンクごとにビットマップへ 1 度だけ焼き、毎フレームは貼るだけにする
 * （[MapChunkCache]）。
 * - 地面: 領地と同じテクスチャ（草・森の床・岩・雪・砂・水。tools/artgen/gen_ground.py）を、地形の割合の下絵で型抜きして
 *   重ね、色むらと起伏の陰影をかける（[MapGround]）。北は雪原、南東は砂漠。境目はタイルの形にせず、ノイズで揺らす。
 * - 川: 北から南へ蛇行する飾りの川（砂の岸・水のテクスチャ・深みの青）。細い水の連なりのタイルも川として細く塗る。
 * - 道: 自城から同盟の城・NPC 城・砦・聖地へ、水をよけて引く土の道（土のテクスチャと、暗い縁）。
 * - 飾り: 山の絵（tex_peaks。左手前から光が当たる立体の山塊）、森の木と岩のスプライト、草原の花と草むら。
 *   ズームが遠いと省く（LOD）。
 * 出力は [seed] だけで決まる（決定論）。
 */
internal object MapArt {
    const val CHUNK = 8

    /** 北の端の雪原の強さ 0..1（y が小さいほど強い。縁はノイズで揺らす）。 */
    fun snowT(
        x: Int,
        y: Int,
        size: Int,
        seed: Long,
    ): Float = snowAt(x.toFloat(), y.toFloat(), size, seed)

    /** [snowT] と同じ式を、タイルの途中の点でも求める（境目をタイルの形にしないため）。 */
    fun snowAt(
        x: Float,
        y: Float,
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
    ): Float = sandAt(x.toFloat(), y.toFloat(), size, seed)

    /** [sandT] と同じ式を、タイルの途中の点でも求める。 */
    fun sandAt(
        x: Float,
        y: Float,
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

    /**
     * 川（見た目だけの装飾）。北の端から南の端へ、ゆるく蛇行して流れる折れ線（タイル座標）。拠点は避ける。
     * :core の地形にはほとんど水が無いので、マップに「つながった川」を足す。道は川の上に重ねて描く（橋に見える）。
     */
    fun river(
        world: WorldMap,
        seed: Long,
    ): List<Offset> {
        val n = world.size
        val start = n * (RIVER_START_MIN + RIVER_START_SPAN * hash(0, 0, seed, 90))
        val phase = hash(1, 0, seed, 91) * RIVER_PHASE
        val xs = FloatArray(n + 3)
        for (i in -1..n + 1) {
            val y = i.toFloat()
            xs[i + 1] =
                start + n * RIVER_DRIFT * (y / n) + sin(y * RIVER_FREQ + phase) * RIVER_SWAY +
                (noise(y * 0.1f, 0f, seed, 92) - 0.5f) * RIVER_NOISE
        }
        // 拠点の近くは、急に折れず、なだらかに押しのける（距離が近いほど強く）。そのあと全体をならす
        repeat(RIVER_PASSES) {
            for (i in xs.indices) {
                val y = (i - 1) + 0.5f
                for (st in world.structures) {
                    val dx = xs[i] - (st.x + 0.5f)
                    val dy = y - (st.y + 0.5f)
                    val d = hypot(dx, dy)
                    if (d < RIVER_CLEAR) xs[i] += (if (dx >= 0f) 1f else -1f) * (RIVER_CLEAR - d) * RIVER_PUSH
                }
            }
            for (i in 1 until xs.lastIndex) xs[i] = (xs[i - 1] + 2f * xs[i] + xs[i + 1]) / 4f
        }
        val out = ArrayList<Offset>()
        for (i in xs.indices) out += Offset(xs[i].coerceIn(0.5f, n - 0.5f), (i - 1) + 0.5f)
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

    /**
     * チャンク ([cx], [cy]) を [px]（1 タイルの px）で描く。呼び出し側が (0,0)〜(CHUNK*px) のキャンバスを用意する。
     * [detail] が false なら飾りを省く（遠いズーム）。テクスチャのデコードを含むので、バックグラウンドで呼ぶ。
     */
    fun DrawScope.drawChunk(
        world: WorldMap,
        cx: Int,
        cy: Int,
        px: Float,
        seed: Long,
        roads: List<List<Offset>>,
        detail: Boolean,
        textures: GroundTextures,
        sprites: SpriteCache? = null,
        river: List<Offset> = emptyList(),
    ) {
        val x0 = cx * CHUNK
        val y0 = cy * CHUNK
        val tile = px.roundToInt()
        val canvas = drawContext.canvas.nativeCanvas
        // 地面（テクスチャ・境目・色むら・陰影）
        MapGround.bake(canvas, world, x0, y0, tile, seed, textures)
        if (px >= ROAD_MIN_PX) {
            canvas.save()
            // チャンクの外へは、焼いた絵の縁（[MapChunkCache.APRON]）のぶんだけはみ出してよい
            val apron = MapChunkCache.APRON.toFloat()
            canvas.clipRect(-apron, -apron, CHUNK * px + apron, CHUNK * px + apron)
            // 川（見た目だけの装飾）。道はその上に重ねる（橋に見える）
            drawRiver(canvas, river, x0, y0, tile, textures)
            drawRoads(canvas, roads, x0, y0, tile, textures)
            canvas.restore()
        }
        // 飾り
        if (detail) {
            drawMeadow(canvas, world, x0, y0, px, seed, textures.sheet(R.drawable.tex_decals))
            drawPeaks(canvas, world, x0, y0, px, seed, textures.sheet(R.drawable.tex_peaks))
            if (sprites != null) drawForest(world, x0, y0, px, seed, sprites)
        }
    }

    /**
     * 折れ線 [line] のうち、チャンクの近くを通る部分だけを、なめらかな曲線の Path にして [draw] に渡す
     * （各点を制御点に、隣り合う点の中点どうしをつなぐ。座標はチャンクの左上を原点にした px）。
     * 地図を縦断する川や長い道をまるごと Path にすると、縁のぼかしが線全体のマスクを作って遅い。曲線の 1 区間は
     * 前後 3 点だけで決まるので、近くの点に前後 [CURVE_LEAD] 点を足せば、チャンクの中の形は切り出す前と同じになる。
     */
    private inline fun nearCurves(
        line: List<Offset>,
        x0: Int,
        y0: Int,
        px: Int,
        draw: (android.graphics.Path) -> Unit,
    ) {
        var k = 0
        while (k < line.size) {
            if (!near(line[k], x0, y0)) {
                k++
                continue
            }
            var end = k
            while (end + 1 < line.size && near(line[end + 1], x0, y0)) end++
            val from = (k - CURVE_LEAD).coerceAtLeast(0)
            val to = (end + CURVE_LEAD).coerceAtMost(line.lastIndex)
            if (to > from) draw(curve(line, from, to, x0, y0, px))
            k = end + 1
        }
    }

    private fun near(
        o: Offset,
        x0: Int,
        y0: Int,
    ): Boolean =
        o.x > x0 - CURVE_NEAR && o.x < x0 + CHUNK + CURVE_NEAR && o.y > y0 - CURVE_NEAR && o.y < y0 + CHUNK + CURVE_NEAR

    /** [line] の [from]..[to] 番目の点の曲線。 */
    private fun curve(
        line: List<Offset>,
        from: Int,
        to: Int,
        x0: Int,
        y0: Int,
        px: Int,
    ): android.graphics.Path {
        val p = android.graphics.Path()
        // 途中から始めるときは、区間の継ぎ目（隣の点との中点）から
        val first = line[from]
        val start =
            if (from == 0) {
                first
            } else {
                Offset(
                    (first.x + line[from + 1].x) / 2f,
                    (first.y + line[from + 1].y) / 2f,
                )
            }
        p.moveTo((start.x - x0) * px, (start.y - y0) * px)
        for (k in from + 1 until to) {
            val mx = (line[k].x + line[k + 1].x) / 2f
            val my = (line[k].y + line[k + 1].y) / 2f
            p.quadTo((line[k].x - x0) * px, (line[k].y - y0) * px, (mx - x0) * px, (my - y0) * px)
        }
        if (to == line.lastIndex) p.lineTo((line[to].x - x0) * px, (line[to].y - y0) * px)
        return p
    }

    /** 線を太さ [width]（タイル）でなぞる Paint。縁は [soft]（タイル）ぶんぼかす。 */
    private fun stroke(
        px: Int,
        width: Float,
        soft: Float,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = px * width
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (px * soft >= MIN_BLUR_PX) maskFilter = BlurMaskFilter(px * soft, BlurMaskFilter.Blur.NORMAL)
        }

    /** 飾りの川: 砂の岸、水のテクスチャ、流れの芯の深い青。縁はぼかして、岸と水をなじませる。 */
    private fun drawRiver(
        canvas: Canvas,
        river: List<Offset>,
        x0: Int,
        y0: Int,
        px: Int,
        textures: GroundTextures,
    ) {
        val bank = stroke(px, RIVER_BANK, RIVER_BANK_SOFT)
        bank.shader = MapGround.shader(textures, R.drawable.tex_sand, MapGround.SAND_TILES, x0, y0, px)
        val water = stroke(px, RIVER_WATER, RIVER_WATER_SOFT)
        water.shader = MapGround.shader(textures, R.drawable.tex_water, MapGround.WATER_TILES, x0, y0, px)
        val deep = stroke(px, RIVER_DEEP, RIVER_DEEP_SOFT)
        deep.color = RIVER_DEEP_COLOR
        nearCurves(river, x0, y0, px) { path ->
            canvas.drawPath(path, bank)
            canvas.drawPath(path, water)
            canvas.drawPath(path, deep)
        }
    }

    /** 道: 暗い縁（踏み固めた土の影）の上に、土のテクスチャ。 */
    private fun drawRoads(
        canvas: Canvas,
        roads: List<List<Offset>>,
        x0: Int,
        y0: Int,
        px: Int,
        textures: GroundTextures,
    ) {
        val edge = stroke(px, ROAD_EDGE, ROAD_EDGE_SOFT)
        edge.color = ROAD_EDGE_COLOR
        val body = stroke(px, ROAD_WIDTH, ROAD_SOFT)
        body.shader = MapGround.shader(textures, R.drawable.tex_dirt, MapGround.DIRT_TILES, x0, y0, px)
        for (line in roads) {
            nearCurves(line, x0, y0, px) { path ->
                canvas.drawPath(path, edge)
                canvas.drawPath(path, body)
            }
        }
    }

    /**
     * 山の絵（tex_peaks: 3 種類 x 岩 / 雪）。山のタイルごとに 1 つ、少しずらして大きさを変えて置く。奥（北）の行から
     * 手前へ重ねるので、連なった山並みになる。山塊は隣のタイルやチャンクにはみ出すので、まわりのタイルの分も描く。
     */
    private fun drawPeaks(
        canvas: Canvas,
        world: WorldMap,
        x0: Int,
        y0: Int,
        px: Float,
        seed: Long,
        atlas: Bitmap,
    ) {
        val cell = atlas.width / PEAK_KINDS
        val src = Rect()
        val dst = RectF()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (ty in -1..CHUNK + 1) {
            for (tx in -2..CHUNK + 1) {
                val x = x0 + tx
                val y = y0 + ty
                if (!world.inBounds(x, y) || world.terrainAt(x, y) != Terrain.MOUNTAIN) continue
                if (world.structureAt(x, y) != null || hash(x, y, seed, 40) > PEAK_CHANCE) continue
                val kind = (hash(x, y, seed, 41) * PEAK_KINDS).toInt().coerceAtMost(PEAK_KINDS - 1)
                val snowy = snowT(x, y, world.size, seed) > PEAK_SNOW_FROM
                val size = px * (PEAK_SIZE + PEAK_SIZE_VARY * hash(x, y, seed, 42))
                val ax = (tx + 0.5f + (hash(x, y, seed, 43) - 0.5f) * PEAK_JITTER) * px
                val ay = (ty + PEAK_FOOT + (hash(x, y, seed, 44) - 0.5f) * PEAK_JITTER) * px
                src.set(kind * cell, if (snowy) cell else 0, (kind + 1) * cell, if (snowy) 2 * cell else cell)
                dst.set(ax - size / 2f, ay - size * PEAK_ANCHOR, ax + size / 2f, ay + size * (1f - PEAK_ANCHOR))
                // 砂漠の山は、砂岩の色に寄せる
                paint.colorFilter = if (sandT(x, y, world.size, seed) > PEAK_SNOW_FROM) SANDSTONE_PEAK else null
                canvas.drawBitmap(atlas, src, dst, paint)
            }
        }
    }

    /** 草原の花の群れ・草むら・小石（tex_decals。領地と同じ絵）。雪原には置かず、砂漠には小石だけ。 */
    private fun drawMeadow(
        canvas: Canvas,
        world: WorldMap,
        x0: Int,
        y0: Int,
        px: Float,
        seed: Long,
        atlas: Bitmap,
    ) {
        val src = Rect()
        val dst = RectF()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for (ty in 0 until CHUNK) {
            for (tx in 0 until CHUNK) {
                val x = x0 + tx
                val y = y0 + ty
                if (!world.inBounds(x, y) || world.terrainAt(x, y) != Terrain.PLAIN) continue
                if (world.structureAt(x, y) != null || snowT(x, y, world.size, seed) > MEADOW_BIOME_LIMIT) continue
                val r = hash(x, y, seed, 7)
                val desert = sandT(x, y, world.size, seed) > MEADOW_BIOME_LIMIT
                // 花の色は、数タイルの一帯ごとにそろえる
                val bloom = (hash(x / FLOWER_PATCH, y / FLOWER_PATCH, seed, 50) * FLOWER_KINDS).toInt()
                val kind =
                    when {
                        desert -> if (r < MEADOW_PEBBLES) GroundPlan.DECAL_PEBBLES else continue
                        r < MEADOW_FLOWERS -> bloom.coerceAtMost(FLOWER_KINDS - 1)
                        r < MEADOW_DARK -> GroundPlan.DECAL_TUFT_DARK
                        r < MEADOW_TUFTS -> GroundPlan.DECAL_TUFT_LIGHT
                        r < MEADOW_STONES -> GroundPlan.DECAL_PEBBLES
                        else -> continue
                    }
                val size = px * (MEADOW_SIZE + MEADOW_SIZE_VARY * hash(x, y, seed, 51))
                // タイルの中に収める（チャンクの継ぎ目で切れない）
                val room = px - size
                val left = tx * px + room * hash(x, y, seed, 52)
                val top = ty * px + room * hash(x, y, seed, 53)
                val col = kind % DECAL_COLUMNS
                val row = kind / DECAL_COLUMNS
                val cell = atlas.width / DECAL_COLUMNS
                src.set(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell)
                dst.set(left, top, left + size, top + size)
                canvas.drawBitmap(atlas, src, dst, paint)
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
    private const val TREE_TILE_DIV = 54f

    private const val ROAD_MIN_PX = 8f

    /** 飾りの川の太さ（タイル）: 砂の岸・水面・流れの芯と、それぞれの縁のぼかし。 */
    private const val RIVER_BANK = 1.5f
    private const val RIVER_BANK_SOFT = 0.1f
    private const val RIVER_WATER = 1.02f
    private const val RIVER_WATER_SOFT = 0.05f
    private const val RIVER_DEEP = 0.4f
    private const val RIVER_DEEP_SOFT = 0.16f
    private const val RIVER_DEEP_COLOR = 0x731B4E96

    /** 道の太さ（タイル）と、暗い縁。 */
    private const val ROAD_WIDTH = 0.2f
    private const val ROAD_SOFT = 0.02f
    private const val ROAD_EDGE = 0.3f
    private const val ROAD_EDGE_SOFT = 0.05f
    private const val ROAD_EDGE_COLOR = 0x70402C16
    private const val MIN_BLUR_PX = 0.5f

    /** 線のうち「チャンクの近く」とみなす幅（タイル。線の太さとぼかしが収まる）と、切り出すときに前後へ足す点の数。 */
    private const val CURVE_NEAR = 1.5f
    private const val CURVE_LEAD = 2

    /** 山の絵: 種類の数、置く割合、大きさ（タイル）、足元の位置、雪の山に替える雪の強さ。 */
    private const val PEAK_KINDS = 3
    private const val PEAK_CHANCE = 0.86f
    private const val PEAK_SIZE = 1.7f
    private const val PEAK_SIZE_VARY = 0.7f
    private const val PEAK_JITTER = 0.3f
    private const val PEAK_FOOT = 0.72f
    private const val PEAK_ANCHOR = 0.74f
    private const val PEAK_SNOW_FROM = 0.45f
    private val SANDSTONE_PEAK = LightingColorFilter(0xFFF0CFA2.toInt(), 0x00100800)

    /** 草原の小物: 花・草むら・小石の割合（乱数のしきい値）と大きさ（タイル）。 */
    private const val MEADOW_FLOWERS = 0.07f
    private const val MEADOW_DARK = 0.12f
    private const val MEADOW_TUFTS = 0.17f
    private const val MEADOW_STONES = 0.2f
    private const val MEADOW_PEBBLES = 0.05f
    private const val MEADOW_SIZE = 0.42f
    private const val MEADOW_SIZE_VARY = 0.2f
    private const val MEADOW_BIOME_LIMIT = 0.5f
    private const val FLOWER_PATCH = 3
    private const val FLOWER_KINDS = 4
    private const val DECAL_COLUMNS = 4
    private const val RIVER_START_MIN = 0.2f
    private const val RIVER_START_SPAN = 0.2f
    private const val RIVER_PHASE = 6.28f
    private const val RIVER_DRIFT = 0.35f
    private const val RIVER_FREQ = 0.16f
    private const val RIVER_SWAY = 3.2f
    private const val RIVER_NOISE = 4f
    private const val RIVER_CLEAR = 4.5f
    private const val RIVER_PASSES = 3
    private const val RIVER_PUSH = 0.7f
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
