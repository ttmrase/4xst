package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.session.UpgradePreview
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.ui.common.CostChips
import com.ttmrase.crownfall.ui.descRes
import com.ttmrase.crownfall.ui.effectLabelRes
import com.ttmrase.crownfall.ui.effectValueText
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GameBottomSheet
import com.ttmrase.crownfall.ui.kit.GameButton
import com.ttmrase.crownfall.ui.kit.GameButtonSize
import com.ttmrase.crownfall.ui.kit.GameButtonStyle
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.Pill
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.theme.CrownFonts
import com.ttmrase.crownfall.ui.theme.GoldText

/** 建物詳細シート（効果・コスト・所要時間・前提・アップグレード）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpgradeSheet(
    state: GameState,
    preview: UpgradePreview,
    secondBuilderLocked: Boolean,
    tutorialBuildStep: Boolean,
    onUpgrade: () -> Unit,
    onSpeedup: (Long) -> Unit,
    onResourceShortage: (Resources) -> Unit,
    onUnlockBuilder: () -> Unit,
    onDismiss: () -> Unit,
    extraActions: @Composable () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blocker = preview.blocker
    GameBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier.fillMaxWidth().verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val next = preview.nextLevel
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                BuildingArt(preview.slot.type, preview.slot.level, size = 104.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GoldText(
                        stringResource(preview.slot.type.nameRes()),
                        style =
                            MaterialTheme.typography.headlineSmall.copy(
                                shadow = Shadow(Cf.Ink, Offset(0f, 2f), 3f),
                            ),
                    )
                    Text(
                        stringResource(preview.slot.type.descRes()),
                        style = MaterialTheme.typography.bodySmall,
                        color = Cf.Muted,
                    )
                    if (next == null) {
                        Pill(glyph = Glyph.STAR) { Text(stringResource(R.string.sheet_max_level)) }
                    } else {
                        Row(
                            modifier =
                                Modifier
                                    .gameFrame(FrameStyle.Success, corner = 10.dp)
                                    .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlyphIcon(Glyph.ARROW_UP, size = 18.dp)
                            Text(
                                stringResource(R.string.sheet_next_level, preview.slot.level, next),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontFamily = CrownFonts.Numeric,
                                fontSize = 15.sp,
                            )
                        }
                    }
                }
            }
            if (next != null) {
                EffectsSection(preview)
                CostSection(state, preview)
                PrereqSection(preview)
            }
            ActionSection(
                preview = preview,
                state = state,
                secondBuilderLocked = secondBuilderLocked,
                tutorialBuildStep = tutorialBuildStep,
                onUpgrade = onUpgrade,
                onSpeedup = onSpeedup,
                onResourceShortage = onResourceShortage,
                onUnlockBuilder = onUnlockBuilder,
            )
            extraActions()
        }
    }
}

/** 節の見出し（小さな金のひし形 + 文字）。 */
@Composable
private fun SectionLabel(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(8.dp).drawBehind {
                val p =
                    Path().apply {
                        moveTo(size.width / 2, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width / 2, size.height)
                        lineTo(0f, size.height / 2)
                        close()
                    }
                drawPath(p, Cf.Gold)
            },
        )
        Text(text, style = MaterialTheme.typography.titleSmall, color = Cf.GoldHi)
    }
}

@Composable
private fun EffectsSection(preview: UpgradePreview) {
    val keys = (preview.nextEffects.keys + preview.currentEffects.keys).filter { effectLabelRes(it) != null }
    if (keys.isEmpty()) return
    SectionLabel(stringResource(R.string.sheet_effects))
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .gameFrame(FrameStyle.Inset, corner = 12.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        keys.forEach { key ->
            val labelRes = effectLabelRes(key) ?: return@forEach
            val now = preview.currentEffects[key]
            val after = preview.nextEffects[key]
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    now == null && after != null -> {
                        GlyphIcon(Glyph.PLUS, size = 14.dp)
                        Text(
                            " " + stringResource(labelRes, effectValueText(key, after)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cf.EmeraldHi,
                        )
                    }
                    now != null && after != null && now != after -> {
                        Text(
                            stringResource(labelRes, effectValueText(key, now)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        GlyphIcon(Glyph.ARROW_UP, size = 14.dp)
                        Text(
                            " " + effectValueText(key, after),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Cf.EmeraldHi,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    now != null ->
                        Text(
                            stringResource(labelRes, effectValueText(key, now)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                }
            }
        }
    }
}

@Composable
private fun CostSection(
    state: GameState,
    preview: UpgradePreview,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.sheet_cost)) }
        Pill(glyph = Glyph.CLOCK) {
            Text(stringResource(R.string.sheet_duration, DurationFormat.clock(preview.durationSec)))
        }
    }
    CostChips(have = state.resources, cost = preview.cost, missing = preview.missing)
}

@Composable
private fun PrereqSection(preview: UpgradePreview) {
    if (preview.unmetPrereqs.isEmpty()) return
    SectionLabel(stringResource(R.string.sheet_prereq_title))
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .gameFrame(FrameStyle.Alarm, corner = 12.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        preview.unmetPrereqs.forEach { p ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.LOCK, size = 18.dp)
                Text(
                    stringResource(R.string.sheet_prereq_item, stringResource(p.type.nameRes()), p.level),
                    color = Color(0xFFFFD9D2),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ActionSection(
    preview: UpgradePreview,
    state: GameState,
    secondBuilderLocked: Boolean,
    tutorialBuildStep: Boolean,
    onUpgrade: () -> Unit,
    onSpeedup: (Long) -> Unit,
    onResourceShortage: (Resources) -> Unit,
    onUnlockBuilder: () -> Unit,
) {
    val job = preview.upgrading
    val blocker = preview.blocker
    Column(modifier = Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            job != null -> {
                Text(
                    stringResource(R.string.sheet_upgrading) + "  " +
                        DurationFormat.clock(maxOf(0L, job.endEpochSec - state.nowEpochSec)),
                    style = MaterialTheme.typography.titleMedium,
                )
                GameButton(
                    onClick = { onSpeedup(job.id) },
                    modifier = Modifier.fillMaxWidth(),
                    size = GameButtonSize.Large,
                ) {
                    GlyphIcon(Glyph.SPEEDUP, size = 24.dp)
                    Text(stringResource(R.string.sheet_speedup))
                }
            }
            blocker == ActionFailure.MaxLevel -> Unit
            blocker is ActionFailure.NotEnoughResources ->
                // オファー誘導はタップ時に 1 回だけ（表示中の副作用にしない。M6 で再検討）。
                GameButton(
                    onClick = { onResourceShortage(blocker.missing) },
                    modifier = Modifier.fillMaxWidth(),
                    style = GameButtonStyle.Ruby,
                    size = GameButtonSize.Large,
                ) {
                    GlyphIcon(Glyph.PLUS, size = 22.dp)
                    Text(stringResource(R.string.sheet_not_enough_resources))
                }
            blocker is ActionFailure.PrereqNotMet -> Unit
            blocker == ActionFailure.QueueFull -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.ALERT, size = 18.dp)
                    Text(stringResource(R.string.sheet_queue_full), color = Color(0xFFFFB3A8))
                }
                if (secondBuilderLocked) {
                    GameButton(
                        onClick = onUnlockBuilder,
                        modifier = Modifier.fillMaxWidth(),
                        style = GameButtonStyle.Amethyst,
                        size = GameButtonSize.Large,
                    ) {
                        GlyphIcon(Glyph.BUILD, size = 24.dp)
                        Text(stringResource(R.string.sheet_unlock_builder))
                    }
                }
            }
            blocker == null -> {
                val label = if (preview.slot.level == 0) R.string.sheet_construct else R.string.sheet_upgrade
                TutorialHighlight(active = tutorialBuildStep, modifier = Modifier.fillMaxWidth()) {
                    GameButton(
                        onClick = onUpgrade,
                        modifier = Modifier.fillMaxWidth(),
                        style = GameButtonStyle.Emerald,
                        size = GameButtonSize.Large,
                        sound = true,
                    ) {
                        GlyphIcon(Glyph.BUILD, size = 24.dp)
                        Text(stringResource(label), fontSize = 17.sp)
                    }
                }
            }
            else -> Unit
        }
    }
}
