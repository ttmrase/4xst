package com.ttmrase.crownfall.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.GameButton
import com.ttmrase.crownfall.ui.kit.GameButtonSize
import com.ttmrase.crownfall.ui.kit.GameButtonStyle
import com.ttmrase.crownfall.ui.kit.GamePanel

private const val COUNT_MS = 650

/** 金の縁取りに使うグラデーション。 */
val GoldBrush: Brush = Cf.GoldRim

/** 金のグラデーション文字。見出し・戦力・ジェム数などの強調に使う。 */
@Composable
fun GoldText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Text(text, modifier = modifier, style = style.copy(brush = GoldBrush))
}

/** 押すと沈み込むボタン（押した瞬間にボタン音。設定でミュート可）。[style] で色を選ぶ。 */
@Composable
fun PressButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: GameButtonStyle = GameButtonStyle.Gold,
    size: GameButtonSize = GameButtonSize.Large,
    content: @Composable RowScope.() -> Unit,
) = GameButton(onClick, modifier, enabled, style, size, sound = true, content = content)

/** 金枠のパネル（資源バーなど）。 */
@Composable
fun GoldPanel(
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    content: @Composable () -> Unit,
) {
    GamePanel(modifier = modifier, alpha = alpha) { content() }
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
