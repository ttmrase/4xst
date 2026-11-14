package com.ttmrase.crownfall.ui.territory

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrixColorFilter
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.ttmrase.crownfall.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * 継ぎ目なく並ぶ地面のテクスチャ 1 枚と、その縮小版（[levels] の 0 が等倍、1 が 1/2 …）。
 * 縮めて描くときは粗い段階を使う（ミップマップの代わり。縮小でちらつかない）。
 */
internal class GroundTexture(val levels: Array<Bitmap>)

/**
 * 地面のテクスチャ（res/drawable-nodpi/tex_*.webp。tools/artgen/gen_ground.py が作る）を読み込んで貯める。
 * アプリ全体で 1 つ。領地ビューとワールドマップで共有する。[get] はデコードするので、描画スレッドでは呼ばない。
 */
internal class GroundTextures private constructor(context: Context) {
    private val res = context.applicationContext.resources
    private val tiles = HashMap<Int, GroundTexture>()
    private val sheets = HashMap<Int, Bitmap>()

    /** 並べて使うテクスチャ（段階つき）。 */
    fun get(id: Int): GroundTexture =
        synchronized(tiles) {
            tiles.getOrPut(id) {
                val full = decode(id)
                var last = full
                GroundTexture(
                    Array(GroundPlan.TEXTURE_LEVELS) { k ->
                        if (k > 0) last = Bitmap.createScaledBitmap(last, last.width / 2, last.height / 2, true)
                        last
                    },
                )
            }
        }

    /** 切り出して使う絵（小物・山の絵のアトラス）。 */
    fun sheet(id: Int): Bitmap = synchronized(sheets) { sheets.getOrPut(id) { decode(id) } }

    private fun decode(id: Int): Bitmap {
        val options =
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        return checkNotNull(BitmapFactory.decodeResource(res, id, options)) { "ground texture $id" }
    }

    companion object {
        @Volatile private var instance: GroundTextures? = null

        fun shared(context: Context): GroundTextures =
            instance ?: synchronized(this) { instance ?: GroundTextures(context).also { instance = it } }
    }
}

/**
 * 領地の地面を、テクスチャで描く（3D レンダリングの建物が立つ地面）。
 * - 絵の具: 草・森の床・土・石畳（丸石 / 敷石 / 大理石）・水・雪のテクスチャを、グリッド空間に並べる [BitmapShader]。
 *   呼び出し側がアイソメの行列をかけた Canvas に描くので、テクスチャは地面と同じ向きにつぶれ、カメラと一緒に動く。
 * - 型: 「どこが草地で、どこが道か」は [GroundPlan] が帯ごとに 1 度だけ焼く濃さの下絵（ALPHA_8）。下絵を、絵の具を
 *   持たせた Paint で貼ると、下絵の形にテクスチャが抜ける（ALPHA_8 のビットマップは Paint の shader で塗られる）。
 * - 毎フレームの仕事は、テクスチャつきの矩形を数枚貼るだけ: 森の床（草地の外だけ）→ 草地 → 土 → 石畳 → 色むら、
 *   それに川（水のテクスチャを 2 枚ずらして流す）と小物。Shader・Paint・Matrix・下絵はすべて作り置きで、描画では確保しない。
 * - 下絵とテクスチャの用意はバックグラウンド。済むまでは [draw] が false を返し、呼び出し側が平らな色で描く。
 *   済んだら [version] が進んで再描画される。次の帯の下絵も先に焼いておく（拡張の演出で待たない）。
 */
internal class GroundArt private constructor(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val textures = GroundTextures.shared(context)

    /** 用意が済むたびに増える（描画が読んで再描画の目印にする）。 */
    var version by mutableIntStateOf(0)
        private set

    /** テクスチャ 1 種類ぶんの絵の具。[span] グリッド単位で 1 回くり返す。段階ごとの Shader を持ち、倍率で切り替える。 */
    private class Material(
        texture: GroundTexture,
        span: Float,
        turn: Float,
    ) {
        private val ppu = texture.levels[0].width / span
        private val shaders =
            Array(texture.levels.size) { k ->
                val bitmap = texture.levels[k]
                BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
                    setLocalMatrix(
                        Matrix().apply {
                            setScale(span / bitmap.width, span / bitmap.height)
                            postRotate(turn)
                        },
                    )
                }
            }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** 倍率 [sc] に合う段階の Shader を付けた Paint（使い回し）。 */
        fun use(
            sc: Float,
            alpha: Int = OPAQUE,
            filter: ColorFilter? = null,
        ): Paint {
            paint.shader = shaders[GroundPlan.textureLevel(ppu, sc)]
            paint.alpha = alpha
            paint.colorFilter = filter
            return paint
        }
    }

    /** 濃さの下絵のビットマップと、それをグリッド空間に置く行列。 */
    private class Mask(sheet: AlphaSheet) {
        val bitmap: Bitmap = alphaBitmap(sheet.frame.w, sheet.frame.h, sheet.data)
        val matrix: Matrix = frameMatrix(sheet.frame)
    }

    /** 帯 1 つぶんの下絵。 */
    private class BandArt(
        val band: Int,
        sheets: GroundSheets,
        val stoneMaterial: Material?,
        val coreHalf: Float,
    ) {
        val lawn = Mask(sheets.lawn)
        val earth = Mask(sheets.earth)
        val stone = sheets.stone?.let { Mask(it) }
        val shade: Bitmap =
            Bitmap.createBitmap(sheets.shade.data, sheets.shade.frame.w, sheets.shade.frame.h, Bitmap.Config.ARGB_8888)
        val shadeMatrix: Matrix = frameMatrix(sheets.shade.frame)
    }

    /** 帯に依らないもの: 絵の具、森の床・川の下絵、小物。 */
    private class BaseArt(
        textures: GroundTextures,
        plan: GroundPlan,
    ) {
        val grass = Material(textures.get(R.drawable.tex_grass), GRASS_SPAN, GRASS_TURN)
        val forestFloor = Material(textures.get(R.drawable.tex_forest), FOREST_SPAN, FOREST_TURN)
        val dirt = Material(textures.get(R.drawable.tex_dirt), DIRT_SPAN, DIRT_TURN)
        val water = Material(textures.get(R.drawable.tex_water), WATER_SPAN, 0f)
        val snow = Material(textures.get(R.drawable.tex_snow), SNOW_SPAN, SNOW_TURN)
        val forest = Mask(plan.bakeForest())
        val river: Mask
        val bank: Mask
        val blob: Bitmap = alphaBitmap(BLOB_PX, BLOB_PX, softSquare(BLOB_PX))
        val atlas: Bitmap = textures.sheet(R.drawable.tex_decals)
        val decals: GroundDecals = plan.decals()

        init {
            val (surface, shore) = plan.bakeRiver()
            river = Mask(surface)
            bank = Mask(shore)
        }
    }

    private var plan: GroundPlan? = null

    /** 設計図を替えるたびに進む（古い設計図で焼いた結果を捨てる目印）。 */
    @Volatile private var generation = 0

    @Volatile private var base: BaseArt? = null

    @Volatile private var failed = false
    private val bands = AtomicReferenceArray<BandArt?>(PlotLayout.BANDS + 2)
    private val pending = ConcurrentHashMap.newKeySet<Int>()
    private val stones = arrayOfNulls<Material>(STONE_KINDS)

    private val shadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val decalPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scratch = Matrix()
    private val src = Rect()
    private val dst = RectF()

    /** いまの設計図（区画の並びが変わったら、下絵を捨てて焼き直す）。描画スレッドから呼ぶ。 */
    private fun adopt(next: GroundPlan) {
        if (next === plan) return
        plan = next
        generation++
        base = null
        for (b in 0 until bands.length()) bands.set(b, null)
    }

    /**
     * 地面を描く。[canvas] にはグリッド座標 → 画面座標の行列がかかっていること（[ox] [oy] [sc] はその行列と同じ値で、
     * 小物を画面の外で省くのに使う）。帯の拡張の途中（[settled] が false）は、前の帯の上に次の帯を [ease] の濃さで重ねる。
     * まだ用意ができていなければ何も描かずに false を返す。
     */
    fun draw(
        canvas: Canvas,
        plan: GroundPlan,
        sc: Float,
        ox: Float,
        oy: Float,
        viewW: Float,
        viewH: Float,
        fromBand: Int,
        toBand: Int,
        ease: Float,
        settled: Boolean,
        season: String?,
        seconds: Float,
    ): Boolean {
        adopt(plan)
        if (failed) return false
        val base = this.base
        if (base == null) {
            requestBase(plan)
            return false
        }
        val to = band(plan, toBand)
        val moving = !settled && fromBand != toBand
        val from = if (moving) bands.get(fromBand) else null
        val main = from ?: to ?: bands.get(fromBand) ?: return false
        val winter = season == SEASON_WINTER
        // 森の床。草地が必ず覆う範囲は描かない（重ね塗りを減らす）
        val core = main.coreHalf
        canvas.save()
        canvas.clipOutRect(MID - core, MID - core, MID + core, MID + core)
        canvas.drawBitmap(base.forest.bitmap, base.forest.matrix, forestPaint(base, sc, winter))
        canvas.restore()
        drawBand(canvas, base, main, sc, OPAQUE, season)
        if (from != null && to != null) drawBand(canvas, base, to, sc, (ease * OPAQUE).toInt().coerceIn(0, OPAQUE), season)
        if (settled) retain(toBand)
        drawRiver(canvas, base, sc, seconds)
        drawDecals(canvas, base, sc, ox, oy, viewW, viewH, fromBand, toBand, ease, moving, season)
        return true
    }

    private fun forestPaint(
        base: BaseArt,
        sc: Float,
        winter: Boolean,
    ): Paint = if (winter) base.snow.use(sc, filter = SNOW_SHADE) else base.forestFloor.use(sc)

    /** 帯 1 つぶん: 草地 → 土 → 石畳 → 色むら。 */
    private fun drawBand(
        canvas: Canvas,
        base: BaseArt,
        art: BandArt,
        sc: Float,
        alpha: Int,
        season: String?,
    ) {
        val lawn =
            when (season) {
                SEASON_WINTER -> base.snow.use(sc, alpha)
                SEASON_SPRING -> base.grass.use(sc, alpha, SPRING_GRASS)
                SEASON_AUTUMN -> base.grass.use(sc, alpha, AUTUMN_GRASS)
                else -> base.grass.use(sc, alpha)
            }
        canvas.drawBitmap(art.lawn.bitmap, art.lawn.matrix, lawn)
        canvas.drawBitmap(art.earth.bitmap, art.earth.matrix, base.dirt.use(sc, alpha))
        val stone = art.stone
        val material = art.stoneMaterial
        if (stone != null && material != null) canvas.drawBitmap(stone.bitmap, stone.matrix, material.use(sc, alpha))
        shadePaint.alpha = alpha
        shadePaint.colorFilter = if (season == SEASON_WINTER) SNOW_SHADOWS else null
        canvas.drawBitmap(art.shade, art.shadeMatrix, shadePaint)
    }

    /**
     * 川: 岸の土、その上に水面。水のテクスチャは 2 枚を違う速さで下流（グリッドの +y）へ流して重ねる（波頭がずれて揺らめく）。
     * Shader の行列は変えず、Canvas ごとずらして、型（下絵）だけ逆へ戻す。
     */
    private fun drawRiver(
        canvas: Canvas,
        base: BaseArt,
        sc: Float,
        seconds: Float,
    ) {
        canvas.drawBitmap(base.bank.bitmap, base.bank.matrix, base.dirt.use(sc, filter = BANK_SAND))
        flow(canvas, base.river, 0f, (seconds * WATER_FLOW) % WATER_SPAN, base.water.use(sc))
        val drift = (seconds * WATER_FLOW_TOP) % WATER_SPAN + WATER_TOP_SHIFT
        flow(canvas, base.river, WATER_TOP_SHIFT, drift, base.water.use(sc, WATER_TOP_ALPHA))
    }

    private fun flow(
        canvas: Canvas,
        mask: Mask,
        dx: Float,
        dy: Float,
        paint: Paint,
    ) {
        canvas.save()
        canvas.translate(dx, dy)
        scratch.set(mask.matrix)
        scratch.postTranslate(-dx, -dy)
        canvas.drawBitmap(mask.bitmap, scratch, paint)
        canvas.restore()
    }

    /** 草地の小物。画面の外は省く。拡張の途中で現れる帯の小物は、[ease] の濃さで出す。 */
    private fun drawDecals(
        canvas: Canvas,
        base: BaseArt,
        sc: Float,
        ox: Float,
        oy: Float,
        viewW: Float,
        viewH: Float,
        fromBand: Int,
        toBand: Int,
        ease: Float,
        moving: Boolean,
        season: String?,
    ) {
        val d = base.decals
        val winter = season == SEASON_WINTER
        val autumn = season == SEASON_AUTUMN
        val fresh = (ease * OPAQUE).toInt().coerceIn(0, OPAQUE)
        for (i in 0 until d.count) {
            val intro = d.band[i].toInt()
            if (intro > toBand) continue
            var kind = d.kind[i].toInt()
            // 雪の上には小石だけ。秋は花の代わりに落ち葉
            if (winter && kind != GroundPlan.DECAL_PEBBLES) continue
            if (autumn && kind <= GroundPlan.DECAL_BLUEBELL) kind = GroundPlan.DECAL_LEAVES
            val gx = d.x[i]
            val gy = d.y[i]
            val half = d.size[i] / 2f
            val reach = d.size[i] * PlotLayout.U * sc
            val px = ox + PlotLayout.wx(gx, gy) * sc
            val py = oy + PlotLayout.wy(gx, gy) * sc
            if (px < -reach || px > viewW + reach || py < -reach || py > viewH + reach) continue
            decalPaint.alpha = if (moving && intro > fromBand) fresh else OPAQUE
            val col = kind % ATLAS_COLUMNS
            val row = kind / ATLAS_COLUMNS
            src.set(col * ATLAS_CELL, row * ATLAS_CELL, (col + 1) * ATLAS_CELL, (row + 1) * ATLAS_CELL)
            dst.set(gx - half, gy - half, gx + half, gy + half)
            canvas.drawBitmap(base.atlas, src, dst, decalPaint)
        }
    }

    /**
     * 建設できない区画（中心 [gx], [gy]）を、縁のやわらかい森の床で覆う。[draw] が true を返したフレームで、同じ行列の
     * まま呼ぶ。
     */
    fun drawLockPatch(
        canvas: Canvas,
        gx: Float,
        gy: Float,
        sc: Float,
        season: String?,
    ) {
        val base = this.base ?: return
        scratch.setScale(2f * LOCK_HALF / BLOB_PX, 2f * LOCK_HALF / BLOB_PX)
        scratch.postTranslate(gx - LOCK_HALF, gy - LOCK_HALF)
        canvas.drawBitmap(base.blob, scratch, forestPaint(base, sc, season == SEASON_WINTER))
    }

    /** 帯 [band] の下絵。まだ無ければ null を返して、焼き始める。 */
    private fun band(
        plan: GroundPlan,
        band: Int,
    ): BandArt? {
        bands.get(band)?.let { return it }
        requestBand(plan, band)
        return null
    }

    /** いまの帯と、先に焼いた次の帯だけを残す。 */
    private fun retain(band: Int) {
        for (b in 1..PlotLayout.BANDS) if (b != band && b != band + 1 && bands.get(b) != null) bands.set(b, null)
    }

    private fun requestBase(plan: GroundPlan) {
        val gen = generation
        work(KEY_BASE) {
            val made = BaseArt(textures, plan)
            if (gen == generation) base = made
        }
    }

    private fun requestBand(
        plan: GroundPlan,
        band: Int,
    ) {
        if (band !in 1..PlotLayout.BANDS) return
        val gen = generation
        work(band) {
            for (b in band..minOf(band + 1, PlotLayout.BANDS)) {
                if (gen != generation || bands.get(b) != null) continue
                val made = BandArt(b, plan.bake(b), stoneFor(b), plan.lawnCoreHalf(b))
                if (gen == generation) {
                    bands.set(b, made)
                    version++
                }
            }
        }
    }

    /** 帯ごとの石畳の絵の具（2・3 は丸石、4 は敷石、5 は大理石。最初の帯には無い）。 */
    private fun stoneFor(band: Int): Material? {
        val kind =
            when {
                band <= 1 -> return null
                band <= COBBLE_UNTIL -> 0
                band < PlotLayout.BANDS -> 1
                else -> 2
            }
        return synchronized(stones) {
            stones[kind] ?: when (kind) {
                0 -> Material(textures.get(R.drawable.tex_cobble), STONE_SPAN, 0f)
                1 -> Material(textures.get(R.drawable.tex_flag), STONE_SPAN, 0f)
                else -> Material(textures.get(R.drawable.tex_marble), STONE_SPAN, 0f)
            }.also { stones[kind] = it }
        }
    }

    /** [key] の仕事を、重ならないようにバックグラウンドで走らせる。失敗（メモリ不足など）したら、以後は平らな色のまま。 */
    private fun work(
        key: Int,
        block: () -> Unit,
    ) {
        if (!pending.add(key)) return
        scope.launch(Dispatchers.Default) {
            try {
                block()
                version++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                failed = true
                version++
            } finally {
                pending.remove(key)
            }
        }
    }

    companion object {
        @Volatile private var instance: GroundArt? = null

        /** アプリ全体で 1 つ（城内に戻るたびに焼き直さない）。 */
        fun shared(context: Context): GroundArt =
            instance ?: synchronized(this) {
                instance ?: GroundArt(context, CoroutineScope(SupervisorJob() + Dispatchers.Default)).also {
                    instance = it
                }
            }

        const val SEASON_SPRING = "spring"
        const val SEASON_AUTUMN = "autumn"
        const val SEASON_WINTER = "winter"

        private const val MID = PlotLayout.MID
        private const val OPAQUE = 255
        private const val KEY_BASE = 0

        /** テクスチャ 1 枚が覆う幅（グリッド単位）と、格子に対する回転（度。通りの格子とくり返しの向きをずらす）。 */
        private const val GRASS_SPAN = 12f
        private const val GRASS_TURN = 0f
        private const val FOREST_SPAN = 9.6f
        private const val FOREST_TURN = 27f
        private const val DIRT_SPAN = 7f
        private const val DIRT_TURN = 33f
        private const val STONE_SPAN = 8f
        private const val WATER_SPAN = 6.4f
        private const val SNOW_SPAN = 9f
        private const val SNOW_TURN = 21f
        private const val STONE_KINDS = 3
        private const val COBBLE_UNTIL = 3

        /** 川の流れ（グリッド単位 / 秒）。上の層は少し速く、横にずらして重ねる。 */
        private const val WATER_FLOW = 0.5f
        private const val WATER_FLOW_TOP = 0.85f
        private const val WATER_TOP_SHIFT = 2.9f
        private const val WATER_TOP_ALPHA = 110

        /** 建設できない区画を覆う森の床の半幅（区画の半幅 2 より少し広い）と、その型の大きさ。 */
        private const val LOCK_HALF = 2.9f
        private const val BLOB_PX = 64
        private const val BLOB_EDGE = 0.42f

        /** 小物のアトラス（tex_decals: 4 列 x 2 行、1 マス 64px）。 */
        private const val ATLAS_COLUMNS = 4
        private const val ATLAS_CELL = 64

        /** 領地のテーマ: 春は明るい若草、秋は枯れ草色。 */
        private val SPRING_GRASS: ColorFilter =
            ColorMatrixColorFilter(
                floatArrayOf(
                    1.02f, 0.04f, 0f, 0f, 4f,
                    0f, 1.1f, 0f, 0f, 6f,
                    0f, 0.05f, 0.95f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        private val AUTUMN_GRASS: ColorFilter =
            ColorMatrixColorFilter(
                floatArrayOf(
                    0.62f, 0.56f, 0f, 0f, 0f,
                    0.26f, 0.74f, 0f, 0f, 0f,
                    0f, 0f, 0.82f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )

        /** 冬: 森の床は木陰の青い雪、色むらはすべて青い影にする。 */
        private val SNOW_SHADE: ColorFilter = LightingColorFilter(0xFF8FA6C4.toInt(), 0)
        private val SNOW_SHADOWS: ColorFilter = PorterDuffColorFilter(0xFF5F7FA8.toInt(), PorterDuff.Mode.SRC_IN)

        /** 川岸は、道の土より明るい砂の色。 */
        private val BANK_SAND: ColorFilter = LightingColorFilter(0xFFFFF6E0.toInt(), 0x00141208)

        private fun frameMatrix(frame: SheetFrame): Matrix =
            Matrix().apply {
                setScale(1f / frame.ppu, 1f / frame.ppu)
                postTranslate(frame.x0, frame.y0)
            }

        /** 濃さの配列（1 画素 1 byte）から ALPHA_8 のビットマップを作る。 */
        private fun alphaBitmap(
            w: Int,
            h: Int,
            data: ByteArray,
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val row = bitmap.rowBytes
            if (row == w) {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(data))
            } else {
                // 行の長さが幅と違う実装でも、行ごとに詰め直せば同じ絵になる
                val padded = ByteArray(row * h)
                for (y in 0 until h) System.arraycopy(data, y * w, padded, y * row, w)
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(padded))
            }
            return bitmap
        }

        /** 縁がやわらかく消える正方形の型（[n] x [n]）。 */
        private fun softSquare(n: Int): ByteArray {
            val data = ByteArray(n * n)
            val mid = (n - 1) / 2f
            for (y in 0 until n) {
                for (x in 0 until n) {
                    val d = maxOf(kotlin.math.abs(x - mid), kotlin.math.abs(y - mid)) / mid
                    val round = kotlin.math.hypot(x - mid, y - mid) / (mid * ROUND_REACH)
                    val v = 1f - GroundPlan.smooth((maxOf(d, round) - (1f - BLOB_EDGE)) / BLOB_EDGE)
                    data[y * n + x] = (v * OPAQUE).toInt().toByte()
                }
            }
            return data
        }

        private const val ROUND_REACH = 1.25f
    }
}
