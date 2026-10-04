package com.dongholab.pagetuner.server.progress

import java.time.Instant
import java.util.UUID

const val MAX_PROGRESS_VERSION = 9_007_199_254_740_991L

enum class ReadingProgressKind { ORIGINAL, TRANSLATION }

/** Character offsets use UTF-16 code units, as both Kotlin and JavaScript readers do. */
data class ReadingProgressAnchor(val paragraphId: String, val characterOffset: Int)
data class ReadingProgressView(
    val kind: ReadingProgressKind,
    val recordId: UUID,
    val version: Long,
    val anchor: ReadingProgressAnchor?,
    val updatedAt: Instant?,
)
data class PutReadingProgressRequest(
    val expectedVersion: Long,
    val mutationId: UUID,
    val anchor: ReadingProgressAnchor,
)

class ReadingProgressFailure(
    val code: String,
    val status: Int,
    message: String,
    val current: ReadingProgressView? = null,
    val retryAfterSeconds: Int? = null,
) : RuntimeException(message)

internal fun invalidProgress(): Nothing = throw ReadingProgressFailure(
    "READING_PROGRESS_INVALID", 400, "Invalid reading position request.",
)
