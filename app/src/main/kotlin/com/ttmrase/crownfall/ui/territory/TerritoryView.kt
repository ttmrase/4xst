package com.ttmrase.crownfall.ui.territory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.ui.theme.CrownFonts
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** カメラ（ワールド中心と倍率）。描画の中で読むので、変えると再描画だけが走る（再構図は走らない）。 */
internal class TerritoryCamera {
    var cx by mutableFloatStateOf(0f)
    var cy by mutableFloatStateOf(0f)
    var sc by mutableFloatStateOf(0f)
    var ready = false
}

/** 帯（城 Lv の段階）が変わったときの拡張アニメの状態。 */
private class BandAnim(band: Int) {
    var from = band
    var to = band
    var startMs = -ANIM_DONE_MS
}

private const val ANIM_DONE_MS = 10_000L
private const val BAND_ANIM_MS = 1_800f
private const val POP_MS = 800f
private const val BOUNCE_MS = 260f
private const val MAX_SLOTS = 40
private const val FRAME_MS = 33L

/** 小さな文字列のレイアウトを使い回す（毎フレーム measure しない）。 */
private class TextCache(private val measurer: TextMeasurer) {
    private val map =
        object : LinkedHashMap<String, TextLayoutResult>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TextLayoutResult>?): Boolean =
                size > MAX_ENTRIES
        }

    fun layout(
        text: String,
        style: TextStyle,
    ): TextLayoutResult = map.getOrPut("${style.fontSize.value}|$text") { measurer.measure(text, style) }

    private companion object {
        const val MAX_ENTRIES = 96
    }
}

/**
 * 領地ビュー（T10.3）。城・区画・城壁・森・川を、ズームとパンができるアイソメトリック風の地図に描く。
 * 見た目の層だけを差し替える: スロットとレベルは [state] のまま、建物の見た目は Lv の段階（1–5 / 6–10 / …）で変わる。
 * アニメは描画フェーズの時計 [frame] で動かし、毎秒の state 更新とは分ける。スプライトは [SpriteCache] で
 * 事前にラスタライズする。
 */
@Composable
fun TerritoryView(
    state: GameState,
    clock: State<Long>?,
    tutorialSlotId: Int?,
    onTileClick: (BuildingSlot) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = remember { SpriteCache(context, scope) }
    val measurer = rememberTextMeasurer()
    val texts = remember(measurer) { TextCache(measurer) }
    val cam = remember { TerritoryCamera() }
    val frame = remember { mutableLongStateOf(0L) }
    val currentState by rememberUpdatedState(state)
    val currentClick by rememberUpdatedState(onTileClick)

    val cells =
        remember {
            val perType = HashMap<BuildingType, Int>()
            state.slots.map { slot ->
                val idx = perType.getOrDefault(slot.type, 0)
                perType[slot.type] = idx + 1
                Triple(slot.slotId, PlotLayout.cellOf(slot.type, idx), slot.type)
            }
        }
    val scene = remember { TerritoryScene.build(cells) }
    val frontToBack = remember(scene) { scene.buildingEnts.sortedBy { it.depth } }
    val typeBySlot =
        remember {
            arrayOfNulls<BuildingType>(MAX_SLOTS).also { a -> state.slots.forEach { a[it.slotId] = it.type } }
        }

    val levels = IntArray(MAX_SLOTS)
    state.slots.forEach { levels[it.slotId] = it.level }
    val jobs = arrayOfNulls<TimerJob>(MAX_SLOTS)
    state.timers.forEach { if (it.kind == TimerKind.BUILD) it.slotId?.let { id -> jobs[id] = it } }
    val castleLevel = state.slots.firstOrNull { it.type == BuildingType.CASTLE }?.level ?: 1
    val band = PlotLayout.bandOfCastle(castleLevel)

    val anim = remember { BandAnim(band) }
    val popAt = remember { LongArray(MAX_SLOTS) { -ANIM_DONE_MS } }
    val bounceAt = remember { LongArray(MAX_SLOTS) { -ANIM_DONE_MS } }
    val seenLevels = remember { IntArray(MAX_SLOTS) { -1 } }
    val signLabel = stringResource(R.string.territory_expand_sign, PlotLayout.nextBandCastleLevel(band) ?: 0)
    val levelLabels = remember { HashMap<Int, String>() }
    val lvFormat = stringResource(R.string.tile_level, 0)

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameMillis { t ->
                    if (t - last >= FRAME_MS) {
                        last = t
                        frame.longValue = t
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        cache.preload(
            SpriteIds.ALL_BUILDINGS.filterIndexed {
                    i,
                    _,
                ->
                i % 5 == 0
            } + SpriteIds.SITE,
        )
    }
    LaunchedEffect(band) {
        if (band != anim.to) {
            anim.from = anim.to
            anim.to = band
            anim.startMs = frame.longValue
        }
    }
    LaunchedEffect(levels.contentHashCode()) {
        for (slot in state.slots) {
            val before = seenLevels[slot.slotId]
            if (before >= 0 && slot.level > before) popAt[slot.slotId] = frame.longValue
            seenLevels[slot.slotId] = slot.level
        }
    }

    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewW) {
        if (viewW > 0f && !cam.ready) {
            cam.ready = true
            cam.sc = viewW / INITIAL_SPAN
            cam.cx = 0f
            cam.cy = PlotLayout.wy(PlotLayout.CENTER, PlotLayout.CENTER) + CAMERA_DROP
        }
    }

    Canvas(
        modifier =
            modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged {
                    viewW = it.width.toFloat()
                    viewH = it.height.toFloat()
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val old = cam.sc
                        val target = (old * zoom).coerceIn(size.width / MAX_SPAN, size.width / MIN_SPAN)
                        // 指の下のワールド点を保つ
                        val dx = centroid.x - size.width / 2f
                        val dy = centroid.y - size.height / 2f
                        val wx = cam.cx + dx / old
                        val wy = cam.cy + dy / old
                        cam.sc = target
                        cam.cx = (wx - dx / target - pan.x / target).coerceIn(-PAN_X, PAN_X)
                        cam.cy = (wy - dy / target - pan.y / target).coerceIn(PAN_Y_MIN, PAN_Y_MAX)
                    }
                }
                .pointerInput(scene) {
                    detectTapGestures(
                        onDoubleTap = {
                            cam.sc = size.width / INITIAL_SPAN
                            cam.cx = 0f
                            cam.cy = PlotLayout.wy(PlotLayout.CENTER, PlotLayout.CENTER) + CAMERA_DROP
                        },
                        onTap = { tap ->
                            val sc = cam.sc
                            var best: Ent? = null
                            for (e in scene.buildingEnts) {
                                val sx = size.width / 2f + (PlotLayout.wx(e.gx, e.gy) - cam.cx) * sc
                                val sy = size.height / 2f + (PlotLayout.wy(e.gx, e.gy) - cam.cy) * sc
                                val inside =
                                    abs(tap.x - sx) <= HIT_HALF_W * sc && tap.y >= sy - HIT_UP * sc &&
                                        tap.y <= sy + HIT_DOWN * sc
                                if (inside && (best == null || e.depth > best.depth)) best = e
                            }
                            best?.let { hit ->
                                val slot = currentState.slots.firstOrNull { it.slotId == hit.slotId }
                                if (slot != null) {
                                    bounceAt[slot.slotId] = frame.longValue
                                    currentClick(slot)
                                }
                            }
                        },
                    )
                },
    ) {
        val nowMs = frame.longValue
        val loadTick = cache.version
        if (!cam.ready || loadTick < 0) return@Canvas
        val sc = cam.sc
        val ox = size.width / 2f - cam.cx * sc
        val oy = size.height / 2f - cam.cy * sc
        val nowSec = clock?.value ?: state.nowEpochSec
        val t = ((nowMs - anim.startMs) / BAND_ANIM_MS).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t)
        val painter =
            TerritoryPainter(
                this, cache, texts, scene, sc, ox, oy, nowMs, nowSec, anim.from, anim.to, if (t >= 1f) 1f else e,
                t >= 1f,
            )
        painter.drawGround()
        painter.drawEntities(levels, jobs, typeBySlot, popAt, bounceAt, tutorialSlotId, signLabel)
        painter.drawBadges(frontToBack, levels, jobs, typeBySlot, levelLabels, lvFormat)
        painter.drawFog()
    }
}

private const val INITIAL_SPAN = 470f
private const val MAX_SPAN = 300f
private const val MIN_SPAN = 1000f
private const val CAMERA_DROP = 14f
private const val PAN_X = 620f
private const val PAN_Y_MIN = -230f
private const val PAN_Y_MAX = 430f
private const val HIT_HALF_W = 52f
private const val HIT_UP = 98f
private const val HIT_DOWN = 30f

/** 1 フレーム分の描画。状態は持たず、引数の値だけで描く。 */
private class TerritoryPainter(
    private val scope: DrawScope,
    private val cache: SpriteCache,
    private val texts: TextCache,
    private val scene: TerritoryScene,
    private val sc: Float,
    private val ox: Float,
    private val oy: Float,
    private val nowMs: Long,
    private val nowSec: Long,
    private val fromBand: Int,
    private val toBand: Int,
    private val ease: Float,
    private val settled: Boolean,
) {
    private val seconds = nowMs / 1000f

    private fun sx(
        gx: Float,
        gy: Float,
    ): Float = ox + PlotLayout.wx(gx, gy) * sc

    private fun sy(
        gx: Float,
        gy: Float,
    ): Float = oy + PlotLayout.wy(gx, gy) * sc

    /** 帯 [band] で見えているか（途中のアニメでは 0..1 の濃さ）。 */
    private fun presence(e: Ent): Float {
        val inTo = e.introBand <= toBand && e.goneBand > toBand
        if (settled) return if (inTo) 1f else 0f
        val inFrom = e.introBand <= fromBand && e.goneBand > fromBand
        return when {
            inTo && inFrom -> 1f
            inTo -> ease
            inFrom -> 1f - ease
            else -> 0f
        }
    }

    // ---- 地面 -----------------------------------------------------------------

    fun drawGround() {
        with(scope) {
            drawRect(Color(0xFF1B3524))
            val m = isoMatrix()
            withTransform({ transform(m) }) {
                // 草地（開かれた土地）。帯が進むと広がる
                drawPath(scene.lawn[fromBand - 1], LAWN)
                if (!settled && toBand != fromBand) drawPath(scene.lawn[toBand - 1], LAWN, alpha = ease)
                // 通り
                drawPath(scene.streets[fromBand - 1], streetColor(fromBand))
                if (!settled && toBand != fromBand) {
                    drawPath(
                        scene.streets[toBand - 1],
                        streetColor(toBand),
                        alpha = ease,
                    )
                }
                // 川
                drawPath(scene.river, RIVER_BANK, style = Stroke(5.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(scene.river, RIVER, style = Stroke(3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(
                    scene.river,
                    Color.White.copy(alpha = 0.45f),
                    style =
                        Stroke(
                            0.5f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.6f, 4.4f), -seconds * 2.2f),
                        ),
                )
            }
        }
    }

    private fun streetColor(band: Int): Color = if (band <= 2) STREET_DIRT else STREET_COBBLE

    private fun isoMatrix(): Matrix {
        val m = Matrix()
        m.values[Matrix.ScaleX] = PlotLayout.U * sc
        m.values[Matrix.SkewY] = PlotLayout.V * sc
        m.values[Matrix.SkewX] = -PlotLayout.U * sc
        m.values[Matrix.ScaleY] = PlotLayout.V * sc
        m.values[Matrix.TranslateX] = ox
        m.values[Matrix.TranslateY] = oy
        return m
    }

    // ---- スプライト -------------------------------------------------------------

    private fun drawSprite(
        id: Int,
        gx: Float,
        gy: Float,
        alpha: Float = 1f,
        scale: Float = 1f,
        dy: Float = 0f,
    ) {
        val sp = cache.get(id) ?: return
        val w = sp.vw * sc * scale
        val h = sp.vh * sc * scale
        val left = sx(gx, gy) - sp.ax * sc * scale
        val top = sy(gx, gy) - sp.ay * sc * scale + dy
        if (left > scope.size.width || top > scope.size.height || left + w < 0f || top + h < 0f) return
        scope.drawImage(
            image = sp.image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sp.image.width, sp.image.height),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
            alpha = alpha.coerceIn(0f, 1f),
            filterQuality = FilterQuality.Medium,
        )
    }

    fun drawEntities(
        levels: IntArray,
        jobs: Array<TimerJob?>,
        types: Array<BuildingType?>,
        popAt: LongArray,
        bounceAt: LongArray,
        tutorialSlotId: Int?,
        signLabel: String,
    ) {
        val vils = visibleVillagers()
        var vi = 0
        for (ent in scene.ents) {
            while (vi < vils.size && vils[vi].depth < ent.depth) {
                drawVillager(vils[vi])
                vi++
            }
            // 画面外はすぐ飛ばす
            val px = sx(ent.gx, ent.gy)
            val py = sy(ent.gx, ent.gy)
            if (isOffScreen(px, py)) continue
            if (ent.kind == EntKind.BUILDING) {
                drawBuilding(ent, levels, jobs, types, popAt, bounceAt, tutorialSlotId)
                continue
            }
            val a = presence(ent)
            if (a <= 0.01f) continue
            when (ent.kind) {
                EntKind.TREE ->
                    drawSprite(ent.sprite, ent.gx, ent.gy, a, scale = 0.7f + 0.3f * a, dy = swayTree(ent))
                EntKind.SIGN -> {
                    drawSprite(ent.sprite, ent.gx, ent.gy, a)
                    if (signLabel.isNotEmpty()) drawSignText(ent, signLabel, a)
                }
                else -> drawSprite(ent.sprite, ent.gx, ent.gy, a, scale = 0.88f + 0.12f * a)
            }
        }
        while (vi < vils.size) {
            drawVillager(vils[vi])
            vi++
        }
    }

    private fun isOffScreen(
        px: Float,
        py: Float,
    ): Boolean {
        val m = OFF * sc
        return px < -m || px > scope.size.width + m || py < -m || py > scope.size.height + m
    }

    private fun swayTree(ent: Ent): Float = sin(seconds * 1.1f + ent.seed * 6.28f) * 0.35f * sc

    private fun drawSignText(
        ent: Ent,
        label: String,
        alpha: Float,
    ) {
        val layout = texts.layout(label, SIGN_STYLE)
        val cx = sx(ent.gx, ent.gy)
        val cy = sy(ent.gx, ent.gy) - 30f * sc
        with(scope) {
            drawText(
                layout,
                color = Color(0xFF3A2A18).copy(alpha = alpha),
                topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
            )
        }
    }

    // ---- 建物 -----------------------------------------------------------------

    private fun drawBuilding(
        ent: Ent,
        levels: IntArray,
        jobs: Array<TimerJob?>,
        types: Array<BuildingType?>,
        popAt: LongArray,
        bounceAt: LongArray,
        tutorialSlotId: Int?,
    ) {
        val id = ent.slotId
        val type = types[id] ?: return
        val level = levels[id]
        val job = jobs[id]
        val pop = ((nowMs - popAt[id]) / POP_MS).coerceIn(0f, 1f)
        val bounce = ((nowMs - bounceAt[id]) / BOUNCE_MS).coerceIn(0f, 1f)
        var scale = 1f
        if (pop < 1f) scale += 0.2f * sin(pop * PI.toFloat()) * (1f - pop)
        val dy = if (bounce < 1f) -7f * sc * sin(bounce * PI.toFloat()) else 0f
        if (tutorialSlotId == id) drawTutorialRing(ent)
        if (level <= 0) {
            drawSprite(SpriteIds.SITE, ent.gx, ent.gy, 1f)
            val ghost = if (job == null) 0.3f else 0.55f
            drawSprite(SpriteIds.building(type, 1), ent.gx, ent.gy, ghost, scale, dy)
        } else {
            drawSprite(SpriteIds.building(type, PlotLayout.tierOfLevel(level)), ent.gx, ent.gy, 1f, scale, dy)
        }
        if (job != null) drawConstruction(ent)
        if (pop < 1f) drawBurst(ent, pop)
        drawAmbient(ent, type, level)
    }

    private fun drawTutorialRing(ent: Ent) {
        val pulse = 0.5f + 0.5f * sin(seconds * 6f)
        with(scope) {
            val path =
                Path().apply {
                    moveTo(sx(ent.gx, ent.gy - 2.3f), sy(ent.gx, ent.gy - 2.3f))
                    lineTo(sx(ent.gx + 2.3f, ent.gy), sy(ent.gx + 2.3f, ent.gy))
                    lineTo(sx(ent.gx, ent.gy + 2.3f), sy(ent.gx, ent.gy + 2.3f))
                    lineTo(sx(ent.gx - 2.3f, ent.gy), sy(ent.gx - 2.3f, ent.gy))
                    close()
                }
            drawPath(path, GOLD.copy(alpha = 0.25f + 0.35f * pulse))
            drawPath(path, GOLD, style = Stroke(width = 3f * density))
            val cx = sx(ent.gx, ent.gy)
            val cy = sy(ent.gx, ent.gy) - (115f + 8f * pulse) * sc
            val arrow =
                Path().apply {
                    moveTo(cx, cy)
                    lineTo(cx - 9f * density, cy - 14f * density)
                    lineTo(cx + 9f * density, cy - 14f * density)
                    close()
                }
            drawPath(arrow, GOLD)
        }
    }

    /** 足場・槌・土埃（建設中）。 */
    private fun drawConstruction(ent: Ent) {
        with(scope) {
            val wood = Color(0xFF8C6A3C)
            val corners = listOf(-2f to -2f, 2f to -2f, -2f to 2f, 2f to 2f)
            for ((dx, dy) in corners) {
                val x = sx(ent.gx + dx, ent.gy + dy)
                val y = sy(ent.gx + dx, ent.gy + dy)
                drawLine(wood, Offset(x, y), Offset(x, y - 46f * sc), strokeWidth = 1.6f * sc)
                drawLine(wood, Offset(x, y - 20f * sc), Offset(x + 5f * sc, y - 28f * sc), strokeWidth = 1.2f * sc)
            }
            for (h in listOf(18f, 36f)) {
                val a = Offset(sx(ent.gx - 2f, ent.gy + 2f), sy(ent.gx - 2f, ent.gy + 2f) - h * sc)
                val b = Offset(sx(ent.gx + 2f, ent.gy + 2f), sy(ent.gx + 2f, ent.gy + 2f) - h * sc)
                val c = Offset(sx(ent.gx + 2f, ent.gy - 2f), sy(ent.gx + 2f, ent.gy - 2f) - h * sc)
                drawLine(wood, a, b, strokeWidth = 1.4f * sc)
                drawLine(wood, b, c, strokeWidth = 1.4f * sc)
            }
            // 槌（振る）
            val swing = sin(seconds * 9f + ent.seed * 6f)
            val px = sx(ent.gx + 1.2f, ent.gy + 2f)
            val py = sy(ent.gx + 1.2f, ent.gy + 2f) - 22f * sc
            val ex = px + (6f + 5f * swing) * sc
            val ey = py - (10f - 3f * swing) * sc
            drawLine(Color(0xFF5A4630), Offset(px, py), Offset(ex, ey), strokeWidth = 1.8f * sc)
            drawRect(Color(0xFF8C8A82), Offset(ex - 2.6f * sc, ey - 3f * sc), Size(5.2f * sc, 4f * sc))
            // 土埃
            for (k in 0 until 3) {
                val ph = (seconds * 0.9f + k * 0.33f + ent.seed) % 1f
                val dx = (k - 1) * 11f * sc + (ph - 0.5f) * 6f * sc
                drawCircle(
                    Color(0xFFE8DCC0).copy(alpha = 0.55f * (1f - ph)),
                    radius = (3f + 6f * ph) * sc,
                    center = Offset(sx(ent.gx, ent.gy + 2f) + dx, sy(ent.gx, ent.gy + 2f) + 6f * sc - ph * 12f * sc),
                )
            }
        }
    }

    /** 完成の光とキラキラ。 */
    private fun drawBurst(
        ent: Ent,
        p: Float,
    ) {
        with(scope) {
            val cx = sx(ent.gx, ent.gy)
            val cy = sy(ent.gx, ent.gy) - 30f * sc
            drawCircle(
                Color(0xFFFFE9A0).copy(alpha = 0.55f * (1f - p)),
                radius = (20f + 70f * p) * sc,
                center = Offset(cx, cy),
            )
            drawCircle(
                GOLD.copy(alpha = 0.9f * (1f - p)),
                radius = (24f + 80f * p) * sc,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f * density),
            )
            for (k in 0 until 10) {
                val ang = k * 0.628f + ent.seed
                val r = (14f + 60f * p) * sc
                val x = cx + r * kotlin.math.cos(ang)
                val y = cy + r * 0.62f * sin(ang) - 14f * p * sc
                drawCircle(Color.White.copy(alpha = 1f - p), radius = 2.2f * sc, center = Offset(x, y))
            }
        }
    }

    /** 常時の小さな演出（煙・見張りの灯り・城の輝き）。 */
    private fun drawAmbient(
        ent: Ent,
        type: BuildingType,
        level: Int,
    ) {
        if (level <= 0) return
        val tier = PlotLayout.tierOfLevel(level)
        with(scope) {
            when (type) {
                BuildingType.TAVERN -> {
                    val bx = sx(ent.gx, ent.gy) + 29f * sc
                    val by = sy(ent.gx, ent.gy) - (31f + 3f * tier) * sc
                    for (k in 0 until 4) {
                        val ph = (seconds * 0.55f + k * 0.25f) % 1f
                        drawCircle(
                            Color.White.copy(alpha = 0.5f * (1f - ph)),
                            radius = (2.4f + 4f * ph) * sc,
                            center = Offset(bx + sin(ph * 6f + k) * 3f * sc, by - ph * 22f * sc),
                        )
                    }
                }
                BuildingType.WATCHTOWER -> {
                    val h = listOf(34f, 44f, 54f, 64f, 76f)[tier - 1] + 3f
                    val glow = 0.25f + 0.2f * sin(seconds * 3f)
                    drawCircle(
                        Color(0xFFFFCC66).copy(alpha = glow),
                        radius = 9f * sc,
                        center = Offset(sx(ent.gx, ent.gy), sy(ent.gx, ent.gy) - h * sc),
                    )
                }
                BuildingType.CASTLE ->
                    if (tier >= 4) {
                        for (k in 0 until 3) {
                            val ph = (seconds * 0.6f + k * 0.33f) % 1f
                            drawCircle(
                                GOLD.copy(alpha = 0.8f * (1f - ph)),
                                radius = 2.2f * sc,
                                center =
                                    Offset(
                                        sx(ent.gx, ent.gy) + (k - 1) * 22f * sc,
                                        sy(ent.gx, ent.gy) - (70f + ph * 26f) * sc,
                                    ),
                            )
                        }
                    }
                BuildingType.GOLD_MINE ->
                    if (tier >= 3) {
                        val tw = 0.5f + 0.5f * sin(seconds * 4f + ent.seed * 9f)
                        drawCircle(
                            Color(0xFFFFE08A).copy(alpha = 0.6f * tw),
                            radius = 3f * sc,
                            center = Offset(sx(ent.gx, ent.gy) + 6f * sc, sy(ent.gx, ent.gy) - 28f * sc),
                        )
                    }
                else -> Unit
            }
        }
    }

    // ---- 住民 -----------------------------------------------------------------

    private class VillagerPose(val depth: Float, val gx: Float, val gy: Float, val color: Color)

    private fun visibleVillagers(): List<VillagerPose> {
        val out = ArrayList<VillagerPose>(scene.villagers.size)
        for (v in scene.villagers) {
            if (v.introBand > toBand) continue
            val len = max(1f, v.to - v.from)
            val u = (seconds * v.speed * 0.8f + v.phase * len * 2f) % (len * 2f)
            val along = v.from + if (u < len) u else len * 2f - u
            val gx = if (v.alongX) along else v.fixed
            val gy = if (v.alongX) v.fixed else along
            out.add(VillagerPose(gx + gy, gx, gy, Color(v.color)))
        }
        out.sortBy { it.depth }
        return out
    }

    private fun drawVillager(v: VillagerPose) {
        with(scope) {
            val x = sx(v.gx, v.gy)
            val y = sy(v.gx, v.gy)
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 2.6f * sc, center = Offset(x, y + 0.5f * sc))
            drawRect(v.color, Offset(x - 1.3f * sc, y - 6f * sc), Size(2.6f * sc, 5.6f * sc))
            drawCircle(Color(0xFFF1D2B0), radius = 1.5f * sc, center = Offset(x, y - 7.4f * sc))
        }
    }

    // ---- バッジ（Lv・残り時間）--------------------------------------------------

    fun drawBadges(
        order: List<Ent>,
        levels: IntArray,
        jobs: Array<TimerJob?>,
        types: Array<BuildingType?>,
        levelLabels: HashMap<Int, String>,
        lvFormat: String,
    ) {
        for (ent in order) {
            val px = sx(ent.gx, ent.gy)
            val py = sy(ent.gx, ent.gy) + 24f * sc
            if (isOffScreen(px, py)) continue
            val level = levels[ent.slotId]
            val job = jobs[ent.slotId]
            val label =
                when {
                    job != null -> DurationFormat.clock((job.endEpochSec - nowSec).coerceAtLeast(0))
                    level <= 0 -> "＋"
                    else -> levelLabels.getOrPut(level) { lvFormat.replace("0", level.toString()) }
                }
            drawPill(label, px, py, active = job != null, empty = level <= 0 && job == null)
        }
    }

    private fun drawPill(
        label: String,
        cx: Float,
        cy: Float,
        active: Boolean,
        empty: Boolean,
    ) {
        val layout = texts.layout(label, PILL_STYLE)
        val padX = 7f * scope.density
        val padY = 3f * scope.density
        val w = layout.size.width + padX * 2f
        val h = layout.size.height + padY * 2f
        val left = cx - w / 2f
        val top = cy - h / 2f
        with(scope) {
            val bg =
                when {
                    active -> Color(0xFF7A1F1F).copy(alpha = 0.92f)
                    empty -> Color(0xFF2B2A24).copy(alpha = 0.78f)
                    else -> Color(0xFF16244A).copy(alpha = 0.88f)
                }
            drawRoundRect(
                bg,
                Offset(left, top),
                Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
            )
            drawRoundRect(
                GOLD.copy(alpha = if (empty) 0.5f else 0.95f),
                Offset(left, top),
                Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f),
                style = Stroke(width = 1.2f * density),
            )
            drawText(
                layout,
                color = if (empty) Color(0xFFE6D9A8) else Color(0xFFFFF1C2),
                topLeft = Offset(left + padX, top + padY),
            )
        }
    }

    // ---- 霧 -------------------------------------------------------------------

    fun drawFog() {
        val fog = PlotLayout.FOG
        val from = fog[fromBand - 1]
        val to = fog[toBand - 1]
        val radiusUnits = if (settled) to else from + (to - from) * ease
        val rx = radiusUnits * PlotLayout.U * 1.414f * sc
        val cx = sx(PlotLayout.CENTER, PlotLayout.CENTER)
        val cy = sy(PlotLayout.CENTER, PlotLayout.CENTER)
        with(scope) {
            withTransform({
                translate(cx, cy)
                scale(1f, 0.5f, Offset.Zero)
            }) {
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            0f to Color.Transparent,
                            0.6f to Color.Transparent,
                            0.82f to FOG.copy(alpha = 0.7f),
                            1f to FOG,
                            center = Offset.Zero,
                            radius = rx,
                        ),
                    radius = rx * 4f,
                    center = Offset.Zero,
                )
            }
        }
    }

    private companion object {
        const val OFF = 90f
        val GOLD = Color(0xFFC9A227)
        val LAWN = Color(0xFF5C9944)
        val STREET_DIRT = Color(0xFF8E7B57)
        val STREET_COBBLE = Color(0xFF9A9486)
        val RIVER = Color(0xFF3E7FC4)
        val RIVER_BANK = Color(0xFFC9B88A)
        val FOG = Color(0xFF0F1A33)
        val PILL_STYLE = TextStyle(fontFamily = CrownFonts.Numeric, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        val SIGN_STYLE = TextStyle(fontFamily = CrownFonts.Display, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}
