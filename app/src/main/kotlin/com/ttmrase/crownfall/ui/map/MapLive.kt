package com.ttmrase.crownfall.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchViews
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
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
 * 地面（キャッシュ済みチャンク）の上に毎フレーム描くもの: 領土の輪・霧・拠点・資源の光・野営地の焚き火・行軍（土ぼこり）・
 * 雲の視差・昼夜の色味。ズームが遠いと細部を省く（LOD）。
 */
internal object MapLive {
    private val CASTLE = Color(0xFFE8B730)
    private val ALLY = Color(0xFF4F9DDE)
    private val NPC = Color(0xFFC0392B)
    private val FORT = Color(0xFF6B1F1F)
    private val HOLY = Color(0xFFF5F0DC)
    private val FOG = Color(0xFF0F1A33)
    private val DUST = Color(0xFFD9C7A0)
    private val GLYPH_STYLE = TextStyle(fontSize = 14.sp)

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
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.28f), Color.Transparent), center, r), r, center)
        drawCircle(c.copy(alpha = 0.6f), r * 0.98f, center, style = Stroke(width = 2.5f))
        if (tile < LOD_FAR_PX) return
        repeat(14) { i ->
            val a = i * 2.399f + seconds * (0.15f + (i % 3) * 0.05f)
            val d = r * (0.25f + 0.7f * ((i * 37) % 100) / 100f)
            val p = Offset(center.x + cos(a) * d, center.y + sin(a) * d)
            when (theme) {
                "sakura" -> drawCircle(Color(0xCCFFC1D3), tile * 0.07f, p + Offset(0f, (seconds * 14f + i * 11f) % 12f))
                "ice" -> drawCircle(Color(0xCCFFFFFF), tile * 0.05f, p + Offset(0f, (seconds * 10f + i * 7f) % 10f))
                // 竜: 火の粉が昇る
                "dragon" -> drawCircle(Color(0xCCFF8A2A), tile * 0.05f, p - Offset(0f, (seconds * 16f + i * 9f) % 14f))
                else -> drawCircle(Color(0xCCFFE082), tile * 0.05f * (0.6f + 0.4f * sin(seconds * 3f + i)), p)
            }
        }
    }

    /** 霧: 自城から遠いほど暗くなる（見える範囲は城の Lv で広がる）。 */
    fun DrawScope.drawFog(
        world: WorldMap,
        center: Pair<Int, Int>,
        radiusTiles: Float,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        tile: Float,
        offX: Float,
        offY: Float,
    ) {
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d = hypot((x - center.first).toFloat(), (y - center.second).toFloat())
                val a = MapArt.smooth((d - radiusTiles) / FOG_SOFT_TILES) * FOG_MAX_ALPHA
                if (a > 0.01f) {
                    drawRect(
                        FOG.copy(alpha = a),
                        Offset(offX + x * tile, offY + y * tile),
                        Size(tile + 1f, tile + 1f),
                    )
                }
            }
        }
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

    /** 雲の視差（地図より少し速く動かす）。ズームが近いと出さない。 */
    fun DrawScope.drawClouds(
        offX: Float,
        offY: Float,
        seconds: Float,
        tile: Float,
    ) {
        if (tile > CLOUD_MAX_TILE_PX) return
        val w = size.width + CLOUD_SPAN
        repeat(7) { i ->
            val speed = 6f + (i % 3) * 3f
            val x = ((i * 233f + seconds * speed + offX * CLOUD_PARALLAX) % w + w) % w - CLOUD_SPAN / 2f
            val y = (i * 157f % size.height) + offY * CLOUD_PARALLAX * 0.5f % size.height
            val r = tile * (1.6f + (i % 3) * 0.5f)
            val c = Color(0x33FFFFFF)
            drawOval(c, Offset(x, y), Size(r * 2.4f, r * 0.9f))
            drawOval(c, Offset(x + r * 0.7f, y - r * 0.3f), Size(r * 1.6f, r * 0.9f))
            drawOval(Color(0x14000000), Offset(x + r * 0.2f, y + r * 2.2f), Size(r * 2.2f, r * 0.5f))
        }
    }

    /** 拠点（城・同盟の城・NPC 城・砦・聖地）。自城は城のスキンの色。 */
    fun DrawScope.drawStructures(
        world: WorldMap,
        tile: Float,
        offX: Float,
        offY: Float,
        castleTheme: String?,
        visible: (Int, Int) -> Boolean,
    ) {
        for (st in world.structures) {
            if (!visible(st.x, st.y)) continue
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
                    style = Stroke(1.5f),
                )
            }
            drawRect(Color.Black, Offset(bx, by), Size(bw, bh), style = Stroke(2f))
            drawRect(Color(0xFF3A2A18), Offset(bx + bw * 0.4f, by + bh * 0.4f), Size(bw * 0.2f, bh * 0.6f))
            if (tile >= LOD_FAR_PX) {
                drawLine(Color.Black, Offset(bx + bw / 2, by - tile * 0.22f), Offset(bx + bw / 2, by - tile * 0.5f), 2f)
                val flag =
                    Path().apply {
                        moveTo(bx + bw / 2, by - tile * 0.5f)
                        lineTo(bx + bw / 2 + tile * 0.22f, by - tile * 0.42f)
                        lineTo(bx + bw / 2, by - tile * 0.34f)
                        close()
                    }
                drawPath(flag, if (st.type == StructureType.PLAYER_CASTLE) Color(0xFFD32F2F) else color)
            }
        }
    }

    /** 資源ノード: 脈打つ光＋絵文字（遠いズームでは光だけ）。 */
    fun DrawScope.drawNodes(
        s: GameState,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
        glyph: (ResourceType) -> TextLayoutResult?,
        visible: (Int, Int) -> Boolean,
    ) {
        for (n in s.map.nodes) {
            if (!visible(n.x, n.y)) continue
            val c = Offset(offX + (n.x + 0.5f) * tile, offY + (n.y + 0.5f) * tile)
            val pulse = 0.85f + 0.15f * sin(seconds * 2.4f + n.x * 0.7f + n.y)
            val color = nodeColor(n.type)
            val r = tile * 0.55f * pulse
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent), c, r), r, c)
            drawCircle(color, tile * 0.2f, c)
            drawCircle(Color.Black.copy(alpha = 0.5f), tile * 0.2f, c, style = Stroke(1.5f))
            if (tile >= LOD_FAR_PX) {
                glyph(n.type)?.let {
                    drawText(it, topLeft = Offset(c.x - it.size.width / 2f, c.y - it.size.height / 2f - tile * 0.02f))
                }
            }
        }
    }

    /** 野蛮人の野営地: テントと、ゆらめく焚き火。 */
    fun DrawScope.drawCamps(
        s: GameState,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
        visible: (Int, Int) -> Boolean,
    ) {
        for (b in s.map.barbarians) {
            if (!visible(b.x, b.y)) continue
            val x = offX + b.x * tile
            val y = offY + b.y * tile
            drawOval(Color(0x40000000), Offset(x + tile * 0.1f, y + tile * 0.75f), Size(tile * 0.8f, tile * 0.2f))
            val tent =
                Path().apply {
                    moveTo(x + tile * 0.12f, y + tile * 0.82f)
                    lineTo(x + tile * 0.4f, y + tile * 0.3f)
                    lineTo(x + tile * 0.68f, y + tile * 0.82f)
                    close()
                }
            drawPath(tent, Color(0xFF8B3A2E))
            drawPath(tent, Color.Black, style = Stroke(1.5f))
            // 焚き火（炎の大きさと揺れは時間で変える）
            val fx = x + tile * 0.78f
            val fy = y + tile * 0.78f
            val flick = 0.8f + 0.25f * sin(seconds * 9f + b.x * 1.7f) + 0.1f * sin(seconds * 17f + b.y)
            drawCircle(Color(0x55FF8A3D), tile * 0.3f * flick, Offset(fx, fy - tile * 0.12f))
            val flame =
                Path().apply {
                    moveTo(fx - tile * 0.07f, fy)
                    quadraticTo(
                        fx - tile * 0.05f,
                        fy - tile * 0.2f * flick,
                        fx + sin(seconds * 6f) * tile * 0.03f,
                        fy - tile * 0.3f * flick,
                    )
                    quadraticTo(fx + tile * 0.07f, fy - tile * 0.15f, fx + tile * 0.07f, fy)
                    close()
                }
            drawPath(flame, Color(0xFFFF8F00))
            drawCircle(Color(0xFFFFEB3B), tile * 0.04f, Offset(fx, fy - tile * 0.07f))
        }
    }

    /** 行軍: 自城から目標への線、3 人の隊列、後ろに舞う土ぼこり。 */
    fun DrawScope.drawMarches(
        s: GameState,
        data: GameData,
        tile: Float,
        offX: Float,
        offY: Float,
        nowSec: Long,
        seconds: Float,
        trailTheme: String?,
    ) {
        val castle =
            Offset(offX + (data.mapRules.center.x + 0.5f) * tile, offY + (data.mapRules.center.y + 0.5f) * tile)
        for (m in s.marches) {
            val color = trailColor(m.kind, trailTheme)
            val target = Offset(offX + (m.target.x + 0.5f) * tile, offY + (m.target.y + 0.5f) * tile)
            drawLine(color.copy(alpha = 0.35f), castle, target, strokeWidth = 3f)
            val (mx, my) = MarchViews.position(m, nowSec, data)
            val head = Offset(offX + (mx + 0.5f) * tile, offY + (my + 0.5f) * tile)
            val dir =
                (target - head).let { v ->
                    val l = hypot(v.x, v.y).coerceAtLeast(1f)
                    Offset(v.x / l, v.y / l)
                }
            // 土ぼこり（進行方向の後ろに、時間差で薄れる粒）
            if (tile >= LOD_FAR_PX * 0.5f) {
                repeat(6) { i ->
                    val u = i / 6f
                    val rise = -tile * 0.12f * u * (1f + sin(seconds * 5f + i))
                    val p = head - dir * (tile * (0.25f + u * 0.9f)) + Offset(0f, rise)
                    drawCircle(
                        (if (trailTheme == null) DUST else color).copy(alpha = 0.5f * (1f - u)),
                        tile * (0.07f + 0.09f * u),
                        p,
                    )
                }
            }
            // 隊列（旗持ち＋兵 2 人）
            val side = Offset(-dir.y, dir.x)
            val bob = sin(seconds * 10f + m.target.x * 0.9f) * tile * 0.025f
            for ((k, o) in listOf(
                Offset.Zero,
                side * (tile * 0.14f) - dir * (tile * 0.12f),
                side * (-tile * 0.14f) - dir * (tile * 0.12f),
            ).withIndex()) {
                val p = head + o + Offset(0f, bob)
                drawCircle(Color.White, tile * 0.1f + 2f, p)
                drawCircle(color, tile * (if (k == 0) 0.1f else 0.08f), p)
            }
            drawLine(Color(0xFF3A2A18), head + Offset(0f, bob), head + Offset(0f, bob - tile * 0.3f), strokeWidth = 2f)
            drawRect(color, head + Offset(0f, bob - tile * 0.3f), Size(tile * 0.18f, tile * 0.1f))
        }
    }

    const val LOD_FAR_PX = 22f
    private const val FOG_SOFT_TILES = 5f
    private const val FOG_MAX_ALPHA = 0.7f
    private const val NIGHT_ALPHA = 0.28f
    private const val DUSK_ALPHA = 0.14f
    private const val CLOUD_SPAN = 400f
    private const val CLOUD_PARALLAX = 1.3f
    private const val CLOUD_MAX_TILE_PX = 120f

    @Suppress("unused")
    private val TWO_PI = (2 * PI).toFloat()
}
