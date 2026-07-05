package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.DocumentIds
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class OpenAiCompatibleLlmTranslationProvider(
    private val apiKey: String,
    private val endpoint: String,
    private val model: String,
    private val transport: LlmHttpTransport = LlmHttpTransport.default(),
) : TranslationProvider {
    override val id: String = buildString {
        append("openai-compatible-llm:")
        append(DocumentIds.sha256(endpoint).take(12))
        append(':')
        append(model)
    }

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        if (apiKey.isBlank()) {
            throw providerConfigurationException(ProviderName, "LLM API key is required.")
        }
        if (endpoint.isBlank()) {
            throw providerConfigurationException(ProviderName, "LLM endpoint is required.")
        }
        if (model.isBlank()) {
            throw providerConfigurationException(ProviderName, "LLM model is required.")
        }
        if (request.segments.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = transport.post(
                    endpoint = endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer ${apiKey.trim()}",
                        "Content-Type" to "application/json; charset=utf-8",
                        "Accept" to "application/json",
                    ),
                    body = buildRequestBody(request),
                )
                parseResponse(request, response)
            }.getOrElse { error ->
                throw error.asProviderNetworkFailure(ProviderName)
            }
        }
    }

    private fun buildRequestBody(request: TranslationRequest): String {
        val input = JSONArray().apply {
            request.segments.forEach { put(it.text) }
        }
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("messages", JSONArray().apply {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "You are a translation engine. Return only JSON. " +
                                "Preserve paragraph count and order. Do not add explanations.",
                        ),
                )
                put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            buildString {
                                append("Translate each item from ")
                                append(request.sourceLanguage)
                                append(" to ")
                                append(request.targetLanguage)
                                append(". Return exactly this shape: ")
                                append("{\"translations\":[\"...\"]}\n")
                                append(input.toString())
                            },
                        ),
                )
            })
        }.toString()
    }

    private fun parseResponse(
        request: TranslationRequest,
        response: String,
    ): List<TranslatedSegment> {
        val content = runCatching {
            JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }.getOrElse { error ->
            throw providerResponseFormatException(
                providerName = ProviderName,
                detail = "LLM response did not contain a chat completion message.",
                cause = error,
            )
        }

        val translations = runCatching {
            extractJsonObject(content).getJSONArray("translations")
        }.getOrElse { error ->
            throw providerResponseFormatException(
                providerName = ProviderName,
                detail = "LLM response did not contain translation JSON.",
                cause = error,
            )
        }
        if (translations.length() != request.segments.size) {
            throw providerResponseFormatException(
                providerName = ProviderName,
                detail = "LLM translation response size did not match request size.",
            )
        }

        return request.segments.mapIndexed { index, segment ->
            TranslatedSegment(
                segmentId = segment.id,
                translatedText = translations.getString(index),
            )
        }
    }

    private fun extractJsonObject(content: String): JSONObject {
        val trimmed = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            JSONObject(trimmed)
        } catch (error: Exception) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) throw IOException("LLM response did not contain JSON.", error)
            JSONObject(trimmed.substring(start, end + 1))
        }
    }

    private companion object {
        const val ProviderName = "LLM API"
    }
}

fun interface LlmHttpTransport {
    suspend fun post(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
    ): String

    companion object {
        fun default(): LlmHttpTransport = LlmHttpTransport { endpoint, headers, body ->
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (responseCode !in 200..299) {
                throw providerHttpException(
                    providerName = "LLM API",
                    statusCode = responseCode,
                    responseBody = response,
                )
            }
            response
        }
    }
}
