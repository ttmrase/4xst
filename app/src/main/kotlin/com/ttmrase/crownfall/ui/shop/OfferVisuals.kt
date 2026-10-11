package com.ttmrase.crownfall.ui.shop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.heroNameRes
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

/** 赤いリボン（「97% OFF」「1回限り！」「残り 2 個」）。 */
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
        style = MaterialTheme.typography.labelLarge,
        modifier =
            modifier
                .then(if (shake) Modifier.shaking() else Modifier)
                .background(
                    color,
                    RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 4.dp, bottomStart = 12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
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

/** 中身アイコン 1 つ分（絵文字＋レアリティの光）。 */
private class IconSpec(
    val glyph: String,
    val rarity: HeroRarity,
    val label: String,
)

private fun iconSpecs(contents: PackContents): List<IconSpec> {
    val list = mutableListOf<IconSpec>()
    if (contents.gems > 0) list += IconSpec("💎", HeroRarity.SR, "gems")
    ResourceType.entries.forEach { type ->
        if (contents.resources[type] > 0) list += IconSpec(type.glyph(), HeroRarity.R, type.name)
    }
    contents.items.forEach { (id, _) -> list += IconSpec("🎁", HeroRarity.R, id) }
    contents.heroShards.forEach { (id, _) -> list += IconSpec("⭐", HeroRarity.SSR, id) }
    if (contents.eventPoints > 0) list += IconSpec("🏆", HeroRarity.SR, "event")
    if (contents.secondBuilderDays > 0) list += IconSpec("🔨", HeroRarity.SR, "builder")
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
    glyph: String,
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
        Text(glyph, fontSize = 24.sp)
    }
}

/** 全画面オファーの英雄ポートレート。専用の絵はないので、レアリティの後光＋シルエットで表す。 */
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
    val gold = rarityColor(HeroRarity.SSR)
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            val c = Offset(size.width / 2f, size.height * 0.52f)
            val unit = min(size.width, size.height)
            for (i in 0 until 10) {
                val a = i * TAU / 10 + spin
                drawLine(
                    color = gold.copy(alpha = 0.35f),
                    start = c,
                    end = c + Offset(cos(a) * unit, sin(a) * unit),
                    strokeWidth = unit * 0.07f,
                )
            }
            drawCircle(
                Brush.radialGradient(listOf(gold.copy(alpha = 0.9f), Color.Transparent), c, unit * 0.6f),
                unit * 0.6f,
                c,
            )
            if (heroId != null) {
                val rim = Color.White.copy(alpha = 0.85f)
                drawCircle(Color(0xFF14161F), unit * 0.17f, c + Offset(0f, -unit * 0.14f))
                drawCircle(rim, unit * 0.17f, c + Offset(0f, -unit * 0.14f), style = Stroke(width = 3.dp.toPx()))
                drawOval(
                    Color(0xFF14161F),
                    topLeft = c + Offset(-unit * 0.32f, unit * 0.06f),
                    size = androidx.compose.ui.geometry.Size(unit * 0.64f, unit * 0.5f),
                )
            } else {
                drawCrown(c, unit * 0.5f, gold)
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

/** 購入完了の宝箱。ふたが開いて金色の光があふれる。 */
@Composable
fun TreasureChest(modifier: Modifier = Modifier) {
    // 1.5 秒: 揺れる（0〜40%）→ ひびが入って光がもれる（40〜60%）→ ふたが開いて光の筋が弾ける（60〜100%）
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(CHEST_MS, easing = LinearEasing)) }
    val p = t.value
    val shake = if (p < SHAKE_END) sin(p * SHAKE_FREQ) * (1f - 0.3f * p / SHAKE_END) * SHAKE_DEG else 0f
    val crack = ((p - SHAKE_END) / (OPEN_START - SHAKE_END)).coerceIn(0f, 1f)
    val open = ((p - OPEN_START) / (1f - OPEN_START)).coerceIn(0f, 1f)
    Canvas(
        modifier =
            modifier.size(width = 140.dp, height = 110.dp).graphicsLayer {
                rotationZ = shake
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            },
    ) {
        val w = size.width
        val h = size.height
        val body = Color(0xFF8B4A1C)
        val band = FoilGold
        val glowAlpha = maxOf(open, crack * 0.35f)
        val core = Offset(w / 2f, h * 0.4f)
        drawCircle(
            Brush.radialGradient(listOf(FoilGold.copy(alpha = glowAlpha), Color.Transparent), core, w * 0.6f),
            w * 0.6f,
            core,
        )
        if (open > 0f) {
            repeat(RAYS) { i ->
                val a = (i.toFloat() / RAYS) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                val len = w * (0.25f + 0.65f * open)
                drawLine(
                    FoilGold.copy(alpha = (1f - open * 0.7f).coerceIn(0f, 1f)),
                    core,
                    Offset(core.x + kotlin.math.cos(a) * len, core.y + kotlin.math.sin(a) * len * 0.8f),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }
        }
        // 箱本体
        drawRoundRect(
            body,
            Offset(w * 0.1f, h * 0.45f),
            androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.5f),
            androidx.compose.ui.geometry.CornerRadius(8f),
        )
        drawRect(band, Offset(w * 0.1f, h * 0.45f), androidx.compose.ui.geometry.Size(w * 0.8f, h * 0.07f))
        drawRect(band, Offset(w * 0.47f, h * 0.5f), androidx.compose.ui.geometry.Size(w * 0.06f, h * 0.3f))
        // ふた（ひびの段階で少し浮き、開くほど上へ跳ね上がる）
        val lift = crack * h * 0.05f + open * h * 0.23f
        val lid =
            Path().apply {
                moveTo(w * 0.1f, h * 0.45f - lift)
                quadraticTo(w * 0.5f, h * 0.0f - lift, w * 0.9f, h * 0.45f - lift)
                close()
            }
        drawPath(lid, body)
        drawPath(lid, band, style = Stroke(width = 3.dp.toPx()))
        if (crack > 0f) {
            // ふたと箱のすきまから光がもれる
            drawRect(
                Color.White.copy(alpha = crack * 0.9f),
                Offset(w * 0.12f, h * 0.45f - lift),
                androidx.compose.ui.geometry.Size(w * 0.76f, (lift + 2f).coerceAtLeast(2f)),
            )
        }
    }
}

private const val CHEST_MS = 1500
private const val SHAKE_END = 0.4f
private const val OPEN_START = 0.6f
private const val SHAKE_FREQ = 55f
private const val SHAKE_DEG = 7f
private const val RAYS = 12
