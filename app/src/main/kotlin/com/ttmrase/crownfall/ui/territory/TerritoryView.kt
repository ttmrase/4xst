package com.ttmrase.crownfall.ui.territory

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.theme.CrownFonts
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    fun set(c: CamState) {
        cx = c.cx
        cy = c.cy
        sc = c.sc
    }

    fun get() = CamState(cx, cy, sc)
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

/** 小さな文字列のレイアウトを使い回す（毎フレーム measure しない）。 */
private class TextCache(private val measurer: TextMeasurer) {
    private val byStyle = HashMap<TextStyle, HashMap<String, TextLayoutResult>>()

    fun layout(
        text: String,
        style: TextStyle,
    ): TextLayoutResult {
        val map = byStyle.getOrPut(style) { HashMap() }
        map[text]?.let { return it }
        if (map.size >= MAX_ENTRIES) map.clear()
        return measurer.measure(text, style).also { map[text] = it }
    }

    private companion object {
        const val MAX_ENTRIES = 160
    }
}

/**
 * 領地ビュー（T10.3）。城・区画・城壁・森・川を、ズームとパンができるアイソメトリック風の地図に描く。
 * 見た目の層だけを差し替える: スロットとレベルは [state] のまま、建物の見た目は Lv の段階（1–5 / 6–10 / …）で変わる。
 * アニメは描画フェーズの時計 [frame] で動かし、毎秒の state 更新とは分ける。スプライトはアプリ共通の [SpriteCache]。
 * [locks] は今は建設できない区画（未建設で前提未達）で、森と霧で覆って札を立てる。建設できる区画は覆わない。
 * [events] の [GameEvent.BuildingLevelUp] で完成のポップを出す（5 秒サンプリングの state に依存しない）。
 */
@Composable
fun TerritoryView(
    state: GameState,
    clock: State<Long>?,
    tutorialSlotId: Int?,
    onTileClick: (BuildingSlot) -> Unit,
    modifier: Modifier = Modifier,
    locks: Map<Int, Prereq> = emptyMap(),
    events: SharedFlow<GameEvent>? = null,
    live: StateFlow<GameState?>? = null,
) {
    val context = LocalContext.current
    val cache = remember { SpriteCache.shared(context) }
    val measurer = rememberTextMeasurer()
    val texts = remember(measurer) { TextCache(measurer) }
    val cam = remember { TerritoryCamera() }
    val frame = remember { mutableLongStateOf(0L) }
    // 区画とタイマーは生の状態から取る（5 秒サンプリングの state だと、完成のポップが古い絵の上で出る）。
    // 区画の並びとジョブの集合が変わったときだけ再構図される。
    val slots by remember(live) { (live?.map { it?.slots } ?: flowOf(null)).filterNotNull().distinctUntilChanged() }
        .collectAsState(initial = state.slots)
    val timers by remember(live) { (live?.map { it?.timers } ?: flowOf(null)).filterNotNull().distinctUntilChanged() }
        .collectAsState(initial = state.timers)
    val currentClick by rememberUpdatedState(onTileClick)
    val currentSlots by rememberUpdatedState(slots)

    val layoutKey = slots.map { it.slotId to it.type }
    val cells = remember(layoutKey) { PlotLayout.cellsFor(slots) }
    val scene = remember(layoutKey) { TerritoryScene.build(cells) }
    val frontToBack = remember(scene) { scene.buildingEnts.sortedBy { it.depth } }
    val slotCount = (slots.maxOfOrNull { it.slotId } ?: -1) + 1
    val typeBySlot =
        remember(layoutKey) {
            arrayOfNulls<BuildingType>(slotCount).also { a -> slots.forEach { a[it.slotId] = it.type } }
        }
    val levels = remember(slotCount) { IntArray(slotCount) }
    val jobs = remember(slotCount) { arrayOfNulls<TimerJob>(slotCount) }
    slots.forEach { levels[it.slotId] = it.level }
    jobs.fill(null)
    timers.forEach { if (it.kind == TimerKind.BUILD) it.slotId?.let { id -> if (id < slotCount) jobs[id] = it } }
    val lockLabels = remember(slotCount) { arrayOfNulls<String>(slotCount) }
    lockLabels.fill(null)
    locks.forEach { (id, info) -> if (id < slotCount) lockLabels[id] = lockText(context, info) }
    val castleLevel = slots.firstOrNull { it.type == BuildingType.CASTLE }?.level ?: 1
    val band = PlotLayout.bandOfCastle(castleLevel)

    val anim = remember { BandAnim(band) }
    val popAt = remember(slotCount) { LongArray(slotCount) { -ANIM_DONE_MS } }
    val bounceAt = remember(slotCount) { LongArray(slotCount) { -ANIM_DONE_MS } }
    // 各帯の札は、その帯から決まる「Lv n」を持つ（帯が進んで消えていく間も、自分の n のまま）
    val signLabels =
        remember {
            Array(PlotLayout.BANDS + 1) { b ->
                PlotLayout.nextBandCastleLevel(b)?.let { context.getString(R.string.territory_expand_sign, it) } ?: ""
            }
        }
    val levelLabels = remember { HashMap<Int, String>() }
    val emptyMark = stringResource(R.string.territory_empty_mark)

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) androidx.compose.runtime.withFrameMillis { t -> frame.longValue = t }
        }
    }
    LaunchedEffect(Unit) {
        cache.preload(SpriteIds.ALL_BUILDINGS.filterIndexed { i, _ -> i % 5 == 0 } + SpriteIds.SITE)
    }
    LaunchedEffect(band) {
        if (band != anim.to) {
            anim.from = anim.to
            anim.to = band
            anim.startMs = frame.longValue
        }
    }
    LaunchedEffect(events, slotCount) {
        events?.collect { e ->
            if (e is GameEvent.BuildingLevelUp && e.slotId in 0 until slotCount) popAt[e.slotId] = frame.longValue
        }
    }

    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewW) {
        if (viewW > 0f && !cam.ready) {
            cam.ready = true
            cam.set(CameraMath.initial(viewW, 0f, PlotLayout.wy(PlotLayout.MID, PlotLayout.MID) + CAMERA_DROP))
        }
    }
    val painter = remember { TerritoryPainter() }
    val res = context.resources

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .onSizeChanged {
                        viewW = it.width.toFloat()
                        viewH = it.height.toFloat()
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            cam.set(
                                CameraMath.transform(
                                    cam.get(),
                                    size.width.toFloat(),
                                    size.height.toFloat(),
                                    centroid.x,
                                    centroid.y,
                                    pan.x,
                                    pan.y,
                                    zoom,
                                ),
                            )
                        }
                    }
                    .pointerInput(scene) {
                        // ダブルタップを待たないので、タップは遅れない
                        detectTapGestures(
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
                                    val slot = currentSlots.firstOrNull { it.slotId == hit.slotId }
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
            val nowSec = clock?.value ?: state.nowEpochSec
            val t = ((nowMs - anim.startMs) / BAND_ANIM_MS).coerceIn(0f, 1f)
            val e = 1f - (1f - t) * (1f - t)
            painter.begin(
                this, cache, texts, scene, sc, size.width / 2f - cam.cx * sc, size.height / 2f - cam.cy * sc,
                nowMs, nowSec, anim.from, anim.to, if (t >= 1f) 1f else e, t >= 1f,
            )
            painter.drawGround()
            painter.drawEntities(levels, jobs, typeBySlot, popAt, bounceAt, tutorialSlotId, signLabels, lockLabels)
            painter.drawBadges(frontToBack, levels, jobs, levelLabels, lockLabels, emptyMark) { lv ->
                res.getString(R.string.tile_level, lv)
            }
        }
        // 画面の読み上げ・操作用: 区画ごとの透明なノード（ポインタ入力は受けず、タップは Canvas が処理する）
        val onScreen by remember(scene) {
            derivedStateOf {
                val sc = cam.sc
                scene.buildingEnts.filter { e ->
                    val x = viewW / 2f + (PlotLayout.wx(e.gx, e.gy) - cam.cx) * sc
                    val y = viewH / 2f + (PlotLayout.wy(e.gx, e.gy) - cam.cy) * sc
                    x in 0f..viewW && y in 0f..viewH
                }.map { it.slotId }.toSet()
            }
        }
        for (ent in scene.buildingEnts) {
            if (ent.slotId !in onScreen) continue
            val slot = slots.firstOrNull { it.slotId == ent.slotId } ?: continue
            val desc = slotDescription(context, slot, lockLabels.getOrNull(slot.slotId))
            Box(
                modifier =
                    Modifier
                        .offset {
                            val sc = cam.sc
                            val x = viewW / 2f + (PlotLayout.wx(ent.gx, ent.gy) - cam.cx) * sc
                            val y = viewH / 2f + (PlotLayout.wy(ent.gx, ent.gy) - cam.cy) * sc - A11Y_LIFT * sc
                            IntOffset((x - A11Y_HALF * density).roundToInt(), (y - A11Y_HALF * density).roundToInt())
                        }
                        .size(A11Y_SIZE_DP.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = desc
                            onClick(label = desc) {
                                currentClick(slot)
                                true
                            }
                        },
            )
        }
    }
}

private fun lockText(
    context: android.content.Context,
    need: Prereq,
): String =
    if (need.type == BuildingType.CASTLE) {
        context.getString(R.string.territory_unlock_castle, need.level)
    } else {
        context.getString(R.string.territory_unlock_building, context.getString(need.type.nameRes()), need.level)
    }

private fun slotDescription(
    context: android.content.Context,
    slot: BuildingSlot,
    lock: String?,
): String {
    val name = context.getString(slot.type.nameRes())
    return when {
        slot.level > 0 -> context.getString(R.string.territory_slot_built, name, slot.level)
        lock != null -> context.getString(R.string.territory_slot_locked, name, lock)
        else -> context.getString(R.string.territory_slot_empty, name)
    }
}

private const val CAMERA_DROP = 14f
private const val HIT_HALF_W = 52f
private const val HIT_UP = 98f
private const val HIT_DOWN = 30f
private const val A11Y_SIZE_DP = 48
private const val A11Y_HALF = 24f
private const val A11Y_LIFT = 40f

/** 1 フレーム分の描画。状態は持たず、引数の値だけで描く。 */
private class TerritoryPainter {
    private lateinit var scope: DrawScope
    private lateinit var cache: SpriteCache
    private lateinit var texts: TextCache
    private lateinit var scene: TerritoryScene
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f
    private var nowMs = 0L
    private var nowSec = 0L
    private var fromBand = 1
    private var toBand = 1
    private var ease = 1f
    private var settled = true
    private var seconds = 0f
    private val iso = Matrix()
    private val ringPath = Path()
    private val arrowPath = Path()
    private val diamond = Path()
    private val labelCache = HashMap<Long, String>()
    private var pillStroke = Stroke(1f)
    private var pillDensity = 0f

    /** 1 フレームの始まり。このクラスのインスタンスは使い回す（フレームごとに作らない）。 */
    fun begin(
        scope: DrawScope,
        cache: SpriteCache,
        texts: TextCache,
        scene: TerritoryScene,
        sc: Float,
        ox: Float,
        oy: Float,
        nowMs: Long,
        nowSec: Long,
        fromBand: Int,
        toBand: Int,
        ease: Float,
        settled: Boolean,
    ) {
        this.scope = scope
        this.cache = cache
        this.texts = texts
        this.scene = scene
        this.sc = sc
        this.ox = ox
        this.oy = oy
        this.nowMs = nowMs
        this.nowSec = nowSec
        this.fromBand = fromBand
        this.toBand = toBand
        this.ease = ease
        this.settled = settled
        seconds = nowMs / MS_PER_SEC
    }

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
                drawPath(scene.river, RIVER_BANK, style = RIVER_BANK_STROKE)
                drawPath(scene.river, RIVER, style = RIVER_STROKE)
                drawPath(
                    scene.river,
                    RIVER_FOAM,
                    style = FOAM_STROKE,
                )
            }
        }
    }

    private fun streetColor(band: Int): Color = if (band <= 2) STREET_DIRT else STREET_COBBLE

    private fun isoMatrix(): Matrix {
        val m = iso
        m.reset()
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
        signLabels: Array<String>,
        locks: Array<String?>,
    ) {
        val nv = computeVillagers()
        var vi = 0
        for (ent in scene.ents) {
            while (vi < nv && vDepth[vOrder[vi]] < ent.depth) {
                drawVillager(vOrder[vi])
                vi++
            }
            // 画面外はすぐ飛ばす
            val px = sx(ent.gx, ent.gy)
            val py = sy(ent.gx, ent.gy)
            if (isOffScreen(px, py)) continue
            if (ent.kind == EntKind.BUILDING) {
                if (locks[ent.slotId] != null) {
                    drawLocked(ent)
                } else {
                    drawBuilding(ent, levels, jobs, types, popAt, bounceAt, tutorialSlotId)
                }
                continue
            }
            val a = presence(ent)
            if (a <= 0.01f) continue
            when (ent.kind) {
                EntKind.TREE ->
                    drawSprite(ent.sprite, ent.gx, ent.gy, a, scale = 0.7f + 0.3f * a, dy = swayTree(ent))
                EntKind.SIGN -> {
                    drawSprite(ent.sprite, ent.gx, ent.gy, a)
                    val label = signLabels.getOrNull(ent.band).orEmpty()
                    if (label.isNotEmpty()) drawSignText(ent, label, a)
                }
                else -> {
                    drawSprite(ent.sprite, ent.gx, ent.gy, a, scale = 0.88f + 0.12f * a)
                    // 帯が上がって街並みが建つ・消えるとき、足元に土埃を立てる
                    if (a < 1f && isTown(ent.kind)) drawDust(ent, a)
                }
            }
        }
        while (vi < nv) {
            drawVillager(vOrder[vi])
            vi++
        }
    }

    /** 建設できない区画: 森と霧で覆い、札は [drawBadges] が出す。 */
    private fun drawLocked(ent: Ent) {
        with(scope) {
            val h = 2.6f
            diamond.rewind()
            diamond.moveTo(sx(ent.gx, ent.gy - h), sy(ent.gx, ent.gy - h))
            diamond.lineTo(sx(ent.gx + h, ent.gy), sy(ent.gx + h, ent.gy))
            diamond.lineTo(sx(ent.gx, ent.gy + h), sy(ent.gx, ent.gy + h))
            diamond.lineTo(sx(ent.gx - h, ent.gy), sy(ent.gx - h, ent.gy))
            diamond.close()
            drawPath(diamond, Color(0xFF14301E))
        }
        for (k in LOCK_TREES.indices step 2) {
            drawSprite(SpriteIds.TREE_PINE_A, ent.gx + LOCK_TREES[k], ent.gy + LOCK_TREES[k + 1], 1f)
        }
        with(scope) {
            drawPath(diamond, FOG.copy(alpha = 0.55f))
        }
    }

    private fun isTown(kind: EntKind): Boolean =
        kind == EntKind.HOUSE || kind == EntKind.STALL || kind == EntKind.FIELD ||
            kind == EntKind.MANOR || kind == EntKind.STATUE

    private fun drawDust(
        ent: Ent,
        a: Float,
    ) {
        val x = sx(ent.gx, ent.gy)
        val y = sy(ent.gx, ent.gy)
        val fade = 1f - a
        with(scope) {
            for (k in 0 until 3) {
                val dx = (k - 1) * 9f * sc
                drawCircle(
                    DUST.copy(alpha = 0.55f * fade),
                    radius = (4f + 8f * a) * sc,
                    center = Offset(x + dx, y + 2f * sc - 6f * a * sc),
                )
            }
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
            val path = ringPath
            path.rewind()
            path.moveTo(sx(ent.gx, ent.gy - 2.3f), sy(ent.gx, ent.gy - 2.3f))
            path.lineTo(sx(ent.gx + 2.3f, ent.gy), sy(ent.gx + 2.3f, ent.gy))
            path.lineTo(sx(ent.gx, ent.gy + 2.3f), sy(ent.gx, ent.gy + 2.3f))
            path.lineTo(sx(ent.gx - 2.3f, ent.gy), sy(ent.gx - 2.3f, ent.gy))
            path.close()
            drawPath(path, GOLD.copy(alpha = 0.25f + 0.35f * pulse))
            drawPath(path, GOLD, style = ringStrokeFor(density))
            val cx = sx(ent.gx, ent.gy)
            val cy = sy(ent.gx, ent.gy) - (115f + 8f * pulse) * sc
            val arrow = arrowPath
            arrow.rewind()
            arrow.moveTo(cx, cy)
            arrow.lineTo(cx - 9f * density, cy - 14f * density)
            arrow.lineTo(cx + 9f * density, cy - 14f * density)
            arrow.close()
            drawPath(arrow, GOLD)
        }
    }

    /** 足場・槌・土埃（建設中）。 */
    private fun drawConstruction(ent: Ent) {
        with(scope) {
            val wood = Color(0xFF8C6A3C)
            for (c in 0 until 4) {
                val dx = CORNERS[c * 2]
                val dy = CORNERS[c * 2 + 1]
                val x = sx(ent.gx + dx, ent.gy + dy)
                val y = sy(ent.gx + dx, ent.gy + dy)
                drawLine(wood, Offset(x, y), Offset(x, y - 46f * sc), strokeWidth = 1.6f * sc)
                drawLine(wood, Offset(x, y - 20f * sc), Offset(x + 5f * sc, y - 28f * sc), strokeWidth = 1.2f * sc)
            }
            for (h in RAILS) {
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
                style = burstStrokeFor(density),
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
                    val h = TOWER_HEIGHTS[tier - 1] + 3f
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

    private var vDepth = FloatArray(0)
    private var vGx = FloatArray(0)
    private var vGy = FloatArray(0)
    private var vOrder = IntArray(0)

    /** 見える住民の位置と描く順（奥から手前）を配列に作る。返り値は人数。毎フレームの確保はしない。 */
    private fun computeVillagers(): Int {
        val list = scene.villagers
        if (vDepth.size < list.size) {
            vDepth = FloatArray(list.size)
            vGx = FloatArray(list.size)
            vGy = FloatArray(list.size)
            vOrder = IntArray(list.size)
        }
        var n = 0
        for (idx in list.indices) {
            val v = list[idx]
            if (v.introBand > toBand) continue
            val len = max(1f, v.to - v.from)
            val u = (seconds * v.speed * 0.8f + v.phase * len * 2f) % (len * 2f)
            val along = v.from + if (u < len) u else len * 2f - u
            val gx = if (v.alongX) along else v.fixed
            val gy = if (v.alongX) v.fixed else along
            vDepth[idx] = gx + gy
            vGx[idx] = gx
            vGy[idx] = gy
            // 挿入ソート（人数は十数人）
            var k = n
            while (k > 0 && vDepth[vOrder[k - 1]] > vDepth[idx]) {
                vOrder[k] = vOrder[k - 1]
                k--
            }
            vOrder[k] = idx
            n++
        }
        return n
    }

    private fun drawVillager(idx: Int) {
        val v = scene.villagers[idx]
        with(scope) {
            val x = sx(vGx[idx], vGy[idx])
            val y = sy(vGx[idx], vGy[idx])
            drawCircle(Color.Black.copy(alpha = 0.25f), radius = 2.6f * sc, center = Offset(x, y + 0.5f * sc))
            drawRect(Color(v.color), Offset(x - 1.3f * sc, y - 6f * sc), Size(2.6f * sc, 5.6f * sc))
            drawCircle(Color(0xFFF1D2B0), radius = 1.5f * sc, center = Offset(x, y - 7.4f * sc))
        }
    }

    // ---- バッジ（Lv・残り時間）--------------------------------------------------

    fun drawBadges(
        order: List<Ent>,
        levels: IntArray,
        jobs: Array<TimerJob?>,
        levelLabels: HashMap<Int, String>,
        locks: Array<String?>,
        emptyMark: String,
        levelText: (Int) -> String,
    ) {
        for (ent in order) {
            val px = sx(ent.gx, ent.gy)
            val py = sy(ent.gx, ent.gy) + 24f * sc
            if (isOffScreen(px, py)) continue
            val level = levels[ent.slotId]
            val job = jobs[ent.slotId]
            val lock = locks[ent.slotId]
            val label =
                when {
                    lock != null -> lock
                    job != null -> timerLabel((job.endEpochSec - nowSec).coerceAtLeast(0))
                    level <= 0 -> emptyMark
                    else -> levelLabels.getOrPut(level) { levelText(level) }
                }
            drawPill(label, px, py, active = job != null, empty = level <= 0 && job == null)
        }
    }

    /** 残り時間の文字列。同じ秒の間は作り直さない。 */
    private fun timerLabel(remainSec: Long): String {
        if (labelCache.size > MAX_LABELS) labelCache.clear()
        return labelCache.getOrPut(remainSec) { DurationFormat.clock(remainSec) }
    }

    private var ringStroke = Stroke(1f)
    private var ringDensity = 0f
    private var burstStroke = Stroke(1f)
    private var burstDensity = 0f

    private fun ringStrokeFor(density: Float): Stroke {
        if (density != ringDensity) {
            ringDensity = density
            ringStroke = Stroke(width = 3f * density)
        }
        return ringStroke
    }

    private fun burstStrokeFor(density: Float): Stroke {
        if (density != burstDensity) {
            burstDensity = density
            burstStroke = Stroke(width = 2.5f * density)
        }
        return burstStroke
    }

    private fun pillStrokeFor(density: Float): Stroke {
        if (density != pillDensity) {
            pillDensity = density
            pillStroke = Stroke(width = 1.2f * density)
        }
        return pillStroke
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
                style = pillStrokeFor(density),
            )
            drawText(
                layout,
                color = if (empty) Color(0xFFE6D9A8) else Color(0xFFFFF1C2),
                topLeft = Offset(left + padX, top + padY),
            )
        }
    }

    private companion object {
        const val OFF = 130f
        val DUST = Color(0xFFE8DCC0)
        const val MS_PER_SEC = 1000f
        const val MAX_LABELS = 400
        val CORNERS = floatArrayOf(-2f, -2f, 2f, -2f, -2f, 2f, 2f, 2f)
        val RAILS = floatArrayOf(18f, 36f)
        val TOWER_HEIGHTS = floatArrayOf(34f, 44f, 54f, 64f, 76f)
        val LOCK_TREES = floatArrayOf(-1.2f, -1.2f, 1.2f, -1.2f, -1.2f, 1.2f, 1.2f, 1.2f)
        val RIVER_BANK_STROKE = Stroke(5.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val RIVER_STROKE = Stroke(3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val RIVER_FOAM = Color.White.copy(alpha = 0.45f)
        val DASH = PathEffect.dashPathEffect(floatArrayOf(1.6f, 4.4f), 0f)
        val FOAM_STROKE = Stroke(0.5f, cap = StrokeCap.Round, pathEffect = DASH)
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
