package com.ttmrase.crownfall.ui.city

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R

/** チュートリアルの対象を点滅する枠と上下に揺れる矢印で示す。[active] が false のときは何も足さない。 */
@Composable
fun TutorialHighlight(
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!active) {
        Box(modifier) { content() }
        return
    }
    val transition = rememberInfiniteTransition(label = "tutorial")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "alpha",
    )
    val bounce by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 500), RepeatMode.Reverse),
        label = "bounce",
    )
    val color = MaterialTheme.colorScheme.primary
    Box(
        modifier =
            modifier.drawWithContent {
                drawContent()
                drawRoundRect(
                    color = color.copy(alpha = alpha),
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            },
    ) {
        content()
        Text(
            text = "▼",
            color = color,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.align(Alignment.TopCenter).offset(y = (-14).dp + bounce.dp),
        )
    }
}

/** チュートリアルの説明バナー（スキップ付き）。[step] は 0..2。 */
@Composable
fun TutorialBanner(
    step: Int,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text =
        when (step) {
            0 -> R.string.tutorial_step0
            1 -> R.string.tutorial_step1
            else -> R.string.tutorial_step2
        }
    Card(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(text), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onSkip) { Text(stringResource(R.string.tutorial_skip)) }
        }
    }
}
