package com.ttmrase.crownfall.ui.heroes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.hero.OrbColor
import com.ttmrase.crownfall.core.hero.RitualKind
import com.ttmrase.crownfall.core.hero.RitualPlan
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.SummonEntry
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.rarityColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val CHARGE_MS = 1100L
private const val FLASH_MS = 800L
private const val BURST_MS = 600L
private const val PARTICLES = 36
private const val GOLDEN = 2.399963f
private const val TAU = (2 * PI).toFloat()
private const val SHAKE_MS = 900

internal val RitualBackground = Color(0xFF0B0F1A)

internal fun orbTint(color: OrbColor): Color =
    when (color) {
        OrbColor.BLUE -> rarityColor(HeroRarity.R)
        OrbColor.PURPLE -> rarityColor(HeroRarity.SR)
        OrbColor.GOLD -> rarityColor(HeroRarity.SSR)
    }

private fun flashIndex(elapsed: Long): Int = if (elapsed < CHARGE_MS) -1 else ((elapsed - CHARGE_MS) / FLASH_MS).toInt()

/**
 * 召喚の儀式（溜め → 宝珠が青→紫→金に光る → 破裂）。結果は既に確定済みで、台本 [plan] は表示だけを決める。
 * 終了またはスキップで [onDone]。
 */
@Composable
internal fun RitualAnimation(
    plan: RitualPlan,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var elapsed by remember(plan) { mutableLongStateOf(0L) }
    val total = CHARGE_MS + plan.flashes.size * FLASH_MS + BURST_MS
    LaunchedEffect(plan) {
        Sfx.play(context, SfxId.CHARGE)
        val start = withFrameMillis { it }
        var lastStage = -1
        while (elapsed < total) {
            elapsed = withFrameMillis { it } - start
            val stage = min(flashIndex(elapsed), plan.flashes.size - 1)
            if (stage != lastStage) {
                lastStage = stage
                val color = plan.flashes.getOrNull(stage)
                when {
                    color == OrbColor.GOLD && plan.kind != RitualKind.NEAR_MISS -> Sfx.play(context, SfxId.SSR)
                    color == OrbColor.GOLD || color == OrbColor.PURPLE -> Sfx.play(context, SfxId.RARE)
                }
            }
        }
        onDone()
    }
    Box(modifier = Modifier.fillMaxSize().background(RitualBackground).pointerInput(Unit) {}) {
        Canvas(modifier = Modifier.fillMaxSize()) { drawRitual(plan, elapsed) }
        Text(
            stringResource(R.string.summon_summoning),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.Center).padding(top = 240.dp),
        )
        OutlinedButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
            Text(stringResource(R.string.summon_skip), color = Color.White)
        }
    }
}

private fun DrawScope.drawRitual(
    plan: RitualPlan,
    elapsed: Long,
) {
    val center = Offset(size.width / 2f, size.height * 0.42f)
    val unit = min(size.width, size.height)
    val chargeP = (elapsed.toFloat() / CHARGE_MS).coerceIn(0f, 1f)
    val orbR = unit * (0.05f + 0.09f * chargeP)
    val flash = flashIndex(elapsed)
    val color = plan.flashes.getOrNull(flash.coerceAtMost(plan.flashes.size - 1))
    val burstStart = CHARGE_MS + plan.flashes.size * FLASH_MS
    val inBurst = elapsed >= burstStart
    val nearMissGold = plan.kind == RitualKind.NEAR_MISS && color == OrbColor.GOLD
    val tint =
        when {
            inBurst -> rarityColor(plan.top)
            flash < 0 -> orbTint(OrbColor.BLUE)
            nearMissGold -> orbTint(OrbColor.PURPLE)
            else -> orbTint(color ?: OrbColor.BLUE)
        }
    // 渦巻く光の粒（溜め中は中心へ収束、その後は薄く周回する）
    for (i in 0 until PARTICLES) {
        val a0 = i * GOLDEN
        val r0 = unit * (0.30f + 0.25f * ((i * 0.618f) % 1f))
        val r = if (chargeP < 1f) r0 * (1f - chargeP) + orbR else orbR * (1.6f + 0.4f * sin(elapsed / 300f + i))
        val a = a0 + chargeP * 5f + elapsed / 2000f
        val alpha = if (chargeP < 1f) 0.35f + 0.65f * chargeP else 0.35f
        drawCircle(
            color = tint.copy(alpha = alpha),
            radius = unit * 0.008f + unit * 0.006f * (i % 3),
            center = center + Offset(cos(a) * r, sin(a) * r),
        )
    }
    // 宝珠
    val pulse = 1f + 0.06f * sin(elapsed / 90f)
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White, tint, tint.copy(alpha = 0f)), center, orbR * 2.2f * pulse),
        radius = orbR * 2.2f * pulse,
        center = center,
    )
    drawCircle(color = Color.White.copy(alpha = 0.9f), radius = orbR * 0.45f, center = center)
    // 色が変わった瞬間に広がる輪
    if (flash >= 0 && !inBurst) {
        val p = ((elapsed - CHARGE_MS - flash * FLASH_MS).toFloat() / FLASH_MS).coerceIn(0f, 1f)
        drawCircle(
            color = orbTint(color ?: OrbColor.BLUE).copy(alpha = 1f - p),
            radius = orbR * (1.2f + 3f * p),
            center = center,
            style = Stroke(width = unit * 0.012f),
        )
        if (nearMissGold) drawGoldSparks(center, orbR * (1.4f + 2f * p), 1f - p)
    }
    if (inBurst) {
        val p = ((elapsed - burstStart).toFloat() / BURST_MS).coerceIn(0f, 1f)
        drawRays(center, unit, tint, 1f - p, elapsed)
        drawRect(Color.White.copy(alpha = (1f - p) * 0.85f))
    }
}

private fun DrawScope.drawGoldSparks(
    center: Offset,
    radius: Float,
    alpha: Float,
) {
    val gold = orbTint(OrbColor.GOLD)
    for (i in 0 until 14) {
        val a = i * TAU / 14
        drawCircle(gold.copy(alpha = alpha), radius = 5f, center = center + Offset(cos(a) * radius, sin(a) * radius))
    }
}

private fun DrawScope.drawRays(
    center: Offset,
    unit: Float,
    tint: Color,
    alpha: Float,
    elapsed: Long,
) {
    val rays = 16
    for (i in 0 until rays) {
        val a = i * TAU / rays + elapsed / 1500f
        drawLine(
            color = tint.copy(alpha = alpha * 0.8f),
            start = center,
            end = center + Offset(cos(a) * unit, sin(a) * unit),
            strokeWidth = unit * 0.02f,
        )
    }
}

/** SSR 降臨の全画面演出（金の光線＋画面の揺れ＋バイブ）。タップまたは戻るで閉じる。 */
@Composable
internal fun SsrReveal(
    entry: SummonEntry,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val shake = remember { Animatable(1f) }
    LaunchedEffect(entry) {
        Sfx.play(context, SfxId.SSR)
        Sfx.vibrate(context, 140L)
        shake.animateTo(0f, tween(SHAKE_MS, easing = LinearEasing))
    }
    val spin by rememberInfiniteTransition(label = "ssrSpin").animateFloat(
        initialValue = 0f,
        targetValue = TAU,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val gold = rarityColor(HeroRarity.SSR)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = sin(shake.value * 70f) * 22f * shake.value
                    translationY = cos(shake.value * 53f) * 14f * shake.value
                }
                .background(RitualBackground.copy(alpha = 0.95f))
                .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height * 0.4f)
            val unit = min(size.width, size.height)
            for (i in 0 until 12) {
                val a = i * TAU / 12 + spin
                drawLine(
                    brush = Brush.radialGradient(listOf(gold, gold.copy(alpha = 0f)), c, unit),
                    start = c,
                    end = c + Offset(cos(a) * unit, sin(a) * unit),
                    strokeWidth = unit * 0.05f,
                )
            }
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, gold, gold.copy(alpha = 0f)), c, unit * 0.35f),
                radius = unit * 0.35f,
                center = c,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.summon_ssr_banner),
                color = gold,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                heroNameRes(entry.heroId)?.let { stringResource(it) } ?: entry.heroId,
                color = Color.White,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.summon_stars),
                color = gold,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (entry.isNew) {
                Text(
                    stringResource(R.string.summon_new),
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Text(
                stringResource(R.string.summon_tap_continue),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
    }
}

/**
 * 召喚の待機画面の中央に出す召喚陣。ゆっくり回る二重の輪とルーン、中央のオーブが明滅する。
 * [tint] はタブ（通常 / プレミアム）に合わせた色。
 */
@Composable
internal fun SummonCircle(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "circle")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = TAU,
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "pulse",
    )
    Canvas(modifier = modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) * 0.46f
        // 床の淡い光
        drawCircle(
            Brush.radialGradient(listOf(tint.copy(alpha = 0.25f * pulse), Color.Transparent), c, r * 1.3f),
            r * 1.3f,
            c,
        )
        val ring = Stroke(width = r * 0.025f)
        drawCircle(tint.copy(alpha = 0.9f), r, c, style = ring)
        drawCircle(tint.copy(alpha = 0.6f), r * 0.86f, c, style = Stroke(width = r * 0.012f))
        // 外周のルーン（回る）
        for (i in 0 until 24) {
            val a = i * TAU / 24 + spin
            val len = if (i % 2 == 0) 0.07f else 0.04f
            drawLine(
                tint.copy(alpha = 0.85f),
                c + Offset(cos(a) * r * 0.9f, sin(a) * r * 0.9f),
                c + Offset(cos(a) * r * (0.9f - len), sin(a) * r * (0.9f - len)),
                strokeWidth = r * 0.014f,
            )
        }
        // 逆回りの六芒星
        for (k in 0 until 2) {
            val base = if (k == 0) -spin * 1.5f else -spin * 1.5f + TAU / 6f
            for (i in 0 until 3) {
                val a0 = base + i * TAU / 3
                val a1 = base + (i + 1) * TAU / 3
                drawLine(
                    tint.copy(alpha = 0.75f),
                    c + Offset(cos(a0) * r * 0.7f, sin(a0) * r * 0.7f),
                    c + Offset(cos(a1) * r * 0.7f, sin(a1) * r * 0.7f),
                    strokeWidth = r * 0.012f,
                )
            }
        }
        drawCircle(tint.copy(alpha = 0.5f), r * 0.42f, c, style = Stroke(width = r * 0.01f))
        // 中央のオーブ
        drawCircle(
            Brush.radialGradient(listOf(Color.White, tint, tint.copy(alpha = 0f)), c, r * 0.4f * pulse),
            r * 0.4f * pulse,
            c,
        )
    }
}
