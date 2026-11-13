package com.ttmrase.crownfall.ui.territory

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.ui.territory.LifeDraw.bird
import com.ttmrase.crownfall.ui.territory.LifeDraw.figure
import com.ttmrase.crownfall.ui.territory.LifeDraw.flag
import com.ttmrase.crownfall.ui.territory.LifeDraw.flame
import com.ttmrase.crownfall.ui.territory.LifeDraw.fract
import com.ttmrase.crownfall.ui.territory.LifeDraw.hash01
import com.ttmrase.crownfall.ui.territory.LifeDraw.pole
import com.ttmrase.crownfall.ui.territory.LifeDraw.soft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 領地ビューの「生活感」の層: 通りを歩く住民と衛兵、はためく旗、建物の煙・たいまつ・きらめき、川と噴水の光、
 * 雲の影、鳥、森の縁の蛍や落ち葉。すべて見た目だけで、時刻（秒）と配置の種から決まる（GameState は読むだけ）。
 * 1 フレームの値は [begin] で受け取り、インスタンスと配列は使い回す（描画のたびに確保しない）。
 * 建物の絵の上の位置（煙突・たいまつ）は、スプライトの作図（tools/artgen。足元 4 x 4 のグリッドと高さ）に合わせた値で、
 * 絵を差し替えても大きくずれないよう、にじみと立ち上がりの薄い玉で描く。
 */
internal class TerritoryLife {
    private lateinit var scope: DrawScope
    private lateinit var scene: TerritoryScene
    private val art = LifeArt.shared
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f
    private var seconds = 0f
    private var animate = true
    private var castleLevel = 1
    private var band = 1
    private var gust = 0.5f
    private var moteMode = MOTES_DEFAULT
    private var winter = false

    private val pose = WalkerPose()
    private var itemRef = IntArray(0)
    private var itemGx = FloatArray(0)
    private var itemGy = FloatArray(0)
    private var itemDirX = FloatArray(0)
    private var itemDirY = FloatArray(0)
    private var itemStride = FloatArray(0)
    private var itemFlags = IntArray(0)
    private var order = IntArray(0)
    private var count = 0

    /** 1 フレームの始まり。[seconds] は飾りの時計、[animate] が false なら静止画（鳥は出さない）。 */
    fun begin(
        scope: DrawScope,
        scene: TerritoryScene,
        sc: Float,
        ox: Float,
        oy: Float,
        seconds: Float,
        animate: Boolean,
        season: String?,
        castleLevel: Int,
        band: Int,
    ) {
        this.scope = scope
        this.scene = scene
        this.sc = sc
        this.ox = ox
        this.oy = oy
        this.seconds = seconds
        this.animate = animate
        this.castleLevel = castleLevel
        this.band = band
        winter = season == "winter" || season == "ice"
        moteMode =
            when (season) {
                "autumn" -> MOTES_AUTUMN
                "spring", "sakura" -> MOTES_PETALS
                "winter", "ice" -> MOTES_SNOW
                "gold" -> MOTES_GOLD
                "dragon" -> MOTES_EMBERS
                else -> MOTES_DEFAULT
            }
        // 風の強さ（0..1）。旗・煙・木の揺れが同じ風でそろって強まる
        gust = 0.5f + 0.3f * sin(seconds * 0.23f) + 0.2f * sin(seconds * 0.61f + 1.3f)
    }

    private fun sx(
        gx: Float,
        gy: Float,
    ): Float = ox + PlotLayout.wx(gx, gy) * sc

    private fun sy(
        gx: Float,
        gy: Float,
    ): Float = oy + PlotLayout.wy(gx, gy) * sc

    private fun offScreen(
        x: Float,
        y: Float,
        margin: Float,
    ): Boolean = x < -margin || x > scope.size.width + margin || y < -margin || y > scope.size.height + margin

    // ---- 住民と旗（建物と一緒に奥から手前へ並べる）------------------------------------

    /**
     * いま歩いている住民と旗竿を数え、奥から手前の順に並べる。返り値は個数。
     * 行き先の区画が未建設（[levels] が 0 以下）か、森に覆われている（[locks] が非 null）住民は出さない。
     */
    fun prepare(
        levels: IntArray,
        locks: Array<String?>,
    ): Int {
        val walkers = scene.walkers
        val room = walkers.size + MAX_BANNERS
        if (itemRef.size < room) {
            itemRef = IntArray(room)
            itemGx = FloatArray(room)
            itemGy = FloatArray(room)
            itemDirX = FloatArray(room)
            itemDirY = FloatArray(room)
            itemStride = FloatArray(room)
            itemFlags = IntArray(room)
            order = IntArray(room)
        }
        count = 0
        for (idx in walkers.indices) {
            val w = walkers[idx]
            if (castleLevel < w.minLevel || castleLevel > w.maxLevel) continue
            if (!open(w.fromSlot, levels, locks) || !open(w.toSlot, levels, locks)) continue
            w.poseAt(seconds, pose)
            itemDirX[count] = pose.dirX
            itemDirY[count] = pose.dirY
            itemStride[count] = pose.stride
            itemFlags[count] = (if (pose.moving) FLAG_MOVING else 0) or (if (pose.outbound) FLAG_OUTBOUND else 0)
            add(idx, pose.gx, pose.gy)
        }
        val castle = scene.castle
        if (castle != null) {
            add(BANNER_CASTLE_LEFT, castle.gx - CASTLE_BANNER, castle.gy + CASTLE_BANNER)
            add(BANNER_CASTLE_RIGHT, castle.gx + CASTLE_BANNER, castle.gy - CASTLE_BANNER)
        }
        val hi = PlotLayout.MID + PlotLayout.WALL_HALF[band - 1]
        add(BANNER_GATE_LEFT, hi - GATE_BANNER_ALONG, hi - GATE_BANNER_INSIDE)
        add(BANNER_GATE_RIGHT, hi - GATE_BANNER_INSIDE, hi - GATE_BANNER_ALONG)
        return count
    }

    private fun open(
        slot: Int,
        levels: IntArray,
        locks: Array<String?>,
    ): Boolean = slot < 0 || (slot < levels.size && levels[slot] > 0 && locks[slot] == null)

    /** 1 つ足して、奥行きの順に挿入する（数十個なので挿入ソートで足りる）。 */
    private fun add(
        ref: Int,
        gx: Float,
        gy: Float,
    ) {
        val n = count
        itemRef[n] = ref
        itemGx[n] = gx
        itemGy[n] = gy
        val depth = gx + gy
        var k = n
        while (k > 0 && itemGx[order[k - 1]] + itemGy[order[k - 1]] > depth) {
            order[k] = order[k - 1]
            k--
        }
        order[k] = n
        count = n + 1
    }

    /** 奥から [k] 番目のものの奥行き（[Ent.depth] と同じ尺度）。 */
    fun depthAt(k: Int): Float = itemGx[order[k]] + itemGy[order[k]]

    /** 奥から [k] 番目のもの（住民か旗竿）を描く。 */
    fun drawItem(k: Int) {
        val n = order[k]
        val x = sx(itemGx[n], itemGy[n])
        val y = sy(itemGx[n], itemGy[n])
        if (offScreen(x, y, ITEM_MARGIN * sc)) return
        val ref = itemRef[n]
        if (ref < 0) {
            drawBanner(ref, x, y)
            return
        }
        val w = scene.walkers[ref]
        val moving = itemFlags[n] and FLAG_MOVING != 0
        val outbound = itemFlags[n] and FLAG_OUTBOUND != 0
        // グリッドの向きを画面の向きに（x は右下、y は左下へ延びる）
        val faceX = if (itemDirX[n] - itemDirY[n] >= 0f) 1f else -1f
        val away = itemDirX[n] + itemDirY[n] < 0f
        val guard = w.role == WalkerRole.GUARD
        val gear =
            when (w.role) {
                WalkerRole.GUARD -> LifeDraw.GEAR_SPEAR
                WalkerRole.WORKER -> LifeDraw.GEAR_TOOL
                WalkerRole.CARRIER -> if (outbound) LifeDraw.GEAR_SACK else LifeDraw.GEAR_NONE
                WalkerRole.TOWNSFOLK -> LifeDraw.GEAR_NONE
            }
        val look = if (guard) LifeDraw.GUARD_LOOK else LifeDraw.TOWN_LOOKS[w.look % LifeDraw.TOWN_LOOKS.size]
        val u = sc * (if (guard) GUARD_SCALE else FIGURE_SCALE)
        scope.figure(x, y, u, look, itemStride[n] * STRIDE_RATE, moving, faceX, away, gear)
    }

    /** 地面に立つ旗竿と、風にはためく旗（城の両脇は燕尾の大旗、門の内側は三角旗）。 */
    private fun drawBanner(
        ref: Int,
        x: Float,
        y: Float,
    ) {
        val castle = ref == BANNER_CASTLE_LEFT || ref == BANNER_CASTLE_RIGHT
        val height = (if (castle) CASTLE_POLE else GATE_POLE) * sc
        val length = (if (castle) CASTLE_FLAG else GATE_FLAG) * sc * (0.92f + 0.12f * gust)
        val drop = length * (if (castle) 0.62f else 0.5f)
        with(scope) {
            drawOval(SHADOW.copy(alpha = 0.22f), Offset(x - 1.2f * sc, y - 0.8f * sc), Size(5f * sc, 1.8f * sc))
            pole(x, y, height, POLE_WIDTH * sc)
            flag(x + 0.4f * sc, y - height + 0.6f * sc, length, drop, BANNER_RED, seconds, -ref * 1.7f, castle, !castle)
            if (castle) {
                // 旗の中央の金の紋（波に合わせて上下する）
                val arg = seconds * EMBLEM_SPEED - EMBLEM_AT * EMBLEM_WAVE + -ref * 1.7f
                val ey = y - height + 0.6f * sc + sin(arg) * drop * EMBLEM_AMP + drop * 0.52f
                drawCircle(BANNER_GOLD, drop * 0.2f, Offset(x + 0.4f * sc + length * EMBLEM_AT, ey))
            }
        }
    }

    // ---- 木の揺れ ------------------------------------------------------------------

    /** 木の傾き（度）。風の波が森を渡り、突風のときに大きく揺れる。根元を軸に回すので、足元は動かない。 */
    fun treeSway(
        gx: Float,
        gy: Float,
        seed: Float,
    ): Float {
        val wave = sin(seconds * 1.25f + gx * 0.33f + gy * 0.21f + seed * 2f)
        val flutter = sin(seconds * 2.9f + seed * 40f)
        return (wave * SWAY_DEG + flutter * SWAY_FLUTTER) * (0.45f + 0.75f * gust) * (0.7f + 0.6f * seed)
    }

    // ---- 建物の常時の演出 -------------------------------------------------------------

    /** 建物の絵の上の点（足元 4 x 4 のグリッド上の [x] [y]、高さ [z]）の画面座標。 */
    private fun bx(
        ent: Ent,
        x: Float,
        y: Float,
    ): Float = sx(ent.gx + x - HALF_PLOT, ent.gy + y - HALF_PLOT)

    private fun by(
        ent: Ent,
        x: Float,
        y: Float,
        z: Float,
    ): Float = sy(ent.gx + x - HALF_PLOT, ent.gy + y - HALF_PLOT) - z * sc

    /**
     * 建物ごとの常時の演出（[tier] は見た目の段階 1..5）。煙突の煙、たいまつと炉のゆらめき、物見の火、金のきらめき、
     * アップグレードできる建物の「準備できた」きらめき。
     */
    fun buildingIdle(
        ent: Ent,
        type: BuildingType,
        tier: Int,
        ready: Boolean,
    ) {
        when (type) {
            BuildingType.TAVERN -> {
                smoke(bx(ent, 3f, 0.9f), by(ent, 3f, 0.9f, 30f + 4f * tier), ent.seed, false, 1f)
                if (tier >= 3) {
                    torch(bx(ent, 1.55f, 2.8f), by(ent, 1.55f, 2.8f, 12.5f), ent.seed * 9f, 1f)
                    torch(bx(ent, 2.45f, 2.8f), by(ent, 2.45f, 2.8f, 12.5f), ent.seed * 9f + 2f, 1f)
                }
                // 戸口からもれるあかり
                glow(bx(ent, 2f, 2.9f), by(ent, 2f, 2.9f, 5f), DOOR_GLOW * sc, 0.34f, ent.seed * 5f)
            }
            BuildingType.SIEGE_WORKSHOP -> {
                smoke(bx(ent, 0.83f, 0.93f), by(ent, 0.83f, 0.93f, 28f + 3f * tier), ent.seed, true, 1.15f)
                forge(bx(ent, 1.4f, 2.5f), by(ent, 1.4f, 2.5f, 5f), ent.seed)
            }
            BuildingType.BARRACKS -> {
                torch(bx(ent, 1.52f, 2.6f), by(ent, 1.52f, 2.6f, 13.5f), ent.seed * 9f, 1f)
                torch(bx(ent, 2.48f, 2.6f), by(ent, 2.48f, 2.6f, 13.5f), ent.seed * 9f + 2f, 1f)
            }
            BuildingType.WALL -> {
                torch(bx(ent, 1.56f, 3.9f), by(ent, 1.56f, 3.9f, 13f), ent.seed * 9f, 1f)
                torch(bx(ent, 2.64f, 3.9f), by(ent, 2.64f, 3.9f, 13f), ent.seed * 9f + 2f, 1f)
            }
            BuildingType.CASTLE ->
                if (tier == 1) {
                    torch(bx(ent, 1.53f, 3.62f), by(ent, 1.53f, 3.62f, 15.5f), ent.seed * 9f, 1f)
                    torch(bx(ent, 2.47f, 3.62f), by(ent, 2.47f, 3.62f, 15.5f), ent.seed * 9f + 2f, 1f)
                } else {
                    torch(bx(ent, 1.56f, 3.72f), by(ent, 1.56f, 3.72f, 11.5f), ent.seed * 9f, 1f)
                    torch(bx(ent, 2.44f, 3.72f), by(ent, 2.44f, 3.72f, 11.5f), ent.seed * 9f + 2f, 1f)
                }
            BuildingType.ALLIANCE_CENTER ->
                if (tier >= 2) {
                    torch(bx(ent, 1.25f, 3.6f), by(ent, 1.25f, 3.6f, 5f), ent.seed * 9f, BRAZIER)
                    torch(bx(ent, 2.75f, 3.6f), by(ent, 2.75f, 3.6f, 5f), ent.seed * 9f + 2f, BRAZIER)
                }
            BuildingType.WATCHTOWER ->
                if (tier >= 2) {
                    val z = TOWER_HEIGHTS[tier - 1] + 6f
                    torch(bx(ent, 2f, 2f), by(ent, 2f, 2f, z), ent.seed * 9f, BRAZIER)
                }
            BuildingType.SAWMILL -> dust(bx(ent, 2.6f, 3.2f), by(ent, 2.6f, 3.2f, 4f), ent.seed)
            BuildingType.QUARRY -> dust(bx(ent, 2.2f, 2.6f), by(ent, 2.2f, 2.6f, 5f), ent.seed + 0.4f)
            else -> Unit
        }
        if (tier >= GOLD_ROOF_TIER || type == BuildingType.GOLD_MINE && tier >= 3) {
            val roof = roofHeight(type, tier)
            twinkles(ent, if (type == BuildingType.CASTLE) CASTLE_TWINKLES else ROOF_TWINKLES, roof * 0.55f, roof)
        }
        if (ready) readySparkle(ent)
    }

    /** 屋根のてっぺんのおおよその高さ（きらめきを散らす範囲）。 */
    private fun roofHeight(
        type: BuildingType,
        tier: Int,
    ): Float =
        when (type) {
            BuildingType.CASTLE -> CASTLE_HEIGHTS[tier - 1] + 10f
            BuildingType.WATCHTOWER -> TOWER_HEIGHTS[tier - 1]
            BuildingType.GOLD_MINE -> 22f
            else -> 22f + 3f * tier
        }

    /** 町の飾り（家・屋敷の煙突、街灯、噴水）の演出。[ent] が見えているときだけ呼ぶ。 */
    fun decorIdle(ent: Ent) {
        when (ent.sprite) {
            SpriteIds.LAMP -> {
                val pulse = sin(seconds * 1.9f + ent.gx * 1.3f + ent.gy * 0.7f)
                val x = sx(ent.gx, ent.gy)
                val y = sy(ent.gx, ent.gy) - LAMP_HEIGHT * sc
                val r = (LAMP_GLOW + pulse) * sc
                scope.soft(art.glow, x, y, r * 2f, r * 2f, 0.2f + 0.08f * pulse, blend = BlendMode.Plus)
            }
            SpriteIds.FOUNTAIN -> fountain(ent)
            SpriteIds.HOUSE_A ->
                if (ent.seed < HOUSE_SMOKE_SHARE) {
                    val x = sx(ent.gx + 0.47f, ent.gy - 0.38f)
                    smoke(x, sy(ent.gx + 0.47f, ent.gy - 0.38f) - HOUSE_CHIMNEY * sc, ent.seed * 3f, false, 0.8f)
                }
            SpriteIds.MANOR ->
                if (ent.seed < MANOR_SMOKE_SHARE) {
                    val x = sx(ent.gx + 0.57f, ent.gy - 0.43f)
                    smoke(x, sy(ent.gx + 0.57f, ent.gy - 0.43f) - MANOR_CHIMNEY * sc, ent.seed * 3f, false, 0.9f)
                }
            else -> Unit
        }
    }

    /** 煙突の煙。玉は薄く現れて、風下（右）へ流れながら広がって消える。[dark] は工房の濃い煙。 */
    private fun smoke(
        x: Float,
        y: Float,
        seed: Float,
        dark: Boolean,
        big: Float,
    ) {
        if (offScreen(x, y, SMOKE_MARGIN * sc)) return
        val k0 = big * sc
        val thick = if (winter) WINTER_SMOKE else 1f
        for (k in 0 until SMOKE_PUFFS) {
            val ph = fract(seconds * SMOKE_RATE + k / SMOKE_PUFFS.toFloat() + seed)
            val drift = ph * ph * SMOKE_DRIFT * (0.5f + gust) + sin(ph * 5f + k + seed * 9f) * 1.4f
            val r = (SMOKE_R0 + SMOKE_GROW * ph) * k0
            val a = (ph / SMOKE_FADE_IN).coerceAtMost(1f) * (1f - ph) * SMOKE_ALPHA * thick
            scope.soft(
                art.puff,
                x + drift * k0,
                y - ph * SMOKE_RISE * k0,
                r * 2f,
                r * 2f,
                a,
                if (dark) LifeArt.SOOT_TINT else null,
            )
        }
    }

    /** 製材所・採石場の土ぼこり（低く、短く舞う）。 */
    private fun dust(
        x: Float,
        y: Float,
        seed: Float,
    ) {
        if (offScreen(x, y, SMOKE_MARGIN * sc)) return
        for (k in 0 until DUST_PUFFS) {
            val ph = fract(seconds * DUST_RATE + k / DUST_PUFFS.toFloat() + seed)
            val r = (2f + 4f * ph) * sc
            val dx = ((k - 1) * 3.5f + ph * 5f * (0.5f + gust)) * sc
            scope.soft(art.puff, x + dx, y - ph * 7f * sc, r * 2f, r * 2f, (1f - ph) * 0.3f, LifeArt.DUST_TINT)
        }
    }

    private fun glow(
        x: Float,
        y: Float,
        radius: Float,
        alpha: Float,
        p: Float,
    ) {
        val f = LifeDraw.flicker(seconds, p)
        val r = radius * (0.85f + 0.25f * f)
        scope.soft(art.glow, x, y, r * 2f, r * 2f, alpha * f, blend = BlendMode.Plus)
    }

    /** たいまつ / かがり火: 暖かいにじみと、ゆれる炎。[big] は大きさの倍率。 */
    private fun torch(
        x: Float,
        y: Float,
        p: Float,
        big: Float,
    ) {
        if (offScreen(x, y, SMOKE_MARGIN * sc)) return
        val f = LifeDraw.flicker(seconds, p)
        glow(x, y - 1.6f * big * sc, TORCH_GLOW * big * sc, 0.5f, p)
        with(scope) { flame(x, y + 0.6f * big * sc, TORCH_FLAME * big * sc, f, sin(seconds * 7f + p) * 0.35f * sc) }
    }

    /** 炉: 戸口の奥で脈打つ赤いあかりと、はぜて飛ぶ火の粉。 */
    private fun forge(
        x: Float,
        y: Float,
        seed: Float,
    ) {
        if (offScreen(x, y, SMOKE_MARGIN * sc)) return
        val pulse = 0.5f + 0.5f * sin(seconds * 2.1f + seed * 6f)
        glow(x, y, FORGE_GLOW * sc * (0.9f + 0.2f * pulse), 0.42f + 0.25f * pulse, seed * 13f)
        for (k in 0 until FORGE_SPARKS) {
            val c = seconds * SPARK_RATE + k * 0.31f + seed
            val f = c - c.toInt()
            val dir = hash01(c.toInt(), k) - 0.3f
            val px = x + dir * f * 9f * sc
            val py = y - (f * 11f - f * f * 7f) * sc
            scope.drawCircle(EMBER.copy(alpha = 1f - f), (0.9f - 0.4f * f) * sc, Offset(px, py))
        }
    }

    /** 金の屋根のきらめき。数秒に 1 度、屋根のあたりのどこかで小さく光る。 */
    private fun twinkles(
        ent: Ent,
        n: Int,
        zLo: Float,
        zHi: Float,
    ) {
        val salt = (ent.seed * SALT).toInt()
        for (k in 0 until n) {
            val c = seconds * TWINKLE_RATE + k * 0.37f + ent.seed * 7f
            val cycle = c.toInt()
            val f = c - cycle
            if (f > TWINKLE_ON) continue
            val b = sin(f / TWINKLE_ON * PI.toFloat())
            val lx = ROOF_LO + (ROOF_HI - ROOF_LO) * hash01(cycle, salt + k * 31)
            val ly = ROOF_LO + (ROOF_HI - ROOF_LO) * hash01(cycle, salt + k * 31 + 7)
            val z = zLo + (zHi - zLo) * hash01(cycle, salt + k * 31 + 13)
            val s = (5f + 5f * b) * sc
            scope.soft(art.spark, bx(ent, lx, ly), by(ent, lx, ly, z), s, s, b, LifeArt.GOLD_TINT, BlendMode.Plus)
        }
    }

    /** アップグレードできる建物: 金のきらめきが、建物のまわりをゆっくり回りながら昇る。 */
    private fun readySparkle(ent: Ent) {
        for (k in 0 until READY_SPARKS) {
            val f = fract(seconds * READY_RATE + k / READY_SPARKS.toFloat() + ent.seed)
            val ang = seconds * 0.7f + k * (2f * PI.toFloat() / READY_SPARKS) + ent.seed * 6f
            val gx = ent.gx + cos(ang) * READY_RADIUS
            val gy = ent.gy + sin(ang) * READY_RADIUS
            val s = (4f + 4f * sin(f * PI.toFloat())) * sc
            scope.soft(
                art.spark,
                sx(gx, gy),
                sy(gx, gy) - (4f + f * READY_RISE) * sc,
                s,
                s,
                sin(f * PI.toFloat()) * 0.9f,
                LifeArt.GOLD_TINT,
                BlendMode.Plus,
            )
        }
    }

    /** 噴水: 上の水盤から落ちるしぶき、水面のきらめき、てっぺんの霧。 */
    private fun fountain(ent: Ent) {
        val cx = sx(ent.gx, ent.gy)
        val cy = sy(ent.gx, ent.gy)
        with(scope) {
            for (k in 0 until FOUNTAIN_DROPS) {
                val f = fract(seconds * 1.1f + k * 0.37f)
                val ang = (k * 60f + 20f) * DEG
                val r = 0.2f + 0.8f * f
                val z = FOUNTAIN_TOP + 4f * sin(f * PI.toFloat()) - (FOUNTAIN_TOP - FOUNTAIN_BASIN) * f * f
                val x = sx(ent.gx + cos(ang) * r, ent.gy + sin(ang) * r)
                val y = sy(ent.gx + cos(ang) * r, ent.gy + sin(ang) * r) - z * sc
                drawCircle(WATER_LIGHT.copy(alpha = 0.9f - 0.5f * f), (0.75f - 0.25f * f) * sc, Offset(x, y))
            }
            val mist = (3.4f + 0.5f * sin(seconds * 3f)) * sc
            soft(art.puff, cx, cy - (FOUNTAIN_TOP + 3f) * sc, mist * 2f, mist * 2f, 0.3f)
        }
        val salt = (ent.gx * 13f + ent.gy * 7f).toInt()
        for (k in 0 until FOUNTAIN_GLINTS) {
            val c = seconds * 0.9f + k * 0.41f
            val cycle = c.toInt()
            val f = c - cycle
            if (f > GLINT_ON) continue
            val ang = hash01(cycle, salt + k * 17) * 2f * PI.toFloat()
            val x = sx(ent.gx + cos(ang) * 1.05f, ent.gy + sin(ang) * 1.05f)
            val y = sy(ent.gx + cos(ang) * 1.05f, ent.gy + sin(ang) * 1.05f) - FOUNTAIN_BASIN * sc
            val s = (3f + 4f * sin(f / GLINT_ON * PI.toFloat())) * sc
            scope.soft(art.spark, x, y, s, s, sin(f / GLINT_ON * PI.toFloat()), blend = BlendMode.Plus)
        }
    }

    // ---- 水面（川）-------------------------------------------------------------------

    /** 川の流れの光。下流へ流れる短い白い筋と、ときどき強く光る点。川の中心線は [TerritoryScene.riverX]。 */
    fun drawWater() {
        // 流れの向き（グリッドの +y）は、画面では左下
        val fx = -PlotLayout.U * FLOW_NORM
        val fy = PlotLayout.V * FLOW_NORM
        with(scope) {
            for (k in 0 until RIVER_GLINTS) {
                val lane = (hash01(k, 3) - 0.5f) * RIVER_LANE
                val speed = RIVER_FLOW * (0.7f + 0.6f * hash01(k, 5))
                val gy = TerritoryScene.RIVER_FROM + fract(hash01(k, 7) + seconds * speed / RIVER_SPAN) * RIVER_SPAN
                val gx = TerritoryScene.riverX(gy) + lane
                val x = sx(gx, gy)
                val y = sy(gx, gy)
                if (offScreen(x, y, 8f * sc)) continue
                val tw = 0.5f + 0.5f * sin(seconds * 2.3f + k * 1.7f)
                val len = (1.6f + 2.2f * hash01(k, 9)) * sc
                drawLine(
                    Color.White.copy(alpha = 0.2f + 0.5f * tw * tw),
                    Offset(x - fx * len, y - fy * len),
                    Offset(x + fx * len, y + fy * len),
                    strokeWidth = 0.9f * sc,
                    cap = StrokeCap.Round,
                )
            }
        }
        for (k in 0 until RIVER_SPARKS) {
            val c = seconds * 0.6f + k * 0.29f
            val cycle = c.toInt()
            val f = c - cycle
            if (f > GLINT_ON) continue
            val gy = TerritoryScene.RIVER_FROM + hash01(cycle, k * 19 + 1) * RIVER_SPAN
            val gx = TerritoryScene.riverX(gy) + (hash01(cycle, k * 19 + 2) - 0.5f) * RIVER_LANE
            val b = sin(f / GLINT_ON * PI.toFloat())
            val s = (4f + 5f * b) * sc
            scope.soft(art.spark, sx(gx, gy), sy(gx, gy), s, s, b * 0.9f, blend = BlendMode.Plus)
        }
    }

    // ---- 空（雲の影・森の縁の粒・鳥）----------------------------------------------------

    /** 街の上を流れる雲の影。ワールドに固定して風下へ流し、広い範囲で折り返す（カメラを動かしても地面に貼りつく）。 */
    fun drawCloudShadows() {
        for (k in 0 until CLOUDS) {
            val speed = CLOUD_SPEED * (0.75f + 0.5f * hash01(k, 21))
            val wx = (fract(hash01(k, 22) + seconds * speed / CLOUD_WRAP_X) - 0.5f) * CLOUD_WRAP_X
            val wy =
                CLOUD_CENTER_Y +
                    (fract(hash01(k, 23) + seconds * speed * CLOUD_SLANT / CLOUD_WRAP_Y) - 0.5f) * CLOUD_WRAP_Y
            val w = CLOUD_SIZE * (0.8f + 0.6f * hash01(k, 24)) * sc
            scope.soft(art.cloud, ox + wx * sc, oy + wy * sc, w, w * 0.5f, CLOUD_ALPHA, LifeArt.SHADOW_TINT)
        }
    }

    /**
     * 森の縁（城壁と森のあいだ）にただよう粒。領地のテーマで変わる: 標準は蛍と木の葉、秋は落ち葉、春・桜は花びら、
     * 冬・氷は雪面のきらめき、金は金色の蛍、竜は昇る火の粉。画面の上から降る既存の季節の粒（drawSeason）とは別の層で、
     * 森の近くの地面に貼りつくので、重ならない。
     */
    fun drawEdgeMotes() {
        val c = PlotLayout.MID
        val half = PlotLayout.WALL_HALF[band - 1]
        for (k in 0 until EDGE_MOTES) {
            val r = half + EDGE_NEAR + EDGE_DEPTH * hash01(k, 12)
            val p = hash01(k, 11) * 8f * r
            val side = (p / (2f * r)).toInt().coerceIn(0, 3)
            val t = p - side * 2f * r - r
            val gx =
                when (side) {
                    0 -> c + t
                    1 -> c + r
                    2 -> c - t
                    else -> c - r
                }
            val gy =
                when (side) {
                    0 -> c - r
                    1 -> c + t
                    2 -> c + r
                    else -> c - t
                }
            val x = sx(gx, gy)
            val y = sy(gx, gy)
            if (offScreen(x, y, EDGE_MARGIN * sc)) continue
            when (moteMode) {
                MOTES_AUTUMN -> leaf(k, x, y, AUTUMN_LEAVES)
                MOTES_PETALS -> leaf(k, x, y, PETALS)
                MOTES_SNOW -> snowGlint(k, x, y)
                MOTES_GOLD -> firefly(k, x, y, LifeArt.GOLD_TINT, false)
                MOTES_EMBERS -> firefly(k, x, y, LifeArt.EMBER_TINT, true)
                else ->
                    if (k % LEAF_EVERY == 0) {
                        leaf(k, x, y, GREEN_LEAVES)
                    } else {
                        firefly(k, x, y, LifeArt.FIREFLY_TINT, false)
                    }
            }
        }
    }

    /** 蛍: ふわふわとさまよい、ゆっくり明滅する。[rising] なら火の粉のように昇って消える。 */
    private fun firefly(
        k: Int,
        x: Float,
        y: Float,
        tint: ColorFilter,
        rising: Boolean,
    ) {
        val a = k * 2.4f
        val blink = sin(seconds * 1.3f + a).coerceAtLeast(0f)
        val px = x + sin(seconds * 0.5f + a) * 9f * sc
        val up = if (rising) fract(seconds * 0.22f + hash01(k, 31)) * 30f else 7f + 4f * sin(seconds * 0.8f + a * 1.3f)
        val py = y + cos(seconds * 0.37f + a * 0.7f) * 4f * sc - up * sc
        val fade = if (rising) 1f - fract(seconds * 0.22f + hash01(k, 31)) else blink * blink
        val s = (5f + 3f * fade) * sc
        scope.soft(art.glow, px, py, s, s, fade * 0.85f, tint, BlendMode.Plus)
        scope.drawCircle(Color.White.copy(alpha = fade * 0.9f), 0.5f * sc, Offset(px, py))
    }

    /** 木の葉 / 花びら: 梢から風下へ舞い落ちる。回る小さな楕円で、1 枚ごとに色と速さが違う。 */
    private fun leaf(
        k: Int,
        x: Float,
        y: Float,
        colors: Array<Color>,
    ) {
        val f = fract(seconds * LEAF_RATE * (0.8f + 0.4f * hash01(k, 41)) + hash01(k, 42))
        val fall = 1f - f
        val px = x + (f * LEAF_DRIFT * (0.6f + 0.8f * gust) + sin(f * 14f + k) * 2.5f) * sc
        val py = y - LEAF_FROM * fall * fall * sc + f * LEAF_DRIFT * 0.3f * sc
        val alpha = (f / 0.12f).coerceAtMost(1f) * ((1f - f) / 0.18f).coerceAtMost(1f)
        val w = (2.4f + hash01(k, 43)) * sc
        scope.withTransform({ rotate(seconds * 190f + k * 47f, Offset(px, py)) }) {
            drawOval(
                colors[k % colors.size].copy(alpha = alpha * 0.92f),
                Offset(px - w / 2f, py - w * 0.28f),
                Size(w, w * 0.56f),
            )
        }
    }

    /** 雪面のきらめき: 数秒に 1 度、その場で小さく光る。 */
    private fun snowGlint(
        k: Int,
        x: Float,
        y: Float,
    ) {
        val c = seconds * 0.5f + hash01(k, 51) * 4f
        val f = c - c.toInt()
        if (f > GLINT_ON) return
        val b = sin(f / GLINT_ON * PI.toFloat())
        val s = (4f + 4f * b) * sc
        scope.soft(art.spark, x, y, s, s, b * 0.9f, LifeArt.ICE_TINT, BlendMode.Plus)
    }

    /**
     * ときどき街の上を横切る鳥の群れ（V 字の編隊）と、地面に落ちる影。[BIRD_PERIOD] 秒ごとに 1 群れ、
     * 向きと高さは回ごとに変わる（回の番号から決める）。静止画（アニメーション無効）では出さない。
     */
    fun drawBirds() {
        if (!animate) return
        val c = seconds / BIRD_PERIOD
        val cycle = c.toInt()
        val t = (c - cycle) * BIRD_PERIOD
        if (t > BIRD_FLIGHT) return
        val dir = if (hash01(cycle, 61) < 0.5f) 1f else -1f
        val n = BIRD_MIN + (hash01(cycle, 62) * (BIRD_MAX - BIRD_MIN + 1)).toInt().coerceAtMost(BIRD_MAX - BIRD_MIN)
        val wx0 = dir * (t / BIRD_FLIGHT - 0.5f) * BIRD_RANGE
        val wy0 = BIRD_Y_FROM + hash01(cycle, 63) * BIRD_Y_SPAN + wx0 * (hash01(cycle, 64) - 0.5f) * BIRD_SLOPE
        val span = BIRD_SPAN * sc
        with(scope) {
            for (k in 0 until n) {
                // V 字: 先頭の後ろに、左右交互に並ぶ
                val row = (k + 1) / 2
                val sideways = if (k % 2 == 0) 1f else -1f
                val wx = wx0 - dir * row * BIRD_GAP
                val wy = wy0 + sideways * row * BIRD_GAP * 0.55f
                val x = ox + wx * sc
                val y = oy + wy * sc
                if (offScreen(x, y, BIRD_ALTITUDE * sc * 1.5f)) continue
                val flap = sin(seconds * BIRD_FLAP + k * 1.3f)
                bird(x + BIRD_ALTITUDE * 0.35f * sc, y, span, flap, dir, shadow = true)
                bird(x, y - BIRD_ALTITUDE * sc + sin(seconds * 1.7f + k) * 1.5f * sc, span, flap, dir)
            }
        }
    }

    private companion object {
        const val FLAG_MOVING = 1
        const val FLAG_OUTBOUND = 2
        const val BANNER_CASTLE_LEFT = -1
        const val BANNER_CASTLE_RIGHT = -2
        const val BANNER_GATE_LEFT = -3
        const val BANNER_GATE_RIGHT = -4
        const val MAX_BANNERS = 4
        const val MOTES_DEFAULT = 0
        const val MOTES_AUTUMN = 1
        const val MOTES_PETALS = 2
        const val MOTES_SNOW = 3
        const val MOTES_GOLD = 4
        const val MOTES_EMBERS = 5
        const val HALF_PLOT = 2f
        const val DEG = 0.017453292f
        const val SALT = 1000f

        /** 住民の大きさ（背はワールド約 13px。家の戸口より大きめにして、初期ズームでも歩く姿が分かるようにする）。 */
        const val FIGURE_SCALE = 1.05f
        const val GUARD_SCALE = 1.15f

        /** 歩いた距離（グリッド単位）あたりの足の振り（ラジアン）。0.8 単位で 2 歩。 */
        const val STRIDE_RATE = 7.85f
        const val ITEM_MARGIN = 40f

        /** 城の両脇の旗竿の位置（区画の左右の角）と大きさ（ワールド px）。 */
        const val CASTLE_BANNER = 2.25f
        const val CASTLE_POLE = 30f
        const val CASTLE_FLAG = 13f
        const val GATE_BANNER_INSIDE = 1f
        const val GATE_BANNER_ALONG = 2.9f
        const val GATE_POLE = 28f
        const val GATE_FLAG = 12f
        const val POLE_WIDTH = 1.2f
        const val EMBLEM_AT = 0.42f
        const val EMBLEM_SPEED = 5.2f
        const val EMBLEM_WAVE = 7.2f
        const val EMBLEM_AMP = 0.085f
        val BANNER_RED = Color(0xFFC0342C)
        val BANNER_GOLD = Color(0xFFF2CB55)
        val SHADOW = Color(0xFF14102A)

        const val SWAY_DEG = 1.5f
        const val SWAY_FLUTTER = 0.5f

        /** 煙: 玉の数・1 秒あたりの周回・昇る高さ・風下へ流れる幅・半径（ワールド px）。 */
        const val SMOKE_PUFFS = 4
        const val SMOKE_RATE = 0.3f
        const val SMOKE_RISE = 24f
        const val SMOKE_DRIFT = 10f
        const val SMOKE_R0 = 2.2f
        const val SMOKE_GROW = 5.2f
        const val SMOKE_FADE_IN = 0.16f
        const val SMOKE_ALPHA = 0.6f
        const val WINTER_SMOKE = 1.25f
        const val SMOKE_MARGIN = 60f
        const val DUST_PUFFS = 3
        const val DUST_RATE = 0.55f
        const val TORCH_GLOW = 6.5f
        const val TORCH_FLAME = 3.6f
        const val BRAZIER = 1.35f
        const val DOOR_GLOW = 8f
        const val FORGE_GLOW = 10f
        const val FORGE_SPARKS = 4
        const val SPARK_RATE = 1.4f
        val EMBER = Color(0xFFFFB347)
        const val GOLD_ROOF_TIER = 4
        const val ROOF_TWINKLES = 2
        const val CASTLE_TWINKLES = 4
        const val TWINKLE_RATE = 0.42f
        const val TWINKLE_ON = 0.3f
        const val GLINT_ON = 0.35f
        const val ROOF_LO = 1f
        const val ROOF_HI = 3f
        const val READY_SPARKS = 3
        const val READY_RATE = 0.38f
        const val READY_RADIUS = 1.7f
        const val READY_RISE = 34f
        val TOWER_HEIGHTS = floatArrayOf(34f, 44f, 54f, 64f, 76f)
        val CASTLE_HEIGHTS = floatArrayOf(26f, 34f, 44f, 52f, 62f)
        const val LAMP_HEIGHT = 24f
        const val LAMP_GLOW = 8f
        const val HOUSE_SMOKE_SHARE = 0.6f
        const val MANOR_SMOKE_SHARE = 0.5f
        const val HOUSE_CHIMNEY = 23f
        const val MANOR_CHIMNEY = 40f
        const val FOUNTAIN_TOP = 21f
        const val FOUNTAIN_BASIN = 8f
        const val FOUNTAIN_DROPS = 6
        const val FOUNTAIN_GLINTS = 2
        val WATER_LIGHT = Color(0xFFEAF7FF)

        /** 川: 光の筋の数・流れる速さ（グリッド単位 / 秒）・川幅の中で散らす幅・強く光る点の数。 */
        const val RIVER_GLINTS = 40
        const val RIVER_FLOW = 2.2f
        const val RIVER_SPAN = TerritoryScene.RIVER_TO - TerritoryScene.RIVER_FROM
        const val RIVER_LANE = 2.6f
        const val RIVER_SPARKS = 5
        const val FLOW_NORM = 0.0638877f

        /** 雲の影: 数・流れる速さ（ワールド px / 秒）・折り返す範囲・大きさ・濃さ。 */
        const val CLOUDS = 6
        const val CLOUD_SPEED = 13f
        const val CLOUD_WRAP_X = 2000f
        const val CLOUD_WRAP_Y = 900f
        const val CLOUD_CENTER_Y = 60f
        const val CLOUD_SLANT = 0.3f
        const val CLOUD_SIZE = 380f
        const val CLOUD_ALPHA = 0.24f

        /** 森の縁の粒: 数、城壁からの距離（グリッド単位）。 */
        const val EDGE_MOTES = 30
        const val EDGE_NEAR = 1.4f
        const val EDGE_DEPTH = 3.2f
        const val EDGE_MARGIN = 50f
        const val LEAF_EVERY = 3
        const val LEAF_RATE = 0.18f
        const val LEAF_DRIFT = 46f
        const val LEAF_FROM = 26f
        val GREEN_LEAVES = arrayOf(Color(0xFF7FBF4A), Color(0xFFA8D45A), Color(0xFF5E9E3C))
        val AUTUMN_LEAVES = arrayOf(Color(0xFFE0792A), Color(0xFFC8452E), Color(0xFFE8B23A))
        val PETALS = arrayOf(Color(0xFFFFC1D3), Color(0xFFFFE1EA), Color(0xFFF79AB8))

        /** 鳥: 何秒ごとに 1 群れ・横切るのにかかる秒・飛ぶ範囲（ワールド px）・羽の幅・高さ・羽ばたきの速さ。 */
        const val BIRD_PERIOD = 34f
        const val BIRD_FLIGHT = 24f
        const val BIRD_RANGE = 1500f
        const val BIRD_Y_FROM = -90f
        const val BIRD_Y_SPAN = 330f
        const val BIRD_SLOPE = 0.5f
        const val BIRD_MIN = 3
        const val BIRD_MAX = 5
        const val BIRD_GAP = 13f
        const val BIRD_SPAN = 9f
        const val BIRD_ALTITUDE = 58f
        const val BIRD_FLAP = 9f
    }
}
