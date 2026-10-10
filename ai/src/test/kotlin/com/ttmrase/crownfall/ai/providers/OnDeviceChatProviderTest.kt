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
        )

    private fun provider(
        ready: Boolean = true,
        chargingOnly: Boolean = false,
        charging: Boolean = true,
        interval: Long = 0,
        generator: OnDeviceGenerator,
    ) = OnDeviceChatProvider({ ready }, generator, { chargingOnly }, { charging }, interval, { now })

    private fun run(p: OnDeviceChatProvider) = runBlocking { p.complete(request()) }

    @Test
    fun usesAShortPromptWithAtMostTwoSpeakers() {
        var system = ""
        var prompt = ""
        val p =
            provider {
                    s, u ->
                system = s
                prompt = u
                """{"lines":[{"speaker":"kaede","text":"こんにちは"}]}"""
            }
        val res = run(p)
        assertTrue(res is AiResult.Success)
        assertEquals("こんにちは", res.response.lines.single().text)
        assertTrue(system.length < 900, "system ${system.length}")
        assertTrue("kaede、gen" in system && "yuki" !in system)
        assertEquals(OnDeviceChatProvider.MAX_TURNS, prompt.lines().count { it.startsWith("kaede:") })
        assertTrue("発言9" in prompt && "発言1\n" !in prompt)
    }

    @Test
    fun brokenJsonIsSalvagedAndGarbageFails() {
        val salvage = provider { _, _ -> """{"lines":[{"speaker":"gen","text":"途中まで","x":""" }
        assertEquals("途中まで", ((run(salvage)) as AiResult.Success).response.lines.single().text)
        val garbage = provider { _, _ -> "   " }
        assertEquals("empty_response", (run(garbage) as AiResult.Failure).reason)
    }

    @Test
    fun missingModelChargingAndCooldownFailFast() {
        var called = 0
        val gen = OnDeviceGenerator { _, _ -> called++.let { """{"lines":[{"speaker":"kaede","text":"a"}]}""" } }
        assertEquals("no_model", (run(provider(ready = false, generator = gen)) as AiResult.Failure).reason)
        val unplugged = provider(chargingOnly = true, charging = false, generator = gen)
        assertEquals("not_charging", (run(unplugged) as AiResult.Failure).reason)
        assertEquals(0, called)
        val spaced = provider(interval = 3000, generator = gen)
        assertTrue(run(spaced) is AiResult.Success)
        assertEquals("cooldown", (run(spaced) as AiResult.Failure).reason)
        now += 3001
        assertTrue(run(spaced) is AiResult.Success)
        assertEquals(2, called)
    }

    @Test
    fun generatorExceptionBecomesAFailure() {
        val p = provider { _, _ -> error("native crash") }
        val res = run(p) as AiResult.Failure
        assertTrue(res.reason.startsWith("on_device_"))
        assertTrue(res.retryable)
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
        val ok = provider { _, _ -> """{"lines":[{"speaker":"kaede","text":"端末内"}]}""" }
        val cfg = RouterConfig(order = listOf("on_device"), hourlyBudget = 0, dailyBudget = 0)
        repeat(3) {
            val res = runBlocking { router(cfg, ok, usage = usage).complete(request()) }
            assertEquals("端末内", res.lines.single().text)
        }
        assertEquals(0, usage.countSince(UsageLog.HOUR, RouterConfig.LOCAL_PROVIDERS))
        assertEquals(3, usage.countSince(UsageLog.HOUR))
        assertEquals(OnDeviceProvider.ID, ok.id)
    }
}
