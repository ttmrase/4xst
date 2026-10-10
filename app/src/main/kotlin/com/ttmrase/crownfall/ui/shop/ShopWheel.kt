package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.LuckyWheel
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.namedString
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor

private const val FULL_TURN = 360f
private const val MIN_SPIN_DEGREES = 720f
private const val SPIN_MS = 2600
private const val TEASE_MS = 1900
private const val TEASE_PAUSE_MS = 700L
private const val EDGE_FRACTION = 0.42f
private const val GOLD = 0xFFFFC107
private const val SAT = 0.5f
private const val VALUE = 0.9f
private const val NEEDLE_HALF = 14f
private const val NEEDLE_LENGTH = 30f
private const val HUB_RADIUS = 14f

/** ホイールを [index] 番のセグメントが上の針に来る角度へ回すための目標角（現在角より 2 回転以上手前）。 */
internal fun wheelTarget(
    current: Float,
    index: Int,
    sweep: Float,
    offset: Float = 0f,
): Float {
    val base = floor(current / FULL_TURN) * FULL_TURN - index * sweep + offset
    var t = base
    while (t > current - MIN_SPIN_DEGREES) t -= FULL_TURN
    return t
}

/**
 * ラッキーホイール（1 日 1 回無料、追加は ¥120 かジェム）。結果は :core が決め、UI は「SSR の欠片のすぐ隣で止まる」
 * 惜しい演出を見せてから本当の結果へ回す（04 §2）。
 */
@Composable
fun WheelCard(
    state: GameState,
    data: GameData,
    session: GameSession,
    snackbarHostState: SnackbarHostState,
    onBuyExtra: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val segs = data.shopRules.wheel.segments
    val sweep = FULL_TURN / segs.size
    val rotation = remember { Animatable(0f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val freeLeft = LuckyWheel.freeAvailable(state)
    val extra = state.shop.wheelExtra
    val gemsCost = data.shopRules.wheel.extraGems
    val teaseText = stringResource(R.string.wheel_tease)

    fun spin(payGems: Boolean) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            when (val r = session.dispatch { Actions.spinWheel(it, payGems, data) }) {
                is ActionResult.Failure -> snackbarHostState.showReplacing(failureMessage(context, r.reason))
                is ActionResult.Success -> {
                    val result = r.events.filterIsInstance<GameEvent.WheelResult>().firstOrNull()
                    if (result != null) {
                        if (result.tease >= 0) {
                            // 惜しい演出: SSR の欠片の縁まで回して止まる
                            val edge = wheelTarget(rotation.value, result.tease, sweep, offset = -sweep * EDGE_FRACTION)
                            rotation.animateTo(edge, tween(TEASE_MS, easing = FastOutSlowInEasing))
                            message = teaseText
                            delay(TEASE_PAUSE_MS)
                        }
                        rotation.animateTo(
                            wheelTarget(rotation.value, result.segment, sweep),
                            tween(SPIN_MS, easing = LinearOutSlowInEasing),
                        )
                        message =
                            context.getString(
                                R.string.wheel_won,
                                namedString(context, "wheel_seg_", result.segmentId),
                            )
                    }
                }
            }
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.wheel_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Canvas(Modifier.size(220.dp)) {
                val dia = size.minDimension
                rotate(rotation.value, pivot = center) {
                    segs.forEachIndexed { i, seg ->
                        val color = if (seg.tease) Color(GOLD) else Color.hsv(FULL_TURN * i / segs.size, SAT, VALUE)
                        drawArc(
                            color = color,
                            startAngle = i * sweep - sweep / 2f - 90f,
                            sweepAngle = sweep,
                            useCenter = true,
                            topLeft = Offset((size.width - dia) / 2f, (size.height - dia) / 2f),
                            size = Size(dia, dia),
                        )
                    }
                }
                val needle =
                    Path().apply {
                        moveTo(center.x - NEEDLE_HALF, 0f)
                        lineTo(center.x + NEEDLE_HALF, 0f)
                        lineTo(center.x, NEEDLE_LENGTH)
                        close()
                    }
                drawPath(needle, Color.DarkGray)
                drawCircle(Color.White, radius = HUB_RADIUS, center = center)
            }
            message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            segs.forEachIndexed { i, seg ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "●",
                        color = if (seg.tease) Color(GOLD) else Color.hsv(FULL_TURN * i / segs.size, SAT, VALUE),
                        fontSize = 12.sp,
                    )
                    Text(namedString(context, "wheel_seg_", seg.id), style = MaterialTheme.typography.bodySmall)
                }
            }
            when {
                freeLeft ->
                    Button(
                        onClick = { spin(false) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().pulsing(!busy),
                    ) {
                        Text(stringResource(R.string.wheel_spin_free))
                    }
                extra > 0 ->
                    Button(onClick = { spin(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wheel_spin_extra, extra))
                    }
                else -> {
                    Button(onClick = onBuyExtra, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wheel_buy_extra, formatYen(data.shopRules.wheel.extraYen)))
                    }
                    OutlinedButton(onClick = { spin(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.wheel_spin_gems, gemsCost))
                    }
                }
            }
        }
    }
}
