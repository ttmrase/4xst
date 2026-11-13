package com.ttmrase.crownfall.ui.map

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.core.alliance.Personas
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.Structure
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.Terrain
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.core.march.March
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.MarchViews
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.kit.GlyphBitmaps
import com.ttmrase.crownfall.ui.territory.FigureLook
import com.ttmrase.crownfall.ui.territory.LifeArt
import com.ttmrase.crownfall.ui.territory.LifeDraw
import com.ttmrase.crownfall.ui.territory.LifeDraw.bird
import com.ttmrase.crownfall.ui.territory.LifeDraw.figure
import com.ttmrase.crownfall.ui.territory.LifeDraw.flag
import com.ttmrase.crownfall.ui.territory.LifeDraw.flame
import com.ttmrase.crownfall.ui.territory.LifeDraw.fract
import com.ttmrase.crownfall.ui.territory.LifeDraw.hash01
import com.ttmrase.crownfall.ui.territory.LifeDraw.soft
import com.ttmrase.crownfall.ui.territory.Sprite
import com.ttmrase.crownfall.ui.territory.SpriteCache
import com.ttmrase.crownfall.ui.territory.SpriteIds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 装備中のスキンから決まる、マップの見た目（M11 T11.4）。null はすべて標準。 */
internal data class MapStyle(
    val castleTheme: String? = null,
    val territoryTheme: String? = null,
    val trailTheme: String? = null,
)

/** マップの「昼夜の色味」の設定。 */
internal enum class DayNight(val id: Int) {
    AUTO(0),
    DAY(1),
    NIGHT(2),
    ;

    companion object {
        fun of(id: Int): DayNight = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * 行軍の位置を、秒の刻みのあいだもなめらかに進める（:core の位置は 1 秒ごとに変わるので、そのまま描くと 1 秒ごとに跳ぶ）。
 * 秒が変わったときだけ「いまの位置」と「1 秒後の位置」を [MarchViews.position] で求めて覚え、描画ではそのあいだを
 * 実時間で補間する。見た目だけで、ゲームの状態には触れない。毎フレームの確保はしない。
 */
internal class MarchTrack {
    private var sec = Long.MIN_VALUE
    private var atMs = 0L
    private var marches: List<March>? = null
    private var from = FloatArray(0)
    private var to = FloatArray(0)
    private var frac = 0f

    /** 描画の始めに呼ぶ。[nowSec] はゲームの時計（秒）、[nowMs] は単調増加の実時間。 */
    fun update(
        marches: List<March>,
        nowSec: Long,
        nowMs: Long,
        data: GameData,
        animate: Boolean,
    ) {
        if (nowSec != sec || marches !== this.marches) {
            if (nowSec != sec) atMs = nowMs
            sec = nowSec
            this.marches = marches
            if (from.size < marches.size * 2) {
                from = FloatArray(marches.size * 2)
                to = FloatArray(marches.size * 2)
            }
            for (i in marches.indices) {
                val a = MarchViews.position(marches[i], nowSec, data)
                val b = MarchViews.position(marches[i], nowSec + 1, data)
                from[i * 2] = a.first
                from[i * 2 + 1] = a.second
                to[i * 2] = b.first
                to[i * 2 + 1] = b.second
            }
        }
        frac = if (animate) ((nowMs - atMs) / MS_PER_SEC).coerceIn(0f, 1f) else 0f
    }

    /** [i] 番目の行軍の位置（タイル座標）。 */
    fun x(i: Int): Float = from[i * 2] + (to[i * 2] - from[i * 2]) * frac

    fun y(i: Int): Float = from[i * 2 + 1] + (to[i * 2 + 1] - from[i * 2 + 1]) * frac

    private companion object {
        const val MS_PER_SEC = 1000f
    }
}

/**
 * 地面（キャッシュ済みチャンク）の上に毎フレーム描くもの: 領土の輪・自城の脈打つ輪・川と湖の光・霧・拠点（はためく旗）・
 * 資源の光・野営地の焚き火・行軍（歩く兵と土ぼこり）・雲とその影・鳥・昼夜の色味。ズームが遠いと細部を省く（LOD）。
 * 動きは [seconds]（飾りの時計）だけで決まり、描画のたびにオブジェクトを確保しない。
 */
internal object MapLive {
    private val CASTLE = Color(0xFFE8B730)
    private val PULSE_GOLD = Color(0xFFFFE08A)
    private val ALLY = Color(0xFF4F9DDE)
    private val NPC = Color(0xFFC0392B)
    private val FORT = Color(0xFF6B1F1F)
    private val HOLY = Color(0xFFF5F0DC)
    private val FOG = Color(0xFF0F1A33)
    private val DUST = Color(0xFFD9C7A0)
    private val HILL_DARK = Color(0xFF5B8A3F)
    private val HILL_LIGHT = Color(0xFF7BA650)
    private val HILL_ROCKS = floatArrayOf(-0.78f, 0.12f, 0.6f, 0.18f, -0.1f, 0.3f)
    private const val NODE_GLYPH = 0.62f

    /** 描画のたびに作り直さず使い回す Path（メインスレッドの描画だけが使う）。 */
    private val scratch = Path()

    fun nodeColor(t: ResourceType): Color =
        when (t) {
            ResourceType.FOOD -> Color(0xFFE6D84F)
            ResourceType.WOOD -> Color(0xFF8B5A2B)
            ResourceType.STONE -> Color(0xFFD0D0D0)
            ResourceType.GOLD -> Color(0xFFFFC107)
        }

    fun kindColor(k: MarchKind): Color =
        when (k) {
            MarchKind.GATHER -> Color(0xFF66BB6A)
            MarchKind.ATTACK -> Color(0xFFEF5350)
            MarchKind.SCOUT -> Color(0xFF29B6F6)
            MarchKind.RALLY -> Color(0xFFAB47BC)
        }

    /** 行軍の軌跡の色（トレイルのスキンがあればその色、無ければ種別の色）。 */
    fun trailColor(
        k: MarchKind,
        theme: String?,
    ): Color =
        when (theme) {
            "flame", "dragon" -> Color(0xFFFF7043)
            "ice" -> Color(0xFF80DEEA)
            "sakura" -> Color(0xFFF48FB1)
            else -> kindColor(k)
        }

    fun territoryColor(theme: String?): Color? =
        when (theme) {
            "ice" -> Color(0xFF9FD8FF)
            "gold" -> Color(0xFFFFD54F)
            "sakura" -> Color(0xFFFF9EC0)
            "dragon" -> Color(0xFFE03A4E)
            "spring" -> Color(0xFFA5D6A7)
            "autumn" -> Color(0xFFFFB74D)
            "winter" -> Color(0xFFB3E5FC)
            else -> null
        }

    /** 領土の輪（自城のまわり）と、テーマごとの粒（雪・金のきらめき・花びら）。 */
    fun DrawScope.drawTerritory(
        center: Offset,
        tile: Float,
        theme: String?,
        seconds: Float,
    ) {
        val c = territoryColor(theme) ?: return
        val r = tile * 6f
        drawCircle(c.copy(alpha = 0.08f), r, center)
        drawCircle(c.copy(alpha = 0.08f), r * 0.8f, center)
        drawCircle(c.copy(alpha = 0.08f), r * 0.55f, center)
        drawCircle(c.copy(alpha = 0.6f), r * 0.98f, center, style = TERRITORY_STROKE)
        if (tile < LOD_FAR_DP * density) return
        repeat(14) { i ->
            val a = i * 2.399f + seconds * (0.15f + (i % 3) * 0.05f)
            val d = r * (0.25f + 0.7f * ((i * 37) % 100) / 100f)
            val p = Offset(center.x + cos(a) * d, center.y + sin(a) * d)
            when (theme) {
                "sakura" -> drawCircle(Color(0xCCFFC1D3), tile * 0.07f, p + Offset(0f, (seconds * 14f + i * 11f) % 12f))
                "ice", "winter" ->
                    drawCircle(
                        Color(0xCCFFFFFF),
                        tile * 0.05f,
                        p + Offset(0f, (seconds * 10f + i * 7f) % 10f),
                    )
                "spring" -> drawCircle(Color(0xCCFFD1DC), tile * 0.06f, p + Offset(0f, (seconds * 12f + i * 9f) % 12f))
                "autumn" -> drawCircle(Color(0xCCE0792A), tile * 0.06f, p + Offset(0f, (seconds * 13f + i * 5f) % 12f))
                // 竜: 火の粉が昇る
                "dragon" -> drawCircle(Color(0xCCFF8A2A), tile * 0.05f, p - Offset(0f, (seconds * 16f + i * 9f) % 14f))
                else -> drawCircle(Color(0xCCFFE082), tile * 0.05f * (0.6f + 0.4f * sin(seconds * 3f + i)), p)
            }
        }
    }

    /**
     * 霧のビットマップを焼く（1 タイル = 1px、城の Lv ごとに 1 回）。見える範囲の外ほど濃く、境目はなだらか。
     * 描くときは拡大（双一次補間）で貼るので、毎フレームのタイルごとの矩形描画が要らない。
     */
    fun bakeFog(
        world: WorldMap,
        center: Pair<Int, Int>,
        radiusTiles: Float,
    ): ImageBitmap {
        val n = world.size
        val pixels = IntArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val d = hypot((x - center.first).toFloat(), (y - center.second).toFloat())
                val a = MapArt.smooth((d - radiusTiles) / FOG_SOFT_TILES) * FOG_MAX_ALPHA
                pixels[y * n + x] = FOG.copy(alpha = a).toArgb()
            }
        }
        return Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /** 焼いた霧を、見えているタイル範囲だけ貼る。 */
    fun DrawScope.drawFog(
        fog: ImageBitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        tile: Float,
        offX: Float,
        offY: Float,
    ) {
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w <= 0 || h <= 0) return
        drawImage(
            fog,
            srcOffset = IntOffset(x0, y0),
            srcSize = IntSize(w, h),
            dstOffset = IntOffset((offX + x0 * tile).roundToInt(), (offY + y0 * tile).roundToInt()),
            dstSize = IntSize((w * tile).roundToInt(), (h * tile).roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }

    /** 昼夜の色味（全面に薄く重ねる）。 */
    fun DrawScope.drawDayNight(
        mode: DayNight,
        hourOfDay: Int,
    ) {
        val tint =
            when (mode) {
                DayNight.DAY -> null
                DayNight.NIGHT -> Color(0xFF0B1B4D).copy(alpha = NIGHT_ALPHA)
                DayNight.AUTO ->
                    when (hourOfDay) {
                        in 5..6, in 17..18 -> Color(0xFFFF9A4D).copy(alpha = DUSK_ALPHA)
                        in 7..16 -> null
                        else -> Color(0xFF0B1B4D).copy(alpha = NIGHT_ALPHA)
                    }
            }
        if (tint != null) drawRect(tint, Offset.Zero, size)
    }

    /**
     * 自城の足元から広がって消える輪（自分の城がひと目で分かる）。[cx] [gy] は城の足元の中心。
     * 地面に寝た楕円を 2 つ、半周期ずらして広げる。
     */
    fun DrawScope.drawCastlePulse(
        cx: Float,
        gy: Float,
        tile: Float,
        color: Color,
        seconds: Float,
    ) {
        if (pulseDensity != density) {
            pulseDensity = density
            pulseStroke = Stroke(width = PULSE_STROKE_DP * density)
        }
        // 足元のやわらかい明るみ（ゆっくり息をする）
        val breath = 0.5f + 0.5f * sin(seconds * PULSE_RATE * 2f * PI.toFloat())
        val glow = tile * (PULSE_FROM + 0.25f) * 2f
        soft(LifeArt.shared.puff, cx, gy, glow * 2f, glow * 2f * PULSE_FLAT, 0.16f + 0.12f * breath, pulseTint(color))
        for (k in 0 until PULSE_RINGS) {
            val f = fract(seconds * PULSE_RATE + k / PULSE_RINGS.toFloat())
            val rx = tile * (PULSE_FROM + PULSE_GROW * f)
            val ry = rx * PULSE_FLAT
            drawOval(
                color.copy(alpha = (1f - f) * (1f - f) * PULSE_ALPHA),
                Offset(cx - rx, gy - ry),
                Size(rx * 2f, ry * 2f),
                style = pulseStroke,
            )
        }
    }

    private var pulseTintColor = Color.Unspecified
    private var pulseTintFilter: ColorFilter? = null

    /** 輪の色の ColorFilter（色が変わったときだけ作り直す）。 */
    private fun pulseTint(color: Color): ColorFilter? {
        if (color != pulseTintColor) {
            pulseTintColor = color
            pulseTintFilter = ColorFilter.tint(color)
        }
        return pulseTintFilter
    }

    /**
     * 川の流れの光。川の折れ線 [river]（タイル座標の x, y の並び。1 行に 1 点）に沿って、下流へ流れる短い白い筋を描く。
     * 見えている行だけを回す。
     */
    fun DrawScope.drawRiverShimmer(
        river: FloatArray,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
        y0: Int,
        y1: Int,
    ) {
        if (tile < LOD_FAR_DP * density) return
        val last = river.size / 2 - 2
        for (i in max(0, y0)..min(last, y1 + 2)) {
            val ax = river[i * 2]
            val ay = river[i * 2 + 1]
            val dx = river[i * 2 + 2] - ax
            val dy = river[i * 2 + 3] - ay
            val len = hypot(dx, dy).coerceAtLeast(MIN_LEN)
            for (k in 0 until RIVER_GLINTS) {
                val f = fract(seconds * RIVER_FLOW * (0.7f + 0.6f * hash01(i, k)) + hash01(i, k + 7))
                val lane = (hash01(i, k + 13) - 0.5f) * RIVER_LANE
                val px = offX + (ax + dx * f + lane) * tile
                val py = offY + (ay + dy * f) * tile
                if (px < -tile || px > size.width + tile) continue
                val tw = 0.5f + 0.5f * sin(seconds * 2.1f + i * 1.7f + k)
                val half = tile * RIVER_STREAK
                drawLine(
                    Color.White.copy(alpha = sin(f * PI.toFloat()) * (0.2f + 0.5f * tw)),
                    Offset(px - dx / len * half, py - dy / len * half),
                    Offset(px + dx / len * half, py + dy / len * half),
                    strokeWidth = tile * RIVER_STREAK_WIDTH,
                    cap = StrokeCap.Round,
                )
            }
        }
    }

    /** 湖や海（地形の水タイル）のきらめき。見えている水タイルのどこかが、数秒に 1 度、短く光る。 */
    fun DrawScope.drawWaterGlints(
        world: WorldMap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
    ) {
        if (tile < LOD_FAR_DP * density) return
        val art = LifeArt.shared
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (world.terrainAt(x, y) != Terrain.WATER) continue
                val c = seconds * WATER_RATE + hash01(x, y) * WATER_SPREAD
                val cycle = c.toInt()
                val f = c - cycle
                if (f > WATER_ON) continue
                val b = sin(f / WATER_ON * PI.toFloat())
                val px = offX + (x + 0.15f + 0.7f * hash01(cycle + x, y)) * tile
                val py = offY + (y + 0.15f + 0.7f * hash01(cycle + y, x + 31)) * tile
                val s = tile * (0.16f + 0.2f * b)
                soft(art.spark, px, py, s, s, b * 0.9f, blend = BlendMode.Plus)
            }
        }
    }

    /**
     * 雲とその影。地図より少し速く流れ（視差）、風で右下へ動き続ける。影は地面に落ちるので、雲の右下にずらして暗く重ねる。
     * ズームが近いと出さない（拠点を隠さない）。
     */
    fun DrawScope.drawClouds(
        offX: Float,
        offY: Float,
        seconds: Float,
        tile: Float,
    ) {
        if (tile > CLOUD_MAX_TILE_DP * density) return
        val art = LifeArt.shared
        for (i in 0 until CLOUDS) {
            val speed = (CLOUD_SPEED_DP + (i % 3) * CLOUD_SPEED_STEP_DP) * density
            val w = tile * (CLOUD_TILES + (i % 3) * 1.3f)
            val h = w * 0.5f
            val wrapX = size.width + w * 2f
            val wrapY = size.height + h * 2f
            val cx = wrap(hash01(i, 71) * wrapX + seconds * speed + offX * CLOUD_PARALLAX, wrapX) - w * 0.5f
            val driftY = seconds * speed * CLOUD_SLANT + offY * CLOUD_PARALLAX
            val cy = wrap(hash01(i, 72) * wrapY + driftY, wrapY) - h * 0.5f
            soft(art.cloud, cx + tile * 0.6f, cy + tile * 1.2f, w, h, CLOUD_SHADOW_ALPHA, LifeArt.SHADOW_TINT)
            soft(art.cloud, cx, cy, w, h, CLOUD_ALPHA)
        }
    }

    /** [v] を 0 以上 [m] 未満に折り返す。 */
    private fun wrap(
        v: Float,
        m: Float,
    ): Float = ((v % m) + m) % m

    /**
     * ときどき地図の上を横切る鳥の群れと、その影（画面に対して飛ぶ。[BIRD_PERIOD] 秒ごとに 1 群れ、向きと高さは回ごとに変わる）。
     */
    fun DrawScope.drawBirds(seconds: Float) {
        val c = seconds / BIRD_PERIOD
        val cycle = c.toInt()
        val t = (c - cycle) * BIRD_PERIOD
        if (t > BIRD_FLIGHT) return
        val dir = if (hash01(cycle, 81) < 0.5f) 1f else -1f
        val span = BIRD_SPAN_DP * density
        val margin = span * BIRD_MARGIN
        val u = t / BIRD_FLIGHT
        val reach = (size.width + margin * 2f) * u
        val x0 = if (dir > 0f) reach - margin else size.width + margin - reach
        val slope = (hash01(cycle, 83) - 0.5f) * BIRD_SLOPE
        val y0 = size.height * (0.2f + 0.5f * hash01(cycle, 82) + (u - 0.5f) * slope)
        val n = BIRD_MIN + (hash01(cycle, 84) * (BIRD_MAX - BIRD_MIN + 1)).toInt().coerceAtMost(BIRD_MAX - BIRD_MIN)
        for (k in 0 until n) {
            val row = (k + 1) / 2
            val sideways = if (k % 2 == 0) 1f else -1f
            val x = x0 - dir * row * span * 1.5f
            val y = y0 + sideways * row * span * 0.8f
            val flap = sin(seconds * BIRD_FLAP + k * 1.3f)
            bird(x + span * 2.2f, y + span * 5f, span, flap, dir, shadow = true)
            bird(x, y + sin(seconds * 1.7f + k) * span * 0.15f, span, flap, dir)
        }
    }

    /**
     * 拠点（城・同盟の城・NPC 城・砦・聖地）。自城は城のスキンの色。[tiers] は拠点ごとの見た目の段階（[structureTier]）。
     * 旗は風にはためく。
     */
    fun DrawScope.drawStructures(
        world: WorldMap,
        tile: Float,
        offX: Float,
        offY: Float,
        castleTheme: String?,
        tiers: IntArray,
        sprites: SpriteCache?,
        seconds: Float,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val near = sprites != null && tile >= SPRITE_MIN_DP * density
        val structures = world.structures
        for (index in structures.indices) {
            val st = structures[index]
            if (st.x !in x0..x1 || st.y !in y0..y1) continue
            val tier = tiers.getOrElse(index) { 1 }
            if (near && drawStructureSprite(st, tile, offX, offY, castleTheme, tier, sprites!!, seconds)) continue
            val color =
                when (st.type) {
                    StructureType.PLAYER_CASTLE -> territoryColor(castleTheme) ?: CASTLE
                    StructureType.ALLY_CASTLE -> ALLY
                    StructureType.NPC_CASTLE -> NPC
                    StructureType.BARBARIAN_FORT -> FORT
                    StructureType.HOLY_LAND -> HOLY
                }
            val x = offX + st.x * tile
            val y = offY + st.y * tile
            val inset = tile * 0.1f
            // 影
            drawOval(Color(0x40000000), Offset(x + inset, y + tile * 0.78f), Size(tile - inset * 2, tile * 0.22f))
            // 本体＋小塔 3 つ＋旗
            val bw = tile - inset * 2
            val bh = tile * 0.5f
            val bx = x + inset
            val by = y + tile * 0.4f
            drawRect(color, Offset(bx, by), Size(bw, bh))
            drawRect(Color(0x33000000), Offset(bx + bw * 0.5f, by), Size(bw * 0.5f, bh))
            for (k in 0..2) {
                val tx = bx + (bw - tile * 0.18f) * k / 2f
                drawRect(color, Offset(tx, by - tile * 0.22f), Size(tile * 0.18f, tile * 0.34f))
                drawRect(
                    Color.Black,
                    Offset(tx, by - tile * 0.22f),
                    Size(tile * 0.18f, tile * 0.34f),
                    style = THIN_STROKE,
                )
            }
            drawRect(Color.Black, Offset(bx, by), Size(bw, bh), style = ICON_STROKE)
            drawRect(Color(0xFF3A2A18), Offset(bx + bw * 0.4f, by + bh * 0.4f), Size(bw * 0.2f, bh * 0.6f))
            if (tile >= LOD_FAR_DP * density) {
                drawLine(Color.Black, Offset(bx + bw / 2, by - tile * 0.22f), Offset(bx + bw / 2, by - tile * 0.5f), 2f)
                flag(
                    bx + bw / 2,
                    by - tile * 0.5f,
                    tile * 0.26f,
                    tile * 0.17f,
                    if (st.type == StructureType.PLAYER_CASTLE) Color(0xFFD32F2F) else color,
                    seconds,
                    st.x * 1.3f + st.y * 0.7f,
                    pennant = true,
                )
            }
        }
    }

    /** 拠点のスプライト（城内と同じアイソメの絵）。読み込み前なら false（簡易アイコンで描く）。 */
    private fun DrawScope.drawStructureSprite(
        st: Structure,
        tile: Float,
        offX: Float,
        offY: Float,
        castleTheme: String?,
        tier: Int,
        sprites: SpriteCache,
        seconds: Float,
    ): Boolean {
        val id =
            when (st.type) {
                StructureType.PLAYER_CASTLE ->
                    if (castleTheme != null) {
                        SpriteIds.castleSkin(
                            castleTheme,
                            tier,
                        )
                    } else {
                        SpriteIds.building(BuildingType.CASTLE, tier)
                    }
                StructureType.ALLY_CASTLE, StructureType.NPC_CASTLE -> SpriteIds.building(BuildingType.CASTLE, tier)
                StructureType.BARBARIAN_FORT -> SpriteIds.building(BuildingType.WATCHTOWER, tier)
                StructureType.HOLY_LAND -> SpriteIds.TEMPLE
            }
        val sp = sprites.get(id) ?: return false
        val ring =
            when (st.type) {
                StructureType.PLAYER_CASTLE -> territoryColor(castleTheme) ?: CASTLE
                StructureType.ALLY_CASTLE -> ALLY
                StructureType.NPC_CASTLE -> NPC
                StructureType.BARBARIAN_FORT -> FORT
                StructureType.HOLY_LAND -> HOLY
            }
        val cx = offX + (st.x + 0.5f) * tile
        val gy = offY + (st.y + 0.5f) * tile + tile * GROUND_DROP
        // 足元の輪（持ち主が分かる色）と影
        drawOval(ring.copy(alpha = 0.45f), Offset(cx - tile * 0.8f, gy - tile * 0.24f), Size(tile * 1.6f, tile * 0.48f))
        drawOval(Color(0x40000000), Offset(cx - tile * 0.85f, gy - tile * 0.2f), Size(tile * 1.7f, tile * 0.4f))
        val scale = tile * (if (st.type == StructureType.HOLY_LAND) HOLY_SPAN else CASTLE_SPAN) / FOOTPRINT_VP
        drawSprite(sp, cx, gy, scale)
        // 持ち主ごとの旗（自城=金、同盟=青、NPC=赤、敵の砦=紫）。聖地には立てない。旗は風にはためく。
        // 竿は絵のいちばん高いところ（絵から測る）に立てるので、絵を差し替えても宙に浮かない
        if (st.type != StructureType.HOLY_LAND) {
            val peak = peakOf(sp)
            val px = cx + peak[0] * scale
            val top = gy - (peak[1] - FLAG_SINK_VP) * scale
            val pole = tile * FLAG_POLE
            drawLine(Color(0xFF3A2A18), Offset(px, top), Offset(px, top - pole), strokeWidth = 2f)
            flag(
                px + 1f,
                top - pole,
                tile * FLAG_WIDTH,
                tile * FLAG_DROOP * 2f,
                flagColor(st.type),
                seconds,
                st.x * 1.3f + st.y * 0.7f,
                pennant = true,
            )
            drawCircle(Color(0xFFF2CB55), tile * 0.035f, Offset(px, top - pole))
        }
        return true
    }

    /** スプライトごとの「てっぺん」（足元の原点から見た横のずれと高さ。viewport 単位）。絵が捨てられたら一緒に消える。 */
    private val peaks = java.util.WeakHashMap<Sprite, FloatArray>()

    /**
     * 絵 [sp] のいちばん高い不透明な点を探す（中央の帯だけを上から見ていく。スプライトごとに 1 度だけ）。
     * 段階ごとの高さの表を持たずに、どの絵でも旗竿を屋根の先に立てられる。
     */
    private fun peakOf(sp: Sprite): FloatArray {
        peaks[sp]?.let { return it }
        val bitmap = sp.image.asAndroidBitmap()
        val k = bitmap.width / sp.vw
        val from = ((sp.ax - PEAK_BAND_VP) * k).toInt().coerceIn(0, bitmap.width - 1)
        val until = ((sp.ax + PEAK_BAND_VP) * k).toInt().coerceIn(from + 1, bitmap.width)
        val row = IntArray(until - from)
        var found = floatArrayOf(0f, sp.ay * PEAK_FALLBACK)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, row.size, from, y, row.size, 1)
            var sum = 0
            var n = 0
            for (i in row.indices) {
                if (row[i] ushr ALPHA_SHIFT > PEAK_ALPHA) {
                    sum += i
                    n++
                }
            }
            if (n > 0) {
                found = floatArrayOf((from + sum / n.toFloat()) / k - sp.ax, sp.ay - y / k)
                break
            }
        }
        peaks[sp] = found
        return found
    }

    private fun flagColor(type: StructureType): Color =
        when (type) {
            StructureType.PLAYER_CASTLE -> Color(0xFFFFC107)
            StructureType.ALLY_CASTLE -> Color(0xFF1E88E5)
            StructureType.NPC_CASTLE -> Color(0xFFC62828)
            StructureType.BARBARIAN_FORT -> Color(0xFF6A1B9A)
            StructureType.HOLY_LAND -> Color.White
        }

    /**
     * 拠点の城の段階（1..5）。自城は [castleTier]、同盟はペルソナ / メンバーの城 Lv（データ）、NPC は戦力の順位、
     * 砦は砦の Lv から決める。
     */
    fun structureTier(
        st: Structure,
        world: WorldMap,
        s: GameState,
        data: GameData,
        castleTier: Int,
    ): Int =
        when (st.type) {
            StructureType.PLAYER_CASTLE -> castleTier
            StructureType.ALLY_CASTLE -> tierOfLv(allyLv(st, s, data))
            StructureType.NPC_CASTLE -> {
                val npcs = world.structures.filter { it.type == StructureType.NPC_CASTLE }
                val rank = npcs.count { it.power < st.power }
                (1 + rank * 5 / npcs.size.coerceAtLeast(1)).coerceIn(1, 5)
            }
            StructureType.BARBARIAN_FORT -> ((st.level - 1) / 2 + 1).coerceIn(1, 5)
            StructureType.HOLY_LAND -> 5
        }

    /** すべての拠点の段階（[WorldMap.structures] と同じ並び）。状態が変わったときに 1 度だけ求める。 */
    fun structureTiers(
        world: WorldMap,
        s: GameState,
        data: GameData,
        castleTier: Int,
    ): IntArray = IntArray(world.structures.size) { structureTier(world.structures[it], world, s, data, castleTier) }

    /** 折れ線（タイル座標）を x, y の並びの配列にする（描画で確保なしに読める形）。 */
    fun flatten(line: List<Offset>): FloatArray {
        val out = FloatArray(line.size * 2)
        for (i in line.indices) {
            out[i * 2] = line[i].x
            out[i * 2 + 1] = line[i].y
        }
        return out
    }

    /** 自城の脈打つ輪の色（城のスキンの色。無ければ金）。 */
    fun pulseColor(castleTheme: String?): Color = territoryColor(castleTheme) ?: PULSE_GOLD

    private fun tierOfLv(lv: Int): Int = ((lv - 1) / TIER_LV_SPAN + 1).coerceIn(1, 5)

    private fun allyLv(
        st: Structure,
        s: GameState,
        data: GameData,
    ): Int {
        val slot = st.tag.substringAfterLast('_').toIntOrNull() ?: return DEFAULT_ALLY_LV
        val personas = data.personas
        return if (st.isPersona) {
            val days = (s.nowEpochSec - s.alliance.startEpochSec).coerceAtLeast(0) / SECONDS_PER_DAY
            personas.personas.getOrNull(slot)?.let { Personas.castleLv(it, days) } ?: DEFAULT_ALLY_LV
        } else {
            personas.fillerMembers.getOrNull(slot)?.castleLv ?: DEFAULT_ALLY_LV
        }
    }

    private fun DrawScope.drawSprite(
        sp: Sprite,
        cx: Float,
        groundY: Float,
        scale: Float,
    ) {
        drawImage(
            sp.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sp.image.width, sp.image.height),
            dstOffset = IntOffset((cx - sp.ax * scale).roundToInt(), (groundY - sp.ay * scale).roundToInt()),
            dstSize =
                IntSize(
                    (sp.vw * scale).roundToInt().coerceAtLeast(1),
                    (sp.vh * scale).roundToInt().coerceAtLeast(1),
                ),
            filterQuality = FilterQuality.Medium,
        )
    }

    /** 資源ノード: 脈打つ光＋絵文字（遠いズームでは光だけ）。 */
    fun DrawScope.drawNodes(
        s: GameState,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
        glyphs: GlyphBitmaps,
        sprites: SpriteCache?,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val near = sprites != null && tile >= SPRITE_MIN_DP * density
        for (n in s.map.nodes) {
            if (n.x !in x0..x1 || n.y !in y0..y1) continue
            val c = Offset(offX + (n.x + 0.5f) * tile, offY + (n.y + 0.5f) * tile)
            val pulse = 0.85f + 0.15f * sin(seconds * 2.4f + n.x * 0.7f + n.y)
            val color = nodeColor(n.type)
            if (n.type == ResourceType.STONE) drawHill(c, tile)
            val r = tile * 0.55f * pulse
            // 光は重ねた半透明の円（毎フレーム Brush を作らない）
            drawCircle(color.copy(alpha = 0.16f), r, c)
            drawCircle(color.copy(alpha = 0.22f), r * 0.72f, c)
            drawCircle(color.copy(alpha = 0.3f), r * 0.45f, c)
            // 近いズームは、資源ごとの建物のスプライト（畑・材木置き場・採石場・金鉱。ノードの Lv で段階が上がる）
            val nodeSprite = if (near) sprites!!.get(nodeSpriteId(n.type, n.level)) else null
            if (nodeSprite != null) {
                drawSprite(nodeSprite, c.x, c.y + tile * NODE_DROP, tile * NODE_SPAN / FOOTPRINT_VP)
                continue
            }
            drawCircle(color, tile * 0.2f, c)
            drawCircle(Color.Black.copy(alpha = 0.5f), tile * 0.2f, c, style = THIN_STROKE)
            if (tile >= LOD_FAR_DP * density) {
                val side = (tile * NODE_GLYPH).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = glyphs.get(n.type.glyph()),
                    dstOffset = IntOffset((c.x - side / 2f).roundToInt(), (c.y - side / 2f).roundToInt()),
                    dstSize = IntSize(side, side),
                )
            }
        }
    }

    /** 先読みするスプライト（資源地の建物の全段階・テント・神殿・拠点の城と砦）。 */
    fun spriteIdsFor(tiers: Int): List<Int> {
        val ids = ArrayList<Int>()
        for (t in 1..tiers) {
            for (b in listOf(BuildingType.FARM, BuildingType.SAWMILL, BuildingType.QUARRY, BuildingType.GOLD_MINE)) {
                ids += SpriteIds.building(b, t)
            }
            ids += SpriteIds.building(BuildingType.CASTLE, t)
        }
        for (t in 1..tiers) ids += SpriteIds.building(BuildingType.WATCHTOWER, t)
        ids += SpriteIds.TENT
        ids += SpriteIds.TEMPLE
        return ids
    }

    private fun nodeSpriteId(
        type: ResourceType,
        level: Int,
    ): Int {
        val tier = ((level - 1) / 2 + 1).coerceIn(1, 5)
        val b =
            when (type) {
                ResourceType.FOOD -> BuildingType.FARM
                ResourceType.WOOD -> BuildingType.SAWMILL
                ResourceType.STONE -> BuildingType.QUARRY
                ResourceType.GOLD -> BuildingType.GOLD_MINE
            }
        return SpriteIds.building(b, tier)
    }

    /** 石のノードのまわりの丘: 草色のこんもりした盛り上がりと小さな岩（地面のキャッシュには入れず、ノードと一緒に描く）。 */
    private fun DrawScope.drawHill(
        c: Offset,
        tile: Float,
    ) {
        if (tile < LOD_FAR_DP * density * 0.5f) return
        drawOval(Color(0x33000000), Offset(c.x - tile * 0.95f, c.y + tile * 0.05f), Size(tile * 1.9f, tile * 0.7f))
        drawOval(HILL_DARK, Offset(c.x - tile * 0.9f, c.y - tile * 0.45f), Size(tile * 1.8f, tile * 0.95f))
        drawOval(HILL_LIGHT, Offset(c.x - tile * 0.7f, c.y - tile * 0.52f), Size(tile * 1.35f, tile * 0.7f))
        for (k in HILL_ROCKS.indices step 2) {
            drawOval(
                Color(0xFF8E8B82),
                Offset(c.x + tile * HILL_ROCKS[k], c.y + tile * HILL_ROCKS[k + 1]),
                Size(tile * 0.2f, tile * 0.13f),
            )
        }
    }

    /** 野蛮人の野営地: テントと、ゆらめく焚き火（暖かいにじみ・炎・はぜる火の粉・細い煙）。 */
    fun DrawScope.drawCamps(
        s: GameState,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
        sprites: SpriteCache?,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val near = sprites != null && tile >= SPRITE_MIN_DP * density
        val art = LifeArt.shared
        for (b in s.map.barbarians) {
            if (b.x !in x0..x1 || b.y !in y0..y1) continue
            val tentSprite = if (near) sprites!!.get(SpriteIds.TENT) else null
            val x = offX + b.x * tile
            val y = offY + b.y * tile
            drawOval(Color(0x40000000), Offset(x + tile * 0.1f, y + tile * 0.75f), Size(tile * 0.8f, tile * 0.2f))
            if (tentSprite != null) {
                drawSprite(tentSprite, x + tile * 0.42f, y + tile * 0.82f, tile * TENT_SPAN / TENT_FOOTPRINT_VP)
            } else {
                val tent = scratch
                tent.rewind()
                tent.moveTo(x + tile * 0.12f, y + tile * 0.82f)
                tent.lineTo(x + tile * 0.4f, y + tile * 0.3f)
                tent.lineTo(x + tile * 0.68f, y + tile * 0.82f)
                tent.close()
                drawPath(tent, Color(0xFF8B3A2E))
                drawPath(tent, Color.Black, style = THIN_STROKE)
            }
            // 焚き火（炎の大きさと揺れは時間で変える。野営地ごとに位相をずらす）
            val fx = x + tile * 0.78f
            val fy = y + tile * 0.8f
            val p = b.x * 1.7f + b.y * 0.9f
            val flick = LifeDraw.flicker(seconds, p)
            val glow = tile * FIRE_GLOW * (0.85f + 0.3f * flick)
            soft(art.glow, fx, fy - tile * 0.12f, glow * 2f, glow * 2f, 0.62f * flick, blend = BlendMode.Plus)
            if (tile < LOD_FAR_DP * density) continue
            // 細い煙
            for (k in 0 until FIRE_SMOKE) {
                val ph = fract(seconds * 0.32f + k / FIRE_SMOKE.toFloat() + p)
                val r = tile * (0.05f + 0.12f * ph)
                soft(
                    art.puff,
                    fx + tile * (0.22f * ph * ph + 0.03f * sin(ph * 6f + k)),
                    fy - tile * (0.3f + 0.55f * ph),
                    r * 2f,
                    r * 2f,
                    (ph / 0.2f).coerceAtMost(1f) * (1f - ph) * 0.4f,
                )
            }
            drawLine(
                Color(0xFF4A3526),
                Offset(fx - tile * 0.08f, fy + tile * 0.01f),
                Offset(fx + tile * 0.08f, fy - tile * 0.02f),
                strokeWidth = tile * 0.035f,
                cap = StrokeCap.Round,
            )
            flame(fx, fy, tile * FIRE_HEIGHT, flick, sin(seconds * 6f + p) * tile * 0.02f)
            // はぜる火の粉
            for (k in 0 until FIRE_SPARKS) {
                val c = seconds * 1.3f + k * 0.37f + p
                val f = c - floorInt(c)
                val dx = (hash01(floorInt(c), k + b.x) - 0.5f) * 0.3f
                drawCircle(
                    Color(0xFFFFC85A).copy(alpha = 1f - f),
                    tile * 0.018f,
                    Offset(fx + tile * dx * f, fy - tile * (0.15f + 0.4f * f)),
                )
            }
        }
    }

    private fun floorInt(v: Float): Int = if (v >= 0f) v.toInt() else v.toInt() - 1

    /**
     * 行軍: 自城から目標への線、歩く 3 人の隊列（先頭は旗持ち）、後ろに舞う土ぼこり。位置は [track] がなめらかに補間する。
     * 遠いズームでは、色のついた点だけにする。
     */
    fun DrawScope.drawMarches(
        s: GameState,
        data: GameData,
        track: MarchTrack,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
        trailTheme: String?,
    ) {
        val castleX = offX + (data.mapRules.center.x + 0.5f) * tile
        val castleY = offY + (data.mapRules.center.y + 0.5f) * tile
        val art = LifeArt.shared
        val detail = tile >= LOD_FAR_DP * density
        val marches = s.marches
        for (index in marches.indices) {
            val m = marches[index]
            val color = trailColor(m.kind, trailTheme)
            val targetX = offX + (m.target.x + 0.5f) * tile
            val targetY = offY + (m.target.y + 0.5f) * tile
            drawLine(color.copy(alpha = 0.35f), Offset(castleX, castleY), Offset(targetX, targetY), strokeWidth = 3f)
            val hx = offX + (track.x(index) + 0.5f) * tile
            val hy = offY + (track.y(index) + 0.5f) * tile
            // 進む向き: 往路は目標へ、復路は城へ。止まっている（待機・作業中）ときは向きだけ保ち、土ぼこりは出さない
            val moving = m.phase == MarchPhase.OUTBOUND || m.phase == MarchPhase.RETURNING
            val goalX = if (m.phase == MarchPhase.RETURNING) castleX else targetX
            val goalY = if (m.phase == MarchPhase.RETURNING) castleY else targetY
            val aimX = if (moving) goalX - hx else targetX - castleX
            val aimY = if (moving) goalY - hy else targetY - castleY
            val aim = hypot(aimX, aimY).coerceAtLeast(1f)
            val dirX = aimX / aim
            val dirY = aimY / aim
            if (!detail) {
                drawCircle(Color.White, tile * 0.1f + 2f, Offset(hx, hy))
                drawCircle(color, tile * 0.1f, Offset(hx, hy))
                continue
            }
            // 土ぼこり（進行方向の後ろへ流れ、広がりながら薄れる）
            if (moving) {
                val tint = dustTint(trailTheme)
                for (i in 0 until MARCH_DUST) {
                    val u = fract(seconds * 0.9f + i / MARCH_DUST.toFloat() + index * 0.37f)
                    val back = tile * (0.2f + u * 0.95f)
                    val r = tile * (0.08f + 0.18f * u)
                    soft(
                        art.puff,
                        hx - dirX * back + sin(u * 9f + i) * tile * 0.04f,
                        hy - dirY * back - tile * 0.14f * u,
                        r * 2f,
                        r * 2f,
                        (u / 0.15f).coerceAtMost(1f) * (1f - u) * 0.8f,
                        tint,
                    )
                }
            }
            // 隊の足元の輪（種別の色。地図の上で見つけやすい）
            drawOval(
                color.copy(alpha = 0.3f),
                Offset(hx - tile * 0.4f, hy - tile * 0.15f),
                Size(tile * 0.8f, tile * 0.34f),
            )
            drawOval(
                Color.White.copy(alpha = 0.75f),
                Offset(hx - tile * 0.4f, hy - tile * 0.15f),
                Size(tile * 0.8f, tile * 0.34f),
                style = THIN_STROKE,
            )
            // 隊列（旗持ち＋兵 2 人）。奥の兵から描く
            val u = tile * MARCH_FIGURE
            val faceX = if (dirX >= 0f) 1f else -1f
            val away = dirY < -0.2f
            val stride = seconds * MARCH_STRIDE + index * 1.3f
            val look = MARCH_LOOKS[m.kind.ordinal % MARCH_LOOKS.size]
            val backX = -dirX * tile * 0.2f
            val backY = -dirY * tile * 0.2f * 0.6f
            val sideX = -dirY * tile * 0.17f
            val sideY = dirX * tile * 0.17f * 0.6f
            val leaderFirst = backY > 0f
            if (leaderFirst) marchLeader(hx, hy, u, look, stride, moving, faceX, away, color, seconds, index)
            val firstSide = if (sideY > 0f) -1f else 1f
            figure(
                hx + backX + sideX * firstSide,
                hy + backY + sideY * firstSide,
                u,
                look,
                stride + PI.toFloat(),
                moving,
                faceX,
                away,
                LifeDraw.GEAR_SPEAR,
            )
            figure(
                hx + backX - sideX * firstSide,
                hy + backY - sideY * firstSide,
                u,
                look,
                stride + PI.toFloat() * 0.5f,
                moving,
                faceX,
                away,
                LifeDraw.GEAR_SPEAR,
            )
            if (!leaderFirst) marchLeader(hx, hy, u, look, stride, moving, faceX, away, color, seconds, index)
        }
    }

    /** 隊の先頭の旗持ち（歩く人と、背の高い旗）。 */
    private fun DrawScope.marchLeader(
        x: Float,
        y: Float,
        u: Float,
        look: FigureLook,
        stride: Float,
        moving: Boolean,
        faceX: Float,
        away: Boolean,
        color: Color,
        seconds: Float,
        index: Int,
    ) {
        figure(x, y, u, look, stride, moving, faceX, away)
        val px = x - faceX * 2.2f * u
        val top = y - MARCH_POLE * u
        drawLine(
            Color(0xFF3A2A18),
            Offset(px, y - 2f * u),
            Offset(px, top),
            strokeWidth = 0.8f * u,
            cap = StrokeCap.Round,
        )
        flag(px + 0.3f * u, top + 0.4f * u, 7.5f * u, 5f * u, color, seconds, index * 2.1f, swallow = true)
        drawCircle(Color(0xFFF2CB55), 0.7f * u, Offset(px, top))
    }

    private fun dustTint(theme: String?): ColorFilter =
        when (theme) {
            "flame", "dragon" -> LifeArt.EMBER_TINT
            "ice" -> LifeArt.ICE_TINT
            "sakura" -> LifeArt.PETAL_TINT
            else -> LifeArt.DUST_TINT
        }

    /** これより小さい（dp）と、飾り・粒・ラベルを省く（密度に依らず見た目の大きさで決める）。 */
    const val LOD_FAR_DP = 8f

    /** これ以上（dp）大きいとき、拠点・資源地・野営地をスプライトで描く（それより遠いと簡易アイコン）。 */
    const val SPRITE_MIN_DP = 14f
    private const val FOOTPRINT_VP = 112f
    private const val TIER_LV_SPAN = 5
    private const val DEFAULT_ALLY_LV = 8
    private const val SECONDS_PER_DAY = 86_400L
    private const val FLAG_POLE = 0.42f
    private const val FLAG_WIDTH = 0.3f
    private const val FLAG_DROOP = 0.1f

    /** 旗竿を屋根の先に少し沈める量と、てっぺんを探す中央の帯の半幅（viewport 単位）。 */
    private const val FLAG_SINK_VP = 2f
    private const val PEAK_BAND_VP = 14f
    private const val PEAK_ALPHA = 128
    private const val PEAK_FALLBACK = 0.8f
    private const val ALPHA_SHIFT = 24
    private const val CASTLE_SPAN = 1.5f
    private const val HOLY_SPAN = 1.8f
    private const val NODE_SPAN = 0.95f
    private const val NODE_DROP = 0.3f
    private const val GROUND_DROP = 0.3f
    private const val TENT_SPAN = 1.0f
    private const val TENT_FOOTPRINT_VP = 36f
    private const val FOG_SOFT_TILES = 5f
    private const val FOG_MAX_ALPHA = 0.7f
    private const val NIGHT_ALPHA = 0.28f
    private const val DUSK_ALPHA = 0.14f
    private const val CLOUD_PARALLAX = 1.3f
    private const val CLOUD_MAX_TILE_DP = 44f

    /** 雲: 数・流れる速さ（dp / 秒）・大きさ（タイル）・濃さ。 */
    private const val CLOUDS = 6
    private const val CLOUD_SPEED_DP = 9f
    private const val CLOUD_SPEED_STEP_DP = 3f
    private const val CLOUD_SLANT = 0.24f
    private const val CLOUD_TILES = 5.4f
    private const val CLOUD_ALPHA = 0.3f
    private const val CLOUD_SHADOW_ALPHA = 0.14f

    /** 自城の脈打つ輪: 数・1 秒あたりの周回・半径（タイル）・つぶれ・濃さ・線の太さ。 */
    private const val PULSE_RINGS = 2
    private const val PULSE_RATE = 0.45f
    private const val PULSE_FROM = 0.75f
    private const val PULSE_GROW = 1.25f
    private const val PULSE_FLAT = 0.42f
    private const val PULSE_ALPHA = 0.95f
    private const val PULSE_STROKE_DP = 2.2f
    private var pulseStroke = Stroke(1f)
    private var pulseDensity = 0f

    /** 川の光: 1 行あたりの筋の数・流れる速さ・川幅の中で散らす幅（タイル）・筋の長さと太さ（タイル）。 */
    private const val RIVER_GLINTS = 2
    private const val RIVER_FLOW = 0.4f
    private const val RIVER_LANE = 0.5f
    private const val RIVER_STREAK = 0.1f
    private const val RIVER_STREAK_WIDTH = 0.035f
    private const val MIN_LEN = 0.001f
    private const val WATER_RATE = 0.4f
    private const val WATER_SPREAD = 7f
    private const val WATER_ON = 0.3f

    /** 焚き火: にじみの半径・炎の高さ（タイル）・煙の玉と火の粉の数。 */
    private const val FIRE_GLOW = 0.34f
    private const val FIRE_HEIGHT = 0.3f
    private const val FIRE_SMOKE = 3
    private const val FIRE_SPARKS = 2

    /** 行軍: 兵の大きさ（タイルに対する 1 単位。背は約 0.37 タイル）・足の速さ・旗竿の高さ・土ぼこりの数。 */
    private const val MARCH_FIGURE = 0.03f
    private const val MARCH_STRIDE = 9f
    private const val MARCH_POLE = 17f
    private const val MARCH_DUST = 5
    private val MARCH_LOOKS =
        arrayOf(
            FigureLook(Color(0xFF5FAE62), Color(0xFF3A3F36), Color(0xFFC8CED8)),
            FigureLook(Color(0xFFD9534F), Color(0xFF41343A), Color(0xFFC8CED8)),
            FigureLook(Color(0xFF3FA7DC), Color(0xFF34404C), Color(0xFFC8CED8)),
            FigureLook(Color(0xFF9C4FB5), Color(0xFF3C3448), Color(0xFFC8CED8)),
        )

    /** 鳥: 何秒ごとに 1 群れ・横切るのにかかる秒・羽の幅（dp）・羽ばたきの速さ。 */
    private const val BIRD_PERIOD = 31f
    private const val BIRD_FLIGHT = 15f
    private const val BIRD_SPAN_DP = 9f
    private const val BIRD_MARGIN = 8f
    private const val BIRD_SLOPE = 0.4f
    private const val BIRD_MIN = 3
    private const val BIRD_MAX = 5
    private const val BIRD_FLAP = 9f
    private val TERRITORY_STROKE = Stroke(width = 2.5f)
    private val THIN_STROKE = Stroke(1.5f)
    private val ICON_STROKE = Stroke(2f)
}
