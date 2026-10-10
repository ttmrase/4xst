package com.ttmrase.crownfall.ui.heroes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.hero.SummonKind
import com.ttmrase.crownfall.core.hero.Summoner
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameEvent
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.HeroRarity
import com.ttmrase.crownfall.core.model.SummonEntry
import com.ttmrase.crownfall.core.model.SummonResult
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.offers.OfferHooks
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.rarityColor
import kotlinx.coroutines.launch

private const val TEN = 10

/** 召喚画面（T3.5）。通常/プレミアム × 単発/10 連、天井表示、カード反転の簡易演出。 */
@Composable
fun SummonScreen(
    session: GameSession,
    data: GameData,
    hooks: OfferHooks,
    snackbarHostState: SnackbarHostState,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var kindIndex by rememberSaveable { mutableIntStateOf(1) }
    var result by remember { mutableStateOf<SummonResult?>(null) }
    var flipped by remember { mutableStateOf(setOf<Int>()) }
    val s = state ?: return

    val kind = if (kindIndex == 0) SummonKind.NORMAL else SummonKind.PREMIUM
    val table = Summoner.table(data, kind)
    val tickets = s.items[table.ticketItem] ?: 0
    val tavernBuilt = s.slots.any { it.type == BuildingType.TAVERN && it.level >= 1 }

    fun summon(times: Int) {
        scope.launch {
            when (val r = session.dispatch { Actions.summon(it, kind, times, data) }) {
                is ActionResult.Success -> {
                    result = r.events.filterIsInstance<GameEvent.HeroSummoned>().firstOrNull()?.result
                    flipped = emptySet()
                }
                is ActionResult.Failure -> {
                    val reason = r.reason
                    if (reason is ActionFailure.NotEnoughGems) hooks.onGemShortage(reason.missing)
                    if (reason is ActionFailure.NotEnoughResources) hooks.onResourceShortage(reason.missing)
                    snackbarHostState.showReplacing(failureMessage(context, reason))
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = kindIndex) {
            Tab(
                selected = kindIndex == 0,
                onClick = { kindIndex = 0 },
                text = { Text(stringResource(R.string.summon_normal)) },
            )
            Tab(
                selected = kindIndex == 1,
                onClick = { kindIndex = 1 },
                text = { Text(stringResource(R.string.summon_premium)) },
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.summon_title), style = MaterialTheme.typography.titleLarge)
            val until = Summoner.untilPity(s, data, kind)
            Text(
                if (until != null) {
                    stringResource(
                        R.string.summon_pity,
                        until,
                    )
                } else {
                    stringResource(R.string.summon_pity_none)
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(R.string.summon_tickets, tickets), style = MaterialTheme.typography.labelMedium)
            if (!tavernBuilt) {
                Text(stringResource(R.string.summon_tavern_locked), color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SummonButton(
                    label = stringResource(R.string.summon_once),
                    cost = costText(s, kind, 1, data),
                    enabled = tavernBuilt,
                    modifier = Modifier.weight(1f),
                ) { summon(1) }
                SummonButton(
                    label = stringResource(R.string.summon_ten),
                    cost = costText(s, kind, TEN, data),
                    enabled = tavernBuilt,
                    modifier = Modifier.weight(1f),
                ) { summon(TEN) }
            }
        }
        val res = result
        if (res != null) {
            SummonResultView(res = res, flipped = flipped, onFlip = { flipped = flipped + it }, onFlipAll = {
                flipped = res.results.indices.toSet()
            })
        }
    }
}

@Composable
private fun costText(
    state: GameState,
    kind: SummonKind,
    times: Int,
    data: GameData,
): String {
    val table = Summoner.table(data, kind)
    val tickets = state.items[table.ticketItem] ?: 0
    return when {
        tickets >= times -> stringResource(R.string.summon_cost_ticket, times)
        kind == SummonKind.NORMAL ->
            stringResource(R.string.summon_cost_gold, ShortNumber.format(if (times == 1) table.gold else table.goldTen))
        else ->
            stringResource(
                R.string.summon_cost_gems,
                ShortNumber.format(if (times == 1) table.gems else table.gemsTen),
            )
    }
}

@Composable
private fun SummonButton(
    label: String,
    cost: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(cost, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SummonResultView(
    res: SummonResult,
    flipped: Set<Int>,
    onFlip: (Int) -> Unit,
    onFlipAll: () -> Unit,
) {
    val hasSsr = res.results.any { it.rarity == HeroRarity.SSR }
    val hasSr = res.results.any { it.rarity == HeroRarity.SR }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            hasSsr ->
                Text(
                    stringResource(R.string.summon_ssr),
                    color = rarityColor(HeroRarity.SSR),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            hasSr && res.results.size >= TEN ->
                Text(
                    stringResource(R.string.summon_sr),
                    color = rarityColor(HeroRarity.SR),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.summon_tap_to_flip), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = onFlipAll) { Text(stringResource(R.string.summon_flip_all)) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (res.results.size > 1) 5 else 1),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(res.results) { index, entry ->
                SummonCard(entry = entry, flipped = index in flipped, onClick = { onFlip(index) })
            }
        }
    }
}

@Composable
private fun SummonCard(
    entry: SummonEntry,
    flipped: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "flip",
    )
    val density = LocalDensity.current.density
    val front = rotation > 90f
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .graphicsLayer {
                    rotationY = if (front) rotation - 180f else rotation
                    cameraDistance = 12f * density
                }
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        border = if (front) BorderStroke(2.dp, rarityColor(entry.rarity)) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            if (!front) {
                Text(stringResource(R.string.summon_back), style = MaterialTheme.typography.headlineMedium)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        entry.rarity.name,
                        color = rarityColor(entry.rarity),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        heroNameRes(entry.heroId)?.let { stringResource(it) } ?: entry.heroId,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                    )
                    if (entry.isNew) {
                        Text(
                            stringResource(R.string.summon_new),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        Text(
                            stringResource(R.string.summon_dup, entry.shards),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
