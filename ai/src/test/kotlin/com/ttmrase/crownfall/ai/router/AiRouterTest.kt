package com.ttmrase.crownfall.ai.router

import com.ttmrase.crownfall.ai.AiProvider
import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatResponse
import com.ttmrase.crownfall.ai.SpokenLine
import com.ttmrase.crownfall.ai.providers.GeminiProvider
import com.ttmrase.crownfall.ai.providers.OpenAiCompatProvider
import com.ttmrase.crownfall.ai.providers.ScriptedProvider
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatVars
import com.ttmrase.crownfall.core.data.ClasspathDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiRouterTest {
    private val data = ClasspathDataSource.load()
    private val scripted = ScriptedProvider(data)
    private lateinit var server: MockWebServer
    private var now = 1_000_000L
    private val logs = ArrayList<String>()

    @BeforeTest
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stop() {
        server.shutdown()
    }

    private fun request() =
        ChatRequest(
            "sys",
            emptyList(),
            event = ChatEvent.DailyGreeting,
            speakers = listOf("kaede"),
            seed = 3,
            vars = ChatVars(mapOf("player" to "アレン"), mapOf("kaede" to "アレン"), "領主さん"),
            knownSpeakers = setOf("kaede", "gen"),
        )

    private fun gemini() = GeminiProvider(OkHttpClient(), { "key" }, { "m" }, server.url("/").toString())

    private fun compat() = OpenAiCompatProvider(OkHttpClient(), { server.url("/v1").toString() }, { "k" }, { "m" })

    private fun router(
        cfg: RouterConfig,
        vararg extra: AiProvider,
        usage: UsageLog = UsageLog(InMemoryUsageStore()) { now },
    ) = AiRouter((listOf(gemini(), compat()) + extra).associateBy { it.id }, scripted, { cfg }, usage, { logs += it })

    private fun body(text: String) =
        """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}]}}]}"""

    private fun ok(
        speaker: String,
        text: String,
    ) = body("""{"lines":[{"speaker":"$speaker","text":"$text"}]}""")

    private fun run(r: AiRouter) = runBlocking { r.complete(request()) }

    private fun isScripted(res: ChatResponse) = res == ChatResponse(scripted.generate(request()))

    @Test
    fun usesTheFirstProviderThatAnswers() {
        server.enqueue(MockResponse().setBody(ok("kaede", "LLMの挨拶")))
        val res = run(router(RouterConfig(order = listOf("gemini"))))
        assertEquals("LLMの挨拶", res.lines.single().text)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fallsThroughOn429And5xxAndFinishesWithScripted() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(500))
        val r = router(RouterConfig(order = listOf("gemini", "openai_compat")))
        val res = run(r)
        assertTrue(isScripted(res))
        assertEquals(2, server.requestCount)
        assertTrue(logs.any { it.contains("http_429") } && logs.any { it.contains("http_500") })
    }

    @Test
    fun secondProviderIsUsedAfterTheFirstFails() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{\"lines\":[{\"speaker\":\"kaede\",\"text\":\"二番手\"}]}"}}]}""",
            ),
        )
        val res = run(router(RouterConfig(order = listOf("gemini", "openai_compat"))))
        assertEquals("二番手", res.lines.single().text)
    }

    @Test
    fun timeoutMovesOnWithinTheConfiguredTime() {
        server.enqueue(MockResponse().setBody(ok("kaede", "遅い")).setBodyDelay(3, TimeUnit.SECONDS))
        val t0 = System.nanoTime()
        val res = run(router(RouterConfig(order = listOf("gemini"), timeoutMs = 300)))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(isScripted(res))
        assertTrue(ms < 2_500, "took ${ms}ms")
        assertTrue(logs.any { it.contains("timeout") })
    }

    @Test
    fun brokenJsonStillBecomesAnAnswerFromTheLlm() {
        server.enqueue(MockResponse().setBody(body("ただの文章です")))
        val res = run(router(RouterConfig(order = listOf("gemini"))))
        assertEquals(listOf(SpokenLine("kaede", "ただの文章です")), res.lines)
    }

    @Test
    fun emptyOrFilteredAnswersFallBack() {
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        assertTrue(isScripted(run(router(RouterConfig(order = listOf("gemini"))))))
        server.enqueue(MockResponse().setBody(ok("kaede", "死ね")))
        assertTrue(isScripted(run(router(RouterConfig(order = listOf("gemini"))))))
    }

    @Test
    fun hourlyAndDailyBudgetsSwitchToScriptedWithoutCallingTheServer() {
        val usage = UsageLog(InMemoryUsageStore()) { now }
        val r = router(RouterConfig(order = listOf("gemini"), hourlyBudget = 2, dailyBudget = 3), usage = usage)
        repeat(2) { server.enqueue(MockResponse().setBody(ok("kaede", "回答$it"))) }
        assertEquals("回答0", run(r).lines.single().text)
        assertEquals("回答1", run(r).lines.single().text)
        assertTrue(isScripted(run(r)))
        assertEquals(2, server.requestCount)
        now += 3601 // 1 時間後に再び使える
        server.enqueue(MockResponse().setBody(ok("kaede", "回答2")))
        assertEquals("回答2", run(r).lines.single().text)
        now += 3601 // 日次 3 件に達した
        assertTrue(isScripted(run(r)))
        assertEquals(3, server.requestCount)
        now += 86_400 // 翌日は復活
        server.enqueue(MockResponse().setBody(ok("kaede", "回答3")))
        assertEquals("回答3", run(r).lines.single().text)
        assertEquals(UsageStats(1, 0), usage.stats())
    }

    @Test
    fun unconfiguredAndDisabledProvidersAreSkippedWithoutCountingAnything() {
        val usage = UsageLog(InMemoryUsageStore()) { now }
        val noKey = GeminiProvider(OkHttpClient(), { null }, { "m" }, server.url("/").toString())
        val r = AiRouter(mapOf(noKey.id to noKey), scripted, { RouterConfig(order = listOf("gemini")) }, usage)
        assertTrue(isScripted(runBlocking { r.complete(request()) }))
        val disabled = router(RouterConfig(order = listOf("gemini"), disabled = setOf("gemini")), usage = usage)
        assertTrue(isScripted(run(disabled)))
        val unknown = router(RouterConfig(order = listOf("nope")), usage = usage)
        assertTrue(isScripted(run(unknown)))
        assertEquals(0, server.requestCount)
        assertEquals(UsageStats(0, 0), usage.stats())
    }

    @Test
    fun cleartextToAPublicHostFallsBackWithoutNetwork() {
        val bad = OpenAiCompatProvider(OkHttpClient(), { "http://example.com/v1" }, { "k" }, { "m" })
        val usage = UsageLog(InMemoryUsageStore()) { now }
        val r = AiRouter(mapOf(bad.id to bad), scripted, { RouterConfig(order = listOf("openai_compat")) }, usage)
        assertTrue(isScripted(runBlocking { r.complete(request()) }))
        assertEquals(UsageStats(1, 1), usage.stats())
    }

    @Test
    fun anExceptionInAProviderIsAFailureNotACrash() {
        val boom =
            object : AiProvider {
                override val id = "boom"

                override suspend fun complete(request: ChatRequest): AiResult = error("bug")
            }
        val slow =
            object : AiProvider {
                override val id = "slow"

                override suspend fun complete(request: ChatRequest): AiResult {
                    delay(10_000)
                    return AiResult.Success(ChatResponse(emptyList()))
                }
            }
        val usage = UsageLog(InMemoryUsageStore()) { now }
        val r =
            AiRouter(mapOf("boom" to boom, "slow" to slow), scripted, {
                RouterConfig(order = listOf("boom", "slow"), timeoutMs = 100)
            }, usage)
        assertTrue(isScripted(runBlocking { r.complete(request()) }))
        assertEquals(UsageStats(2, 2), usage.stats())
    }

    @Test
    fun unknownSpeakersAreRemappedAndFiltersApply() {
        val f = OutputFilter()
        val lines =
            f.sanitize(
                listOf(
                    SpokenLine("kaede", "見て https://evil.example/x と www.evil.com です"),
                    SpokenLine("stranger", "こんにちは"),
                    SpokenLine("world:Boris", "ワールドの人"),
                    SpokenLine("world:運営", "名乗り"),
                    SpokenLine("kaede", "   "),
                    SpokenLine("kaede", "FUCK you"),
                ),
                setOf("kaede", "world:Boris"),
                "kaede",
            )
        assertEquals(
            listOf(
                SpokenLine("kaede", "見て と です"),
                SpokenLine("kaede", "こんにちは"),
                SpokenLine("world:Boris", "ワールドの人"),
                SpokenLine("kaede", "名乗り"),
            ),
            lines,
        )
        val many = f.sanitize((1..10).map { SpokenLine("kaede", "行$it") }, setOf("kaede"), "kaede")
        assertEquals(OutputFilter.MAX_LINES, many.size)
        assertEquals(
            OutputFilter.MAX_LENGTH,
            f.sanitize(listOf(SpokenLine("kaede", "あ".repeat(500))), setOf("kaede"), "kaede").single().text.length,
        )
    }

    @Test
    fun usageLogPrunesAndPersists() {
        val store = InMemoryUsageStore()
        val log = UsageLog(store) { now }
        log.record("gemini", true)
        now += 100
        log.record("gemini", false)
        assertEquals(UsageStats(2, 1), log.stats())
        assertEquals(2, log.countSince(UsageLog.HOUR))
        assertEquals(2, store.load().size)
        now += UsageLog.DAY
        assertEquals(UsageStats(0, 0), log.stats())
        val reloaded = UsageLog(store) { now }
        assertEquals(0, reloaded.countSince(UsageLog.DAY))
        log.record("openai_compat", true)
        assertEquals(1, UsageLog(store) { now }.countSince(UsageLog.HOUR))
    }
}
