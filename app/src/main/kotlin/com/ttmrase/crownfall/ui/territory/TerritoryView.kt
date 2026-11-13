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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
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
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.BuildingSlot
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Prereq
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.TimerJob
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.kit.GlyphBitmaps
import com.ttmrase.crownfall.ui.kit.drawGlyph
import com.ttmrase.crownfall.ui.kit.rememberGlyphBitmaps
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
 * アニメは描画フェーズの時計 現在時刻（描画のたびに読む）で動かし、毎秒の state 更新とは分ける。スプライトはアプリ共通の [SpriteCache]。
 * 再描画は画面のフレームごと（[rememberAmbientFrame]。重い端末では [FramePacer] が間合いを落とす）。端末のアニメーションが
 * 無効なら合図を出さず、飾りの動き（[TerritoryLife]）は静止画になる。
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
    upgradable: Set<Int> = emptySet(),
    events: SharedFlow<GameEvent>? = null,
    live: StateFlow<GameState?>? = null,
    /** いま出ている収穫バブル（スロット ID → 中身の資源）。タップは [onBubbleTap]（画面上の位置つき）。 */
    bubbles: Map<Int, ResourceType> = emptyMap(),
    onBubbleTap: (slotId: Int, windowPos: Offset) -> Unit = { _, _ -> },
    /** 装備中の城のスキンのテーマ（ice / gold / sakura。null は標準）。 */
    castleSkin: String? = null,
    territorySeason: String? = null,
) {
    val context = LocalContext.current
    val cache = remember { SpriteCache.shared(context) }
    val measurer = rememberTextMeasurer()
    val texts = remember(measurer) { TextCache(measurer) }
    val glyphs = rememberGlyphBitmaps(22.dp)
    val cam = remember { TerritoryCamera() }
    // 区画とタイマーは生の状態から取る（5 秒サンプリングの state だと、完成のポップが古い絵の上で出る）。
    // 区画の並びとジョブの集合が変わったときだけ再構図される。
    val slots by remember(live) { (live?.map { it?.slots } ?: flowOf(null)).filterNotNull().distinctUntilChanged() }
        .collectAsState(initial = state.slots)
    val timers by remember(live) { (live?.map { it?.timers } ?: flowOf(null)).filterNotNull().distinctUntilChanged() }
        .collectAsState(initial = state.timers)
    val currentClick by rememberUpdatedState(onTileClick)
    val currentBubbles by rememberUpdatedState(bubbles)
    val currentBubbleTap by rememberUpdatedState(onBubbleTap)
    var viewOrigin by remember { mutableStateOf(Offset.Zero) }
    val currentSlots by rememberUpdatedState(slots)
    val currentBandState = remember { mutableIntStateOf(1) }

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
    val upgradeFlags = remember(slotCount) { BooleanArray(slotCount) }
    upgradeFlags.fill(false)
    upgradable.forEach { if (it in 0 until slotCount) upgradeFlags[it] = true }
    val lockLabels = remember(slotCount) { arrayOfNulls<String>(slotCount) }
    lockLabels.fill(null)
    locks.forEach { (id, info) -> if (id < slotCount) lockLabels[id] = lockText(context, info) }
    // 札を出すのは「次に開く」区画だけ（城 Lv の最小のものを 1 段ずつ）。それ以外は霧と森だけで、タップすれば案内が出る
    val pillLabels = remember(slotCount) { arrayOfNulls<String>(slotCount) }
    val shown = lockPillSlots(locks)
    for (i in 0 until slotCount) pillLabels[i] = lockLabels[i]?.let { if (i in shown) it else "" }
    val castleLevel = slots.firstOrNull { it.type == BuildingType.CASTLE }?.level ?: 1
    val band = PlotLayout.bandOfCastle(castleLevel)
    currentBandState.intValue = band

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

    // 毎フレームの再描画の合図（描画の中で読んで、再描画だけを起こす）。無限アニメーションと同じ待ち方なので、
    // Compose のテストは idle になれる。アニメーション無効の端末では合図を出さず、静止画を描く
    val animate = animationsEnabled()
    val pacer = remember { FramePacer() }
    val frame = rememberAmbientFrame(pacer, animate)
    LaunchedEffect(Unit) {
        cache.preload(SpriteIds.ALL_BUILDINGS.filterIndexed { i, _ -> i % 5 == 0 } + SpriteIds.SITE)
    }
    LaunchedEffect(band) {
        if (band != anim.to) {
            anim.from = anim.to
            anim.to = band
            anim.startMs = nowMs()
        }
    }
    LaunchedEffect(events, slotCount) {
        events?.collect { e ->
            if (e is GameEvent.BuildingLevelUp && e.slotId in 0 until slotCount) popAt[e.slotId] = nowMs()
        }
    }

    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(viewW, viewH) {
        if (viewW > 0f && viewH > 0f && !cam.ready) {
            cam.ready = true
            cam.set(CameraMath.initial(viewW, viewH, PlotLayout.INITIAL_CENTER_X, PlotLayout.INITIAL_CENTER_Y))
        }
    }
    val painter = remember { TerritoryPainter() }
    painter.castleSkin = castleSkin
    painter.season = territorySeason
    val res = context.resources
    // 描画のたびにラムダを作らない
    val levelText = remember(res) { { lv: Int -> res.getString(R.string.tile_level, lv) } }

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
                    .onGloballyPositioned { viewOrigin = it.positionInWindow() }
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
                                    limitHalf = PlotLayout.WALL_HALF[currentBandState.intValue - 1],
                                ),
                            )
                        }
                    }
                    .pointerInput(scene) {
                        // ダブルタップを待たないので、タップは遅れない
                        detectTapGestures(
                            onTap = { tap ->
                                val sc = cam.sc
                                // バブルは建物より前面にあるので、先に当たりを見る
                                val w = size.width / 2f
                                val h = size.height / 2f
                                val hit =
                                    scene.buildingEnts.filter { it.slotId in currentBubbles }
                                        .map { e ->
                                            e to
                                                Offset(
                                                    w + (PlotLayout.wx(e.gx, e.gy) - cam.cx) * sc,
                                                    h + (PlotLayout.wy(e.gx, e.gy) - cam.cy) * sc - BUBBLE_UP * sc,
                                                )
                                        }
                                        .minByOrNull { (_, c) -> (tap - c).getDistance() }
                                        ?.takeIf { (_, c) -> (tap - c).getDistance() <= BUBBLE_HIT_DP * density }
                                if (hit != null) {
                                    currentBubbleTap(hit.first.slotId, viewOrigin + hit.second)
                                    return@detectTapGestures
                                }
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
                                        bounceAt[slot.slotId] = nowMs()
                                        currentClick(slot)
                                    }
                                }
                            },
                        )
                    },
        ) {
            if (frame.longValue < 0L) return@Canvas
            val startNs = System.nanoTime()
            val nowMs = startNs / NANOS_PER_MS
            val loadTick = cache.version
            if (!cam.ready || loadTick < 0) return@Canvas
            val sc = cam.sc
            val nowSec = clock?.value ?: state.nowEpochSec
            // アニメーション無効なら、帯の拡張も途中の形を見せずに終わった形で描く
            val t = if (animate) ((nowMs - anim.startMs) / BAND_ANIM_MS).coerceIn(0f, 1f) else 1f
            val e = 1f - (1f - t) * (1f - t)
            painter.begin(
                this, cache, texts, glyphs, scene, sc, size.width / 2f - cam.cx * sc, size.height / 2f - cam.cy * sc,
                nowMs, nowSec, anim.from, anim.to, if (t >= 1f) 1f else e, t >= 1f, animate, castleLevel,
            )
            painter.drawGround()
            painter.drawEntities(
                levels,
                jobs,
                typeBySlot,
                popAt,
                bounceAt,
                tutorialSlotId,
                signLabels,
                lockLabels,
                upgradeFlags,
            )
            painter.drawSky()
            painter.drawBadges(
                frontToBack,
                levels,
                jobs,
                levelLabels,
                pillLabels,
                emptyMark,
                upgradeFlags,
                sc * BADGE_SPAN >= size.width,
                levelText,
            )
            painter.drawSeason()
            if (currentBubbles.isNotEmpty()) painter.drawBubbles(frontToBack, currentBubbles)
            pacer.reportCost(System.nanoTime() - startNs)
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

/**
 * 札を出す区画。城 Lv が前提の区画は、最小の Lv のものだけ（初期の街で札が重ならないように）。
 * 城以外が前提の区画は、城前提の区画が無いときだけ出す。
 */
internal fun lockPillSlots(locks: Map<Int, Prereq>): Set<Int> {
    val castle = locks.filterValues { it.type == BuildingType.CASTLE }
    if (castle.isEmpty()) return locks.keys
    val min = castle.values.minOf { it.level }
    return castle.filterValues { it.level == min }.keys
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

/** 画面の幅がこの幅のワールド範囲より狭く映るほど拡大したら、全区画に Lv の札を出す。 */
private const val BADGE_SPAN = 420f
private const val NANOS_PER_MS = 1_000_000L

private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MS

private const val HIT_HALF_W = 52f
private const val HIT_UP = 98f
private const val HIT_DOWN = 30f

/** 収穫バブル: 建物の上に浮かせる高さ（ワールド座標）と、タップできる半径（dp）。 */
private const val BUBBLE_UP = 84f
private const val BUBBLE_HIT_DP = 24f
private const val A11Y_SIZE_DP = 48
private const val A11Y_HALF = 24f
private const val A11Y_LIFT = 40f

/** 1 フレーム分の描画。状態は持たず、引数の値だけで描く。 */
private class TerritoryPainter {
    private lateinit var scope: DrawScope
    private lateinit var cache: SpriteCache
    private lateinit var texts: TextCache
    private lateinit var glyphs: GlyphBitmaps
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
    private var animate = true

    /** 住民・旗・煙・雲の影など、飾りの動きの層。 */
    private val life = TerritoryLife()

    /** 装備中の城のスキン（theme。無ければ標準）。M11 T11.4。 */
    var castleSkin: String? = null

    /** 装備中の領地のテーマ（spring / autumn / winter は地面の色も変わる。無ければ標準）。M11 T11.4。 */
    var season: String? = null
    private val iso = android.graphics.Matrix()
    private val isoValues = FloatArray(MATRIX_VALUES)
    private val ringPath = Path()
    private val arrowPath = Path()
    private val diamond = Path()
    private var timerSec = LongArray(0)
    private var timerText = arrayOfNulls<String>(0)
    private var pillStroke = Stroke(1f)
    private var pillDensity = 0f

    /** 1 フレームの始まり。このクラスのインスタンスは使い回す（フレームごとに作らない）。 */
    fun begin(
        scope: DrawScope,
        cache: SpriteCache,
        texts: TextCache,
        glyphs: GlyphBitmaps,
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
        animate: Boolean,
        castleLevel: Int,
    ) {
        this.scope = scope
        this.cache = cache
        this.texts = texts
        this.glyphs = glyphs
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
        this.animate = animate
        // 飾りの時計。アニメーション無効なら止めて、いつ描いても同じ静止画にする
        seconds = if (animate) ambientSeconds(nowMs) else AMBIENT_STILL_SECONDS
        life.begin(scope, scene, sc, ox, oy, seconds, animate, season, castleLevel, toBand)
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
            drawRect(if (season == "winter") WINTER_BG else Color(0xFF1B3524))
            val lawn = lawnColor()
            // グリッド座標のまま描くための行列。ネイティブの Canvas に直接かける（Compose の transform(Matrix) は
            // 呼ぶたびに android.graphics.Matrix を確保する）
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            canvas.concat(isoMatrix())
            // 草地（開かれた土地）。帯が進むと広がる
            drawPath(scene.lawn[fromBand - 1], lawn)
            if (!settled && toBand != fromBand) drawPath(scene.lawn[toBand - 1], lawn, alpha = ease)
            // 通り
            drawPath(scene.streets[fromBand - 1], streetColor(fromBand))
            if (!settled && toBand != fromBand) drawPath(scene.streets[toBand - 1], streetColor(toBand), alpha = ease)
            // 川
            drawPath(scene.river, RIVER_BANK, style = RIVER_BANK_STROKE)
            drawPath(scene.river, RIVER, style = RIVER_STROKE)
            drawPath(scene.river, RIVER_FOAM, style = FOAM_STROKE)
            canvas.restore()
        }
        // 水面を流れる光
        life.drawWater()
    }

    /** 雲の影・森の縁の粒・鳥（建物と住民の上、札の下）。 */
    fun drawSky() {
        life.drawCloudShadows()
        life.drawEdgeMotes()
        life.drawBirds()
    }

    private fun lawnColor(): Color =
        when (season) {
            "spring" -> LAWN_SPRING
            "autumn" -> LAWN_AUTUMN
            "winter" -> LAWN_WINTER
            else -> LAWN
        }

    /** 領地のテーマの粒（春・桜: 花びら / 秋: 落ち葉 / 冬・氷: 雪 / 金: きらめき / 竜: 火の粉）。画面の上から降る。 */
    fun drawSeason() {
        val theme = season ?: return
        val spec = SEASON_PARTICLES[theme] ?: return
        with(scope) {
            val w = size.width
            val h = size.height
            val r = spec.radiusDp * density
            for (i in 0 until SEASON_COUNT) {
                val speed = spec.speed * (0.6f + (i % 5) * 0.2f)
                val phase = (i * 0.6180339f) % 1f
                val rise = spec.rising
                val t = ((seconds * speed / h * density * 40f + phase) % 1f)
                val y = if (rise) h * (1f - t) else h * t
                val x = (phase * 7.3f % 1f) * w + sin(seconds * spec.sway + i) * 14f * density
                drawCircle(spec.color, r * (0.7f + (i % 3) * 0.3f), Offset(x, y))
            }
        }
    }

    private fun streetColor(band: Int): Color = if (band <= 2) STREET_DIRT else STREET_COBBLE

    /** グリッド座標 → 画面座標の行列（x' = (gx - gy) U sc + ox、y' = (gx + gy) V sc + oy）。使い回す。 */
    private fun isoMatrix(): android.graphics.Matrix {
        val v = isoValues
        v[android.graphics.Matrix.MSCALE_X] = PlotLayout.U * sc
        v[android.graphics.Matrix.MSKEW_X] = -PlotLayout.U * sc
        v[android.graphics.Matrix.MTRANS_X] = ox
        v[android.graphics.Matrix.MSKEW_Y] = PlotLayout.V * sc
        v[android.graphics.Matrix.MSCALE_Y] = PlotLayout.V * sc
        v[android.graphics.Matrix.MTRANS_Y] = oy
        v[android.graphics.Matrix.MPERSP_2] = 1f
        iso.setValues(v)
        return iso
    }

    // ---- スプライト -------------------------------------------------------------

    private fun drawSprite(
        id: Int,
        gx: Float,
        gy: Float,
        alpha: Float = 1f,
        scale: Float = 1f,
        dy: Float = 0f,
        /** 足元を軸にした傾き（度）。木が風に揺れる。 */
        tilt: Float = 0f,
    ) {
        val sp = cache.get(id) ?: return
        val w = sp.vw * sc * scale
        val h = sp.vh * sc * scale
        val px = sx(gx, gy)
        val py = sy(gx, gy)
        val left = px - sp.ax * sc * scale
        val top = py - sp.ay * sc * scale + dy
        if (left > scope.size.width || top > scope.size.height || left + w < 0f || top + h < 0f) return
        if (tilt == 0f) {
            drawSpriteAt(sp, left, top, w, h, alpha)
        } else {
            scope.withTransform({ rotate(tilt, Offset(px, py)) }) { drawSpriteAt(sp, left, top, w, h, alpha) }
        }
    }

    private fun drawSpriteAt(
        sp: Sprite,
        left: Float,
        top: Float,
        w: Float,
        h: Float,
        alpha: Float,
    ) {
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

    /**
     * 奥から手前へ: 飾り・建物と、そのあいだを歩く住民・旗竿（[TerritoryLife]）を奥行きの順に混ぜて描く。
     * 並びは奥行き（= 画面の縦位置）の順なので、画面に映る範囲だけを二分探索で切り出して回す。
     */
    fun drawEntities(
        levels: IntArray,
        jobs: Array<TimerJob?>,
        types: Array<BuildingType?>,
        popAt: LongArray,
        bounceAt: LongArray,
        tutorialSlotId: Int?,
        signLabels: Array<String>,
        locks: Array<String?>,
        upgradable: BooleanArray,
    ) {
        val nv = life.prepare(levels, locks)
        var vi = 0
        val ents = scene.ents
        val span = PlotLayout.V * sc
        val from = (-OFF * sc - oy) / span
        val to = (scope.size.height + OFF * sc - oy) / span + SIGN_DEPTH_SPAN
        var i = firstAtDepth(ents, from)
        while (i < ents.size) {
            val ent = ents[i++]
            if (ent.depth > to) break
            while (vi < nv && life.depthAt(vi) < ent.depth) life.drawItem(vi++)
            // 画面外はすぐ飛ばす
            val px = sx(ent.gx, ent.gy)
            val py = sy(ent.gx, ent.gy)
            if (isOffScreen(px, py)) continue
            if (ent.kind == EntKind.BUILDING) {
                if (locks[ent.slotId] != null) {
                    drawLocked(ent)
                } else {
                    drawBuilding(ent, levels, jobs, types, popAt, bounceAt, tutorialSlotId, upgradable)
                }
                continue
            }
            val a = presence(ent)
            if (a <= 0.01f) continue
            when (ent.kind) {
                EntKind.TREE ->
                    drawSprite(
                        ent.sprite,
                        ent.gx,
                        ent.gy,
                        a,
                        scale = 0.7f + 0.3f * a,
                        tilt = life.treeSway(ent.gx, ent.gy, ent.seed),
                    )
                EntKind.SIGN -> {
                    drawSprite(ent.sprite, ent.gx, ent.gy, a)
                    val label = signLabels.getOrNull(ent.band).orEmpty()
                    if (label.isNotEmpty()) drawSignText(ent, label, a)
                }
                else -> {
                    drawSprite(ent.sprite, ent.gx, ent.gy, a, scale = 0.88f + 0.12f * a)
                    // 帯が上がって街並みが建つ・消えるとき、足元に土埃を立てる
                    if (a < 1f && isTown(ent.kind)) {
                        drawDust(ent, a)
                    } else if (a >= 1f) {
                        life.decorIdle(ent)
                    }
                }
            }
        }
        while (vi < nv) life.drawItem(vi++)
    }

    /** 奥行きが [depth] 以上の最初の要素の位置（[ents] は奥行きの昇順）。 */
    private fun firstAtDepth(
        ents: Array<Ent>,
        depth: Float,
    ): Int {
        var lo = 0
        var hi = ents.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ents[mid].depth < depth) lo = mid + 1 else hi = mid
        }
        return lo
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
            val gx = ent.gx + LOCK_TREES[k]
            val gy = ent.gy + LOCK_TREES[k + 1]
            drawSprite(SpriteIds.TREE_PINE_A, gx, gy, 1f, tilt = life.treeSway(gx, gy, ent.seed + k * 0.11f))
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
        upgradable: BooleanArray,
    ) {
        val id = ent.slotId
        val type = types[id] ?: return
        val level = levels[id]
        val job = jobs[id]
        // アニメーション無効なら、完成のポップもタップの弾みも出さない（途中の形で止まらない）
        val pop = if (animate) ((nowMs - popAt[id]) / POP_MS).coerceIn(0f, 1f) else 1f
        val bounce = if (animate) ((nowMs - bounceAt[id]) / BOUNCE_MS).coerceIn(0f, 1f) else 1f
        var scale = 1f
        if (pop < 1f) scale += 0.2f * sin(pop * PI.toFloat()) * (1f - pop)
        val dy = if (bounce < 1f) -7f * sc * sin(bounce * PI.toFloat()) else 0f
        if (tutorialSlotId == id) drawTutorialRing(ent)
        if (level <= 0) {
            drawSprite(SpriteIds.SITE, ent.gx, ent.gy, 1f)
            val ghost = if (job == null) 0.3f else 0.55f
            drawSprite(SpriteIds.building(type, 1), ent.gx, ent.gy, ghost, scale, dy)
        } else {
            val tier = PlotLayout.tierOfLevel(level)
            val skin = castleSkin
            val sprite =
                if (type == BuildingType.CASTLE && skin != null) {
                    SpriteIds.castleSkin(skin, tier)
                } else {
                    SpriteIds.building(type, tier)
                }
            drawSprite(sprite, ent.gx, ent.gy, 1f, scale, dy)
        }
        if (job != null) drawConstruction(ent)
        if (pop < 1f) drawBurst(ent, pop)
        if (level > 0) life.buildingIdle(ent, type, PlotLayout.tierOfLevel(level), upgradable[id] && job == null)
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

    // ---- バッジ（Lv・残り時間）--------------------------------------------------

    fun drawBadges(
        order: List<Ent>,
        levels: IntArray,
        jobs: Array<TimerJob?>,
        levelLabels: HashMap<Int, String>,
        locks: Array<String?>,
        emptyMark: String,
        upgradable: BooleanArray,
        showLevels: Boolean,
        levelText: (Int) -> String,
    ) {
        for (ent in order) {
            val px = sx(ent.gx, ent.gy)
            val py = sy(ent.gx, ent.gy) + 12f * sc
            if (isOffScreen(px, py)) continue
            val level = levels[ent.slotId]
            val job = jobs[ent.slotId]
            val lock = locks[ent.slotId]
            when {
                lock != null -> if (lock.isNotEmpty()) drawPill(lock, px, py, active = false, empty = true)
                job != null ->
                    drawPill(
                        timerLabel(ent.slotId, (job.endEpochSec - nowSec).coerceAtLeast(0)),
                        px,
                        py,
                        active = true,
                        empty = false,
                    )
                // 初期ズームでは Lv の札を出さず、拡大したときだけ（建物の絵を隠さない）
                showLevels ->
                    drawPill(
                        if (level <= 0) emptyMark else levelLabels.getOrPut(level) { levelText(level) },
                        px,
                        py,
                        active = false,
                        empty = level <= 0,
                    )
                upgradable[ent.slotId] -> drawUpgradeMark(ent)
            }
        }
    }

    /** 今すぐアップグレードできる区画の、小さな金の印（上下にゆっくり揺れる）。 */
    private fun drawUpgradeMark(ent: Ent) {
        val x = sx(ent.gx, ent.gy)
        val bob = sin(seconds * 4f + ent.seed * 6f) * 2f * sc
        val y = sy(ent.gx, ent.gy) - 66f * sc + bob
        val r = 6f * scope.density
        with(scope) {
            drawCircle(GOLD, radius = r, center = Offset(x, y))
            val tri = arrowPath
            tri.rewind()
            tri.moveTo(x, y - r * 0.55f)
            tri.lineTo(x - r * 0.5f, y + r * 0.35f)
            tri.lineTo(x + r * 0.5f, y + r * 0.35f)
            tri.close()
            drawPath(tri, Color.White)
        }
    }

    /** 収穫バブル。建物の上にふわふわ揺れて浮かび、中に資源の絵文字が入る（拡大縮小しても大きさは一定）。 */
    fun drawBubbles(
        order: List<Ent>,
        bubbles: Map<Int, ResourceType>,
    ) {
        for (ent in order) {
            val type = bubbles[ent.slotId] ?: continue
            val x = sx(ent.gx, ent.gy)
            val bob = sin(seconds * 2.6f + ent.seed * 6f)
            val y = sy(ent.gx, ent.gy) - BUBBLE_UP * sc + bob * 3f * scope.density
            if (isOffScreen(x, y)) continue
            val sway = sin(seconds * 1.9f + ent.seed * 3f) * 2f * scope.density
            val r = 15f * scope.density * (1f + 0.05f * sin(seconds * 5f + ent.seed))
            with(scope) {
                val c = Offset(x + sway, y)
                drawCircle(Color(0x66FFFFFF), radius = r * 1.25f, center = c)
                drawCircle(Color(0xE6F4F9FF), radius = r, center = c)
                drawCircle(BUBBLE_RIM, radius = r, center = c, style = bubbleStrokeFor(density))
                drawCircle(Color.White, radius = r * 0.22f, center = Offset(c.x - r * 0.4f, c.y - r * 0.45f))
                drawGlyph(glyphs, type.glyph(), c, r * BUBBLE_ICON)
            }
        }
    }

    /** 区画 [slot] の残り時間の文字列。同じ秒の間は作り直さない（区画ごとに直前の値を覚える。箱詰めもしない）。 */
    private fun timerLabel(
        slot: Int,
        remainSec: Long,
    ): String {
        if (timerSec.size <= slot) {
            timerSec = LongArray(slot + LABEL_ROOM) { -1L }
            timerText = arrayOfNulls(slot + LABEL_ROOM)
        }
        val cached = timerText[slot]
        if (cached != null && timerSec[slot] == remainSec) return cached
        val made = DurationFormat.clock(remainSec)
        timerSec[slot] = remainSec
        timerText[slot] = made
        return made
    }

    private var ringStroke = Stroke(1f)
    private var ringDensity = 0f
    private var burstStroke = Stroke(1f)
    private var burstDensity = 0f
    private var bubbleStroke = Stroke(1f)
    private var bubbleDensity = 0f

    private fun bubbleStrokeFor(density: Float): Stroke {
        if (density != bubbleDensity) {
            bubbleDensity = density
            bubbleStroke = Stroke(width = 1.6f * density)
        }
        return bubbleStroke
    }

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
        val padX = 4f * scope.density
        val padY = 1.5f * scope.density
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
        const val LABEL_ROOM = 8
        const val MATRIX_VALUES = 9

        /** 立て札は奥行きを手前へずらして並べてある（[Ent.depth]）。画面に映る範囲を切り出すとき、そのぶん広げる。 */
        const val SIGN_DEPTH_SPAN = 12f
        val CORNERS = floatArrayOf(-2f, -2f, 2f, -2f, -2f, 2f, 2f, 2f)
        val RAILS = floatArrayOf(18f, 36f)
        val LOCK_TREES = floatArrayOf(-1.2f, -1.2f, 1.2f, -1.2f, -1.2f, 1.2f, 1.2f, 1.2f)
        val RIVER_BANK_STROKE = Stroke(5.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val RIVER_STROKE = Stroke(3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val RIVER_FOAM = Color.White.copy(alpha = 0.45f)
        val DASH = PathEffect.dashPathEffect(floatArrayOf(1.6f, 4.4f), 0f)
        val FOAM_STROKE = Stroke(0.5f, cap = StrokeCap.Round, pathEffect = DASH)
        val GOLD = Color(0xFFC9A227)
        val LAWN = Color(0xFF5C9944)
        val LAWN_SPRING = Color(0xFF6DBA55)
        val LAWN_AUTUMN = Color(0xFF8E8A3A)
        val LAWN_WINTER = Color(0xFFE3EBF0)
        val WINTER_BG = Color(0xFF2C3B49)
        const val SEASON_COUNT = 26

        class SeasonParticle(
            val color: Color,
            val radiusDp: Float,
            val speed: Float,
            val sway: Float,
            val rising: Boolean = false,
        )

        val SEASON_PARTICLES =
            mapOf(
                "spring" to SeasonParticle(Color(0xCCFFC1D3), 2.6f, 0.9f, 1.3f),
                "sakura" to SeasonParticle(Color(0xCCFFC1D3), 2.6f, 0.9f, 1.3f),
                "autumn" to SeasonParticle(Color(0xDDE0792A), 2.8f, 1.1f, 1.8f),
                "winter" to SeasonParticle(Color(0xEEFFFFFF), 2.2f, 0.8f, 0.9f),
                "ice" to SeasonParticle(Color(0xEEFFFFFF), 2.2f, 0.8f, 0.9f),
                "gold" to SeasonParticle(Color(0xCCFFE082), 1.9f, 0.4f, 2.2f),
                "dragon" to SeasonParticle(Color(0xCCFF8A2A), 1.9f, 0.9f, 1.6f, rising = true),
            )
        val STREET_DIRT = Color(0xFF8E7B57)
        val STREET_COBBLE = Color(0xFF9A9486)
        val RIVER = Color(0xFF3E7FC4)
        val RIVER_BANK = Color(0xFFC9B88A)
        val FOG = Color(0xFF0F1A33)
        val PILL_STYLE = TextStyle(fontFamily = CrownFonts.Numeric, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        val BUBBLE_RIM = Color(0xFF7FB2E5)
        const val BUBBLE_ICON = 1.5f
        val SIGN_STYLE = TextStyle(fontFamily = CrownFonts.Display, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}
