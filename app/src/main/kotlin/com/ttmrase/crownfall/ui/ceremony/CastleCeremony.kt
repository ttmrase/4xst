package com.ttmrase.crownfall.ui.ceremony

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.feature.glyph
import com.ttmrase.crownfall.ui.feature.nameRes
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GameButton
import com.ttmrase.crownfall.ui.kit.GameButtonSize
import com.ttmrase.crownfall.ui.kit.GameButtonStyle
import com.ttmrase.crownfall.ui.kit.GameDialogFrame
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.territory.PlotLayout
import com.ttmrase.crownfall.ui.territory.SpriteIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

private val NAVY = Color(0xFF12203F)
private val GOLD = Color(0xFFE8C547)
private const val LINE_MS = 650L
private const val FIRST_MS = 900L

/** 城の昇格の演出で順に出す 1 行。 */
sealed interface CeremonyLine {
    data class Feature(val feature: com.ttmrase.crownfall.core.feature.Feature) : CeremonyLine

    data class Building(val type: BuildingType) : CeremonyLine

    data class Storage(val delta: Long) : CeremonyLine

    data class Troops(val delta: Long) : CeremonyLine

    data class March(val delta: Int) : CeremonyLine
}

/** 城が [level] になって増えたもの（解放された機能 → 建てられる建物 → 倉庫・兵・行軍枠）。純関数。 */
fun castleCeremonyLines(
    data: GameData,
    level: Int,
): List<CeremonyLine> {
    val out = mutableListOf<CeremonyLine>()
    FeatureGate.newlyUnlocked(data, level - 1, level).forEach { out += CeremonyLine.Feature(it) }
    data.buildings.values.filter { def ->
        def.type != BuildingType.CASTLE && def.levels.firstOrNull()?.prereqs
            ?.any { it.type == BuildingType.CASTLE && it.level == level } == true
    }.sortedBy { it.type.ordinal }.forEach { out += CeremonyLine.Building(it.type) }
    val castle = data.building(BuildingType.CASTLE)

    fun delta(key: String): Long = castle.effect(level, key) - castle.effect(level - 1, key)
    delta("storage_cap_base").takeIf { it > 0 }?.let { out += CeremonyLine.Storage(it) }
    delta("troop_capacity").takeIf { it > 0 }?.let { out += CeremonyLine.Troops(it) }
    delta("march_slots").takeIf { it > 0 }?.let { out += CeremonyLine.March(it.toInt()) }
    return out
}

/**
 * 城のレベルアップの全画面演出（M11 T11.3）。「城 Lv n 達成！」→ 解放された機能・建てられる建物・増えた上限を 1 行ずつ。
 * タップ・OK・戻るでいつでも閉じられる（残りの行は飛ばす）。出ているあいだは [onActive] で知らせる
 * （機能の解放の演出とオファーは、これが終わってから出す）。
 */
@Composable
fun CastleCeremonyHost(
    session: GameSession,
    data: GameData,
    onActive: (Boolean) -> Unit,
) {
    val state by session.collectCalmState()
    val scope = rememberCoroutineScope()
    val castleLv = state?.let { FeatureGate.castleLevel(it) } ?: 0
    val celebrated = state?.celebratedCastleLv ?: 0
    // 閉じた直後、保存へ反映されるまでの間に同じ演出を出し直さない
    var closedUpTo by remember { mutableIntStateOf(0) }
    // 旧セーブ（記録なし）は、現在の Lv へ黙って追従する
    LaunchedEffect(castleLv, celebrated) {
        if (state != null && celebrated == 0 && castleLv > 0) {
            session.dispatch { Actions.celebrateCastle(it, castleLv) }
        }
    }
    val from = maxOf(celebrated, closedUpTo)
    val active = celebrated > 0 && castleLv > from
    LaunchedEffect(active) { onActive(active) }
    if (active) {
        CastleCeremony(castleLv, from + 1, data) {
            closedUpTo = castleLv
            scope.launch { session.dispatch { Actions.celebrateCastle(it, castleLv) } }
        }
    }
}

@Composable
private fun CastleCeremony(
    level: Int,
    firstLevel: Int,
    data: GameData,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // 飛び越えた Lv の解放も、順に全部出す（途中の Lv の行を落とさない）
    val lines = remember(level, firstLevel) { (firstLevel..level).flatMap { castleCeremonyLines(data, it) } }
    var shown by remember(level) { mutableIntStateOf(0) }
    val pop = remember(level) { Animatable(0.4f) }
    val animate = animationsEnabled()
    LaunchedEffect(level) {
        Sfx.play(context, SfxId.LEVELUP)
        if (animate) pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) else pop.snapTo(1f)
        delay(if (animate) FIRST_MS else 0L)
        while (shown < lines.size) {
            shown++
            Sfx.play(context, SfxId.TAP)
            delay(if (animate) LINE_MS else 0L)
        }
    }
    val spin by rememberInfiniteTransition(label = "rays").animateFloat(
        initialValue = 0f,
        targetValue = FULL_TURN,
        animationSpec = infiniteRepeatable(tween(RAY_TURN_MS, easing = LinearEasing)),
        label = "spin",
    )
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xE6060A16)).clickable(onClick = onClose)) {
            Box(Modifier.align(Alignment.Center).padding(horizontal = 24.dp)) {
                GameDialogFrame(
                    title = { Text(stringResource(R.string.castle_up_title, level)) },
                    style = FrameStyle.Royal,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(132.dp)
                                    .scale(pop.value)
                                    .drawBehind { sunburst(if (animate) spin else 0f) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter =
                                    painterResource(
                                        SpriteIds.building(BuildingType.CASTLE, PlotLayout.tierOfLevel(level)),
                                    ),
                                contentDescription = null,
                                modifier = Modifier.size(118.dp),
                            )
                        }
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .gameFrame(FrameStyle.Inset, corner = 12.dp)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            lines.forEachIndexed { i, line ->
                                Row(
                                    modifier = Modifier.alpha(if (i < shown) 1f else 0f),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GlyphIcon(lineGlyph(line), size = 24.dp)
                                    Text(lineText(line), color = Cf.Ivory, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        GameButton(
                            onClick = onClose,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            style = GameButtonStyle.Emerald,
                            size = GameButtonSize.Large,
                        ) { Text(stringResource(R.string.feature_unlock_ok)) }
                    }
                }
            }
        }
    }
}

/** 城の後ろで回る金の光条。 */
private fun DrawScope.sunburst(angle: Float) {
    val c = Offset(size.width / 2, size.height / 2)
    val r = size.minDimension * 0.62f
    drawCircle(Brush.radialGradient(listOf(Cf.Gold.copy(alpha = 0.55f), Color.Transparent), c, r), r, c)
    rotate(angle, c) {
        for (i in 0 until RAYS) {
            val a0 = (TWO_PI * i / RAYS).toFloat()
            val a1 = a0 + RAY_WIDTH
            val ray =
                Path().apply {
                    moveTo(c.x, c.y)
                    lineTo(c.x + r * cos(a0), c.y + r * sin(a0))
                    lineTo(c.x + r * cos(a1), c.y + r * sin(a1))
                    close()
                }
            drawPath(ray, Brush.radialGradient(listOf(Cf.GoldHi.copy(alpha = 0.5f), Color.Transparent), c, r))
        }
    }
}

private const val RAYS = 12
private const val RAY_WIDTH = 0.2f
private const val TWO_PI = 2 * Math.PI
private const val FULL_TURN = 360f
private const val RAY_TURN_MS = 16_000

private fun lineGlyph(line: CeremonyLine): Glyph =
    when (line) {
        is CeremonyLine.Feature -> line.feature.glyph()
        is CeremonyLine.Building -> Glyph.BUILD
        is CeremonyLine.Storage -> Glyph.CHEST
        is CeremonyLine.Troops -> Glyph.TROOPS
        is CeremonyLine.March -> Glyph.FLAG
    }

@Composable
private fun lineText(line: CeremonyLine): String =
    when (line) {
        is CeremonyLine.Feature -> stringResource(R.string.castle_up_feature, stringResource(line.feature.nameRes()))
        is CeremonyLine.Building -> stringResource(R.string.castle_up_building, stringResource(line.type.nameRes()))
        is CeremonyLine.Storage -> stringResource(R.string.castle_up_storage, ShortNumber.format(line.delta))
        is CeremonyLine.Troops -> stringResource(R.string.castle_up_troops, ShortNumber.format(line.delta))
        is CeremonyLine.March -> stringResource(R.string.castle_up_march, line.delta)
    }
