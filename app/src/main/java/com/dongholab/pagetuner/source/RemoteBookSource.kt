package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageLoader
import com.dongholab.pagetuner.core.paging.PageRequest
import com.dongholab.pagetuner.core.paging.PageResult
import com.dongholab.pagetuner.document.DocumentFormat

enum class RemoteSourceType {
    PageTurnerWebCatalog,
    FtpServer,
    GoogleDrive,
    WebNovel,
}

data class RemoteSourceConnection(
    val sourceType: RemoteSourceType,
    val accountId: String,
    val title: String,
    val itemCount: Int,
)

data class RemoteBookIdentity(
    val sourceType: RemoteSourceType,
    val accountId: String,
    val remoteId: String,
)

enum class RemoteBookContentVariant {
    Original,
    Translated,
}

data class RemoteTranslationHints(
    val sourceLanguage: String = "auto",
    val targetLanguages: List<String> = emptyList(),
)

data class RemoteBookItem(
    val identity: RemoteBookIdentity,
    val title: String,
    val authors: List<String> = emptyList(),
    val format: DocumentFormat,
    val language: String? = null,
    val downloadUrl: String,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val checksum: String? = null,
    val updatedAt: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    val chapterCount: Int? = null,
    val tags: List<String> = emptyList(),
    val translationHints: RemoteTranslationHints = RemoteTranslationHints(),
    /** Stable parent-work identity shared by every chapter of one web novel. */
    val seriesId: String? = null,
    val seriesTitle: String? = null,
    val chapterNumber: Int? = null,
    /** Indicates that the downloaded bytes already contain translated reader text. */
    val contentVariant: RemoteBookContentVariant = RemoteBookContentVariant.Original,
)

enum class RemoteCatalogLoadStep {
    FetchingPage,
    ParsingDom,
}

/** A server-side page returned by a paginated remote source. */
data class RemoteCatalogPage(
    val title: String,
    val url: String,
    override val items: List<RemoteBookItem>,
    override val currentPage: Int = 1,
    override val totalPages: Int? = null,
    override val totalItems: Int? = null,
    override val hasPreviousPage: Boolean = currentPage > 1,
    override val hasNextPage: Boolean = totalPages?.let { currentPage < it } ?: false,
) : PageResult<RemoteBookItem>

/** Optional capability for sources whose catalogs are paged by the remote website. */
interface PaginatedRemoteBookSource : PageLoader<RemoteBookItem> {
    suspend fun loadCatalogPage(
        page: Int,
        onStep: (RemoteCatalogLoadStep) -> Unit = {},
    ): RemoteCatalogPage

    override suspend fun loadPage(request: PageRequest): PageResult<RemoteBookItem> =
        loadCatalogPage(request.pageNumber)
}

interface RemoteBookSource {
    val sourceType: RemoteSourceType
    val accountId: String

    suspend fun connect(): RemoteSourceConnection

    suspend fun list(): List<RemoteBookItem>

    suspend fun search(query: String): List<RemoteBookItem>

    suspend fun download(item: RemoteBookItem): ByteArray

    suspend fun refresh(): List<RemoteBookItem>
}
