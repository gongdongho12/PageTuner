package com.dongholab.pagetuner.server.glossary

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncValidation
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BookGlossaryService(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    @Transactional(readOnly = true)
    fun get(user: String, identity: BookGlossarySyncIdentity): BookGlossaryView {
        validate(identity)
        return stored(user, identity, digest(identity))?.view ?: empty(identity)
    }

    @Transactional
    fun put(user: String, request: PutBookGlossaryRequest): BookGlossaryView {
        val identity = request.identity()
        validate(identity, request.entries)
        if (request.expectedVersion !in 0 until MAX_BOOK_GLOSSARY_VERSION) invalidGlossary()
        val digest = digest(identity)
        // Length-safe, unambiguous account/source/language tuple. Collision only serializes unrelated writers.
        val lockKey = json.writeValueAsString(listOf("book-glossary", user, identity.providerId, identity.bookId, identity.targetLanguage))
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { row -> while (row.next()) { } }, lockKey)
        val previous = stored(user, identity, digest)
        if (previous?.mutationId == request.mutationId) {
            if (previous.expectedVersion != request.expectedVersion || previous.view.entries != request.entries)
                throw BookGlossaryFailure("BOOK_GLOSSARY_MUTATION_REUSED", 400, "A mutation ID cannot identify different glossary changes.")
            return previous.view
        }
        val current = previous?.view ?: empty(identity)
        if (current.version == MAX_BOOK_GLOSSARY_VERSION) throw BookGlossaryFailure("BOOK_GLOSSARY_EXHAUSTED", 409, "The glossary version limit has been reached.")
        if (current.version != request.expectedVersion) throw BookGlossaryFailure("BOOK_GLOSSARY_CONFLICT", 409, "The book glossary changed on another device.", current)
        val entriesJson = request.entries?.let(json::writeValueAsString)
        val at = Timestamp.from(Instant.now())
        if (previous == null) {
            jdbc.update("""
                insert into book_glossary(user_id,identity_digest,provider_id,book_id,target_language,version,entries_json,mutation_id,expected_version,updated_at)
                values(?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(), user, digest, identity.providerId, identity.bookId, identity.targetLanguage, 1L, entriesJson, request.mutationId, request.expectedVersion, at)
        } else {
            val changed = jdbc.update("""
                update book_glossary set version=?,entries_json=?,mutation_id=?,expected_version=?,updated_at=?
                where user_id=? and identity_digest=? and version=?
            """.trimIndent(), current.version + 1, entriesJson, request.mutationId, request.expectedVersion, at, user, digest, request.expectedVersion)
            check(changed == 1) { "Book glossary changed without its transaction lock." }
        }
        return requireNotNull(stored(user, identity, digest)).view
    }

    private fun validate(identity: BookGlossarySyncIdentity, entries: List<BookGlossarySyncEntry>? = null) {
        try { BookGlossarySyncValidation.validateIdentity(identity); entries?.let(BookGlossarySyncValidation::validateEntries) }
        catch (_: IllegalArgumentException) { invalidGlossary() }
    }
    private fun digest(identity: BookGlossarySyncIdentity) = MessageDigest.getInstance("SHA-256")
        .digest(json.writeValueAsBytes(listOf(identity.providerId, identity.bookId, identity.targetLanguage)))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun stored(user: String, identity: BookGlossarySyncIdentity, digest: String): Stored? = jdbc.query(
        "select * from book_glossary where user_id=? and identity_digest=?", RowMapper { row, _ ->
            check(row.getString("provider_id") == identity.providerId && row.getString("book_id") == identity.bookId &&
                row.getString("target_language") == identity.targetLanguage) { "Glossary identity digest collision." }
            val entries = row.getString("entries_json")?.let { json.readValue(it, Array<BookGlossarySyncEntry>::class.java).toList() }
            Stored(BookGlossaryView(identity.providerId, identity.bookId, identity.targetLanguage, row.getLong("version"), entries,
                row.getTimestamp("updated_at").toInstant()), row.getObject("mutation_id", UUID::class.java), row.getLong("expected_version"))
        }, user, digest).singleOrNull()
    private fun empty(identity: BookGlossarySyncIdentity) = BookGlossaryView(identity.providerId, identity.bookId, identity.targetLanguage, 0, null, null)
    private data class Stored(val view: BookGlossaryView, val mutationId: UUID, val expectedVersion: Long)
}
