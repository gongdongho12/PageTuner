package com.dongholab.pagetuner.translation

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GoogleCloudTranslationProvider(
    private val apiKey: String,
    private val transport: LlmHttpTransport = LlmHttpTransport.default(ProviderName),
) : TranslationProvider {
    override val id: String = "google-cloud-v2"

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        if (apiKey.isBlank()) {
            throw providerConfigurationException(
                providerName = ProviderName,
                detail = "Google Cloud Translation API key is required.",
            )
        }
        if (request.segments.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = executeRequest(request)
                parseResponse(request, response).also { validateTranslationResponse(request.segments, it, ProviderName) }
            }.getOrElse { error ->
                throw error.asProviderNetworkFailure(ProviderName)
            }
        }
    }

    private suspend fun executeRequest(request: TranslationRequest): String {
        val encodedKey = URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        val endpoint = "https://translation.googleapis.com/language/translate/v2?key=$encodedKey"

        val body = JSONObject().apply {
            put("q", JSONArray().apply {
                request.segments.forEach { put(it.text) }
            })
            put("target", request.targetLanguage)
            put("format", "text")
            if (request.sourceLanguage != "auto") {
                put("source", request.sourceLanguage)
            }
        }.toString()

        return transport.post(endpoint, mapOf("Content-Type" to "application/json; charset=utf-8", "Accept" to "application/json"), body)
    }

    private fun parseResponse(
        request: TranslationRequest,
        response: String,
    ): List<TranslatedSegment> {
        val translations = runCatching {
            JSONObject(response)
                .getJSONObject("data")
                .getJSONArray("translations")
        }.getOrElse { error ->
            throw providerResponseFormatException(
                providerName = ProviderName,
                detail = "Google Cloud response did not contain translated text.",
                cause = error,
            )
        }

        if (translations.length() != request.segments.size) {
            throw providerResponseFormatException(
                providerName = ProviderName,
                detail = "Google Cloud response size did not match request size.",
            )
        }

        return request.segments.mapIndexed { index, segment ->
            TranslatedSegment(
                segmentId = segment.id,
                translatedText = translations.getJSONObject(index).getString("translatedText"),
            )
        }
    }

    private companion object {
        const val ProviderName = "Google Cloud"
    }
}
