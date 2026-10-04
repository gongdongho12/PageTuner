package com.dongholab.pagetuner.server.glossary

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import java.time.Instant
import java.util.UUID

internal const val MAX_BOOK_GLOSSARY_VERSION = 9_007_199_254_740_991L
data class BookGlossaryView(val providerId: String, val bookId: String, val targetLanguage: String,
    val version: Long, val entries: List<BookGlossarySyncEntry>?, val updatedAt: Instant?)
data class PutBookGlossaryRequest(val providerId: String, val bookId: String, val targetLanguage: String,
    val expectedVersion: Long, val mutationId: UUID, val entries: List<BookGlossarySyncEntry>?) {
    fun identity() = BookGlossarySyncIdentity(providerId, bookId, targetLanguage)
}
class BookGlossaryFailure(val code: String, val status: Int, message: String,
    val current: BookGlossaryView? = null, val retryAfterSeconds: Int? = null) : RuntimeException(message)
internal fun invalidGlossary(): Nothing = throw BookGlossaryFailure("BOOK_GLOSSARY_INVALID", 400, "Invalid book glossary request.")
