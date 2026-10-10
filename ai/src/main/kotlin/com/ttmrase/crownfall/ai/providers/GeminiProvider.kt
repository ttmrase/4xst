package com.ttmrase.crownfall.ai.providers

import com.ttmrase.crownfall.ai.AiProvider
import com.ttmrase.crownfall.ai.AiResult
import com.ttmrase.crownfall.ai.ChatRequest
import com.ttmrase.crownfall.ai.ChatResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Gemini API（Generative Language API）。キーはヘッダー `x-goog-api-key` で送り、URL にもログにも出さない。
 * [apiKey] と [model] は呼ぶたびに読む（設定変更がすぐ効く）。
 */
class GeminiProvider(
    private val client: OkHttpClient,
    private val apiKey: () -> String?,
    private val model: () -> String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : AiProvider {
    override val id: String = ID

    override fun isConfigured(): Boolean = !apiKey().isNullOrBlank()

    override suspend fun complete(request: ChatRequest): AiResult {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return AiResult.Failure("no_api_key", false)
        val url = "${baseUrl.trimEnd('/')}/v1beta/models/${model().trim()}:generateContent"
        UrlPolicy.reject(url)?.let { return AiResult.Failure(it, false) }
        val http =
            try {
                Request.Builder().url(url).header("x-goog-api-key", key)
                    .post(body(request).toRequestBody(JSON)).build()
            } catch (e: IllegalArgumentException) {
                // 例外メッセージにヘッダ値（キー）が載るので、メッセージは捨てる。
                return AiResult.Failure("invalid_key", false)
            }
        return try {
            val res = client.noRedirects().newCall(http).awaitText()
            when {
                res.code == HTTP_TOO_MANY -> AiResult.Failure("http_429", true)
                res.code >= HTTP_SERVER_ERROR -> AiResult.Failure("http_${res.code}", true)
                !res.isSuccessful -> AiResult.Failure("http_${res.code}", false)
                else -> parse(res.body, request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AiResult.Failure("network", true)
        }
    }

    private fun body(request: ChatRequest): String {
        val user = request.messages.joinToString("\n") { "${it.speaker}: ${it.text}" }
        val obj =
            buildJsonObject {
                put(
                    "systemInstruction",
                    buildJsonObject {
                        put(
                            "parts",
                            JsonArray(listOf(buildJsonObject { put("text", request.system) })),
                        )
                    },
                )
                put(
                    "contents",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("role", "user")
                                put("parts", JsonArray(listOf(buildJsonObject { put("text", user) })))
                            },
                        ),
                    ),
                )
                put(
                    "generationConfig",
                    buildJsonObject {
                        put("temperature", request.temperature)
                        put("maxOutputTokens", request.maxTokens)
                        put("responseMimeType", "application/json")
                    },
                )
            }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun parse(
        text: String,
        request: ChatRequest,
    ): AiResult {
        val content =
            runCatching {
                Json.parseToJsonElement(text).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        if (content.isNullOrBlank()) return AiResult.Failure("empty_response", true)
        val lines = ResponseParser.parse(content, request.speakers.firstOrNull() ?: DEFAULT_SPEAKER)
        return if (lines.isEmpty()) AiResult.Failure("empty_response", true) else AiResult.Success(ChatResponse(lines))
    }

    companion object {
        const val ID = "gemini"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"

        /** 既定モデル（GA 版 Flash-Lite。公式の料金ページで無料枠対象。設定画面で変更できる）。 */
        const val DEFAULT_MODEL = "gemini-3.1-flash-lite"
        private const val DEFAULT_SPEAKER = "kaede"
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
