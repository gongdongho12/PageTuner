package com.dongholab.pagetuner.server.notes

import com.dongholab.pagetuner.server.progress.MAX_PROGRESS_VERSION
import com.dongholab.pagetuner.server.progress.ReadingProgressAnchor
import com.dongholab.pagetuner.server.progress.ReadingProgressKind
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class ReadingNoteService(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun changes(user: String, kind: ReadingProgressKind, recordId: UUID, afterRevision: Long = 0,
                untilRevision: Long? = null, limit: Int = 50): ReadingNoteChanges {
        if (afterRevision !in 0..MAX_PROGRESS_VERSION || untilRevision?.let { it !in 0..MAX_PROGRESS_VERSION } == true || limit !in 1..100) invalidNote()
        requireOwner(user, kind, recordId)
        val current = revision(user, kind, recordId)
        val watermark = untilRevision ?: current
        if (watermark !in afterRevision..current) invalidNote()
        val rows = jdbc.query("""
            select * from reading_note_change where user_id=? and kind=? and record_id=?
                and change_revision>? and change_revision<=? order by change_revision limit ?
        """.trimIndent(), RowMapper { row, _ -> item(row) }, user, kind.name, recordId, afterRevision, watermark, limit + 1)
        val items = rows.take(limit)
        val hasMore = rows.size > limit
        return ReadingNoteChanges(kind, recordId, items, if (hasMore) items.last().changeRevision else watermark, watermark, hasMore)
    }

    @Transactional
    fun put(user: String, kind: ReadingProgressKind, recordId: UUID, noteId: UUID, request: PutReadingNoteRequest): ReadingNoteItem {
        if (request.expectedVersion !in 0 until MAX_PROGRESS_VERSION || request.deleted != (request.note == null)) invalidNote()
        // Commit order must match revision order: a sequence alone would allow a late lower revision
        // to commit after clients had already advanced their cursor past it.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { row -> while (row.next()) { } },
            "reading-notes:$user:${kind.name}:$recordId")
        requireOwner(user, kind, recordId)
        val requestJson = json.writeValueAsString(request)
        val previous = stored(user, kind, recordId, noteId)
        if (previous?.mutationId == request.mutationId) {
            if (previous.requestJson != requestJson) throw ReadingNoteFailure("READING_NOTE_MUTATION_REUSED", 400,
                "A mutation ID cannot identify different note changes.")
            return previous.item
        }
        val current = previous?.item ?: ReadingNoteItem(noteId, 0, 0, true, null, null)
        if (current.version != request.expectedVersion) throw ReadingNoteFailure("READING_NOTE_CONFLICT", 409,
            "The note changed on another device.", current)
        val note = request.note?.let { validateNote(user, kind, recordId, it) }
        val before = revision(user, kind, recordId)
        if (before == MAX_PROGRESS_VERSION || current.version == MAX_PROGRESS_VERSION) throw ReadingNoteFailure(
            "READING_NOTE_REVISION_EXHAUSTED", 409, "The reading note revision limit has been reached.")
        jdbc.update("""
            insert into reading_note_document(user_id,kind,record_id,original_record_id,translation_record_id,revision)
            values(?,?,?,?,?,0) on conflict(user_id,kind,record_id) do nothing
        """.trimIndent(), user, kind.name, recordId, recordId.takeIf { kind == ReadingProgressKind.ORIGINAL },
            recordId.takeIf { kind == ReadingProgressKind.TRANSLATION })
        val nextRevision = before + 1
        val changed = jdbc.update("update reading_note_document set revision=? where user_id=? and kind=? and record_id=? and revision=?",
            nextRevision, user, kind.name, recordId, before)
        check(changed == 1) { "Reading note revision changed without its transaction lock." }
        jdbc.update("""
            insert into reading_note_change(user_id,kind,record_id,change_revision,note_id,version,deleted,note_json,updated_at)
            values(?,?,?,?,?,?,?,?,?)
        """.trimIndent(), user, kind.name, recordId, nextRevision, noteId, current.version + 1, request.deleted,
            note?.let(json::writeValueAsString), Timestamp.from(Instant.now()))
        jdbc.update("""
            insert into reading_note_current(user_id,kind,record_id,note_id,change_revision,mutation_id,request_json)
            values(?,?,?,?,?,?,?) on conflict(user_id,kind,record_id,note_id) do update
                set change_revision=excluded.change_revision,mutation_id=excluded.mutation_id,request_json=excluded.request_json
        """.trimIndent(), user, kind.name, recordId, noteId, nextRevision, request.mutationId, requestJson)
        return requireNotNull(stored(user, kind, recordId, noteId)).item
    }

    private fun validateNote(user: String, kind: ReadingProgressKind, recordId: UUID, input: ReadingNoteInput): ReadingNote {
        if (input.title.isBlank() || input.title.length > 200 || input.text.length > 4000 ||
            !validNoteUnicode(input.title) || !validNoteUnicode(input.text) ||
            input.kind == ReadingNoteKind.NOTE && input.text.isBlank() || input.createdAt < MIN_NOTE_TIME || input.createdAt > MAX_NOTE_TIME) invalidNote()
        val raw = jdbc.queryForObject("select paragraphs_json from ${table(kind)} where user_id=? and id=?", String::class.java, user, recordId)!!
        val paragraphs = json.readTree(raw).map { Paragraph(it["paragraphId"].textValue(), it["text"].textValue()) }
        val index = validateAnchor(paragraphs, input.anchor, allowEnd = false)
        val excerpt = if (input.kind == ReadingNoteKind.HIGHLIGHT) {
            val range = input.range ?: invalidNote()
            if (range.start != input.anchor) invalidNote()
            val end = validateAnchor(paragraphs, range.end, allowEnd = true)
            if (end < index || end == index && range.end.characterOffset <= range.start.characterOffset) invalidNote()
            val selected = StringBuilder()
            for (position in index..end) {
                if (position > index) selected.append("\n\n")
                val text = paragraphs[position].text
                val from = if (position == index) range.start.characterOffset else 0
                val until = if (position == end) range.end.characterOffset else text.length
                if (selected.length.toLong() + until - from > 4000) invalidNote()
                selected.append(text, from, until)
            }
            selected.toString().also { if (it.isBlank()) invalidNote() }
        } else {
            if (input.range != null) invalidNote()
            val text = paragraphs[index].text
            var end = minOf(text.length, input.anchor.characterOffset + 1000)
            if (end in 1 until text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            text.substring(input.anchor.characterOffset, end)
        }
        return ReadingNote(input.kind, input.title, input.text, input.anchor, input.range, input.createdAt, excerpt)
    }

    private fun validateAnchor(paragraphs: List<Paragraph>, anchor: ReadingProgressAnchor, allowEnd: Boolean): Int {
        if (anchor.paragraphId.length !in 1..200 || anchor.paragraphId.any(Char::isISOControl) || !validNoteUnicode(anchor.paragraphId)) invalidNote()
        val matches = paragraphs.indices.filter { paragraphs[it].id == anchor.paragraphId }
        val index = matches.singleOrNull() ?: invalidNote()
        val text = paragraphs[index].text
        val offset = anchor.characterOffset
        if (offset < 0 || offset > text.length || !allowEnd && offset == text.length ||
            offset in 1 until text.length && Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])) invalidNote()
        return index
    }

    private fun requireOwner(user: String, kind: ReadingProgressKind, recordId: UUID) {
        if (jdbc.queryForObject("select exists(select 1 from ${table(kind)} where id=? and user_id=?)", Boolean::class.java, recordId, user) != true) {
            throw ReadingNoteFailure("READING_NOTE_NOT_FOUND", 404, "The server document was not found.")
        }
    }
    private fun table(kind: ReadingProgressKind) = if (kind == ReadingProgressKind.ORIGINAL) "source_chapter" else "translation_artifact"
    private fun revision(user: String, kind: ReadingProgressKind, recordId: UUID) = jdbc.query(
        "select revision from reading_note_document where user_id=? and kind=? and record_id=?",
        RowMapper { row, _ -> row.getLong("revision") }, user, kind.name, recordId).singleOrNull() ?: 0L
    private fun stored(user: String, kind: ReadingProgressKind, recordId: UUID, noteId: UUID) = jdbc.query("""
        select change.*,current.mutation_id,current.request_json from reading_note_current current
        join reading_note_change change on current.user_id=change.user_id and current.kind=change.kind
            and current.record_id=change.record_id and current.change_revision=change.change_revision
        where current.user_id=? and current.kind=? and current.record_id=? and current.note_id=?
    """.trimIndent(), RowMapper { row, _ -> StoredNote(item(row), row.getObject("mutation_id", UUID::class.java), row.getString("request_json")) },
        user, kind.name, recordId, noteId).singleOrNull()
    private fun item(row: ResultSet) = ReadingNoteItem(row.getObject("note_id", UUID::class.java), row.getLong("version"), row.getLong("change_revision"),
        row.getBoolean("deleted"), row.getString("note_json")?.let { json.readValue(it, ReadingNote::class.java) }, row.getTimestamp("updated_at").toInstant())
    private data class StoredNote(val item: ReadingNoteItem, val mutationId: UUID, val requestJson: String)
    private data class Paragraph(val id: String, val text: String)
    companion object {
        private val MIN_NOTE_TIME = Instant.parse("0001-01-01T00:00:00Z")
        private val MAX_NOTE_TIME = Instant.parse("9999-12-31T23:59:59.999999999Z")
    }
}
