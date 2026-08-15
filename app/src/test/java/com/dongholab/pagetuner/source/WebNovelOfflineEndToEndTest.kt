package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.source.webnovel.WebNovelChapterLoadStrategy
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
import com.dongholab.pagetuner.source.webnovel.WtrLabSiteAdapter
import com.dongholab.pagetuner.translation.CachedTranslation
import com.dongholab.pagetuner.translation.ContentTranslationRequest
import com.dongholab.pagetuner.translation.DefaultContentTranslationService
import com.dongholab.pagetuner.translation.TranslatableField
import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.TranslationCache
import com.dongholab.pagetuner.translation.TranslationCacheKey
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProvider
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationRequest
import com.dongholab.pagetuner.translation.TranslationSettings
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the production source, parser, translation, and offline-storage boundaries without
 * starting an Activity, Compose, Android WebView, or a real translation provider.
 */
class WebNovelOfflineEndToEndTest {
    @Test
    fun catalogToOfflineTranslatedReaderRunsWithoutTheAppOrWebView() = runTest {
        val provider = RecordingTranslationProvider()
        val translationService = DefaultContentTranslationService(provider, MemoryTranslationCache())
        var readerHttpCalls = 0
        val adapter = WtrLabSiteAdapter(
            chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpOnly,
            postReaderJson = { apiUrl, requestBody, referer ->
                readerHttpCalls += 1
                assertEquals("https://wtr-lab.com/api/reader/get", apiUrl)
                assertTrue(requestBody.contains("\"raw_id\":88774"))
                assertTrue(requestBody.contains("\"chapter_no\":1"))
                assertTrue(referer.endsWith("/chapter-1"))
                readerJson
            },
        )
        val registry = WebNovelSiteAdapterRegistry(listOf(adapter))

        // Catalog -> selected work.
        val catalogSource = WebNovelRemoteBookSource(
            accountId = ACCOUNT_ID,
            endpointUrl = CATALOG_URL,
            fetchHtml = { url ->
                assertEquals("$CATALOG_URL?page=1", url)
                WtrLabDomScraperTest.catalogHtml
            },
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val catalogPage = catalogSource.loadCatalogPage(1) {}
        val selectedBook = catalogPage.items.single()
        assertEquals("God Emperor of Devouring", selectedBook.title)
        assertEquals(2_368, selectedBook.chapterCount)
        assertFalse(selectedBook.seriesId.isNullOrBlank())

        // The catalog translation uses the same common translation service as chapter content.
        val translatedCatalog = DefaultRemoteCatalogTranslationService(translationService)
            .translate(listOf(selectedBook), settings())
            .getValue(selectedBook.translationKey())
        assertTrue(translatedCatalog.title.startsWith("[ko] "))

        // Work -> detail and complete chapter index. One detail HTML load is shared by both calls.
        var detailFetches = 0
        val bookSource = WebNovelRemoteBookSource(
            accountId = ACCOUNT_ID,
            endpointUrl = selectedBook.downloadUrl,
            fetchHtml = {
                detailFetches += 1
                WtrLabDomScraperTest.detailHtml
            },
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val detail = bookSource.loadNovelDetail()
        val chapters = bookSource.list()
        val selectedChapter = chapters.first()
        assertEquals(1, detailFetches)
        assertEquals("God Emperor of Devouring", detail.title)
        assertEquals(3, chapters.size)
        assertEquals(selectedBook.seriesId, selectedChapter.seriesId)
        assertEquals(1, selectedChapter.chapterNumber)

        // Chapter -> lightweight HTTP reader JSON -> clean original text. WebView is unavailable.
        val chapterSource = WebNovelRemoteBookSource(
            accountId = ACCOUNT_ID,
            endpointUrl = selectedChapter.downloadUrl,
            fetchHtml = { error("HTTP reader loading must not fetch or render the chapter page.") },
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val originalText = chapterSource.download(selectedChapter).toString(Charsets.UTF_8)
        assertEquals(1, readerHttpCalls)
        assertTrue(originalText.contains("Ji Ting met Yu Wen at the Shopping Mall before sunrise."))
        assertFalse(originalText.contains("※"))

        // Extracted text is immediately consumable by the same pure Kotlin reader parser.
        val document = PlainTextDocumentParser.parse(selectedChapter.title, originalText)
        assertTrue(document.pageCount > 0)
        assertTrue(document.pages.flatMap { it.segments }.any { it.text.contains("Ji Ting") })

        // Translate once, then verify the stable translation cache avoids a second provider call.
        val bodyFieldId = "${selectedChapter.translationKey()}:body"
        val translationRequest = ContentTranslationRequest(
            namespace = "web-novel-chapter-v1",
            title = selectedChapter.title,
            fields = listOf(TranslatableField(bodyFieldId, originalText)),
        )
        val translated = translationService.translate(translationRequest, settings())
        val callsAfterTranslation = provider.calls
        val cachedTranslation = translationService.translate(translationRequest, settings())
        assertTrue(translated.values.getValue(bodyFieldId).startsWith("[ko] "))
        assertEquals(callsAfterTranslation, provider.calls)
        assertTrue(cachedTranslation.completedFromCache)

        // Persist original + translation, recreate the store, and read entirely offline.
        val directory = Files.createTempDirectory("web-novel-e2e").toFile()
        try {
            OfflineNovelStorageStore.forDirectory(directory).apply {
                saveOriginalChapter(selectedChapter, 1, originalText)
                saveTranslation(
                    item = selectedChapter,
                    chapterNumber = 1,
                    targetLanguage = "ko",
                    translatedText = translated.values.getValue(bodyFieldId),
                    providerId = translated.providerId,
                )
            }

            val restored = requireNotNull(
                OfflineNovelStorageStore.forDirectory(directory).getOfflineChapter(selectedChapter),
            )
            assertEquals(originalText, restored.originalText)
            assertTrue(restored.preferredText("ko").first.startsWith("[ko] "))
            assertEquals(setOf("en", "ko"), buildSet {
                add(restored.sourceLanguage)
                addAll(restored.translations.keys)
            })
            assertEquals(callsAfterTranslation, provider.calls)
            assertEquals(1, readerHttpCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun settings() = TranslationSettings(
        providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
        apiKey = "",
        sourceLanguage = "en",
        targetLanguage = "ko",
        paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
        batchSize = 24,
    )

    private class RecordingTranslationProvider : TranslationProvider {
        override val id: String = "fixture-translator"
        var calls = 0

        override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
            calls += 1
            return request.segments.map { segment ->
                TranslatedSegment(segment.id, "[ko] ${segment.text}")
            }
        }
    }

    private class MemoryTranslationCache : TranslationCache {
        private val records = mutableMapOf<String, CachedTranslation>()

        override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> =
            keys.mapNotNull { key -> records[key.id]?.let { key.id to it } }.toMap()

        override suspend fun putAll(records: List<CachedTranslation>) {
            records.forEach { record -> this.records[record.key.id] = record }
        }

        override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int =
            keys.count { records.remove(it.id) != null }
    }

    private companion object {
        const val ACCOUNT_ID = "wtr-e2e"
        const val CATALOG_URL = "https://wtr-lab.com/en/novel-list"

        val readerJson = """
            {
              "success": true,
              "chapter": {
                "raw_id": 88774,
                "order": 1,
                "title": "Chapter 1: The Awakening"
              },
              "data": {
                "data": {
                  "title": "Chapter 1: The Awakening",
                  "body": [
                    "※0⛬ met ※1⛬ at the ※2⛬ before sunrise.",
                    "The long journey began with enough chapter text to pass production validation and create a readable offline document without any Android UI or WebView runtime."
                  ],
                  "glossary_data": {
                    "terms": [
                      ["Ji Ting", "季听"],
                      ["Yu Wen", "喻闻"],
                      ["Shopping Mall", "商场"]
                    ]
                  }
                }
              }
            }
        """.trimIndent()
    }
}
