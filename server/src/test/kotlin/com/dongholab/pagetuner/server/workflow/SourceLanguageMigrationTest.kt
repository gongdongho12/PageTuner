package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.server.notes.*
import com.dongholab.pagetuner.server.progress.*
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.testcontainers.containers.PostgreSQLContainer

/** Only a freshly named test schema is created or dropped; no application schema is cleaned. */
class SourceLanguageMigrationTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null
        private val database by lazy {
            ExternalPostgresTestDatabase.fromEnvironment(System.getenv()) ?: run {
                val container = PostgreSQLContainer<Nothing>("postgres:17-alpine")
                container.start(); postgres = container
                ExternalPostgresTestDatabase(container.jdbcUrl, container.username, container.password)
            }
        }
        @AfterAll @JvmStatic fun stop() { postgres?.stop() }
    }

    @Test fun `V13 upgrade preserves original UUID all metadata and foreign keys while separating languages`() {
        val schema = "source_language_test_${UUID.randomUUID().toString().replace("-", "")}".also {
            require(it.matches(Regex("source_language_test_[0-9a-f]{32}")))
        }
        fun migration() = Flyway.configure().dataSource(database.url, database.user, database.password)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
        migration().target("13").load().migrate()
        DriverManager.getConnection(database.url, database.user, database.password).use { connection ->
            connection.schema = schema
            val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
            val json = jacksonObjectMapper().findAndRegisterModules()
            val owner = "migration-reader"
            val id = UUID.randomUUID()
            val created = Instant.parse("2026-01-02T03:04:05Z")
            val input = UploadedChapterRequest("book:書", "Book", "chapter|one", "Chapter", "en", listOf(SourceParagraph("p1", 0, "Same source😀")))
            val original = StoredChapter(id, "uploaded-document", input.bookId, input.bookTitle, "", input.chapterId, input.chapterTitle,
                "", input.sourceLanguage, "", input.paragraphs, created)
            val revision = original.content().sourceRevision
            val digest = StableContentHash.sha256(json.writeValueAsString(listOf(original.providerId, original.bookId, original.chapterId)))
            try {
                jdbc.update("""
                    insert into source_chapter(id,user_id,identity_key,provider_id,book_id,book_title,book_url,chapter_id,chapter_title,
                        chapter_url,source_language,source_revision,paragraphs_json,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(), id, owner, digest, original.providerId, original.bookId, original.bookTitle, original.bookUrl,
                    original.chapterId, original.chapterTitle, original.chapterUrl, original.sourceLanguage, revision,
                    json.writeValueAsString(original.paragraphs), Timestamp.from(created))
                val progress = ReadingProgressService(jdbc)
                progress.put(owner, ReadingProgressKind.ORIGINAL, id,
                    PutReadingProgressRequest(0, UUID.randomUUID(), ReadingProgressAnchor("p1", 2)))
                val notes = ReadingNoteService(jdbc, json)
                notes.put(owner, ReadingProgressKind.ORIGINAL, id, UUID.randomUUID(), PutReadingNoteRequest(0, UUID.randomUUID(), false,
                    ReadingNoteInput(ReadingNoteKind.NOTE, "Remember", "Existing note", ReadingProgressAnchor("p1", 0), null, created)))
                jdbc.update("""
                    insert into library_organization(user_id,kind,record_id,original_record_id,version,organization_json,mutation_id,expected_version,updated_at)
                    values(?,'ORIGINAL',?,?,1,?,?,0,?)
                """.trimIndent(), owner, id, id, "{\"folder\":\"Existing\",\"tags\":[\"keep\"],\"favorite\":true}", UUID.randomUUID(), Timestamp.from(created))
                jdbc.update("""
                    insert into translation_job(id,user_id,idempotency_key,request_hash,chapter_record_id,provider_kind,target_language,
                        settings_json,worker_id,lease_until,status,completed_paragraphs,total_paragraphs,created_at,updated_at)
                    values(?,?,?,?,?,'GOOGLE_WEB','ko','{}',?,?,'CANCELLED',0,1,?,?)
                """.trimIndent(), UUID.randomUUID(), owner, UUID.randomUUID(), "a".repeat(64), id, UUID.randomUUID(),
                    Timestamp.from(created), Timestamp.from(created), Timestamp.from(created))
                val preservedTables = listOf("source_chapter", "reading_progress", "reading_note_document", "reading_note_change",
                    "reading_note_current", "library_organization", "translation_job")
                val before = preservedTables.associateWith { jdbc.queryForList("select * from $it") }

                migration().target("14").load().migrate()

                preservedTables.forEach { assertEquals(before.getValue(it), jdbc.queryForList("select * from $it"), it) }
                val store = SourceChapterStore(jdbc, json)
                assertEquals(id, store.upload(owner, input).recordId)
                assertEquals(created, store.get(owner, id).createdAt)
                val other = store.upload(owner, input.copy(sourceLanguage = "ko"))
                assertNotEquals(id, other.recordId)
                assertEquals("en", store.get(owner, id).sourceLanguage)
                assertEquals("ko", other.sourceLanguage)
                assertEquals(revision, other.sourceRevision)
                assertEquals(0L, progress.get(owner, ReadingProgressKind.ORIGINAL, other.recordId).version)
                assertTrue(notes.changes(owner, ReadingProgressKind.ORIGINAL, other.recordId).items.isEmpty())
                assertEquals(1L, jdbc.queryForObject("select count(*) from translation_job where chapter_record_id=?", Long::class.java, id))
                assertEquals(0L, jdbc.queryForObject("select count(*) from library_organization where record_id=?", Long::class.java, other.recordId))
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("drop schema \"$schema\" cascade") }
            }
        }
    }
}
