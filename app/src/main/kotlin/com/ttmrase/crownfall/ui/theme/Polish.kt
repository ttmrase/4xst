package com.ttmrase.crownfall.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId

private const val PRESSED_SCALE = 0.93f
private const val COUNT_MS = 650

/** 金の縁取りに使うグラデーション。 */
val GoldBrush: Brush =
    Brush.verticalGradient(listOf(Color(0xFFFFF1B8), Color(0xFFFFC107), Color(0xFFB8860B)))

/** 金のグラデーション文字。見出し・戦力・ジェム数などの強調に使う。 */
@Composable
fun GoldText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(text, modifier = modifier, style = style.copy(brush = GoldBrush))
}

/** 押すと沈み込み、離すとバネで戻るボタン。押した瞬間にボタン音（設定でミュート可）。 */
@Composable
fun PressButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 600f),
        label = "press",
    )
    val context = LocalContext.current
    Button(
        onClick = {
            Sfx.play(context, SfxId.BUTTON)
            onClick()
        },
        modifier =
            modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        colors = colors,
        interactionSource = source,
        content = content,
    )
}

/** 金枠のパネル（資源バーなど。縁が金のグラデーション）。 */
@Composable
fun GoldPanel(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = alpha)),
        border = BorderStroke(1.5.dp, GoldBrush),
    ) { content() }
}

/** 数値がカウントアップ/ダウンしながら変わる表示（資源バーなど）。 */
@Composable
fun AnimatedNumber(
    value: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val shown = remember { Animatable(value.toFloat()) }
    LaunchedEffect(value) { shown.animateTo(value.toFloat(), tween(COUNT_MS)) }
    Text(ShortNumber.format(shown.value.toLong()), modifier = modifier, style = style, color = color)
}
