package com.ttmrase.crownfall.ui.alliance

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.alliance.AllianceTech
import com.ttmrase.crownfall.core.alliance.DonationKind
import com.ttmrase.crownfall.core.alliance.Personas
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.PersonaDef
import com.ttmrase.crownfall.core.map.TileRef
import com.ttmrase.crownfall.core.march.MarchKind
import com.ttmrase.crownfall.core.model.AiRally
import com.ttmrase.crownfall.core.model.ChatMessage
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.RequestKind
import com.ttmrase.crownfall.core.model.TimerKind
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionFailure
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.DurationFormat
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.remainingUntil
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.itemName
import com.ttmrase.crownfall.ui.map.FormationSheet
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.namedString
import com.ttmrase.crownfall.ui.personaName
import kotlinx.coroutines.launch

private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val MAX_LOGIN_HOURS = 24
private const val ONLINE_HOURS = 0
private val TAB_TITLES =
    listOf(
        R.string.alliance_tab_members,
        R.string.alliance_tab_help,
        R.string.alliance_tab_requests,
        R.string.alliance_tab_tech,
        R.string.alliance_tab_gifts,
    )

/**
 * 同盟画面（T5.5）。メンバー（R1〜R5・城 Lv・最終ログイン・VIP）、ヘルプ、要請とラリー、
 * 寄付・同盟ショップ、ギフト箱。文面はここでは作らない（チャットは [com.ttmrase.crownfall.ui.chat.ChatScreen]）。
 */
@Composable
fun AllianceScreen(
    session: GameSession,
    data: GameData,
    snackbarHostState: SnackbarHostState,
    onOpenChat: () -> Unit,
) {
    val state by session.collectCalmState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var rallyId by rememberSaveable { mutableStateOf<Long?>(null) }
    var rallyError by rememberSaveable { mutableStateOf<String?>(null) }
    var sendingRally by remember { mutableStateOf(false) }
    val s = state ?: return

    fun run(block: (GameState) -> ActionResult) {
        scope.launch {
            val r = session.dispatch(block)
            // 押し直しで出る「ヘルプする相手がいない」は黙って無視する。
            if (r is ActionResult.Failure && r.reason != ActionFailure.NothingToHelp) {
                snackbarHostState.showReplacing(failureMessage(context, r.reason))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(s, data, onOpenChat)
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TAB_TITLES.forEachIndexed { i, title ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(stringResource(title)) })
            }
        }
        when (tab) {
            0 -> MembersTab(s, data)
            1 -> HelpTab(s, data) { run { st -> Actions.helpAll(st, data) } }
            2 ->
                RequestsTab(
                    s,
                    data,
                    onFulfill = { id -> run { st -> Actions.fulfillRequest(st, id, data) } },
                    onJoin = { id ->
                        rallyError = null
                        rallyId = id
                    },
                )
            3 ->
                TechTab(
                    s,
                    data,
                    onDonate = { tech, kind -> run { st -> Actions.donate(st, tech, kind, data) } },
                    onBuy = { id -> run { st -> Actions.buyAllianceShop(st, id, data) } },
                )
            else -> GiftsTab(s) { id -> run { st -> Actions.openGift(st, id, data) } }
        }
    }

    val rally = s.alliance.rallies.firstOrNull { it.id == rallyId }
    if (rally != null) {
        FormationSheet(
            state = s,
            data = data,
            kind = MarchKind.RALLY,
            target = rally.target,
            error = rallyError,
            sending = sendingRally,
            onSend = { troops, heroes ->
                if (!sendingRally) {
                    sendingRally = true
                    scope.launch {
                        val r = session.dispatch { Actions.joinRally(it, rally.id, troops, heroes, data) }
                        if (r is ActionResult.Failure) {
                            rallyError = failureMessage(context, r.reason)
                        } else {
                            rallyId = null
                        }
                        sendingRally = false
                    }
                }
            },
            onDismiss = { rallyId = null },
        )
    }
}

@Composable
private fun Header(
    s: GameState,
    data: GameData,
    onOpenChat: () -> Unit,
) {
    val unread =
        remember(s.alliance.history, s.alliance.chatRead) { unreadTotal(s.alliance.history, s.alliance.chatRead) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(stringResource(R.string.alliance_points, ShortNumber.format(s.alliance.points)))
            Text(
                stringResource(
                    R.string.alliance_helped_today,
                    s.alliance.helpedToday,
                    data.allianceRules.help.dailyLimit,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedButton(onClick = onOpenChat) {
            BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                Text(stringResource(R.string.alliance_open_chat))
            }
        }
    }
}

/** 既読より新しい、プレイヤー以外の発言の総数。 */
fun unreadTotal(
    history: Map<String, List<ChatMessage>>,
    chatRead: Map<String, Long>,
): Int = history.keys.sumOf { unreadIn(history, chatRead, it) }

/** チャンネル [channel] の未読数。 */
fun unreadIn(
    history: Map<String, List<ChatMessage>>,
    chatRead: Map<String, Long>,
    channel: String,
): Int {
    val read = chatRead[channel] ?: 0L
    return history[channel].orEmpty().count { it.id > read && it.speaker != "player" }
}

private fun hoursSinceActive(
    def: PersonaDef,
    s: GameState,
): Int {
    val off = s.settings.utcOffsetSec
    for (h in 0..MAX_LOGIN_HOURS) {
        if (Personas.isActive(def, s.nowEpochSec - h * SECONDS_PER_HOUR, off)) return h
    }
    return MAX_LOGIN_HOURS
}

@Composable
private fun MembersTab(
    s: GameState,
    data: GameData,
) {
    val context = LocalContext.current
    val days = (s.nowEpochSec - s.alliance.startEpochSec).coerceAtLeast(0) / SECONDS_PER_DAY
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(data.personas.personas, key = { it.id }) { def ->
            val ago = hoursSinceActive(def, s)
            MemberRow(
                name = personaName(context, def.id),
                rank = def.rank,
                castleLv = Personas.castleLv(def, days),
                login = if (ago == ONLINE_HOURS) stringResource(R.string.alliance_online) else null,
                hoursAgo = ago,
                vip = def.vipLv,
            )
        }
        items(data.personas.fillerMembers, key = { it.name }) { f ->
            MemberRow(
                name = f.name,
                rank = 1,
                castleLv = f.castleLv,
                login = if (f.lastLoginHoursAgo <= 0) stringResource(R.string.alliance_online) else null,
                hoursAgo = f.lastLoginHoursAgo,
                vip = 0,
            )
        }
    }
}

@Composable
private fun MemberRow(
    name: String,
    rank: Int,
    castleLv: Int,
    login: String?,
    hoursAgo: Int,
    vip: Int,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, fontWeight = FontWeight.Bold)
                    if (vip > 0) {
                        Text(
                            stringResource(R.string.alliance_vip, vip),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                Text(
                    login ?: stringResource(R.string.alliance_last_login, hoursAgo),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(stringResource(R.string.alliance_rank, rank))
                Text(stringResource(R.string.alliance_castle_lv, castleLv), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun HelpTab(
    s: GameState,
    data: GameData,
    onHelpAll: () -> Unit,
) {
    val context = LocalContext.current
    val mine = s.timers.filter { it.helpRequested }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Button(
                onClick = onHelpAll,
                enabled = s.alliance.helpPool.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.alliance_help_all, s.alliance.helpPool.size)) }
            Text(
                stringResource(
                    R.string.alliance_help_note,
                    data.allianceRules.help.reductionPct,
                    data.allianceRules.help.pointsPerHelp,
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (s.alliance.helpPool.isEmpty()) {
            item { Text(stringResource(R.string.alliance_help_empty)) }
        }
        items(s.alliance.helpPool, key = { it.id }) { h ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(if (h.isPersona) personaName(context, h.member) else h.member)
                    Text(stringResource(timerKindRes(h.timerKind)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (mine.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(stringResource(R.string.alliance_help_mine), style = MaterialTheme.typography.titleSmall)
            }
            items(mine, key = { it.id }) { job ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(timerKindRes(job.kind)))
                    Text(
                        stringResource(
                            R.string.alliance_help_count,
                            job.helpCount,
                            data.allianceRules.help.minHelpsPerTimer,
                        ),
                    )
                }
            }
        }
    }
}

private fun timerKindRes(kind: TimerKind): Int =
    when (kind) {
        TimerKind.BUILD -> R.string.timer_kind_build
        TimerKind.RESEARCH -> R.string.timer_kind_research
        TimerKind.TRAIN -> R.string.timer_kind_train
        TimerKind.HEAL -> R.string.timer_kind_heal
    }

@Composable
private fun RequestsTab(
    s: GameState,
    data: GameData,
    onFulfill: (Long) -> Unit,
    onJoin: (Long) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text(stringResource(R.string.alliance_requests), style = MaterialTheme.typography.titleSmall) }
        if (s.alliance.requests.isEmpty()) item { Text(stringResource(R.string.alliance_requests_empty)) }
        items(s.alliance.requests, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val who = personaName(context, r.persona)
                    val left = DurationFormat.clock(remainingUntil(r.expiresEpochSec, s.nowEpochSec))
                    if (r.kind == RequestKind.RESOURCE) {
                        val res = r.resource
                        val name = if (res != null) stringResource(res.nameRes()) else ""
                        Text(
                            stringResource(R.string.alliance_request_resource, who, name, ShortNumber.format(r.amount)),
                        )
                        Text(
                            stringResource(R.string.alliance_request_left, left),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { onFulfill(r.id) }) { Text(stringResource(R.string.alliance_request_send)) }
                    } else {
                        val t: TileRef? = r.tile
                        Text(stringResource(R.string.alliance_request_scout, who, t?.x ?: 0, t?.y ?: 0))
                        Text(
                            stringResource(R.string.alliance_request_left, left),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            stringResource(R.string.alliance_request_scout_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(stringResource(R.string.alliance_rallies), style = MaterialTheme.typography.titleSmall)
        }
        if (s.alliance.rallies.isEmpty()) item { Text(stringResource(R.string.alliance_rallies_empty)) }
        items(s.alliance.rallies, key = { it.id }) { r -> RallyCard(r, s, onJoin) }
    }
}

@Composable
private fun RallyCard(
    r: AiRally,
    s: GameState,
    onJoin: (Long) -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    R.string.alliance_rally_row,
                    personaName(context, r.host),
                    r.fortLevel,
                    r.target.x,
                    r.target.y,
                ),
            )
            Text(
                stringResource(
                    R.string.alliance_rally_left,
                    DurationFormat.clock(remainingUntil(r.joinDeadlineEpochSec, s.nowEpochSec)),
                    r.participants.size,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { onJoin(r.id) }) { Text(stringResource(R.string.alliance_rally_join)) }
        }
    }
}

@Composable
private fun TechTab(
    s: GameState,
    data: GameData,
    onDonate: (String, DonationKind) -> Unit,
    onBuy: (String) -> Unit,
) {
    val context = LocalContext.current
    val donate = data.allianceRules.donate
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(stringResource(R.string.alliance_tech_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.alliance_donate_today, s.alliance.donatedToday, donate.dailyLimit),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(data.allianceRules.techs, key = { it.id }) { t ->
            val lv = AllianceTech.level(s, t)
            val max = t.expPerLevel.size
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(namedString(context, "tech_name_", t.id), fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(
                            R.string.alliance_tech_level,
                            lv,
                            max,
                            ShortNumber.format(AllianceTech.bp(s, t) / BP_PER_PERCENT),
                        ),
                    )
                    if (lv < max) {
                        val have = s.alliance.techExp[t.id] ?: 0L
                        Text(
                            stringResource(
                                R.string.alliance_tech_exp,
                                ShortNumber.format(have),
                                ShortNumber.format(t.expPerLevel[lv]),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            DonationKind.entries.forEach { kind ->
                                OutlinedButton(onClick = { onDonate(t.id, kind) }) {
                                    val unit = if (kind.resource != null) donate.resourceUnit else donate.gemUnit
                                    val res = kind.resource
                                    val label =
                                        if (res != null) {
                                            stringResource(
                                                res.nameRes(),
                                            )
                                        } else {
                                            stringResource(R.string.alliance_gems)
                                        }
                                    Text(
                                        stringResource(
                                            R.string.alliance_donate_button,
                                            label,
                                            ShortNumber.format(unit),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(stringResource(R.string.alliance_tech_max), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(stringResource(R.string.alliance_shop_title), style = MaterialTheme.typography.titleSmall)
        }
        items(data.allianceRules.shop, key = { it.id }) { e ->
            val bought = s.alliance.shopBought[e.id] ?: 0
            val item = e.item?.let { data.items[it] }
            val name =
                if (item != null) {
                    itemName(context, item)
                } else {
                    namedString(context, "shop_name_", e.id)
                }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.alliance_shop_item, name, e.count))
                    Text(
                        stringResource(R.string.alliance_shop_limit, bought, e.dailyLimit),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = { onBuy(e.id) }, enabled = bought < e.dailyLimit && s.alliance.points >= e.cost) {
                    Text(stringResource(R.string.alliance_shop_buy, e.cost))
                }
            }
        }
    }
}

private const val BP_PER_PERCENT = 100L

@Composable
private fun GiftsTab(
    s: GameState,
    onOpen: (Long) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (s.alliance.gifts.isEmpty()) item { Text(stringResource(R.string.alliance_gifts_empty)) }
        items(s.alliance.gifts, key = { it.id }) { g ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(stringResource(R.string.alliance_gift_from, personaName(context, g.from)))
                        Text(
                            stringResource(
                                R.string.alliance_gift_left,
                                DurationFormat.clock(remainingUntil(g.expiresEpochSec, s.nowEpochSec)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = { onOpen(g.id) }) { Text(stringResource(R.string.alliance_gift_open)) }
                }
            }
        }
    }
}
