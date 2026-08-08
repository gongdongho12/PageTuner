package com.dongholab.pagetuner.translation.glossary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTextProcessorTest {
    private val character = BookGlossaryEntry(
        id = "qin-feng",
        sourceTerm = "Qin Feng",
        translatedTerm = "진풍",
        displayTerm = "주인공",
    )

    @Test
    fun protectsAndRestoresACharacterName() {
        val protected = GlossaryTextProcessor.protect("Qin Feng met Qin Feng.", listOf(character))

        assertFalse(protected.text.contains("Qin Feng"))
        assertEquals("진풍 met 진풍.", GlossaryTextProcessor.restore(protected.text, protected.replacements))
    }

    @Test
    fun longestTermWinsAndLatinTermsDoNotReplaceInsideWords() {
        val entries = listOf(
            character,
            BookGlossaryEntry("qin", "Qin", "진"),
        )

        val protected = GlossaryTextProcessor.protect("Qin Feng and Qin, but not Qinling.", entries)
        val restored = GlossaryTextProcessor.restore(protected.text, protected.replacements)

        assertEquals("진풍 and 진, but not Qinling.", restored)
    }

    @Test
    fun displayAliasCanChangeWithoutInvalidatingTranslationCache() {
        val first = BookGlossary("book", listOf(character))
        val second = first.copy(entries = listOf(character.copy(displayTerm = "풍")))

        assertEquals(first.translationFingerprint, second.translationFingerprint)
        assertEquals("주인공 arrived.", GlossaryTextProcessor.applyTranslatedDisplayAliases("진풍 arrived.", first.entries))
        assertNotEquals(
            first.translationFingerprint,
            first.copy(entries = listOf(character.copy(translatedTerm = "친 펑"))).translationFingerprint,
        )
        assertTrue(first.activeEntries.isNotEmpty())
    }
}
