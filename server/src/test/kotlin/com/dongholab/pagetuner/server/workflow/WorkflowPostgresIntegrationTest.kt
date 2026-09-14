package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.dongholab.pagetuner.source.service.SourceChapterDraft
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=workflow-test-password"])
@Import(WorkflowPostgresIntegrationTest.Configuration::class)
class WorkflowPostgresIntegrationTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null
        private val database by lazy {
            ExternalPostgresTestDatabase.fromEnvironment(System.getenv()) ?: run {
                val container = PostgreSQLContainer<Nothing>("postgres:17-alpine")
                container.start(); postgres = container
                ExternalPostgresTestDatabase(container.jdbcUrl, container.username, container.password)
            }
        }
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.datasource.username") { database.user }
            registry.add("spring.datasource.password") { database.password }
        }
        @AfterAll @JvmStatic fun stop() { postgres?.stop() }
    }
    class TestTranslator : WorkflowTranslator {
        @Volatile var failAfter: Int? = null
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val translated = CopyOnWriteArrayList<String>()
        override suspend fun translate(chapter: ChapterContent, config: JobConfiguration, apiKey: String,
            completed: Map<String, String>, onParagraph: suspend (String, String) -> Unit): List<TranslatedParagraph> {
            val output = completed.toMutableMap()
            for (paragraph in chapter.paragraphs) {
                if (paragraph.paragraphId in output) continue
                if (failAfter != null && output.size >= failAfter!!) throw IllegalStateException("must not expose secret $apiKey")
                gate?.await()
                translated += paragraph.paragraphId
                val text = "번역: ${paragraph.text}"
                onParagraph(paragraph.paragraphId, text)
                output[paragraph.paragraphId] = text
            }
            return chapter.paragraphs.map { TranslatedParagraph(it.paragraphId, output.getValue(it.paragraphId)) }
        }
    }
    @TestConfiguration class Configuration {
        @Bean @Primary fun testTranslator() = TestTranslator()
    }
    @Autowired lateinit var workflow: TranslationWorkflowService
    @Autowired lateinit var chapters: SourceChapterStore
    @Autowired lateinit var jobs: TranslationJobStore
    @Autowired lateinit var translations: TranslationApplicationService
    @Autowired lateinit var translator: TestTranslator
    @Autowired lateinit var jdbc: JdbcTemplate
    private val user = "workflow-reader"

    @BeforeEach fun reset() {
        translator.failAfter = null; translator.gate = null; translator.translated.clear()
        jdbc.update("delete from translation_job_paragraph")
        jdbc.update("delete from translation_job")
        jdbc.update("delete from source_chapter")
        jdbc.update("delete from translation_backup")
        jdbc.update("delete from translation_artifact")
    }
    private fun draft(paragraphs: List<String> = listOf("First paragraph.", "Second paragraph.", "Third paragraph.")) = SourceChapterDraft(
        "test-source", "book", "A book title", "https://example.org/book", "chapter", "A chapter title",
        "https://example.org/book/chapter", "en", paragraphs,
    )
    private fun request(chapter: StoredChapter, id: UUID = UUID.randomUUID(), retry: UUID? = null,
        target: String = "ko", glossary: List<WorkflowGlossaryEntry> = emptyList()) = CreateTranslationJobRequest(
        chapter.recordId, "GOOGLE_CLOUD", target, id, apiKey = "PRIVATE-TEST-CREDENTIAL", glossary = glossary, retryOf = retry,
    )
    private fun awaitJob(id: UUID, status: String): TranslationJobView {
        val until = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < until) {
            val result = workflow.get(user, id)
            if (result.status == status) return result
            if (result.status in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")) fail<Unit>("Expected $status, got $result")
            Thread.sleep(10)
        }
        error("Job did not reach $status: ${workflow.get(user, id)}")
    }

    @Test fun `source snapshots preserve paragraphs reuse identical imports and isolate owners`() {
        val first = chapters.save(user, draft())
        assertEquals(first.recordId, chapters.save(user, draft()).recordId)
        assertEquals(first.sourceRevision, first.content().sourceRevision)
        assertEquals(listOf(0, 1, 2), first.paragraphs.map { it.ordinal })
        val changed = chapters.save(user, draft(listOf("Changed source.")))
        assertNotEquals(first.recordId, changed.recordId)
        assertNotEquals(first.sourceRevision, changed.sourceRevision)
        assertThrows(WorkflowFailure::class.java) { chapters.get("another-reader", first.recordId) }
        assertEquals(2, chapters.list(user, 0, 1).totalPages)
        assertTrue(chapters.list("another-reader", 0, 12).items.isEmpty())
    }

    @Test fun `translation completes to real persisted artifact and repeated request avoids provider work`() {
        val chapter = chapters.save(user, draft())
        val input = request(chapter)
        val first = workflow.submit(user, input)
        val done = awaitJob(first.jobId, "COMPLETED")
        assertEquals(3, done.completedParagraphs)
        val artifact = translations.get(user, requireNotNull(done.translationRecordId))
        assertEquals(chapter.sourceRevision, artifact.sourceRevision)
        assertEquals(chapter.paragraphs.map { it.paragraphId }, artifact.paragraphs.map { it.paragraphId })
        assertEquals("A book title", artifact.bookTitle)
        assertEquals(first.jobId, workflow.submit(user, input).jobId)
        val aliasKey = UUID.randomUUID()
        assertEquals(first.jobId, workflow.submit(user, request(chapter, id = aliasKey)).jobId)
        assertEquals("en", done.settings.sourceLanguage)
        assertThrows(WorkflowFailure::class.java) { workflow.submit(user, request(chapter, id = aliasKey, target = "ja")) }
        assertEquals(3, translator.translated.size)
        assertFalse(jdbc.queryForObject("select settings_json from translation_job where id=?", String::class.java, first.jobId)!!.contains("PRIVATE-TEST-CREDENTIAL"))
        assertThrows(WorkflowFailure::class.java) { workflow.get("another-reader", first.jobId) }
    }

    @Test fun `failed job never publishes partial artifact and retry resumes committed paragraphs`() {
        val chapter = chapters.save(user, draft())
        translator.failAfter = 1
        val failed = awaitJob(workflow.submit(user, request(chapter)).jobId, "FAILED")
        assertEquals(1, failed.completedParagraphs)
        assertNull(failed.translationRecordId)
        assertFalse(failed.errorMessage.orEmpty().contains("PRIVATE-TEST-CREDENTIAL"))
        assertEquals(0L, translations.list(user, 0, 12).totalItems)
        translator.failAfter = null
        val retried = awaitJob(workflow.submit(user, request(chapter, retry = failed.jobId)).jobId, "COMPLETED")
        assertNotEquals(failed.jobId, retried.jobId)
        assertEquals(3, translator.translated.size)
        assertEquals(3, translator.translated.distinct().size)
    }

    @Test fun `cancelling a provider call is owner scoped and cannot publish after cancellation`() {
        val chapter = chapters.save(user, draft())
        val gate = CompletableDeferred<Unit>()
        translator.gate = gate
        val started = workflow.submit(user, request(chapter))
        awaitJob(started.jobId, "RUNNING")
        assertThrows(WorkflowFailure::class.java) { workflow.cancel("another-reader", started.jobId) }
        assertEquals("CANCELLED", workflow.cancel(user, started.jobId).status)
        gate.complete(Unit)
        assertEquals("CANCELLED", workflow.get(user, started.jobId).status)
        assertNull(workflow.get(user, started.jobId).translationRecordId)
    }

    @Test fun `idempotency mismatch and changed glossary cannot reuse the same artifact`() {
        val chapter = chapters.save(user, draft())
        val key = UUID.randomUUID()
        val first = awaitJob(workflow.submit(user, request(chapter, id = key)).jobId, "COMPLETED")
        assertThrows(WorkflowFailure::class.java) { workflow.submit(user, request(chapter, id = key, target = "ja")) }
        val second = awaitJob(workflow.submit(user, request(chapter, glossary = listOf(WorkflowGlossaryEntry("First", "첫")))).jobId, "COMPLETED")
        assertNotEquals(first.translationRecordId, second.translationRecordId)
        assertNotEquals(translations.get(user, first.translationRecordId!!).glossaryRevision, translations.get(user, second.translationRecordId!!).glossaryRevision)
    }

    @Test fun `expired worker lease becomes retryable interrupted rather than forever running`() {
        val chapter = chapters.save(user, draft())
        val gate = CompletableDeferred<Unit>(); translator.gate = gate
        val started = workflow.submit(user, request(chapter))
        awaitJob(started.jobId, "RUNNING")
        jdbc.update("update translation_job set lease_until=now()-interval '1 second' where id=?", started.jobId)
        val interrupted = workflow.get(user, started.jobId)
        assertEquals("INTERRUPTED", interrupted.status)
        assertTrue(interrupted.canRetry)
        gate.complete(Unit)
        assertNull(workflow.get(user, started.jobId).translationRecordId)
    }
}
