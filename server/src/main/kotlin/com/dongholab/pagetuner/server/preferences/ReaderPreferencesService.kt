package com.dongholab.pagetuner.server.preferences

import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReaderPreferencesService(private val jdbc: JdbcTemplate) {
    @Transactional(readOnly = true)
    fun get(user: String): ReaderPreferencesView = stored(user)?.view ?: empty()

    @Transactional
    fun put(user: String, request: PutReaderPreferencesRequest): ReaderPreferencesView {
        if (request.expectedVersion !in 0 until MAX_READER_PREFERENCES_VERSION) invalidPreferences()
        request.preferences.validate()
        // Serialize absent-row creation as well as updates across server processes.
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { row -> while (row.next()) { } },
            "reader-preferences:$user")
        val previous = stored(user)
        if (previous?.mutationId == request.mutationId) {
            if (previous.expectedVersion != request.expectedVersion || previous.view.preferences != request.preferences) {
                throw ReaderPreferencesFailure("READER_PREFERENCES_MUTATION_REUSED", 400,
                    "A mutation ID cannot identify different reader preferences.")
            }
            return previous.view
        }
        val current = previous?.view ?: empty()
        if (current.version == MAX_READER_PREFERENCES_VERSION) throw ReaderPreferencesFailure(
            "READER_PREFERENCES_EXHAUSTED", 409, "The reader preferences version limit has been reached.")
        if (current.version != request.expectedVersion) throw ReaderPreferencesFailure(
            "READER_PREFERENCES_CONFLICT", 409, "The reader preferences changed on another device.", current)
        val value = request.preferences
        val changedAt = Timestamp.from(Instant.now())
        if (previous == null) {
            jdbc.update("""
                insert into reader_preferences(user_id,version,font_size,line_height_percent,page_margin,
                    touch_direction,list_mode,mutation_id,expected_version,updated_at)
                values(?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(), user, current.version + 1, value.fontSize, value.lineHeightPercent, value.pageMargin,
                value.touchDirection, value.listMode, request.mutationId, request.expectedVersion, changedAt)
        } else {
            val changed = jdbc.update("""
                update reader_preferences set version=?,font_size=?,line_height_percent=?,page_margin=?,
                    touch_direction=?,list_mode=?,mutation_id=?,expected_version=?,updated_at=?
                where user_id=? and version=?
            """.trimIndent(), current.version + 1, value.fontSize, value.lineHeightPercent, value.pageMargin,
                value.touchDirection, value.listMode, request.mutationId, request.expectedVersion, changedAt, user, request.expectedVersion)
            check(changed == 1) { "Reader preferences changed without their transaction lock." }
        }
        // Use persisted timestamp precision so response-loss retries return an identical view.
        return requireNotNull(stored(user)).view
    }

    private fun stored(user: String): StoredPreferences? = jdbc.query(
        "select * from reader_preferences where user_id=?", RowMapper { row, _ ->
            StoredPreferences(ReaderPreferencesView(row.getLong("version"),
                ReaderPreferences(row.getInt("font_size"), row.getInt("line_height_percent"), row.getInt("page_margin"),
                    row.getString("touch_direction"), row.getString("list_mode")), row.getTimestamp("updated_at").toInstant()),
                row.getObject("mutation_id", UUID::class.java), row.getLong("expected_version"))
        }, user).singleOrNull()

    private fun empty() = ReaderPreferencesView(0, null, null)
    private data class StoredPreferences(val view: ReaderPreferencesView, val mutationId: UUID, val expectedVersion: Long)
}
