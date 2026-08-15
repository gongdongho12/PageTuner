package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.translation.ContentTranslationRequest
import com.dongholab.pagetuner.translation.ContentTranslationResult
import com.dongholab.pagetuner.translation.ContentTranslationService
import com.dongholab.pagetuner.translation.TranslationProgress
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCatalogTranslationServiceTest {
    @Test
    fun sameChapterIdInDifferentSeriesHasDifferentTranslationKey() {
        val base = RemoteBookItem(
            identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "site", "chapter_1"),
            title = "Same chapter title",
            format = DocumentFormat.TEXT,
            downloadUrl = "https://example.test/chapter-1",
        )

        assertNotEquals(
            base.copy(seriesId = "series-42").translationKey(),
            base.copy(seriesId = "series-99").translationKey(),
        )
    }

    @Test
    fun mapsRemoteItemsToStableNamedFieldsAndBack() = runTest {
        val contentService = FixtureContentTranslationService()
        val service = DefaultRemoteCatalogTranslationService(contentService)
        val item = RemoteBookItem(
            identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "site", "book-42"),
            title = "Original title",
            description = "Original summary",
            format = DocumentFormat.TEXT,
            downloadUrl = "https://example.test/book/42",
        )

        val translated = service.translate(listOf(item), settings()).getValue(item.translationKey())

        assertEquals("Translated title", translated.title)
        assertEquals("Translated summary", translated.description)
        assertEquals("ko", translated.targetLanguage)
        assertEquals("web-catalog-v1", contentService.lastRequest?.namespace)
        assertTrue(contentService.lastRequest?.fields?.any { it.id.endsWith(":book-42:title") } == true)
    }

    private fun settings() = TranslationSettings(
        providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
        apiKey = "",
        targetLanguage = "ko",
    )

    private class FixtureContentTranslationService : ContentTranslationService {
        override val providerId: String = "fixture"
        var lastRequest: ContentTranslationRequest? = null

        override suspend fun translate(
            request: ContentTranslationRequest,
            settings: TranslationSettings,
            onProgress: suspend (TranslationProgress) -> Unit,
        ): ContentTranslationResult {
            lastRequest = request
            val titleId = request.fields.first { it.id.endsWith(":title") }.id
            val descriptionId = request.fields.first { it.id.endsWith(":description") }.id
            return ContentTranslationResult(
                values = mapOf(
                    titleId to "Translated title",
                    descriptionId to "Translated summary",
                ),
                sourceLanguage = "en",
                targetLanguage = "ko",
                providerId = providerId,
                completedFromCache = false,
            )
        }
    }
}
