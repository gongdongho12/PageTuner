package com.dongholab.pagetuner.source

import android.util.Log
import com.dongholab.pagetuner.common.DiagnosticLogger
import com.dongholab.pagetuner.document.DocumentFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WebNovelRemoteBookSource(
    override val accountId: String,
    private val endpointUrl: String,
) : RemoteBookSource {

    private val TAG = "WebNovelRemoteBookSource"

    private fun logI(msg: String) {
        runCatching { Log.i(TAG, msg) }.onFailure { println("[$TAG] $msg") }
    }

    private fun logD(msg: String) {
        runCatching { Log.d(TAG, msg) }.onFailure { println("[$TAG] $msg") }
    }

    private fun logW(msg: String) {
        runCatching { Log.w(TAG, msg) }.onFailure { println("[$TAG] $msg") }
    }

    private fun logE(msg: String, tr: Throwable? = null) {
        runCatching { Log.e(TAG, msg, tr) }.onFailure { println("[$TAG] ERROR: $msg ${tr?.message}") }
    }

    override val sourceType: RemoteSourceType = RemoteSourceType.WebNovel

    override suspend fun connect(): RemoteSourceConnection = withContext(Dispatchers.IO) {
        val html = fetchHttpText(endpointUrl)
        val title = WebNovelTextExtractor.extractNovelTitle(html, fallback = "Web Novel Source")
        logD("Connected to $endpointUrl -> Title: $title (HTML length: ${html.length})")
        RemoteSourceConnection(
            sourceType = sourceType,
            accountId = accountId,
            title = title,
            itemCount = 0,
        )
    }

    override suspend fun list(): List<RemoteBookItem> = withContext(Dispatchers.IO) {
        logD("Fetching catalog list from endpoint: $endpointUrl")
        val htmlContent = fetchHttpText(endpointUrl)
        val rawLinks = WebNovelTextExtractor.parseNovelLinksFromHtml(htmlContent, endpointUrl)
        logD("Parsed ${rawLinks.size} novel items from $endpointUrl")
        rawLinks.mapIndexed { index, (title, fullUrl, coverUrl) ->
            createWebNovelItem(
                id = "wn_$index",
                title = title,
                downloadUrl = fullUrl,
                coverUrl = coverUrl,
                authors = listOf("Unknown Author"),
                language = "en",
            )
        }
    }

    override suspend fun search(query: String): List<RemoteBookItem> = withContext(Dispatchers.IO) {
        val items = list()
        if (query.isBlank()) return@withContext items
        items.filter { it.title.contains(query, ignoreCase = true) || it.downloadUrl.contains(query, ignoreCase = true) }
    }

    override suspend fun download(item: RemoteBookItem): ByteArray = withContext(Dispatchers.IO) {
        var targetUrl = item.downloadUrl
        logD("Starting download request for item: '${item.title}' at URL: $targetUrl")

        // If the URL is a novel overview catalog page, automatically resolve to Chapter 1
        if (!targetUrl.contains("/chapter/") && !targetUrl.endsWith(".html")) {
            logD("URL is novel overview page. Fetching TOC to resolve Chapter 1...")
            val overviewHtml = fetchHttpText(targetUrl)
            val chapters = WebNovelTextExtractor.parseNovelLinksFromHtml(overviewHtml, targetUrl)
            val ch1 = chapters.firstOrNull { it.first.contains("Chapter", ignoreCase = true) || it.second.contains("/chapter/") }
                ?: chapters.firstOrNull()
            if (ch1 != null) {
                targetUrl = ch1.second
                logD("Resolved Chapter 1 URL: $targetUrl (Title: ${ch1.first})")
            } else {
                logW("Could not resolve Chapter 1 from overview page $targetUrl. Downloading overview directly.")
            }
        }

        val htmlContent = fetchHttpText(targetUrl)
        DiagnosticLogger.log("[STEP 1: FETCH SUCCESS]", "Received HTML (${htmlContent.length} chars) from $targetUrl")

        var extractedText = WebNovelTextExtractor.extractNovelText(htmlContent)
        val finalTitle = WebNovelTextExtractor.extractNovelTitle(htmlContent, fallback = item.title)

        if (extractedText.isBlank()) {
            DiagnosticLogger.log("[STEP 2: PARSE WARNING]", "Extracted text was BLANK for $targetUrl!")
            extractedText = "Unable to extract novel text from $targetUrl.\n\nPage Title: $finalTitle\n\nPlease check network connection."
        } else {
            DiagnosticLogger.log("[STEP 2: PARSE SUCCESS]", "Extracted ${extractedText.length} chars of text from $targetUrl (Title: '$finalTitle')")
        }

        val formattedBookText = buildString {
            append("# ").append(finalTitle).append("\n\n")
            append(extractedText)
        }

        val bytes = formattedBookText.toByteArray(Charsets.UTF_8)
        logD("Generated book document byte array (${bytes.size} bytes)")
        bytes
    }

    override suspend fun refresh(): List<RemoteBookItem> {
        return list()
    }

    private fun createWebNovelItem(
        id: String,
        title: String,
        downloadUrl: String,
        coverUrl: String? = null,
        authors: List<String>,
        language: String,
    ): RemoteBookItem {
        return RemoteBookItem(
            identity = RemoteBookIdentity(
                sourceType = sourceType,
                accountId = accountId,
                remoteId = id,
            ),
            title = title,
            authors = authors,
            format = DocumentFormat.TEXT,
            language = language,
            contentType = "text/plain",
            downloadUrl = downloadUrl,
            coverUrl = coverUrl,
        )
    }

    private fun fetchHttpText(urlString: String): String {
        var currentUrl = urlString
        repeat(5) { redirectCount ->
            runCatching {
                val url = java.net.URL(currentUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = true

                val domain = runCatching { url.host }.getOrDefault("wtr-lab.com")
                val origin = "https://$domain"

                // Authentic Chrome Desktop User-Agent Spoofing & Client Hints
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                connection.setRequestProperty("Referer", origin)
                connection.setRequestProperty("Origin", origin)
                connection.setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
                connection.setRequestProperty("sec-ch-ua-mobile", "?0")
                connection.setRequestProperty("sec-ch-ua-platform", "\"Windows\"")
                connection.setRequestProperty("sec-fetch-dest", "document")
                connection.setRequestProperty("sec-fetch-mode", "navigate")
                connection.setRequestProperty("sec-fetch-site", "same-origin")
                connection.setRequestProperty("sec-fetch-user", "?1")
                connection.setRequestProperty("Upgrade-Insecure-Requests", "1")
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
                connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9,ko;q=0.8")

                connection.connectTimeout = 12000
                connection.readTimeout = 12000

                val responseCode = connection.responseCode
                logD("HTTP GET [$redirectCount] $currentUrl -> Response Code $responseCode")

                if (responseCode in 300..399) {
                    val redirectUrl = connection.getHeaderField("Location")
                    if (!redirectUrl.isNullOrBlank()) {
                        logD("Redirecting to $redirectUrl")
                        currentUrl = redirectUrl
                        return@repeat
                    }
                }
                if (responseCode in 200..299) {
                    val inputStream = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) {
                        java.util.zip.GZIPInputStream(connection.inputStream)
                    } else {
                        connection.inputStream
                    }
                    val text = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    logD("Successfully read ${text.length} chars from $currentUrl")
                    return text
                } else {
                    logW("HTTP GET failed with code $responseCode for $currentUrl")
                }
            }.onFailure { error ->
                logE("Error fetching HTTP text from $currentUrl: ${error.message}", error)
            }
        }
        return ""
    }
}
