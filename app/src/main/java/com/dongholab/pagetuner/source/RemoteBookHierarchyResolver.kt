package com.dongholab.pagetuner.source

data class RemoteBookHierarchy(
    val book: RemoteBookItem,
    val chapters: List<RemoteBookItem>,
)

fun interface RemoteBookHierarchyResolver {
    suspend fun resolve(book: RemoteBookItem): RemoteBookHierarchy
}

object SingleDocumentBookHierarchyResolver : RemoteBookHierarchyResolver {
    override suspend fun resolve(book: RemoteBookItem): RemoteBookHierarchy {
        return RemoteBookHierarchy(
            book = book.copy(chapterCount = book.chapterCount ?: 1),
            chapters = listOf(book),
        )
    }
}

object WebNovelBookHierarchyResolver : RemoteBookHierarchyResolver {
    override suspend fun resolve(book: RemoteBookItem): RemoteBookHierarchy {
        val source = WebNovelRemoteBookSource(
            accountId = book.identity.accountId,
            endpointUrl = book.downloadUrl,
        )
        val detail = source.loadNovelDetail()
        val chapters = source.list()
        return RemoteBookHierarchy(
            book = book.withResolvedSourceIdentity(source.sourceProviderId).copy(
                title = detail.title.ifBlank { book.title },
                authors = listOf(detail.author).filter(String::isNotBlank),
                coverUrl = detail.coverUrl ?: book.coverUrl,
                description = detail.summary.takeIf(String::isNotBlank) ?: book.description,
                chapterCount = detail.totalChapters.takeIf { it > 0 },
                tags = detail.tags,
            ),
            chapters = chapters,
        )
    }
}

class RoutingRemoteBookHierarchyResolver(
    private val resolvers: Map<RemoteSourceType, RemoteBookHierarchyResolver>,
    private val fallback: RemoteBookHierarchyResolver = SingleDocumentBookHierarchyResolver,
) : RemoteBookHierarchyResolver {
    override suspend fun resolve(book: RemoteBookItem): RemoteBookHierarchy {
        return resolvers[book.identity.sourceType]?.resolve(book) ?: fallback.resolve(book)
    }

    companion object {
        val default: RoutingRemoteBookHierarchyResolver by lazy {
            RoutingRemoteBookHierarchyResolver(
                resolvers = mapOf(RemoteSourceType.WebNovel to WebNovelBookHierarchyResolver),
            )
        }
    }
}

/** A resolved metadata URL may change independently of an already known original identity. */
internal fun RemoteBookItem.withResolvedSourceIdentity(providerId: String): RemoteBookItem =
    if (sourceProviderId != null && sourceBookId != null) this else copy(
        sourceProviderId = providerId,
        sourceBookId = com.dongholab.pagetuner.source.webnovel.WebNovelSeriesKeys.fromUrl(downloadUrl),
    )
