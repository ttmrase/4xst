package com.ttmrase.crownfall.chat

import com.ttmrase.crownfall.ai.AiService
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatRequestFactory
import com.ttmrase.crownfall.ai.SpokenLine
import com.ttmrase.crownfall.ai.router.AiRouter
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.RevealAi
import com.ttmrase.crownfall.core.chat.SpokenText
import com.ttmrase.crownfall.core.data.GameData
import com.ttmrase.crownfall.core.model.GameState
import com.ttmrase.crownfall.core.model.PendingChat
import com.ttmrase.crownfall.core.session.GameSession
import com.ttmrase.crownfall.core.sim.Actions
import com.ttmrase.crownfall.core.util.Mix
import com.ttmrase.crownfall.core.util.SystemClock
import com.ttmrase.crownfall.data.GameRepository
import com.ttmrase.crownfall.data.Startup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * チャットの文面づくりと表示（docs/02 §11）。**前景にいる間だけ**動く。
 * :core が積んだ [PendingChat] を 1 件ずつ取り、新しいものは [AiService] のルーターで文面を作って 1 行ずつ
 * 「入力中…」を挟みながら出す。しばらく不在だった間に溜まったもの（[BACKLOG_SEC] 超）は Scripted でまとめて出す。
 * 背景へ回ったら残りの行を即座に書き戻して終わる（行が消えない）。
 */
class ChatCoordinator(
    private val repository: GameRepository,
    private val ai: AiService,
    private val scope: CoroutineScope,
) {
    private val _typing = MutableStateFlow<Map<String, String>>(emptyMap())

    /** チャンネル → 入力中の発話者 ID（`world:<名前>` を含む）。 */
    val typing: StateFlow<Map<String, String>> = _typing.asStateFlow()

    private var job: Job? = null

    /** 前景に来た。TZ を :core に伝え、文面づくりを始める。 */
    fun onForeground() {
        val previous = job
        job =
            scope.launch {
                // 背景→前景へすぐ戻った場合、前のループの後始末（残り行の書き戻し）を待ってから始める。
                previous?.cancelAndJoin()
                val ready = repository.startup.first { it is Startup.Ready } as Startup.Ready
                ready.session.state.first { it != null }
                ai.awaitLoaded()
                loop(ready.session, ready.data)
            }
    }

    /** 背景に来た。進行中の行は書き戻して止める。 */
    fun onBackground() {
        job?.cancel()
    }

    private suspend fun loop(
        session: GameSession,
        data: GameData,
    ) {
        val router = ai.router(data)
        val scripted = ai.scripted(data)
        while (true) {
            val state = session.state.first { !it?.alliance?.pendingChat.isNullOrEmpty() } ?: continue
            val pending = state.alliance.pendingChat.first()
            try {
                val request =
                    ChatRequestFactory.create(
                        state,
                        pending,
                        data,
                        recent = recentTexts(state, pending.channel),
                        reveal = ai.settings.value?.revealAi ?: RevealAi.AMBIGUOUS,
                        locale = java.util.Locale.getDefault().language,
                    )
                val backlog = SystemClock.nowEpochSec() - pending.createdEpochSec > BACKLOG_SEC
                if (backlog) {
                    append(session, data, pending, scripted.generate(request).map(::spoken), true, null, true)
                } else {
                    reveal(session, data, pending, request, router)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 文面づくりの失敗でアプリを落とさない。このきっかけは捨てる（無限に再試行しない）。
                _typing.value = _typing.value - pending.channel
                withContext(NonCancellable) { append(session, data, pending, emptyList(), true, null, true) }
            }
        }
    }

    private suspend fun reveal(
        session: GameSession,
        data: GameData,
        pending: PendingChat,
        request: ChatRequest,
        router: AiRouter,
    ) {
        val channel = pending.channel
        val leadSpeaker = pending.speakers.firstOrNull() ?: DEFAULT_SPEAKER
        _typing.value = _typing.value + (channel to leadSpeaker)
        var lines: List<SpokenLine> = emptyList()
        var shown = 0
        var completed = false
        try {
            val started = System.currentTimeMillis()
            lines = router.complete(request).lines
            val ambient = pending.event is ChatEvent.Ambient || pending.event is ChatEvent.WorldAmbient
            // LLM が速く返っても「考えている間」を少し置く。
            val minThink = if (ambient) AMBIENT_THINK_MS else THINK_MS
            delay((minThink - (System.currentTimeMillis() - started)).coerceAtLeast(0L))
            lines.forEachIndexed { i, line ->
                _typing.value = _typing.value + (channel to line.speaker)
                delay(typingMs(line.text, Mix.roll(request.seed, i.toLong(), 1L, TYPING_JITTER_MS.toInt()).toLong()))
                val last = i == lines.lastIndex
                val now = SystemClock.nowEpochSec()
                append(session, data, pending, listOf(spoken(line)), last, now, i == 0)
                shown = i + 1
                if (!last) delay(GAP_MS)
            }
            completed = true
        } finally {
            withContext(NonCancellable) {
                val cut = lines.isNotEmpty() && shown < lines.size
                if (cut || (completed && lines.isEmpty())) {
                    // 背景に回った等。残りを即座に書き戻し、キューから外す。
                    val rest = lines.drop(shown).map(::spoken)
                    append(session, data, pending, rest, true, SystemClock.nowEpochSec(), shown == 0)
                }
                _typing.value = _typing.value - channel
            }
        }
    }

    private suspend fun append(
        session: GameSession,
        data: GameData,
        pending: PendingChat,
        lines: List<SpokenText>,
        done: Boolean,
        at: Long?,
        first: Boolean = true,
    ) {
        session.dispatch { Actions.appendChat(it, pending.id, lines, data, done, at, first) }
    }

    private fun spoken(line: SpokenLine) = SpokenText(line.speaker, line.text)

    private fun recentTexts(
        state: GameState,
        channel: String,
    ): List<String> = state.alliance.history[channel].orEmpty().takeLast(RECENT).map { it.text }

    private fun typingMs(
        text: String,
        jitter: Long,
    ): Long = (TYPING_BASE_MS + text.length * TYPING_PER_CHAR_MS + jitter).coerceAtMost(TYPING_MAX_MS)

    private companion object {
        const val BACKLOG_SEC = 120L
        const val THINK_MS = 1_200L
        const val AMBIENT_THINK_MS = 3_000L
        const val TYPING_BASE_MS = 700L
        const val TYPING_PER_CHAR_MS = 60L
        const val TYPING_JITTER_MS = 800L
        const val TYPING_MAX_MS = 3_500L
        const val GAP_MS = 1_500L
        const val RECENT = 6
        const val DEFAULT_SPEAKER = "kaede"
    }
}
