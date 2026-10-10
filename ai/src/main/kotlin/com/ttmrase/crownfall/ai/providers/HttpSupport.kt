package com.ttmrase.crownfall.ai.providers

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * URL の許可規則。https は常に許可。平文 http はプライベート IP（10/8・172.16/12・192.168/16）・
 * ループバック・localhost・`*.local` のホストだけ許可する（network_security_config では IP 範囲を指定できないため、
 * アプリ側のこの検査で守る。docs/02 §11）。
 */
object UrlPolicy {
    private const val OCTET_MAX = 255
    private const val OCTETS = 4
    private const val B172_MIN = 16
    private const val B172_MAX = 31

    // 先頭 0 付き（010.0.0.1 など）は実装により八進数として解釈され、別のアドレスになり得るので許可しない
    private const val OCTET = "(0|[1-9]\\d{0,2})"
    private val IPV4 = Regex("^$OCTET\\.$OCTET\\.$OCTET\\.$OCTET$")

    /** [url] を使ってよいか。使えるなら null、拒否なら理由を返す。 */
    fun reject(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return "invalid_url"
        if (parsed.isHttps) return null
        return if (isPrivateHost(parsed.host)) null else "cleartext_not_allowed"
    }

    /** ホスト名がプライベート / ローカルか（DNS 解決はしない）。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h == "::1") return true
        val m = IPV4.matchEntire(h) ?: return false
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.size != OCTETS || o.any { it > OCTET_MAX }) return false
        return o[0] == 10 || o[0] == 127 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in B172_MIN..B172_MAX)
    }
}

/**
 * 呼び出し用のクライアント。リダイレクトは追従しない（[UrlPolicy] の検査は最初の URL だけで、`x-goog-api-key` のような
 * 独自ヘッダーはリダイレクト先にもそのまま送られてしまうため）。API の応答に 3xx は不要。
 */
internal fun OkHttpClient.noRedirects(): OkHttpClient =
    newBuilder().followRedirects(
        false,
    ).followSslRedirects(false).build()

/** HTTP 応答（ステータスと本文）。 */
internal class HttpText(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * OkHttp の非同期呼び出しを suspend にし、本文の読み取りも OkHttp のスレッドで行う。
 * キャンセル（タイムアウト）されたら call を取り消すので、本文の読み取り中でも止まる。
 */
internal suspend fun Call.awaitText(): HttpText =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        val text = response.use { HttpText(it.code, readCapped(it)) }
                        if (cont.isActive) cont.resume(text)
                    } catch (e: Exception) {
                        val failure = if (e is IOException) e else IOException("read_failed")
                        if (cont.isActive) cont.resumeWithException(failure)
                    }
                }
            },
        )
    }

private const val MAX_BODY_BYTES = 64L * 1024

/** 本文を [MAX_BODY_BYTES] までに制限して読む（超えたら IOException）。 */
private fun readCapped(response: Response): String {
    val source = response.body?.source() ?: return ""
    source.request(MAX_BODY_BYTES + 1)
    if (source.buffer.size > MAX_BODY_BYTES) throw IOException("body_too_large")
    return source.buffer.readUtf8()
}
