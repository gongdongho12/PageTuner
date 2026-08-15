package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.DocumentIds
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class GoogleWebTranslateHtmlProvider(
    private val apiKey: String,
    private val endpoint: String = DefaultEndpoint,
    private val transport: GoogleWebTranslateHtmlTransport = GoogleWebTranslateHtmlTransport.default(),
    private val publicEndpoint: String = DefaultPublicEndpoint,
    private val publicTransport: GoogleWebTranslateTextTransport = GoogleWebTranslateTextTransport.default(),
) : TranslationProvider {
    override val id: String = buildString {
        if (apiKey.isBlank()) {
            append("google-web-translate-public:")
            append(DocumentIds.sha256(publicEndpoint).take(12))
        } else {
            append("google-web-translate-html:")
            append(DocumentIds.sha256(endpoint).take(12))
        }
    }

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        if (request.segments.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching {
                if (apiKey.isBlank()) {
                    translateWithPublicEndpoint(request)
                } else {
                    val response = transport.post(
                        endpoint = endpoint,
                        headers = buildHeaders(request),
                        body = buildRequestBody(request),
                    )
                    parseResponse(request, response)
                }
            }.getOrElse { error ->
                throw error.asProviderNetworkFailure(ProviderName)
            }
        }
    }

    private suspend fun translateWithPublicEndpoint(
        request: TranslationRequest,
    ): List<TranslatedSegment> {
        return request.segments.map { segment ->
            val response = publicTransport.post(
                endpoint = publicEndpoint,
                headers = buildPublicHeaders(request),
                parameters = mapOf(
                    "client" to "gtx",
                    "sl" to request.sourceLanguage.trim().ifBlank { "auto" },
                    "tl" to request.targetLanguage.trim().ifBlank { "en" },
                    "dt" to "t",
                    "q" to segment.text,
                ),
            )
            val translatedText = GoogleWebTranslateTextResponseParser.parse(response).getOrElse { error ->
                throw providerResponseFormatException(
                    providerName = ProviderName,
                    detail = "Google public translation response did not contain translated text.",
                    cause = error,
                )
            }
            TranslatedSegment(segmentId = segment.id, translatedText = translatedText)
        }
    }

    private fun buildHeaders(request: TranslationRequest): Map<String, String> {
        return buildMap {
            put("Accept", "*/*")
            put("Accept-Language", request.acceptLanguageHeader())
            put("Content-Type", "application/json+protobuf")
            apiKey.trim().takeIf { it.isNotBlank() }?.let { key ->
                put("X-Goog-Api-Key", key)
            }
        }
    }

    private fun buildPublicHeaders(request: TranslationRequest): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Accept-Language" to request.acceptLanguageHeader(),
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        // Android's default Dalvik user agent is rejected intermittently by this
        // browser-facing endpoint. Keep a stable mobile browser identity here so
        // the exact same no-key provider works in both JVM tests and the app.
        "User-Agent" to PublicUserAgent,
    )

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
        const val DefaultPublicEndpoint = "https://translate.googleapis.com/translate_a/single"
        const val ProviderName = "Google Web Translate"
        const val PublicUserAgent =
            "Mozilla/5.0 (Linux; Android 13; PageTurner) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}

fun interface GoogleWebTranslateTextTransport {
    suspend fun post(
        endpoint: String,
        headers: Map<String, String>,
        parameters: Map<String, String>,
    ): String

    companion object {
        fun default(): GoogleWebTranslateTextTransport = GoogleWebTranslateTextTransport { endpoint, headers, parameters ->
            val body = parameters.entries.joinToString("&") { (name, value) ->
                "${name.urlEncode()}=${value.urlEncode()}"
            }
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
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
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

object GoogleWebTranslateTextResponseParser {
    fun parse(response: String): Result<String> {
        return runCatching {
            val root = JSONTokener(response.withoutGoogleJsonPrefix()).nextValue() as? JSONArray
                ?: throw IOException("Response root was not an array.")
            val sentences = root.optJSONArray(0)
                ?: throw IOException("Response contained no sentence list.")
            buildString {
                for (index in 0 until sentences.length()) {
                    val sentence = sentences.optJSONArray(index) ?: continue
                    append(sentence.optString(0))
                }
            }.trim().ifBlank { throw IOException("Translated text was blank.") }
        }
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
        val root = runCatching { JSONTokener(response.withoutGoogleJsonPrefix()).nextValue() }
            .getOrElse { error -> return Result.failure(IOException("Response was not JSON.", error)) }
        val allStrings = mutableListOf<String>()
        collectStrings(root, allStrings)

        val anchored = parseAnchoredTranslations(allStrings, expectedCount)
        if (anchored != null) return Result.success(anchored)

        val joinedAnchored = parseAnchoredTranslations(listOf(allStrings.joinToString("")), expectedCount)
        if (joinedAnchored != null) return Result.success(joinedAnchored)

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
            is JSONObject -> {
                value.keys().forEach { key ->
                    collectStrings(value.opt(key), output)
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

private fun TranslationRequest.acceptLanguageHeader(): String {
    val target = targetLanguage.trim().ifBlank { "en" }
    val source = sourceLanguage.trim().takeUnless { it.isBlank() || it.equals("auto", ignoreCase = true) }
    return buildList {
        add(target)
        if (source != null && !source.equals(target, ignoreCase = true)) {
            add("$source;q=0.9")
        }
        if (!target.equals("en", ignoreCase = true) && source?.equals("en", ignoreCase = true) != true) {
            add("en;q=0.8")
        }
    }.joinToString(",")
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

private fun String.withoutGoogleJsonPrefix(): String {
    val trimmed = trimStart()
    return if (trimmed.startsWith(")]}'")) {
        trimmed.substringAfter(
            delimiter = "\n",
            missingDelimiterValue = trimmed.removePrefix(")]}'"),
        ).trimStart()
    } else {
        this
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

private fun String.urlEncode(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

private val htmlEntityRegex = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")
