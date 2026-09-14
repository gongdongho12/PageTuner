package com.dongholab.pagetuner.server.catalogTranslation

import com.dongholab.pagetuner.core.translation.CatalogTranslationEntry
import com.dongholab.pagetuner.server.workflow.WorkflowFailure
import com.dongholab.pagetuner.server.workflow.WorkflowProviders
import com.dongholab.pagetuner.translation.ChapterTranslationEngine
import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.TranslationProvider
import com.dongholab.pagetuner.translation.TranslationRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CatalogTranslationTest {
    private val mapper = jacksonObjectMapper()
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader.getResource("catalog-translations-v1/$name")).readText()
    private fun request() = mapper.readValue<CatalogTranslationRequest>(fixture("request.json"))

    @Test fun sharedFixtureSourceIdentityAndSensitiveRequestProjectionAreStable() {
        val request = request().also { it.validate() }
        val expected = mapper.readTree(fixture("completed-response.json"))
        assertEquals(expected["sourceHash"].asText(), request.sourceHash)
        assertEquals(expected["requestId"].asText(), request.requestId.toString())
        assertFalse(CatalogTranslationRequest(request.requestId, request.items, apiKey = "provider-secret-value").toString().contains("provider-secret-value"))
        assertThrows(IllegalArgumentException::class.java) {
            CatalogTranslationRequest(UUID.randomUUID(), List(25) { CatalogTranslationEntry("book-$it", "Title", null) }).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            CatalogTranslationRequest(UUID.randomUUID(), listOf(CatalogTranslationEntry("book", "Title", "x".repeat(2001)))).validate()
        }
    }

    @Test fun actualSharedFieldMapperAndRuntimeKeepLongDescriptionsAndSameTitlesDistinct() = runBlocking {
        val texts = mutableListOf<String>()
        val engine = ChapterTranslationEngine(providerFactory = { object : TranslationProvider {
            override val id = "test"
            override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
                texts += request.segments.map { it.text }
                return request.segments.map { TranslatedSegment(it.id, "[${it.text}]") }
            }
        } }, maxRetries = 0)
        val input = CatalogTranslationRequest(UUID.randomUUID(), listOf(CatalogTranslationEntry("one", "Same", "a".repeat(801)),
            CatalogTranslationEntry("two", "Same", null)), sourceLanguage = "en")
        val result = RuntimeCatalogTextTranslator(WorkflowProviders()).execute(input, { _, _ -> }, engine)
        assertEquals(listOf("one", "two"), result.map { it.key })
        assertEquals("[Same]", result.first().title)
        assertEquals("[${"a".repeat(400)}][${"a".repeat(400)}][a]", result.first().description)
        assertEquals(5, texts.size)
    }

    @Test fun accountIsolationIdempotencyAndCancellationNeverExposeLateOrPartialResults() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val jobs = CatalogTranslationJobs { request, progress ->
            progress(1, 3); entered.complete(Unit)
            // Simulates a provider which finishes just after cancellation.
            withContext(NonCancellable) { release.await() }
            request.items.map { CatalogTranslationItem(it.key, "Translated", it.description, request.targetLanguage) }
        }
        try {
            val input = request()
            jobs.start("reader", input); entered.await()
            val partial = jobs.get("reader", input.requestId)
            assertEquals(1, partial.completedSegments); assertTrue(partial.items.isEmpty())
            assertThrows(WorkflowFailure::class.java) { jobs.get("other", input.requestId) }
            assertEquals(input.requestId, jobs.start("reader", input).requestId)
            assertThrows(WorkflowFailure::class.java) { jobs.start("reader", CatalogTranslationRequest(UUID.randomUUID(), input.items)) }
            jobs.cancel("reader", input.requestId); release.complete(Unit); delay(30)
            val cancelled = jobs.get("reader", input.requestId)
            assertEquals("CANCELLED", cancelled.status); assertTrue(cancelled.items.isEmpty())
        } finally { release.complete(Unit); jobs.close() }
    }

    @Test fun completedResultsMatchTheContractAndProviderExceptionsAreRedacted() = runBlocking {
        val jobs = CatalogTranslationJobs { input, progress ->
            progress(3, 3)
            if (input.items.first().title == "fail") error("provider-secret-value")
            val expected = mapper.readTree(fixture("completed-response.json"))["items"]
            expected.map { CatalogTranslationItem(it["key"].asText(), it["title"].asText(), it["description"].takeUnless { v -> v.isNull }?.asText(), it["targetLanguage"].asText()) }
        }
        try {
            val input = request(); jobs.start("reader", input)
            val result = terminal(jobs, "reader", input.requestId)
            assertEquals("COMPLETED", result.status); assertEquals(2, result.items.size)
            val bad = CatalogTranslationRequest(UUID.randomUUID(), listOf(CatalogTranslationEntry("bad", "fail", null)))
            jobs.start("reader", bad)
            val failed = terminal(jobs, "reader", bad.requestId)
            assertEquals("FAILED", failed.status); assertEquals("CATALOG_PROVIDER_FAILED", failed.errorCode)
            assertFalse(failed.toString().contains("provider-secret-value")); assertTrue(failed.items.isEmpty())
        } finally { jobs.close() }
    }

    private suspend fun terminal(jobs: CatalogTranslationJobs, username: String, id: UUID) = withTimeout(2_000) {
        var value = jobs.get(username, id)
        while (value.status in setOf("QUEUED", "RUNNING")) { delay(5); value = jobs.get(username, id) }
        value
    }
}
