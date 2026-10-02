package com.dongholab.pagetuner.server.organization

import java.time.Instant
import java.util.UUID

internal const val MAX_LIBRARY_ORGANIZATION_VERSION = 9_007_199_254_740_991L
enum class LibraryOrganizationKind { ORIGINAL, TRANSLATION }
data class LibraryOrganization(val folder: String, val tags: List<String>, val favorite: Boolean) {
    internal fun validate() {
        if (!canonical(folder, 0, 200) || tags.size > 32 || tags.any { !canonical(it, 1, 60) } || tags.toSet().size != tags.size) {
            invalidOrganization()
        }
    }
}

data class LibraryOrganizationView(
    val kind: LibraryOrganizationKind, val recordId: UUID, val version: Long,
    val organization: LibraryOrganization?, val updatedAt: Instant?,
)
data class PutLibraryOrganizationRequest(val expectedVersion: Long, val mutationId: UUID, val organization: LibraryOrganization)
class LibraryOrganizationFailure(
    val code: String, val status: Int, message: String,
    val current: LibraryOrganizationView? = null, val retryAfterSeconds: Int? = null,
) : RuntimeException(message)
internal fun invalidOrganization(): Nothing = throw LibraryOrganizationFailure(
    "LIBRARY_ORGANIZATION_INVALID", 400, "Invalid library organization request.")

private fun canonical(value: String, minimum: Int, maximum: Int): Boolean {
    if (value.length !in minimum..maximum || value.any(Char::isISOControl)) return false
    if (value.isNotEmpty() && (trimCharacter(value.first()) || trimCharacter(value.last()))) return false
    var offset = 0
    while (offset < value.length) {
        val character = value[offset++]
        if (Character.isHighSurrogate(character)) {
            if (offset == value.length || !Character.isLowSurrogate(value[offset++])) return false
        } else if (Character.isLowSurrogate(character)) return false
    }
    return true
}

// Explicit ECMAScript trim set rather than platform-specific Char.isWhitespace.
private fun trimCharacter(value: Char): Boolean = value in '\u0009'..'\u000d' || value == '\u0020' || value == '\u00a0' ||
    value == '\u1680' || value in '\u2000'..'\u200a' || value in '\u2028'..'\u2029' || value == '\u202f' ||
    value == '\u205f' || value == '\u3000' || value == '\ufeff'
