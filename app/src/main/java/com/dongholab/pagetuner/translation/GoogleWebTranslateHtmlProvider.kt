package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.DocumentIds
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener

class GoogleWebTranslateHtmlProvider(
    private val apiKey: String,
    private val endpoint: String = DefaultEndpoint,
    private val transport: GoogleWebTranslateHtmlTransport = GoogleWebTranslateHtmlTransport.default(),
) : TranslationProvider {
    override val id: String = buildString {
        append("google-web-translate-html:")
        append(DocumentIds.sha256(endpoint).take(12))
    }

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        if (apiKey.isBlank()) {
            throw providerConfigurationException(
                providerName = ProviderName,
                detail = "Google Web Translate API key is required.",
            )
        }
        if (request.segments.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = transport.post(
                    endpoint = endpoint,
                    headers = mapOf(
                        "Accept" to "*/*",
                        "Content-Type" to "application/json+protobuf",
                        "X-Goog-Api-Key" to apiKey.trim(),
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
        val htmlSegments = JSONArray().apply {
            request.segments.forEachIndexed { index, segment ->
                put("""<a i=$index>${segment.text.escapeHtml()}</a>""")
            }
        }

        return JSONArray().apply {
            put(
                JSONArray().apply {
                    put(htmlSegments)
                    put(request.sourceLanguage)
                    put(request.targetLanguage)
                },
            )
            put("te_lib")
        }.toString()
    }

    private fun parseResponse(
        request: TranslationRequest,
        response: String,
    ): List<TranslatedSegment> {
        val translations = GoogleWebTranslateHtmlResponseParser.parse(
            response = response,
            expectedCount = request.segments.size,
        ).getOrElse { error ->
            throw providerResponseFormatException(
                providerName = ProviderName,
                detail = "Google Web HTML response did not contain the expected translated segments.",
                cause = error,
            )
        }

        return request.segments.mapIndexed { index, segment ->
            TranslatedSegment(
                segmentId = segment.id,
                translatedText = translations[index],
            )
        }
    }

    companion object {
        const val DefaultEndpoint = "https://translate-pa.googleapis.com/v1/translateHtml"
        const val ProviderName = "Google Web HTML"
    }
}

fun interface GoogleWebTranslateHtmlTransport {
    suspend fun post(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
    ): String

    companion object {
        fun default(): GoogleWebTranslateHtmlTransport = GoogleWebTranslateHtmlTransport { endpoint, headers, body ->
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
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
                    providerName = GoogleWebTranslateHtmlProvider.ProviderName,
                    statusCode = responseCode,
                    responseBody = response,
                )
            }
            response
        }
    }
}

object GoogleWebTranslateHtmlResponseParser {
    private val anchorRegex = Regex(
        pattern = """<a\s+[^>]*\bi=(?:"|')?(\d+)(?:"|')?[^>]*>(.*?)</a>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    fun parse(response: String, expectedCount: Int): Result<List<String>> {
        val root = runCatching { JSONTokener(response).nextValue() }
            .getOrElse { error -> return Result.failure(IOException("Response was not JSON.", error)) }
        val allStrings = mutableListOf<String>()
        collectStrings(root, allStrings)

        val anchored = parseAnchoredTranslations(allStrings, expectedCount)
        if (anchored != null) return Result.success(anchored)

        val directCandidate = findDirectStringArrayCandidate(root, expectedCount)
        if (directCandidate != null) {
            return Result.success(directCandidate.take(expectedCount).map { it.cleanupTranslatedHtml() })
        }

        return Result.failure(IOException("Response size did not match request size."))
    }

    private fun parseAnchoredTranslations(
        strings: List<String>,
        expectedCount: Int,
    ): List<String>? {
        val byIndex = mutableMapOf<Int, StringBuilder>()
        strings.forEach { value ->
            anchorRegex.findAll(value).forEach { match ->
                val index = match.groupValues[1].toIntOrNull()
                if (index != null) {
                    val text = match.groupValues[2].cleanupTranslatedHtml()
                    byIndex.getOrPut(index) { StringBuilder() }.append(text)
                }
            }
        }

        if ((0 until expectedCount).any { index -> !byIndex.containsKey(index) }) {
            return null
        }
        return (0 until expectedCount).map { index -> byIndex.getValue(index).toString() }
    }

    private fun collectStrings(value: Any?, output: MutableList<String>) {
        when (value) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectStrings(value.opt(index), output)
                }
            }
            is String -> output += value
        }
    }

    private fun findDirectStringArrayCandidate(
        value: Any?,
        expectedCount: Int,
    ): List<String>? {
        if (value !is JSONArray) return null

        val directStrings = buildList {
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                if (item is String) add(item)
            }
        }
        if (directStrings.size >= expectedCount) return directStrings

        for (index in 0 until value.length()) {
            findDirectStringArrayCandidate(value.opt(index), expectedCount)?.let { return it }
        }
        return null
    }
}

private fun String.escapeHtml(): String {
    return replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private fun String.cleanupTranslatedHtml(): String {
    return replace(Regex("(?is)<[^>]+>"), "")
        .decodeHtmlEntities()
        .trim()
}

private fun String.decodeHtmlEntities(): String {
    return htmlEntityRegex.replace(this) { match ->
        when (val entity = match.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "nbsp" -> " "
            else -> entity.decodeNumericHtmlEntity() ?: match.value
        }
    }
}

private fun String.decodeNumericHtmlEntity(): String? {
    val codePoint = when {
        startsWith("#x", ignoreCase = true) -> drop(2).toIntOrNull(16)
        startsWith("#") -> drop(1).toIntOrNull()
        else -> null
    } ?: return null

    return runCatching { String(Character.toChars(codePoint)) }.getOrNull()
}

private val htmlEntityRegex = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")
