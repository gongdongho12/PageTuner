package com.dongholab.pagetuner.source

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object WebNovelTextExtractor {
    private const val TAG = "WebNovelTextExtractor"

    private val BlacklistKeywords = listOf(
        "login", "sign-in", "register", "sign-up", "terms", "privacy", "policy", "faq",
        "discord", "contact", "about", "dmca", "copyright", "disclaimer", "cookie",
        "home", "browse", "search", "latest", "categories", "genres", "rss", "feed",
        "facebook", "twitter", "patreon", "support us", "donate", "account", "profile"
    )

    private fun logI(msg: String) {
        runCatching { Log.i(TAG, msg) }.onFailure { println("[$TAG] $msg") }
    }

    private fun logD(msg: String) {
        runCatching { Log.d(TAG, msg) }.onFailure { println("[$TAG] $msg") }
    }

    private fun logW(msg: String) {
        runCatching { Log.w(TAG, msg) }.onFailure { println("[$TAG] $msg") }
    }

    fun extractNovelText(html: String): String {
        if (html.isBlank()) {
            logW("extractNovelText called with BLANK html!")
            return ""
        }

        // Strategy A: Next.js __NEXT_DATA__ JSON Extraction
        val nextDataMatch = Regex("(?is)<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>").find(html)
        if (nextDataMatch != null) {
            val jsonString = nextDataMatch.groupValues[1].trim()
            runCatching {
                val jsonObj = JSONObject(jsonString)
                val props = jsonObj.optJSONObject("props")?.optJSONObject("pageProps")
                val extracted = extractTextFromPageProps(props)
                if (extracted.isNotBlank()) {
                    logI("[STRATEGY A: NEXT.JS JSON] Extracted ${extracted.length} chars from __NEXT_DATA__")
                    return sanitizeExtractedText(extracted)
                }
            }.onFailure { error ->
                logW("Strategy A Next.js JSON extraction failed: ${error.message}")
            }
        }

        // Strategy B: Linked Data LD+JSON or Application JSON Scripts
        val jsonScriptMatches = Regex("(?is)<script[^>]*type=[\"']application/(?:ld\\+)?json[\"'][^>]*>(.*?)</script>").findAll(html)
        for (scriptMatch in jsonScriptMatches) {
            val jsonString = scriptMatch.groupValues[1].trim()
            runCatching {
                if (jsonString.startsWith("{")) {
                    val jsonObj = JSONObject(jsonString)
                    val text = jsonObj.optString("articleBody").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("text").takeIf { it.isNotBlank() }
                        ?: jsonObj.optString("description")
                    if (!text.isNullOrBlank()) {
                        logI("[STRATEGY B: LD+JSON] Extracted ${text.length} chars from application/json script")
                        return sanitizeExtractedText(text)
                    }
                }
            }
        }

        // Strategy C: Semantic HTML Container Detection
        var content = html
        val containerMatch = Regex("(?is)<div[^>]*id=[\"'](?:chr-content|chapter-content|chapter-container|reading-content|novel-content|entry-content|epcontent|content)[\"'][^>]*>(.*?)</div>").find(content)
            ?: Regex("(?is)<div[^>]*class=[\"'][^\"']*(?:chr-content|chapter-content|chapter-container|reading-content|novel-content|entry-content|epcontent|text-left|p-4)[^\"']*[\"'][^>]*>(.*?)</div>").find(content)
            ?: Regex("(?is)<article.*?>(.*?)</article>").find(content)
            ?: Regex("(?is)<main.*?>(.*?)</main>").find(content)
            ?: Regex("(?is)<body.*?>(.*?)</body>").find(content)

        if (containerMatch != null) {
            content = containerMatch.groupValues[1]
            logI("[STRATEGY C: SEMANTIC HTML] Found matching HTML container (${content.length} raw chars)")
        }

        val sanitized = sanitizeExtractedText(content)
        logI("[FINAL EXTRACTED TEXT] Output length: ${sanitized.length} chars")
        return sanitized
    }

    private fun extractTextFromPageProps(props: JSONObject?): String {
        if (props == null) return ""
        val chapterData = props.optJSONObject("chapter")
            ?: props.optJSONObject("data")
            ?: props.optJSONObject("rawChapter")
            ?: props.optJSONObject("serie")
            ?: props.optJSONObject("novel")

        if (chapterData != null) {
            val text = chapterData.optString("content").takeIf { it.isNotBlank() }
                ?: chapterData.optString("text").takeIf { it.isNotBlank() }
                ?: chapterData.optString("body").takeIf { it.isNotBlank() }
                ?: chapterData.optString("translatedText")
            if (!text.isNullOrBlank()) return text
        }
        return props.optString("content").takeIf { it.isNotBlank() }
            ?: props.optString("text")
    }

    private fun sanitizeExtractedText(rawHtml: String): String {
        var content = rawHtml

        // Strip non-text elements
        content = content.replace(Regex("(?is)<script.*?>.*?</script>"), "")
        content = content.replace(Regex("(?is)<style.*?>.*?</style>"), "")
        content = content.replace(Regex("(?is)<header.*?>.*?</header>"), "")
        content = content.replace(Regex("(?is)<footer.*?>.*?</footer>"), "")
        content = content.replace(Regex("(?is)<nav.*?>.*?</nav>"), "")
        content = content.replace(Regex("(?is)<aside.*?>.*?</aside>"), "")
        content = content.replace(Regex("(?is)<iframe.*?>.*?</iframe>"), "")

        // Replace break & paragraph tags with newlines
        content = content.replace(Regex("(?i)<br\\s*/?>"), "\n")
        content = content.replace(Regex("(?i)</p>"), "\n\n")
        content = content.replace(Regex("(?i)</div>"), "\n\n")
        content = content.replace(Regex("(?i)</h[1-6]>"), "\n\n")
        content = content.replace(Regex("(?i)</li>"), "\n")

        // Preserve content <img> tags as Markdown images
        content = content.replace(Regex("(?i)<img\\s+[^>]*?(?:src|data-src|srcset)=[\"']([^\"']+)[\"'][^>]*>")) { match ->
            val imgUrl = match.groupValues[1].trim()
            "\n\n![Image]($imgUrl)\n\n"
        }

        // Strip remaining HTML tags
        content = content.replace(Regex("<[^>]+>"), "")

        // Decode HTML entities
        content = decodeHtmlEntities(content)

        // Clean spaces and newlines
        content = content.replace(Regex("\r\n|\r"), "\n")
        content = content.replace(Regex("[ \t]+\n"), "\n")
        content = content.replace(Regex("\n{3,}"), "\n\n")

        return content.trim()
    }

    fun extractNovelTitle(html: String, fallback: String = "Web Novel"): String {
        val titleMatch = Regex("(?i)<title>(.*?)</title>").find(html)
        if (titleMatch != null) {
            val titleText = titleMatch.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            if (titleText.isNotBlank()) {
                return decodeHtmlEntities(titleText)
            }
        }
        val h1Match = Regex("(?i)<h1.*?>(.*?)</h1>").find(html)
        if (h1Match != null) {
            val h1Text = h1Match.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            if (h1Text.isNotBlank()) {
                return decodeHtmlEntities(h1Text)
            }
        }
        return fallback
    }

    fun parseNovelLinksFromHtml(html: String, baseUrl: String): List<Triple<String, String, String?>> {
        val results = mutableListOf<Triple<String, String, String?>>()
        val seenUrls = mutableSetOf<String>()

        // Strategy A: Next.js __NEXT_DATA__ JSON Parser
        val nextDataMatch = Regex("(?is)<script[^>]*id=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>").find(html)
        if (nextDataMatch != null) {
            runCatching {
                val jsonString = nextDataMatch.groupValues[1].trim()
                val jsonObj = JSONObject(jsonString)
                val pageProps = jsonObj.optJSONObject("props")?.optJSONObject("pageProps")
                val rawSeriesList = pageProps?.optJSONArray("series")
                    ?: pageProps?.optJSONArray("novels")
                    ?: pageProps?.optJSONArray("chapters")
                    ?: pageProps?.optJSONArray("data")

                if (rawSeriesList != null) {
                    for (i in 0 until rawSeriesList.length()) {
                        val item = rawSeriesList.optJSONObject(i) ?: continue
                        val title = item.optString("title").takeIf { it.isNotBlank() }
                            ?: item.optString("name")
                        val slug = item.optString("slug").takeIf { it.isNotBlank() }
                            ?: item.optString("url") ?: item.optString("id")
                        val cover = item.optString("cover").takeIf { it.isNotBlank() }
                            ?: item.optString("image") ?: item.optString("coverUrl")

                        if (!title.isNullOrBlank() && !slug.isNullOrBlank()) {
                            val fullUrl = resolveUrl(baseUrl, slug)
                            if (!isBlacklistedUrlOrText(fullUrl, title)) {
                                val resolvedCover = cover?.let { resolveUrl(baseUrl, it) }
                                if (seenUrls.add(fullUrl)) {
                                    results.add(Triple(decodeHtmlEntities(title), fullUrl, resolvedCover))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (results.isNotEmpty()) {
            logD("Parsed ${results.size} valid novel links from Next.js __NEXT_DATA__ JSON")
            return results
        }

        // Strategy B: Standard HTML Link Scraper
        val linkRegex = Regex("(?is)<a\\s+[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
        val matches = linkRegex.findAll(html)

        for (match in matches) {
            val href = match.groupValues[1].trim()
            val innerHtml = match.groupValues[2]
            val rawText = innerHtml.replace(Regex("<[^>]+>"), "").trim()
            val text = decodeHtmlEntities(rawText)

            val imgMatch = Regex("(?i)<img\\s+[^>]*src=[\"']([^\"']+)[\"']").find(innerHtml)
            val imgSrc = imgMatch?.groupValues?.get(1)?.let { resolveUrl(baseUrl, it) }

            if (text.length >= 3 && !href.startsWith("#") && !href.startsWith("javascript:")) {
                val fullUrl = resolveUrl(baseUrl, href)
                if (isNovelOrChapterUrl(fullUrl, text) && seenUrls.add(fullUrl)) {
                    results.add(Triple(text, fullUrl, imgSrc))
                }
            }
        }
        logD("Parsed ${results.size} valid novel links from Standard HTML Link Scraper")
        return results
    }

    private fun resolveUrl(baseUrl: String, href: String): String {
        return runCatching {
            java.net.URI(baseUrl).resolve(href).toString()
        }.getOrDefault(href)
    }

    private fun isBlacklistedUrlOrText(url: String, text: String): Boolean {
        val lowerUrl = url.lowercase()
        val lowerText = text.lowercase().trim()
        return BlacklistKeywords.any { kw ->
            lowerUrl.contains("/$kw") || lowerUrl.endsWith("/$kw") || lowerText == kw
        }
    }

    private fun isNovelOrChapterUrl(url: String, text: String): Boolean {
        if (isBlacklistedUrlOrText(url, text)) return false

        val lowerUrl = url.lowercase()
        val lowerText = text.lowercase().trim()

        val hasNovelPath = lowerUrl.contains("/novel/") || lowerUrl.contains("/series/") ||
            lowerUrl.contains("/book/") || lowerUrl.contains("/chapter/") || lowerUrl.contains("/ch-")
        val hasChapterText = lowerText.contains("chapter ") || lowerText.contains("ch. ") ||
            lowerText.contains("volume ") || lowerText.contains("vol. ")

        return hasNovelPath || hasChapterText
    }

    private fun decodeHtmlEntities(text: String): String {
        return text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&#8217;", "'")
            .replace("&#8216;", "'")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
            .replace("&#8211;", "-")
            .replace("&#8212;", "--")
    }
}
