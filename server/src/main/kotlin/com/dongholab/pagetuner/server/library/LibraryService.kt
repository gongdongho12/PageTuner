package com.dongholab.pagetuner.server.library

import com.dongholab.pagetuner.core.content.*
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ResponseStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@ResponseStatus(HttpStatus.NOT_FOUND)
class LibraryNotFound : RuntimeException("Library item was not found.")
@ResponseStatus(HttpStatus.CONFLICT)
class ProgressConflict : RuntimeException("Reading position changed. Fetch the current version before saving again.")

@Service
@Transactional(readOnly = true)
class LibraryService(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    private val bookMapper = RowMapper { rs: ResultSet, _: Int ->
        BookResponse(rs.getObject("id", UUID::class.java), rs.getString("title"), rs.getString("author"),
            rs.getString("source_language"), rs.getInt("chapter_count"), rs.getTimestamp("created_at").toInstant())
    }
    private val bookSelect = """select b.*, (select count(*) from library_chapter c where c.book_id=b.id) chapter_count
        from library_book b"""

    fun books(userId: String, page: Int, size: Int, query: String): PageResponse<BookResponse> {
        validatePage(page, size)
        require(query.length <= 200) { "Search query must be at most 200 characters." }
        val filter = "where b.user_id=? and (position(lower(?) in lower(b.title))>0 or position(lower(?) in lower(b.author))>0)"
        val total = jdbc.queryForObject("select count(*) from library_book b $filter", Long::class.java, userId, query, query)!!
        return PageResponse(jdbc.query("$bookSelect $filter order by b.created_at desc,b.id limit ? offset ?",
            bookMapper, userId, query, query, size, page.toLong() * size), page, size, total)
    }

    fun book(userId: String, bookId: UUID): BookResponse =
        jdbc.query("$bookSelect where b.id=? and b.user_id=?", bookMapper, bookId, userId)
            .singleOrNull() ?: throw LibraryNotFound()

    @Transactional
    fun importBook(userId: String, request: ImportBookRequest): BookResponse {
        // Bound the aggregate, not only each individual paragraph.
        require(request.chapters.sumOf { c -> c.paragraphs.sumOf { it.text.length.toLong() } } <= 5_000_000) {
            "A book may contain at most 5 million characters."
        }
        val bookId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc.update("insert into library_book(id,user_id,title,author,source_language,created_at) values (?,?,?,?,?,?)",
            bookId, userId, request.title, request.author, request.sourceLanguage, now)
        request.chapters.forEachIndexed { ordinal, chapter ->
            val id = UUID.randomUUID()
            val content = ChapterContent(ChapterIdentity(BookIdentity("library", bookId.toString()), id.toString()),
                chapter.title, request.sourceLanguage,
                chapter.paragraphs.mapIndexed { index, p -> ContentParagraph(p.paragraphId, index, p.text) })
            jdbc.update("""insert into library_chapter(id,book_id,ordinal,title,source_revision,paragraphs_json)
                values (?,?,?,?,?,?)""", id, bookId, ordinal, chapter.title, content.sourceRevision,
                mapper.writeValueAsString(chapter.paragraphs))
        }
        return book(userId, bookId)
    }

    fun chapters(userId: String, bookId: UUID, page: Int, size: Int): PageResponse<ChapterSummary> {
        validatePage(page, size)
        val book = book(userId, bookId)
        val items = jdbc.query("select * from library_chapter where book_id=? order by ordinal limit ? offset ?",
            RowMapper { rs, _ -> ChapterSummary(rs.getObject("id", UUID::class.java), rs.getInt("ordinal"),
                rs.getString("title"), rs.getString("source_revision")) }, bookId, size, page.toLong() * size)
        return PageResponse(items, page, size, book.chapterCount.toLong())
    }

    fun chapter(userId: String, bookId: UUID, chapterId: UUID): ChapterResponse {
        val book = book(userId, bookId)
        return jdbc.query("select * from library_chapter where id=? and book_id=?", RowMapper { rs, _ ->
            ChapterResponse(chapterId, bookId, rs.getInt("ordinal"), rs.getString("title"),
                sourceLanguage = book.sourceLanguage, sourceRevision = rs.getString("source_revision"),
                paragraphs = mapper.readValue(rs.getString("paragraphs_json"), object : TypeReference<List<ParagraphRequest>>() {}))
        }, chapterId, bookId).singleOrNull() ?: throw LibraryNotFound()
    }

    fun progress(userId: String, bookId: UUID): ProgressResponse {
        book(userId, bookId)
        return jdbc.query("select * from library_progress where book_id=?", RowMapper { rs, _ ->
            ProgressResponse(rs.anchor(), rs.getLong("version"), rs.getTimestamp("updated_at").toInstant())
        }, bookId).singleOrNull() ?: ProgressResponse(null, 0, null)
    }

    @Transactional
    fun saveProgress(userId: String, bookId: UUID, request: SaveProgressRequest): ProgressResponse {
        validateAnchor(userId, bookId, request.anchor)
        // Lock the parent so simultaneous first writes are serialized too.
        jdbc.query("select id from library_book where id=? and user_id=? for update", RowMapper { rs, _ -> rs.getObject(1) }, bookId, userId)
        val current = progress(userId, bookId)
        if (current.version != request.version) throw ProgressConflict()
        val now = Instant.now()
        jdbc.update("""insert into library_progress(book_id,chapter_id,paragraph_id,character_offset,version,updated_at)
            values (?,?,?,?,?,?) on conflict(book_id) do update set chapter_id=excluded.chapter_id,
            paragraph_id=excluded.paragraph_id,character_offset=excluded.character_offset,
            version=excluded.version,updated_at=excluded.updated_at""", bookId, request.anchor.chapterId,
            request.anchor.paragraphId, request.anchor.characterOffset, current.version + 1, Timestamp.from(now))
        return ProgressResponse(request.anchor, current.version + 1, now)
    }

    fun bookmarks(userId: String, bookId: UUID, page: Int, size: Int): PageResponse<BookmarkResponse> {
        validatePage(page, size)
        book(userId, bookId)
        val total = jdbc.queryForObject("select count(*) from library_bookmark where book_id=?", Long::class.java, bookId)!!
        val items = jdbc.query("select * from library_bookmark where book_id=? order by created_at,id limit ? offset ?",
            RowMapper { rs, _ -> BookmarkResponse(rs.getObject("id", UUID::class.java), rs.anchor(),
                rs.getString("note"), rs.getTimestamp("created_at").toInstant()) }, bookId, size, page.toLong() * size)
        return PageResponse(items, page, size, total)
    }

    @Transactional
    fun addBookmark(userId: String, bookId: UUID, request: SaveBookmarkRequest): BookmarkResponse {
        validateAnchor(userId, bookId, request.anchor)
        val result = BookmarkResponse(UUID.randomUUID(), request.anchor, request.note, Instant.now())
        jdbc.update("insert into library_bookmark(id,book_id,chapter_id,paragraph_id,character_offset,note,created_at) values (?,?,?,?,?,?,?)",
            result.id, bookId, result.anchor.chapterId, result.anchor.paragraphId, result.anchor.characterOffset,
            result.note, Timestamp.from(result.createdAt))
        return result
    }

    @Transactional
    fun deleteBookmark(userId: String, bookId: UUID, bookmarkId: UUID) {
        book(userId, bookId)
        if (jdbc.update("delete from library_bookmark where id=? and book_id=?", bookmarkId, bookId) == 0) throw LibraryNotFound()
    }

    private fun validateAnchor(userId: String, bookId: UUID, anchor: AnchorRequest) {
        val chapter = chapter(userId, bookId, anchor.chapterId)
        val paragraph = chapter.paragraphs.find { it.paragraphId == anchor.paragraphId }
        require(paragraph != null) { "Paragraph does not belong to this chapter." }
        require(anchor.characterOffset in 0..paragraph.text.length) { "Character offset is outside the paragraph." }
    }
    private fun ResultSet.anchor() = AnchorRequest(getObject("chapter_id", UUID::class.java), getString("paragraph_id"), getInt("character_offset"))
    private fun validatePage(page: Int, size: Int) {
        require(page >= 0 && size in 1..100) { "page must be nonnegative and size must be between 1 and 100." }
    }
}
