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
 * OpenAI 互換の `/chat/completions`（Groq / OpenRouter / Ollama / LM Studio）。
 * [baseUrl] は `https://api.groq.com/openai/v1` や `http://192.168.0.5:11434/v1` のように `/v1` までを含める。
 * 平文 http は [UrlPolicy] が許すホスト（LAN など）だけ。
 */
class OpenAiCompatProvider(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val apiKey: () -> String?,
    private val model: () -> String,
) : AiProvider {
    override val id: String = ID

    override fun isConfigured(): Boolean = baseUrl().isNotBlank() && model().isNotBlank()

    override suspend fun complete(request: ChatRequest): AiResult {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty() || model().isBlank()) return AiResult.Failure("not_configured", false)
        val url = "$base/chat/completions"
        UrlPolicy.reject(url)?.let { return AiResult.Failure(it, false) }
        val builder = Request.Builder().url(url).post(body(request).toRequestBody(JSON))
        apiKey()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return try {
            val res = client.newCall(builder.build()).awaitText()
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
                put("model", model().trim())
                put(
                    "messages",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("role", "system")
                                put("content", request.system)
                            },
                            buildJsonObject {
                                put("role", "user")
                                put("content", user)
                            },
                        ),
                    ),
                )
                put("temperature", request.temperature)
                put("max_tokens", request.maxTokens)
            }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun parse(
        text: String,
        request: ChatRequest,
    ): AiResult {
        val content =
            runCatching {
                Json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        if (content.isNullOrBlank()) return AiResult.Failure("empty_response", true)
        val lines = ResponseParser.parse(content, request.speakers.firstOrNull() ?: DEFAULT_SPEAKER)
        return if (lines.isEmpty()) AiResult.Failure("empty_response", true) else AiResult.Success(ChatResponse(lines))
    }

    companion object {
        const val ID = "openai_compat"
        private const val DEFAULT_SPEAKER = "kaede"
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
