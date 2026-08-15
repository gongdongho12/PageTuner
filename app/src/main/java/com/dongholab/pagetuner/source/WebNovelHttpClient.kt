package com.dongholab.pagetuner.source

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WebNovelHttpClient {
    suspend fun fetchText(url: String): String = requestText(
        initialUrl = url,
        initialMethod = "GET",
    )

    suspend fun postJson(
        url: String,
        jsonBody: String,
        referer: String,
    ): String = requestText(
        initialUrl = url,
        initialMethod = "POST",
        initialBody = jsonBody.toByteArray(Charsets.UTF_8),
        headers = mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json; charset=utf-8",
            "Origin" to originOf(url),
            "Referer" to referer,
        ),
    )

    private suspend fun requestText(
        initialUrl: String,
        initialMethod: String,
        initialBody: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        var currentUrl = initialUrl
        var currentMethod = initialMethod
        var currentBody = initialBody
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            val connection = URL(currentUrl).openConnection() as? HttpURLConnection
                ?: throw IOException("Only HTTP(S) web novel URLs are supported.")
            try {
                connection.requestMethod = currentMethod
                connection.instanceFollowRedirects = false
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ko;q=0.7")
                connection.setRequestProperty("Accept-Encoding", "gzip")
                for ((name, value) in headers) {
                    connection.setRequestProperty(name, value)
                }
                currentBody?.let { body ->
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(body.size)
                    connection.outputStream.use { it.write(body) }
                }

                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirect response did not include a Location header.")
                    if (redirectIndex >= MAX_REDIRECTS) {
                        throw IOException("Too many redirects while loading $initialUrl.")
                    }
                    currentUrl = URI(currentUrl).resolve(location).toString()
                    if (status == HttpURLConnection.HTTP_SEE_OTHER) {
                        currentMethod = "GET"
                        currentBody = null
                    }
                    return@repeat
                }
                if (status !in 200..299) {
                    throw IOException("Web novel page returned HTTP $status for $currentUrl.")
                }

                val rawStream = connection.inputStream
                val stream = if (connection.contentEncoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(rawStream)
                } else {
                    rawStream
                }
                val bytes = stream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        total += count
                        if (total > MAX_HTML_BYTES) {
                            throw IOException("Web novel page exceeded the ${MAX_HTML_BYTES / 1024 / 1024} MB limit.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.isEmpty()) throw IOException("Web novel page returned an empty response.")
                return@withContext bytes.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Unable to load web novel page after redirects: $initialUrl")
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority)
        }
    }.getOrDefault(url)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_HTML_BYTES = 5 * 1024 * 1024
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
}
