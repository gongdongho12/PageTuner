package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationRepositoryTest {
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
}

private class FakeTranslationProvider : TranslationProvider {
    override val id: String = "fake"
    var translatedSegments: Int = 0

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
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
}
