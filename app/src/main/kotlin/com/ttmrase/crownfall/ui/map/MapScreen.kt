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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.map.MapGenerator
import com.ttmrase.crownfall.core.map.StructureType
import com.ttmrase.crownfall.core.map.Terrain
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.map.WorldMap
import com.ttmrase.crownfall.core.march.MailBox
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.march.MarchViews
import com.ttmrase.crownfall.core.march.Marching
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.LocalClock
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.failureMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

private const val BASE_TILE_DP = 28
private const val MIN_SCALE = 0.4f
private const val MAX_SCALE = 4f
private const val HALF = 0.5f
private const val NODE_RADIUS = 0.28f
private const val BARB_RADIUS = 0.3f
private const val MARCH_RADIUS = 0.2f
private const val GRID_MIN_TILE_PX = 40f
private const val GRID_ALPHA = 0.12f
private const val LINE_ALPHA = 0.35f

private val PLAIN = Color(0xFF6FA35A)
private val FOREST = Color(0xFF2F6B3A)
private val MOUNTAIN = Color(0xFF8A8579)
private val WATER = Color(0xFF3F7FBF)
private val CASTLE = Color(0xFFE8B730)
private val ALLY = Color(0xFF4F9DDE)
private val NPC = Color(0xFFC0392B)
private val FORT = Color(0xFF6B1F1F)
private val HOLY = Color(0xFFF5F0DC)
private val BARBARIAN = Color(0xFFB03A2E)
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

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier =
                Modifier.fillMaxSize()
                    .onSizeChanged { viewSize = it }
                    .transformable(transformState)
                    .pointerInput(Unit) { detectTapGestures { onTap(it) } },
        ) {
            if (!offX.isNaN() && !offY.isNaN()) {
                drawWorld(world, s, data, basePx * scale, offX, offY, selected) {
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

private fun terrainColor(t: Terrain): Color =
    when (t) {
        Terrain.PLAIN -> PLAIN
        Terrain.FOREST -> FOREST
        Terrain.MOUNTAIN -> MOUNTAIN
        Terrain.WATER -> WATER
    }

private fun nodeColor(t: ResourceType): Color =
    when (t) {
        ResourceType.FOOD -> Color(0xFFE6D84F)
        ResourceType.WOOD -> Color(0xFF8B5A2B)
        ResourceType.STONE -> Color(0xFFD0D0D0)
        ResourceType.GOLD -> Color(0xFFFFC107)
    }

private fun kindColor(k: MarchKind): Color =
    when (k) {
        MarchKind.GATHER -> Color(0xFF66BB6A)
        MarchKind.ATTACK -> Color(0xFFEF5350)
        MarchKind.SCOUT -> Color(0xFF29B6F6)
        MarchKind.RALLY -> Color(0xFFAB47BC)
    }

/** 可視範囲のタイルだけを描く（64×64 全体は描かない）。 */
private fun DrawScope.drawWorld(
    world: WorldMap,
    s: GameState,
    data: GameData,
    tile: Float,
    offX: Float,
    offY: Float,
    selected: TileRef?,
    nowEpochSec: () -> Long,
) {
    val now = nowEpochSec()
    val x0 = floor(-offX / tile).toInt().coerceAtLeast(0)
    val y0 = floor(-offY / tile).toInt().coerceAtLeast(0)
    val x1 = ceil((size.width - offX) / tile).toInt().coerceAtMost(world.size - 1)
    val y1 = ceil((size.height - offY) / tile).toInt().coerceAtMost(world.size - 1)

    fun px(x: Float) = offX + x * tile

    fun py(y: Float) = offY + y * tile

    fun visible(
        x: Int,
        y: Int,
    ) = x in x0..x1 && y in y0..y1
    for (y in y0..y1) {
        for (x in x0..x1) {
            drawRect(
                terrainColor(world.terrainAt(x, y)),
                Offset(px(x.toFloat()), py(y.toFloat())),
                Size(tile + 1f, tile + 1f),
            )
        }
    }
    if (tile >= GRID_MIN_TILE_PX) {
        for (x in x0..x1 + 1) drawLine(
            Color.Black.copy(alpha = GRID_ALPHA),
            Offset(px(x.toFloat()), py(y0.toFloat())),
            Offset(px(x.toFloat()), py(y1 + 1f)),
        )
        for (y in y0..y1 + 1) drawLine(
            Color.Black.copy(alpha = GRID_ALPHA),
            Offset(px(x0.toFloat()), py(y.toFloat())),
            Offset(px(x1 + 1f), py(y.toFloat())),
        )
    }
    for (st in world.structures) {
        if (!visible(st.x, st.y)) continue
        val color =
            when (st.type) {
                StructureType.PLAYER_CASTLE -> CASTLE
                StructureType.ALLY_CASTLE -> ALLY
                StructureType.NPC_CASTLE -> NPC
                StructureType.BARBARIAN_FORT -> FORT
                StructureType.HOLY_LAND -> HOLY
            }
        val inset = tile * 0.12f
        drawRect(
            color,
            Offset(px(st.x.toFloat()) + inset, py(st.y.toFloat()) + inset),
            Size(tile - inset * 2, tile - inset * 2),
        )
        drawRect(
            Color.Black,
            Offset(px(st.x.toFloat()) + inset, py(st.y.toFloat()) + inset),
            Size(tile - inset * 2, tile - inset * 2),
            style = Stroke(width = 2f),
        )
    }
    for (n in s.map.nodes) {
        if (visible(n.x, n.y)) drawCircle(nodeColor(n.type), tile * NODE_RADIUS, Offset(px(n.x + HALF), py(n.y + HALF)))
    }
    for (b in s.map.barbarians) {
        if (!visible(b.x, b.y)) continue
        val c = Offset(px(b.x + HALF), py(b.y + HALF))
        drawCircle(BARBARIAN, tile * BARB_RADIUS, c)
        drawCircle(Color.Black, tile * BARB_RADIUS, c, style = Stroke(width = 2f))
    }
    val castle = Offset(px(data.mapRules.center.x + HALF), py(data.mapRules.center.y + HALF))
    for (m in s.marches) {
        val color = kindColor(m.kind)
        drawLine(
            color.copy(alpha = LINE_ALPHA),
            castle,
            Offset(px(m.target.x + HALF), py(m.target.y + HALF)),
            strokeWidth = 3f,
        )
        val (mx, my) = MarchViews.position(m, now, data)
        val c = Offset(px(mx + HALF), py(my + HALF))
        drawCircle(Color.White, tile * MARCH_RADIUS + 2f, c)
        drawCircle(color, tile * MARCH_RADIUS, c)
    }
    if (selected != null && visible(selected.x, selected.y)) {
        drawRect(
            SELECT,
            Offset(px(selected.x.toFloat()), py(selected.y.toFloat())),
            Size(tile, tile),
            style = Stroke(width = 4f),
        )
    }
}
