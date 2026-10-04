package com.dongholab.pagetuner.server.progress

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReadingProgressService(private val jdbc: JdbcTemplate) {
    @Transactional(readOnly = true)
    fun get(user: String, kind: ReadingProgressKind, recordId: UUID): ReadingProgressView {
        requireOwner(user, kind, recordId)
        return stored(user, kind, recordId)?.view ?: empty(kind, recordId)
    }

    @Transactional
    fun put(user: String, kind: ReadingProgressKind, recordId: UUID, request: PutReadingProgressRequest): ReadingProgressView {
        if (request.expectedVersion !in 0 until MAX_PROGRESS_VERSION ||
            request.anchor.paragraphId.length !in 1..200 || request.anchor.paragraphId.any(Char::isISOControl) ||
            request.anchor.characterOffset < 0) invalidProgress()
        // A database transaction lock also serializes first writes across server processes.
        // Every update still checks the client's observed version before changing the row.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { row -> while (row.next()) { } },
            "reading-progress:$user:${kind.name}:$recordId")
        requireOwner(user, kind, recordId)
        validateAnchor(user, kind, recordId, request.anchor)
        val previous = stored(user, kind, recordId)
        if (previous?.mutationId == request.mutationId) {
            if (previous.expectedVersion != request.expectedVersion || previous.view.anchor != request.anchor) {
                throw ReadingProgressFailure("READING_PROGRESS_MUTATION_REUSED", 400, "A mutation ID cannot identify different reading positions.")
            }
            return previous.view
        }
        val current = previous?.view ?: empty(kind, recordId)
        if (current.version != request.expectedVersion) {
            throw ReadingProgressFailure("READING_PROGRESS_CONFLICT", 409, "The reading position changed on another device.", current)
        }
        val version = current.version + 1
        val changedAt = Timestamp.from(Instant.now())
        if (previous == null) {
            jdbc.update("""
                insert into reading_progress(user_id,kind,record_id,original_record_id,translation_record_id,
                    version,paragraph_id,character_offset,mutation_id,expected_version,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(), user, kind.name, recordId,
                recordId.takeIf { kind == ReadingProgressKind.ORIGINAL },
                recordId.takeIf { kind == ReadingProgressKind.TRANSLATION }, version,
                request.anchor.paragraphId, request.anchor.characterOffset, request.mutationId, request.expectedVersion, changedAt)
        } else {
            val changed = jdbc.update("""
                update reading_progress set version=?,paragraph_id=?,character_offset=?,mutation_id=?,expected_version=?,updated_at=?
                where user_id=? and kind=? and record_id=? and version=?
            """.trimIndent(), version, request.anchor.paragraphId, request.anchor.characterOffset, request.mutationId,
                request.expectedVersion, changedAt, user, kind.name, recordId, request.expectedVersion)
            check(changed == 1) { "Reading progress changed without its transaction lock." }
        }
        // Return the stored timestamp precision so a response-loss retry is byte-for-byte equivalent.
        return requireNotNull(stored(user, kind, recordId)).view
    }

    private fun requireOwner(user: String, kind: ReadingProgressKind, recordId: UUID) {
        val exists = jdbc.queryForObject("select exists(select 1 from ${table(kind)} where id=? and user_id=?)",
            Boolean::class.java, recordId, user) == true
        if (!exists) throw ReadingProgressFailure("READING_PROGRESS_NOT_FOUND", 404, "The server document was not found.")
    }

    private fun validateAnchor(user: String, kind: ReadingProgressKind, recordId: UUID, anchor: ReadingProgressAnchor) {
        val texts = jdbc.query("""
            select paragraph->>'text' as text from ${table(kind)} document
            cross join lateral jsonb_array_elements(document.paragraphs_json::jsonb) paragraph
            where document.id=? and document.user_id=? and paragraph->>'paragraphId'=?
        """.trimIndent(), RowMapper { row, _ -> row.getString("text") }, recordId, user, anchor.paragraphId)
        val text = texts.singleOrNull() ?: invalidProgress()
        val offset = anchor.characterOffset
        if (offset > text.length || (offset in 1 until text.length &&
                Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset]))) invalidProgress()
    }

    private fun table(kind: ReadingProgressKind) = when (kind) {
        ReadingProgressKind.ORIGINAL -> "source_chapter"
        ReadingProgressKind.TRANSLATION -> "translation_artifact"
    }

    private fun stored(user: String, kind: ReadingProgressKind, recordId: UUID): StoredProgress? = jdbc.query(
        "select * from reading_progress where user_id=? and kind=? and record_id=?", RowMapper { row, _ ->
            StoredProgress(ReadingProgressView(kind, recordId, row.getLong("version"),
                ReadingProgressAnchor(row.getString("paragraph_id"), row.getInt("character_offset")),
                row.getTimestamp("updated_at").toInstant()), row.getObject("mutation_id", UUID::class.java), row.getLong("expected_version"))
        }, user, kind.name, recordId).singleOrNull()

    private fun empty(kind: ReadingProgressKind, recordId: UUID) = ReadingProgressView(kind, recordId, 0, null, null)
    private data class StoredProgress(val view: ReadingProgressView, val mutationId: UUID, val expectedVersion: Long)
}
