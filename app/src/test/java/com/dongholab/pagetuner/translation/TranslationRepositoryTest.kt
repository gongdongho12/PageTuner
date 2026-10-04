package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationRepositoryTest {
    @Test
    fun translatesTenPagesInOneProviderRequestAndRestoresPageResults() = runTest {
        val provider = FakeTranslationProvider()
        val repository = TranslationRepository(provider, MemoryTranslationCache())
        val document = com.dongholab.pagetuner.document.ReaderDocument(
            id = "ten-pages",
            title = "Grouped",
            format = com.dongholab.pagetuner.document.DocumentFormat.TEXT,
            pages = (0 until 10).map { pageIndex ->
                com.dongholab.pagetuner.document.ReaderPage(
                    index = pageIndex,
                    segments = listOf(
                        com.dongholab.pagetuner.document.TextSegment(
                            id = "segment-$pageIndex",
                            pageIndex = pageIndex,
                            indexInPage = 0,
                            text = "Page ${pageIndex + 1}",
                        ),
                    ),
                )
            },
        )

        val results = repository.translatePages(document, document.pages, settings())

        assertEquals(1, provider.requests)
        assertEquals(10, provider.translatedSegments)
        assertEquals(10, results.size)
        assertEquals("ko:Page 1", results.first().text)
        assertEquals("ko:Page 10", results.last().text)
    }

    @Test
    fun cachesTranslatedSegmentsForOfflineReuse() = runTest {
        val provider = FakeTranslationProvider()
        val cache = MemoryTranslationCache()
        val repository = TranslationRepository(provider, cache)
        val document = PlainTextDocumentParser.parse(
            title = "Cache",
            rawText = """
                One paragraph.

                Another paragraph.
            """.trimIndent(),
        )
        val settings = TranslationSettings(
            apiKey = "test",
            sourceLanguage = "en",
            targetLanguage = "ko",
            paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
            batchSize = 1,
        )

        val first = repository.translatePage(document, document.pages.first(), settings)
        val second = repository.translatePage(document, document.pages.first(), settings)

        assertEquals(first.text, second.text)
        assertEquals(2, provider.translatedSegments)
        assertEquals(true, second.completedFromCache)
    }

    @Test
    fun reportsAndClearsDocumentCacheStatus() = runTest {
        val provider = FakeTranslationProvider()
        val cache = MemoryTranslationCache()
        val repository = TranslationRepository(provider, cache)
        val document = PlainTextDocumentParser.parse(
            title = "Cache",
            rawText = """
                One paragraph.

                Another paragraph.
            """.trimIndent(),
        )
        val settings = TranslationSettings(
            apiKey = "test",
            sourceLanguage = "en",
            targetLanguage = "ko",
            paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
            batchSize = 1,
        )

        assertEquals(0, repository.cacheStatus(document, settings).cachedSegments)

        repository.translatePage(document, document.pages.first(), settings)
        val saved = repository.cacheStatus(document, settings)

        assertEquals(2, saved.cachedSegments)
        assertEquals(2, saved.totalSegments)
        assertEquals(2, repository.clearDocumentCache(document, settings))
        assertEquals(0, repository.cacheStatus(document, settings).cachedSegments)
    }

    private fun settings() = TranslationSettings(
        apiKey = "test",
        sourceLanguage = "en",
        targetLanguage = "ko",
        paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
    )
}

private class FakeTranslationProvider : TranslationProvider {
    override val id: String = "fake"
    var translatedSegments: Int = 0
    var requests: Int = 0

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        requests += 1
        translatedSegments += request.segments.size
        return request.segments.map { segment ->
            TranslatedSegment(
                segmentId = segment.id,
                translatedText = "ko:${segment.text}",
            )
        }
    }
}

private class MemoryTranslationCache : TranslationCache {
    private val records = mutableMapOf<String, CachedTranslation>()

    override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> {
        return keys.mapNotNull { key -> records[key.id]?.let { key.id to it } }.toMap()
    }

    override suspend fun putAll(records: List<CachedTranslation>) {
        records.forEach { this.records[it.key.id] = it }
    }

    override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int {
        return keys.count { key -> records.remove(key.id) != null }
    }
}
