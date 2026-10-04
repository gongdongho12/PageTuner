package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.source.webnovel.WebNovelChapterLoadStrategy
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
import com.dongholab.pagetuner.source.webnovel.WtrLabSiteAdapter
import com.dongholab.pagetuner.translation.ContentTranslationRequest
import com.dongholab.pagetuner.translation.ContentTranslationResult
import com.dongholab.pagetuner.translation.ContentTranslationService
import com.dongholab.pagetuner.translation.TranslationProgress
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationSettings
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in integration test whose catalog, detail, and chapter content all come from live WTR-LAB. */
class WtrLabFullFlowLiveTest {
    @Test
    fun liveSearchToOfflineTranslationUsesRealWebResponses() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1")

        var htmlGetCalls = 0
        var readerPostCalls = 0
        val fetchLiveHtml: suspend (String) -> String = { url ->
            htmlGetCalls += 1
            WebNovelHttpClient.fetchText(url)
        }
        val liveAdapter = WtrLabSiteAdapter(
            chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpOnly,
            postReaderJson = { url, body, referer ->
                readerPostCalls += 1
                WebNovelHttpClient.postJson(url, body, referer)
            },
        )
        val registry = WebNovelSiteAdapterRegistry(listOf(liveAdapter))

        // Real server-side search selects a stable public work instead of a local fixture.
        val searchUrl = WtrLabCatalogQueryParams(query = "Sea Survival").buildUrl()
        val catalogSource = WebNovelRemoteBookSource(
            accountId = ACCOUNT_ID,
            endpointUrl = searchUrl,
            fetchHtml = fetchLiveHtml,
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val searchPage = catalogSource.loadCatalogPage(1) {}
        val selectedBook = requireNotNull(
            searchPage.items.firstOrNull { it.identity.remoteId == "novel_51593" },
        ) { "The live WTR-LAB search no longer returns the expected public test novel." }
        assertTrue(selectedBook.title.contains("Sea Survival", ignoreCase = true))
        assertTrue((selectedBook.chapterCount ?: 0) > 1)

        // Real detail HTML provides metadata and the complete synthesized chapter index.
        val bookSource = WebNovelRemoteBookSource(
            accountId = ACCOUNT_ID,
            endpointUrl = selectedBook.downloadUrl,
            fetchHtml = fetchLiveHtml,
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val detail = bookSource.loadNovelDetail()
        val chapters = bookSource.list()
        val selectedChapter = chapters.first()
        assertTrue(detail.totalChapters > 1)
        assertEquals(detail.totalChapters, chapters.size)
        assertEquals(selectedBook.seriesId, selectedChapter.seriesId)
        assertEquals(1, selectedChapter.chapterNumber)

        // Real POST /api/reader/get response is parsed without creating Android WebView.
        val chapterSource = WebNovelRemoteBookSource(
            accountId = ACCOUNT_ID,
            endpointUrl = selectedChapter.downloadUrl,
            fetchHtml = fetchLiveHtml,
            renderedChapterLoader = null,
            adapterRegistry = registry,
        )
        val originalText = chapterSource.download(selectedChapter).toString(Charsets.UTF_8)
        assertTrue(originalText.length > 1_000)
        assertTrue(originalText.contains("Ji Ting"))
        assertFalse(originalText.contains("※"))
        assertEquals(2, htmlGetCalls)
        assertEquals(1, readerPostCalls)

        // Translation is deterministic here; the source content above remains entirely live.
        val translationService = PrefixTranslationService()
        val fieldId = "${selectedChapter.translationKey()}:body"
        val translated = translationService.translate(
            request = ContentTranslationRequest(
                namespace = "web-novel-chapter-v1",
                title = selectedChapter.title,
                fields = listOf(com.dongholab.pagetuner.translation.TranslatableField(fieldId, originalText)),
            ),
            settings = settings(),
        )

        val directory = Files.createTempDirectory("wtr-live-e2e").toFile()
        try {
            OfflineNovelStorageStore.forDirectory(directory).apply {
                saveOriginalChapter(selectedChapter, 1, originalText)
                saveTranslation(
                    item = selectedChapter,
                    chapterNumber = 1,
                    targetLanguage = "ko",
                    translatedText = translated.values.getValue(fieldId),
                    providerId = translated.providerId,
                )
            }
            val restored = requireNotNull(
                OfflineNovelStorageStore.forDirectory(directory).getOfflineChapter(selectedChapter),
            )
            assertEquals(originalText, restored.originalText)
            assertTrue(restored.preferredText("ko").first.startsWith("[live-ko] "))
            assertEquals(2, htmlGetCalls)
            assertEquals(1, readerPostCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun settings() = TranslationSettings(
        providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
        apiKey = "",
        sourceLanguage = "en",
        targetLanguage = "ko",
    )

    private class PrefixTranslationService : ContentTranslationService {
        override val providerId: String = "live-fixture-translator"

        override suspend fun translate(
            request: ContentTranslationRequest,
            settings: TranslationSettings,
            onProgress: suspend (TranslationProgress) -> Unit,
        ): ContentTranslationResult = ContentTranslationResult(
            values = request.fields.associate { it.id to "[live-ko] ${it.text}" },
            sourceLanguage = settings.normalizedSourceLanguage,
            targetLanguage = settings.normalizedTargetLanguage,
            providerId = providerId,
            completedFromCache = false,
        )
    }

    private companion object {
        const val ACCOUNT_ID = "default_wtr_lab"
    }
}
