package com.ttmrase.crownfall.ui.kit

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Crownfall の見た目の基本色（docs/02 §21）。「夜の紺 + 金」を土台に、行動の種類ごとに宝石色を 1 つずつ割り当てる。
 * 画面ごとに色を作らず、ここから取る。
 */
object Cf {
    // 夜の紺（背景から浮き上がる面の順）
    val Night0 = Color(0xFF060A16)
    val Night1 = Color(0xFF0C1326)
    val Night2 = Color(0xFF131D3B)
    val Night3 = Color(0xFF1B2850)
    val Night4 = Color(0xFF27376A)
    val Line = Color(0xFF3B4C86)
    val Ink = Color(0xFF04060D)

    // 文字
    val Ivory = Color(0xFFF8F1DE)
    val Muted = Color(0xFFAAB4D4)
    val Dim = Color(0xFF7783A8)

    // 金
    val GoldHi = Color(0xFFFFF4B8)
    val Gold = Color(0xFFFFD04A)
    val GoldMid = Color(0xFFE0A21F)
    val GoldLo = Color(0xFFA86A0C)
    val GoldDeep = Color(0xFF5E3A06)
    val GoldInk = Color(0xFF3A2405)

    // 宝石色（赤 = 購入・警告、緑 = 実行・無料、青 = 情報、紫 = プレミアム）
    val Ruby = Color(0xFFE9324A)
    val RubyHi = Color(0xFFFF8E7C)
    val RubyLo = Color(0xFF8E1028)
    val Emerald = Color(0xFF3FC05C)
    val EmeraldHi = Color(0xFFA5F08A)
    val EmeraldLo = Color(0xFF14773A)
    val Sapphire = Color(0xFF3D8DEE)
    val SapphireHi = Color(0xFF9CD3FF)
    val SapphireLo = Color(0xFF1B4BAE)
    val Amethyst = Color(0xFF9A58EA)
    val AmethystHi = Color(0xFFE0B8FF)
    val AmethystLo = Color(0xFF4D2599)

    // 羊皮紙（明るい面）
    val ParchHi = Color(0xFFFFF7DE)
    val Parch = Color(0xFFF1DCA8)
    val ParchLo = Color(0xFFCBA869)
    val ParchInk = Color(0xFF3B2A12)

    /** 金の縁取り（上が明るく下が暗い）。 */
    val GoldRim: Brush = Brush.verticalGradient(listOf(GoldHi, Gold, GoldMid, GoldLo))

    /** 金のグラデーション文字。 */
    val GoldTextBrush: Brush = Brush.verticalGradient(listOf(GoldHi, Gold, GoldMid))

    /** パネルの面（上がわずかに明るい）。 */
    val PanelFill: Brush = Brush.verticalGradient(listOf(Night3, Night2))

    /** 画面の地（上が紺、下が深い夜）。 */
    val ScreenFill: Brush = Brush.verticalGradient(listOf(Color(0xFF14204A), Night1, Night0))
}
