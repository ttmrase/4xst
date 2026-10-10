package com.ttmrase.crownfall.ui.city

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.session.UpgradePreview
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.descRes
import com.ttmrase.crownfall.ui.effectLabelRes
import com.ttmrase.crownfall.ui.effectValueText
import com.ttmrase.crownfall.ui.nameRes

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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val blocker = preview.blocker
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier =
                Modifier.fillMaxWidth().verticalScroll(
                    rememberScrollState(),
                ).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(preview.slot.type.nameRes()), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(preview.slot.type.descRes()), style = MaterialTheme.typography.bodyMedium)
            val next = preview.nextLevel
            if (next == null) {
                Text(stringResource(R.string.sheet_max_level), color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    stringResource(R.string.sheet_next_level, preview.slot.level, next),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                HorizontalDivider()
                EffectsSection(preview)
                HorizontalDivider()
                CostSection(state, preview)
                Text(stringResource(R.string.sheet_duration, DurationFormat.clock(preview.durationSec)))
                PrereqSection(preview)
            }
            HorizontalDivider()
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
        }
    }
}

@Composable
private fun EffectsSection(preview: UpgradePreview) {
    Text(stringResource(R.string.sheet_effects), style = MaterialTheme.typography.titleSmall)
    val keys = (preview.nextEffects.keys + preview.currentEffects.keys).filter { effectLabelRes(it) != null }
    keys.forEach { key ->
        val labelRes = effectLabelRes(key) ?: return@forEach
        val now = preview.currentEffects[key]
        val after = preview.nextEffects[key]
        val text =
            when {
                now == null && after != null -> "→ " + stringResource(labelRes, effectValueText(key, after))
                now != null && after != null && now != after ->
                    stringResource(labelRes, effectValueText(key, now)) + " → " + effectValueText(key, after)
                now != null -> stringResource(labelRes, effectValueText(key, now))
                else -> ""
            }
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CostSection(
    state: GameState,
    preview: UpgradePreview,
) {
    Text(stringResource(R.string.sheet_cost), style = MaterialTheme.typography.titleSmall)
    ResourceType.entries.forEach { type ->
        val cost = preview.cost[type]
        if (cost <= 0) return@forEach
        val missing = preview.missing[type]
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(type.nameRes()))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (missing > 0) {
                    Text(
                        stringResource(R.string.sheet_missing, ShortNumber.format(missing)),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "${ShortNumber.format(state.resources[type])} / ${ShortNumber.format(cost)}",
                    color = if (missing > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PrereqSection(preview: UpgradePreview) {
    if (preview.unmetPrereqs.isEmpty()) return
    Text(stringResource(R.string.sheet_prereq_title), style = MaterialTheme.typography.titleSmall)
    preview.unmetPrereqs.forEach { p ->
        Text(
            stringResource(R.string.sheet_prereq_item, stringResource(p.type.nameRes()), p.level),
            color = MaterialTheme.colorScheme.error,
        )
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
                Button(onClick = { onSpeedup(job.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sheet_speedup))
                }
            }
            blocker == ActionFailure.MaxLevel -> Unit
            blocker is ActionFailure.NotEnoughResources ->
                // オファー誘導はタップ時に 1 回だけ（表示中の副作用にしない。M6 で再検討）。
                OutlinedButton(onClick = { onResourceShortage(blocker.missing) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sheet_not_enough_resources), color = MaterialTheme.colorScheme.error)
                }
            blocker is ActionFailure.PrereqNotMet -> Unit
            blocker == ActionFailure.QueueFull -> {
                Text(stringResource(R.string.sheet_queue_full), color = MaterialTheme.colorScheme.error)
                if (secondBuilderLocked) {
                    OutlinedButton(onClick = onUnlockBuilder, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sheet_unlock_builder))
                    }
                }
            }
            blocker == null -> {
                val label = if (preview.slot.level == 0) R.string.sheet_construct else R.string.sheet_upgrade
                TutorialHighlight(active = tutorialBuildStep, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(label))
                    }
                }
            }
            else -> Unit
        }
    }
}
