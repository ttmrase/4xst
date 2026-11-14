package com.ttmrase.crownfall.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.map.Terrain
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.ui.territory.GroundPlan
import com.ttmrase.crownfall.ui.territory.GroundTextures
import com.ttmrase.crownfall.ui.territory.alphaBitmap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * ワールドマップの地面を、領地と同じテクスチャ（草・森の床・岩・雪・砂・水）で塗る。チャンクを焼くときに 1 度だけ走る
 * （毎フレームは焼いた絵を貼るだけ。[MapChunkCache]）。
 * - 地形はタイル単位だが、境目はタイルの形にしない: 標本点（1 タイルを [SAMPLES] 個に割った格子）ごとに、ノイズでゆがめた
 *   位置でまわりのタイルの地形を双一次に混ぜ、「森の割合・山の割合・水の割合…」を求める（[Patch.mix]）。それを濃さの下絵に
 *   して、テクスチャを型抜きで重ねる。雪原と砂漠は、縁をノイズで破って、まだらに入り混じらせる。
 * - その上に色むらと陰影の下絵を 1 枚重ねる: 大きな明暗、乾いた草の黄色み、山と丘の起伏の陰影（光は左手前から。
 *   拠点のスプライトと同じ向き）、深い水の濃い青、凍った水。
 * - 下絵は隣のチャンクと同じ標本点・同じ値になる（世界の座標だけで決まる）ので、継ぎ目は出ない。
 * 出力は世界と [seed] だけで決まる（決定論）。
 */
internal object MapGround {
    /** 標本点 1 つぶんの地面の混ざり具合（どれも 0..1）。 */
    class Mix {
        var forest = 0f
        var mountain = 0f
        var water = 0f

        /** 水ぎわの砂（水面より少し広い）。 */
        var shore = 0f

        /** 水の深さ（岸から遠いほど 1）。 */
        var depth = 0f
        var snow = 0f
        var sand = 0f
    }

    /**
     * チャンク（左上のタイルが [x0], [y0]）のまわりの地形を、標本点から引けるように写したもの。
     * 川かどうか・水の深さはタイルごとに 1 度だけ求める。
     */
    class Patch(
        private val world: WorldMap,
        private val seed: Long,
        private val x0: Int,
        private val y0: Int,
    ) {
        private val side = MapArt.CHUNK + 2 * PAD
        private val terrain = ByteArray(side * side)
        private val share = FloatArray(side * side)
        private val deep = FloatArray(side * side)

        init {
            for (j in 0 until side) {
                for (i in 0 until side) {
                    // 地図の外は、いちばん近い内側のタイルと同じ
                    val x = (x0 - PAD + i).coerceIn(0, world.size - 1)
                    val y = (y0 - PAD + j).coerceIn(0, world.size - 1)
                    val t = world.terrainAt(x, y)
                    terrain[j * side + i] = t.ordinal.toByte()
                    if (t == Terrain.WATER) {
                        // 川（細い水の連なり）は、水の割合を下げて細く見せる
                        share[j * side + i] = if (MapArt.isRiver(world, x, y)) RIVER_SHARE else 1f
                        deep[j * side + i] = depthOf(x, y)
                    }
                }
            }
            // 拠点（城・野営地など）のタイルとその隣には水を描かない。池の縁はにじませて描くので、
            // そのままだと隣の拠点が水の上に乗って見える
            for (st in world.structures) {
                val i0 = st.x - (x0 - PAD)
                val j0 = st.y - (y0 - PAD)
                if (i0 < -1 || j0 < -1 || i0 > side || j0 > side) continue
                for (dj in -1..1) {
                    for (di in -1..1) {
                        val i = i0 + di
                        val j = j0 + dj
                        if (i in 0 until side && j in 0 until side) share[j * side + i] = 0f
                    }
                }
            }
        }

        /** 水タイルの深さ: 2 タイル以内のいちばん近い陸までの距離から 0 / 0.5 / 1。 */
        private fun depthOf(
            x: Int,
            y: Int,
        ): Float {
            var near = DEPTH_REACH + 1
            for (r in 1..DEPTH_REACH) {
                for (dy in -r..r) for (dx in -r..r) {
                    if (world.inBounds(x + dx, y + dy) && world.terrainAt(x + dx, y + dy) != Terrain.WATER) {
                        near = min(near, max(abs(dx), abs(dy)))
                    }
                }
            }
            return (near - 1) / DEPTH_REACH.toFloat()
        }

        /** タイル座標の点 ([x], [y])（タイル (i, j) の中心は i + 0.5, j + 0.5）の地面の混ざり具合を [out] に入れる。 */
        fun mix(
            x: Float,
            y: Float,
            out: Mix,
        ) {
            // 境目をタイルの形にしないよう、見る位置をノイズでゆがめる
            val wx = x + (MapArt.noise(x * WARP_FREQ, y * WARP_FREQ, seed, SALT_WARP) - HALF) * WARP
            val wy = y + (MapArt.noise(x * WARP_FREQ, y * WARP_FREQ, seed, SALT_WARP + 1) - HALF) * WARP
            val fx = wx - HALF - (x0 - PAD)
            val fy = wy - HALF - (y0 - PAD)
            val ix = floor(fx).toInt().coerceIn(0, side - 2)
            val iy = floor(fy).toInt().coerceIn(0, side - 2)
            val tx = (fx - ix).coerceIn(0f, 1f)
            val ty = (fy - iy).coerceIn(0f, 1f)
            var forest = 0f
            var mountain = 0f
            var water = 0f
            var depth = 0f
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val w = (if (dx == 0) 1f - tx else tx) * (if (dy == 0) 1f - ty else ty)
                    val at = (iy + dy) * side + ix + dx
                    when (terrain[at].toInt()) {
                        FOREST -> forest += w
                        MOUNTAIN -> mountain += w
                        WATER -> {
                            water += w * share[at]
                            depth += w * deep[at]
                        }
                    }
                }
            }
            out.forest = edge(forest, LAND_EDGE)
            out.mountain = edge(mountain, LAND_EDGE)
            out.water = edge(water, WATER_EDGE)
            out.shore = MapArt.smooth((water - SHORE_FROM) / SHORE_SPAN)
            out.depth = depth
            // 雪原と砂漠は、縁をノイズで破る（一様にぼかさず、まだらに入り混じる）
            val ragged = (MapArt.noise(x * RAGGED_FREQ, y * RAGGED_FREQ, seed, SALT_RAGGED) - HALF) * RAGGED
            val snow = MapArt.snowAt(x - HALF, y - HALF, world.size, seed)
            val sand = MapArt.sandAt(x - HALF, y - HALF, world.size, seed)
            out.snow = MapArt.smooth((snow + ragged - BIOME_FROM) / BIOME_SPAN)
            out.sand = MapArt.smooth((sand + ragged - BIOME_FROM) / BIOME_SPAN)
        }

        /** 起伏の高さ（陰影用。山は稜線のあるノイズ、平地はゆるい丘、水は低い）。 */
        fun height(
            x: Float,
            y: Float,
            mix: Mix,
        ): Float {
            val ridge = 1f - abs(MapArt.noise(x * RIDGE_FREQ, y * RIDGE_FREQ, seed, SALT_RELIEF) * 2f - 1f)
            val hills = MapArt.noise(x * HILL_FREQ, y * HILL_FREQ, seed, SALT_RELIEF + 1)
            return mix.mountain * (PEAK_BASE + PEAK_RIDGE * ridge) + HILLS * hills + CANOPY * mix.forest -
                BASIN * mix.water
        }
    }

    /**
     * チャンク（左上のタイルが [x0], [y0]、1 タイル [px] px）の地面を [canvas] に塗る。
     * [canvas] の原点はチャンクの左上。バックグラウンドのスレッドから呼ぶ（テクスチャのデコードを含む）。
     */
    fun bake(
        canvas: Canvas,
        world: WorldMap,
        x0: Int,
        y0: Int,
        px: Int,
        seed: Long,
        textures: GroundTextures,
    ) {
        val per = if (px >= FINE_MIN_PX) SAMPLES else SAMPLES_FAR
        val n = (MapArt.CHUNK + 2) * per
        val count = n * n
        val forest = ByteArray(count)
        val rock = ByteArray(count)
        val sand = ByteArray(count)
        val snow = ByteArray(count)
        val shore = ByteArray(count)
        val water = ByteArray(count)
        val heights = FloatArray(count)
        val tint = IntArray(count)
        val used = BooleanArray(LAYERS)
        val patch = Patch(world, seed, x0, y0)
        val mix = Mix()
        val acc = FloatArray(ACC_SIZE)
        for (j in 0 until n) {
            val y = y0 - 1 + (j + HALF) / per
            for (i in 0 until n) {
                val x = x0 - 1 + (i + HALF) / per
                val at = j * n + i
                patch.mix(x, y, mix)
                heights[at] = patch.height(x, y, mix)
                val land = 1f - mix.water
                val plain = (1f - mix.forest - mix.mountain).coerceAtLeast(0f)
                forest[at] = put(mix.forest, used, LAYER_FOREST)
                rock[at] = put(mix.mountain, used, LAYER_ROCK)
                // 砂漠と雪は、草原にはほぼ全面、森と山には薄くかぶる
                val cover = plain * ON_PLAIN + mix.forest * ON_FOREST + mix.mountain * ON_MOUNTAIN
                sand[at] = put(mix.sand * cover * land, used, LAYER_SAND)
                snow[at] = put(mix.snow * cover, used, LAYER_SNOW)
                shore[at] = put(mix.shore * (1f - mix.snow * SHORE_UNDER_SNOW), used, LAYER_SHORE)
                water[at] = put(mix.water, used, LAYER_WATER)
                tint[at] = tone(x, y, seed, mix, plain, acc)
            }
        }
        relief(heights, tint, n, per, acc)

        // 下絵（まわりへ 1 タイルはみ出している）の画素 → チャンクの px と、その逆
        val scale = px / per.toFloat()
        val toChunk =
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(-px.toFloat(), -px.toFloat())
            }
        val toMask =
            Matrix().apply {
                setTranslate(px.toFloat(), px.toFloat())
                postScale(1f / scale, 1f / scale)
            }
        val brush = Brush(canvas, textures, x0, y0, px, n, toChunk, toMask)
        canvas.drawPaint(brush.paint(R.drawable.tex_grass, GRASS_TILES, null))
        if (used[LAYER_FOREST]) brush.fill(forest, R.drawable.tex_forest, FOREST_TILES)
        if (used[LAYER_ROCK]) brush.fill(rock, R.drawable.tex_rock, ROCK_TILES)
        if (used[LAYER_SAND]) brush.fill(sand, R.drawable.tex_sand, SAND_TILES)
        if (used[LAYER_SNOW]) brush.fill(snow, R.drawable.tex_snow, SNOW_TILES)
        if (used[LAYER_SHORE]) brush.fill(shore, R.drawable.tex_sand, SAND_TILES)
        if (used[LAYER_WATER]) brush.fill(water, R.drawable.tex_water, WATER_TILES)
        canvas.drawBitmap(Bitmap.createBitmap(tint, n, n, Bitmap.Config.ARGB_8888), toChunk, brush.plain)
    }

    /**
     * [textures] の絵の具（タイル [tiles] 枚で 1 回くり返す。世界の座標に貼りつくので、チャンクをまたいでつながる）。
     * [toLocal] は「チャンクの px → 描く先の座標」で、null ならチャンクの px のまま（線や全面の塗り）。
     */
    fun shader(
        textures: GroundTextures,
        id: Int,
        tiles: Float,
        x0: Int,
        y0: Int,
        px: Int,
        toLocal: Matrix? = null,
    ): Shader {
        val texture = textures.get(id)
        val level = GroundPlan.textureLevelFor(texture.levels[0].width / tiles, px.toFloat())
        val bitmap = texture.levels[level]
        return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(tiles * px / bitmap.width, tiles * px / bitmap.height)
                    postTranslate(-x0.toFloat() * px, -y0.toFloat() * px)
                    if (toLocal != null) postConcat(toLocal)
                },
            )
        }
    }

    /** 1 チャンクぶんの塗り道具（焼くたびに作る。焼くスレッドごとに別なので、共有しない）。 */
    private class Brush(
        private val canvas: Canvas,
        private val textures: GroundTextures,
        private val x0: Int,
        private val y0: Int,
        private val px: Int,
        private val n: Int,
        private val toChunk: Matrix,
        private val toMask: Matrix,
    ) {
        val plain = Paint(Paint.FILTER_BITMAP_FLAG)
        private val textured = Paint(Paint.FILTER_BITMAP_FLAG)

        fun paint(
            id: Int,
            tiles: Float,
            toLocal: Matrix?,
        ): Paint {
            textured.shader = shader(textures, id, tiles, x0, y0, px, toLocal)
            return textured
        }

        /**
         * 濃さの下絵 [mask] の形に、テクスチャを塗る。ALPHA_8 のビットマップを行列つきで貼ると、Paint の shader も
         * 下絵の画素の座標で貼られるので、Shader には「チャンクの px → 下絵の画素」を掛けておく。
         */
        fun fill(
            mask: ByteArray,
            id: Int,
            tiles: Float,
        ) {
            canvas.drawBitmap(alphaBitmap(n, n, mask), toChunk, paint(id, tiles, toMask))
        }
    }

    private fun put(
        v: Float,
        used: BooleanArray,
        layer: Int,
    ): Byte {
        val b = (v.coerceIn(0f, 1f) * BYTE_MAX + HALF).toInt()
        if (b > 0) used[layer] = true
        return b.toByte()
    }

    /** 色むら: 大きな明暗、乾いた草、森の暗さ、深い水、凍った水、砂漠の山の赤み（ARGB。乗算済みではない）。 */
    private fun tone(
        x: Float,
        y: Float,
        seed: Long,
        mix: Mix,
        plain: Float,
        acc: FloatArray,
    ): Int {
        acc.fill(0f)
        val land = 1f - mix.water
        val big = (MapArt.noise(x * TONE_FREQ, y * TONE_FREQ, seed, SALT_TONE) - HALF) * 2f
        if (big > 0f) over(acc, SUN, big * TONE_SUN * land) else over(acc, DEEP_GREEN, -big * TONE_DEEP * land)
        val dry = MapArt.noise(x * DRY_FREQ, y * DRY_FREQ, seed, SALT_TONE + 1)
        over(acc, DRY, ((dry - DRY_FROM) * 2f).coerceIn(0f, DRY_MAX) * plain * land * (1f - mix.snow) * (1f - mix.sand))
        over(acc, CANOPY_SHADE, mix.forest * CANOPY_ALPHA * (1f - mix.snow * HALF))
        over(acc, SANDSTONE, mix.mountain * mix.sand * SANDSTONE_ALPHA)
        over(acc, DEEP_WATER, mix.water * MapArt.smooth(mix.depth) * DEEP_ALPHA)
        over(acc, ICE, mix.water * mix.snow * ICE_ALPHA)
        return pack(acc)
    }

    /**
     * 起伏の陰影を [tint] に重ねる。光は左手前（地図では西南西）から: 光へ向く斜面を暖かく明るく、背く斜面を冷たく暗く。
     * [per] は 1 タイルあたりの標本数（傾きをタイル単位にそろえる）。
     */
    private fun relief(
        heights: FloatArray,
        tint: IntArray,
        n: Int,
        per: Int,
        acc: FloatArray,
    ) {
        for (j in 0 until n) {
            for (i in 0 until n) {
                val l = heights[j * n + max(i - 1, 0)]
                val r = heights[j * n + min(i + 1, n - 1)]
                val u = heights[max(j - 1, 0) * n + i]
                val d = heights[min(j + 1, n - 1) * n + i]
                val lit = ((l - r) * LIGHT_X + (u - d) * LIGHT_Y) * per * RELIEF
                if (abs(lit) < MIN_RELIEF) continue
                unpack(tint[j * n + i], acc)
                if (lit > 0f) {
                    over(
                        acc,
                        LIT,
                        min(lit, 1f) * LIT_ALPHA,
                    )
                } else {
                    over(acc, SHADOW, min(-lit, 1f) * SHADOW_ALPHA)
                }
                tint[j * n + i] = pack(acc)
            }
        }
    }

    /** 0..1 の割合 [v] を、0.5 を境に ±[soft] でくっきりさせる。 */
    private fun edge(
        v: Float,
        soft: Float,
    ): Float = MapArt.smooth((v - HALF + soft) / (2f * soft))

    /** [acc]（乗算済みの r, g, b, a）に、色 [color] を濃さ [alpha] で重ねる。 */
    private fun over(
        acc: FloatArray,
        color: FloatArray,
        alpha: Float,
    ) {
        val a = alpha.coerceIn(0f, 1f)
        if (a <= 0f) return
        for (k in 0 until ACC_A) acc[k] = color[k] * a + acc[k] * (1f - a)
        acc[ACC_A] = a + acc[ACC_A] * (1f - a)
    }

    private fun pack(acc: FloatArray): Int {
        val a = acc[ACC_A]
        if (a <= MIN_ALPHA) return 0
        return (channel(a) shl ALPHA_SHIFT) or (channel(acc[0] / a) shl RED_SHIFT) or
            (channel(acc[1] / a) shl GREEN_SHIFT) or channel(acc[2] / a)
    }

    private fun unpack(
        color: Int,
        acc: FloatArray,
    ) {
        val a = (color ushr ALPHA_SHIFT) / BYTE_MAX
        acc[0] = ((color shr RED_SHIFT) and BYTE_MASK) / BYTE_MAX * a
        acc[1] = ((color shr GREEN_SHIFT) and BYTE_MASK) / BYTE_MAX * a
        acc[2] = (color and BYTE_MASK) / BYTE_MAX * a
        acc[ACC_A] = a
    }

    private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * BYTE_MAX + HALF).toInt()

    /** テクスチャ 1 枚が覆うタイル数（道と川も同じテクスチャを使う。[MapArt]）。 */
    const val GRASS_TILES = 4f
    const val FOREST_TILES = 3f
    const val ROCK_TILES = 2.6f
    const val SAND_TILES = 2.4f
    const val SNOW_TILES = 3.6f
    const val WATER_TILES = 2.2f
    const val DIRT_TILES = 1.3f

    /** 1 タイルあたりの標本数（遠いズームの段階は粗くてよい）。 */
    private const val SAMPLES = 8
    private const val SAMPLES_FAR = 3
    private const val FINE_MIN_PX = 32

    /** [Patch] がチャンクのまわりに取る余白（タイル）。ゆがめた位置と、下絵の 1 タイルの余白が収まる。 */
    private const val PAD = 3
    private val FOREST = Terrain.FOREST.ordinal
    private val MOUNTAIN = Terrain.MOUNTAIN.ordinal
    private val WATER = Terrain.WATER.ordinal
    private const val HALF = 0.5f
    private const val BYTE_MAX = 255f
    private const val BYTE_MASK = 0xFF
    private const val ALPHA_SHIFT = 24
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val ACC_SIZE = 4
    private const val ACC_A = 3
    private const val MIN_ALPHA = 0.004f

    private const val LAYERS = 6
    private const val LAYER_FOREST = 0
    private const val LAYER_ROCK = 1
    private const val LAYER_SAND = 2
    private const val LAYER_SNOW = 3
    private const val LAYER_SHORE = 4
    private const val LAYER_WATER = 5

    private const val WARP = 0.8f
    private const val WARP_FREQ = 1.3f
    private const val LAND_EDGE = 0.2f
    private const val WATER_EDGE = 0.07f
    private const val RIVER_SHARE = 0.74f
    private const val SHORE_FROM = 0.24f
    private const val SHORE_SPAN = 0.16f
    private const val SHORE_UNDER_SNOW = 0.8f
    private const val DEPTH_REACH = 2
    private const val RAGGED = 0.5f
    private const val RAGGED_FREQ = 1.9f
    private const val BIOME_FROM = 0.3f
    private const val BIOME_SPAN = 0.4f
    private const val ON_PLAIN = 0.96f
    private const val ON_FOREST = 0.7f
    private const val ON_MOUNTAIN = 0.5f

    private const val RIDGE_FREQ = 0.9f
    private const val HILL_FREQ = 0.33f
    private const val PEAK_BASE = 0.45f
    private const val PEAK_RIDGE = 0.75f
    private const val HILLS = 0.22f
    private const val CANOPY = 0.05f
    private const val BASIN = 0.1f
    private const val RELIEF = 1.5f
    private const val MIN_RELIEF = 0.02f
    private const val LIGHT_X = 0.9f
    private const val LIGHT_Y = -0.44f
    private const val LIT_ALPHA = 0.3f
    private const val SHADOW_ALPHA = 0.38f

    private const val TONE_FREQ = 0.16f
    private const val TONE_SUN = 0.22f
    private const val TONE_DEEP = 0.26f
    private const val DRY_FREQ = 0.09f
    private const val DRY_FROM = 0.55f
    private const val DRY_MAX = 0.5f
    private const val CANOPY_ALPHA = 0.16f
    private const val SANDSTONE_ALPHA = 0.45f
    private const val DEEP_ALPHA = 0.5f
    private const val ICE_ALPHA = 0.6f
    private const val SALT_WARP = 101
    private const val SALT_RAGGED = 111
    private const val SALT_RELIEF = 121
    private const val SALT_TONE = 131

    private val SUN = floatArrayOf(0.97f, 0.93f, 0.46f)
    private val DEEP_GREEN = floatArrayOf(0.06f, 0.26f, 0.2f)
    private val DRY = floatArrayOf(0.78f, 0.7f, 0.36f)
    private val CANOPY_SHADE = floatArrayOf(0.04f, 0.16f, 0.1f)
    private val SANDSTONE = floatArrayOf(0.74f, 0.58f, 0.4f)
    private val DEEP_WATER = floatArrayOf(0.08f, 0.22f, 0.5f)
    private val ICE = floatArrayOf(0.8f, 0.9f, 0.96f)
    private val LIT = floatArrayOf(1f, 0.96f, 0.8f)
    private val SHADOW = floatArrayOf(0.06f, 0.08f, 0.22f)
}
