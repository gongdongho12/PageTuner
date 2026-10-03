package com.dongholab.pagetuner.server.favorites

import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteMetadata
import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteValidation
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
class SourceBookFavoriteService(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun changes(user: String, afterRevision: Long = 0, untilRevision: Long? = null, limit: Int = 50): SourceBookFavoriteChanges {
        if (afterRevision !in 0..MAX_FAVORITE_REVISION || untilRevision?.let { it !in 0..MAX_FAVORITE_REVISION } == true || limit !in 1..100) invalidFavorite()
        val current = revision(user)
        val watermark = untilRevision ?: current
        if (watermark !in afterRevision..current) invalidFavorite()
        val rows = jdbc.query("""
            select * from source_book_favorite_change where user_id=? and change_revision>? and change_revision<=?
                order by change_revision limit ?
        """.trimIndent(), RowMapper { row, _ -> item(row) }, user, afterRevision, watermark, limit + 1)
        val items = rows.take(limit)
        val more = rows.size > limit
        return SourceBookFavoriteChanges(items, if (more) items.last().changeRevision else watermark, watermark, more)
    }

    @Transactional
    fun put(user: String, request: PutSourceBookFavoriteRequest): SourceBookFavoriteItem {
        if (request.expectedVersion !in 0 until MAX_FAVORITE_REVISION || request.deleted != (request.book == null)) invalidFavorite()
        try {
            SourceBookFavoriteValidation.validateIdentity(request.providerId, request.bookId)
            request.book?.let(SourceBookFavoriteValidation::validateMetadata)
        } catch (_: IllegalArgumentException) { invalidFavorite() }
        // Account-wide transaction lock makes commit order match feed revision order.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { row -> while (row.next()) { } }, "source-book-favorites:$user")
        val requestJson = json.writeValueAsString(request)
        val previous = stored(user, request.providerId, request.bookId)
        if (previous?.mutationId == request.mutationId) {
            if (previous.requestJson != requestJson) throw SourceBookFavoriteFailure("SOURCE_BOOK_FAVORITE_MUTATION_REUSED", 400,
                "A mutation ID cannot identify different favorite changes.")
            return previous.item
        }
        val current = previous?.item ?: SourceBookFavoriteItem(request.providerId, request.bookId, 0, 0, true, null, null)
        if (current.version != request.expectedVersion) throw SourceBookFavoriteFailure("SOURCE_BOOK_FAVORITE_CONFLICT", 409,
            "The source book favorite changed on another device.", current)
        val before = revision(user)
        if (before == MAX_FAVORITE_REVISION || current.version == MAX_FAVORITE_REVISION) throw SourceBookFavoriteFailure(
            "SOURCE_BOOK_FAVORITE_REVISION_EXHAUSTED", 409, "The source book favorite revision limit has been reached.")
        jdbc.update("insert into source_book_favorite_account(user_id,revision) values(?,0) on conflict(user_id) do nothing", user)
        val nextRevision = before + 1
        val changed = jdbc.update("update source_book_favorite_account set revision=? where user_id=? and revision=?", nextRevision, user, before)
        check(changed == 1) { "Source book favorite revision changed without its transaction lock." }
        jdbc.update("""
            insert into source_book_favorite_change(user_id,change_revision,provider_id,book_id,version,deleted,book_json,updated_at)
            values(?,?,?,?,?,?,?,?)
        """.trimIndent(), user, nextRevision, request.providerId, request.bookId, current.version + 1, request.deleted,
            request.book?.let(json::writeValueAsString), Timestamp.from(Instant.now()))
        jdbc.update("""
            insert into source_book_favorite_current(user_id,provider_id,book_id,change_revision,mutation_id,request_json)
            values(?,?,?,?,?,?) on conflict(user_id,provider_id,book_id) do update
                set change_revision=excluded.change_revision,mutation_id=excluded.mutation_id,request_json=excluded.request_json
        """.trimIndent(), user, request.providerId, request.bookId, nextRevision, request.mutationId, requestJson)
        return requireNotNull(stored(user, request.providerId, request.bookId)).item
    }

    private fun revision(user: String) = jdbc.query("select revision from source_book_favorite_account where user_id=?",
        RowMapper { row, _ -> row.getLong("revision") }, user).singleOrNull() ?: 0L
    private fun stored(user: String, providerId: String, bookId: String) = jdbc.query("""
        select change.*,current.mutation_id,current.request_json from source_book_favorite_current current
        join source_book_favorite_change change on current.user_id=change.user_id and current.change_revision=change.change_revision
        where current.user_id=? and current.provider_id=? and current.book_id=?
    """.trimIndent(), RowMapper { row, _ -> StoredFavorite(item(row), row.getObject("mutation_id", UUID::class.java), row.getString("request_json")) },
        user, providerId, bookId).singleOrNull()
    private fun item(row: ResultSet) = SourceBookFavoriteItem(row.getString("provider_id"), row.getString("book_id"), row.getLong("version"),
        row.getLong("change_revision"), row.getBoolean("deleted"),
        row.getString("book_json")?.let { json.readValue(it, SourceBookFavoriteMetadata::class.java) }, row.getTimestamp("updated_at").toInstant())
    private data class StoredFavorite(val item: SourceBookFavoriteItem, val mutationId: UUID, val requestJson: String)
}
