package com.ttmrase.crownfall.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ttmrase.crownfall.R
import com.ttmrase.crownfall.core.chat.ChatKeys
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.data.SkinCategory
import com.ttmrase.crownfall.core.model.ChatMessage
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.shop.VipCalculator
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.skin.Skins
import com.ttmrase.crownfall.core.title.Titles
import com.ttmrase.crownfall.ui.alliance.unreadIn
import com.ttmrase.crownfall.ui.channelName
import com.ttmrase.crownfall.ui.common.collectCalmState
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.portrait.PortraitKeys
import com.ttmrase.crownfall.ui.portrait.PortraitView
import com.ttmrase.crownfall.ui.shop.skinThemeColor
import com.ttmrase.crownfall.ui.speakerName
import com.ttmrase.crownfall.ui.titleLabel
import com.ttmrase.crownfall.ui.vip.VipBadge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val MILLIS = 1000L
private const val BUBBLE_ALPHA = 0.4f
private const val PLATE_ALPHA = 0.55f

/**
 * チャット画面（T5.5）。同盟 / ワールド / ペルソナごとの DM のタブ、未読バッジ、入力中表示、
 * ぽんずのクイズ選択肢、自由入力（同盟・DM のみ。ワールドは読み取り専用）。
 */
@Composable
fun ChatScreen(
    session: GameSession,
    data: GameData,
    typing: StateFlow<Map<String, String>>,
    snackbarHostState: SnackbarHostState,
    /** チャットを開いたとき（端末内 LLM が有効なら、先にモデルを読み込んでおく）。 */
    onOpen: () -> Unit = {},
) {
    LaunchedEffect(Unit) { onOpen() }
    val state by session.collectCalmState()
    val typingNow by typing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var channel by rememberSaveable { mutableStateOf(ChatKeys.ALLIANCE) }
    var input by rememberSaveable { mutableStateOf("") }
    val s = state ?: return
    val channels = listOf(ChatKeys.ALLIANCE, ChatKeys.WORLD) + data.personas.personas.map { ChatKeys.dm(it.id) }
    val messages = s.alliance.history[channel].orEmpty()
    val writable = channel != ChatKeys.WORLD
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val unreadByChannel =
        remember(s.alliance.history, s.alliance.chatRead) {
            channels.associateWith { unreadIn(s.alliance.history, s.alliance.chatRead, it) }
        }
    val listState = rememberLazyListState()

    fun fail(r: ActionResult) {
        if (r is ActionResult.Failure) {
            scope.launch {
                snackbarHostState.showReplacing(
                    failureMessage(context, r.reason),
                )
            }
        }
    }

    val playerName = s.playerName
    val onAnswer: (Long, Int) -> Unit =
        remember(session, data) {
            { id, option -> scope.launch { fail(session.dispatch { Actions.answerQuiz(it, id, option, data) }) } }
        }

    // 開いている間は最新まで既読にし、新着が来たら末尾へ。
    LaunchedEffect(channel, messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
            session.dispatch { Actions.markChannelRead(it, channel) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = channels.indexOf(channel).coerceAtLeast(0), edgePadding = 8.dp) {
            channels.forEach { ch ->
                val unread = if (ch == channel) 0 else unreadByChannel[ch] ?: 0
                Tab(
                    selected = ch == channel,
                    onClick = { channel = ch },
                    text = {
                        BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) {
                            Text(channelName(context, ch))
                        }
                    },
                )
            }
        }
        if (channel.startsWith("dm_")) {
            val id = channel.removePrefix("dm_")
            val def = data.personas.personas.firstOrNull { it.id == id }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val n = channelName(context, channel)
                PortraitView(id, n, 44.dp, vip = def?.vipLv ?: 0)
                Text(n, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) item { Text(stringResource(R.string.chat_empty)) }
            items(messages, key = { it.id }) { m ->
                val vip =
                    if (m.speaker == "player") {
                        VipCalculator.level(s, data)
                    } else {
                        data.personas.personas.firstOrNull { it.id == m.speaker }?.vipLv ?: 0
                    }
                val title =
                    if (m.speaker == "player") {
                        Titles.displayed(s, data)
                    } else {
                        data.personas.personas.firstOrNull { it.id == m.speaker }?.title?.ifEmpty { null }
                    }
                val skins = if (m.speaker == "player") Skins.equipped(s, data) else emptyMap()
                MessageRow(
                    m,
                    playerName,
                    vip,
                    portraitKey(m.speaker, s.avatar),
                    title,
                    skins.mapValues { it.value.theme },
                    timeFormat,
                    onAnswer,
                )
            }
        }
        typingNow[channel]?.let { who ->
            Text(
                stringResource(R.string.chat_typing, speakerName(context, who, s.playerName)),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        if (writable) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(Actions.MAX_CHAT_TEXT) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_input_hint)) },
                    maxLines = 3,
                )
                Button(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val text = input
                        input = ""
                        scope.launch { fail(session.dispatch { Actions.submitFreeText(it, text, channel, data) }) }
                    },
                ) { Text(stringResource(R.string.chat_send)) }
            }
        } else {
            Text(
                stringResource(R.string.chat_world_readonly),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** 発言者のポートレートの鍵（プレイヤーはアバター、ペルソナは ID、システム等は頭文字で代用）。 */
private fun portraitKey(
    speaker: String,
    avatar: Int,
): String = if (speaker == "player") PortraitKeys.avatar(avatar) else speaker

@Composable
private fun MessageRow(
    m: ChatMessage,
    playerName: String,
    vip: Int,
    portrait: String,
    title: String?,
    skinThemes: Map<SkinCategory, String>,
    timeFormat: DateFormat,
    onAnswer: (Long, Int) -> Unit,
) {
    val context = LocalContext.current
    val name = speakerName(context, m.speaker, playerName)
    val time = timeFormat.format(Date(m.epochSec * MILLIS))
    val mine = m.speaker == "player"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (mine) {
                            skinThemes[SkinCategory.BUBBLE]?.let { skinThemeColor(it).copy(alpha = BUBBLE_ALPHA) }
                                ?: MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                ),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PortraitView(portrait, name, 32.dp, vip = vip, frameTheme = skinThemes[SkinCategory.FRAME])
                    val plate = skinThemes[SkinCategory.NAMEPLATE]
                    Text(
                        name,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                        modifier =
                            if (plate != null) {
                                Modifier.background(
                                    skinThemeColor(plate).copy(alpha = PLATE_ALPHA),
                                    RoundedCornerShape(50),
                                )
                                    .padding(horizontal = 8.dp, vertical = 1.dp)
                            } else {
                                Modifier
                            },
                    )
                    title?.let { id ->
                        titleLabel(context, id)?.let {
                            Text(
                                "「$it」",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    VipBadge(vip)
                    Text(time, style = MaterialTheme.typography.labelSmall)
                }
                Text(m.text)
                if (m.choices.isNotEmpty()) {
                    m.choices.forEachIndexed { i, label ->
                        if (m.answered < 0) {
                            OutlinedButton(
                                onClick = { onAnswer(m.id, i) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(label) }
                        } else {
                            Text(
                                (if (i == m.answered) "▶ " else "　") + label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (i == m.answered) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}
