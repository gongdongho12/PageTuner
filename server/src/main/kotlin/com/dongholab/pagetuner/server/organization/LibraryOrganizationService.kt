package com.dongholab.pagetuner.server.organization

import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class LibraryOrganizationService(private val jdbc: JdbcTemplate, private val json: ObjectMapper) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun get(user: String, kind: LibraryOrganizationKind, recordId: UUID): LibraryOrganizationView {
        requireOwner(user, kind, recordId)
        return stored(user, kind, recordId)?.view ?: empty(kind, recordId)
    }

    @Transactional
    fun put(user: String, kind: LibraryOrganizationKind, recordId: UUID, request: PutLibraryOrganizationRequest): LibraryOrganizationView {
        if (request.expectedVersion !in 0 until MAX_LIBRARY_ORGANIZATION_VERSION) invalidOrganization()
        request.organization.validate()
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { row -> while (row.next()) { } },
            "library-organization:$user:${kind.name}:$recordId")
        requireOwner(user, kind, recordId, lock = true)
        val previous = stored(user, kind, recordId)
        if (previous?.mutationId == request.mutationId) {
            if (previous.expectedVersion != request.expectedVersion || previous.view.organization != request.organization) {
                throw LibraryOrganizationFailure("LIBRARY_ORGANIZATION_MUTATION_REUSED", 400,
                    "A mutation ID cannot identify different library organization.")
            }
            return previous.view
        }
        val current = previous?.view ?: empty(kind, recordId)
        if (current.version == MAX_LIBRARY_ORGANIZATION_VERSION) throw LibraryOrganizationFailure(
            "LIBRARY_ORGANIZATION_EXHAUSTED", 409, "The library organization version limit has been reached.")
        if (current.version != request.expectedVersion) throw LibraryOrganizationFailure(
            "LIBRARY_ORGANIZATION_CONFLICT", 409, "The document organization changed on another device.", current)
        val value = json.writeValueAsString(request.organization)
        val changedAt = Timestamp.from(Instant.now())
        if (previous == null) {
            jdbc.update("""
                insert into library_organization(user_id,kind,record_id,original_record_id,translation_record_id,
                    version,organization_json,mutation_id,expected_version,updated_at)
                values(?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(), user, kind.name, recordId, recordId.takeIf { kind == LibraryOrganizationKind.ORIGINAL },
                recordId.takeIf { kind == LibraryOrganizationKind.TRANSLATION }, current.version + 1, value,
                request.mutationId, request.expectedVersion, changedAt)
        } else {
            val changed = jdbc.update("""
                update library_organization set version=?,organization_json=?,mutation_id=?,expected_version=?,updated_at=?
                where user_id=? and kind=? and record_id=? and version=?
            """.trimIndent(), current.version + 1, value, request.mutationId, request.expectedVersion, changedAt,
                user, kind.name, recordId, request.expectedVersion)
            check(changed == 1) { "Library organization changed without its transaction lock." }
        }
        return requireNotNull(stored(user, kind, recordId)).view
    }

    private fun requireOwner(user: String, kind: LibraryOrganizationKind, recordId: UUID, lock: Boolean = false) {
        val table = if (kind == LibraryOrganizationKind.ORIGINAL) "source_chapter" else "translation_artifact"
        val suffix = if (lock) " for key share" else ""
        val owned = jdbc.query("select id from $table where id=? and user_id=?$suffix",
            RowMapper { row, _ -> row.getObject("id", UUID::class.java) }, recordId, user).isNotEmpty()
        if (!owned) throw LibraryOrganizationFailure("LIBRARY_ORGANIZATION_NOT_FOUND", 404, "The server document was not found.")
    }

    private fun stored(user: String, kind: LibraryOrganizationKind, recordId: UUID): StoredOrganization? = jdbc.query(
        "select * from library_organization where user_id=? and kind=? and record_id=?", RowMapper { row, _ ->
            StoredOrganization(LibraryOrganizationView(kind, recordId, row.getLong("version"),
                json.readValue(row.getString("organization_json"), LibraryOrganization::class.java), row.getTimestamp("updated_at").toInstant()),
                row.getObject("mutation_id", UUID::class.java), row.getLong("expected_version"))
        }, user, kind.name, recordId).singleOrNull()

    private fun empty(kind: LibraryOrganizationKind, recordId: UUID) = LibraryOrganizationView(kind, recordId, 0, null, null)
    private data class StoredOrganization(val view: LibraryOrganizationView, val mutationId: UUID, val expectedVersion: Long)
}
