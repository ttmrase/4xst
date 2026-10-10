package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatTurn
import com.ttmrase.crownfall.core.chat.ChatEvent
import com.ttmrase.crownfall.core.chat.ChatVars
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmProvidersTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val key = "AIzaSy-FAKE-KEY-123"

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
            system = "SYSTEM RULES",
            messages = listOf(ChatTurn("player", "こんにちは"), ChatTurn("system", "きっかけ")),
            event = ChatEvent.DailyGreeting,
            speakers = listOf("kaede", "gen"),
            seed = 1,
            vars = ChatVars(emptyMap(), emptyMap(), "領主さん"),
        )

    private fun gemini(
        apiKey: String? = key,
        base: String = server.url("/").toString(),
    ) = GeminiProvider(client, { apiKey }, { "test-model" }, base)

    private fun openai(
        apiKey: String? = key,
        base: String = server.url("/v1").toString(),
    ) = OpenAiCompatProvider(client, { base }, { apiKey }, { "llama-test" })

    private fun run(
        p: com.ttmrase.crownfall.ai.AiProvider,
        r: ChatRequest = request(),
    ) = runBlocking { p.complete(r) }

    private fun geminiBody(text: String) =
        """{"candidates":[{"content":{"parts":[{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}]}}]}"""

    private fun openAiBody(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":${kotlinx.serialization.json.JsonPrimitive(text)}}}]}"""

    @Test
    fun geminiSendsTheKeyInAHeaderNeverInTheUrlOrBody() {
        server.enqueue(
            MockResponse().setBody(
                geminiBody("""{"lines":[{"speaker":"kaede","text":"おはよう"},{"speaker":"gen","text":"うむ"}]}"""),
            ),
        )
        val res = run(gemini())
        val ok = assertIs<AiResult.Success>(res)
        assertEquals(listOf("kaede" to "おはよう", "gen" to "うむ"), ok.response.lines.map { it.speaker to it.text })
        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/test-model:generateContent", recorded.path)
        assertEquals(key, recorded.getHeader("x-goog-api-key"))
        assertFalse(recorded.requestUrl.toString().contains(key))
        val body = recorded.body.readUtf8()
        assertFalse(body.contains(key))
        assertTrue(
            body.contains("SYSTEM RULES") && body.contains("systemInstruction") && body.contains("application/json"),
        )
        assertTrue(body.contains("player: こんにちは"))
    }

    @Test
    fun openAiCompatPostsChatCompletionsWithABearerToken() {
        server.enqueue(MockResponse().setBody(openAiBody("""{"lines":[{"speaker":"gen","text":"ふむ"}]}""")))
        val ok = assertIs<AiResult.Success>(run(openai()))
        assertEquals("ふむ", ok.response.lines.single().text)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer $key", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("llama-test") && body.contains("\"role\":\"system\"") && body.contains("SYSTEM RULES"))
        // キー無し（Ollama など）は Authorization を付けない
        server.enqueue(MockResponse().setBody(openAiBody("""{"lines":[{"speaker":"gen","text":"ふむ"}]}""")))
        assertIs<AiResult.Success>(run(openai(apiKey = null)))
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun brokenJsonBecomesOneLineFromTheFirstSpeaker() {
        server.enqueue(MockResponse().setBody(geminiBody("すみません、JSONではなく普通の文章です")))
        val ok = assertIs<AiResult.Success>(run(gemini()))
        assertEquals("kaede", ok.response.lines.single().speaker)
        assertEquals("すみません、JSONではなく普通の文章です", ok.response.lines.single().text)
        server.enqueue(
            MockResponse().setBody(openAiBody("```json\n{\"lines\":[{\"speaker\":\"gen\",\"text\":\"fenced\"}]}\n```")),
        )
        assertEquals("fenced", (run(openai()) as AiResult.Success).response.lines.single().text)
    }

    @Test
    fun errorsAreFailuresNotExceptions() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        assertEquals(AiResult.Failure("http_429", true), run(gemini()))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AiResult.Failure("http_503", true), run(openai()))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(AiResult.Failure("http_401", false), run(gemini()))
        server.enqueue(MockResponse().setBody("""{"candidates":[]}"""))
        assertEquals(AiResult.Failure("empty_response", true), run(gemini()))
        server.enqueue(MockResponse().setBody("not json at all"))
        assertEquals(AiResult.Failure("empty_response", true), run(openai()))
        server.enqueue(MockResponse().setBody(geminiBody("   ")))
        assertEquals(AiResult.Failure("empty_response", true), run(gemini()))
        assertEquals(AiResult.Failure("network", true), run(gemini(base = "http://127.0.0.1:1/")))
    }

    @Test
    fun missingKeyOrBaseUrlMeansNotConfiguredAndNoRequestIsMade() {
        assertFalse(gemini(apiKey = null).isConfigured())
        assertFalse(gemini(apiKey = "  ").isConfigured())
        assertEquals(AiResult.Failure("no_api_key", false), run(gemini(apiKey = null)))
        assertFalse(openai(base = "").isConfigured())
        assertEquals(AiResult.Failure("not_configured", false), run(openai(base = "")))
        assertTrue(gemini().isConfigured() && openai().isConfigured())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cleartextHttpToPublicHostsIsRefusedBeforeAnyNetworkAccess() {
        val g = run(gemini(base = "http://example.com"))
        assertEquals(AiResult.Failure("cleartext_not_allowed", false), g)
        val o = run(openai(base = "http://api.example.org/v1"))
        assertEquals(AiResult.Failure("cleartext_not_allowed", false), o)
        assertEquals(AiResult.Failure("invalid_url", false), run(openai(base = "not a url")))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun urlPolicyAllowsOnlyPrivateCleartextHosts() {
        val allowed =
            listOf(
                "https://api.groq.com/openai/v1",
                "http://localhost:11434/v1",
                "http://127.0.0.1/v1",
                "http://10.1.2.3/v1",
                "http://172.16.0.1/v1",
                "http://172.31.255.254/v1",
                "http://192.168.0.5:11434/v1",
                "http://my-pc.local:1234/v1",
                "http://[::1]:8080/v1",
            )
        for (u in allowed) assertNull(UrlPolicy.reject(u), u)
        val refused =
            listOf(
                "http://example.com/v1",
                "http://172.15.0.1/v1",
                "http://172.32.0.1/v1",
                "http://11.0.0.1/v1",
                "http://192.169.0.1/v1",
                "http://8.8.8.8/v1",
                "http://localhost.evil.com/v1",
                "http://10.0.0.1.evil.com/v1",
            )
        for (u in refused) assertEquals("cleartext_not_allowed", UrlPolicy.reject(u), u)
        assertEquals("invalid_url", UrlPolicy.reject("ftp"))
        assertFalse(UrlPolicy.isPrivateHost("999.1.1.1"))
    }

    @Test
    fun parserHandlesFencesBlankSpeakersAndGarbage() {
        assertTrue(ResponseParser.parse("   ", "kaede").isEmpty())
        val lines =
            ResponseParser.parse(
                "{\"lines\":[{\"speaker\":\"\",\"text\":\"a\"},{\"speaker\":\"gen\",\"text\":\" \"}]}",
                "kaede",
            )
        assertEquals(listOf("kaede" to "a"), lines.map { it.speaker to it.text })
        assertEquals("kaede", ResponseParser.parse("{\"lines\":[]}", "kaede").single().speaker)
        assertEquals("plain", ResponseParser.parse("plain", "gen").single().text)
    }
}
