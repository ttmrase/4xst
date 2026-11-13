package com.ttmrase.crownfall.ui.barracks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.TroopType
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.session.UiQueries
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.city.SpeedupHost
import com.ttmrase.crownfall.ui.common.CostRows
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.kit.GameButton
import com.ttmrase.crownfall.ui.kit.GameDivider
import com.ttmrase.crownfall.ui.kit.GameOutlinedButton
import com.ttmrase.crownfall.ui.kit.GameProgressBar
import com.ttmrase.crownfall.ui.kit.GameTab
import com.ttmrase.crownfall.ui.kit.GameTabRow
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.troopName
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import com.ttmrase.crownfall.ui.glyph
import com.ttmrase.crownfall.ui.kit.Cf
import com.ttmrase.crownfall.ui.kit.FrameStyle
import com.ttmrase.crownfall.ui.kit.GameButtonSize
import com.ttmrase.crownfall.ui.kit.GameButtonStyle
import com.ttmrase.crownfall.ui.kit.Glyph
import com.ttmrase.crownfall.ui.kit.GlyphIcon
import com.ttmrase.crownfall.ui.kit.Pill
import com.ttmrase.crownfall.ui.kit.gameFrame
import com.ttmrase.crownfall.ui.theme.GoldText

/** 兵舎画面（T3.5）。兵種タブ・Tier 選択・数量スライダー・コストと時間・訓練・加速。 */
@Composable
fun BarracksScreen(
    session: GameSession,
    data: GameData,
    hooks: OfferHooks,
    snackbarHostState: SnackbarHostState,
    initialTab: Int,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, TroopType.entries.lastIndex)) }
    var tierIndex by rememberSaveable { mutableIntStateOf(0) }
    var amount by rememberSaveable { mutableFloatStateOf(1f) }
    var speedupJobId by rememberSaveable { mutableStateOf<Long?>(null) }
    val s = state ?: return

    val type = TroopType.entries[tab]
    val tiers = UiQueries.troopTiers(s, type, data)
    val view = tiers.getOrNull(tierIndex.coerceIn(0, maxOf(0, tiers.lastIndex))) ?: return
    val troop = view.troop
    val maxCount = view.maxTrainable
    val count = if (maxCount <= 0) 1L else amount.roundToLong().coerceIn(1L, maxCount)
    val preview = UiQueries.trainPreview(s, troop.id, count, data)
    val blocker = preview?.blocker

    Column(modifier = Modifier.fillMaxSize()) {
        GameTabRow(selectedTabIndex = tab) {
            TroopType.entries.forEachIndexed { index, t ->
                val built = UiQueries.troopTiers(s, t, data).firstOrNull()?.let { !it.locked } ?: false
                GameTab(
                    selected = tab == index,
                    onClick = {
                        tab = index
                        tierIndex = 0
                        amount = 1f
                    },
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlyphIcon(if (built) t.glyph() else Glyph.LOCK, size = 18.dp)
                            Text(stringResource(t.nameRes()), maxLines = 1)
                        }
                    },
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiers.forEachIndexed { index, t ->
                    val tier = stringResource(R.string.tier_label, t.troop.tier)
                    GameButton(
                        onClick = {
                            tierIndex = index
                            amount = 1f
                        },
                        modifier = Modifier.weight(1f),
                        style = if (index == tierIndex) GameButtonStyle.Gold else GameButtonStyle.Ghost,
                        size = GameButtonSize.Small,
                    ) {
                        if (t.locked) GlyphIcon(Glyph.LOCK, size = 14.dp)
                        Text(tier)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // 兵種の紋章（くぼんだ台座に大きなアイコン。未解放は色あせる）
                Box(
                    modifier = Modifier.size(92.dp).gameFrame(FrameStyle.Inset, corner = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(troop.type.glyph(), size = 66.dp, dimmed = view.locked, alpha = if (view.locked) 0.6f else 1f)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    GoldText(troopName(context, troop), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        stringResource(R.string.barracks_owned, ShortNumber.format(view.owned)) +
                            "  " + stringResource(R.string.barracks_wounded, ShortNumber.format(view.wounded)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Cf.Muted,
                    )
                    Text(
                        stringResource(
                            R.string.troop_stats,
                            ShortNumber.format(troop.attack),
                            ShortNumber.format(troop.defense),
                            ShortNumber.format(troop.hp),
                            ShortNumber.format(troop.speed),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        modifier =
                            Modifier
                                .gameFrame(FrameStyle.Inset, corner = 8.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (view.locked) {
                Text(
                    stringResource(
                        R.string.troop_unlock_at,
                        stringResource(troop.type.trainingBuilding.nameRes()),
                        view.requiredBuildingLevel,
                    ),
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                if (maxCount > 1) {
                    Text(
                        stringResource(R.string.barracks_amount, ShortNumber.format(count)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Slider(
                        value = amount.coerceIn(1f, maxCount.toFloat()),
                        onValueChange = { amount = it },
                        valueRange = 1f..maxCount.toFloat(),
                    )
                } else {
                    Text(
                        stringResource(R.string.barracks_amount, ShortNumber.format(count)),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (preview != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.barracks_cost),
                            style = MaterialTheme.typography.titleSmall,
                            color = Cf.GoldHi,
                            modifier = Modifier.weight(1f),
                        )
                        Pill(glyph = Glyph.CLOCK) {
                            Text(stringResource(R.string.barracks_duration, DurationFormat.clock(preview.durationSec)))
                        }
                    }
                    CostRows(have = s.resources, cost = preview.cost, missing = preview.missing)
                }
            }
            val running = preview?.running
            if (running != null) {
                Text(stringResource(R.string.barracks_training), style = MaterialTheme.typography.titleSmall)
                GameProgressBar(
                    progress = { UiQueries.progress(s, running) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(DurationFormat.clock(UiQueries.remainingSec(s, running)))
                    GameButton(onClick = { speedupJobId = running.id }, size = GameButtonSize.Small) {
                        GlyphIcon(Glyph.SPEEDUP, size = 18.dp)
                        Text(stringResource(R.string.sheet_speedup))
                    }
                }
            }
            if (!view.locked) {
                when {
                    blocker is ActionFailure.NotEnoughResources ->
                        GameButton(
                            onClick = { hooks.onResourceShortage(blocker.missing) },
                            modifier = Modifier.fillMaxWidth(),
                            style = GameButtonStyle.Ruby,
                            size = GameButtonSize.Large,
                        ) {
                            GlyphIcon(Glyph.PLUS, size = 22.dp)
                            Text(stringResource(R.string.barracks_not_enough))
                        }
                    blocker == ActionFailure.QueueFull ->
                        Text(stringResource(R.string.barracks_queue_full), color = MaterialTheme.colorScheme.error)
                    blocker != null ->
                        Text(failureMessage(context, blocker), color = MaterialTheme.colorScheme.error)
                    else ->
                        GameButton(
                            onClick = {
                                scope.launch {
                                    when (
                                        val r =
                                            session.dispatch {
                                                Actions.startTraining(
                                                    it,
                                                    troop.id,
                                                    count,
                                                    data,
                                                )
                                            }
                                    ) {
                                        is ActionResult.Success -> amount = 1f
                                        is ActionResult.Failure ->
                                            snackbarHostState.showReplacing(
                                                failureMessage(context, r.reason),
                                            )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            style = GameButtonStyle.Emerald,
                            size = GameButtonSize.Large,
                            sound = true,
                        ) {
                            GlyphIcon(troop.type.glyph(), size = 26.dp)
                            Text(stringResource(R.string.barracks_train), fontSize = 17.sp)
                        }
                }
            }
        }
    }

    val jobId = speedupJobId
    if (jobId != null) {
        SpeedupHost(
            session = session,
            data = data,
            hooks = hooks,
            snackbarHostState = snackbarHostState,
            jobId = jobId,
            onSuccess = {},
            onDismiss = { speedupJobId = null },
        )
    }
}
