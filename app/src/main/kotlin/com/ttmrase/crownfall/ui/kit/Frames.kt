package com.ttmrase.crownfall.ui.kit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 枠の種類。面の色と縁取りの組み合わせ。 */
enum class FrameStyle(
    internal val top: Color,
    internal val bottom: Color,
    internal val rim: Rim,
    internal val content: Color,
) {
    /** 金の二重縁のパネル（HUD・ダイアログ・重要なカード）。 */
    Panel(Cf.Night3, Cf.Night2, Rim.GOLD, Cf.Ivory),

    /** 細い縁の地味なカード（一覧の行）。 */
    Card(Color(0xFF202E5C), Color(0xFF151F40), Rim.LINE, Cf.Ivory),

    /** くぼんだ面（スロット・入力欄・数値の受け皿）。 */
    Inset(Color(0xFF080D1D), Color(0xFF0E1630), Rim.INSET, Cf.Ivory),

    /** 羊皮紙（明るい面。オファーの紙面など）。 */
    Parchment(Cf.ParchHi, Cf.Parch, Rim.BRONZE, Cf.ParchInk),

    /** 紫のプレミアム面（VIP・召喚）。 */
    Royal(Color(0xFF3A2470), Color(0xFF1C1140), Rim.GOLD, Cf.Ivory),

    /** 赤の警告面（襲撃・期間限定）。 */
    Alarm(Color(0xFF7A1528), Color(0xFF3C0A16), Rim.GOLD, Cf.Ivory),

    /** 緑の達成面（受け取り可能・完了）。 */
    Success(Color(0xFF1C5A38), Color(0xFF0E2F22), Rim.GOLD, Cf.Ivory),
}

internal enum class Rim { GOLD, LINE, INSET, BRONZE }

private val BronzeRim = Brush.verticalGradient(listOf(Color(0xFFE9C988), Color(0xFFB8893C), Color(0xFF7A5520)))

/**
 * ゲームの枠を背景に描く。[ornate] で四隅に金の鋲を打つ（ダイアログ・主役のパネル用）。
 * 描くだけで、中身の切り抜きはしない（切り抜くなら [clipFrame] を足す）。
 */
fun Modifier.gameFrame(
    style: FrameStyle = FrameStyle.Panel,
    corner: Dp = 14.dp,
    ornate: Boolean = false,
    alpha: Float = 1f,
    accent: Color? = null,
    tint: Color? = null,
): Modifier =
    this.drawWithCache {
        val r = corner.toPx()
        val d = 1.dp.toPx()
        val fill =
            if (tint != null) {
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = tint.alpha * alpha), tint.darker().copy(alpha = tint.alpha * alpha)),
                )
            } else {
                Brush.verticalGradient(listOf(style.top.copy(alpha = alpha), style.bottom.copy(alpha = alpha)))
            }
        val gloss =
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = if (style == FrameStyle.Inset) 0f else 0.09f),
                0.42f to Color.Transparent,
            )
        onDrawBehind {
            // 落ち影（下に 2dp ずらした暗い面）
            if (style != FrameStyle.Inset) {
                drawRoundRect(
                    color = Cf.Ink.copy(alpha = 0.45f * alpha),
                    topLeft = Offset(0f, 2 * d),
                    size = size,
                    cornerRadius = CornerRadius(r),
                )
            }
            drawRoundRect(brush = fill, cornerRadius = CornerRadius(r))
            drawRoundRect(brush = gloss, cornerRadius = CornerRadius(r))
            if (accent != null) {
                // 指定色の縁（レアリティなど）。外に暗い線、内に色の線、さらに内に淡い光
                ring(Cf.Ink.copy(alpha = 0.9f), 0f, r, d)
                ring(Brush.verticalGradient(listOf(accent.lighter(), accent, accent.darker())), d, r, 2f * d)
                ring(accent.copy(alpha = 0.28f), 3f * d, r, 2f * d)
            } else {
                rims(style, r, d)
            }
            if (ornate) studs(r, d)
        }
    }

private fun Color.lighter() =
    Color(
        red + (1 - red) * 0.45f,
        green + (1 - green) * 0.45f,
        blue + (1 - blue) * 0.45f,
        alpha,
    )

private fun Color.darker() = Color(red * 0.62f, green * 0.62f, blue * 0.62f, alpha)

private fun DrawScope.rims(
    style: FrameStyle,
    r: Float,
    d: Float,
) {
    when (style.rim) {
        Rim.GOLD -> {
            ring(Cf.Ink.copy(alpha = 0.9f), 0f, r, d)
            ring(Cf.GoldRim, d, r, 1.6f * d)
            ring(Cf.Ink.copy(alpha = 0.55f), 2.8f * d, r, 0.8f * d)
        }
        Rim.BRONZE -> {
            ring(Color(0xFF3B2A12), 0f, r, d)
            ring(BronzeRim, d, r, 1.6f * d)
            ring(Color(0x553B2A12), 2.8f * d, r, 0.8f * d)
        }
        Rim.LINE -> {
            ring(Cf.Ink.copy(alpha = 0.85f), 0f, r, d)
            ring(Cf.Line.copy(alpha = 0.9f), d, r, d)
        }
        Rim.INSET -> {
            ring(Cf.Ink, 0f, r, 1.4f * d)
            // 上の内側に落ちる影（くぼみ）
            drawRoundRect(
                brush = Brush.verticalGradient(0f to Cf.Ink.copy(alpha = 0.7f), 0.5f to Color.Transparent),
                cornerRadius = CornerRadius(r),
            )
            ring(Cf.Line.copy(alpha = 0.45f), 1.2f * d, r, 0.8f * d)
        }
    }
}

/** 枠の形に中身を切り抜く。 */
fun Modifier.clipFrame(corner: Dp = 14.dp): Modifier = this.clip(RoundedCornerShape(corner))

private fun DrawScope.ring(
    color: Color,
    inset: Float,
    radius: Float,
    width: Float,
) {
    val half = width / 2
    drawRoundRect(
        color = color,
        topLeft = Offset(inset + half, inset + half),
        size = Size(size.width - 2 * (inset + half), size.height - 2 * (inset + half)),
        cornerRadius = CornerRadius((radius - inset - half).coerceAtLeast(0f)),
        style = Stroke(width),
    )
}

private fun DrawScope.ring(
    brush: Brush,
    inset: Float,
    radius: Float,
    width: Float,
) {
    val half = width / 2
    drawRoundRect(
        brush = brush,
        topLeft = Offset(inset + half, inset + half),
        size = Size(size.width - 2 * (inset + half), size.height - 2 * (inset + half)),
        cornerRadius = CornerRadius((radius - inset - half).coerceAtLeast(0f)),
        style = Stroke(width),
    )
}

/** 四隅の金の鋲（ひし形）。 */
private fun DrawScope.studs(
    r: Float,
    d: Float,
) {
    val k = r * STUD_ARC + 1.8f * d
    val h = 3.6f * d
    listOf(
        Offset(k, k),
        Offset(size.width - k, k),
        Offset(k, size.height - k),
        Offset(size.width - k, size.height - k),
    ).forEach { c ->
        val p =
            Path().apply {
                moveTo(c.x, c.y - h)
                lineTo(c.x + h, c.y)
                lineTo(c.x, c.y + h)
                lineTo(c.x - h, c.y)
                close()
            }
        drawPath(p, Brush.verticalGradient(listOf(Cf.GoldHi, Cf.GoldMid), c.y - h, c.y + h))
        drawPath(p, Cf.GoldDeep, style = Stroke(0.9f * d))
    }
}

private const val STUD_ARC = 0.2929f

/** 金縁のパネル。 */
@Composable
fun GamePanel(
    modifier: Modifier = Modifier,
    style: FrameStyle = FrameStyle.Panel,
    corner: Dp = 14.dp,
    ornate: Boolean = false,
    alpha: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides style.content) {
        Column(modifier = modifier.gameFrame(style, corner, ornate, alpha), content = content)
    }
}

/** 一覧の行などに使うカード（Material3 の Card の置き換え）。[onClick] があれば押せる。 */
@Composable
fun GameCard(
    modifier: Modifier = Modifier,
    style: FrameStyle = FrameStyle.Card,
    corner: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    accent: Color? = null,
    tint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.gameFrame(style, corner, accent = accent, tint = tint)
    val clickable =
        if (onClick != null) {
            base.clip(
                RoundedCornerShape(corner),
            ).clickable(enabled = enabled, onClick = onClick)
        } else {
            base
        }
    CompositionLocalProvider(LocalContentColor provides style.content) {
        Column(modifier = clickable, content = content)
    }
}

/** 区切り線（中央が明るく、両端が消える金の線）。 */
@Composable
fun GameDivider(
    modifier: Modifier = Modifier,
    color: Color = Cf.GoldMid,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.18f to color.copy(alpha = 0.55f),
                        0.5f to color.copy(alpha = 0.85f),
                        0.82f to color.copy(alpha = 0.55f),
                        1f to Color.Transparent,
                    ),
                )
            },
    )
}

/**
 * 画面の地。紺から夜へのグラデーションに、上からの淡い光と、ひし形の格子の地紋を重ねる。
 * 一覧の画面（同盟・クエスト・設定など）の背景に使う。
 */
fun Modifier.screenBackdrop(glow: Color = Color(0xFF3558C8)): Modifier =
    this.drawWithCache {
        val step = 28.dp.toPx()
        val lattice =
            Path().apply {
                var x = -size.height
                while (x < size.width) {
                    moveTo(x, 0f)
                    lineTo(x + size.height, size.height)
                    moveTo(x + size.height, 0f)
                    lineTo(x, size.height)
                    x += step
                }
            }
        val halo =
            Brush.radialGradient(
                listOf(glow.copy(alpha = 0.32f), Color.Transparent),
                center = Offset(size.width / 2, 0f),
                radius = size.width * 0.95f,
            )
        val vignette =
            Brush.verticalGradient(0.55f to Color.Transparent, 1f to Cf.Ink.copy(alpha = 0.55f))
        onDrawBehind {
            drawRect(Cf.ScreenFill)
            drawRect(halo)
            drawPath(lattice, Color.White.copy(alpha = 0.022f), style = Stroke(1.dp.toPx()))
            drawRect(vignette)
        }
    }

/** 中身が無いときの案内（大きなアイコンと一言）。一覧の画面の空の状態に使う。 */
@Composable
fun EmptyState(
    glyph: Glyph,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlyphIcon(glyph, size = 72.dp, alpha = 0.75f)
        Text(text, color = Cf.Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}
