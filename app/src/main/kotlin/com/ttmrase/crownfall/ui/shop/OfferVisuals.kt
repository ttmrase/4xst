package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.PackContents
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.portrait.PortraitView
import com.ttmrase.crownfall.ui.rarityColor
import com.ttmrase.crownfall.ui.theme.CrownFonts
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val FOIL_MS = 2400
private const val SHAKE_MS = 160
private const val SHAKE_DEG = 4f
private const val ROLL_MS = 1100
private const val TAU = (2 * PI).toFloat()
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val DIGIT_PAIR = 100

internal val FoilGold = Color(0xFFFFD86B)

/** 購入ボタンの赤（テーマの error は暗色テーマでは淡いピンクなので、白い文字が読める赤を使う）。 */
internal val BuyRed = Color(0xFFD7263D)
internal val FoilDark = Color(0xFF9C6B00)

/** 金箔の縁取り。光沢が斜めに流れ続ける（購入カードの「特別感」）。 */
fun Modifier.goldFoil(): Modifier =
    composed {
        val transition = rememberInfiniteTransition(label = "foil")
        val shift by transition.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(tween(FOIL_MS, easing = LinearEasing), RepeatMode.Restart),
            label = "foilShift",
        )
        drawWithContent {
            drawContent()
            val w = size.width
            val h = size.height
            val brush =
                Brush.linearGradient(
                    colors = listOf(FoilDark, FoilGold, Color.White, FoilGold, FoilDark),
                    start = Offset(w * (shift - 0.5f), 0f),
                    end = Offset(w * (shift + 0.5f), h),
                )
            drawRoundRect(
                brush = brush,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                style = Stroke(width = 3.dp.toPx()),
            )
        }
    }

/** 小刻みに揺れるバッジ（目を引く演出）。 */
fun Modifier.shaking(): Modifier =
    composed {
        val transition = rememberInfiniteTransition(label = "shake")
        val deg by transition.animateFloat(
            initialValue = -SHAKE_DEG,
            targetValue = SHAKE_DEG,
            animationSpec = infiniteRepeatable(tween(SHAKE_MS, easing = LinearEasing), RepeatMode.Reverse),
            label = "shakeDeg",
        )
        graphicsLayer { rotationZ = deg }
    }

/** 赤いリボン（「97% OFF」「1回限り！」「残り 2 個」）。右端に切れ込みのある帯で、金の細い縁と艶がある。 */
@Composable
fun Ribbon(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BuyRed,
    shake: Boolean = true,
) {
    Text(
        text,
        color = Color.White,
        fontFamily = CrownFonts.Numeric,
        fontWeight = FontWeight.Black,
        style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(Color(0x99000000), Offset(0f, 1.5f), 2f)),
        modifier =
            modifier
                .then(if (shake) Modifier.shaking() else Modifier)
                .drawBehind {
                    val d = 1.dp.toPx()
                    val notch = size.height * 0.32f
                    val shape =
                        Path().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width, 0f)
                            lineTo(size.width - notch, size.height / 2)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                    translate(0f, 1.5f * d) { drawPath(shape, Color(0x80000000)) }
                    drawPath(
                        shape,
                        Brush.verticalGradient(
                            listOf(
                                color.copy(red = (color.red + 0.25f).coerceAtMost(1f)),
                                color,
                                color.copy(red = color.red * 0.7f),
                            ),
                        ),
                    )
                    drawRect(Color.White.copy(alpha = 0.22f), size = Size(size.width - notch, size.height * 0.45f))
                    drawPath(shape, Color(0xFFFFE08A), style = Stroke(1.1f * d))
                }
                .padding(start = 9.dp, end = 15.dp, top = 3.dp, bottom = 3.dp),
    )
}

/** 定価の取り消し線から、特価へ数字が転がり落ちる。表示された瞬間に一度だけ回る。 */
@Composable
fun RollingPrice(
    listYen: Long,
    dealYen: Long,
    modifier: Modifier = Modifier,
) {
    val progress = remember(listYen, dealYen) { Animatable(0f) }
    LaunchedEffect(listYen, dealYen) { progress.animateTo(1f, tween(ROLL_MS)) }
    val shown = (listYen + (dealYen - listYen) * progress.value).toLong()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (listYen > dealYen) {
            Text(
                formatYen(listYen),
                textDecoration = TextDecoration.LineThrough,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            formatYen(shown),
            fontFamily = CrownFonts.Numeric,
            fontWeight = FontWeight.Black,
            fontSize = 30.sp,
            color = BuyRed,
        )
    }
}

/** 残り時間。桁が下からめくれ上がる（パタパタ式）。 */
@Composable
fun FlipCountdown(
    remainingSec: Long,
    modifier: Modifier = Modifier,
) {
    val h = remainingSec / SECONDS_PER_HOUR
    val m = remainingSec % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val s = remainingSec % SECONDS_PER_MINUTE
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (h > 0) {
            FlipPair(h % DIGIT_PAIR)
            Text(":", color = BuyRed, fontWeight = FontWeight.Black)
        }
        FlipPair(m)
        Text(":", color = BuyRed, fontWeight = FontWeight.Black)
        FlipPair(s)
    }
}

@Composable
private fun FlipPair(value: Long) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FlipDigit((value / 10).toInt())
        FlipDigit((value % 10).toInt())
    }
}

@Composable
private fun FlipDigit(digit: Int) {
    Box(
        modifier = Modifier.size(width = 20.dp, height = 28.dp).background(Color(0xFF1B1B22), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = digit,
            transitionSpec = { slideInVertically { -it } togetherWith slideOutVertically { it } },
            label = "digit",
        ) { d ->
            Text(
                d.toString(),
                color = Color.White,
                fontFamily = CrownFonts.Numeric,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
            )
        }
    }
}

/** 中身アイコン 1 つ分（アイコン＋レアリティの光）。 */
private class IconSpec(
    val glyph: Glyph,
    val rarity: HeroRarity,
    val label: String,
)

private fun iconSpecs(contents: PackContents): List<IconSpec> {
    val list = mutableListOf<IconSpec>()
    if (contents.gems > 0) list += IconSpec(Glyph.GEM, HeroRarity.SR, "gems")
    ResourceType.entries.forEach { type ->
        if (contents.resources[type] > 0) list += IconSpec(type.glyph(), HeroRarity.R, type.name)
    }
    contents.items.forEach { (id, _) -> list += IconSpec(Glyph.SPEEDUP, HeroRarity.R, id) }
    contents.heroShards.forEach { (id, _) -> list += IconSpec(Glyph.SHARD, HeroRarity.SSR, id) }
    if (contents.eventPoints > 0) list += IconSpec(Glyph.TROPHY, HeroRarity.SR, "event")
    if (contents.secondBuilderDays > 0) list += IconSpec(Glyph.BUILD, HeroRarity.SR, "builder")
    return list
}

/** 中身のアイコン列。それぞれがレアリティ色にぼんやり光る。 */
@Composable
fun ContentIcons(
    contents: PackContents,
    modifier: Modifier = Modifier,
) {
    val specs = remember(contents) { iconSpecs(contents) }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        specs.forEach { spec -> GlowIcon(spec.glyph, rarityColor(spec.rarity)) }
    }
}

/** 光る円形アイコン。 */
@Composable
fun GlowIcon(
    glyph: Glyph,
    glow: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "glow")
    val pulse by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "glowPulse",
    )
    Box(
        modifier =
            modifier.size(44.dp).drawBehind {
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = pulse), Color.Transparent)))
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = 30.dp)
    }
}

/**
 * 全画面オファーの主役の絵。回る光条の前に、英雄のかけら入りなら英雄のポートレート、そうでなければ金貨と宝石があふれる宝箱。
 */
@Composable
fun HeroPortrait(
    heroId: String?,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "portrait")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = TAU,
        animationSpec = infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rays",
    )
    val bob by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600), RepeatMode.Reverse),
        label = "bob",
    )
    val gold = rarityColor(HeroRarity.SSR)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier.fillMaxWidth().height(150.dp).drawBehind {
                    val c = Offset(size.width / 2f, size.height * 0.52f)
                    val unit = min(size.width, size.height)
                    val reach = unit * 1.25f
                    for (i in 0 until RAY_COUNT) {
                        val a0 = i * TAU / RAY_COUNT + spin
                        val a1 = a0 + TAU / RAY_COUNT * 0.42f
                        val ray =
                            Path().apply {
                                moveTo(c.x, c.y)
                                lineTo(c.x + cos(a0) * reach, c.y + sin(a0) * reach)
                                lineTo(c.x + cos(a1) * reach, c.y + sin(a1) * reach)
                                close()
                            }
                        drawPath(
                            ray,
                            Brush.radialGradient(listOf(gold.copy(alpha = 0.55f), Color.Transparent), c, reach),
                        )
                    }
                    drawCircle(
                        Brush.radialGradient(
                            listOf(Color.White.copy(alpha = 0.85f), gold.copy(alpha = 0.7f), Color.Transparent),
                            c,
                            unit * 0.62f,
                        ),
                        unit * 0.62f,
                        c,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (heroId != null) {
                PortraitView(heroId, heroId, size = 116.dp, rarity = HeroRarity.SSR)
            } else {
                GlyphIcon(Glyph.NAV_SHOP, Modifier.offset(y = (bob * 3).dp), size = 124.dp)
            }
        }
        val name = heroId?.let { heroNameRes(it) }
        if (name != null) {
            Text(
                stringResource(name),
                color = gold,
                fontFamily = CrownFonts.Display,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val RAY_COUNT = 12

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrown(
    center: Offset,
    width: Float,
    color: Color,
) {
    val l = center.x - width / 2f
    val r = center.x + width / 2f
    val bottom = center.y + width * 0.25f
    val top = center.y - width * 0.25f
    val path =
        Path().apply {
            moveTo(l, bottom)
            lineTo(l, top)
            lineTo(l + width * 0.25f, center.y)
            lineTo(center.x, top - width * 0.08f)
            lineTo(r - width * 0.25f, center.y)
            lineTo(r, top)
            lineTo(r, bottom)
            close()
        }
    drawPath(path, color)
    drawPath(path, Color.White.copy(alpha = 0.8f), style = Stroke(width = 3.dp.toPx()))
}

/**
 * 開いた宝箱（購入完了・報酬の演出）。金貨と宝石があふれる宝箱が弾んで現れ、後ろで金の光条がゆっくり回る。
 */
@Composable
fun TreasureChest(modifier: Modifier = Modifier) {
    val animate = animationsEnabled()
    val pop = remember { Animatable(if (animate) 0.3f else 1f) }
    LaunchedEffect(Unit) { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
    val spin by rememberInfiniteTransition(label = "chest").animateFloat(
        initialValue = 0f,
        targetValue = TAU,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rays",
    )
    val gold = rarityColor(HeroRarity.SSR)
    Box(
        modifier =
            modifier.size(150.dp).drawBehind {
                val c = Offset(size.width / 2f, size.height / 2f)
                val reach = size.minDimension * 0.85f
                val turn = if (animate) spin else 0f
                for (i in 0 until RAY_COUNT) {
                    val a0 = i * TAU / RAY_COUNT + turn
                    val a1 = a0 + TAU / RAY_COUNT * 0.42f
                    val ray =
                        Path().apply {
                            moveTo(c.x, c.y)
                            lineTo(c.x + cos(a0) * reach, c.y + sin(a0) * reach)
                            lineTo(c.x + cos(a1) * reach, c.y + sin(a1) * reach)
                            close()
                        }
                    drawPath(ray, Brush.radialGradient(listOf(gold.copy(alpha = 0.6f), Color.Transparent), c, reach))
                }
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.8f), gold.copy(alpha = 0.55f), Color.Transparent),
                        c,
                        size.minDimension * 0.5f,
                    ),
                    size.minDimension * 0.5f,
                    c,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(
            Glyph.NAV_SHOP,
            modifier =
                Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
            size = 112.dp,
        )
    }
}

private const val CHEST_MS = 1500
private const val SHAKE_END = 0.4f
private const val OPEN_START = 0.6f
private const val SHAKE_FREQ = 55f
private const val CHEST_SHAKE_DEG = 7f
private const val RAYS = 12
