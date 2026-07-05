package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

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
    const val Version = "pagetuner.catalog.v0"

    fun parse(
        rawJson: String,
        catalogUrl: String,
    ): PageTurnerCatalog {
        val root = JSONObject(rawJson)
        val version = root.optString("version").ifBlank { Version }
        require(version == Version) { "Unsupported PageTurner catalog version: $version" }

        val catalogId = root.requireString("id")
        val baseUri = URI(catalogUrl)
        val links = root.optJSONArray("links").toList { item ->
            PageTurnerCatalogLink(
                rel = item.requireString("rel"),
                href = baseUri.resolve(item.requireString("href")).toString(),
                type = item.optString("type").takeIf { it.isNotBlank() },
            )
        }
        val items = root.optJSONArray("items").toList { item ->
            item.toRemoteBookItem(
                sourceType = RemoteSourceType.PageTurnerWebCatalog,
                accountId = catalogId,
                baseUri = baseUri,
            )
        }

        return PageTurnerCatalog(
            version = version,
            id = catalogId,
            title = root.optString("title").ifBlank { catalogId },
            updatedAt = root.optString("updatedAt").takeIf { it.isNotBlank() },
            links = links,
            items = items,
        )
    }

    private fun JSONObject.toRemoteBookItem(
        sourceType: RemoteSourceType,
        accountId: String,
        baseUri: URI,
    ): RemoteBookItem {
        val remoteId = requireString("id")
        val format = requireString("format").toDocumentFormat()
        val hints = optJSONObject("translationHints")
        return RemoteBookItem(
            identity = RemoteBookIdentity(
                sourceType = sourceType,
                accountId = accountId,
                remoteId = remoteId,
            ),
            title = requireString("title"),
            authors = optJSONArray("authors").toStrings(),
            format = format,
            language = optString("language").takeIf { it.isNotBlank() },
            downloadUrl = baseUri.resolve(requireString("href")).toString(),
            contentType = optString("type").takeIf { it.isNotBlank() },
            sizeBytes = if (has("size")) optLong("size") else null,
            checksum = optString("checksum").takeIf { it.isNotBlank() },
            updatedAt = optString("updatedAt").takeIf { it.isNotBlank() },
            coverUrl = optString("cover").takeIf { it.isNotBlank() }?.let {
                baseUri.resolve(it).toString()
            },
            translationHints = RemoteTranslationHints(
                sourceLanguage = hints?.optString("sourceLanguage")?.ifBlank { "auto" } ?: "auto",
                targetLanguages = hints?.optJSONArray("targetLanguages").toStrings(),
            ),
        )
    }

    private fun String.toDocumentFormat(): DocumentFormat {
        return when (lowercase()) {
            "epub" -> DocumentFormat.EPUB
            "pdf" -> DocumentFormat.PDF
            "md",
            "markdown" -> DocumentFormat.MARKDOWN
            "txt",
            "text" -> DocumentFormat.TEXT
            else -> error("Unsupported remote book format: $this")
        }
    }

    private fun JSONObject.requireString(name: String): String {
        val value = optString(name)
        require(value.isNotBlank()) { "Catalog field '$name' is required." }
        return value
    }

    private fun JSONArray?.toStrings(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { index -> optString(index) }.filter { it.isNotBlank() }
    }

    private inline fun <T> JSONArray?.toList(mapper: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.let(mapper)
        }
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
