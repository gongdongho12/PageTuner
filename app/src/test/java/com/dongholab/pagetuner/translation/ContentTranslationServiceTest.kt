package com.dongholab.pagetuner.translation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class ContentTranslationServiceTest {
    @Test
    fun translatesAndReassemblesNamedFieldsThroughBoundedSegments() = runTest {
        val provider = RecordingProvider()
        val service = DefaultContentTranslationService(provider, MemoryTranslationCache())
        val longBody = "a".repeat(950)

        val result = service.translate(
            request = ContentTranslationRequest(
                namespace = "test-content",
                title = "Test",
                fields = listOf(
                    TranslatableField("title", "hello"),
                    TranslatableField("body", longBody),
                ),
            ),
            settings = settings(),
        )

        assertEquals("[hello]", result.values.getValue("title"))
        assertEquals(3, provider.seenSegments.count { it.text.startsWith("a") })
        assertTrue(provider.seenSegments.all { it.text.length <= 400 })
        assertEquals(longBody.length + 6, result.values.getValue("body").replace("\n\n", "").length)
        assertEquals("recording", result.providerId)
    }

    @Test
    fun secondRequestUsesStableCacheWithoutCallingProviderAgain() = runTest {
        val provider = RecordingProvider()
        val cache = MemoryTranslationCache()
        val service = DefaultContentTranslationService(provider, cache)
        val request = ContentTranslationRequest(
            namespace = "catalog",
            title = "Catalog",
            fields = listOf(TranslatableField("book-1:title", "Original title")),
        )

        val first = service.translate(request, settings())
        val callsAfterFirst = provider.calls
        val second = service.translate(request, settings())

        assertEquals(1, callsAfterFirst)
        assertEquals(callsAfterFirst, provider.calls)
        assertTrue(second.completedFromCache)
        assertEquals(first.values, second.values)
    }

    @Test
    fun cacheIsSeparatedByTargetLanguage() = runTest {
        val provider = RecordingProvider()
        val service = DefaultContentTranslationService(provider, MemoryTranslationCache())
        val request = ContentTranslationRequest(
            namespace = "languages",
            title = "Languages",
            fields = listOf(TranslatableField("field", "text")),
        )

        service.translate(request, settings(targetLanguage = "ko"))
        service.translate(request, settings(targetLanguage = "ja"))

        assertEquals(2, provider.calls)
    }

    @Test
    fun rejectsDuplicateFieldIdsBeforeCallingProvider() {
        val provider = RecordingProvider()
        val service = DefaultContentTranslationService(provider, MemoryTranslationCache())

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                service.translate(
                    ContentTranslationRequest(
                        namespace = "duplicates",
                        title = "Duplicates",
                        fields = listOf(
                            TranslatableField("same", "one"),
                            TranslatableField("same", "two"),
                        ),
                    ),
                    settings(),
                )
            }
        }
        assertEquals(0, provider.calls)
    }

    private fun settings(targetLanguage: String = "ko") = TranslationSettings(
        providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
        apiKey = "",
        sourceLanguage = "en",
        targetLanguage = targetLanguage,
        paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
        batchSize = 24,
    )

    private class RecordingProvider : TranslationProvider {
        override val id: String = "recording"
        var calls: Int = 0
        val seenSegments = mutableListOf<com.dongholab.pagetuner.document.TextSegment>()

        override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
            calls += 1
            seenSegments += request.segments
            return request.segments.map { TranslatedSegment(it.id, "[${it.text}]") }
        }
    }

    private class MemoryTranslationCache : TranslationCache {
        private val values = mutableMapOf<String, CachedTranslation>()

        override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> {
            return keys.mapNotNull { key -> values[key.id]?.let { key.id to it } }.toMap()
        }

        override suspend fun putAll(records: List<CachedTranslation>) {
            records.forEach { values[it.key.id] = it }
        }

        override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int {
            return keys.count { values.remove(it.id) != null }
        }
    }
}
