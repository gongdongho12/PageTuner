package com.dongholab.pagetuner.server.preferences

import java.time.Instant
import java.util.UUID

const val MAX_READER_PREFERENCES_VERSION = 9_007_199_254_740_991L

/** Only portable reader controls are shared; font families, hardware keys and provider secrets stay local. */
data class ReaderPreferences(
    val fontSize: Int,
    val lineHeightPercent: Int,
    val pageMargin: Int,
    val touchDirection: String,
    val listMode: String,
)

data class ReaderPreferencesView(
    val version: Long,
    val preferences: ReaderPreferences?,
    val updatedAt: Instant?,
)

data class PutReaderPreferencesRequest(
    val expectedVersion: Long,
    val mutationId: UUID,
    val preferences: ReaderPreferences,
)

class ReaderPreferencesFailure(
    val code: String,
    val status: Int,
    message: String,
    val current: ReaderPreferencesView? = null,
    val retryAfterSeconds: Int? = null,
) : RuntimeException(message)

internal fun invalidPreferences(): Nothing = throw ReaderPreferencesFailure(
    "READER_PREFERENCES_INVALID", 400, "Invalid reader preferences request.",
)

internal fun ReaderPreferences.validate() {
    if (fontSize !in 14..36 || lineHeightPercent !in 110..240 || pageMargin !in 0..48 ||
        touchDirection !in setOf("left-previous", "left-next", "buttons-only") ||
        listMode !in setOf("paged", "scroll")) invalidPreferences()
}
