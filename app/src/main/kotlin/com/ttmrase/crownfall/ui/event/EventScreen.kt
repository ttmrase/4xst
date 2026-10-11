package com.ttmrase.crownfall.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.EventDef
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.event.WeeklyEvents
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.remainingUntil
import com.ttmrase.crownfall.ui.portrait.PortraitKeys
import com.ttmrase.crownfall.ui.portrait.PortraitView
import com.ttmrase.crownfall.ui.shop.ContentsLines

fun eventNameRes(def: EventDef): Int =
    when (def.kind) {
        "HUNT" -> R.string.event_name_hunt
        "GATHER" -> R.string.event_name_gather
        else -> R.string.event_name_power
    }

private fun eventHowRes(def: EventDef): Int =
    when (def.kind) {
        "HUNT" -> R.string.event_how_hunt
        "GATHER" -> R.string.event_how_gather
        else -> R.string.event_how_power
    }

/** 週替わりイベント画面（T7.2）。残り時間・自分のスコア・順位表・順位報酬。 */
@Composable
fun EventScreen(
    session: GameSession,
    data: GameData,
) {
    val state by session.collectCalmState()
    // 画面を開いた = イベントの「最初の使用」（解放済みのときだけ印が付く）
    LaunchedEffect(Unit) { session.dispatch { Actions.recordEventOpened(it, data) } }
    val s = state ?: return
    val def = WeeklyEvents.current(s, data)
    val end = WeeklyEvents.endEpochSec(s)
    // 順位表とスコアは 1 回だけ計算する（戦力の計算を伴う）。順位の差は表から導く
    val self = WeeklyEvents.selfScore(s, data)
    val rows =
        remember(s.event, s.heroes, s.slots, s.research, s.nowEpochSec / SCORE_PERIOD_SEC) {
            WeeklyEvents.standings(s, data)
        }
    val mine = rows.firstOrNull { it.isPlayer }
    val rank = rows.indexOfFirst { it.isPlayer } + 1
    val top = rows.firstOrNull { !it.isPlayer }?.score ?: 0L
    val gap = maxOf(0L, top - (mine?.score ?: 0L))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(eventNameRes(def)), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(eventHowRes(def)), style = MaterialTheme.typography.bodyMedium)
                    RemainingLine(end, s.nowEpochSec)
                    Text(
                        stringResource(R.string.event_my_score, ShortNumber.format((self + s.event.bonusScore))),
                        fontWeight = FontWeight.Bold,
                    )
                    if (self < data.events.participation.minScore) {
                        Text(stringResource(R.string.event_not_joined), style = MaterialTheme.typography.bodySmall)
                    } else if (rank == 1) {
                        Text(stringResource(R.string.event_rank_first), color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text(stringResource(R.string.event_rank_gap, rank, ShortNumber.format(gap)))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.event_standings), style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(rows.take(TOP_ROWS), key = { i, _ -> i }) { i, row ->
            StandingLine(i + 1, row.name, row.alliance, row.score, row.isPlayer, s.avatar)
        }
        if (rank > TOP_ROWS) {
            item {
                val me = rows[rank - 1]
                StandingLine(rank, me.name, me.alliance, me.score, true, s.avatar)
            }
        }
        item { Text(stringResource(R.string.event_rewards), style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(data.events.rewards, key = { i, _ -> "r$i" }) { _, r ->
            Card {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    val label =
                        if (r.from == r.to) {
                            stringResource(R.string.event_rank_single, r.from)
                        } else {
                            stringResource(R.string.event_rank_range, r.from, r.to)
                        }
                    Text(label, fontWeight = FontWeight.Bold)
                    ContentsLines(r.reward, data)
                }
            }
        }
        item { Text(stringResource(R.string.event_note), style = MaterialTheme.typography.bodySmall) }
    }
}

private const val TOP_ROWS = 10
private const val SCORE_PERIOD_SEC = 30L

/** 残り時間。毎秒変わるのでここだけ再構図する。 */
@Composable
private fun RemainingLine(
    end: Long,
    fallbackNow: Long,
) {
    Text(stringResource(R.string.event_remaining, DurationFormat.clock(remainingUntil(end, fallbackNow))))
}

@Composable
private fun StandingLine(
    place: Int,
    name: String,
    alliance: String?,
    score: Long,
    isPlayer: Boolean,
    avatar: Int,
) {
    val colors =
        if (isPlayer) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PortraitView(if (isPlayer) PortraitKeys.avatar(avatar) else PortraitKeys.byName(name), name, 32.dp)
            val label = "$place  $name" + (alliance?.let { "  [$it]" } ?: "")
            Text(label, fontWeight = if (isPlayer) FontWeight.Bold else null, modifier = Modifier.weight(1f))
            Text(ShortNumber.format(score))
        }
    }
}
