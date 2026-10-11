package com.ttmrase.crownfall.ui.ceremony

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.feature.FeatureGate
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.animationsEnabled
import com.ttmrase.crownfall.ui.feature.emoji
import com.ttmrase.crownfall.ui.feature.nameRes
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.theme.CrownFonts
import kotlinx.coroutines.delay

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
    var level by remember { mutableIntStateOf(0) }
    LaunchedEffect(session) {
        session.events.collect { e ->
            if (e is GameEvent.BuildingLevelUp && e.type == BuildingType.CASTLE && e.newLevel >= 2) {
                level = maxOf(level, e.newLevel)
            }
        }
    }
    LaunchedEffect(level) { onActive(level > 0) }
    if (level > 0) CastleCeremony(level, data) { level = 0 }
}

@Composable
private fun CastleCeremony(
    level: Int,
    data: GameData,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lines = remember(level) { castleCeremonyLines(data, level) }
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
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xE60B0A12)).clickable(onClick = onClose)) {
            Column(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .background(NAVY, RoundedCornerShape(20.dp))
                        .border(2.dp, GOLD, RoundedCornerShape(20.dp))
                        .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("🏰", fontSize = 56.sp, modifier = Modifier.scale(pop.value))
                Text(
                    stringResource(R.string.castle_up_title, level),
                    color = GOLD,
                    fontFamily = CrownFonts.Display,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.scale(pop.value),
                )
                lines.forEachIndexed { i, line ->
                    Text(
                        lineText(line),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.alpha(if (i < shown) 1f else 0f),
                    )
                }
                Button(onClick = onClose) { Text(stringResource(R.string.feature_unlock_ok)) }
            }
        }
    }
}

@Composable
private fun lineText(line: CeremonyLine): String =
    when (line) {
        is CeremonyLine.Feature ->
            stringResource(R.string.castle_up_feature, line.feature.emoji(), stringResource(line.feature.nameRes()))
        is CeremonyLine.Building -> stringResource(R.string.castle_up_building, stringResource(line.type.nameRes()))
        is CeremonyLine.Storage -> stringResource(R.string.castle_up_storage, ShortNumber.format(line.delta))
        is CeremonyLine.Troops -> stringResource(R.string.castle_up_troops, ShortNumber.format(line.delta))
        is CeremonyLine.March -> stringResource(R.string.castle_up_march, line.delta)
    }
