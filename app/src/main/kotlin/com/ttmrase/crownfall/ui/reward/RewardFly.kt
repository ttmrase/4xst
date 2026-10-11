package com.ttmrase.crownfall.ui.reward

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.glyph
import kotlin.math.min

/** 飛んでいく先（HUD の資源・ジェムの表示位置）。 */
enum class FlyTarget {
    FOOD,
    WOOD,
    STONE,
    GOLD,
    GEMS,
    ;

    val glyph: String
        get() =
            when (this) {
                FOOD -> ResourceType.FOOD.glyph()
                WOOD -> ResourceType.WOOD.glyph()
                STONE -> ResourceType.STONE.glyph()
                GOLD -> ResourceType.GOLD.glyph()
                GEMS -> GEM_GLYPH
            }

    companion object {
        const val GEM_GLYPH = "💎"

        fun of(type: ResourceType): FlyTarget =
            when (type) {
                ResourceType.FOOD -> FOOD
                ResourceType.WOOD -> WOOD
                ResourceType.STONE -> STONE
                ResourceType.GOLD -> GOLD
            }
    }
}

internal class Flight(
    val from: Offset,
    val target: FlyTarget,
    val startMs: Long,
    val durationMs: Long,
    val lift: Float,
    val sway: Float,
    /** 資源以外のアイコン（機能の解放など）を飛ばすときの絵文字と行き先。null なら [target] の HUD へ。 */
    val glyph: String? = null,
    val dest: Offset? = null,
)

/**
 * 獲得アニメの状態（M11 T11.2）。画面のどこからでも [fly] で「ここから HUD の資源へ」アイコンを飛ばす。
 * 飛ばす先の位置は HUD が [flyTarget] で登録する。アニメーションを削除した端末では何も飛ばさない（[enabled]）。
 * ポインタ入力は受けない（描画だけ）。
 */
@Stable
class RewardFlyState {
    internal val targets = mutableStateMapOf<FlyTarget, Offset>()
    internal val arrivals = mutableStateMapOf<FlyTarget, Long>()
    internal val flights = mutableStateListOf<Flight>()
    private var seq = 0L
    private var lastSfx = 0L

    /** false なら [fly] は何もしない（「アニメーションを削除」）。 */
    var enabled: Boolean = true

    /** 描画層（[RewardFlyLayer]）が組み込まれているときだけ true。無いのに飛ばすと溜まり続けるので無視する。 */
    internal var layerAttached: Boolean = false

    /** HUD の行き先が無い画面（城以外）で、代わりに飛んでいく先（下のナビの「城」タブの中心。ウィンドウ座標）。 */
    var fallbackTarget: Offset? = null

    /** 最後にタップされた位置（ウィンドウ座標）。受け取りボタンから飛ばすときの出発点。 */
    var lastTap: Offset = Offset.Unspecified

    /** 画面上（ウィンドウ座標）の [from] から、[target] へ [count] 個のアイコンを飛ばす。 */
    fun fly(
        from: Offset,
        target: FlyTarget,
        count: Int = DEFAULT_COUNT,
    ) {
        if (!enabled || !layerAttached || count <= 0) return
        val now = System.currentTimeMillis()
        val n = count.coerceIn(MIN_ICONS, MAX_ICONS)
        repeat(n) { i ->
            seq++
            val jitter = ((seq * 37) % 100) / 100f
            flights.add(
                Flight(
                    from = from,
                    target = target,
                    startMs = now + i * STAGGER_MS,
                    durationMs = FLIGHT_MS + (jitter * FLIGHT_JITTER_MS).toLong(),
                    lift = LIFT_MIN + jitter * LIFT_RANGE,
                    sway = (jitter - 0.5f) * SWAY,
                ),
            )
        }
    }

    /** 絵文字 [glyph] を、画面上の [from] から [to]（ウィンドウ座標）へ 1 つ飛ばす（機能の解放でタブへ向かうアイコンなど）。 */
    fun flyGlyph(
        from: Offset,
        to: Offset,
        glyph: String,
    ) {
        if (!enabled || !layerAttached) return
        seq++
        val jitter = ((seq * 37) % 100) / 100f
        flights.add(
            Flight(
                from = from,
                target = FlyTarget.GEMS,
                startMs = System.currentTimeMillis(),
                durationMs = FLIGHT_MS + (jitter * FLIGHT_JITTER_MS).toLong(),
                lift = LIFT_MIN + jitter * LIFT_RANGE,
                sway = (jitter - 0.5f) * SWAY,
                glyph = glyph,
                dest = to,
            ),
        )
    }

    /** 資源と [gems] の獲得を、種類ごとに飛ばす。量が多いほど個数が増える（5〜12 個）。 */
    fun flyAll(
        from: Offset,
        resources: Resources,
        gems: Long = 0,
    ) {
        ResourceType.entries.forEach { t -> if (resources[t] > 0) fly(from, FlyTarget.of(t), iconsFor(resources[t])) }
        if (gems > 0) fly(from, FlyTarget.GEMS, iconsFor(gems * GEM_WEIGHT))
    }

    internal fun playArrival(
        context: android.content.Context,
        now: Long,
    ) {
        if (now - lastSfx < SFX_GAP_MS) return
        lastSfx = now
        Sfx.play(context, SfxId.COIN)
    }

    companion object {
        const val DEFAULT_COUNT = 8
        const val MIN_ICONS = 5
        const val MAX_ICONS = 12
        private const val STAGGER_MS = 45L
        private const val FLIGHT_MS = 650L
        private const val FLIGHT_JITTER_MS = 200L
        private const val LIFT_MIN = 90f
        private const val LIFT_RANGE = 120f
        private const val SWAY = 140f
        private const val SFX_GAP_MS = 90L
        private const val GEM_WEIGHT = 50L

        /** 獲得量から飛ばす個数（5〜12）。 */
        fun iconsFor(amount: Long): Int =
            when {
                amount >= 5000 -> MAX_ICONS
                amount >= 1000 -> 10
                amount >= 200 -> 8
                amount >= 50 -> 6
                else -> MIN_ICONS
            }
    }
}

/** 既定は何も描かない空の状態（プレビューやテストで host が無くても落ちない）。 */
val LocalRewardFly = compositionLocalOf { RewardFlyState() }

/** HUD の資源・ジェムの表示にこれを付けると、飛んできたアイコンの行き先になり、到着のたびにぽんと弾む。 */
@Composable
fun Modifier.flyTarget(target: FlyTarget): Modifier {
    val fly = LocalRewardFly.current
    val scale = remember { Animatable(1f) }
    val arrived = fly.arrivals[target] ?: 0L
    DisposableEffect(fly, target) { onDispose { fly.targets.remove(target) } }
    LaunchedEffect(arrived) {
        if (arrived > 0L) {
            scale.animateTo(BOUNCE, tween(BOUNCE_UP_MS))
            scale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
        }
    }
    return this
        .onGloballyPositioned { fly.targets[target] = it.boundsInWindow().center }
        .graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        }
}

private const val BOUNCE = 1.25f
private const val BOUNCE_UP_MS = 70
private const val ICON_SP = 22
private const val FALLBACK_TOP_DP = 48f
private val ICON_STYLE = TextStyle(fontSize = ICON_SP.sp)

/**
 * 獲得アニメの描画層。アプリの一番上に全画面で重ねる。ポインタ入力は受けない。
 * 放物線（出発点 → 持ち上がる → HUD）で飛ばし、到着で HUD を弾ませてコインの音を鳴らす。
 */
@Composable
fun RewardFlyLayer(state: RewardFlyState) {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    var origin by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableLongStateOf(0L) }
    state.enabled = animationsEnabled()
    DisposableEffect(state) {
        state.layerAttached = true
        onDispose {
            state.layerAttached = false
            state.flights.clear()
        }
    }
    val active = state.flights.isNotEmpty()
    LaunchedEffect(active) {
        while (state.flights.isNotEmpty()) {
            withFrameMillis { ms -> frame = ms }
            val now = System.currentTimeMillis()
            val done = state.flights.filter { now >= it.startMs + it.durationMs }
            if (done.isNotEmpty()) {
                done.forEach { if (it.dest == null) state.arrivals[it.target] = now }
                if (done.any { it.dest == null }) state.playArrival(context, now)
                state.flights.removeAll(done.toSet())
            }
        }
    }
    Canvas(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() },
    ) {
        if (frame < 0L || state.flights.isEmpty()) return@Canvas
        val now = System.currentTimeMillis()
        val fallback = Offset(size.width / 2f, FALLBACK_TOP_DP * density)
        for (f in state.flights) {
            if (now < f.startMs) continue
            val t = ((now - f.startMs).toFloat() / f.durationMs).coerceIn(0f, 1f)
            val e = t * t * (3f - 2f * t)
            val from = f.from - origin
            val to = (f.dest ?: state.targets[f.target] ?: state.fallbackTarget ?: (fallback + origin)) - origin
            val ctrl = Offset((from.x + to.x) / 2f + f.sway * density, min(from.y, to.y) - f.lift * density)
            val u = 1f - e
            val p =
                Offset(
                    u * u * from.x + 2f * u * e * ctrl.x + e * e * to.x,
                    u * u * from.y + 2f * u * e * ctrl.y + e * e * to.y,
                )
            val layout = measurer.measure(f.glyph ?: f.target.glyph, ICON_STYLE)
            val alpha = if (t > FADE_START) 1f - (t - FADE_START) / (1f - FADE_START) * 0.5f else 1f
            drawText(
                layout,
                topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f),
                alpha = alpha,
            )
        }
    }
}

private const val FADE_START = 0.85f

/**
 * [block] を [GameSession.dispatch] し、成功したら増えた資源・ジェムを、最後にタップした位置から HUD へ飛ばす
 * （タップ位置が分からなければ画面中央の少し下から）。増減は行動の前後の差だけなので、生産の分は混ざらない。
 */
suspend fun GameSession.dispatchFly(
    fly: RewardFlyState,
    block: (GameState) -> ActionResult,
): ActionResult {
    var gain = Resources.ZERO
    var gems = 0L
    val result =
        dispatch { cur ->
            block(cur).also { r ->
                if (r is ActionResult.Success) {
                    gain =
                        ResourceType.entries.fold(Resources.ZERO) { acc, t ->
                            acc.with(t, maxOf(0L, r.state.resources[t] - cur.resources[t]))
                        }
                    gems = maxOf(0L, r.state.gems - cur.gems)
                }
            }
        }
    if (result is ActionResult.Success) fly.flyAll(fly.originOrDefault(), gain, gems)
    return result
}

internal fun RewardFlyState.originOrDefault(): Offset =
    if (lastTap.isSpecified) lastTap else Offset(DEFAULT_X, DEFAULT_Y)

private const val DEFAULT_X = 540f
private const val DEFAULT_Y = 1400f

/**
 * ルートに付けて、最後のタップ位置を記録し、タップのたびに [onTap] を呼ぶ（ポインタ入力は消費しない）。
 * 「タップ」は、ほとんど動かさずに短く離したもの（スクロールやドラッグでは呼ばない）。
 */
fun Modifier.trackTaps(
    fly: RewardFlyState,
    windowOrigin: () -> Offset,
    onTap: () -> Unit = {},
): Modifier =
    pointerInput(fly) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            var downAt = Offset.Unspecified
            var downMs = 0L
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                if (change.pressed && !change.previousPressed) {
                    downAt = change.position
                    downMs = System.currentTimeMillis()
                    fly.lastTap = change.position + windowOrigin()
                } else if (!change.pressed && change.previousPressed && downAt.isSpecified) {
                    val quick = System.currentTimeMillis() - downMs < TAP_MAX_MS
                    if (quick && (change.position - downAt).getDistance() <= slop) onTap()
                    downAt = Offset.Unspecified
                }
            }
        }
    }

private const val TAP_MAX_MS = 350L
