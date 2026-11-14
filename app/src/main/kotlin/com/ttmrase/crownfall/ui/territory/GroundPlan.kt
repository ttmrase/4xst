package com.ttmrase.crownfall.ui.territory

import com.ttmrase.crownfall.core.model.BuildingType
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** グリッド空間に貼る下絵 1 枚の置き場所: 左上 ([x0], [y0])、1 グリッド単位あたり [ppu] px、[w] x [h] px。 */
internal class SheetFrame(
    val x0: Float,
    val y0: Float,
    val ppu: Float,
    val w: Int,
    val h: Int,
) {
    /** 画素 [px] の中心のグリッド座標。 */
    fun gx(px: Int): Float = x0 + (px + HALF_PIXEL) / ppu

    fun gy(py: Int): Float = y0 + (py + HALF_PIXEL) / ppu

    /** グリッド座標の点が入る画素の添字（枠の外は -1）。 */
    fun index(
        gx: Float,
        gy: Float,
    ): Int {
        val px = floor((gx - x0) * ppu).toInt()
        val py = floor((gy - y0) * ppu).toInt()
        return if (px < 0 || py < 0 || px >= w || py >= h) -1 else py * w + px
    }

    companion object {
        private const val HALF_PIXEL = 0.5f

        /** 中心 ([cx], [cy])・半幅 [half] の正方形を覆う枠。 */
        fun square(
            cx: Float,
            cy: Float,
            half: Float,
            ppu: Float,
        ): SheetFrame {
            val side = ceil(2f * half * ppu).toInt()
            return SheetFrame(cx - half, cy - half, ppu, side, side)
        }
    }
}

/** 濃さだけの下絵（0..255）。絵の具（テクスチャ）は描く側が選ぶ。 */
internal class AlphaSheet(
    val frame: SheetFrame,
    val data: ByteArray,
) {
    /** グリッド座標の点の濃さ 0..1（枠の外は 0）。 */
    fun at(
        gx: Float,
        gy: Float,
    ): Float {
        val i = frame.index(gx, gy)
        return if (i < 0) 0f else (data[i].toInt() and BYTE_MASK) / BYTE_MAX
    }

    private companion object {
        const val BYTE_MASK = 0xFF
        const val BYTE_MAX = 255f
    }
}

/** 色つきの下絵（ARGB。乗算済みではない）。 */
internal class ColorSheet(
    val frame: SheetFrame,
    val data: IntArray,
)

/** 草地に散らす小物（花・草むら・小石）。[kind] は [GroundPlan] の DECAL_* で、[band] 以上の帯で現れる。 */
internal class GroundDecals(
    val x: FloatArray,
    val y: FloatArray,
    val size: FloatArray,
    val kind: ByteArray,
    val band: ByteArray,
    val count: Int,
)

/** 帯 1 つぶんの地面の下絵（[GroundPlan.bake]）。 */
internal class GroundSheets(
    val band: Int,
    val lawn: AlphaSheet,
    val earth: AlphaSheet,
    /** 石畳。最初の帯（土の道だけの村）には無い。 */
    val stone: AlphaSheet?,
    val shade: ColorSheet,
)

/** 地面の模様に使う決定論的なノイズ（格子の乱数をなめらかにつなぐ。0..1）。 */
internal object GroundNoise {
    private const val MIX_X = 374_761_393
    private const val MIX_Y = 668_265_263
    private const val MIX_SALT = 1_274_126_177
    private const val SHIFT_A = 13
    private const val SHIFT_B = 16
    private const val MASK = 0xFFFF
    private const val SCALE = 65_535f
    private const val OCTAVE_GAIN = 0.4f
    private const val OCTAVE_FREQ = 2.17f
    private const val OCTAVE_SHIFT = 31.7f

    private fun lattice(
        ix: Int,
        iy: Int,
        salt: Int,
    ): Float {
        var h = ix * MIX_X + iy * MIX_Y + salt * MIX_SALT
        h = (h xor (h ushr SHIFT_A)) * MIX_SALT
        h = h xor (h ushr SHIFT_B)
        return (h and MASK) / SCALE
    }

    /** 値ノイズ（0..1）。[salt] が違えば別の模様。 */
    fun value(
        x: Float,
        y: Float,
        salt: Int,
    ): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val sx = fx * fx * (3f - 2f * fx)
        val sy = fy * fy * (3f - 2f * fy)
        val a = lattice(x0, y0, salt)
        val b = lattice(x0 + 1, y0, salt)
        val c = lattice(x0, y0 + 1, salt)
        val d = lattice(x0 + 1, y0 + 1, salt)
        val top = a + (b - a) * sx
        return top + ((c + (d - c) * sx) - top) * sy
    }

    /** 2 オクターブ重ねた値ノイズ（0..1）。大きなむらに、少し細かいむらが乗る。 */
    fun cloud(
        x: Float,
        y: Float,
        salt: Int,
    ): Float =
        (value(x, y, salt) + OCTAVE_GAIN * value(x * OCTAVE_FREQ + OCTAVE_SHIFT, y * OCTAVE_FREQ, salt + 1)) /
            (1f + OCTAVE_GAIN)
}

/**
 * 領地の地面の設計図（見た目だけ。GameState には触れない）。区画・通り・広場・城壁・川の配置から、
 * 「どこが草地で、どこが土で、どこが石畳か」を 0..1 の濃さで答える純関数の集まりで、Android には依存しない（JVM でテストできる）。
 * 描く側（[GroundArt]）は、これを帯ごとに 1 度だけ下絵（[GroundSheets]）に焼き、テクスチャの型抜きに使う。
 * - 通りは区画の隙間の格子（住民が歩く道筋と同じ線）のままだが、縁をノイズで揺らし、区画の並びの外では細い踏み分け道にして、
 *   城壁の内側の見回り道で終わらせる。城の帯（段階）が上がると 土 → 丸石 → 敷石 → 大理石 と敷き直される。
 * - 城のまわりと井戸（噴水）のまわりは広場。建物の足元には土のエプロン、城下町の家のまわりは踏まれた土。
 * - 門からは、外の森へ土の道が延びて消える。
 * 出力は配置だけで決まる（決定論）。
 */
internal class GroundPlan(
    /** 区画の足元の左上（x, y の並び）。 */
    private val plots: FloatArray,
    /** 城の区画の足元の中心。 */
    private val castleX: Float,
    private val castleY: Float,
    /** 城下町の飾りの足元（x, y, 半径, 現れる帯, 消える帯 の並び）。 */
    private val spots: FloatArray,
) {
    /** 1 点ぶんの計算結果の入れ物（焼くスレッドごとに 1 つ作って使い回す）。 */
    internal class Paving {
        var earth = 0f
        var stone = 0f

        /** 通りの中心の踏み跡（暗くする強さ）。 */
        var wear = 0f

        fun clear() {
            earth = 0f
            stone = 0f
            wear = 0f
        }
    }

    // ---- 点ごとの答え（テストと、小物の配置に使う）--------------------------------------------

    /** 帯 [band] の開けた草地の濃さ 0..1。城壁の少し外まで広がり、縁はノイズで揺れて森の床に溶ける。 */
    fun lawn(
        gx: Float,
        gy: Float,
        band: Int,
    ): Float = ramp(lawnEdge(gx, gy, band), LAWN_SOFT)

    /** 草地の縁からの内側への距離（グリッド単位。外は負）。 */
    private fun lawnEdge(
        gx: Float,
        gy: Float,
        band: Int,
    ): Float {
        val reach = wallHalf(band) + PlotLayout.CLEAR_MARGIN + LAWN_PUSH
        val wobble =
            (GroundNoise.value(gx * LAWN_WOBBLE_FREQ, gy * LAWN_WOBBLE_FREQ, SALT_LAWN) - HALF) * LAWN_WOBBLE +
                (GroundNoise.value(gx * LAWN_RIPPLE_FREQ, gy * LAWN_RIPPLE_FREQ, SALT_LAWN + 1) - HALF) * LAWN_RIPPLE
        return wobble - roundBox(gx - MID, gy - MID, reach, LAWN_CORNER)
    }

    /** 草地がノイズに関係なく必ず地面を覆う正方形の半幅（[MID] から）。森の床はこの内側には描かなくてよい。 */
    fun lawnCoreHalf(band: Int): Float = wallHalf(band) + LAWN_CORE_MARGIN

    /** 帯 [band] の土（道・エプロン・踏み跡）の濃さ 0..1。 */
    fun earth(
        gx: Float,
        gy: Float,
        band: Int,
    ): Float {
        val p = Paving()
        paving(gx, gy, band, p)
        var v = p.earth
        for (i in plots.indices step 2) v = max(v, apron(gx, gy, plots[i], plots[i + 1]))
        for (i in spots.indices step SPOT_STRIDE) if (spotVisible(i, band)) v = max(v, spot(gx, gy, i))
        return v
    }

    /** 帯 [band] の石畳の濃さ 0..1。 */
    fun stone(
        gx: Float,
        gy: Float,
        band: Int,
    ): Float {
        val p = Paving()
        paving(gx, gy, band, p)
        return p.stone
    }

    /** 森の床の濃さ 0..1（領地のまわりの森。遠くの縁で背景の色へ消える）。 */
    fun forest(
        gx: Float,
        gy: Float,
    ): Float {
        val wobble = (GroundNoise.value(gx * FOREST_WOBBLE_FREQ, gy * FOREST_WOBBLE_FREQ, SALT_FOREST) - HALF)
        return ramp(wobble * FOREST_WOBBLE - roundBox(gx - MID, gy - MID, FOREST_REACH, FOREST_CORNER), FOREST_SOFT)
    }

    /** 川の水面の濃さ 0..1。幅はゆるく変わり、両端は消える。 */
    fun water(
        gx: Float,
        gy: Float,
    ): Float = riverBand(gx, gy, 0f, RIVER_SOFT)

    /** 川岸（砂まじりの土）の濃さ 0..1。水面より [BANK_WIDTH] 広い。 */
    fun bank(
        gx: Float,
        gy: Float,
    ): Float = riverBand(gx, gy, BANK_WIDTH + edgeNoise(gx, gy) * BANK_RAGGED, BANK_SOFT)

    private fun riverBand(
        gx: Float,
        gy: Float,
        extra: Float,
        soft: Float,
    ): Float {
        val half = RIVER_HALF + (GroundNoise.value(gy * RIVER_WIDTH_FREQ, 0f, SALT_RIVER) - HALF) * RIVER_WIDTH_VARY
        val mid = (TerritoryScene.RIVER_FROM + TerritoryScene.RIVER_TO) / 2f
        val span = (TerritoryScene.RIVER_TO - TerritoryScene.RIVER_FROM) / 2f
        val ends = cover(abs(gy - mid), span - RIVER_END_FADE, RIVER_END_FADE)
        return cover(abs(gx - TerritoryScene.riverX(gy)), half + extra, soft) * ends
    }

    // ---- 道と広場 ----------------------------------------------------------------------------

    /** 通り・広場・見回り道・門の道の濃さを [out] に入れる（区画のエプロンと家のまわりは含まない）。 */
    fun paving(
        gx: Float,
        gy: Float,
        band: Int,
        out: Paving,
    ) {
        out.clear()
        val half = wallHalf(band)
        val cheb = max(abs(gx - MID), abs(gy - MID))
        val edge = edgeNoise(gx, gy)
        gateRoad(gx, gy, band, half, edge, out)
        if (cheb > half + WALL_OUTSIDE) return
        val streets = TownLife.streetRange(band)
        val limit = half - PERIMETER_AT
        street(gy, gx, gx, gy, band, streets, limit, edge, out)
        street(gx, gy, gx, gy, band, streets, limit, edge, out)
        plaza(gx, gy, band, edge, out)
        // 城壁の内側: 見回りの踏み分け道と、壁ぎわの擦れた草
        val inside = half - cheb
        val patch = PATH_PATCH_MIN + (1f - PATH_PATCH_MIN) * patchiness(gx, gy, SALT_PATH)
        val path = cover(abs(inside - PERIMETER_AT), PERIMETER_HALF + edge, SOFT) * patch * PERIMETER_ALPHA
        val worn = cover(abs(inside - WORN_AT), WORN_HALF, WORN_SOFT) * WORN_ALPHA * patch
        out.earth = max(out.earth, max(path, worn))
    }

    /**
     * 1 方向の通り。[across] は通りを横切る向きの座標、[along] は通りに沿った座標。
     * 区画の並びの中（[CORE_REACH] まで）は広い道、その外は細くて途切れがちな踏み分け道になる。
     */
    private fun street(
        across: Float,
        along: Float,
        gx: Float,
        gy: Float,
        band: Int,
        streets: IntRange,
        limit: Float,
        edge: Float,
        out: Paving,
    ) {
        val k = floor((across + STREET_OFFSET) / PlotLayout.CELL + HALF).toInt()
        if (k !in streets) return
        val d = abs(across - TownLife.streetCoord(k))
        if (d > STREET_REACH) return
        val t = abs(along - MID)
        val end = cover(t, limit, END_SOFT)
        if (end <= 0f) return
        val avenue = k == AVENUE
        // 区画の並びの中にいる割合（1 = 中、0 = 外の城下町）
        val core = if (k in CORE_FIRST..CORE_LAST) cover(t, CORE_REACH, CORE_SOFT) else 0f
        val wide = if (avenue) 1f else core
        val halfWidth = LANE_HALF + ((if (avenue) AVENUE_HALF else CORE_HALF) - LANE_HALF) * wide
        val paved =
            when (band) {
                1 -> 0f
                2 -> if (avenue) core else 0f
                PlotLayout.BANDS -> 1f
                else -> wide
            }
        // 細い道は、ところどころ草に埋もれる
        val alive = wide + (1f - wide) * (LANE_PATCH_MIN + (1f - LANE_PATCH_MIN) * patchiness(gx, gy, SALT_LANE))
        val bed = cover(d, halfWidth + SHOULDER * paved + edge, SOFT) * end * alive
        out.earth = max(out.earth, bed)
        if (paved > 0f) {
            out.stone = max(out.stone, cover(d, halfWidth + edge * STONE_EDGE, STONE_SOFT) * end * paved)
        }
        val rut = d / WEAR_WIDTH
        out.wear = max(out.wear, exp(-rut * rut) * end * (WEAR_LANE + (1f - WEAR_LANE) * wide))
    }

    /** 城のまわりと、井戸（噴水）のまわりの広場。最初の帯は踏み固めた土、あとは石畳。 */
    private fun plaza(
        gx: Float,
        gy: Float,
        band: Int,
        edge: Float,
        out: Paving,
    ) {
        val ring = roundBox(gx - castleX, gy - castleY, HALF_PLOT + PLAZA_GROW[band - 1], PLAZA_CORNER)
        val well = dist(gx, gy, TownLife.PLAZA, TownLife.PLAZA) - WELL_RADIUS[band - 1]
        val d = min(ring, well) + edge * PLAZA_EDGE
        if (d > PLAZA_REACH) return
        out.earth = max(out.earth, cover(d, SHOULDER, SOFT))
        if (band > 1) out.stone = max(out.stone, cover(d, 0f, STONE_SOFT))
    }

    /**
     * 門（手前の角）を抜ける道。門にいちばん近い交差点から斜めに門へ、門の外では少し蛇行しながら森へ消える。
     * 帯 3 以上は、門の手前まで石畳。
     */
    private fun gateRoad(
        gx: Float,
        gy: Float,
        band: Int,
        half: Float,
        edge: Float,
        out: Paving,
    ) {
        val gate = MID + half
        val a = ((gx - gate) + (gy - gate)) * INV_SQRT2
        val p = ((gx - gate) - (gy - gate)) * INV_SQRT2
        val start = (TownLife.streetCoord(TownLife.streetRange(band).last) - gate) / INV_SQRT2
        if (a < start - GATE_START_SOFT || a > GATE_OUT || abs(p) > GATE_REACH) return
        val outside = a.coerceAtLeast(0f)
        val sway = sin(outside * GATE_SWAY_FREQ) * GATE_SWAY * min(1f, outside / GATE_SWAY_EASE)
        val halfWidth = GATE_HALF - (GATE_HALF - GATE_HALF_END) * (outside / GATE_OUT)
        val begin = ramp(a - start, GATE_START_SOFT)
        val fade = cover(a, GATE_OUT - GATE_FADE, GATE_FADE)
        out.earth = max(out.earth, cover(abs(p - sway), halfWidth + SHOULDER + edge, SOFT) * begin * fade)
        if (band >= GATE_STONE_BAND) {
            val reach = cover(a, GATE_STONE_OUT, GATE_START_SOFT)
            out.stone = max(out.stone, cover(abs(p), halfWidth + edge * STONE_EDGE, STONE_SOFT) * begin * reach)
        }
    }

    /** 区画（左上が [x0], [y0]）の足元のまわりの土。建物の土台が、草ではなく踏まれた土の上に乗る。 */
    private fun apron(
        gx: Float,
        gy: Float,
        x0: Float,
        y0: Float,
    ): Float {
        val d = roundBox(gx - x0 - HALF_PLOT, gy - y0 - HALF_PLOT, HALF_PLOT + APRON, APRON_CORNER)
        if (d > APRON_REACH) return 0f
        val ragged = edgeNoise(gx, gy) * APRON_RAGGED + (GroundNoise.value(gx, gy, SALT_APRON) - HALF) * APRON_WAVE
        return cover(d + ragged, 0f, APRON_SOFT) * APRON_ALPHA
    }

    private fun spotVisible(
        i: Int,
        band: Int,
    ): Boolean = spots[i + SPOT_INTRO] <= band && spots[i + SPOT_GONE] > band

    /** 城下町の家・露店のまわりの踏まれた土（[i] は [spots] の中の位置）。 */
    private fun spot(
        gx: Float,
        gy: Float,
        i: Int,
    ): Float {
        val d = dist(gx, gy, spots[i], spots[i + 1]) - spots[i + SPOT_RADIUS]
        if (d > APRON_REACH) return 0f
        return cover(d + edgeNoise(gx, gy) * SPOT_RAGGED, 0f, SPOT_SOFT) * SPOT_ALPHA
    }

    // ---- 色のむら（明暗）-----------------------------------------------------------------------

    /**
     * 草地に重ねる色のむら（ARGB。乗算済みではない）。大きな明暗（日なたの黄緑 / 木陰の深い緑）、細かいむら、
     * 通りの中心の踏み跡、森の縁の木陰。[wear] は [Paving.wear]。枠の縁では透明に消える。
     */
    private fun shade(
        gx: Float,
        gy: Float,
        band: Int,
        wear: Float,
        acc: FloatArray,
    ): Int {
        acc.fill(0f)
        val tone = ((GroundNoise.cloud(gx * TONE_FREQ, gy * TONE_FREQ, SALT_TONE) - HALF) * TONE_GAIN).coerceIn(-1f, 1f)
        if (tone > 0f) over(acc, SUN_TINT, tone * TONE_SUN) else over(acc, DEEP_TINT, -tone * TONE_DEEP)
        val mottle = (GroundNoise.value(gx * MOTTLE_FREQ, gy * MOTTLE_FREQ, SALT_TONE + 2) - HALF) * 2f
        if (mottle > 0f) over(acc, SUN_TINT, mottle * MOTTLE) else over(acc, DEEP_TINT, -mottle * MOTTLE)
        if (wear > 0f) over(acc, WEAR_TINT, wear * WEAR_ALPHA)
        val inside = lawnEdge(gx, gy, band)
        over(acc, CANOPY_TINT, CANOPY_ALPHA * (1f - smooth(inside / CANOPY_DEPTH)))
        val a = acc[ACC_A] * cover(max(abs(gx - MID), abs(gy - MID)), frameHalf(band) - FRAME_FADE, FRAME_FADE)
        if (a <= MIN_ALPHA) return 0
        return argb(a, acc[0] / acc[ACC_A], acc[1] / acc[ACC_A], acc[2] / acc[ACC_A])
    }

    // ---- 下絵を焼く ---------------------------------------------------------------------------

    /** 草地・道・色むらの下絵が覆う正方形の半幅（[MID] から）。草地の縁の揺れまで入る。 */
    fun frameHalf(band: Int): Float = wallHalf(band) + FRAME_MARGIN

    /** 帯 [band] の下絵を焼く（数十 ms かかるので、描画スレッドでは呼ばない）。 */
    fun bake(band: Int): GroundSheets {
        val half = frameHalf(band)
        val lawnFrame = SheetFrame.square(MID, MID, half, LAWN_PPU)
        val lawn = ByteArray(lawnFrame.w * lawnFrame.h)
        for (py in 0 until lawnFrame.h) {
            val gy = lawnFrame.gy(py)
            for (px in 0 until lawnFrame.w) lawn[py * lawnFrame.w + px] = toByte(lawn(lawnFrame.gx(px), gy, band))
        }
        val paveFrame = SheetFrame.square(MID, MID, half, PAVING_PPU)
        val earth = ByteArray(paveFrame.w * paveFrame.h)
        val stone = if (band > 1) ByteArray(paveFrame.w * paveFrame.h) else null
        val p = Paving()
        for (py in 0 until paveFrame.h) {
            val gy = paveFrame.gy(py)
            for (px in 0 until paveFrame.w) {
                paving(paveFrame.gx(px), gy, band, p)
                val i = py * paveFrame.w + px
                earth[i] = toByte(p.earth)
                if (stone != null) stone[i] = toByte(p.stone)
            }
        }
        // 区画のエプロンと家のまわりは、それぞれの外接矩形の中だけを塗る（全画素で全区画を調べない）
        for (i in plots.indices step 2) {
            val x0 = plots[i]
            val y0 = plots[i + 1]
            val pad = APRON + APRON_REACH
            stamp(paveFrame, earth, x0 - pad, y0 - pad, x0 + PLOT_SIZE + pad, y0 + PLOT_SIZE + pad) { gx, gy ->
                apron(gx, gy, x0, y0)
            }
        }
        for (i in spots.indices step SPOT_STRIDE) {
            if (!spotVisible(i, band)) continue
            val r = spots[i + SPOT_RADIUS] + APRON_REACH
            stamp(paveFrame, earth, spots[i] - r, spots[i + 1] - r, spots[i] + r, spots[i + 1] + r) { gx, gy ->
                spot(gx, gy, i)
            }
        }
        val shadeFrame = SheetFrame.square(MID, MID, half, SHADE_PPU)
        val shade = IntArray(shadeFrame.w * shadeFrame.h)
        val acc = FloatArray(ACC_SIZE)
        for (py in 0 until shadeFrame.h) {
            val gy = shadeFrame.gy(py)
            for (px in 0 until shadeFrame.w) {
                val gx = shadeFrame.gx(px)
                paving(gx, gy, band, p)
                shade[py * shadeFrame.w + px] = shade(gx, gy, band, p.wear, acc)
            }
        }
        return GroundSheets(
            band,
            AlphaSheet(lawnFrame, lawn),
            AlphaSheet(paveFrame, earth),
            stone?.let { AlphaSheet(paveFrame, it) },
            ColorSheet(shadeFrame, shade),
        )
    }

    private inline fun stamp(
        frame: SheetFrame,
        data: ByteArray,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        value: (Float, Float) -> Float,
    ) {
        val fromX = floor((x0 - frame.x0) * frame.ppu).toInt().coerceAtLeast(0)
        val fromY = floor((y0 - frame.y0) * frame.ppu).toInt().coerceAtLeast(0)
        val toX = floor((x1 - frame.x0) * frame.ppu).toInt().coerceAtMost(frame.w - 1)
        val toY = floor((y1 - frame.y0) * frame.ppu).toInt().coerceAtMost(frame.h - 1)
        for (py in fromY..toY) {
            val gy = frame.gy(py)
            for (px in fromX..toX) {
                val i = py * frame.w + px
                val v = toByte(value(frame.gx(px), gy))
                if ((v.toInt() and BYTE_MASK) > (data[i].toInt() and BYTE_MASK)) data[i] = v
            }
        }
    }

    /** 森の床の下絵（帯に依らない）。 */
    fun bakeForest(): AlphaSheet {
        val frame = SheetFrame.square(MID, MID, FOREST_REACH + FOREST_WOBBLE + FOREST_SOFT, FOREST_PPU)
        return alphaSheet(frame) { gx, gy -> forest(gx, gy) }
    }

    /** 川の水面と川岸の下絵（同じ枠。帯に依らない）。 */
    fun bakeRiver(): Pair<AlphaSheet, AlphaSheet> {
        val x0 = RIVER_CENTER - RIVER_FRAME_HALF
        val w = ceil(2f * RIVER_FRAME_HALF * PAVING_PPU).toInt()
        val h = ceil((TerritoryScene.RIVER_TO - TerritoryScene.RIVER_FROM) * PAVING_PPU).toInt()
        val frame = SheetFrame(x0, TerritoryScene.RIVER_FROM, PAVING_PPU, w, h)
        return alphaSheet(frame) { gx, gy -> water(gx, gy) } to alphaSheet(frame) { gx, gy -> bank(gx, gy) }
    }

    private inline fun alphaSheet(
        frame: SheetFrame,
        value: (Float, Float) -> Float,
    ): AlphaSheet {
        val data = ByteArray(frame.w * frame.h)
        for (py in 0 until frame.h) {
            val gy = frame.gy(py)
            for (px in 0 until frame.w) data[py * frame.w + px] = toByte(value(frame.gx(px), gy))
        }
        return AlphaSheet(frame, data)
    }

    // ---- 小物 ---------------------------------------------------------------------------------

    /**
     * 草地の小物（花の群れ・草むら・小石）の配置。通り・広場・区画・家のまわりは避ける。
     * 花は低周波のノイズで「咲いている一帯」に固め、同じ一帯はほぼ同じ色にする（ばらまいた紙吹雪に見えないように）。
     */
    fun decals(): GroundDecals {
        val reach = wallHalf(PlotLayout.BANDS) - DECAL_WALL_GAP
        val n = floor(reach / DECAL_STEP).toInt()
        val room = (2 * n + 1) * (2 * n + 1)
        val xs = FloatArray(room)
        val ys = FloatArray(room)
        val sizes = FloatArray(room)
        val kinds = ByteArray(room)
        val bands = ByteArray(room)
        var count = 0
        val p = Paving()
        for (b in -n..n) {
            for (a in -n..n) {
                val gx = MID + (a + (GroundNoise.value(a * 7.3f, b * 3.1f, SALT_DECAL) - HALF) * DECAL_JITTER) * DECAL_STEP
                val gy = MID + (b + (GroundNoise.value(a * 5.7f, b * 9.1f, SALT_DECAL + 1) - HALF) * DECAL_JITTER) * DECAL_STEP
                val roll = GroundNoise.value(a * 13.7f, b * 17.9f, SALT_DECAL + 2)
                val kind = decalKind(gx, gy, roll)
                if (kind < 0) continue
                val band = bandFor(max(abs(gx - MID), abs(gy - MID)) + DECAL_WALL_GAP)
                if (band > PlotLayout.BANDS || !decalFits(gx, gy, band, kind, p)) continue
                xs[count] = gx
                ys[count] = gy
                sizes[count] = DECAL_SIZE_MIN + DECAL_SIZE_SPAN * GroundNoise.value(a * 3.3f, b * 2.9f, SALT_DECAL + 3)
                kinds[count] = kind.toByte()
                bands[count] = band.toByte()
                count++
            }
        }
        return GroundDecals(xs, ys, sizes, kinds, bands, count)
    }

    /** 点 ([gx], [gy]) に置く小物の種類（置かないなら -1）。[roll] は 0..1 の乱数。 */
    private fun decalKind(
        gx: Float,
        gy: Float,
        roll: Float,
    ): Int {
        val bloom = GroundNoise.value(gx * BLOOM_FREQ, gy * BLOOM_FREQ, SALT_DECAL + 4)
        if (bloom > BLOOM_FROM) {
            if (roll > BLOOM_SHARE) return -1
            val hue = GroundNoise.value(gx * HUE_FREQ, gy * HUE_FREQ, SALT_DECAL + 5) + (roll - HALF) * HUE_STRAY
            return when {
                hue < HUE_DAISY -> DECAL_DAISY
                hue < HUE_BUTTERCUP -> DECAL_BUTTERCUP
                hue < HUE_POPPY -> DECAL_POPPY
                else -> DECAL_BLUEBELL
            }
        }
        return when {
            roll < SHARE_TUFT_DARK -> DECAL_TUFT_DARK
            roll < SHARE_TUFT_LIGHT -> DECAL_TUFT_LIGHT
            roll < SHARE_PEBBLES -> DECAL_PEBBLES
            else -> -1
        }
    }

    /** 小物が、その帯より後のどの帯でも道・広場・区画・家にかからないか。 */
    private fun decalFits(
        gx: Float,
        gy: Float,
        band: Int,
        kind: Int,
        p: Paving,
    ): Boolean {
        // 小石は道ばたにも転がる。花と草むらは土の上には置かない
        val limit = if (kind == DECAL_PEBBLES) DECAL_ON_EARTH_PEBBLES else DECAL_ON_EARTH
        for (i in plots.indices step 2) if (apron(gx, gy, plots[i], plots[i + 1]) > limit) return false
        for (i in spots.indices step SPOT_STRIDE) if (spot(gx, gy, i) > limit) return false
        for (b in band..PlotLayout.BANDS) {
            paving(gx, gy, b, p)
            if (p.earth > limit || p.stone > limit) return false
        }
        return true
    }

    companion object {
        const val DECAL_DAISY = 0
        const val DECAL_BUTTERCUP = 1
        const val DECAL_POPPY = 2
        const val DECAL_BLUEBELL = 3
        const val DECAL_TUFT_DARK = 4
        const val DECAL_TUFT_LIGHT = 5
        const val DECAL_PEBBLES = 6
        const val DECAL_LEAVES = 7

        /** 下絵の細かさ（1 グリッド単位あたりの px）。草地の縁と色むらはぼんやり、道の縁はくっきりめ。 */
        const val LAWN_PPU = 4f
        const val PAVING_PPU = 8f
        const val SHADE_PPU = 5f
        const val FOREST_PPU = 2f

        private const val MID = PlotLayout.MID
        private const val HALF = 0.5f
        private const val HALF_PLOT = 2f
        private const val PLOT_SIZE = 4f
        private const val INV_SQRT2 = 0.70710677f
        private const val BYTE_MASK = 0xFF
        private const val BYTE_MAX = 255f
        private const val MIN_ALPHA = 0.004f

        // 草地の縁
        private const val LAWN_PUSH = 0.9f
        private const val LAWN_CORNER = 1.5f
        private const val LAWN_WOBBLE = 1.6f
        private const val LAWN_WOBBLE_FREQ = 0.33f
        private const val LAWN_RIPPLE = 0.5f
        private const val LAWN_RIPPLE_FREQ = 1.1f
        private const val LAWN_SOFT = 0.45f

        /** 城壁の線から、草地が必ず地面を覆う幅（[LAWN_PUSH] ＋ 伐採の余白 − 揺れ − ぼかし より小さい値）。 */
        private const val LAWN_CORE_MARGIN = 0.5f

        /** 下絵の枠が城壁の外へ取る余白（草地の縁の揺れが収まる幅）。 */
        private const val FRAME_MARGIN = 4.4f
        private const val FRAME_FADE = 0.8f

        // 通り
        /** 通りの線は k x CELL - 0.5 にある。 */
        private const val STREET_OFFSET = 0.5f
        private const val STREET_REACH = 1.1f
        private const val AVENUE = 1
        private const val CORE_FIRST = -2
        private const val CORE_LAST = 4
        private const val CORE_REACH = 15.5f
        private const val CORE_SOFT = 0.7f
        private const val AVENUE_HALF = 0.5f
        private const val CORE_HALF = 0.44f
        private const val LANE_HALF = 0.27f
        private const val LANE_PATCH_MIN = 0.45f
        private const val SHOULDER = 0.15f
        private const val SOFT = 0.13f
        private const val STONE_SOFT = 0.08f
        private const val STONE_EDGE = 0.5f
        private const val END_SOFT = 0.45f
        private const val WEAR_WIDTH = 0.24f
        private const val WEAR_LANE = 0.5f

        // 城壁の内側
        private const val WALL_OUTSIDE = 0.4f
        private const val PERIMETER_AT = 1.15f
        private const val PERIMETER_HALF = 0.3f
        private const val PERIMETER_ALPHA = 0.9f
        private const val PATH_PATCH_MIN = 0.4f
        private const val WORN_AT = 0.55f
        private const val WORN_HALF = 0.55f
        private const val WORN_SOFT = 0.45f
        private const val WORN_ALPHA = 0.42f

        // 広場（帯ごと: 城の区画から広げる幅と、井戸・噴水のまわりの半径）
        private val PLAZA_GROW = floatArrayOf(0.5f, 0.8f, 0.95f, 1.0f, 1.0f)
        private val WELL_RADIUS = floatArrayOf(1.5f, 1.7f, 1.9f, 2.2f, 2.5f)
        private const val PLAZA_CORNER = 0.9f
        private const val PLAZA_EDGE = 0.6f
        private const val PLAZA_REACH = 0.6f

        // 門の道
        private const val GATE_OUT = 10f
        private const val GATE_FADE = 2.5f
        private const val GATE_HALF = 0.55f
        private const val GATE_HALF_END = 0.34f
        private const val GATE_REACH = 2f
        private const val GATE_SWAY = 0.5f
        private const val GATE_SWAY_FREQ = 0.55f
        private const val GATE_SWAY_EASE = 2.5f
        private const val GATE_START_SOFT = 0.4f
        private const val GATE_STONE_BAND = 3
        private const val GATE_STONE_OUT = 0.8f

        // エプロンと家のまわり
        private const val APRON = 0.34f
        private const val APRON_CORNER = 0.6f
        private const val APRON_REACH = 0.7f
        private const val APRON_RAGGED = 1.2f
        private const val APRON_WAVE = 0.3f
        private const val APRON_SOFT = 0.2f
        private const val APRON_ALPHA = 0.9f
        private const val SPOT_STRIDE = 5
        private const val SPOT_RADIUS = 2
        private const val SPOT_INTRO = 3
        private const val SPOT_GONE = 4
        private const val SPOT_RAGGED = 1.6f
        private const val SPOT_SOFT = 0.3f
        private const val SPOT_ALPHA = 0.72f
        private const val HOUSE_RADIUS = 1.05f
        private const val STALL_RADIUS = 0.95f
        private const val MANOR_RADIUS = 1.35f
        private const val STATUE_RADIUS = 0.7f

        // 縁の揺れ
        private const val EDGE_FREQ = 1.9f
        private const val EDGE_AMP = 0.2f
        private const val EDGE_FINE_FREQ = 6.1f
        private const val EDGE_FINE_AMP = 0.08f
        private const val PATCH_FREQ = 0.23f
        private const val PATCH_FROM = 0.28f
        private const val PATCH_SPAN = 0.3f

        // 森と川
        private const val FOREST_REACH = 52f
        private const val FOREST_CORNER = 16f
        private const val FOREST_WOBBLE = 6f
        private const val FOREST_WOBBLE_FREQ = 0.09f
        private const val FOREST_SOFT = 3f
        private const val RIVER_CENTER = 45f
        private const val RIVER_FRAME_HALF = 6f
        private const val RIVER_HALF = 1.8f
        private const val RIVER_WIDTH_VARY = 0.55f
        private const val RIVER_WIDTH_FREQ = 0.21f
        private const val RIVER_SOFT = 0.2f
        private const val RIVER_END_FADE = 3f
        private const val BANK_WIDTH = 0.85f
        private const val BANK_RAGGED = 1.5f
        private const val BANK_SOFT = 0.4f

        // 色むら
        private const val ACC_SIZE = 4
        private const val ACC_A = 3
        private const val TONE_FREQ = 0.075f
        private const val TONE_GAIN = 3.4f
        private const val TONE_SUN = 0.2f
        private const val TONE_DEEP = 0.26f
        private const val MOTTLE_FREQ = 0.36f
        private const val MOTTLE = 0.07f
        private const val WEAR_ALPHA = 0.2f
        private const val CANOPY_ALPHA = 0.24f
        private const val CANOPY_DEPTH = 3.2f
        private val SUN_TINT = floatArrayOf(0.96f, 0.93f, 0.44f)
        private val DEEP_TINT = floatArrayOf(0.05f, 0.24f, 0.2f)
        private val WEAR_TINT = floatArrayOf(0.16f, 0.1f, 0.05f)
        private val CANOPY_TINT = floatArrayOf(0.04f, 0.15f, 0.1f)

        // 小物
        private const val DECAL_STEP = 1.9f
        private const val DECAL_JITTER = 0.9f
        private const val DECAL_WALL_GAP = 1.6f
        private const val DECAL_SIZE_MIN = 1.3f
        private const val DECAL_SIZE_SPAN = 0.9f
        private const val DECAL_ON_EARTH = 0.06f
        private const val DECAL_ON_EARTH_PEBBLES = 0.6f
        private const val BLOOM_FREQ = 0.14f
        private const val BLOOM_FROM = 0.6f
        private const val BLOOM_SHARE = 0.8f
        private const val HUE_FREQ = 0.09f
        private const val HUE_STRAY = 0.25f
        private const val HUE_DAISY = 0.4f
        private const val HUE_BUTTERCUP = 0.58f
        private const val HUE_POPPY = 0.7f
        private const val SHARE_TUFT_DARK = 0.2f
        private const val SHARE_TUFT_LIGHT = 0.36f
        private const val SHARE_PEBBLES = 0.43f

        private const val SALT_LAWN = 11
        private const val SALT_FOREST = 21
        private const val SALT_RIVER = 31
        private const val SALT_PATH = 41
        private const val SALT_LANE = 51
        private const val SALT_APRON = 61
        private const val SALT_EDGE = 71
        private const val SALT_TONE = 81
        private const val SALT_DECAL = 91

        private fun wallHalf(band: Int): Float = PlotLayout.WALL_HALF[band.coerceIn(1, PlotLayout.BANDS) - 1]

        /** 城壁の内側（[cheb] が半幅以下）になる最初の帯。どの帯の外でもあれば [PlotLayout.BANDS] + 1。 */
        private fun bandFor(cheb: Float): Int {
            for (b in 1..PlotLayout.BANDS) if (cheb <= PlotLayout.WALL_HALF[b - 1]) return b
            return PlotLayout.BANDS + 1
        }

        /** 区画の並びと、城下町の飾り（[ents]）から設計図を作る。 */
        fun of(
            slotCells: List<Triple<Int, Pair<Int, Int>, BuildingType>>,
            ents: Array<Ent> = emptyArray(),
        ): GroundPlan {
            val plots = FloatArray(slotCells.size * 2)
            var castleX = HALF_PLOT
            var castleY = HALF_PLOT
            slotCells.forEachIndexed { i, (_, cell, type) ->
                val (x0, y0) = PlotLayout.footprintOrigin(cell)
                plots[i * 2] = x0
                plots[i * 2 + 1] = y0
                if (type == BuildingType.CASTLE) {
                    castleX = x0 + HALF_PLOT
                    castleY = y0 + HALF_PLOT
                }
            }
            val town = ents.filter { spotRadius(it.kind) > 0f }
            val spots = FloatArray(town.size * SPOT_STRIDE)
            town.forEachIndexed { i, e ->
                val at = i * SPOT_STRIDE
                spots[at] = e.gx
                spots[at + 1] = e.gy
                spots[at + SPOT_RADIUS] = spotRadius(e.kind)
                spots[at + SPOT_INTRO] = e.introBand.toFloat()
                spots[at + SPOT_GONE] = e.goneBand.toFloat()
            }
            return GroundPlan(plots, castleX, castleY, spots)
        }

        private fun spotRadius(kind: EntKind): Float =
            when (kind) {
                EntKind.HOUSE -> HOUSE_RADIUS
                EntKind.STALL -> STALL_RADIUS
                EntKind.MANOR -> MANOR_RADIUS
                EntKind.STATUE -> STATUE_RADIUS
                else -> 0f
            }

        /** 0〜1 のなめらかな立ち上がり。 */
        fun smooth(v: Float): Float {
            val u = v.coerceIn(0f, 1f)
            return u * u * (3f - 2f * u)
        }

        /** 内側への距離 [inside] が -[soft]..[soft] の間で 0 → 1 に立ち上がる。 */
        private fun ramp(
            inside: Float,
            soft: Float,
        ): Float = smooth((inside + soft) / (2f * soft))

        /** 中心からの距離 [d] が [half] より内側なら 1、外なら 0（境目は ±[soft] でぼかす）。 */
        private fun cover(
            d: Float,
            half: Float,
            soft: Float,
        ): Float = ramp(half - d, soft)

        /** 角を [r] で丸めた、半幅 [half] の正方形までの符号つき距離（内側は負）。 */
        private fun roundBox(
            dx: Float,
            dy: Float,
            half: Float,
            r: Float,
        ): Float {
            val qx = abs(dx) - (half - r)
            val qy = abs(dy) - (half - r)
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
        }

        private fun dist(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))

        /** 道や広場の縁を揺らす量（グリッド単位。およそ ±0.14）。 */
        private fun edgeNoise(
            gx: Float,
            gy: Float,
        ): Float =
            (GroundNoise.value(gx * EDGE_FREQ, gy * EDGE_FREQ, SALT_EDGE) - HALF) * EDGE_AMP +
                (GroundNoise.value(gx * EDGE_FINE_FREQ, gy * EDGE_FINE_FREQ, SALT_EDGE + 1) - HALF) * EDGE_FINE_AMP

        /** 踏み分け道が見えている割合 0..1（低周波。0 の一帯では草に埋もれる）。 */
        private fun patchiness(
            gx: Float,
            gy: Float,
            salt: Int,
        ): Float = smooth((GroundNoise.value(gx * PATCH_FREQ, gy * PATCH_FREQ, salt) - PATCH_FROM) / PATCH_SPAN)

        private fun toByte(v: Float): Byte = (v.coerceIn(0f, 1f) * BYTE_MAX + HALF).toInt().toByte()

        /** [acc]（乗算済みの r, g, b, a）に、色 [tint] を濃さ [alpha] で重ねる。 */
        private fun over(
            acc: FloatArray,
            tint: FloatArray,
            alpha: Float,
        ) {
            val a = alpha.coerceIn(0f, 1f)
            if (a <= 0f) return
            for (k in 0 until ACC_A) acc[k] = tint[k] * a + acc[k] * (1f - a)
            acc[ACC_A] = a + acc[ACC_A] * (1f - a)
        }

        private fun argb(
            a: Float,
            r: Float,
            g: Float,
            b: Float,
        ): Int =
            (channel(a) shl ALPHA_SHIFT) or (channel(r) shl RED_SHIFT) or (channel(g) shl GREEN_SHIFT) or channel(b)

        private fun channel(v: Float): Int = (v.coerceIn(0f, 1f) * BYTE_MAX + HALF).toInt()

        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8

        /** 地面のテクスチャの解像度の段階の数（等倍・1/2・1/4）。 */
        const val TEXTURE_LEVELS = 3

        /**
         * 画面の倍率 [sc] のとき、1 グリッド単位あたり [ppu] px のテクスチャに使う段階（0 = 等倍、1 = 1/2 …）。
         * 画面上で 1 グリッド単位は、いちばん長い向き（左右）でおよそ [SCREEN_PX_PER_UNIT] x [sc] px。それより
         * [SHARPNESS] 倍を超えて細かいテクスチャは、縮小でちらつくので粗い段階に替える。
         */
        fun textureLevel(
            ppu: Float,
            sc: Float,
        ): Int {
            val wanted = SCREEN_PX_PER_UNIT * sc * SHARPNESS
            var level = 0
            var density = ppu
            while (level < TEXTURE_LEVELS - 1 && density > wanted) {
                density /= 2f
                level++
            }
            return level
        }

        /** グリッドの対角（画面の左右）方向の 1 単位が、倍率 1 で画面に占める px（[PlotLayout.U] x √2）。 */
        private const val SCREEN_PX_PER_UNIT = 19.8f
        private const val SHARPNESS = 1.3f
    }
}
