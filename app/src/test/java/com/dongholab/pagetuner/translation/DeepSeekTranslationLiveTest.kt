package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.glossary.CharacterAliasSuggestion
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in paid API test. The local Debug .env key is injected through BuildConfig. */
class DeepSeekTranslationLiveTest {
    @Test
    fun discoversAndUsesCharacterAliasInOneRealRequest() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_DEEPSEEK_TESTS") == "1")
        assumeTrue(TranslationRuntimeSecrets.hasLocalDeepSeekKey)
        var aliases = emptyList<CharacterAliasSuggestion>()
        val provider = DeepSeekTranslationProvider(
            apiKey = TranslationRuntimeSecrets.deepSeekApiKey,
            endpoint = TranslationRuntimeSecrets.deepSeekApiUrl,
            model = TranslationRuntimeSecrets.deepSeekModel,
            onCharacterAliases = { aliases = it },
        )

        val translated = provider.translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = listOf(
                    TextSegment(
                        id = "alias-line",
                        pageIndex = 0,
                        indexInPage = 0,
                        text = "A-Pu opened the door. A-Pu greeted the village chief.",
                    ),
                ),
            ),
        ).single().translatedText

        val character = aliases.firstOrNull { it.sourceTerm.equals("A-Pu", ignoreCase = true) }
        assertTrue(character != null)
        assertTrue(character!!.alias.any { it in '\uAC00'..'\uD7A3' })
        assertTrue(translated.contains(character.alias))
        println("LIVE_DEEPSEEK_ALIAS_EVIDENCE source=${character.sourceTerm} alias=${character.alias} used=true")
    }

    @Test
    fun translatesTenReaderPagesInOneRealRequest() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_DEEPSEEK_TESTS") == "1")
        assumeTrue(TranslationRuntimeSecrets.hasLocalDeepSeekKey)
        val source = (0 until 10).map { pageIndex ->
            TextSegment(
                id = "page-$pageIndex",
                pageIndex = pageIndex,
                indexInPage = 0,
                text = "This is reader page ${pageIndex + 1}, prepared for offline reading.",
            )
        }

        val translated = liveProvider().translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = source,
            ),
        )

        assertEquals(source.map(TextSegment::id), translated.map(TranslatedSegment::segmentId))
        assertEquals(10, translated.size)
        assertTrue(translated.all { value -> value.translatedText.any { it in '\uAC00'..'\uD7A3' } })
        println("LIVE_DEEPSEEK_BATCH_EVIDENCE pages=10 requests=1 results=${translated.size} korean=true")
    }

    @Test
    fun translatesRealEnglishSegmentsToKorean() = runTest {
        assumeTrue(System.getenv("RUN_LIVE_DEEPSEEK_TESTS") == "1")
        assumeTrue(TranslationRuntimeSecrets.hasLocalDeepSeekKey)

        val provider = liveProvider()
        val source = listOf(
            TextSegment(
                id = "line-1",
                pageIndex = 0,
                indexInPage = 0,
                text = "The cabin lights dimmed as the plane crossed the clouds.",
            ),
            TextSegment(
                id = "line-2",
                pageIndex = 0,
                indexInPage = 1,
                text = "She opened the novel and continued reading offline.",
            ),
        )

        val translated = provider.translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = source,
            ),
        )

        assertEquals(source.map { it.id }, translated.map { it.segmentId })
        assertEquals(2, translated.size)
        assertTrue(translated.all { it.translatedText.isNotBlank() })
        assertTrue(translated.all { segment -> segment.translatedText.any { it in '\uAC00'..'\uD7A3' } })

        println(
            buildString {
                appendLine("LIVE_DEEPSEEK_EVIDENCE")
                appendLine("model=${TranslationRuntimeSecrets.deepSeekModel}")
                appendLine("segments=${translated.size}")
                appendLine("korean=${translated.all { value -> value.translatedText.any { it in '\uAC00'..'\uD7A3' } }}")
                translated.forEachIndexed { index, value ->
                    appendLine("translation${index + 1}=${value.translatedText}")
                }
            }.trimEnd(),
        )
    }

    private fun liveProvider() = DeepSeekTranslationProvider(
        apiKey = TranslationRuntimeSecrets.deepSeekApiKey,
        endpoint = TranslationRuntimeSecrets.deepSeekApiUrl,
        model = TranslationRuntimeSecrets.deepSeekModel,
    )
}
