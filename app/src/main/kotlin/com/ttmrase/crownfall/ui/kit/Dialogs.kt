package com.ttmrase.crownfall.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * ゲームのダイアログ（AlertDialog の置き換え。引数の名前も同じ）。
 * 金の鋲つきの枠に、上端へ乗り上げた赤いリボンの題字。ボタンは下に横並びで、確定が金、取消が紺。
 */
@Composable
fun GameDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    style: FrameStyle = FrameStyle.Panel,
    properties: DialogProperties = DialogProperties(),
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        GameDialogFrame(modifier, title, style) {
            if (icon != null) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp), contentAlignment = Alignment.Center) { icon() }
            }
            if (text != null) {
                Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(color = Cf.Ivory)) { text() }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (dismissButton != null) {
                    Box(Modifier.weight(1f), propagateMinConstraints = true) {
                        CompositionLocalProvider(LocalDialogSlot provides DialogSlot.DISMISS) { dismissButton() }
                    }
                }
                Box(Modifier.weight(1f), propagateMinConstraints = true) {
                    CompositionLocalProvider(LocalDialogSlot provides DialogSlot.CONFIRM) { confirmButton() }
                }
            }
        }
    }
}

/** ダイアログの枠だけ（中身を自由に組むとき）。[title] は上端のリボンに入る。 */
@Composable
fun GameDialogFrame(
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    style: FrameStyle = FrameStyle.Panel,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.widthIn(max = 380.dp).fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .padding(top = if (title != null) RIBBON_OVERLAP.dp else 0.dp)
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .gameFrame(style, corner = 18.dp, ornate = true)
                    .padding(start = 18.dp, end = 18.dp, top = if (title != null) 34.dp else 20.dp, bottom = 18.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides style.content) { content() }
        }
        if (title != null) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(RIBBON_WIDTH)
                        .defaultMinSize(minHeight = 40.dp)
                        .drawBehind { ribbon() }
                        .padding(horizontal = 26.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    ProvideTextStyle(
                        MaterialTheme.typography.titleMedium.copy(
                            color = Color(0xFFFFF6DC),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            shadow = Shadow(Color(0xFF4A0010), Offset(0f, 2f), 3f),
                        ),
                    ) { title() }
                }
            }
        }
    }
}

private const val RIBBON_OVERLAP = 18
private const val RIBBON_WIDTH = 0.9f

/** 題字のリボン: 両端が折れて後ろへ回る、金の縁の赤い帯。 */
private fun DrawScope.ribbon() {
    val d = 1.dp.toPx()
    val fold = 14 * d
    val drop = 7 * d
    val w = size.width
    val h = size.height - drop
    // 後ろへ回る両端（暗い赤）
    listOf(0f, 1f).forEach { side ->
        val x0 = if (side == 0f) 0f else w
        val dir = if (side == 0f) 1f else -1f
        val tail =
            Path().apply {
                moveTo(x0, drop)
                lineTo(x0 + dir * fold * 1.6f, drop)
                lineTo(x0 + dir * fold * 1.6f, h + drop)
                lineTo(x0, h + drop)
                lineTo(x0 + dir * fold * 0.6f, drop + h / 2)
                close()
            }
        drawPath(tail, Brush.verticalGradient(listOf(Color(0xFF9C1230), Color(0xFF5A0A18)), drop, h + drop))
        drawPath(tail, Cf.GoldLo, style = Stroke(1.2f * d))
        val crease =
            Path().apply {
                moveTo(x0 + dir * fold, h)
                lineTo(x0 + dir * fold * 1.6f, h + drop)
                lineTo(x0 + dir * fold * 1.6f, h)
                close()
            }
        drawPath(crease, Color(0xFF3A0510))
    }
    // 正面の帯
    val left = fold
    val bw = w - 2 * fold
    val r = CornerRadius(3 * d)
    drawRoundRect(Cf.Ink.copy(alpha = 0.5f), Offset(left, 2 * d), Size(bw, h), r)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFFF7A6E), Color(0xFFE02B45), Color(0xFFA9142F)), 0f, h),
        Offset(left, 0f),
        Size(bw, h),
        r,
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.30f), Color.Transparent), 0f, h * 0.5f),
        Offset(left, 0f),
        Size(bw, h * 0.5f),
        r,
    )
    drawRoundRect(
        Cf.GoldRim,
        Offset(left + 0.8f * d, 0.8f * d),
        Size(bw - 1.6f * d, h - 1.6f * d),
        r,
        style = Stroke(1.6f * d),
    )
    drawRoundRect(
        Cf.GoldHi.copy(alpha = 0.55f),
        Offset(left + 4 * d, 4 * d),
        Size(bw - 8 * d, h - 8 * d),
        CornerRadius(2 * d),
        style = Stroke(0.7f * d),
    )
}

/**
 * 下から出るシート（ModalBottomSheet の置き換え）。紺の面に金の上縁、つまみも金。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = Cf.Night2,
        contentColor = Cf.Ivory,
        scrimColor = Cf.Ink.copy(alpha = 0.62f),
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val d = 1.dp.toPx()
                        val r = 22.dp.toPx()
                        // 上縁の金の線（角の丸みに沿う）
                        val rim =
                            Path().apply {
                                moveTo(0f, r)
                                arcTo(androidx.compose.ui.geometry.Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                                lineTo(size.width - r, 0f)
                                arcTo(
                                    androidx.compose.ui.geometry.Rect(size.width - 2 * r, 0f, size.width, 2 * r),
                                    270f,
                                    90f,
                                    false,
                                )
                            }
                        drawRect(
                            Brush.verticalGradient(listOf(Color(0xFF2B3D7C), Cf.Night2)),
                            size = Size(size.width, size.height),
                        )
                        drawPath(rim, Cf.GoldRim, style = Stroke(2.2f * d))
                    }
                    .padding(top = 10.dp, bottom = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(width = 44.dp, height = 5.dp).drawBehind {
                        drawRoundRect(
                            Cf.Ink.copy(alpha = 0.6f),
                            Offset(0f, 1.dp.toPx()),
                            size,
                            CornerRadius(size.height / 2),
                        )
                        drawRoundRect(Cf.GoldRim, cornerRadius = CornerRadius(size.height / 2))
                    },
                )
            }
        },
        content = content,
    )
}
