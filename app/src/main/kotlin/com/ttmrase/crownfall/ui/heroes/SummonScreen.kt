package com.ttmrase.crownfall.ui.heroes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.hero.RitualPlan
import com.ttmrase.crownfall.core.hero.SummonKind
import com.ttmrase.crownfall.core.hero.SummonRitual
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
import com.ttmrase.crownfall.ui.common.Sfx
import com.ttmrase.crownfall.ui.common.SfxId
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.heroNameRes
import com.ttmrase.crownfall.ui.rarityColor
import com.ttmrase.crownfall.ui.theme.PressButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val FLIP_AFTER_MS = 450L
private const val SINGLE_CARD_DP = 190
private const val CARD_ASPECT = 0.7f
private const val GRID_COLUMNS = 5

/** 進行中の召喚演出。[result] は確定済みで、演出は [plan] に従って見せるだけ。[salt] は台本の再現用。 */
private class Ritual(
    val result: SummonResult,
    val plan: RitualPlan,
    val times: Int,
    val kind: SummonKind,
    val salt: Long,
)

private const val SEP = "|"

private fun entryLine(e: SummonEntry): String = listOf(e.heroId, e.rarity.name, e.isNew, e.shards).joinToString(SEP)

/** 画面回転でも結果が消えないよう、演出を保存・復元する（台本は結果から作り直す）。 */
private fun ritualSaver(data: GameData): Saver<Ritual?, Any> =
    Saver(
        save = { r ->
            r?.let {
                arrayListOf<Any>(
                    it.times,
                    it.kind.ordinal,
                    it.result.pityAfter,
                    it.salt,
                    ArrayList(it.result.results.map { e -> entryLine(e) }),
                )
            } ?: arrayListOf<Any>()
        },
        restore = { raw ->
            val l = raw as? List<*> ?: return@Saver null
            if (l.size < 5) return@Saver null
            val entries =
                (l[4] as List<*>).map { line ->
                    val f = (line as String).split(SEP)
                    SummonEntry(f[0], HeroRarity.valueOf(f[1]), f[2].toBoolean(), f[3].toInt())
                }
            val result = SummonResult(entries, l[2] as Int)
            val salt = l[3] as Long
            Ritual(
                result,
                SummonRitual.plan(result, data.heroRules.presentation, salt),
                l[0] as Int,
                SummonKind.entries[l[1] as Int],
                salt,
            )
        },
    )

/**
 * 召喚画面（T3.5 / T10.5）。通常/プレミアム × 単発/10 連。召喚するとフルスクリーンの儀式演出 →
 * カードめくり → 結果（天井メーター・もう一度！/10 連）。戻るは演出スキップ → 結果 → 閉じる、の順に必ず進む。
 */
@Composable
fun SummonScreen(
    session: GameSession,
    data: GameData,
    hooks: OfferHooks,
    snackbarHostState: SnackbarHostState,
) {
    val state by session.collectCalmState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var kindIndex by rememberSaveable { mutableIntStateOf(1) }
    var ritual by rememberSaveable(stateSaver = ritualSaver(data)) { mutableStateOf<Ritual?>(null) }
    var animating by rememberSaveable { mutableStateOf(false) }
    var flippedList by rememberSaveable { mutableStateOf(arrayListOf<Int>()) }
    val flipped: Set<Int> = flippedList.toSet()
    var reveal by remember { mutableStateOf<SummonEntry?>(null) }
    // 召喚の dispatch が返るまで再入を拒否する（連打で二重に召喚しない）
    var inFlight by remember { mutableStateOf(false) }
    val s = state ?: return

    val kind = if (kindIndex == 0) SummonKind.NORMAL else SummonKind.PREMIUM
    val table = Summoner.table(data, kind)
    val tickets = s.items[table.ticketItem] ?: 0
    val tavernBuilt = s.slots.any { it.type == BuildingType.TAVERN && it.level >= 1 }

    suspend fun handleSummon(
        times: Int,
        useKind: SummonKind,
    ) {
        when (val r = session.dispatch { Actions.summon(it, useKind, times, data) }) {
            is ActionResult.Success -> {
                val res = r.events.filterIsInstance<GameEvent.HeroSummoned>().firstOrNull()?.result
                if (res != null) {
                    val salt = r.state.rngState
                    val plan = SummonRitual.plan(res, data.heroRules.presentation, salt)
                    ritual = Ritual(res, plan, times, useKind, salt)
                    flippedList = arrayListOf()
                    reveal = null
                    animating = true
                }
            }
            is ActionResult.Failure -> {
                val reason = r.reason
                if (reason is ActionFailure.NotEnoughGems) hooks.onGemShortage(reason.missing)
                if (reason is ActionFailure.NotEnoughResources) hooks.onResourceShortage(reason.missing)
                snackbarHostState.showReplacing(failureMessage(context, reason))
            }
        }
    }

    fun summon(
        times: Int,
        useKind: SummonKind = kind,
    ) {
        if (inFlight) return
        inFlight = true
        scope.launch {
            try {
                handleSummon(times, useKind)
            } finally {
                inFlight = false
            }
        }
    }

    fun flip(indices: Set<Int>) {
        val r = ritual ?: return
        val fresh = indices - flipped
        if (fresh.isEmpty()) return
        flippedList = ArrayList(flipped + fresh)
        val best = fresh.map { r.result.results[it] }.maxByOrNull { it.rarity }
        if (best != null && best.rarity == HeroRarity.SSR) {
            reveal = best
        } else {
            Sfx.play(context, if (best != null && best.rarity == HeroRarity.SR) SfxId.RARE else SfxId.FLIP)
        }
    }

    BackHandler(enabled = ritual != null) {
        when {
            reveal != null -> reveal = null
            animating -> animating = false
            else -> ritual = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SummonSetup(
                s = s,
                kindIndex = kindIndex,
                onKind = { kindIndex = it },
                data = data,
                kind = kind,
                tickets = tickets,
                tavernBuilt = tavernBuilt,
                onSummon = { summon(it) },
            )
        }
        val current = ritual
        if (current != null) {
            Box(modifier = Modifier.fillMaxSize().background(RitualBackground).pointerInput(Unit) {}) {
                if (animating) {
                    RitualAnimation(plan = current.plan, onDone = { animating = false })
                } else {
                    SummonResults(
                        ritual = current,
                        flipped = flipped,
                        data = data,
                        state = s,
                        onFlip = { flip(setOf(it)) },
                        onFlipAll = { flip(current.result.results.indices.toSet()) },
                        onAgain = { summon(current.times, current.kind) },
                        onTen = { summon(Actions.TEN_ROLL, current.kind) },
                        onClose = { ritual = null },
                    )
                }
            }
        }
        val shown = reveal
        if (shown != null) SsrReveal(entry = shown, onDismiss = { reveal = null })
    }
}

@Composable
private fun SummonSetup(
    s: GameState,
    kindIndex: Int,
    onKind: (Int) -> Unit,
    data: GameData,
    kind: SummonKind,
    tickets: Int,
    tavernBuilt: Boolean,
    onSummon: (Int) -> Unit,
) {
    TabRow(selectedTabIndex = kindIndex) {
        Tab(
            selected = kindIndex == 0,
            onClick = { onKind(0) },
            text = { Text(stringResource(R.string.summon_normal)) },
        )
        Tab(
            selected = kindIndex == 1,
            onClick = { onKind(1) },
            text = { Text(stringResource(R.string.summon_premium)) },
        )
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.summon_title), style = MaterialTheme.typography.titleLarge)
        val until = Summoner.untilPity(s, data, kind)
        val pityAt = Summoner.table(data, kind).pityAt
        if (until != null) {
            PityMeter(remaining = until, pityAt = pityAt)
        } else {
            Text(stringResource(R.string.summon_pity_none), style = MaterialTheme.typography.titleSmall)
        }
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
            ) { onSummon(1) }
            SummonButton(
                label = stringResource(R.string.summon_ten_deal),
                cost = costText(s, kind, Actions.TEN_ROLL, data),
                enabled = tavernBuilt,
                modifier = Modifier.weight(1f),
            ) { onSummon(Actions.TEN_ROLL) }
        }
    }
}

/** 大きな天井メーター。[remaining] 回で SSR 確定。 */
@Composable
private fun PityMeter(
    remaining: Int,
    pityAt: Int,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    val progress = if (pityAt > 0) ((pityAt - remaining).toFloat() / pityAt).coerceIn(0f, 1f) else 0f
    val textColor = if (onDark) Color.White else MaterialTheme.colorScheme.primary
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pluralStringResource(R.plurals.summon_pity_big, remaining, remaining),
            color = textColor,
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(
            progress = { progress },
            color = rarityColor(HeroRarity.SSR),
            modifier = Modifier.fillMaxWidth().height(12.dp),
        )
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
    PressButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(cost, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SummonResults(
    ritual: Ritual,
    flipped: Set<Int>,
    data: GameData,
    state: GameState,
    onFlip: (Int) -> Unit,
    onFlipAll: () -> Unit,
    onAgain: () -> Unit,
    onTen: () -> Unit,
    onClose: () -> Unit,
) {
    val res = ritual.result
    val single = res.results.size == 1
    LaunchedEffect(ritual) {
        if (single) {
            delay(FLIP_AFTER_MS)
            onFlip(0)
        }
    }
    val table = Summoner.table(data, ritual.kind)
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            when (ritual.plan.top) {
                HeroRarity.SSR -> stringResource(R.string.summon_result_ssr)
                HeroRarity.SR -> stringResource(R.string.summon_result_sr)
                else -> stringResource(R.string.summon_title)
            },
            color = rarityColor(ritual.plan.top),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!single) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.summon_tap_to_flip),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                OutlinedButton(onClick = onFlipAll) {
                    Text(stringResource(R.string.summon_flip_all), color = Color.White)
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (single) {
                SummonCard(
                    entry = res.results[0],
                    flipped = 0 in flipped,
                    onClick = { onFlip(0) },
                    modifier = Modifier.width(SINGLE_CARD_DP.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(GRID_COLUMNS),
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
        if (table.pityAt > 0) {
            PityMeter(remaining = table.pityAt - res.pityAfter, pityAt = table.pityAt, onDark = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SummonButton(
                label = stringResource(R.string.summon_again),
                cost = costText(state, ritual.kind, ritual.times, data),
                enabled = true,
                modifier = Modifier.weight(1f),
                onClick = onAgain,
            )
            if (ritual.times != Actions.TEN_ROLL) {
                SummonButton(
                    label = stringResource(R.string.summon_ten_deal),
                    cost = costText(state, ritual.kind, Actions.TEN_ROLL, data),
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = onTen,
                )
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(stringResource(R.string.summon_close), color = Color.White)
        }
    }
}

@Composable
private fun SummonCard(
    entry: SummonEntry,
    flipped: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "flip",
    )
    val density = LocalDensity.current.density
    val front = rotation > 90f
    val frame = rarityColor(entry.rarity)
    val glow = front && entry.rarity >= HeroRarity.SR
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .aspectRatio(CARD_ASPECT)
                .graphicsLayer {
                    rotationY = if (front) rotation - 180f else rotation
                    cameraDistance = 12f * density
                }
                .drawBehind {
                    if (glow) {
                        drawRect(Brush.radialGradient(listOf(frame.copy(alpha = 0.55f), Color.Transparent)))
                    }
                }
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = if (front) Color(0xFF1A2236) else Color(0xFF28324D)),
        border = if (front) BorderStroke(if (entry.rarity >= HeroRarity.SR) 3.dp else 2.dp, frame) else null,
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(4.dp), contentAlignment = Alignment.Center) {
            if (!front) {
                Text(stringResource(R.string.summon_back), style = MaterialTheme.typography.headlineMedium)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(entry.rarity.name, color = frame, style = MaterialTheme.typography.titleSmall)
                    Text(
                        heroNameRes(entry.heroId)?.let { stringResource(it) } ?: entry.heroId,
                        color = Color.White,
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
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
