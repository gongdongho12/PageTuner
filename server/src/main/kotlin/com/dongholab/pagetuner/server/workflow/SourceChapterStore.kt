package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.core.model.library.LibraryFilter
import com.dongholab.pagetuner.server.organization.LibraryFilterSql
import com.dongholab.pagetuner.server.organization.LibraryOrganizationKind
import com.dongholab.pagetuner.source.service.SourceChapterDraft
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Stored content is unreadable; deliberately distinct from JDBC/SQL infrastructure failures. */
class SourceChapterMetadataUnavailable(cause: Exception) : RuntimeException("Stored source metadata is unavailable.", cause)

@Repository
class SourceChapterStore(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    @Transactional
    fun save(user: String, draft: SourceChapterDraft): StoredChapter {
        require(user.isNotBlank())
        require(draft.providerId.isNotBlank() && draft.bookId.isNotBlank() && draft.chapterId.isNotBlank())
        require(draft.sourceLanguage.length in 1..32)
        val texts = draft.paragraphs.filter(String::isNotBlank)
        require(texts.isNotEmpty() && texts.size <= 10_000 && texts.sumOf { it.length.toLong() } <= 1_000_000) {
            "The chapter must contain 1–10,000 paragraphs and at most 1,000,000 characters."
        }
        val paragraphs = texts.mapIndexed { index, text -> SourceParagraph("p-${index + 1}-${StableContentHash.sha256(text).take(16)}", index, text) }
        val chapter = StoredChapter(UUID.randomUUID(), draft.providerId, draft.bookId, draft.bookTitle,
            draft.bookUrl, draft.chapterId, draft.chapterTitle, draft.chapterUrl, draft.sourceLanguage,
            "", paragraphs, Instant.now())
        return persist(user, chapter)
    }

    @Transactional
    fun upload(user: String, request: UploadedChapterRequest): StoredChapter {
        require(user.isNotBlank())
        require(listOf(request.bookId, request.bookTitle, request.chapterId, request.chapterTitle).all { it.isNotBlank() && it.length <= 2000 && it.none(Char::isISOControl) }) { "Invalid document metadata." }
        require(request.sourceLanguage.length in 2..24 && request.sourceLanguage.matches(Regex("[A-Za-z][A-Za-z0-9-]+"))) { "Invalid source language." }
        val paragraphs = request.paragraphs.toList()
        require(paragraphs.isNotEmpty() && paragraphs.size <= 10_000 && paragraphs.sumOf { it.text.length.toLong() } <= 1_000_000) { "Document must contain 1–10,000 paragraphs and at most 1,000,000 characters." }
        require(paragraphs.map { it.ordinal } == paragraphs.indices.toList() && paragraphs.all { it.text.isNotBlank() && it.paragraphId.length in 1..200 && it.paragraphId.none(Char::isISOControl) }) { "Invalid document paragraph order or content." }
        val chapter = StoredChapter(UUID.randomUUID(), "uploaded-document", request.bookId, request.bookTitle,
            "", request.chapterId, request.chapterTitle, "", request.sourceLanguage, "", paragraphs, Instant.now())
        return persist(user, chapter)
    }

    private fun persist(user: String, chapter: StoredChapter): StoredChapter {
        val source = chapter.content()
        val result = chapter.copy(sourceRevision = source.sourceRevision)
        val identity = StableContentHash.sha256(json.writeValueAsString(listOf(chapter.providerId, chapter.bookId, chapter.chapterId)))
        jdbc.update("""
            insert into source_chapter(id,user_id,identity_key,provider_id,book_id,book_title,book_url,chapter_id,chapter_title,
                chapter_url,source_language,source_revision,paragraphs_json,created_at)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(user_id,identity_key,source_revision,source_language) do nothing
        """.trimIndent(), result.recordId, user, identity, result.providerId, result.bookId, result.bookTitle, result.bookUrl,
            result.chapterId, result.chapterTitle, result.chapterUrl, result.sourceLanguage, result.sourceRevision,
            json.writeValueAsString(chapter.paragraphs), java.sql.Timestamp.from(result.createdAt))
        return jdbc.query("select * from source_chapter where user_id=? and identity_key=? and source_revision=? and source_language=?", mapper,
            user, identity, result.sourceRevision, result.sourceLanguage).single()
    }

    fun get(user: String, id: UUID): StoredChapter = jdbc.query("select * from source_chapter where id=? and user_id=?", mapper, id, user)
        .singleOrNull() ?: throw WorkflowFailure("CHAPTER_NOT_FOUND", 404, "원문을 찾을 수 없습니다.")

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun list(user: String, page: Int, size: Int, filter: LibraryFilter = LibraryFilter()): WorkflowPage<ChapterSummary> {
        validateWorkflowPage(page, size)
        val query = LibraryFilterSql(filter, LibraryOrganizationKind.ORIGINAL)
        val arguments = listOf(user) + query.arguments
        val count = jdbc.queryForObject("select count(*) from source_chapter document ${query.join} where document.user_id=? ${query.predicates}",
            Long::class.java, *arguments.toTypedArray())!!
        val items = jdbc.query("""
            select id,provider_id,book_id,book_title,book_url,chapter_id,chapter_title,chapter_url,source_language,source_revision,
                created_at,jsonb_array_length(paragraphs_json::jsonb) paragraph_count from (
                select document.id,document.provider_id,document.book_id,document.book_title,document.book_url,
                    document.chapter_id,document.chapter_title,document.chapter_url,document.source_language,
                    document.source_revision,document.created_at,document.paragraphs_json
                from source_chapter document ${query.join}
                where document.user_id=? ${query.predicates}
                order by document.created_at desc,document.id desc limit ? offset ?
            ) library_page order by created_at desc,id desc
        """.trimIndent(), RowMapper { row, _ -> ChapterSummary(row.getObject("id", UUID::class.java), row.getString("provider_id"),
            row.getString("book_id"), row.getString("book_title"), row.getString("book_url"), row.getString("chapter_id"),
            row.getString("chapter_title"), row.getString("chapter_url"), row.getString("source_language"), row.getString("source_revision"),
            row.getInt("paragraph_count"), row.getTimestamp("created_at").toInstant()) },
            *(arguments + listOf(size, page.toLong() * size)).toTypedArray())
        return WorkflowPage(items, page, size, count)
    }

    private val mapper = RowMapper { row: ResultSet, _: Int ->
        // Read through JDBC outside content validation. SQL failures must retain their original type.
        val serializedParagraphs = row.getString("paragraphs_json")
        val paragraphs = try {
            val decoded = json.readValue(serializedParagraphs, object : TypeReference<List<SourceParagraph?>?>() {})
            requireNotNull(decoded).map { requireNotNull(it) }
        } catch (error: JsonProcessingException) { throw SourceChapterMetadataUnavailable(error) }
        catch (error: IllegalArgumentException) { throw SourceChapterMetadataUnavailable(error) }
        val chapter = StoredChapter(row.getObject("id", UUID::class.java), row.getString("provider_id"), row.getString("book_id"),
            row.getString("book_title"), row.getString("book_url"), row.getString("chapter_id"), row.getString("chapter_title"),
            row.getString("chapter_url"), row.getString("source_language"), row.getString("source_revision"),
            paragraphs, row.getTimestamp("created_at").toInstant())
        try {
            check(chapter.content().sourceRevision == chapter.sourceRevision) { "Stored source revision does not match its paragraphs." }
        } catch (error: IllegalArgumentException) { throw SourceChapterMetadataUnavailable(error) }
        catch (error: IllegalStateException) { throw SourceChapterMetadataUnavailable(error) }
        chapter
    }
}
