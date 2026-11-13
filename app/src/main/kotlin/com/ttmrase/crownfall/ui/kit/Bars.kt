package com.ttmrase.crownfall.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.ui.theme.CrownFonts

/**
 * 進捗バー（LinearProgressIndicator の置き換え）。くぼんだ溝に、艶のある面が伸びる。
 * 高さを指定しなければ 10dp。4dp 以下に指定されたときは、縁を省いた細い線にする。
 */
@Composable
fun GameProgressBar(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = Cf.Gold,
    trackColor: Color = Color(0xFF070B18),
) {
    Box(
        modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 10.dp)
            .drawBehind {
                val d = 1.dp.toPx()
                val h = size.height
                val r = CornerRadius(h / 2)
                val p = progress().coerceIn(0f, 1f)
                val thin = h <= THIN_PX_DP * d
                drawRoundRect(trackColor, cornerRadius = r)
                if (!thin) {
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Cf.Ink.copy(alpha = 0.8f), Color.Transparent)),
                        cornerRadius = r,
                    )
                }
                val w = (size.width * p).coerceAtLeast(if (p > 0f) h else 0f)
                if (w > 0f) {
                    val inset = if (thin) 0f else d
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(color.lighten(LIGHTEN), color, color.darken(DARKEN))),
                        topLeft = Offset(inset, inset),
                        size = Size(w - 2 * inset, h - 2 * inset),
                        cornerRadius = CornerRadius((h - 2 * inset) / 2),
                    )
                    if (!thin) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.35f),
                            topLeft = Offset(inset + h * 0.3f, inset + d * 0.8f),
                            size = Size((w - 2 * inset - h * 0.6f).coerceAtLeast(0f), (h - 2 * inset) * 0.32f),
                            cornerRadius = CornerRadius(h),
                        )
                    }
                }
                if (!thin) drawRoundRect(Cf.Line.copy(alpha = 0.7f), cornerRadius = r, style = Stroke(d))
            },
    )
}

/** 進み具合が分からないときのバー（光の帯が左から右へ流れる）。 */
@Composable
fun GameProgressBar(
    modifier: Modifier = Modifier,
    color: Color = Cf.Gold,
) {
    val sweep by rememberInfiniteTransition(label = "busy").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 10.dp)
            .drawBehind {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Color(0xFF070B18), cornerRadius = r)
                val w = size.width * BUSY_WIDTH
                val x = -w + (size.width + w) * sweep
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, color, Color.Transparent), x, x + w),
                    cornerRadius = r,
                )
                drawRoundRect(Cf.Line.copy(alpha = 0.7f), cornerRadius = r, style = Stroke(1.dp.toPx()))
            },
    )
}

private const val SWEEP_MS = 1100
private const val BUSY_WIDTH = 0.45f

private fun Color.lighten(t: Float) = Color(red + (1 - red) * t, green + (1 - green) * t, blue + (1 - blue) * t, alpha)

private fun Color.darken(t: Float) = Color(red * (1 - t), green * (1 - t), blue * (1 - t), alpha)

private const val THIN_PX_DP = 4f
private const val LIGHTEN = 0.45f
private const val DARKEN = 0.28f

/** タブの行の選択中の番号（[GameTab] が自分の見た目を決めるのに使う）。 */
private val LocalTabScrollable = compositionLocalOf { false }

/**
 * タブの行（TabRow / ScrollableTabRow の置き換え）。くぼんだ帯の上に、選択中のタブだけ金の札になる。
 * [scrollable] が false なら等幅、true なら中身の幅で横スクロールする。
 */
@Composable
fun GameTabRow(
    selectedTabIndex: Int,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    tabs: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    val offsets = remember { IntArray(MAX_TABS) }
    val widths = remember { IntArray(MAX_TABS) }
    if (scrollable) {
        LaunchedEffect(selectedTabIndex) {
            val i = selectedTabIndex.coerceIn(0, MAX_TABS - 1)
            val target = offsets[i] + widths[i] / 2 - scroll.viewportSize / 2
            scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue.coerceAtLeast(0)))
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1024), Color(0xFF141E3E))))
                val d = 1.dp.toPx()
                drawRect(Cf.Ink, Offset(0f, size.height - 2.5f * d), Size(size.width, 2.5f * d))
                drawRect(
                    Brush.horizontalGradient(listOf(Cf.GoldLo, Cf.Gold, Cf.GoldLo)),
                    Offset(0f, size.height - 2 * d),
                    Size(size.width, 1.2f * d),
                )
            }
            .then(if (scrollable) Modifier.horizontalScroll(scroll) else Modifier)
            .padding(horizontal = 4.dp, vertical = 5.dp),
    ) {
        CompositionLocalProvider(LocalTabScrollable provides scrollable) {
            Layout(content = tabs) { measurables, constraints ->
                val n = measurables.size.coerceAtLeast(1)
                val placeables =
                    if (scrollable || constraints.maxWidth == Constraints.Infinity) {
                        measurables.map { it.measure(Constraints(minWidth = MIN_TAB_DP.dp.roundToPx())) }
                    } else {
                        val w = constraints.maxWidth / n
                        measurables.map { it.measure(Constraints.fixedWidth(w)) }
                    }
                val height = placeables.maxOfOrNull { it.height } ?: 0
                val width = if (scrollable) placeables.sumOf { it.width } else constraints.maxWidth
                layout(width, height) {
                    var x = 0
                    placeables.forEachIndexed { i, p ->
                        if (i < MAX_TABS) {
                            offsets[i] = x
                            widths[i] = p.width
                        }
                        p.placeRelative(x, (height - p.height) / 2)
                        x += p.width
                    }
                }
            }
        }
    }
}

private const val MAX_TABS = 24
private const val MIN_TAB_DP = 76

/** タブ 1 つ（Tab の置き換え）。[text] の中の Text はそのまま置ける。 */
@Composable
fun GameTab(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    text: @Composable () -> Unit,
) {
    val glow by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = 500f), label = "tab")
    val scrollable = LocalTabScrollable.current
    Box(
        modifier =
            modifier
                .defaultMinSize(minHeight = 36.dp)
                .padding(horizontal = 2.dp)
                .drawBehind {
                    if (glow > 0.01f) {
                        val d = 1.dp.toPx()
                        val r = CornerRadius(9.dp.toPx())
                        drawRoundRect(Cf.Ink.copy(alpha = 0.6f * glow), Offset(0f, 1.5f * d), size, r)
                        drawRoundRect(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFFFFF2B0).copy(alpha = glow),
                                    Color(0xFFFFCA3D).copy(alpha = glow),
                                    Color(0xFFDD9414).copy(alpha = glow),
                                ),
                            ),
                            cornerRadius = r,
                        )
                        drawRoundRect(
                            Color.White.copy(alpha = 0.35f * glow),
                            Offset(2 * d, 1.5f * d),
                            Size(size.width - 4 * d, size.height * 0.42f),
                            CornerRadius(7.dp.toPx()),
                        )
                        drawRoundRect(Cf.GoldDeep.copy(alpha = glow), cornerRadius = r, style = Stroke(d))
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = enabled,
                    role = Role.Tab,
                    onClick = onClick,
                )
                .padding(horizontal = if (scrollable) 14.dp else 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (selected) Cf.GoldInk else Cf.Muted) {
            ProvideTextStyle(
                MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                ),
            ) { text() }
        }
    }
}

/** 画面を 1 つ戻る操作（NavHost が用意する）。null なら戻るボタンを出さない。 */
val LocalNavigateBack = compositionLocalOf<(() -> Unit)?> { null }

/**
 * 画面の見出しの帯。左に戻るボタン、中央に金の題字、右に [trailing]。
 * 城内から開く子画面（兵舎・研究・クエスト・メール・チャット・設定など）の上端に置く。
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    onBack: (() -> Unit)? = LocalNavigateBack.current,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .drawBehind {
                val d = 1.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(Color(0xFF24356E), Color(0xFF131C3C))))
                drawRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)),
                    size = Size(size.width, size.height * 0.5f),
                )
                drawRect(Cf.Ink, Offset(0f, size.height - 3 * d), Size(size.width, 3 * d))
                drawRect(
                    Brush.horizontalGradient(listOf(Cf.GoldLo, Cf.GoldHi, Cf.GoldLo)),
                    Offset(0f, size.height - 2.4f * d),
                    Size(size.width, 1.6f * d),
                )
                // 題字の両脇の飾り（ひし形と細い線）
                val cy = size.height / 2 - d
                listOf(-1f, 1f).forEach { s ->
                    val x0 = size.width / 2 + s * size.width * 0.27f
                    val x1 = size.width / 2 + s * size.width * 0.36f
                    drawLine(Cf.Gold.copy(alpha = 0.6f), Offset(x0, cy), Offset(x1, cy), 1.2f * d)
                    drawCircle(Cf.Gold.copy(alpha = 0.8f), 2.2f * d, Offset(x1 + s * 4 * d, cy))
                }
            },
    ) {
        Text(
            title,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(TITLE_WIDTH).offset(y = (-1).dp),
            style =
                MaterialTheme.typography.titleMedium.copy(
                    brush = Cf.GoldTextBrush,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    shadow = Shadow(Cf.Ink, Offset(0f, 2f), 3f),
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onBack != null) {
            GlyphMedallion(
                Glyph.BACK,
                onClick = onBack,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                        .offset(y = (-1).dp)
                        .then(
                            if (backLabel != null) Modifier.semantics { contentDescription = backLabel } else Modifier,
                        ),
                size = 36.dp,
            )
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).offset(y = (-1).dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = trailing,
        )
    }
}

private const val TITLE_WIDTH = 0.5f

/** 数のバッジ（赤い丸に白い数字）。 */
@Composable
fun CountBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .drawBehind {
                val d = 1.dp.toPx()
                val r = CornerRadius(size.height / 2)
                drawRoundRect(Cf.Ink.copy(alpha = 0.6f), Offset(0f, d), size, r)
                drawRoundRect(Brush.verticalGradient(listOf(Cf.RubyHi, Cf.Ruby, Cf.RubyLo)), cornerRadius = r)
                drawRoundRect(Color.White.copy(alpha = 0.9f), cornerRadius = r, style = Stroke(1.2f * d))
            }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > MAX_BADGE) "$MAX_BADGE+" else count.toString(),
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = CrownFonts.Numeric,
        )
    }
}

private const val MAX_BADGE = 99

/** 丸い札（イベント名・残り時間・「次の解放」など）。 */
@Composable
fun Pill(
    modifier: Modifier = Modifier,
    glyph: Glyph? = null,
    tint: Color = Cf.Gold,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .drawBehind {
                    val d = 1.dp.toPx()
                    val r = CornerRadius(size.height / 2)
                    drawRoundRect(Cf.Ink.copy(alpha = 0.5f), Offset(0f, 1.5f * d), size, r)
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color(0xF0223062), Color(0xF00E152E))),
                        cornerRadius = r,
                    )
                    drawRoundRect(tint.copy(alpha = 0.85f), cornerRadius = r, style = Stroke(1.1f * d))
                }
                .then(
                    if (onClick != null) {
                        Modifier.clickable(
                            remember {
                                MutableInteractionSource()
                            },
                            null,
                            onClick = onClick,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(start = if (glyph != null) 5.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) GlyphIcon(glyph, size = 16.dp)
        CompositionLocalProvider(LocalContentColor provides Cf.Ivory) {
            ProvideTextStyle(MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)) { content() }
        }
    }
}

/** 下部ナビの 1 項目。 */
class BottomItem(
    val glyph: Glyph,
    val label: String,
    val selected: Boolean,
    val locked: Boolean,
    val badge: Int,
    val modifier: Modifier = Modifier,
    val onClick: () -> Unit,
)

/**
 * 下部ナビ。石の台座の帯に 5 つのアイコンが並び、選択中は台座から持ち上がって金色に光る。
 */
@Composable
fun GameBottomBar(
    items: List<BottomItem>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val d = 1.dp.toPx()
                val top = RAISE_DP.dp.toPx()
                drawRect(
                    Brush.verticalGradient(listOf(Color(0xFF1E2C5E), Color(0xFF0D1430), Cf.Night0), top, size.height),
                    Offset(0f, top),
                    Size(size.width, size.height - top),
                )
                drawRect(Cf.Ink, Offset(0f, top - d), Size(size.width, 1.2f * d))
                drawRect(
                    Brush.horizontalGradient(listOf(Cf.GoldLo, Cf.GoldHi, Cf.GoldLo)),
                    Offset(0f, top),
                    Size(size.width, 1.8f * d),
                )
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                        top,
                        top + 14 * d,
                    ),
                    Offset(0f, top + 1.8f * d),
                    Size(size.width, 14 * d),
                )
            }
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = RAISE_DP.dp)) {
            items.forEach { item -> BottomTab(item, Modifier.weight(1f)) }
        }
    }
}

private const val RAISE_DP = 10

@Composable
private fun BottomTab(
    item: BottomItem,
    modifier: Modifier,
) {
    val lift by animateFloatAsState(
        if (item.selected) 1f else 0f,
        spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "nav",
    )
    val pulse = rememberLoop(NAV_PULSE_MS, "navGlow")
    Column(
        modifier =
            modifier
                .then(item.modifier)
                .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = item.onClick)
                .padding(bottom = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 64.dp, height = 44.dp)
                    .graphicsLayer {
                        translationY = -(RAISE_DP + 2).dp.toPx() * lift
                        val k = 1f + SELECT_GROW * lift
                        scaleX = k
                        scaleY = k
                    }
                    .drawBehind {
                        if (lift > 0.01f) {
                            val c = Offset(size.width / 2, size.height / 2)
                            val r = size.height * 0.62f
                            // 選んだタブの後光は、ゆっくり明滅する
                            val glow = 1.35f + 0.18f * kotlin.math.sin(pulse.value * 2f * Math.PI.toFloat())
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Cf.Gold.copy(alpha = 0.55f * lift), Cf.Gold.copy(alpha = 0f)),
                                    c,
                                    r * glow,
                                ),
                                r * glow,
                                c,
                            )
                            val d = 1.dp.toPx()
                            drawCircle(Cf.Ink.copy(alpha = 0.55f * lift), r, c + Offset(0f, 2 * d))
                            drawCircle(
                                Brush.verticalGradient(
                                    listOf(
                                        Cf.GoldHi.copy(alpha = lift),
                                        Cf.Gold.copy(alpha = lift),
                                        Cf.GoldLo.copy(alpha = lift),
                                    ),
                                    c.y - r,
                                    c.y + r,
                                ),
                                r,
                                c,
                            )
                            drawCircle(
                                Brush.radialGradient(
                                    listOf(Color(0xFF4463C0).copy(alpha = lift), Color(0xFF16214A).copy(alpha = lift)),
                                    c - Offset(0f, r * 0.4f),
                                    r * 1.5f,
                                ),
                                r - 2.4f * d,
                                c,
                            )
                            drawCircle(Cf.Ink.copy(alpha = 0.8f * lift), r, c, style = Stroke(d))
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(
                item.glyph,
                size = 34.dp,
                dimmed = item.locked,
                alpha = if (item.locked) LOCKED_ICON_ALPHA else 1f,
            )
            if (item.locked) {
                GlyphIcon(Glyph.LOCK, Modifier.align(Alignment.BottomEnd).offset(x = (-8).dp), size = 18.dp)
            } else if (item.badge > 0) {
                CountBadge(
                    item.badge,
                    Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 1.dp).idleBob(2.dp, 1400),
                )
            }
        }
        Text(
            item.label,
            color =
                when {
                    item.selected -> Cf.GoldHi
                    item.locked -> Cf.Dim
                    else -> Cf.Muted
                },
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (item.selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(shadow = Shadow(Cf.Ink, Offset(0f, 1.5f), 2f)),
        )
        Spacer(Modifier.width(1.dp))
    }
}

private const val SELECT_GROW = 0.12f
private const val NAV_PULSE_MS = 2200
private const val LOCKED_ICON_ALPHA = 0.5f
