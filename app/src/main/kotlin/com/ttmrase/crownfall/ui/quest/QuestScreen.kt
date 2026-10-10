package com.ttmrase.crownfall.ui.quest

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.QuestCond
import com.ttmrase.crownfall.core.data.QuestDef
import com.ttmrase.crownfall.core.model.BuildingType
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.quest.Progress
import com.ttmrase.crownfall.core.quest.QuestLogic
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.ShortNumber
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.nameRes
import com.ttmrase.crownfall.ui.shop.ContentsLines
import kotlinx.coroutines.launch

private val TABS =
    listOf(
        R.string.quest_tab_main,
        R.string.quest_tab_daily,
        R.string.quest_tab_alliance,
        R.string.quest_tab_achievement,
    )

/** クエスト画面（T7.1）。メイン / デイリー / 同盟 / 実績。受け取りは常に手動。 */
@Composable
fun QuestScreen(
    session: GameSession,
    data: GameData,
    snackbarHostState: SnackbarHostState,
) {
    val state by session.collectCalmState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val s = state ?: return

    fun claim(block: (GameState) -> ActionResult) {
        scope.launch {
            val r = session.dispatch(block)
            if (r is ActionResult.Failure) snackbarHostState.showReplacing(failureMessage(context, r.reason))
        }
    }

    val mainBadge = QuestLogic.currentChapter(s, data)?.let { QuestLogic.chapterDone(it, s, data) } == true
    val q = QuestLogic.view(s)
    val dailyBadge =
        data.quests.dailies.any { it.id !in q.dailyClaimed && QuestLogic.dailyProgress(it, s).done } ||
            QuestLogic.claimableChests(s, data).isNotEmpty()
    val oneShots = QuestLogic.claimableOneShots(s, data)
    val allianceBadge = data.quests.alliance.any { it in oneShots }
    val achievementBadge = data.quests.achievements.any { it in oneShots }
    val badges = listOf(mainBadge, dailyBadge, allianceBadge, achievementBadge)

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            TABS.forEachIndexed { i, title ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        BadgedBox(badge = { if (badges[i]) Badge() }) { Text(stringResource(title)) }
                    },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (tab) {
                0 -> mainTab(s, data) { id -> claim { Actions.claimQuest(it, id, data) } }
                1 ->
                    dailyTab(s, data, onClaim = { id -> claim { Actions.claimQuest(it, id, data) } }) { tier ->
                        claim { Actions.claimDailyChest(it, tier, data) }
                    }
                2 -> oneShotTab(s, data, data.quests.alliance) { id -> claim { Actions.claimQuest(it, id, data) } }
                else ->
                    oneShotTab(s, data, data.quests.achievements) { id ->
                        claim { Actions.claimQuest(it, id, data) }
                    }
            }
        }
    }
}

private fun LazyListScope.mainTab(
    s: GameState,
    data: GameData,
    onClaim: (String) -> Unit,
) {
    val chapter = QuestLogic.currentChapter(s, data)
    if (chapter == null) {
        item { Text(stringResource(R.string.quest_all_done), style = MaterialTheme.typography.bodyLarge) }
        return
    }
    val number = data.quests.chapters.indexOf(chapter) + 1
    item {
        Text(stringResource(R.string.quest_chapter, number), style = MaterialTheme.typography.titleMedium)
    }
    items(chapter.tasks, key = { it.id }) { task ->
        val p = QuestLogic.progress(task.cond, s, data)
        QuestRow(condText(LocalContext.current, task.cond), p)
    }
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.quest_reward), fontWeight = FontWeight.Bold)
                ContentsLines(chapter.reward, data)
                Button(
                    onClick = { onClaim(chapter.id) },
                    enabled = QuestLogic.chapterDone(chapter, s, data),
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text(stringResource(R.string.quest_claim)) }
            }
        }
    }
}

private fun LazyListScope.dailyTab(
    s: GameState,
    data: GameData,
    onClaim: (String) -> Unit,
    onChest: (Int) -> Unit,
) {
    val q = QuestLogic.view(s)
    val points = QuestLogic.dailyPoints(s, data)
    val max = (data.quests.chests.maxOfOrNull { it.points } ?: 1).coerceAtLeast(1)
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.quest_daily_points, points, max), fontWeight = FontWeight.Bold)
                LinearProgressIndicator(
                    progress = { (points.toFloat() / max).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
                data.quests.chests.forEach { chest ->
                    val opened = chest.tier in q.dailyChests
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.quest_chest, chest.points))
                            ContentsLines(chest.reward, data)
                        }
                        Button(onClick = { onChest(chest.tier) }, enabled = !opened && points >= chest.points) {
                            Text(stringResource(if (opened) R.string.quest_claimed else R.string.quest_claim))
                        }
                    }
                }
            }
        }
    }
    items(data.quests.dailies, key = { it.id }) { d ->
        val p = QuestLogic.dailyProgress(d, s)
        val claimed = d.id in q.dailyClaimed
        QuestRow(
            title = keyText(LocalContext.current, d.key, d.target),
            progress = p,
            footer = stringResource(R.string.quest_points, d.points),
            action = {
                Button(onClick = { onClaim(d.id) }, enabled = p.done && !claimed) {
                    Text(stringResource(if (claimed) R.string.quest_claimed else R.string.quest_claim))
                }
            },
        )
    }
}

private fun LazyListScope.oneShotTab(
    s: GameState,
    data: GameData,
    defs: List<QuestDef>,
    onClaim: (String) -> Unit,
) {
    // 受け取れるものを先頭に、受け取り済みを末尾に
    val sorted =
        defs.sortedBy {
            when {
                it.id in s.quest.claimed -> 2
                QuestLogic.progress(it.cond, s, data).done -> 0
                else -> 1
            }
        }
    items(sorted, key = { it.id }) { d ->
        val p = QuestLogic.progress(d.cond, s, data)
        val claimed = d.id in s.quest.claimed
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                QuestRow(condText(LocalContext.current, d.cond), if (claimed) Progress(p.target, p.target) else p)
                ContentsLines(d.reward, data)
                Button(
                    onClick = { onClaim(d.id) },
                    enabled = p.done && !claimed,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(if (claimed) R.string.quest_claimed else R.string.quest_claim))
                }
            }
        }
    }
}

@Composable
private fun QuestRow(
    title: String,
    progress: Progress,
    footer: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            val cur = ShortNumber.format(minOf(progress.current, progress.target))
            val line = stringResource(R.string.quest_progress, cur, ShortNumber.format(progress.target))
            Text(if (footer != null) "$line  ·  $footer" else line, style = MaterialTheme.typography.bodySmall)
        }
        action?.invoke()
    }
}

/** 条件の説明。 */
internal fun condText(
    context: Context,
    c: QuestCond,
): String =
    when (c.type) {
        "building" -> {
            val type = BuildingType.entries.firstOrNull { it.name.lowercase() == c.building }
            val name = type?.let { context.getString(it.nameRes()) } ?: (c.building ?: "")
            context.getString(R.string.quest_cond_building, name, c.lv)
        }
        "power" -> context.getString(R.string.quest_cond_power, ShortNumber.format(c.value))
        "research" -> context.getString(R.string.quest_cond_research, c.total)
        "heroes" -> context.getString(R.string.quest_cond_heroes, c.count)
        else -> keyText(context, c.key.orEmpty(), c.target)
    }

/** カウンタ系の項目の説明（「同盟員のタイマーを 5 回ヘルプする」など）。 */
internal fun keyText(
    context: Context,
    key: String,
    target: Long,
): String {
    val res =
        when (key) {
            "help" -> R.string.quest_key_help
            "gather" -> R.string.quest_key_gather
            "train" -> R.string.quest_key_train
            "trainTroops" -> R.string.quest_key_train_troops
            "research" -> R.string.quest_key_research
            "build" -> R.string.quest_key_build
            "hunt" -> R.string.quest_key_hunt
            "donate" -> R.string.quest_key_donate
            "summon" -> R.string.quest_key_summon
            "shop" -> R.string.quest_key_shop
            "chat" -> R.string.quest_key_chat
            "rally" -> R.string.quest_key_rally
            else -> return key
        }
    return context.getString(res, ShortNumber.format(target))
}
