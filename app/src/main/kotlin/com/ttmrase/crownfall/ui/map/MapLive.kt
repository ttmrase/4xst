package com.ttmrase.crownfall.ui.map

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchPhase
import com.ttmrase.crownfall.core.march.MarchViews
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.ui.territory.Sprite
import com.ttmrase.crownfall.ui.territory.SpriteCache
import com.ttmrase.crownfall.ui.territory.SpriteIds
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
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
    private val HILL_DARK = Color(0xFF5B8A3F)
    private val HILL_LIGHT = Color(0xFF7BA650)
    private val HILL_ROCKS = listOf(-0.78f to 0.12f, 0.6f to 0.18f, -0.1f to 0.3f)
    private val GLYPH_STYLE = TextStyle(fontSize = 14.sp)

    /** 描画のたびに作り直さず使い回す Path（メインスレッドの描画だけが使う）。 */
    private val scratch = Path()
    private val scratch2 = Path()

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
        drawCircle(c.copy(alpha = 0.6f), r * 0.98f, center, style = Stroke(width = 2.5f))
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

    /** 雲の視差（地図より少し速く動かす）。ズームが近いと出さない。 */
    fun DrawScope.drawClouds(
        offX: Float,
        offY: Float,
        seconds: Float,
        tile: Float,
    ) {
        if (tile > CLOUD_MAX_TILE_DP * density) return
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
        castleTier: Int,
        sprites: SpriteCache?,
        visible: (Int, Int) -> Boolean,
    ) {
        val near = sprites != null && tile >= SPRITE_MIN_DP * density
        for (st in world.structures) {
            if (!visible(st.x, st.y)) continue
            if (near && drawStructureSprite(st, tile, offX, offY, castleTheme, castleTier, sprites!!)) continue
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
            if (tile >= LOD_FAR_DP * density) {
                drawLine(Color.Black, Offset(bx + bw / 2, by - tile * 0.22f), Offset(bx + bw / 2, by - tile * 0.5f), 2f)
                val flag = scratch
                flag.rewind()
                flag.moveTo(bx + bw / 2, by - tile * 0.5f)
                flag.lineTo(bx + bw / 2 + tile * 0.22f, by - tile * 0.42f)
                flag.lineTo(bx + bw / 2, by - tile * 0.34f)
                flag.close()
                drawPath(flag, if (st.type == StructureType.PLAYER_CASTLE) Color(0xFFD32F2F) else color)
            }
        }
    }

    /** 拠点のスプライト（城内と同じアイソメの絵）。読み込み前なら false（簡易アイコンで描く）。 */
    private fun DrawScope.drawStructureSprite(
        st: com.ttmrase.crownfall.core.map.Structure,
        tile: Float,
        offX: Float,
        offY: Float,
        castleTheme: String?,
        castleTier: Int,
        sprites: SpriteCache,
    ): Boolean {
        val id =
            when (st.type) {
                StructureType.PLAYER_CASTLE ->
                    if (castleTheme != null) {
                        SpriteIds.castleSkin(
                            castleTheme,
                            castleTier,
                        )
                    } else {
                        SpriteIds.building(BuildingType.CASTLE, castleTier)
                    }
                StructureType.ALLY_CASTLE -> SpriteIds.building(BuildingType.CASTLE, ALLY_TIER)
                StructureType.NPC_CASTLE ->
                    SpriteIds.building(
                        BuildingType.CASTLE,
                        NPC_TIER_MIN + (st.x * 7 + st.y * 13) % 3,
                    )
                StructureType.BARBARIAN_FORT -> SpriteIds.building(BuildingType.WATCHTOWER, FORT_TIER)
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
        drawOval(ring.copy(alpha = 0.45f), Offset(cx - tile * 1.0f, gy - tile * 0.28f), Size(tile * 2.0f, tile * 0.56f))
        drawOval(Color(0x40000000), Offset(cx - tile * 0.85f, gy - tile * 0.2f), Size(tile * 1.7f, tile * 0.4f))
        val scale = tile * (if (st.type == StructureType.HOLY_LAND) HOLY_SPAN else CASTLE_SPAN) / FOOTPRINT_VP
        drawSprite(sp, cx, gy, scale)
        return true
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
        glyph: (ResourceType) -> TextLayoutResult?,
        sprites: SpriteCache?,
        visible: (Int, Int) -> Boolean,
    ) {
        val near = sprites != null && tile >= SPRITE_MIN_DP * density
        for (n in s.map.nodes) {
            if (!visible(n.x, n.y)) continue
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
            drawCircle(Color.Black.copy(alpha = 0.5f), tile * 0.2f, c, style = Stroke(1.5f))
            if (tile >= LOD_FAR_DP * density) {
                glyph(n.type)?.let {
                    drawText(it, topLeft = Offset(c.x - it.size.width / 2f, c.y - it.size.height / 2f - tile * 0.02f))
                }
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
        ids += SpriteIds.building(BuildingType.WATCHTOWER, FORT_TIER)
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
        for ((dx, dy) in HILL_ROCKS) {
            drawOval(Color(0xFF8E8B82), Offset(c.x + tile * dx, c.y + tile * dy), Size(tile * 0.2f, tile * 0.13f))
        }
    }

    /** 野蛮人の野営地: テントと、ゆらめく焚き火。 */
    fun DrawScope.drawCamps(
        s: GameState,
        tile: Float,
        offX: Float,
        offY: Float,
        seconds: Float,
        sprites: SpriteCache?,
        visible: (Int, Int) -> Boolean,
    ) {
        val near = sprites != null && tile >= SPRITE_MIN_DP * density
        for (b in s.map.barbarians) {
            if (!visible(b.x, b.y)) continue
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
                drawPath(tent, Color.Black, style = Stroke(1.5f))
            }
            // 焚き火（炎の大きさと揺れは時間で変える）
            val fx = x + tile * 0.78f
            val fy = y + tile * 0.78f
            val flick = 0.8f + 0.25f * sin(seconds * 9f + b.x * 1.7f) + 0.1f * sin(seconds * 17f + b.y)
            drawCircle(Color(0x55FF8A3D), tile * 0.3f * flick, Offset(fx, fy - tile * 0.12f))
            val flame = scratch2
            flame.rewind()
            flame.moveTo(fx - tile * 0.07f, fy)
            flame.quadraticTo(
                fx - tile * 0.05f,
                fy - tile * 0.2f * flick,
                fx + sin(seconds * 6f) * tile * 0.03f,
                fy - tile * 0.3f * flick,
            )
            flame.quadraticTo(fx + tile * 0.07f, fy - tile * 0.15f, fx + tile * 0.07f, fy)
            flame.close()
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
            // 進む向き: 往路は目標へ、復路は城へ。止まっている（待機・作業中）ときは向きだけ保ち、土ぼこりは出さない
            val moving = m.phase == MarchPhase.OUTBOUND || m.phase == MarchPhase.RETURNING
            val goal = if (m.phase == MarchPhase.RETURNING) castle else target
            val aim = if (moving) goal - head else target - castle
            val dir =
                aim.let { v ->
                    val l = hypot(v.x, v.y).coerceAtLeast(1f)
                    Offset(v.x / l, v.y / l)
                }
            // 土ぼこり（進行方向の後ろに、時間差で薄れる粒）
            if (moving && tile >= LOD_FAR_DP * density * 0.5f) {
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

    /** これより小さい（dp）と、飾り・粒・ラベルを省く（密度に依らず見た目の大きさで決める）。 */
    const val LOD_FAR_DP = 8f

    /** これ以上（dp）大きいとき、拠点・資源地・野営地をスプライトで描く（それより遠いと簡易アイコン）。 */
    const val SPRITE_MIN_DP = 14f
    private const val FOOTPRINT_VP = 112f
    private const val CASTLE_SPAN = 1.9f
    private const val HOLY_SPAN = 2.2f
    private const val NODE_SPAN = 1.15f
    private const val NODE_DROP = 0.3f
    private const val GROUND_DROP = 0.3f
    private const val TENT_SPAN = 1.0f
    private const val TENT_FOOTPRINT_VP = 36f
    private const val ALLY_TIER = 3
    private const val NPC_TIER_MIN = 2
    private const val FORT_TIER = 3
    private const val FOG_SOFT_TILES = 5f
    private const val FOG_MAX_ALPHA = 0.7f
    private const val NIGHT_ALPHA = 0.28f
    private const val DUSK_ALPHA = 0.14f
    private const val CLOUD_SPAN = 400f
    private const val CLOUD_PARALLAX = 1.3f
    private const val CLOUD_MAX_TILE_DP = 44f

    @Suppress("unused")
    private val TWO_PI = (2 * PI).toFloat()
}
