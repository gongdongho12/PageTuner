package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.TextSegment
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Run explicitly with RUN_LIVE_TRANSLATION_TESTS=1. */
class GoogleWebTranslateLiveTest {
    @Before
    fun requireLiveOptIn() {
        assumeTrue(System.getenv("RUN_LIVE_TRANSLATION_TESTS") == "1")
    }

    @Test
    fun translatesEnglishToKoreanThroughRealEndpoint() = runTest {
        val provider = GoogleWebTranslateHtmlProvider(apiKey = "")
        val result = provider.translate(
            request(
                source = "en",
                target = "ko",
                "The airplane is ready for departure.",
            ),
        ).single().translatedText

        assertFalse(result.equals("The airplane is ready for departure.", ignoreCase = true))
        assertTrue(result.any { it in '\uac00'..'\ud7a3' })
    }

    @Test
    fun translatesMultipleKoreanSegmentsToEnglishInOrder() = runTest {
        val provider = GoogleWebTranslateHtmlProvider(apiKey = "")
        val sourceTexts = listOf("첫 번째 문장입니다.", "두 번째 번역 테스트입니다.")
        val translated = provider.translate(request("ko", "en", *sourceTexts.toTypedArray()))

        assertEquals(listOf("segment-0", "segment-1"), translated.map { it.segmentId })
        assertEquals(2, translated.size)
        assertTrue(translated.all { value -> value.translatedText.any(Char::isLetter) })
        assertTrue(translated.none { value -> value.translatedText in sourceTexts })
    }

    @Test
    fun commonContentServiceTranslatesNamedFieldsThroughRealEndpoint() = runTest {
        val cacheDirectory = Files.createTempDirectory("live-translation-cache").toFile()
        try {
            val service = DefaultContentTranslationService(
                provider = GoogleWebTranslateHtmlProvider(apiKey = ""),
                cache = JsonFileTranslationCache(cacheDirectory.resolve("cache.json")),
            )
            val result = service.translate(
                request = ContentTranslationRequest(
                    namespace = "live-test-v1",
                    title = "Live test",
                    fields = listOf(
                        TranslatableField("book:title", "A Hero Returns"),
                        TranslatableField("book:description", "The hero returned home after a long journey."),
                    ),
                ),
                settings = settings("en", "ko"),
            )

            assertEquals(setOf("book:title", "book:description"), result.values.keys)
            assertTrue(result.values.values.all { text -> text.any { it in '\uac00'..'\ud7a3' } })
            assertTrue(cacheDirectory.resolve("cache.json").isFile)
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private fun request(source: String, target: String, vararg texts: String): TranslationRequest {
        return TranslationRequest(
            sourceLanguage = source,
            targetLanguage = target,
            segments = texts.mapIndexed { index, text ->
                TextSegment("segment-$index", pageIndex = 0, indexInPage = index, text = text)
            },
        )
    }

    private fun settings(source: String, target: String) = TranslationSettings(
        providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
        apiKey = "",
        sourceLanguage = source,
        targetLanguage = target,
        paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
        batchSize = 6,
    )
}
