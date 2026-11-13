package com.ttmrase.crownfall.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.SkinCategory
import com.ttmrase.crownfall.core.map.MapGenerator
import com.ttmrase.crownfall.core.map.Terrain
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.core.march.MailBox
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.skin.Skins
import com.ttmrase.crownfall.ui.common.LocalClock
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.kit.GameButtonSize
import com.ttmrase.crownfall.ui.kit.GameOutlinedButton
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphBitmaps
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.rememberGlyphBitmaps
import com.ttmrase.crownfall.ui.territory.AMBIENT_STILL_SECONDS
import com.ttmrase.crownfall.ui.territory.FramePacer
import com.ttmrase.crownfall.ui.territory.SpriteCache
import com.ttmrase.crownfall.ui.territory.ambientSeconds
import com.ttmrase.crownfall.ui.territory.rememberAmbientFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

private const val BASE_TILE_DP = 28
private const val MIN_SCALE = 0.4f
private const val MAX_SCALE = 4f
private const val HALF = 0.5f
private const val GRID_MIN_TILE_PX = 40f
private const val GRID_ALPHA = 0.12f

private val SELECT = Color(0xFFFFEB3B)

/** ワールドマップ画面（T4.4）。ズーム / パンできる Canvas。可視範囲のタイルだけ描き、タップでタイル詳細を開く。 */
@Composable
fun MapScreen(
    session: GameSession,
    data: GameData,
    snackbarHostState: SnackbarHostState,
    onOpenMail: () -> Unit,
) {
    val state by session.collectCalmState()
    val s = state ?: return
    val seed = s.map.seed
    val world by produceState<WorldMap?>(initialValue = null, key1 = seed) {
        value = withContext(Dispatchers.Default) { MapGenerator.cached(seed, data.mapRules) }
    }
    val w = world
    if (w == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.map_loading)) }
    } else {
        MapContent(session, data, snackbarHostState, s, w, onOpenMail)
    }
}

@Composable
private fun MapContent(
    session: GameSession,
    data: GameData,
    snackbarHostState: SnackbarHostState,
    s: GameState,
    world: WorldMap,
    onOpenMail: () -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val basePx = with(density) { BASE_TILE_DP.dp.toPx() }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offX by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var offY by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    // 行軍の位置は描画フェーズで時計を読む（再構図せず、再描画だけ）
    val clock = LocalClock.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var selected by remember { mutableStateOf<TileRef?>(null) }
    var formation by remember { mutableStateOf<Pair<MarchKind, TileRef>?>(null) }
    var marchesOpen by remember { mutableStateOf(false) }
    var formationError by remember { mutableStateOf<String?>(null) }
    var marchError by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val center = data.mapRules.center

    fun centerOnCastle() {
        val tile = basePx * scale
        offX = viewSize.width / 2f - (center.x + HALF) * tile
        offY = viewSize.height / 2f - (center.y + HALF) * tile
    }
    LaunchedEffect(viewSize) {
        if (viewSize != IntSize.Zero && (offX.isNaN() || offY.isNaN())) centerOnCastle()
    }
    val transformState =
        rememberTransformableState { zoomChange, panChange, _ ->
            if (viewSize == IntSize.Zero || offX.isNaN() || offY.isNaN()) return@rememberTransformableState
            val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
            val k = next / scale
            val cx = viewSize.width / 2f
            val cy = viewSize.height / 2f
            val mapPx = world.size * basePx * next
            // 地図の端が画面の端より内側へ入らない（外の空白を見せない。地図が画面より小さいときは中央に置く）
            offX = clampOffset((cx - (cx - offX) * k + panChange.x), viewSize.width.toFloat(), mapPx)
            offY = clampOffset((cy - (cy - offY) * k + panChange.y), viewSize.height.toFloat(), mapPx)
            scale = next
        }
    val onTap by rememberUpdatedState<(Offset) -> Unit> { pos ->
        val tile = basePx * scale
        if (!offX.isNaN() && !offY.isNaN()) {
            val x = floor((pos.x - offX) / tile).toInt()
            val y = floor((pos.y - offY) / tile).toInt()
            selected = if (world.inBounds(x, y)) TileRef(x, y) else null
        }
    }

    // 地面のキャッシュ（マップ画面を閉じたら捨てる）と、毎フレームの再描画の合図
    val cacheScope = remember { MapChunkCache.newScope() }
    DisposableEffect(cacheScope) { onDispose { cacheScope.cancel() } }
    // 城内と共有のスプライト（拠点・資源地・野営地・森の木）。画面を開いたとき先読みする
    val sprites = remember { SpriteCache.shared(context) }
    LaunchedEffect(sprites) {
        sprites.preload(MapArt.BAKE_SPRITES.toList())
        sprites.preload(MapLive.spriteIdsFor(MAP_SPRITE_TIERS))
    }
    val chunks = remember(world) { MapChunkCache(world, s.map.seed, cacheScope, sprites) }
    // 動きのある部分（雲・旗・焚き火・川の光・行軍）は画面のフレームごとに描き直す（街の画面と同じ合図。重い端末では
    // FramePacer が間合いを落とす）。端末のアニメーションが無効なら合図を出さず、静止画になる
    val animate = animationsEnabled()
    val pacer = remember { FramePacer() }
    val frame = rememberAmbientFrame(pacer, animate)
    // 川の折れ線（光を流す）と行軍のなめらかな位置。どちらも描画のたびには作らない
    val river = remember(chunks) { MapLive.flatten(chunks.riverLine()) }
    val track = remember { MarchTrack() }
    val fogLv = castleLevel(s).toInt()
    val fog =
        remember(world, fogLv) {
            MapLive.bakeFog(world, data.mapRules.center.x to data.mapRules.center.y, FOG_BASE + FOG_PER_LV * fogLv)
        }
    val measurer = rememberTextMeasurer()
    val glyphs = rememberGlyphBitmaps(20.dp)
    val equipped = Skins.equipped(s, data)
    val style =
        MapStyle(
            castleTheme = equipped[SkinCategory.CASTLE]?.theme,
            territoryTheme = equipped[SkinCategory.TERRITORY]?.theme,
            trailTheme = equipped[SkinCategory.TRAIL]?.theme,
        )
    val dayNight = DayNight.of(MapPrefs.dayNight(context))
    // 拠点の見た目の段階は、状態が変わったとき（再構図）にだけ求める（描画のたびに並べ替えない）
    val tier = castleTier(s)
    val tiers = remember(world, s, data) { MapLive.structureTiers(world, s, data, tier) }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    // 地図は毎フレーム描き直す。自分の層に分けて、上に重ねた HUD や下部ナビまで描き直させない
                    .graphicsLayer()
                    .onSizeChanged { viewSize = it }
                    .transformable(transformState)
                    .pointerInput(Unit) { detectTapGestures { onTap(it) } },
        ) {
            if (frame.longValue < 0L || chunks.version < 0 || sprites.version < 0) return@Canvas
            if (!offX.isNaN() && !offY.isNaN()) {
                val startNs = System.nanoTime()
                val nowMs = startNs / NANOS_PER_MS
                val now = clock?.value ?: s.nowEpochSec
                track.update(s.marches, now, nowMs, data, animate)
                drawWorld(
                    world, chunks, fog, sprites, s, data, basePx * scale, offX, offY, selected, style, dayNight,
                    glyphs, tiers, river, track, now,
                    if (animate) {
                        ambientSeconds(
                            nowMs,
                        )
                    } else {
                        AMBIENT_STILL_SECONDS
                    },
                    animate,
                )
                pacer.reportCost(System.nanoTime() - startNs)
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GameOutlinedButton(onClick = { centerOnCastle() }, size = GameButtonSize.Small) {
                GlyphIcon(Glyph.NAV_CITY, size = 20.dp)
                Text(stringResource(R.string.screen_city))
            }
            GameOutlinedButton(onClick = { marchesOpen = true }, size = GameButtonSize.Small) {
                GlyphIcon(Glyph.FLAG, size = 20.dp)
                Text(stringResource(R.string.map_marches_button, s.marches.size, Marching.slots(s, data)))
            }
            val unread = MailBox.unread(s)
            GameOutlinedButton(onClick = onOpenMail, size = GameButtonSize.Small) {
                GlyphIcon(Glyph.MAIL, size = 20.dp)
                Text(
                    if (unread > 0) {
                        stringResource(R.string.map_mail_button_unread, unread)
                    } else {
                        stringResource(R.string.map_mail_button)
                    },
                )
            }
        }
    }

    selected?.let { ref ->
        TileSheet(
            state = s,
            world = world,
            data = data,
            tile = ref,
            onAction = { kind ->
                formationError = null
                formation = kind to ref
                selected = null
            },
            onDismiss = { selected = null },
        )
    }
    formation?.let { (kind, ref) ->
        FormationSheet(
            state = s,
            data = data,
            kind = kind,
            target = ref,
            error = formationError,
            sending = sending,
            onSend = { troops, heroes ->
                if (!sending) {
                    sending = true
                    scope.launch {
                        val result = session.dispatch { Actions.startMarch(it, kind, ref, troops, heroes, data) }
                        if (result is ActionResult.Failure) {
                            formationError = failureMessage(context, result.reason)
                        } else {
                            formation = null
                        }
                        sending = false
                    }
                }
            },
            onDismiss = { formation = null },
        )
    }
    if (marchesOpen) {
        MarchListSheet(
            state = s,
            data = data,
            error = marchError,
            onRecall = { id ->
                scope.launch {
                    val result = session.dispatch { Actions.recallMarch(it, id, data) }
                    marchError = (result as? ActionResult.Failure)?.let { failureMessage(context, it.reason) }
                }
            },
            onDismiss = { marchesOpen = false },
        )
    }
}

/**
 * 可視範囲だけを描く: キャッシュ済みの地面 → 領土 → 川と湖の光 → 霧 → 自城の脈打つ輪 → 拠点・資源・野営地・行軍 →
 * 雲とその影 → 鳥 → 昼夜の色味。[seconds] は飾りの時計、[animate] が false なら静止画（鳥は出さない）。
 */
private fun DrawScope.drawWorld(
    world: WorldMap,
    chunks: MapChunkCache,
    fog: androidx.compose.ui.graphics.ImageBitmap,
    sprites: SpriteCache,
    s: GameState,
    data: GameData,
    tile: Float,
    offX: Float,
    offY: Float,
    selected: TileRef?,
    style: MapStyle,
    dayNight: DayNight,
    glyphs: GlyphBitmaps,
    tiers: IntArray,
    river: FloatArray,
    track: MarchTrack,
    now: Long,
    seconds: Float,
    animate: Boolean,
) {
    val x0 = floor(-offX / tile).toInt().coerceAtLeast(0)
    val y0 = floor(-offY / tile).toInt().coerceAtLeast(0)
    val x1 = ceil((size.width - offX) / tile).toInt().coerceAtMost(world.size - 1)
    val y1 = ceil((size.height - offY) / tile).toInt().coerceAtMost(world.size - 1)

    // 地面（8×8 のチャンク。焼けていないあいだは地形の平らな色）
    val res = MapChunkCache.resolutionFor(tile)
    val chunkPx = tile * MapArt.CHUNK
    for (cy in (y0 / MapArt.CHUNK)..(y1 / MapArt.CHUNK)) {
        for (cx in (x0 / MapArt.CHUNK)..(x1 / MapArt.CHUNK)) {
            val dst = Offset(offX + cx * chunkPx, offY + cy * chunkPx)
            val img = chunks.get(cx, cy, res)
            if (img != null) {
                drawImage(
                    img,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(dst.x.toInt(), dst.y.toInt()),
                    dstSize = IntSize(ceil(chunkPx).toInt() + 1, ceil(chunkPx).toInt() + 1),
                )
            } else {
                for (ty in 0 until MapArt.CHUNK) for (tx in 0 until MapArt.CHUNK) {
                    val x = cx * MapArt.CHUNK + tx
                    val y = cy * MapArt.CHUNK + ty
                    if (world.inBounds(x, y)) {
                        drawRect(
                            flatColor(world.terrainAt(x, y)),
                            Offset(offX + x * tile, offY + y * tile),
                            Size(tile + 1f, tile + 1f),
                        )
                    }
                }
            }
        }
    }
    if (tile >= GRID_MIN_TILE_PX) {
        for (x in x0..x1 + 1) drawLine(
            Color.Black.copy(alpha = GRID_ALPHA),
            Offset(offX + x * tile, offY + y0 * tile),
            Offset(offX + x * tile, offY + (y1 + 1f) * tile),
        )
        for (y in y0..y1 + 1) drawLine(
            Color.Black.copy(alpha = GRID_ALPHA),
            Offset(offX + x0 * tile, offY + y * tile),
            Offset(offX + (x1 + 1f) * tile, offY + y * tile),
        )
    }
    val center = data.mapRules.center
    val castleCenter = Offset(offX + (center.x + HALF) * tile, offY + (center.y + HALF) * tile)
    with(MapLive) {
        drawTerritory(castleCenter, tile, style.territoryTheme, seconds)
        drawRiverShimmer(river, tile, offX, offY, seconds, y0, y1)
        drawWaterGlints(world, x0, y0, x1, y1, tile, offX, offY, seconds)
        drawFog(fog, x0, y0, x1, y1, tile, offX, offY)
        if (center.x in x0..x1 && center.y in y0..y1) {
            drawCastlePulse(
                castleCenter.x,
                castleCenter.y + tile * CASTLE_GROUND,
                tile,
                pulseColor(style.castleTheme),
                seconds,
            )
        }
        drawStructures(world, tile, offX, offY, style.castleTheme, tiers, sprites, seconds, x0, y0, x1, y1)
        drawNodes(s, tile, offX, offY, seconds, glyphs, sprites, x0, y0, x1, y1)
        drawCamps(s, tile, offX, offY, seconds, sprites, x0, y0, x1, y1)
        drawMarches(s, data, track, tile, offX, offY, seconds, style.trailTheme)
    }
    if (selected != null && selected.x in x0..x1 && selected.y in y0..y1) {
        drawRect(
            SELECT,
            Offset(offX + selected.x * tile, offY + selected.y * tile),
            Size(tile, tile),
            style = SELECT_STROKE,
        )
    }
    with(MapLive) {
        drawClouds(offX, offY, seconds, tile)
        if (animate) drawBirds(seconds)
        drawDayNight(dayNight, (((now + s.settings.utcOffsetSec) / SECONDS_PER_HOUR) % HOURS_PER_DAY).toInt())
    }
}

private val SELECT_STROKE = Stroke(width = 4f)
private const val NANOS_PER_MS = 1_000_000L

/** 拠点のスプライトの足元（タイルの中心から下へ。[MapLive] の拠点の絵と同じ）。 */
private const val CASTLE_GROUND = 0.3f

/** 地図の端が画面の端を越えないように [off] を収める。地図 [mapPx] が画面 [view] より小さければ中央に置く。 */
internal fun clampOffset(
    off: Float,
    view: Float,
    mapPx: Float,
): Float = if (mapPx <= view) (view - mapPx) / 2f else off.coerceIn(view - mapPx, 0f)

/** 城の見た目の段階（Lv1-5 = 1 … Lv21-25 = 5）。 */
private fun castleTier(s: GameState): Int = ((castleLevel(s).toInt() - 1) / CASTLE_TIER_SPAN + 1).coerceIn(1, 5)

private const val CASTLE_TIER_SPAN = 5
private const val MAP_SPRITE_TIERS = 5

private fun castleLevel(s: GameState): Float =
    (s.slots.firstOrNull { it.type == com.ttmrase.crownfall.core.model.BuildingType.CASTLE }?.level ?: 1).toFloat()

private fun flatColor(t: Terrain): Color =
    when (t) {
        Terrain.PLAIN -> Color(0xFF6FA35A)
        Terrain.FOREST -> Color(0xFF2F6B3A)
        Terrain.MOUNTAIN -> Color(0xFF8A8579)
        Terrain.WATER -> Color(0xFF3F7FBF)
    }

private const val FOG_BASE = 24f
private const val FOG_PER_LV = 1.5f
private const val SECONDS_PER_HOUR = 3600L
private const val HOURS_PER_DAY = 24L
