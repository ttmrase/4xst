package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.kit.idleBob
import com.ttmrase.crownfall.ui.territory.PlotLayout
import com.ttmrase.crownfall.ui.territory.SpriteIds
import com.ttmrase.crownfall.ui.theme.CrownFonts

private val Unbuilt = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.15f) })

/**
 * 建物の絵（領地に建っているのと同じスプライト。Lv の段階で見た目が変わる）を、くぼんだ台座に載せて出す。
 * 未建設（Lv0）は最初の段階の絵を色あせさせる。[showLevel] で左下に Lv の札。
 */
@Composable
fun BuildingArt(
    type: BuildingType,
    level: Int,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    showLevel: Boolean = true,
) {
    Box(
        modifier =
            modifier
                .size(size)
                .gameFrame(FrameStyle.Inset, corner = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .drawBehind {
                    // 台座の光（足元の楕円）と、上からの淡い光
                    val c = Offset(this.size.width / 2, this.size.height * 0.78f)
                    drawOval(
                        Brush.radialGradient(listOf(Color(0x664C7BFF), Color.Transparent), c, this.size.width * 0.55f),
                        Offset(0f, this.size.height * 0.55f),
                        androidx.compose.ui.geometry.Size(this.size.width, this.size.height * 0.5f),
                    )
                },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(SpriteIds.building(type, PlotLayout.tierOfLevel(level.coerceAtLeast(1)))),
            contentDescription = null,
            modifier = Modifier.size(size * ART_SCALE).offset(y = -size * ART_LIFT).idleBob(2.dp, 3200),
            colorFilter = if (level <= 0) Unbuilt else null,
            alpha = if (level <= 0) UNBUILT_ALPHA else 1f,
        )
        if (showLevel && level > 0) {
            Text(
                "Lv $level",
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .gameFrame(FrameStyle.Panel, corner = 7.dp)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                color = Cf.GoldHi,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = CrownFonts.Numeric,
            )
        }
    }
}

private const val ART_SCALE = 1.08f
private const val ART_LIFT = 0.04f
private const val UNBUILT_ALPHA = 0.7f
