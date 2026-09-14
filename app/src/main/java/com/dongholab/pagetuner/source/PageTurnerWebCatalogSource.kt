package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.source.catalog.PageTurnerJsonCatalogParser

data class PageTurnerCatalogLink(
    val rel: String,
    val href: String,
    val type: String? = null,
)

data class PageTurnerCatalog(
    val version: String,
    val id: String,
    val title: String,
    val updatedAt: String? = null,
    val links: List<PageTurnerCatalogLink> = emptyList(),
    val items: List<RemoteBookItem> = emptyList(),
)

object PageTurnerWebCatalogParser {
    const val Version = PageTurnerJsonCatalogParser.Version

    fun parse(rawJson: String, catalogUrl: String): PageTurnerCatalog {
        val catalog = PageTurnerJsonCatalogParser.parse(rawJson, catalogUrl)
        return PageTurnerCatalog(catalog.version, catalog.id, catalog.title, catalog.updatedAt,
            catalog.links.map { PageTurnerCatalogLink(it.rel, it.href, it.type) },
            catalog.items.map { item -> RemoteBookItem(
                identity = RemoteBookIdentity(RemoteSourceType.PageTurnerWebCatalog, catalog.id, item.id),
                title = item.title, authors = item.authors,
                format = when (item.format) {
                    "txt" -> DocumentFormat.TEXT
                    "markdown" -> DocumentFormat.MARKDOWN
                    "epub" -> DocumentFormat.EPUB
                    "pdf" -> DocumentFormat.PDF
                    else -> error("Unsupported parsed catalog format")
                },
                language = item.language, downloadUrl = item.href, contentType = item.type,
                sizeBytes = item.size, checksum = item.checksum, updatedAt = item.updatedAt, coverUrl = item.cover,
                translationHints = RemoteTranslationHints(item.translationHints.sourceLanguage, item.translationHints.targetLanguages),
            ) })
    }
}
class PageTurnerWebCatalogSource(
    private val catalogUrl: String,
    private val fetchCatalog: suspend (String) -> String,
    private val downloadBook: suspend (RemoteBookItem) -> ByteArray,
) : RemoteBookSource {
    override val sourceType: RemoteSourceType = RemoteSourceType.PageTurnerWebCatalog
    override val accountId: String
        get() = cachedCatalog?.id ?: catalogUrl

    private var cachedCatalog: PageTurnerCatalog? = null

    override suspend fun connect(): RemoteSourceConnection {
        val catalog = loadCatalog(forceRefresh = false)
        return RemoteSourceConnection(
            sourceType = sourceType,
            accountId = catalog.id,
            title = catalog.title,
            itemCount = catalog.items.size,
        )
    }

    override suspend fun list(): List<RemoteBookItem> {
        return loadCatalog(forceRefresh = false).items
    }

    override suspend fun search(query: String): List<RemoteBookItem> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return list()
        return list().filter { item ->
            item.title.lowercase().contains(normalizedQuery) ||
                item.authors.any { author -> author.lowercase().contains(normalizedQuery) }
        }
    }

    override suspend fun download(item: RemoteBookItem): ByteArray {
        require(item.identity.sourceType == sourceType) {
            "Remote item belongs to ${item.identity.sourceType}, not $sourceType."
        }
        return downloadBook(item)
    }

    override suspend fun refresh(): List<RemoteBookItem> {
        return loadCatalog(forceRefresh = true).items
    }

    private suspend fun loadCatalog(forceRefresh: Boolean): PageTurnerCatalog {
        val existing = cachedCatalog
        if (!forceRefresh && existing != null) return existing

        val loaded = PageTurnerWebCatalogParser.parse(
            rawJson = fetchCatalog(catalogUrl),
            catalogUrl = catalogUrl,
        )
        cachedCatalog = loaded
        return loaded
    }
}
