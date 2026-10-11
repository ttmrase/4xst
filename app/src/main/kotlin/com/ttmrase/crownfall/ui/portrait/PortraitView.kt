package com.ttmrase.crownfall.ui.portrait

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.rarityColor
import com.ttmrase.crownfall.ui.vip.vipColors

/** ポートレートの描画リソース名（`pt_<key>`）の鍵。英雄・ペルソナは ID、名前だけのメンバーは番号、プレイヤーはアバター番号。 */
object PortraitKeys {
    /** 順位表の NPC 用に使う中性的な顔の枚数（gen_n_00..）。 */
    private const val NEUTRAL_COUNT = 6

    /** 名前だけの同盟メンバー。[look]（m / f / n）の中での並び順 [indexInLook] で決める。 */
    fun filler(
        look: String,
        indexInLook: Int,
    ): String = "gen_%s_%02d".format(look, indexInLook)

    /** 名前から決める（順位表の NPC。性別が分からないので中性的な顔だけ。同じ名前はいつも同じ顔）。 */
    fun byName(name: String): String = filler("n", Math.floorMod(name.hashCode(), NEUTRAL_COUNT))

    /** プレイヤーのアバター（[GameState.avatar][com.ttmrase.crownfall.core.model.GameState.avatar]）。 */
    fun avatar(index: Int): String = "avatar_%02d".format(Math.floorMod(index, Actions.AVATAR_COUNT))
}

private const val FRAME_RATIO = 0.24f
private val FRAME_WIDTH = 2.5.dp
private val VIP_WIDTH = 2.5.dp
private const val SHINE_MS = 2400
private val SKIN_WIDTH = 2.dp

private fun skinFrameColor(theme: String): Color =
    when (theme) {
        "ice" -> Color(0xFF8FD3FF)
        "gold" -> Color(0xFFFFD54F)
        "flame" -> Color(0xFFFF7043)
        "sakura" -> Color(0xFFFF9EC0)
        else -> Color(0xFFB0B6BE)
    }

/**
 * ポートレート（M11 T11.5）。レアリティの枠（N 灰 / R 青 / SR 紫 / SSR 金＋流れる光）と、VIP の枠（外側の細いリング）を
 * 重ねて出す。[key] の絵が無ければ [name] の頭文字で代用する。[rarity] が null なら枠なし（プレイヤー・メンバー）。
 */
@Composable
fun PortraitView(
    key: String,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    rarity: HeroRarity? = null,
    vip: Int = 0,
    /** 装備中のアバター枠のスキンのテーマ（ice / gold / flame / sakura。null は無し）。 */
    frameTheme: String? = null,
) {
    val context = LocalContext.current
    val resId = remember(key) { context.resources.getIdentifier("pt_$key", "drawable", context.packageName) }
    val shape = RoundedCornerShape(size * FRAME_RATIO)
    val skinPad = if (frameTheme != null) SKIN_WIDTH + 1.dp else 0.dp
    val pad = skinPad + (if (vip > 0) VIP_WIDTH + 1.dp else 0.dp) + (if (rarity != null) FRAME_WIDTH else 0.dp)
    val shine = rarity == HeroRarity.SSR && animationsEnabled()
    val phase =
        if (shine) {
            val t = rememberInfiniteTransition(label = "ptShine")
            t.animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(SHINE_MS, easing = LinearEasing), RepeatMode.Restart),
                "p",
            ).value
        } else {
            0f
        }
    val vipBrush = vipColors(vip)
    Box(
        modifier =
            modifier
                .size(size + pad * 2)
                .drawWithContent {
                    drawContent()
                    val r = size.toPx() * FRAME_RATIO
                    if (frameTheme != null) {
                        val w = SKIN_WIDTH.toPx()
                        drawRoundRect(
                            skinFrameColor(frameTheme),
                            Offset(w / 2, w / 2),
                            Size(this.size.width - w, this.size.height - w),
                            CornerRadius(r + w * 2),
                            style = Stroke(w),
                        )
                    }
                    if (vip > 0) {
                        val w = VIP_WIDTH.toPx()
                        val skinIn = skinPad.toPx()
                        drawRoundRect(
                            Brush.sweepGradient(vipBrush + vipBrush.first(), center),
                            Offset(w / 2 + skinIn, w / 2 + skinIn),
                            Size(this.size.width - w - 2 * skinIn, this.size.height - w - 2 * skinIn),
                            CornerRadius(r + w),
                            style = Stroke(w),
                        )
                    }
                    if (rarity != null) {
                        val inset = skinPad.toPx() + (if (vip > 0) (VIP_WIDTH + 1.dp).toPx() else 0f)
                        val w = FRAME_WIDTH.toPx()
                        val base = rarityColor(rarity)
                        val brush =
                            if (shine) {
                                val x = (phase * 2.4f - 0.7f) * this.size.width
                                Brush.linearGradient(
                                    listOf(base, Color.White, base),
                                    Offset(x - this.size.width * 0.4f, 0f),
                                    Offset(x + this.size.width * 0.4f, this.size.height),
                                )
                            } else {
                                Brush.linearGradient(listOf(base, base))
                            }
                        drawRoundRect(
                            brush,
                            Offset(inset + w / 2, inset + w / 2),
                            Size(this.size.width - 2 * inset - w, this.size.height - 2 * inset - w),
                            CornerRadius(r),
                            style = Stroke(w),
                        )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.secondaryContainer),
            Alignment.Center,
        ) {
            if (resId != 0) {
                Image(painterResource(resId), name, Modifier.size(size), contentScale = ContentScale.Crop)
            } else {
                Text(name.take(1), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
            }
        }
    }
}
