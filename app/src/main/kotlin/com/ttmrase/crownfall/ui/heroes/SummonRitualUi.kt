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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GameOutlinedButton
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.portrait.PortraitView
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
        GameOutlinedButton(onClick = onDone, modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp)) {
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
    // 地の光（色のついた霧が中心から広がる）と、床の魔法陣（遠近のついた二重の輪。溜めとともに広がって回る）
    drawCircle(
        Brush.radialGradient(
            listOf(tint.copy(alpha = 0.28f + 0.2f * chargeP), Color.Transparent),
            center,
            unit * 0.75f,
        ),
        unit * 0.75f,
        center,
    )
    val floor = center + Offset(0f, unit * 0.2f)
    val ringR = unit * (0.2f + 0.16f * chargeP)
    val spinA = elapsed / 900f
    scale(1f, FLOOR_SQUASH, floor) {
        drawCircle(tint.copy(alpha = 0.16f), ringR, floor)
        drawCircle(tint.copy(alpha = 0.9f), ringR, floor, style = Stroke(unit * 0.008f))
        drawCircle(Color.White.copy(alpha = 0.55f), ringR * 0.8f, floor, style = Stroke(unit * 0.004f))
        for (i in 0 until RUNES) {
            val ang = spinA + i * TAU / RUNES
            val inner = floor + Offset(cos(ang) * ringR * 0.82f, sin(ang) * ringR * 0.82f)
            val outer = floor + Offset(cos(ang) * ringR * 0.98f, sin(ang) * ringR * 0.98f)
            drawLine(Color.White.copy(alpha = 0.75f), inner, outer, unit * 0.006f)
        }
        // 輪の中の星形（反対向きに回る）
        val star = Path()
        for (i in 0 until STAR_POINTS) {
            val ang = -spinA * 0.7f + i * TAU * 2f / STAR_POINTS
            val pt = floor + Offset(cos(ang) * ringR * 0.78f, sin(ang) * ringR * 0.78f)
            if (i == 0) star.moveTo(pt.x, pt.y) else star.lineTo(pt.x, pt.y)
        }
        star.close()
        drawPath(star, tint.copy(alpha = 0.7f), style = Stroke(unit * 0.005f))
    }
    // 光の柱（床から立ち上がり、溜めるほど太く明るい）
    val beamW = unit * (0.05f + 0.16f * chargeP)
    drawRect(
        Brush.horizontalGradient(
            listOf(
                Color.Transparent,
                tint.copy(alpha = 0.5f * chargeP),
                Color.White.copy(alpha = 0.6f * chargeP),
                tint.copy(alpha = 0.5f * chargeP),
                Color.Transparent,
            ),
            center.x - beamW,
            center.x + beamW,
        ),
        Offset(center.x - beamW, 0f),
        Size(beamW * 2, floor.y),
    )
    // 渦巻く光の粒（溜め中は中心へ収束、その後は薄く周回する）。尾を 2 つ引く
    for (i in 0 until PARTICLES) {
        val a0 = i * GOLDEN
        val r0 = unit * (0.30f + 0.25f * ((i * 0.618f) % 1f))
        val r = if (chargeP < 1f) r0 * (1f - chargeP) + orbR else orbR * (1.6f + 0.4f * sin(elapsed / 300f + i))
        val a = a0 + chargeP * 5f + elapsed / 2000f
        val alpha = if (chargeP < 1f) 0.35f + 0.65f * chargeP else 0.35f
        val size = unit * 0.008f + unit * 0.006f * (i % 3)
        for (k in 0..2) {
            val ak = a - k * 0.09f
            drawCircle(
                color = (if (k == 0) Color.White else tint).copy(alpha = alpha * (1f - k * 0.33f)),
                radius = size * (1f - k * 0.25f),
                center = center + Offset(cos(ak) * r, sin(ak) * r),
            )
        }
    }
    // 立ちのぼる火花（十字の光）
    for (i in 0 until SPARKS) {
        val seed = (i * 0.37f) % 1f
        val up = ((elapsed / 1400f + seed) % 1f)
        val x = center.x + (seed - 0.5f) * unit * 0.7f + sin(up * TAU + i) * unit * 0.02f
        val y = floor.y - up * unit * 0.6f
        val s = unit * 0.012f * (1f - up)
        val c = Color.White.copy(alpha = (1f - up) * 0.9f * (0.3f + 0.7f * chargeP))
        drawLine(c, Offset(x - s, y), Offset(x + s, y), s * 0.35f)
        drawLine(c, Offset(x, y - s), Offset(x, y + s), s * 0.35f)
    }
    // 宝珠
    val pulse = 1f + 0.06f * sin(elapsed / 90f)
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White, tint, tint.copy(alpha = 0f)), center, orbR * 2.6f * pulse),
        radius = orbR * 2.6f * pulse,
        center = center,
    )
    drawCircle(color = Color.White.copy(alpha = 0.95f), radius = orbR * 0.5f, center = center)
    drawCircle(Color.White.copy(alpha = 0.5f), orbR * 1.05f * pulse, center, style = Stroke(unit * 0.004f))
    // 色が変わった瞬間に広がる輪（二重）
    if (flash >= 0 && !inBurst) {
        val p = ((elapsed - CHARGE_MS - flash * FLASH_MS).toFloat() / FLASH_MS).coerceIn(0f, 1f)
        val ringColor = orbTint(color ?: OrbColor.BLUE)
        drawCircle(
            color = ringColor.copy(alpha = 1f - p),
            radius = orbR * (1.2f + 3f * p),
            center = center,
            style = Stroke(width = unit * 0.014f),
        )
        drawCircle(
            color = Color.White.copy(alpha = (1f - p) * 0.7f),
            radius = orbR * (1.0f + 4.5f * p),
            center = center,
            style = Stroke(width = unit * 0.005f),
        )
        drawRect(ringColor.copy(alpha = (1f - p) * 0.18f))
        if (nearMissGold) drawGoldSparks(center, orbR * (1.4f + 2f * p), 1f - p)
    }
    if (inBurst) {
        val p = ((elapsed - burstStart).toFloat() / BURST_MS).coerceIn(0f, 1f)
        drawRays(center, unit, tint, 1f - p, elapsed)
        drawCircle(Color.White.copy(alpha = 1f - p), unit * (0.1f + 0.9f * p), center, style = Stroke(unit * 0.02f))
        drawRect(Color.White.copy(alpha = (1f - p) * 0.85f))
    }
}

private const val FLOOR_SQUASH = 0.36f
private const val SSR_STARS = 5
private const val RUNES = 24
private const val STAR_POINTS = 5
private const val SPARKS = 18

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
    // 根元が細く先が広い光の刃（線ではなく三角形）。1 本おきに白く抜く
    val rays = 18
    val reach = unit * 1.3f
    for (i in 0 until rays) {
        val a0 = i * TAU / rays + elapsed / 1500f
        val a1 = a0 + TAU / rays * 0.4f
        val blade =
            Path().apply {
                moveTo(center.x, center.y)
                lineTo(center.x + cos(a0) * reach, center.y + sin(a0) * reach)
                lineTo(center.x + cos(a1) * reach, center.y + sin(a1) * reach)
                close()
            }
        val c = if (i % 2 == 0) tint else Color.White
        drawPath(blade, Brush.radialGradient(listOf(c.copy(alpha = alpha * 0.9f), Color.Transparent), center, reach))
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
            val c = Offset(size.width / 2f, size.height * 0.36f)
            val unit = min(size.width, size.height)
            drawRays(c, unit, gold, 0.8f, (spin * 1500f).toLong())
            drawCircle(
                brush = Brush.radialGradient(listOf(Color.White, gold, gold.copy(alpha = 0f)), c, unit * 0.42f),
                radius = unit * 0.42f,
                center = c,
            )
            // 名前と星の後ろを暗くして読めるようにする
            drawRect(
                Brush.verticalGradient(
                    0.45f to Color.Transparent,
                    0.58f to Color(0xD9060A16),
                    0.8f to Color(0xD9060A16),
                    1f to Color.Transparent,
                ),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.summon_ssr_banner),
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        color = Color.White,
                        shadow = Shadow(Color(0xFFB8501A), Offset(0f, 3f), 14f),
                    ),
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            // 主役の絵（金の枠のポートレート）
            PortraitView(
                entry.heroId,
                heroNameRes(entry.heroId)?.let { stringResource(it) } ?: entry.heroId,
                size = 168.dp,
                modifier = Modifier.padding(top = 14.dp),
                rarity = HeroRarity.SSR,
            )
            Text(
                heroNameRes(entry.heroId)?.let { stringResource(it) } ?: entry.heroId,
                style =
                    MaterialTheme.typography.displaySmall.copy(
                        brush = Cf.GoldTextBrush,
                        shadow = Shadow(Color(0xFF04060D), Offset(0f, 3f), 6f),
                    ),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(SSR_STARS) { GlyphIcon(Glyph.STAR, size = 30.dp) }
            }
            if (entry.isNew) {
                Text(
                    stringResource(R.string.summon_new),
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.titleLarge,
                    modifier =
                        Modifier
                            .padding(top = 8.dp)
                            .gameFrame(FrameStyle.Alarm, corner = 10.dp)
                            .padding(horizontal = 14.dp, vertical = 2.dp),
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
