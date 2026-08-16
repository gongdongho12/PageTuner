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
    fun restoreSelectsParticlesAfterTheFinalTranslatedName() {
        val entries = listOf(
            character,
            BookGlossaryEntry("apu", "A-Pu", "아푸"),
            BookGlossaryEntry("gil", "Gil", "길"),
        )
        val protected = GlossaryTextProcessor.protect(
            "A-Pu은(는) Qin Feng는 만났고 A-Pu이(가) A-Pu을 도왔다. Qin Feng와 Gil으로 갔다.",
            entries,
        )

        assertEquals(
            "아푸는 진풍은 만났고 아푸가 아푸를 도왔다. 진풍과 길로 갔다.",
            GlossaryTextProcessor.restore(protected.text, protected.replacements),
        )
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

    @Test
    fun characterAliasesExposeBoldRangesWithoutEmphasizingOrdinaryTerms() {
        val display = GlossaryTextProcessor.applyTranslatedDisplayAliasesWithRanges(
            text = "아푸 entered the North Hall. 아푸 waved.",
            entries = listOf(
                BookGlossaryEntry("apu", "A-Pu", "아푸", "아푸", GlossaryTermKind.Character),
                BookGlossaryEntry("hall", "North Hall", "북쪽 전당", "전당", GlossaryTermKind.Place),
            ),
        )

        assertEquals("아푸 entered the North Hall. 아푸 waved.", display.text)
        assertEquals(listOf(0..1, 27..28), display.emphasizedRanges)
    }

    @Test
    fun displayAliasesCorrectParticlesAndKeepCharacterBoldRangesAligned() {
        val display = GlossaryTextProcessor.applyTranslatedDisplayAliasesWithRanges(
            text = "진풍는 아푸은(는) 왔다.",
            entries = listOf(
                character,
                BookGlossaryEntry("apu", "A-Pu", "아푸", "아이", GlossaryTermKind.Character),
            ),
        )

        assertEquals("주인공은 아이는 왔다.", display.text)
        assertEquals(listOf(0..2, 5..6), display.emphasizedRanges)
    }

    @Test
    fun particleCorrectionDoesNotRewriteACopulaFollowingAnAlias() {
        assertEquals(
            "주인공이다.",
            GlossaryTextProcessor.applyTranslatedDisplayAliases("진풍이다.", listOf(character)),
        )
    }

    @Test
    fun llmAliasesMergePerBookWithoutOverwritingManualChoices() {
        val manual = BookGlossaryEntry("manual", "A-Pu", "에이푸", "에이푸")
        val merged = BookGlossaryMerger.mergeCharacterAliases(
            glossary = BookGlossary("book-1", listOf(manual)),
            suggestions = listOf(
                CharacterAliasSuggestion("A-Pu", "아푸"),
                CharacterAliasSuggestion("Qin Feng", "진풍"),
                CharacterAliasSuggestion("qin feng", "친펑"),
            ),
        )

        assertEquals(2, merged.entries.size)
        assertEquals("에이푸", merged.entries.first { it.sourceTerm == "A-Pu" }.displayTerm)
        assertEquals("진풍", merged.entries.first { it.sourceTerm == "Qin Feng" }.displayTerm)
    }
}
