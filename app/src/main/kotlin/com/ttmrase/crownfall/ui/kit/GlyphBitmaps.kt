package com.ttmrase.crownfall.ui.kit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Canvas へ直接描く場面（領地の収穫バブル・ワールドマップの資源地・飛んでいく報酬）のための、アイコンのビットマップ。
 * 1 度だけラスタライズして使い回す（毎フレーム Path を組み立てない）。
 */
class GlyphBitmaps(
    context: Context,
    private val sizePx: Int,
) {
    private val app = context.applicationContext
    private val cache = HashMap<Glyph, ImageBitmap>()

    fun get(glyph: Glyph): ImageBitmap =
        cache.getOrPut(glyph) {
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            ContextCompat.getDrawable(app, glyph.res)?.apply {
                setBounds(0, 0, sizePx, sizePx)
                draw(Canvas(bitmap))
            }
            bitmap.asImageBitmap()
        }
}

/** [size] の大きさで描く前提のビットマップの束（拡大しても粗くならないよう 2 倍で持つ）。 */
@Composable
fun rememberGlyphBitmaps(size: Dp): GlyphBitmaps {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { (size.toPx() * OVERSAMPLE).roundToInt().coerceAtLeast(1) }
    return remember(context, px) { GlyphBitmaps(context, px) }
}

/** [center] を中心に、一辺 [sizePx] でアイコンを描く。 */
fun DrawScope.drawGlyph(
    bitmaps: GlyphBitmaps,
    glyph: Glyph,
    center: Offset,
    sizePx: Float,
    alpha: Float = 1f,
) {
    val s = sizePx.roundToInt().coerceAtLeast(1)
    drawImage(
        image = bitmaps.get(glyph),
        dstOffset = IntOffset((center.x - s / 2f).roundToInt(), (center.y - s / 2f).roundToInt()),
        dstSize = IntSize(s, s),
        alpha = alpha,
        filterQuality = FilterQuality.Medium,
    )
}

private const val OVERSAMPLE = 2f
