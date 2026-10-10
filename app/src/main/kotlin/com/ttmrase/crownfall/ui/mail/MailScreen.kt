package com.ttmrase.crownfall.ui.mail

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.march.MailBox
import com.ttmrase.crownfall.core.model.BattleKind
import com.ttmrase.crownfall.core.model.BattleReport
import com.ttmrase.crownfall.core.model.Mail
import com.ttmrase.crownfall.core.model.MailKind
import com.ttmrase.crownfall.core.model.Receipt
import com.ttmrase.crownfall.core.model.ResourceType
import com.ttmrase.crownfall.core.model.Resources
import com.ttmrase.crownfall.core.model.ScoutReport
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.FakePurchase
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.event.eventNameRes
import com.ttmrase.crownfall.ui.itemName
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.shop.ContentsLines
import com.ttmrase.crownfall.ui.shop.formatYen
import com.ttmrase.crownfall.ui.shop.productName
import com.ttmrase.crownfall.ui.troopNameById
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val TABS = listOf(MailKind.BATTLE, MailKind.SCOUT, MailKind.SYSTEM, MailKind.RECEIPT)
private const val MILLIS = 1000L

/** メール画面（T4.4）。戦闘 / 偵察 / システムのタブ、未読表示、戦闘報告の表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailScreen(
    session: GameSession,
    data: GameData,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val s = state ?: return
    val context = LocalContext.current
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(openId) {
        val id = openId ?: return@LaunchedEffect
        if (state?.mails?.any { it.id == id && !it.read } == true) session.dispatch { Actions.markMailRead(it, id) }
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            TABS.forEachIndexed { index, kind ->
                val unread = s.mails.count { it.kind == kind && !it.read }
                val name = stringResource(tabName(kind))
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    text = { Text(if (unread > 0) stringResource(R.string.mail_tab_unread, name, unread) else name) },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = { scope.launch { session.dispatch { Actions.markAllMailRead(it) } } },
                enabled = MailBox.unread(s) > 0,
            ) { Text(stringResource(R.string.mail_mark_all_read)) }
        }
        val list = s.mails.filter { it.kind == TABS[tab] }.asReversed()
        if (list.isEmpty()) {
            Text(stringResource(R.string.mail_empty), modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { mail ->
                    Column(
                        Modifier.fillMaxWidth().clickable { openId = mail.id }.padding(
                            horizontal = 16.dp,
                            vertical = 10.dp,
                        ),
                    ) {
                        Text(
                            mailTitle(context, mail, data),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (mail.read) FontWeight.Normal else FontWeight.Bold,
                        )
                        Text(formatTime(timeFormat, mail.epochSec), style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    val open = s.mails.firstOrNull { it.id == openId }
    if (open != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { openId = null }, sheetState = sheetState) {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(mailTitle(context, open, data), style = MaterialTheme.typography.headlineSmall)
                Text(formatTime(timeFormat, open.epochSec), style = MaterialTheme.typography.labelMedium)
                HorizontalDivider()
                open.battle?.let { BattleBody(it, data) }
                open.scout?.let { ScoutBody(it) }
                open.receipt?.let { ReceiptBody(it, data) }
                if (open.kind == MailKind.SYSTEM) Text(systemText(context, open, data))
            }
        }
    }
}

private fun tabName(kind: MailKind): Int =
    when (kind) {
        MailKind.BATTLE -> R.string.mail_tab_battle
        MailKind.SCOUT -> R.string.mail_tab_scout
        MailKind.RECEIPT -> R.string.mail_tab_receipt
        else -> R.string.mail_tab_system
    }

private fun formatTime(
    format: DateFormat,
    epochSec: Long,
): String = format.format(Date(epochSec * MILLIS))

private fun battleTarget(
    context: Context,
    b: BattleReport,
): String =
    when (b.kind) {
        BattleKind.BARBARIAN -> context.getString(R.string.mail_target_barbarian, b.level)
        BattleKind.FORT -> context.getString(R.string.mail_target_fort, b.level)
        BattleKind.RAID -> context.getString(R.string.mail_target_raid)
    }

private fun scoutTarget(
    context: Context,
    r: ScoutReport,
): String =
    when (r.targetKind) {
        "barbarian" -> context.getString(R.string.mail_target_barbarian, r.level)
        "fort" -> context.getString(R.string.mail_target_fort, r.level)
        "node" -> context.getString(R.string.mail_target_node, r.level)
        else -> context.getString(R.string.mail_target_npc)
    }

/** 自軍から見た勝敗（襲撃では攻撃側が NPC なので反転）。 */
private fun playerWon(b: BattleReport): Boolean = if (b.kind == BattleKind.RAID) !b.attackerWon else b.attackerWon

private fun mailTitle(
    context: Context,
    mail: Mail,
    data: GameData,
): String {
    mail.battle?.let { b ->
        val res = if (playerWon(b)) R.string.mail_title_win else R.string.mail_title_lose
        return context.getString(res, battleTarget(context, b))
    }
    mail.scout?.let { return context.getString(R.string.mail_title_scout, scoutTarget(context, it)) }
    mail.receipt?.let {
        return context.getString(R.string.mail_title_receipt, productName(context, it.sku), formatYen(it.priceYen))
    }
    return systemText(context, mail, data)
}

private fun systemText(
    context: Context,
    mail: Mail,
    data: GameData,
): String {
    val x = (mail.params["x"] ?: 0L).toInt()
    val y = (mail.params["y"] ?: 0L).toInt()
    return when (mail.systemCode) {
        "march_target_gone" -> context.getString(R.string.mail_sys_target_gone, x, y)
        "gather_node_busy" -> context.getString(R.string.mail_sys_node_busy, x, y)
        "gather_done" -> {
            val type = ResourceType.entries.getOrNull((mail.params["type"] ?: 0L).toInt()) ?: ResourceType.FOOD
            context.getString(
                R.string.mail_sys_gather_done,
                context.getString(type.nameRes()),
                ShortNumber.format(mail.params["amount"] ?: 0L),
            )
        }
        "event_result" -> {
            val def = (mail.params["event"] ?: 0L).toInt().let { data.events.events.getOrNull(it) }
            context.getString(
                R.string.mail_sys_event_result,
                def?.let { context.getString(eventNameRes(it)) } ?: "",
                (mail.params["rank"] ?: 0L).toInt(),
                ShortNumber.format(mail.params["score"] ?: 0L),
                ShortNumber.format(mail.params["gems"] ?: 0L),
            )
        }
        else -> context.getString(R.string.mail_sys_unknown)
    }
}

@Composable
private fun BattleBody(
    b: BattleReport,
    data: GameData,
) {
    val context = LocalContext.current
    val raid = b.kind == BattleKind.RAID
    Text(
        stringResource(if (playerWon(b)) R.string.report_win else R.string.report_lose) + "  " +
            stringResource(R.string.report_rounds, b.rounds),
        style = MaterialTheme.typography.titleMedium,
    )
    TroopTable(
        stringResource(if (raid) R.string.report_raid_attacker else R.string.report_attacker),
        b.attackerInitial,
        b.attackerDead,
        b.attackerWounded,
    )
    TroopTable(
        stringResource(if (raid) R.string.report_raid_defender else R.string.report_defender),
        b.defenderInitial,
        b.defenderDead,
        b.defenderWounded,
    )
    if (b.allyTroops.isNotEmpty()) {
        Text(
            stringResource(
                R.string.report_allies_line,
                stringResource(R.string.report_allies),
                b.allyTroops.entries.joinToString {
                    context.getString(
                        R.string.troop_count_line,
                        troopNameById(context, it.key),
                        ShortNumber.format(it.value),
                    )
                },
            ),
        )
    }
    if (b.loot != Resources.ZERO || b.lootItems.isNotEmpty()) {
        Text(
            stringResource(if (raid) R.string.report_raid_loot else R.string.report_loot),
            style = MaterialTheme.typography.titleSmall,
        )
        ResourceType.entries.filter { b.loot[it] > 0 }.forEach {
            Text(stringResource(R.string.resource_plus, stringResource(it.nameRes()), ShortNumber.format(b.loot[it])))
        }
        b.lootItems.forEach { (id, n) ->
            val def = data.items[id]
            Text(stringResource(R.string.report_item, if (def != null) itemName(context, def) else id, n))
        }
    }
    if (b.heroExp > 0) Text(stringResource(R.string.report_hero_exp, ShortNumber.format(b.heroExp)))
    if (b.overflowDead.isNotEmpty()) {
        Text(
            stringResource(R.string.report_overflow, ShortNumber.format(b.overflowDead.values.sum())),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun TroopTable(
    title: String,
    initial: Map<String, Long>,
    dead: Map<String, Long>,
    wounded: Map<String, Long>,
) {
    val context = LocalContext.current
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth()) {
        Cell(stringResource(R.string.report_col_troop), 2f, header = true)
        Cell(stringResource(R.string.report_col_initial), 1f, header = true)
        Cell(stringResource(R.string.report_col_dead), 1f, header = true)
        Cell(stringResource(R.string.report_col_wounded), 1f, header = true)
    }
    if (initial.isEmpty()) Text(stringResource(R.string.report_none))
    initial.keys.sorted().forEach { id ->
        Row(Modifier.fillMaxWidth()) {
            Cell(troopNameById(context, id), 2f)
            Cell(ShortNumber.format(initial.getValue(id)), 1f)
            Cell(ShortNumber.format(dead[id] ?: 0L), 1f)
            Cell(ShortNumber.format(wounded[id] ?: 0L), 1f)
        }
    }
}

@Composable
private fun RowScope.Cell(
    text: String,
    weight: Float,
    header: Boolean = false,
) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        textAlign = if (weight > 1f) TextAlign.Start else TextAlign.End,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun ScoutBody(r: ScoutReport) {
    val context = LocalContext.current
    Text(stringResource(R.string.scout_error, r.errorPct))
    r.nodeType?.let {
        Text(
            stringResource(
                R.string.scout_node_line,
                stringResource(it.nameRes()),
                stringResource(R.string.scout_node, ShortNumber.format(r.nodeRemaining)),
            ),
        )
    }
    if (r.power > 0) Text(stringResource(R.string.scout_power, ShortNumber.format(r.power)))
    r.troopsLow.keys.sorted().forEach { id ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(troopNameById(context, id))
            Text(
                stringResource(
                    R.string.scout_range,
                    ShortNumber.format(r.troopsLow.getValue(id)),
                    ShortNumber.format(r.troopsHigh[id] ?: 0L),
                ),
            )
        }
    }
}

/** レシート（擬似）。商品・内容・価格・注文番号。「（擬似・実支払い ¥0）」は設定で付く。 */
@Composable
private fun ReceiptBody(
    r: Receipt,
    data: GameData,
) {
    val context = LocalContext.current
    Text(productName(context, r.sku), style = MaterialTheme.typography.titleMedium)
    ContentsLines(r.contents, data)
    if (r.firstDouble) Text(stringResource(R.string.receipt_first_double), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.receipt_price, formatYen(r.priceYen)))
    Text(stringResource(R.string.receipt_method))
    Text(stringResource(R.string.billing_order, FakePurchase.orderNumber(r.orderA, r.orderB)))
    if (r.note) Text(stringResource(R.string.receipt_note), style = MaterialTheme.typography.bodySmall)
}
