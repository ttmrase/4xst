package com.ttmrase.crownfall.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId

/** ボタンの色。行動の意味で選ぶ（金 = 主、緑 = 実行・無料、赤 = 購入、青 = 情報、紫 = プレミアム、紺 = 副）。 */
enum class GameButtonStyle(
    internal val hi: Color,
    internal val mid: Color,
    internal val lo: Color,
    internal val lip: Color,
    internal val content: Color,
    internal val textShadow: Color,
    internal val rim: Color? = null,
) {
    Gold(Color(0xFFFFF2B0), Color(0xFFFFCA3D), Color(0xFFE59B14), Color(0xFF8A5206), Cf.GoldInk, Color(0x80FFFFFF)),
    Emerald(Color(0xFFB4F59A), Color(0xFF44C660), Color(0xFF1F9A47), Color(0xFF0C5526), Color.White, Color(0xB3003318)),
    Ruby(Color(0xFFFF9C8A), Color(0xFFEC3650), Color(0xFFBE1838), Color(0xFF6E0A1E), Color.White, Color(0xB34A0010)),
    Sapphire(
        Color(0xFFA9DBFF),
        Color(0xFF4494F2),
        Color(0xFF2768D2),
        Color(0xFF123A85),
        Color.White,
        Color(0xB3001A4A),
    ),
    Amethyst(
        Color(0xFFE6C4FF),
        Color(0xFFA262EE),
        Color(0xFF7238CA),
        Color(0xFF3B1A7E),
        Color.White,
        Color(0xB3200050),
    ),

    /** 紺の地に金の縁（副次的な行動。OutlinedButton の置き換え）。 */
    Ghost(
        Color(0xFF34467E),
        Color(0xFF25346A),
        Color(0xFF1A2650),
        Color(0xFF0A1024),
        Cf.GoldHi,
        Color(0xCC000000),
        Cf.Gold,
    ),
    Disabled(
        Color(0xFF6C7386),
        Color(0xFF515868),
        Color(0xFF41475A),
        Color(0xFF23283A),
        Color(0xFFB9BECC),
        Color(0x99000000),
    ),
}

enum class GameButtonSize(
    internal val height: Dp,
    internal val padH: Dp,
    internal val corner: Dp,
    internal val lip: Dp,
) {
    Small(32.dp, 12.dp, 9.dp, 2.5.dp),
    Normal(44.dp, 18.dp, 12.dp, 3.5.dp),
    Large(54.dp, 22.dp, 14.dp, 4.5.dp),
}

/** ダイアログの中で [GameTextButton] をどう見せるか（確定 = 金の実ボタン、取消 = 紺の実ボタン）。 */
enum class DialogSlot { NONE, CONFIRM, DISMISS }

val LocalDialogSlot = compositionLocalOf { DialogSlot.NONE }

/**
 * 立体的なゲームのボタン。面のグラデーション・上の艶・下の厚み（押すと沈む）を自前で描く。
 * Material3 の Button と同じ並びの引数（onClick, modifier, enabled, content）で置き換えられる。
 */
@Composable
fun GameButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GameButtonStyle = GameButtonStyle.Gold,
    size: GameButtonSize = GameButtonSize.Normal,
    sound: Boolean = false,
    contentPadding: PaddingValues? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "press",
    )
    val look = if (enabled) style else GameButtonStyle.Disabled
    val context = LocalContext.current
    val textStyle =
        (
            if (size == GameButtonSize.Small) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelLarge
            }
        )
            .copy(
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                shadow = Shadow(look.textShadow, Offset(0f, 1.5f), 2f),
            )
    Box(
        modifier =
            modifier
                .defaultMinSize(minWidth = size.height * MIN_WIDTH_RATIO, minHeight = size.height)
                .graphicsLayer {
                    val k = 1f - PRESS_SHRINK * press
                    scaleX = k
                    scaleY = k
                }
                .drawBehind { drawButton(look, size, press) }
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = {
                        if (sound) Sfx.play(context, SfxId.BUTTON)
                        onClick()
                    },
                )
                .padding(
                    contentPadding
                        ?: PaddingValues(start = size.padH, end = size.padH, top = 4.dp, bottom = 4.dp + size.lip),
                ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides look.content) {
            ProvideTextStyle(textStyle) {
                Row(
                    modifier = Modifier.graphicsLayer { translationY = press * size.lip.toPx() * PRESS_TRAVEL },
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content,
                )
            }
        }
    }
}

private fun DrawScope.drawButton(
    s: GameButtonStyle,
    size: GameButtonSize,
    press: Float,
) {
    val d = 1.dp.toPx()
    val r = size.corner.toPx()
    val lip = size.lip.toPx()
    val sink = lip * PRESS_TRAVEL * press
    val w = this.size.width
    val h = this.size.height
    // 影と厚み
    drawRoundRect(Cf.Ink.copy(alpha = 0.5f), Offset(0f, 1.5f * d), Size(w, h), CornerRadius(r))
    drawRoundRect(s.lip, Offset(0f, sink), Size(w, h - sink), CornerRadius(r))
    // 面
    val faceH = h - lip
    val top = sink
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(s.hi, s.mid, s.lo), top, top + faceH),
        topLeft = Offset(0f, top),
        size = Size(w, faceH),
        cornerRadius = CornerRadius(r),
    )
    // 上半分の艶
    drawRoundRect(
        brush =
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.04f)),
                top,
                top + faceH * 0.5f,
            ),
        topLeft = Offset(2 * d, top + 1.5f * d),
        size = Size(w - 4 * d, faceH * 0.46f),
        cornerRadius = CornerRadius((r - 2 * d).coerceAtLeast(0f)),
    )
    // 縁
    s.rim?.let { rim ->
        drawRoundRect(
            color = rim,
            topLeft = Offset(1.2f * d, top + 1.2f * d),
            size = Size(w - 2.4f * d, faceH - 2.4f * d),
            cornerRadius = CornerRadius((r - 1.2f * d).coerceAtLeast(0f)),
            style = Stroke(1.2f * d),
        )
    }
    drawRoundRect(
        color = Cf.Ink.copy(alpha = 0.85f),
        topLeft = Offset(0.5f * d, sink + 0.5f * d),
        size = Size(w - d, h - sink - d),
        cornerRadius = CornerRadius(r),
        style = Stroke(d),
    )
}

/** 副次的な行動のボタン（OutlinedButton の置き換え）。 */
@Composable
fun GameOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: GameButtonSize = GameButtonSize.Normal,
    content: @Composable RowScope.() -> Unit,
) = GameButton(onClick, modifier, enabled, GameButtonStyle.Ghost, size, content = content)

/**
 * 文字だけのボタン（TextButton の置き換え）。[GameDialog] の中では、確定は金、取消は紺の実ボタンになる。
 */
@Composable
fun GameTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    when (LocalDialogSlot.current) {
        DialogSlot.CONFIRM ->
            GameButton(
                onClick,
                modifier,
                enabled,
                GameButtonStyle.Gold,
                GameButtonSize.Normal,
                content = content,
            )
        DialogSlot.DISMISS ->
            GameButton(
                onClick,
                modifier,
                enabled,
                GameButtonStyle.Ghost,
                GameButtonSize.Normal,
                content = content,
            )
        DialogSlot.NONE -> {
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            val color = if (enabled) Cf.Gold else Cf.Dim
            Box(
                modifier =
                    modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 36.dp)
                        .graphicsLayer { alpha = if (pressed) PRESSED_TEXT_ALPHA else 1f }
                        .clickable(source, null, enabled, role = Role.Button, onClick = onClick)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides color) {
                    ProvideTextStyle(
                        LocalTextStyle.current.merge(
                            MaterialTheme.typography.labelLarge,
                        ).copy(fontWeight = FontWeight.Bold),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                            content = content,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 丸い金縁の台座に載ったアイコンのボタン（城内のクイックリンクなど）。[label] は読み上げ用。
 */
@Composable
fun GlyphMedallion(
    glyph: Glyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    locked: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = 900f),
        label = "press",
    )
    Box(
        modifier =
            modifier
                .size(size)
                .graphicsLayer {
                    val k = 1f - MEDALLION_SHRINK * press
                    scaleX = k
                    scaleY = k
                }
                .drawBehind {
                    val d = 1.dp.toPx()
                    val r = this.size.minDimension / 2
                    val c = Offset(this.size.width / 2, this.size.height / 2)
                    drawCircle(Cf.Ink.copy(alpha = 0.5f), r, c + Offset(0f, 2 * d))
                    drawCircle(Brush.verticalGradient(listOf(Cf.GoldHi, Cf.Gold, Cf.GoldLo)), r, c)
                    drawCircle(Cf.Ink.copy(alpha = 0.85f), r - 0.5f * d, c, style = Stroke(d))
                    drawCircle(
                        Brush.radialGradient(
                            listOf(if (locked) Color(0xFF3A4260) else Color(0xFF3B55A8), Cf.Night2, Cf.Night0),
                            center = c - Offset(0f, r * 0.35f),
                            radius = r * 1.5f,
                        ),
                        r - 2.6f * d,
                        c,
                    )
                    drawCircle(Cf.Ink.copy(alpha = 0.7f), r - 2.6f * d, c, style = Stroke(0.8f * d))
                }
                .clickable(source, null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, size = size * MEDALLION_ICON, dimmed = locked, alpha = if (locked) LOCKED_ALPHA else 1f)
        content()
    }
}

private const val MIN_WIDTH_RATIO = 1.6f
private const val PRESS_SHRINK = 0.03f
private const val PRESS_TRAVEL = 0.7f
private const val PRESSED_TEXT_ALPHA = 0.6f
private const val MEDALLION_SHRINK = 0.08f
private const val MEDALLION_ICON = 0.62f
private const val LOCKED_ALPHA = 0.55f
