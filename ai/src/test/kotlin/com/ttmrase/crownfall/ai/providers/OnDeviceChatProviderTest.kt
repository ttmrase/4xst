package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.AiProvider
import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatTurn
import com.ttmrase.crownfall.ai.OnDeviceProvider
import com.ttmrase.crownfall.ai.router.AiRouter
import com.ttmrase.crownfall.ai.router.InMemoryUsageStore
import com.ttmrase.crownfall.ai.router.RouterConfig
import com.ttmrase.crownfall.ai.router.UsageLog
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.chat.PromptContextBuilder
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnDeviceChatProviderTest {
    private val data = ClasspathDataSource.load()
    private val scripted = ScriptedProvider(data)
    private var now = 1_000_000L

    private fun request(speakers: List<String> = listOf("kaede", "gen", "yuki")) =
        ChatRequest(
            system = "s".repeat(2000),
            messages = (1..9).map { ChatTurn("kaede", "発言$it") },
            jsonSchemaHint = "{\"lines\":[]}",
            event = ChatEvent.DailyGreeting,
            speakers = speakers,
            seed = 1,
            vars = ChatVars(mapOf("player" to "アレン"), mapOf("kaede" to "アレン"), "領主さん"),
            knownSpeakers = setOf("kaede", "gen", "yuki"),
            personas =
                speakers.mapNotNull {
                    data.personas.persona(
                        it,
                    )
                }.map { PromptContextBuilder.brief(it, "アレンはん") },
            names = data.personas.personas.associate { it.id to it.displayName } + ("player" to "アレン"),
            situation = "プレイヤーが今日最初にログインした。挨拶する。",
        )

    private fun provider(
        ready: Boolean = true,
        chargingOnly: Boolean = false,
        charging: Boolean = true,
        interval: Long = 0,
        debug: OnDeviceDebugLog? = null,
        generator: OnDeviceGenerator,
    ) = OnDeviceChatProvider({ ready }, generator, { chargingOnly }, { charging }, interval, { now }, debug)

    private fun run(
        p: OnDeviceChatProvider,
        r: ChatRequest = request(),
    ) = runBlocking { p.complete(r) }

    /** 呼ばれるたびに違う日本語を返す（同じ文は「直前の行と同じ」で弾かれるため）。 */
    private fun counting(calls: MutableList<Pair<String, String>>) =
        OnDeviceGenerator { system, prompt ->
            calls += system to prompt
            "うん、今日も${calls.size}回目の挨拶やで"
        }

    @Test
    fun speaksOneAtATimeInOrderWithAtMostTwoSpeakersAndAddsThePreviousLineToTheContext() {
        val calls = mutableListOf<Pair<String, String>>()
        val res = run(provider(generator = counting(calls)))
        assertTrue(res is AiResult.Success)
        assertEquals(listOf("kaede", "gen"), res.response.lines.map { it.speaker })
        assertEquals(2, calls.size, "at most two speakers; the third is left to the router")
        assertTrue("「Kaede」" in calls[0].first && "「老兵ゲン」" !in calls[0].first)
        assertTrue("「老兵ゲン」" in calls[1].first && "「Kaede」" !in calls[1].first)
        assertTrue(calls[0].second.endsWith("Kaede の返事:"))
        assertTrue(calls[1].second.endsWith("老兵ゲン の返事:"))
        // 2 人目の入力には、1 人目の発言が「表示名: 発言」で入る
        assertTrue("Kaede: うん、今日も1回目の挨拶やで" in calls[1].second, calls[1].second)
        assertTrue(calls.all { (system, _) -> system.length <= SmallModelPrompt.MAX_SYSTEM_CHARS })
    }

    @Test
    fun theSecondSpeakerInTheSameRequestIsExemptFromTheCooldownButTheNextRequestIsNot() {
        val calls = mutableListOf<Pair<String, String>>()
        val p = provider(interval = 3000, generator = counting(calls))
        assertTrue(run(p) is AiResult.Success)
        assertEquals(2, calls.size, "both speakers were generated although the interval is 3 s")
        assertEquals("cooldown", (run(p) as AiResult.Failure).reason)
        now += 3001
        assertTrue(run(p, request(listOf("kaede"))) is AiResult.Success)
    }

    @Test
    fun missingModelAndChargingFailFast() {
        var called = 0
        val gen = OnDeviceGenerator { _, _ -> "うん、${called++}回目やで" }
        assertEquals("no_model", (run(provider(ready = false, generator = gen)) as AiResult.Failure).reason)
        val unplugged = provider(chargingOnly = true, charging = false, generator = gen)
        assertEquals("not_charging", (run(unplugged) as AiResult.Failure).reason)
        assertEquals(0, called)
    }

    @Test
    fun aBrokenFirstOutputFailsSoTheRouterMovesOn() {
        val json = provider { _, _ -> """{"lines":[{"speaker":"kaede","text":"こんにちは"}]}""" }
        assertEquals("bad_output", (run(json) as AiResult.Failure).reason)
        val blank = provider { _, _ -> "   " }
        assertEquals("bad_output", (run(blank) as AiResult.Failure).reason)
    }

    @Test
    fun aBrokenSecondOutputKeepsTheFirstLine() {
        var n = 0
        val p = provider { _, _ -> if (n++ == 0) "おはようさん、よう来たな" else """{"lines":[]}""" }
        val res = run(p) as AiResult.Success
        assertEquals(listOf("kaede"), res.response.lines.map { it.speaker })
    }

    @Test
    fun generatorExceptionBecomesAFailure() {
        val p = provider { _, _ -> error("native crash") }
        val res = run(p) as AiResult.Failure
        assertTrue(res.reason.startsWith("on_device_"))
        assertTrue(res.retryable)
    }

    @Test
    fun theDebugLogKeepsTheLatestFiveInMemoryNewestFirst() {
        val log = OnDeviceDebugLog()
        var n = 0
        val p = provider(debug = log) { _, _ -> "テスト${n++}回目の返事です" }
        repeat(4) {
            now += 10_000
            run(p, request(listOf("kaede", "gen")))
        }
        val recent = log.recent()
        assertEquals(OnDeviceDebugLog.CAPACITY, recent.size)
        assertEquals("テスト7回目の返事です", recent.first().raw)
        assertEquals("テスト7回目の返事です", recent.first().cleaned)
        assertTrue(recent.first().system.isNotEmpty() && recent.first().prompt.isNotEmpty())
    }

    private fun router(
        cfg: RouterConfig,
        vararg providers: AiProvider,
        usage: UsageLog = UsageLog(InMemoryUsageStore()) { now },
    ) = AiRouter(providers.associateBy { it.id }, scripted, { cfg }, usage)

    @Test
    fun routerSkipsOnDeviceWithoutModelAndFallsBackToScripted() {
        val none = provider(ready = false) { _, _ -> error("must not run") }
        val res = runBlocking { router(RouterConfig(order = listOf("on_device")), none).complete(request()) }
        assertEquals(scripted.generate(request()), res.lines)
    }

    @Test
    fun routerFallsBackToScriptedWhenTheOnDeviceOutputIsRejected() {
        val bad = provider { _, _ -> """{"lines":[{"speaker":"kaede","text":"x"}]}""" }
        val res = runBlocking { router(RouterConfig(order = listOf("on_device")), bad).complete(request()) }
        assertEquals(scripted.generate(request()), res.lines)
    }

    @Test
    fun routerFillsTheSpeakersBeyondTheBudgetWithScripted() {
        val calls = mutableListOf<Pair<String, String>>()
        val p = provider(generator = counting(calls))
        val res = runBlocking { router(RouterConfig(order = listOf("on_device")), p).complete(request()) }
        assertEquals(3, res.lines.size)
        assertEquals(listOf("kaede", "gen", "yuki"), res.lines.map { it.speaker })
        assertTrue(res.lines[0].text.startsWith("うん、今日も") && res.lines[1].text.startsWith("うん、今日も"))
        assertTrue(!res.lines[2].text.startsWith("うん、今日も"), "the third line is scripted")
    }

    @Test
    fun routerMovesOnWhenOnDeviceTimesOut() {
        val slow = provider { _, _ -> delay(5_000).let { "" } }
        val cfg = RouterConfig(order = listOf("on_device"), onDeviceTimeoutMs = 100)
        val start = System.nanoTime()
        val res = runBlocking { router(cfg, slow).complete(request()) }
        assertEquals(scripted.generate(request()), res.lines)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 3_000)
    }

    @Test
    fun onDeviceIgnoresBudgetsAndIsNotCounted() {
        val usage = UsageLog(InMemoryUsageStore()) { now }
        val ok = provider { _, _ -> "端末内の返事だよ" }
        val cfg = RouterConfig(order = listOf("on_device"), hourlyBudget = 0, dailyBudget = 0)
        repeat(3) {
            val res = runBlocking { router(cfg, ok, usage = usage).complete(request(listOf("kaede"))) }
            assertEquals("端末内の返事だよ", res.lines.single().text)
        }
        assertEquals(0, usage.countSince(UsageLog.HOUR, RouterConfig.LOCAL_PROVIDERS))
        assertEquals(3, usage.countSince(UsageLog.HOUR))
        assertEquals(OnDeviceProvider.ID, ok.id)
    }
}
