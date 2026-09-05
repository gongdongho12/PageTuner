package com.dongholab.pagetuner.core.translation

import com.dongholab.pagetuner.core.content.StableContentHash
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContentTranslationTest {
    private val languages = TranslationLanguages("en", "ko")

    @Test
    fun segmentationMatchesLegacyCacheAndSurvivesSubsetAndReordering() {
        val fields = listOf(TranslatableField("book:title", "a".repeat(401)), TranslatableField("other", "Other"))
        val plan = TranslationFieldSegmenter.create(ContentTranslationRequest("web-catalog-v1", "Display", fields))
        val reordered = TranslationFieldSegmenter.create(ContentTranslationRequest("web-catalog-v1", "Changed", fields.reversed()))
        val subset = TranslationFieldSegmenter.create(ContentTranslationRequest("web-catalog-v1", "Display", fields.take(1)))
        val legacyDocumentId = StableContentHash.sha256("content-translation:web-catalog-v1")
        assertEquals(legacyDocumentId, plan.documentId)
        assertEquals(StableContentHash.sha256("$legacyDocumentId:book:title:0:${"a".repeat(400)}").take(24), plan.segments.first().id)
        assertEquals(listOf(400, 1, 5), plan.segments.map { it.text.length })
        assertEquals(plan.fieldSegments, reordered.fieldSegments)
        assertEquals(plan.fieldSegments["book:title"], subset.fieldSegments["book:title"])
        assertEquals(listOf(0, 1, 2), plan.segments.map { it.ordinal })
    }

    @Test
    fun sourceOrNamespaceChangesInvalidateSegments() {
        val request = ContentTranslationRequest("one", "", listOf(TranslatableField("id", "source")))
        val original = TranslationFieldSegmenter.create(request)
        assertNotEquals(original.segments, TranslationFieldSegmenter.create(request.copy(namespace = "two")).segments)
        assertNotEquals(original.segments, TranslationFieldSegmenter.create(request.copy(fields = listOf(TranslatableField("id", "changed")))).segments)
    }

    @Test
    fun blankFieldsAreIgnoredButDuplicateActiveIdsAreRejected() {
        assertTrue(TranslationFieldSegmenter.create(ContentTranslationRequest("one", "", listOf(TranslatableField("", " ")))).segments.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            TranslationFieldSegmenter.create(ContentTranslationRequest("one", "", listOf(TranslatableField("id", "a"), TranslatableField("id", "b"))))
        }
    }

    @Test
    fun translatesEntireCatalogThroughOnePortCallWithStableFieldNames() = runTest {
        var calls = 0
        val updates = mutableListOf<ContentTranslationProgress>()
        val service = CatalogTranslationService { request, requestedLanguages, progress ->
            calls++
            assertEquals(languages, requestedLanguages)
            assertEquals("web-catalog-v1", request.namespace)
            assertEquals(listOf("book-1:title", "book-1:description", "book-2:title"), request.fields.map { it.id })
            val plan = TranslationFieldSegmenter.create(request)
            assertEquals(3, plan.segments.size)
            progress(ContentTranslationProgress(3, 3, "complete", ""))
            ContentTranslationResult(request.fields.associate { it.id to "번역 ${it.text}" }, "en", "ko", "fixture", false)
        }
        val result = service.translate(listOf(
            CatalogTranslationEntry("book-1", "Same title", "Summary"),
            CatalogTranslationEntry("book-2", "Same title", null),
        ), languages, updates::add)
        assertEquals(1, calls)
        assertEquals(2, result.size)
        assertEquals("번역 Summary", result.getValue("book-1").description)
        assertNull(result.getValue("book-2").description)
        assertEquals(1f, updates.single().fraction)
    }

    @Test
    fun emptyCatalogDoesNotInvokePort() = runTest {
        val service = CatalogTranslationService { _, _, _ -> error("Unexpected call") }
        assertTrue(service.translate(emptyList(), languages).isEmpty())
    }

    @Test
    fun duplicateKeysAreRejectedBeforeCallingProvider() = runTest {
        val service = CatalogTranslationService { _, _, _ -> error("Unexpected call") }
        try {
            service.translate(List(2) { CatalogTranslationEntry("same", "Title", null) }, languages)
            fail("Expected duplicate key rejection")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun cancellationIsPropagatedToCaller() = runTest {
        val service = CatalogTranslationService { _, _, _ -> throw CancellationException("cancelled") }
        try {
            service.translate(listOf(CatalogTranslationEntry("book", "Title", null)), languages)
            fail("Expected cancellation")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }
}
