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

/** QA による :ai の安全性テスト（リダイレクトでの鍵の流出、UrlPolicy の境界）。 */
class QaAiSecurityTest {
    private lateinit var origin: MockWebServer
    private lateinit var elsewhere: MockWebServer
    private val key = "AIzaSy-FAKE-KEY-123"

    @BeforeTest
    fun start() {
        origin = MockWebServer().also { it.start() }
        elsewhere = MockWebServer().also { it.start() }
    }

    @AfterTest
    fun stop() {
        origin.shutdown()
        elsewhere.shutdown()
    }

    private fun request() =
        ChatRequest(
            system = "SYSTEM RULES",
            messages = listOf(ChatTurn("player", "こんにちは")),
            event = ChatEvent.DailyGreeting,
            speakers = listOf("kaede"),
            seed = 1,
            vars = ChatVars(emptyMap(), emptyMap(), "領主さん"),
        )

    private fun redirectToElsewhere() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.url("/stolen").toString()))
        elsewhere.enqueue(MockResponse().setBody("{}"))
    }

    // ───── リダイレクト ─────

    @Test
    fun geminiNeverFollowsARedirectEvenWithADefaultClient() {
        redirectToElsewhere()
        val provider = GeminiProvider(OkHttpClient(), { key }, { "m" }, origin.url("/").toString())
        val res = runBlocking { provider.complete(request()) }
        val failure = assertIs<AiResult.Failure>(res)
        assertEquals("http_302", failure.reason)
        assertFalse(failure.retryable)
        assertEquals(0, elsewhere.requestCount, "the API key must never be sent to the redirect target")
    }

    @Test
    fun openAiCompatNeverFollowsARedirectEvenWithADefaultClient() {
        redirectToElsewhere()
        val provider = OpenAiCompatProvider(OkHttpClient(), { origin.url("/v1").toString() }, { key }, { "m" })
        val res = runBlocking { provider.complete(request()) }
        assertEquals("http_302", assertIs<AiResult.Failure>(res).reason)
        assertEquals(0, elsewhere.requestCount)
    }

    // ───── UrlPolicy の境界 ─────

    @Test
    fun urlPolicyRefusesTrickyPublicLookingHosts() {
        val refused =
            listOf(
                // 先頭 0（八進数として解釈する実装では 8.0.0.1）
                "http://010.0.0.1/v1",
                // 八進数の 127
                "http://0177.0.0.1/v1",
                "http://127.000.000.001/v1",
                // リンクローカル（メタデータ）
                "http://169.254.169.254/v1",
                // CGNAT
                "http://100.64.0.1/v1",
                "http://0.0.0.0/v1",
                "http://1.1.1.1/v1",
                // ユーザー情報で本当のホストを隠す
                "http://192.168.1.1@evil.example/v1",
                "http://[::ffff:8.8.8.8]/v1",
                // 10 進の整数表記
                "http://2130706433/v1",
                "http://0x7f.0.0.1/v1",
                "http://localhost.evil.example/v1",
                "http://evil.example/10.0.0.1",
                "http://localhost@evil.example/v1",
                "http://evil.example#@localhost/v1",
                // 末尾ドットは別ホスト名として扱い、閉じる側に倒す
                "http://localhost./v1",
                // IPv6 はループバック以外（リンクローカル・ULA・未指定）を閉じる側に倒す
                "http://[fe80::1]/v1",
                "http://[fd00::1]/v1",
                "http://[::]/v1",
            )
        for (u in refused) assertEquals("cleartext_not_allowed", UrlPolicy.reject(u), u)
    }

    @Test
    fun urlPolicyAcceptsNormalPrivateForms() {
        val allowed =
            listOf(
                "HTTP://LOCALHOST:11434/v1",
                "http://192.168.100.200:11434/v1",
                "http://10.255.255.255/v1",
                "http://172.20.1.1/v1",
                // https なら公開 IP でもよい
                "https://203.0.113.5/v1",
            )
        for (u in allowed) assertNull(UrlPolicy.reject(u), u)
        assertTrue(UrlPolicy.isPrivateHost("MY-PC.LOCAL"))
    }
}
