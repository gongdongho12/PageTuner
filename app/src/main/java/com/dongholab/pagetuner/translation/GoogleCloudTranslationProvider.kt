package com.dongholab.pagetuner.translation

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class GoogleCloudTranslationProvider(
    private val apiKey: String,
) : TranslationProvider {
    override val id: String = "google-cloud-v2"

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        require(apiKey.isNotBlank()) { "Google Cloud Translation API key is required." }
        if (request.segments.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            val response = executeRequest(request)
            parseResponse(request, response)
        }
    }

    private fun executeRequest(request: TranslationRequest): String {
        val encodedKey = URLEncoder.encode(apiKey.trim(), Charsets.UTF_8.name())
        val url = URL("https://translation.googleapis.com/language/translate/v2?key=$encodedKey")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

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
            throw IOException("Google Translation request failed: HTTP $responseCode $response")
        }

        return response
    }

    private fun parseResponse(
        request: TranslationRequest,
        response: String,
    ): List<TranslatedSegment> {
        val translations = JSONObject(response)
            .getJSONObject("data")
            .getJSONArray("translations")

        if (translations.length() != request.segments.size) {
            throw IOException("Translation response size did not match request size.")
        }

        return request.segments.mapIndexed { index, segment ->
            TranslatedSegment(
                segmentId = segment.id,
                translatedText = translations.getJSONObject(index).getString("translatedText"),
            )
        }
    }
}
