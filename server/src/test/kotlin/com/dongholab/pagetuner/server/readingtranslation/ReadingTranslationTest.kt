package com.dongholab.pagetuner.server.readingtranslation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.server.workflow.*
import com.dongholab.pagetuner.translation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadingTranslationTest {
    private fun chapter(): StoredChapter {
        val content = ChapterContent(ChapterIdentity(BookIdentity("fixture", "book"), "chapter"), "Chapter", "en",
            listOf(ContentParagraph("p-one", 0, "Hello 🌏. Read this page."), ContentParagraph("p-two", 1, "The next page.")))
        return StoredChapter(UUID.fromString("11111111-1111-4111-8111-111111111111"), "fixture", "book", "Book", "", "chapter", "Chapter", "",
            "en", content.sourceRevision, content.paragraphs.map { SourceParagraph(it.paragraphId, it.ordinal, it.text) }, Instant.EPOCH)
    }
    private fun request(chapter: StoredChapter = chapter(), id: UUID = UUID.randomUUID(), fragments: List<ReadingFragment> = listOf(ReadingFragment("p-one", 0, 9))) =
        ReadingTranslationRequest(id, chapter.recordId, chapter.sourceRevision, fragments)

    @Test fun fragmentRangesMustMatchTheOwnedImmutableOriginalAndUtf16Boundaries() {
        val chapter = chapter()
        assertEquals(listOf("Hello 🌏."), request(chapter).validate(chapter))
        for (fragment in listOf(ReadingFragment("missing", 0, 1), ReadingFragment("p-one", 0, 7), ReadingFragment("p-one", 7, 9),
            ReadingFragment("p-one", -1, 4), ReadingFragment("p-one", 1, 999), ReadingFragment("p-one", 1, 1))) {
            assertThrows(IllegalArgumentException::class.java) { request(chapter, fragments = listOf(fragment)).validate(chapter) }
        }
        assertThrows(IllegalArgumentException::class.java) { request(chapter).validate(chapter.copy(sourceRevision = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { request(chapter, fragments = List(65) { ReadingFragment("p-one", 0, 9) }).validate(chapter) }
        assertThrows(IllegalArgumentException::class.java) { ReadingTranslationRequest(UUID.randomUUID(), chapter.recordId, chapter.sourceRevision,
            listOf(ReadingFragment("p-one", 0, 9)), readingWordsPerMinute = 0).validate(chapter) }
    }

    @Test fun actualRuntimeTranslatesOnlyServerExtractedFragmentsAndNeverBuildsAFullArtifact() = runBlocking {
        val chapter = chapter()
        val request = request(chapter, fragments = listOf(ReadingFragment("p-two", 4, 13), ReadingFragment("p-one", 0, 9)))
        val (configuration, key) = WorkflowProviders().resolve(CreateTranslationJobRequest(chapter.recordId, request.providerKind,
            "ko", request.requestId), chapter)
        val seen = mutableListOf<String>()
        val engine = ChapterTranslationEngine(providerFactory = { settings ->
            assertEquals(210, settings.readingWordsPerMinute); assertEquals(TranslationPaceMode.READING, settings.paceMode)
            object : TranslationProvider {
                override val id = "fixture"
                override suspend fun translate(request: TranslationRequest) = request.segments.map {
                    seen.add(it.text); TranslatedSegment(it.id, "번역:${it.text}")
                }
            }
        }, maxRetries = 0)
        val result = RuntimeReadingFragmentTranslator().execute(chapter, request, configuration, key, {}, engine)
        assertEquals(listOf("next page", "Hello 🌏."), seen)
        assertEquals(request.fragments, result.map { ReadingFragment(it.paragraphId, it.start, it.end) })
        assertTrue(result.all { it.text.startsWith("번역:") })
        assertFalse(result.toString().contains("Read this page"))
    }

    @Test fun sourceBudgetIncludesOverlappingRangesAndRejectsOversizeBeforeExtractingLaterRanges() {
        val original = chapter()
        val large = original.copy(paragraphs = listOf(SourceParagraph("large", 0, "x".repeat(1_000_000))))
            .let { it.copy(sourceRevision = it.content().sourceRevision) }
        val exact = listOf(ReadingFragment("large", 0, 12_000), ReadingFragment("large", 1, 12_001))
        assertEquals(24_000, request(large, fragments = exact).validate(large).sumOf { it.length })
        val oversized = exact.dropLast(1) + ReadingFragment("large", 1, 12_002)
        val exceeded = assertThrows(IllegalArgumentException::class.java) { request(large, fragments = oversized).validate(large) }
        assertEquals("Reading request exceeds 24000 source characters.", exceeded.message)

        // A small request can select most of a million-character paragraph repeatedly. The first
        // over-budget range must stop extraction before a later invalid range is ever visited.
        val amplified = (0 until 63).map { ReadingFragment("large", it, 1_000_000) } + ReadingFragment("missing", 0, 1)
        val stoppedEarly = assertThrows(IllegalArgumentException::class.java) { request(large, fragments = amplified).validate(large) }
        assertEquals("Reading request exceeds 24000 source characters.", stoppedEarly.message)
    }

    @Test fun accountIsolationCancellationIdempotencyAndProgressDoNotExposePartialResults() = runBlocking {
        val chapter = chapter(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val source = ReadingSource { owner, _ -> if (owner != "reader") throw WorkflowFailure("SOURCE_NOT_FOUND", 404, "Not found"); chapter }
        val jobs = ReadingTranslationJobs(source, WorkflowProviders()) { _, input, _, _, progress ->
            progress(0); entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            input.fragments.map { ReadingTranslationItem(it.paragraphId, it.start, it.end, "번역문") }
        }
        try {
            val input = request(chapter); jobs.start("reader", input); entered.await()
            assertEquals(input.requestId, jobs.start("reader", input).requestId)
            assertTrue(jobs.get("reader", input.requestId).items.isEmpty())
            assertThrows(WorkflowFailure::class.java) { jobs.get("other", input.requestId) }
            assertThrows(WorkflowFailure::class.java) { jobs.start("other", input) }
            assertThrows(WorkflowFailure::class.java) { jobs.start("reader", request(chapter)) }
            assertThrows(WorkflowFailure::class.java) { jobs.start("reader", request(chapter, input.requestId, listOf(ReadingFragment("p-one", 0, 5)))) }
            jobs.cancel("reader", input.requestId); release.complete(Unit); delay(30)
            assertEquals("CANCELLED", jobs.get("reader", input.requestId).status)
            assertTrue(jobs.get("reader", input.requestId).items.isEmpty())
        } finally { release.complete(Unit); jobs.close() }
    }

    @Test fun completedPreviewHasExplicitScopeAndProviderFailuresAreRedacted() = runBlocking {
        val chapter = chapter(); var fail = false
        val jobs = ReadingTranslationJobs(ReadingSource { _, _ -> chapter }, WorkflowProviders()) { _, input, _, _, progress ->
            if (fail) error("do-not-leak-provider-secret")
            progress(input.fragments.size)
            input.fragments.map { ReadingTranslationItem(it.paragraphId, it.start, it.end, "번역문") }
        }
        try {
            val input = request(chapter); jobs.start("reader", input)
            val done = terminal(jobs, input.requestId)
            assertEquals("COMPLETED", done.status); assertEquals("READING_PREVIEW", done.scope)
            assertEquals(chapter.sourceRevision, done.sourceRevision); assertEquals(input.sourceHash, done.sourceHash)
            assertEquals(1, done.items.size)
            fail = true; val next = request(chapter); jobs.start("reader", next)
            val failed = terminal(jobs, next.requestId)
            assertEquals("TRANSLATION_FAILED", failed.errorCode); assertTrue(failed.items.isEmpty())
            assertFalse(failed.toString().contains("do-not-leak-provider-secret"))
            assertFalse(ReadingTranslationRequest(next.requestId, chapter.recordId, chapter.sourceRevision, next.fragments, apiKey = "do-not-leak-provider-secret").toString().contains("do-not-leak-provider-secret"))
        } finally { jobs.close() }
    }

    @Test fun typedProviderFailuresUseTheSharedCodeWithoutSecretDetails() = runBlocking {
        val chapter = chapter()
        val jobs = ReadingTranslationJobs(ReadingSource { _, _ -> chapter }, WorkflowProviders(emptyMap())) { _, _, _, _, _ ->
            throw TranslationProviderException(TranslationProviderFailure("private-provider", TranslationProviderErrorKind.Authentication, "private-key"))
        }
        try {
            val input = request(chapter); jobs.start("reader", input)
            val failed = terminal(jobs, input.requestId)
            assertEquals("TRANSLATION_AUTHENTICATION_FAILED", failed.errorCode)
            assertTrue(failed.items.isEmpty()); assertFalse(failed.toString().contains("private-"))
        } finally { jobs.close() }
    }

    @Test fun cancelBeforeStartBlocksTheDelayedPostWithoutAProviderCall() {
        val chapter = chapter(); var calls = 0
        val jobs = ReadingTranslationJobs(ReadingSource { _, _ -> chapter }, WorkflowProviders()) { _, _, _, _, _ -> calls++; emptyList() }
        try {
            val input = request(chapter)
            assertThrows(WorkflowFailure::class.java) { jobs.cancel("reader", input.requestId) }
            val failure = assertThrows(WorkflowFailure::class.java) { jobs.start("reader", input) }
            assertEquals("READING_CANCELLED", failure.code); assertEquals(0, calls)
            assertThrows(WorkflowFailure::class.java) { jobs.get("reader", input.requestId) }
        } finally { jobs.close() }
    }

    @Test fun cancellationKeepsAccountAndGlobalSlotsUntilProviderCleanupActuallyFinishes() = runBlocking {
        val chapter = chapter()
        val entered = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val jobs = ReadingTranslationJobs(ReadingSource { _, _ -> chapter }, WorkflowProviders()) { _, input, _, _, _ ->
            calls.incrementAndGet()
            // Model provider cancellation cleanup that cannot finish until its transport releases it.
            withContext(NonCancellable) { entered.send(Unit); release.await() }
            input.fragments.map { ReadingTranslationItem(it.paragraphId, it.start, it.end, "late provider output") }
        }
        try {
            val running = (0 until 8).map { "reader-$it" to request(chapter) }
            running.forEach { (owner, input) ->
                jobs.start(owner, input)
                withTimeout(2_000) { entered.receive() }
                val cancelled = jobs.cancel(owner, input.requestId)
                assertEquals("CANCELLED", cancelled.status)
                assertTrue(cancelled.items.isEmpty())
                val sameAccount = assertThrows(WorkflowFailure::class.java) { jobs.start(owner, request(chapter)) }
                assertEquals("READING_BUSY", sameAccount.code)
                assertEquals(429, sameAccount.httpStatus)
            }
            val next = request(chapter)
            val global = assertThrows(WorkflowFailure::class.java) { jobs.start("reader-after", next) }
            assertEquals("READING_BUSY", global.code)
            assertEquals(8, calls.get())

            release.complete(Unit)
            // Wait for actual coroutine completion, not merely the public CANCELLED response.
            withTimeout(2_000) {
                while (true) {
                    try { jobs.start("reader-after", next); break }
                    catch (error: WorkflowFailure) { assertEquals("READING_BUSY", error.code); delay(5) }
                }
            }
            assertEquals("COMPLETED", terminal(jobs, next.requestId, "reader-after").status)
            assertEquals(9, calls.get())
            running.forEach { (owner, input) ->
                val cancelled = jobs.get(owner, input.requestId)
                assertEquals("CANCELLED", cancelled.status)
                assertTrue(cancelled.items.isEmpty())
            }
        } finally { release.complete(Unit); jobs.close(); entered.close() }
    }

    private suspend fun terminal(jobs: ReadingTranslationJobs, id: UUID, owner: String = "reader") = withTimeout(2_000) {
        var state = jobs.get(owner, id)
        while (state.status in setOf("QUEUED", "RUNNING")) { delay(5); state = jobs.get(owner, id) }
        state
    }
}
