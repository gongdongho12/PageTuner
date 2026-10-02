package com.dongholab.pagetuner.server.favorites

import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteMetadata
import java.time.Instant
import java.util.UUID

internal const val MAX_FAVORITE_REVISION = 9_007_199_254_740_991L

data class SourceBookFavoriteItem(
    val providerId: String, val bookId: String, val version: Long, val changeRevision: Long,
    val deleted: Boolean, val book: SourceBookFavoriteMetadata?, val updatedAt: Instant?,
)
data class SourceBookFavoriteChanges(
    val items: List<SourceBookFavoriteItem>, val nextAfterRevision: Long, val watermark: Long, val hasMore: Boolean,
)
data class PutSourceBookFavoriteRequest(
    val providerId: String, val bookId: String, val expectedVersion: Long, val mutationId: UUID,
    val deleted: Boolean, val book: SourceBookFavoriteMetadata?,
)
class SourceBookFavoriteFailure(
    val code: String, val status: Int, message: String,
    val current: SourceBookFavoriteItem? = null, val retryAfterSeconds: Int? = null,
) : RuntimeException(message)
internal fun invalidFavorite(): Nothing = throw SourceBookFavoriteFailure(
    "SOURCE_BOOK_FAVORITE_INVALID", 400, "Invalid source book favorite request.")
