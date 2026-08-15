package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapterLoader
import com.dongholab.pagetuner.source.WebNovelTextExtractor
import com.dongholab.pagetuner.source.novelbuddy.NovelBuddyBookSummary
import com.dongholab.pagetuner.source.novelbuddy.NovelBuddyChapterSummary
import com.dongholab.pagetuner.source.novelbuddy.NovelBuddyDomScraper
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NovelBuddySiteAdapter : WebNovelSiteAdapter {
    override val id: String = "novelbuddy"
    override val displayName: String = "NovelBuddy"
    override val chapterLoadStrategy: WebNovelChapterLoadStrategy = WebNovelChapterLoadStrategy.HttpOnly
    override val catalogCapabilities: WebNovelCatalogCapabilities = WebNovelCatalogCapabilities(
        remoteSearch = true,
        genreFilterKey = "genres",
        genreOptions = listOf(WebNovelCatalogOption(null, "All Genres")) + NovelBuddyGenres.map {
            WebNovelCatalogOption(it.first, it.second)
        },
    )

    override fun supports(url: String): Boolean = runCatching {
        URI(url).host.orEmpty().removePrefix("www.").equals("novelbuddy.me", ignoreCase = true)
    }.getOrDefault(false)

    override fun classify(url: String): WebNovelPageKind {
        val segments = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
            .trim('/').split('/').filter(String::isNotBlank)
        return when {
            segments.size >= 2 && segments[1].startsWith("chapter-", ignoreCase = true) ->
                WebNovelPageKind.Chapter
            segments.size == 1 && segments[0].lowercase() !in CatalogRoots ->
                WebNovelPageKind.NovelDetail
            else -> WebNovelPageKind.Catalog
        }
    }

    override fun siteTitle(html: String, url: String): String {
        return runCatching { NovelBuddyDomScraper.parseDetail(html, url).name }
            .getOrElse { WebNovelTextExtractor.extractNovelTitle(html, displayName) }
            .removeSuffix(" - NovelBuddy")
    }

    override fun canonicalCatalogUrl(url: String): String = runCatching {
        val uri = URI(url)
        val path = uri.path.orEmpty().trimEnd('/')
        if (path.isBlank() || path == "/" || path.equals("/home", ignoreCase = true)) {
            "${uri.scheme}://${uri.rawAuthority}/search"
        } else {
            url
        }
    }.getOrDefault(url)

    override fun catalogRequest(url: String): WebNovelCatalogRequest {
        val params = queryParameters(url)
        return WebNovelCatalogRequest(
            query = params["q"].orEmpty(),
            page = params["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            filters = params.filterKeys { it != "q" && it != "page" },
        )
    }

    override fun catalogSearchUrl(url: String, request: WebNovelCatalogRequest): String {
        val uri = URI(canonicalCatalogUrl(url))
        val parameters = buildList {
            request.query.trim().takeIf(String::isNotBlank)?.let { add("q" to it) }
            request.filters.forEach { (key, value) ->
                if (key in SupportedSearchFilters && value.isNotBlank()) add(key to value)
            }
            add("page" to request.page.coerceAtLeast(1).toString())
        }
        return buildString {
            append(uri.scheme).append("://").append(uri.rawAuthority).append("/search?")
            append(parameters.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" })
        }
    }

    override fun parseCatalog(html: String, url: String): List<WebNovelSiteBook> =
        parseCatalogPage(html, url).items

    override fun parseCatalogPage(html: String, url: String): WebNovelCatalogPage {
        val response = NovelBuddyDomScraper.parseCatalogResponse(html, url)
        return WebNovelCatalogPage(
            url = url,
            currentPage = response.currentPage,
            totalPages = response.totalPages,
            totalItems = response.totalItems,
            items = response.items.map(::book),
            hasPreviousPage = response.hasPreviousPage,
            hasNextPage = response.hasNextPage,
        )
    }

    override fun parseDetail(html: String, url: String): WebNovelSiteDetail {
        val book = NovelBuddyDomScraper.parseDetail(html, url)
        return WebNovelSiteDetail(
            id = book.id,
            title = book.name,
            author = book.authors.joinToString().ifBlank { "Unknown author" },
            language = "en",
            status = book.status,
            totalChapters = book.chapterCount,
            summary = book.summary.orEmpty(),
            tags = book.tags,
            coverUrl = book.coverUrl,
            views = book.views,
            rating = book.rating,
        )
    }

    override fun parseChapters(html: String, url: String): List<WebNovelSiteChapter> =
        NovelBuddyDomScraper.parseEmbeddedChapters(html, url).map(::chapter)

    override suspend fun loadChapters(
        html: String,
        url: String,
        fetchText: suspend (String) -> String,
    ): List<WebNovelSiteChapter> {
        val embedded = parseChapters(html, url)
        val indexUrl = NovelBuddyDomScraper.chapterIndexUrl(html, url) ?: return embedded
        return runCatching {
            NovelBuddyDomScraper.parseChapterIndex(fetchText(indexUrl), url).map(::chapter)
        }.getOrElse { embedded }
    }

    override suspend fun resolveChapterUrl(
        url: String,
        loadHtml: suspend (String) -> String,
    ): String {
        if (classify(url) == WebNovelPageKind.Chapter) return url
        if (classify(url) == WebNovelPageKind.NovelDetail) {
            return NovelBuddyDomScraper.firstChapterUrl(loadHtml(url), url)
                ?: throw IOException("NovelBuddy book did not expose its first chapter URL: $url")
        }
        val firstBook = parseCatalog(loadHtml(canonicalCatalogUrl(url)), canonicalCatalogUrl(url)).firstOrNull()
            ?: throw IOException("NovelBuddy catalog did not contain a readable book: $url")
        return resolveChapterUrl(firstBook.url, loadHtml)
    }

    override suspend fun loadChapter(
        url: String,
        fallbackTitle: String,
        fetchHtml: suspend (String) -> String,
        renderedChapterLoader: RenderedChapterLoader?,
    ): WebNovelSiteChapterContent {
        val response = NovelBuddyDomScraper.parseChapterContent(fetchHtml(url))
        return WebNovelSiteChapterContent(
            number = response.number,
            title = response.title.ifBlank { fallbackTitle },
            paragraphs = response.paragraphs,
        )
    }

    private fun book(item: NovelBuddyBookSummary): WebNovelSiteBook = WebNovelSiteBook(
        id = item.id,
        title = item.name,
        url = item.url,
        authors = item.authors,
        language = "en",
        coverUrl = item.coverUrl,
        description = item.summary,
        chapterCount = item.chapterCount.takeIf { it > 0 },
        tags = item.tags,
    )

    private fun chapter(item: NovelBuddyChapterSummary): WebNovelSiteChapter = WebNovelSiteChapter(
        id = item.id,
        number = item.number,
        title = item.name,
        url = item.url,
        language = "en",
    )

    private fun queryParameters(url: String): Map<String, String> = runCatching {
        URI(url).rawQuery.orEmpty().split('&').filter(String::isNotBlank).associate { part ->
            part.substringBefore('=').urlDecode() to part.substringAfter('=', "").urlDecode()
        }
    }.getOrDefault(emptyMap())

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun String.urlDecode(): String = URLDecoder.decode(this, StandardCharsets.UTF_8.name())

    private companion object {
        val CatalogRoots = setOf(
            "home", "search", "latest", "genres", "lists", "ranking", "mtl-novels",
            "authors", "tags", "auth", "terms-of-service", "privacy-policy", "dmca", "contact",
        )
        val SupportedSearchFilters = setOf(
            "genres", "exclude", "tag", "min_ch", "max_ch", "status", "demographic", "type",
            "content_rating", "author", "format", "sort", "window", "uncensored", "raw", "mtl",
        )
        val NovelBuddyGenres = listOf(
            "action" to "Action", "adult" to "Adult", "adventure" to "Adventure",
            "comedy" to "Comedy", "drama" to "Drama", "eastern" to "Eastern",
            "ecchi" to "Ecchi", "fan-fiction" to "Fan-Fiction", "fantasy" to "Fantasy",
            "game" to "Game", "gender-bender" to "Gender Bender", "harem" to "Harem",
            "historical" to "Historical", "horror" to "Horror", "josei" to "Josei",
            "martial-arts" to "Martial Arts", "mature" to "Mature", "mecha" to "Mecha",
            "military" to "Military", "modern-life" to "Modern Life", "mystery" to "Mystery",
            "psychological" to "Psychological", "reincarnation" to "Reincarnation",
            "romance" to "Romance", "school-life" to "School Life", "sci-fi" to "Sci-Fi",
            "seinen" to "Seinen", "shoujo" to "Shoujo", "shoujo-ai" to "Shoujo Ai",
            "shounen" to "Shounen", "shounen-ai" to "Shounen Ai",
            "slice-of-life" to "Slice of Life", "smut" to "Smut", "sports" to "Sports",
            "supernatural" to "Supernatural", "system" to "System", "tragedy" to "Tragedy",
            "urban" to "Urban", "urban-life" to "Urban Life", "wuxia" to "Wuxia",
            "xianxia" to "Xianxia", "xuanhuan" to "Xuanhuan", "yaoi" to "Yaoi",
            "yuri" to "Yuri",
        )
    }
}
