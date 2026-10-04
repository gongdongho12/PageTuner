package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.core.backup.BackupState
import com.dongholab.pagetuner.core.backup.TranslationBackupKey
import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=integration-test-password"])
@AutoConfigureMockMvc
class TranslationPostgresIntegrationTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null
        private val database by lazy {
            ExternalPostgresTestDatabase.fromEnvironment(System.getenv()) ?: run {
                // Starting explicitly keeps Docker the default without hiding an
                // unavailable engine behind a disabled/aborted test condition.
                val container = PostgreSQLContainer<Nothing>("postgres:17-alpine")
                container.start()
                postgres = container
                ExternalPostgresTestDatabase(container.jdbcUrl, container.username, container.password)
            }
        }

        @DynamicPropertySource
        @JvmStatic
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            val selected = database
            registry.add("spring.datasource.url") { selected.url }
            registry.add("spring.datasource.username") { selected.user }
            registry.add("spring.datasource.password") { selected.password }
        }

        @AfterAll
        @JvmStatic
        fun stopOwnedContainer() {
            // An explicitly supplied native instance belongs to the caller.
            postgres?.stop()
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var service: TranslationApplicationService
    @Autowired lateinit var artifacts: TranslationArtifactRepository
    @Autowired lateinit var backups: TranslationBackupRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun clearDatabase() {
        jdbc.update("delete from translation_job_paragraph")
        jdbc.update("delete from translation_job")
        jdbc.update("delete from source_chapter")
        backups.deleteAll()
        artifacts.deleteAll()
    }

    @Test
    fun `save and get retain original metadata and repeated save reuses the record`() {
        val request = sample()
        val saved = save(request)
        assertTrue(saved.created)
        assertEquals(request.toArtifact(), saved.toArtifact())
        assertEquals(request.toArtifact().artifactId, saved.artifactId)
        assertEquals(request.toArtifact().revision, saved.revision)
        assertEquals(request.toArtifact().payloadHash, saved.payloadHash)

        val loaded = load(saved.recordId)
        assertEquals(saved.copy(created = false), loaded)
        assertEquals(request.toArtifact(), loaded.toArtifact())
        assertEquals(saved.copy(created = false), save(request, expectedStatus = 200))
        assertEquals(1L, artifacts.count())

        val stored = artifacts.findById(saved.recordId).orElseThrow()
        assertEquals(request.contentProviderId, stored.contentProviderId)
        assertEquals(request.bookId, stored.bookId)
    }

    @Test
    fun `users cannot read or back up another user's translation`() {
        val alice = save(sample(), "alice")
        mvc.perform(get("/api/v1/translations/${alice.recordId}").with(user("bob")))
            .andExpect(status().isNotFound)
        mvc.perform(
            post("/api/v1/translations/${alice.recordId}/backup-plans")
                .with(user("bob")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"backupAccountId":"drive-account"}"""),
        ).andExpect(status().isNotFound)
        val bob = save(sample(), "bob")
        assertNotEquals(alice.recordId, bob.recordId)
        assertEquals(alice.artifactId, bob.artifactId)
        assertEquals(2L, artifacts.count())
        assertEquals(0L, backups.count())
    }

    @Test
    fun `backup plans reuse active jobs completed backups and failed records`() {
        val saved = save(sample())
        val first = plan(saved.recordId)
        assertEquals(BackupPlanStatus.Enqueued, first.status)
        assertEquals(
            TranslationBackupKey("alice", "drive-account", saved.artifactId, saved.revision, saved.payloadHash).id,
            first.backupKeyId,
        )
        assertEquals(first.copy(status = BackupPlanStatus.ActiveJobReused), plan(saved.recordId))
        val entity = backups.findById(first.backupRecordId).orElseThrow()
        entity.state = BackupState.Verified
        entity.remoteFileId = "remote-file"
        backups.saveAndFlush(entity)
        assertEquals(first.copy(status = BackupPlanStatus.AlreadyBackedUp), plan(saved.recordId))

        entity.state = BackupState.Failed
        backups.saveAndFlush(entity)
        assertEquals(first, plan(saved.recordId))
        assertEquals(BackupState.Queued, backups.findById(first.backupRecordId).orElseThrow().state)
        assertEquals(1L, backups.count())
    }

    @Test
    fun `legacy records require metadata repair but retain saved hashes and backup identity`() {
        val request = sample()
        val saved = save(request)
        // This is the exact data shape V2 leaves for a pre-existing V1 row.
        jdbc.update(
            "update translation_artifact set content_provider_id = null, book_id = null where id = ?",
            saved.recordId,
        )
        mvc.perform(get("/api/v1/translations/${saved.recordId}").with(user("alice")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.detail").value(TranslationMetadataUnavailable().message))

        val legacyPlan = plan(saved.recordId)
        val repaired = save(request, expectedStatus = 200)
        assertEquals(saved.copy(created = false), repaired)
        assertEquals(request.toArtifact(), load(saved.recordId).toArtifact())
        assertEquals(legacyPlan.copy(status = BackupPlanStatus.ActiveJobReused), plan(saved.recordId))
        assertEquals(1L, artifacts.count())
    }

    @Test
    fun `ambiguous canonical identity cannot replace the stored original components`() {
        val request = sample().copy(contentProviderId = "source:region", bookId = "book")
        val saved = save(request)
        val ambiguous = request.copy(contentProviderId = "source", bookId = "region:book")
        assertEquals(request.toArtifact().artifactId, ambiguous.toArtifact().artifactId)
        mvc.perform(
            post("/api/v1/translations").with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(ambiguous)),
        ).andExpect(status().isConflict)
        assertEquals(request.toArtifact(), load(saved.recordId).toArtifact())
        assertEquals(1L, artifacts.count())
    }

    @Test
    fun `concurrent duplicate writes and backup requests create one durable record each`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val saveStart = CountDownLatch(1)
            val saves = (1..4).map {
                executor.submit(Callable {
                    check(saveStart.await(10, TimeUnit.SECONDS))
                    service.save("alice", sample())
                })
            }
            saveStart.countDown()
            val results = saves.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.map { it.recordId }.distinct().size)
            assertEquals(1, results.count { it.created })
            assertEquals(1L, artifacts.count())

            val backupStart = CountDownLatch(1)
            val plans = (1..4).map {
                executor.submit(Callable {
                    check(backupStart.await(10, TimeUnit.SECONDS))
                    service.planBackup("alice", results.first().recordId, PlanBackupRequest("drive-account"))
                })
            }
            backupStart.countDown()
            val planned = plans.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, planned.map { it.backupRecordId }.distinct().size)
            assertEquals(1, planned.count { it.status == BackupPlanStatus.Enqueued })
            assertEquals(1L, backups.count())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `library pages are owner scoped exclude legacy rows and sort revisions deterministically`() {
        val request = sample()
        val firstRevision = save(request)
        val nextRevision = save(request.copy(paragraphs = request.paragraphs.map { it.copy(text = "Revised ${it.text}") }))
        val newest = save(request.copy(bookId = "newest-book"))
        val legacy = save(request.copy(bookId = "legacy-book"))
        val bob = save(request, owner = "bob")
        assertEquals(firstRevision.artifactId, nextRevision.artifactId)
        assertNotEquals(firstRevision.revision, nextRevision.revision)
        val tiedTime = Instant.parse("2026-09-14T01:00:00Z")
        val newTime = Instant.parse("2026-09-14T02:00:00Z")
        for (record in listOf(firstRevision, nextRevision)) {
            jdbc.update("update translation_artifact set created_at = ? where id = ?", Timestamp.from(tiedTime), record.recordId)
        }
        jdbc.update("update translation_artifact set created_at = ? where id = ?", Timestamp.from(newTime), newest.recordId)
        jdbc.update("update translation_artifact set content_provider_id = null, book_id = null where id = ?", legacy.recordId)
        // PostgreSQL UUID ordering compares unsigned bytes; canonical UUID strings have that order.
        val tied = listOf(firstRevision, nextRevision).sortedByDescending { it.recordId.toString() }
        val expected = listOf(newest.copy(createdAt = newTime)) + tied.map { it.copy(createdAt = tiedTime) }

        val firstPage = libraryPage(page = 0, size = 2)
        val secondPage = libraryPage(page = 1, size = 2)
        assertEquals(expected.take(2).map { it.toSummary() }, firstPage.items)
        assertEquals(expected.drop(2).map { it.toSummary() }, secondPage.items)
        assertEquals(3L, firstPage.totalItems)
        assertEquals(2, firstPage.totalPages)
        assertEquals(true, firstPage.hasNext)
        assertEquals(false, secondPage.hasNext)
        assertEquals(firstPage, libraryPage(page = 0, size = 2))
        assertEquals(listOf(bob.toSummary()), libraryPage(owner = "bob").items)
        assertEquals(1L, libraryPage(owner = "bob").totalItems)
        val beyondEnd = libraryPage(page = 2, size = 2)
        assertEquals(emptyList<TranslationSummary>(), beyondEnd.items)
        assertEquals(3L, beyondEnd.totalItems)
        assertEquals(2, beyondEnd.totalPages)
        assertEquals(false, beyondEnd.hasNext)
    }

    @Test
    fun `empty library uses default page size and returns zero totals`() {
        val result = mvc.perform(get("/api/v1/translations").with(user("reader-with-no-records")))
            .andExpect(status().isOk).andReturn()
        val response = objectMapper.readValue(result.response.contentAsByteArray, TranslationListResponse::class.java)
        assertEquals(TranslationListResponse(emptyList(), 0, 12, 0L, 0, false), response)
        assertEquals(TranslationListResponse(emptyList(), 42, 1, 0L, 0, false), libraryPage(page = 42, size = 1))
    }

    @Test
    fun `library rejects unauthenticated malformed and excessive page requests`() {
        mvc.perform(get("/api/v1/translations")).andExpect(status().isUnauthorized)
        for ((page, size) in listOf(
            "-1" to "12", "0" to "0", "0" to "51", "0" to "-1",
            "2147483647" to "50", "2147483648" to "1", "x" to "12", "0" to "x",
        )) {
            mvc.perform(
                get("/api/v1/translations").with(user("alice"))
                    .param("page", page).param("size", size).accept(MediaType.APPLICATION_JSON),
            ).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
        }
    }

    private fun libraryPage(page: Int = 0, size: Int = 12, owner: String = "alice"): TranslationListResponse {
        val result = mvc.perform(
            get("/api/v1/translations").with(user(owner))
                .param("page", page.toString()).param("size", size.toString()),
        ).andExpect(status().isOk).andReturn()
        val json = objectMapper.readTree(result.response.contentAsByteArray)
        assertTrue(json["items"].all { !it.has("paragraphs") && !it.has("paragraphsJson") })
        return objectMapper.treeToValue(json, TranslationListResponse::class.java)
    }

    private fun TranslationResponse.toSummary() = TranslationSummary(
        recordId, contentProviderId, bookId, chapterId, sourceLanguage, targetLanguage,
        translationProviderId, modelId, promptRevision, glossaryRevision, sourceRevision,
        artifactId, revision, payloadHash, createdAt, paragraphs.size,
    )

    private fun save(
        request: SaveTranslationRequest,
        owner: String = "alice",
        expectedStatus: Int = 201,
    ): TranslationResponse {
        val result = mvc.perform(
            post("/api/v1/translations").with(user(owner)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)),
        ).andExpect(status().`is`(expectedStatus)).andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, TranslationResponse::class.java)
    }

    private fun load(recordId: UUID): TranslationResponse {
        val result = mvc.perform(get("/api/v1/translations/$recordId").with(user("alice")))
            .andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, TranslationResponse::class.java)
    }

    private fun plan(recordId: UUID): BackupPlanResponse {
        val result = mvc.perform(
            post("/api/v1/translations/$recordId/backup-plans")
                .with(user("alice")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"backupAccountId":"drive-account"}"""),
        ).andExpect(status().isOk).andReturn()
        return objectMapper.readValue(result.response.contentAsByteArray, BackupPlanResponse::class.java)
    }

    private fun sample() = SaveTranslationRequest(
        contentProviderId = " source:region ",
        bookId = " book:one ",
        chapterId = "chapter:three",
        sourceRevision = "original-revision",
        sourceLanguage = "en",
        targetLanguage = "ko",
        translationProviderId = "translation-provider",
        modelId = "model-v2",
        promptRevision = "prompt-v3",
        glossaryRevision = "glossary-v4",
        paragraphs = listOf(
            TranslatedParagraphRequest("paragraph:1", "첫 번째 번역 문단"),
            TranslatedParagraphRequest("paragraph:2", "Second translated paragraph\nwith a newline."),
        ),
    )

    private fun TranslationResponse.toArtifact() = TranslationArtifact(
        chapter = ChapterIdentity(BookIdentity(contentProviderId, bookId), chapterId),
        sourceRevision = sourceRevision,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        providerId = translationProviderId,
        modelId = modelId,
        promptRevision = promptRevision,
        glossaryRevision = glossaryRevision,
        paragraphs = paragraphs.map { TranslatedParagraph(it.paragraphId, it.text) },
    )
}
