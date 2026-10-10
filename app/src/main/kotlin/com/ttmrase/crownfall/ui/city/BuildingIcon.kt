package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.session.BuildingSection
import com.ttmrase.crownfall.core.session.UiQueries

private val CoreColor = Color(0xFFC9A227)
private val ResourceColor = Color(0xFF4F9D5B)
private val MilitaryColor = Color(0xFFB5413A)
private val OtherColor = Color(0xFF5B7FC7)

private fun sectionColor(type: BuildingType): Color =
    when (UiQueries.sectionOf(type)) {
        BuildingSection.CORE -> CoreColor
        BuildingSection.RESOURCE -> ResourceColor
        BuildingSection.MILITARY -> MilitaryColor
        BuildingSection.OTHER -> OtherColor
    }

/** 自作のベクター図形で描く建物アイコン（建物種ごとに色と形を変える）。 */
@Composable
fun BuildingIcon(
    type: BuildingType,
    modifier: Modifier = Modifier,
    iconSize: Dp = 40.dp,
) {
    val color = sectionColor(type)
    Canvas(modifier = modifier.size(iconSize)) {
        when (type) {
            BuildingType.CASTLE -> drawCrown(color)
            BuildingType.FARM, BuildingType.SAWMILL, BuildingType.QUARRY, BuildingType.GOLD_MINE -> drawCircle(color)
            BuildingType.WAREHOUSE, BuildingType.TRADING_POST -> drawBox(color)
            BuildingType.BARRACKS, BuildingType.ARCHERY_RANGE, BuildingType.STABLE, BuildingType.SIEGE_WORKSHOP ->
                drawTriangle(color)
            BuildingType.WALL -> drawWall(color)
            else -> drawDiamond(color)
        }
    }
}

private fun DrawScope.drawCircle(color: Color) {
    drawCircle(color = color, radius = size.minDimension / 2f)
    drawCircle(color = Color.White.copy(alpha = 0.25f), radius = size.minDimension / 4f)
}

private fun DrawScope.drawBox(color: Color) {
    drawRect(
        color = color,
        topLeft = Offset(size.width * 0.1f, size.height * 0.1f),
        size = Size(size.width * 0.8f, size.height * 0.8f),
    )
    drawRect(
        color = Color.White.copy(alpha = 0.25f),
        topLeft = Offset(size.width * 0.3f, size.height * 0.3f),
        size = Size(size.width * 0.4f, size.height * 0.4f),
    )
}

private fun DrawScope.drawTriangle(color: Color) {
    val path =
        Path().apply {
            moveTo(size.width / 2f, size.height * 0.05f)
            lineTo(size.width * 0.95f, size.height * 0.95f)
            lineTo(size.width * 0.05f, size.height * 0.95f)
            close()
        }
    drawPath(path, color)
}

private fun DrawScope.drawDiamond(color: Color) {
    val path =
        Path().apply {
            moveTo(size.width / 2f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(size.width / 2f, size.height)
            lineTo(0f, size.height / 2f)
            close()
        }
    drawPath(path, color)
}

private fun DrawScope.drawWall(color: Color) {
    val w = size.width
    val h = size.height
    drawRect(color = color, topLeft = Offset(0f, h * 0.35f), size = Size(w, h * 0.6f))
    for (i in 0..2) {
        drawRect(color = color, topLeft = Offset(w * (0.05f + i * 0.35f), h * 0.1f), size = Size(w * 0.25f, h * 0.3f))
    }
}

private fun DrawScope.drawCrown(color: Color) {
    val w = size.width
    val h = size.height
    val path =
        Path().apply {
            moveTo(w * 0.1f, h * 0.8f)
            lineTo(w * 0.05f, h * 0.25f)
            lineTo(w * 0.3f, h * 0.5f)
            lineTo(w * 0.5f, h * 0.1f)
            lineTo(w * 0.7f, h * 0.5f)
            lineTo(w * 0.95f, h * 0.25f)
            lineTo(w * 0.9f, h * 0.8f)
            close()
        }
    drawPath(path, color)
    drawRect(color = color, topLeft = Offset(w * 0.1f, h * 0.85f), size = Size(w * 0.8f, h * 0.1f))
}
