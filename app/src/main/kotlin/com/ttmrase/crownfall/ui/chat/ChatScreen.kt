package com.ttmrase.crownfall.ui.chat

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
import com.ttmrase.crownfall.core.model.ChatMessage
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.ActionResult
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.ui.alliance.unreadIn
import com.ttmrase.crownfall.ui.channelName
import com.ttmrase.crownfall.ui.common.showReplacing
import com.ttmrase.crownfall.ui.failureMessage
import com.ttmrase.crownfall.ui.speakerName
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val MILLIS = 1000L
private const val MAX_INPUT = 200

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
) {
    val state by session.state.collectAsStateWithLifecycle()
    val typingNow by typing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var channel by rememberSaveable { mutableStateOf(ChatKeys.ALLIANCE) }
    var input by rememberSaveable { mutableStateOf("") }
    val s = state ?: return
    val channels = listOf(ChatKeys.ALLIANCE, ChatKeys.WORLD) + data.personas.personas.map { ChatKeys.dm(it.id) }
    val messages = s.alliance.history[channel].orEmpty()
    val writable = channel != ChatKeys.WORLD
    val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
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
                val unread = if (ch == channel) 0 else unreadIn(s, ch)
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
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) item { Text(stringResource(R.string.chat_empty)) }
            items(messages, key = { it.id }) { m ->
                MessageRow(
                    m,
                    speakerName(context, m.speaker, s.playerName),
                    timeFormat.format(Date(m.epochSec * MILLIS)),
                ) { option -> scope.launch { fail(session.dispatch { Actions.answerQuiz(it, m.id, option, data) }) } }
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
                    onValueChange = { input = it.take(MAX_INPUT) },
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

@Composable
private fun MessageRow(
    m: ChatMessage,
    name: String,
    time: String,
    onAnswer: (Int) -> Unit,
) {
    val mine = m.speaker == "player"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        if (mine) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                ),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    Text(time, style = MaterialTheme.typography.labelSmall)
                }
                Text(m.text)
                if (m.choices.isNotEmpty()) {
                    m.choices.forEachIndexed { i, label ->
                        if (m.answered < 0) {
                            OutlinedButton(
                                onClick = { onAnswer(i) },
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
