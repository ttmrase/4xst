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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.skin.Skins
import com.ttmrase.crownfall.ui.common.LocalClock
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.territory.SpriteCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
            offX = (cx - (cx - offX) * k + panChange.x).coerceIn(cx - mapPx, cx)
            offY = (cy - (cy - offY) * k + panChange.y).coerceIn(cy - mapPx, cy)
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
    // 動きのある部分（焚き火・光・行軍）は 24Hz で描き直す（街の画面と同じ。ドラッグ・ズーム中は操作のたびに即時に描く）
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameMillis { tick = it }
            delay(TICK_GAP_MS)
        }
    }
    val fogLv = castleLevel(s).toInt()
    val fog =
        remember(world, fogLv) {
            MapLive.bakeFog(world, data.mapRules.center.x to data.mapRules.center.y, FOG_BASE + FOG_PER_LV * fogLv)
        }
    val measurer = rememberTextMeasurer()
    val glyphs = remember(measurer) { HashMap<ResourceType, TextLayoutResult>() }
    val equipped = Skins.equipped(s, data)
    val style =
        MapStyle(
            castleTheme = equipped[SkinCategory.CASTLE]?.theme,
            territoryTheme = equipped[SkinCategory.TERRITORY]?.theme,
            trailTheme = equipped[SkinCategory.TRAIL]?.theme,
        )
    val dayNight = DayNight.of(MapPrefs.dayNight(context))

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .transformable(transformState)
                    .pointerInput(Unit) { detectTapGestures { onTap(it) } },
        ) {
            if (tick < 0L || chunks.version < 0 || sprites.version < 0) return@Canvas
            if (!offX.isNaN() && !offY.isNaN()) {
                drawWorld(
                    world, chunks, fog, sprites, s, data, basePx * scale, offX, offY, selected, style, dayNight,
                    { t -> glyphs.getOrPut(t) { measurer.measure(t.glyph(), GLYPH_STYLE) } },
                ) {
                    clock?.value ?: s.nowEpochSec
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { centerOnCastle() }) { Text(stringResource(R.string.screen_city)) }
            FilledTonalButton(onClick = { marchesOpen = true }) {
                Text(stringResource(R.string.map_marches_button, s.marches.size, Marching.slots(s, data)))
            }
            val unread = MailBox.unread(s)
            FilledTonalButton(onClick = onOpenMail) {
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

/** 可視範囲だけを描く: キャッシュ済みの地面 → 領土 → 霧 → 拠点・資源・野営地・行軍 → 雲 → 昼夜の色味。 */
private fun DrawScope.drawWorld(
    world: WorldMap,
    chunks: MapChunkCache,
    fog: ImageBitmap,
    sprites: SpriteCache,
    s: GameState,
    data: GameData,
    tile: Float,
    offX: Float,
    offY: Float,
    selected: TileRef?,
    style: MapStyle,
    dayNight: DayNight,
    glyph: (ResourceType) -> TextLayoutResult?,
    nowEpochSec: () -> Long,
) {
    val now = nowEpochSec()
    val seconds = (System.currentTimeMillis() % LOOP_MS) / 1000f
    val x0 = floor(-offX / tile).toInt().coerceAtLeast(0)
    val y0 = floor(-offY / tile).toInt().coerceAtLeast(0)
    val x1 = ceil((size.width - offX) / tile).toInt().coerceAtMost(world.size - 1)
    val y1 = ceil((size.height - offY) / tile).toInt().coerceAtMost(world.size - 1)

    fun visible(
        x: Int,
        y: Int,
    ) = x in x0..x1 && y in y0..y1

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
        drawFog(fog, x0, y0, x1, y1, tile, offX, offY)
        drawStructures(world, tile, offX, offY, style.castleTheme, castleTier(s), sprites, ::visible)
        drawNodes(s, tile, offX, offY, seconds, glyph, sprites, ::visible)
        drawCamps(s, tile, offX, offY, seconds, sprites, ::visible)
        drawMarches(s, data, tile, offX, offY, now, seconds, style.trailTheme)
    }
    if (selected != null && visible(selected.x, selected.y)) {
        drawRect(
            SELECT,
            Offset(offX + selected.x * tile, offY + selected.y * tile),
            Size(tile, tile),
            style = Stroke(width = 4f),
        )
    }
    with(MapLive) {
        drawClouds(offX, offY, seconds, tile)
        drawDayNight(dayNight, (((now + s.settings.utcOffsetSec) / SECONDS_PER_HOUR) % HOURS_PER_DAY).toInt())
    }
}

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

private const val LOOP_MS = 600_000L

/** 24Hz 相当（フレーム待ち 約 16ms の前に挟む待ち）。 */
private const val TICK_GAP_MS = 25L
private val GLYPH_STYLE = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
private const val FOG_BASE = 24f
private const val FOG_PER_LV = 1.5f
private const val SECONDS_PER_HOUR = 3600L
private const val HOURS_PER_DAY = 24L
