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

    private class CapturingProvider : TranslationProvider {
        override val id = "capture"
        var received: List<TextSegment> = emptyList()

        override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
            received = request.segments
            return request.segments.map { TranslatedSegment(it.id, "번역: ${it.text}") }
        }
    }
}
