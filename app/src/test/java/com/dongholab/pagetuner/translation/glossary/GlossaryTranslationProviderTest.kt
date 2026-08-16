package com.dongholab.pagetuner.translation.glossary

import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.TranslationProvider
import com.dongholab.pagetuner.translation.TranslationRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTranslationProviderTest {
    @Test
    fun providerReceivesProtectedTextAndReturnsFixedSpelling() = runTest {
        val delegate = CapturingProvider()
        val provider = GlossaryTranslationProvider(
            delegate,
            BookGlossary("book-1", listOf(
                BookGlossaryEntry("hero", "Qin Feng", "진풍"),
            )),
        )
        val request = TranslationRequest(
            sourceLanguage = "en",
            targetLanguage = "ko",
            segments = listOf(TextSegment("s1", 0, 0, "Qin Feng entered the palace.")),
        )

        val translated = provider.translate(request)

        assertFalse(delegate.received.single().text.contains("Qin Feng"))
        assertTrue(delegate.received.single().text.contains("PTGLOSSARY"))
        assertEquals("번역: 진풍 entered the palace.", translated.single().translatedText)
        assertTrue(provider.id.startsWith("capture:glossary-"))
    }

    @Test
    fun providerResultCorrectsParticleAfterRestoringProtectedName() = runTest {
        val provider = GlossaryTranslationProvider(
            ParticleProvider(),
            BookGlossary("book-1", listOf(BookGlossaryEntry("hero", "A-Pu", "아푸"))),
        )

        val translated = provider.translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = listOf(TextSegment("s1", 0, 0, "A-Pu entered.")),
            ),
        )

        assertEquals("아푸는 입장했다.", translated.single().translatedText)
    }

    private class CapturingProvider : TranslationProvider {
        override val id = "capture"
        var received: List<TextSegment> = emptyList()

        override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
            received = request.segments
            return request.segments.map { TranslatedSegment(it.id, "번역: ${it.text}") }
        }
    }

    private class ParticleProvider : TranslationProvider {
        override val id = "particle"

        override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
            return request.segments.map { segment ->
                val protectedName = segment.text.substringBefore(' ')
                TranslatedSegment(segment.id, "${protectedName}은(는) 입장했다.")
            }
        }
    }
}
